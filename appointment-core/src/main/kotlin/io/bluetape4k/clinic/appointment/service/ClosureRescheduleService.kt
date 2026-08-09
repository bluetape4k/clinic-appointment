package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.model.service.AvailableSlot
import io.bluetape4k.clinic.appointment.model.service.SlotQuery
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * 임시휴진 시 영향받는 예약의 재배정을 처리하는 서비스.
 *
 * 1. 영향받는 예약을 PENDING_RESCHEDULE로 전환
 * 2. 각 예약에 대해 재배정 후보 슬롯 탐색
 * 3. 관리자가 후보를 선택하면 새 예약 생성 + 원래 예약 RESCHEDULED 처리
 *
 * @param slotCalculationService 재배정 후보 슬롯 계산 서비스
 * @param appointmentRepository 예약 Repository
 * @param rescheduleCandidateRepository 재배정 후보 Repository
 * @param stateHistoryRepository 예약 상태 이력 Repository
 * @param notificationWriter tenant-scoped 재배정 command의 알림 outbox 연결 port
 * @param statusEventWriter 상태 전이 event intent를 기록하는 필수 port
 * @param clinicRepository 병원 소유권 검증 Repository
 * @param findAvailableSlots 후보 계산 함수. 기본값은 [slotCalculationService]를 사용합니다.
 */
class ClosureRescheduleService(
    private val slotCalculationService: SlotCalculationService,
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val rescheduleCandidateRepository: RescheduleCandidateRepository = RescheduleCandidateRepository(),
    private val stateHistoryRepository: AppointmentStateHistoryRepository = AppointmentStateHistoryRepository(),
    private val doctorRepository: DoctorRepository = DoctorRepository(),
    private val notificationWriter: AppointmentRescheduleNotificationWriter,
    private val statusEventWriter: AppointmentStatusEventWriter,
    private val clinicRepository: ClinicRepository = ClinicRepository(),
    private val findAvailableSlots: (SlotQuery) -> List<AvailableSlot> = slotCalculationService::findAvailableSlots,
) {
    companion object: KLogging() {
        private val ACTIVE_STATUSES = AppointmentState.ACTIVE_STATUSES
        private const val MAX_SEARCH_DAYS = 30
        private const val MAX_AFFECTED_APPOINTMENTS = 100
        private const val MAX_SLOT_CALCULATIONS = 3_000
        private const val MAX_TOTAL_CANDIDATES = 2_000
        private const val LEGACY_CONFIRM_CORRELATION_ID = "legacy-reschedule-confirm"
        private const val LEGACY_AUTO_CORRELATION_ID = "legacy-reschedule-auto"
    }

    private data class SlotCacheKey(
        val scope: TenantClinicScope,
        val doctorId: Long,
        val treatmentTypeId: Long,
        val date: LocalDate,
    )

    /**
     * 임시휴진 선언 시 해당 날짜의 활성 예약을 PENDING_RESCHEDULE로 전환하고
     * 각 예약에 대해 재배정 후보를 탐색합니다.
     *
     * @param scope 검증된 테넌트-병원 범위
     * @param closureDate 휴진 날짜
     * @param searchDays 후보 탐색 일수 (기본 7일)
     * @return 영향받은 예약 ID → 후보 목록
     */
    fun processClosureReschedule(
        scope: TenantClinicScope,
        closureDate: LocalDate,
        searchDays: Int = 7,
        commandContext: AppointmentCommandContext = AppointmentCommandContext.root("legacy-closure-reschedule"),
    ): Map<Long, List<RescheduleCandidateRecord>> =
        run {
            searchDays.requireInRange(1, MAX_SEARCH_DAYS, "searchDays")
            val preflightStarted = System.nanoTime()
            val affected = transaction {
                requireNotNull(clinicRepository.findByIdAndTenant(scope.clinicId, scope.tenantGroupId)) {
                    "Clinic ${scope.clinicId} does not belong to tenant ${scope.tenantGroupId}"
                }
                val ids = appointmentRepository.probeActiveIdsByClinicAndDate(
                    scope = scope,
                    date = closureDate,
                    activeStatuses = ACTIVE_STATUSES,
                    limit = MAX_AFFECTED_APPOINTMENTS + 1,
                )
                if (ids.size > MAX_AFFECTED_APPOINTMENTS) {
                    log.warn { "closure_reschedule code=affected_limit_rejected affected=${ids.size} searchDays=$searchDays" }
                    throw IllegalArgumentException("Affected appointment limit exceeded")
                }
                appointmentRepository.findActiveByClinicAndDate(
                    scope = scope,
                    date = closureDate,
                    activeStatuses = ACTIVE_STATUSES,
                    limit = MAX_AFFECTED_APPOINTMENTS + 1,
                ).also { snapshot ->
                    check(snapshot.mapNotNull { it.id }.toSet() == ids.toSet()) {
                        "Affected appointment snapshot changed during preflight"
                    }
                }
            }
            if (affected.isEmpty()) return@run emptyMap()

            val slotCalculationCount = affected.size.toLong() * searchDays
            if (slotCalculationCount > MAX_SLOT_CALCULATIONS) {
                log.warn {
                    "closure_reschedule code=slot_calculation_limit_rejected " +
                        "affected=${affected.size} searchDays=$searchDays calculations=$slotCalculationCount"
                }
                throw IllegalArgumentException("Slot calculation limit exceeded")
            }

            val slotCache = mutableMapOf<SlotCacheKey, List<AvailableSlot>>()
            val precomputed = linkedMapOf<Long, List<RescheduleCandidateRecord>>()
            var totalCandidates = 0
            val precomputeStarted = System.nanoTime()
            for (appointment in affected) {
                val appointmentId = appointment.id.requireNotNull("appointment.id")
                var priority = 0
                val candidates = buildList {
                    for (dayOffset in 1..searchDays) {
                        val candidateDate = closureDate.plusDays(dayOffset.toLong())
                        val key = SlotCacheKey(scope, appointment.doctorId, appointment.treatmentTypeId, candidateDate)
                        val slots = slotCache.getOrPut(key) {
                            findAvailableSlots(
                                SlotQuery(
                                    scope = scope,
                                    doctorId = appointment.doctorId,
                                    treatmentTypeId = appointment.treatmentTypeId,
                                    date = candidateDate,
                                )
                            )
                        }
                        for (slot in slots) {
                            totalCandidates++
                            if (totalCandidates > MAX_TOTAL_CANDIDATES) {
                                log.warn {
                                    "closure_reschedule code=candidate_limit_rejected " +
                                        "affected=${affected.size} searchDays=$searchDays candidates=$totalCandidates"
                                }
                                throw IllegalArgumentException("Reschedule candidate limit exceeded")
                            }
                            add(
                                RescheduleCandidateRecord(
                                    originalAppointmentId = appointmentId,
                                    candidateDate = candidateDate,
                                    startTime = slot.startTime,
                                    endTime = slot.endTime,
                                    doctorId = appointment.doctorId,
                                    priority = priority++,
                                )
                            )
                        }
                    }
                }
                precomputed[appointmentId] = candidates
            }
            val precomputeDurationMillis = (System.nanoTime() - precomputeStarted) / 1_000_000

            try {
                val result = transaction {
                    val current = appointmentRepository.findActiveByClinicAndDate(
                        scope = scope,
                        date = closureDate,
                        activeStatuses = ACTIVE_STATUSES,
                        limit = MAX_AFFECTED_APPOINTMENTS + 1,
                    )
                    check(current.size <= MAX_AFFECTED_APPOINTMENTS) {
                        "Affected appointment limit exceeded during write validation"
                    }
                    val currentById = current.associateBy { it.id.requireNotNull("appointment.id") }
                    check(current.size == affected.size && affected.all { snapshot ->
                        val id = snapshot.id.requireNotNull("appointment.id")
                        currentById[id]?.let { it.version == snapshot.version && it.status == snapshot.status } == true
                    }) {
                        log.warn { "closure_reschedule code=snapshot_conflict affected=${affected.size}" }
                        "Appointment snapshot changed before closure mutation"
                    }

                    val committed = linkedMapOf<Long, List<RescheduleCandidateRecord>>()
                    for (appointment in affected) {
                        val appointmentId = appointment.id.requireNotNull("appointment.id")
                        check(
                            appointmentRepository.updateLegacyStatus(
                                scope = scope,
                                appointmentId = appointmentId,
                                expectedVersion = appointment.version,
                                newStatus = AppointmentState.PENDING_RESCHEDULE,
                            )
                        ) { "Appointment changed concurrently during closure reschedule" }

                        val updated = appointmentRepository.findByIdAndScope(appointmentId, scope)
                            ?: error("Appointment is unavailable after closure status update")
                        check(updated.version == appointment.version + 1L) {
                            "Appointment version did not advance during closure reschedule"
                        }
                        check(updated.status == AppointmentState.PENDING_RESCHEDULE) {
                            "Appointment status did not advance during closure reschedule"
                        }
                        stateHistoryRepository.save(
                            AppointmentStateHistoryRecord(
                                appointmentId = appointmentId,
                                fromState = appointment.status,
                                toState = AppointmentState.PENDING_RESCHEDULE,
                                reason = "임시휴진으로 인한 재배정",
                            )
                        )
                        statusEventWriter.statusChanged(
                            scope = scope,
                            appointment = updated,
                            fromState = appointment.status,
                            toState = AppointmentState.PENDING_RESCHEDULE,
                            commandContext = commandContext,
                        )
                        committed[appointmentId] = precomputed.getValue(appointmentId).map {
                            rescheduleCandidateRepository.save(it, scope)
                        }
                    }
                    committed
                }
                val totalDurationMillis = (System.nanoTime() - preflightStarted) / 1_000_000
                log.info {
                    "closure_reschedule code=committed affected=${result.size} candidates=$totalCandidates " +
                        "searchDays=$searchDays precomputeDurationMs=$precomputeDurationMillis " +
                        "totalDurationMs=$totalDurationMillis"
                }
                result
            } catch (failure: RuntimeException) {
                val totalDurationMillis = (System.nanoTime() - preflightStarted) / 1_000_000
                log.warn {
                    "closure_reschedule code=rollback failure_code=closure_transaction_failed " +
                        "correlation_id=${commandContext.correlationId.value} " +
                        "affected=${affected.size} candidates=$totalCandidates " +
                        "searchDays=$searchDays totalDurationMs=$totalDurationMillis"
                }
                throw failure
            }
        }

    /**
     * 휴진 재배정을 예약 하나씩 처리하고 callback으로 진행률을 알립니다.
     *
     * ## 동작 / 계약
     * - 예약마다 별도 transaction에서 처리하므로 예약 사이에 DB connection을 해제합니다.
     *   [onProgress]는 각 예약 transaction이 commit된 후에 호출하며 DB connection을
     *   보유한 동안에는 호출하지 않습니다. 따라서 callback 안에서 network I/O(예: SSE flush)를
     *   수행하는 호출자도 안전하게 사용할 수 있습니다.
     * - 종결 신호는 내보내지 않으며, 완료 신호는 호출자가 책임집니다.
     * - 상태 업데이트와 상태 이력 기록은 예약별 처리 전에 하나의 공유 transaction에서 수행합니다.
     *
     * @param scope 검증된 tenant-clinic scope
     * @param closureDate clinic 휴진 날짜
     * @param searchDays 대체 slot을 탐색할 일수(기본값 7)
     * @param onProgress 각 예약 transaction이 commit된 후에 (appointmentId, candidateCount)와 함께 호출하는 callback
     * @return 처리한 예약의 총 건수
     */
    fun streamClosureReschedule(
        scope: TenantClinicScope,
        closureDate: LocalDate,
        searchDays: Int = 7,
        onProgress: (appointmentId: Long, candidateCount: Int) -> Unit,
    ): Int {
        require(searchDays > 0) { "searchDays must be positive" }
        val affected = transaction {
            requireNotNull(clinicRepository.findByIdAndTenant(scope.clinicId, scope.tenantGroupId)) {
                "Clinic ${scope.clinicId} does not belong to tenant ${scope.tenantGroupId}"
            }
            appointmentRepository.findActiveByClinicAndDate(scope, closureDate, ACTIVE_STATUSES)
        }

        if (affected.isEmpty()) return 0

        var processed = 0
        for (appointment in affected) {
            ensureNotInterrupted()
            val appointmentId = appointment.id.requireNotNull("appointment.id")
            // 예약별 트랜잭션으로 처리하여 onProgress callback 전에 DB connection을 반환합니다.
            val candidateCount = transaction {
                ensureNotInterrupted()
                if (!appointmentRepository.updateLegacyStatus(
                        scope = scope,
                        appointmentId = appointmentId,
                        expectedVersion = appointment.version,
                        newStatus = AppointmentState.PENDING_RESCHEDULE,
                    )
                ) {
                    return@transaction null
                }
                stateHistoryRepository.save(
                    AppointmentStateHistoryRecord(
                        appointmentId = appointmentId,
                        fromState = appointment.status,
                        toState = AppointmentState.PENDING_RESCHEDULE,
                        reason = "임시휴진으로 인한 재배정",
                    )
                )
                var count = 0
                var priority = 0
                for (dayOffset in 1..searchDays) {
                    ensureNotInterrupted()
                    val candidateDate = closureDate.plusDays(dayOffset.toLong())
                    val slots = slotCalculationService.findAvailableSlots(
                        SlotQuery(
                            scope = scope,
                            doctorId = appointment.doctorId,
                            treatmentTypeId = appointment.treatmentTypeId,
                            date = candidateDate,
                        )
                    )
                    for (slot in slots) {
                        rescheduleCandidateRepository.save(
                            RescheduleCandidateRecord(
                                originalAppointmentId = appointmentId,
                                candidateDate = candidateDate,
                                startTime = slot.startTime,
                                endTime = slot.endTime,
                                doctorId = appointment.doctorId,
                                priority = priority,
                            ),
                            scope,
                        )
                        count++
                        priority++
                    }
                }
                count
            }
            if (candidateCount == null) continue
            // DB connection을 반환했으므로 network I/O를 포함한 onProgress를 안전하게 호출할 수 있습니다.
            onProgress(appointmentId, candidateCount)
            processed++
        }
        return processed
    }

    private fun ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("Closure reschedule was interrupted")
        }
    }

    /** 검증된 범위 안에서 재배정 후보를 선택하여 확정합니다. */
    fun confirmReschedule(scope: TenantClinicScope, candidateId: Long, originalAppointmentId: Long): Long =
        confirmReschedule(
            scope = scope,
            candidateId = candidateId,
            originalAppointmentId = originalAppointmentId,
            commandContext = AppointmentCommandContext.root(LEGACY_CONFIRM_CORRELATION_ID),
        )

    /** 검증된 command context와 함께 원본 예약의 재배정을 확정합니다. */
    fun confirmReschedule(
        scope: TenantClinicScope,
        candidateId: Long,
        originalAppointmentId: Long,
        commandContext: AppointmentCommandContext,
    ): Long = transaction {
        confirmRescheduleInTransaction(scope, candidateId, originalAppointmentId, commandContext)
    }

    /**
     * 자동 재배정: 가장 높은 우선순위(가장 가까운 날짜/시간)의 후보를 자동 선택합니다.
     *
     * @param scope 검증된 테넌트-병원 범위
     * @param originalAppointmentId 원래 예약 ID
     * @return 새로 생성된 예약 ID, 후보가 없으면 null
     */
    fun autoReschedule(scope: TenantClinicScope, originalAppointmentId: Long): Long? =
        autoReschedule(
            scope = scope,
            originalAppointmentId = originalAppointmentId,
            commandContext = AppointmentCommandContext.root(LEGACY_AUTO_CORRELATION_ID),
        )

    /** 검증된 command context와 함께 가장 우선순위가 높은 후보를 확정합니다. */
    fun autoReschedule(
        scope: TenantClinicScope,
        originalAppointmentId: Long,
        commandContext: AppointmentCommandContext,
    ): Long? = transaction {
        val best = rescheduleCandidateRepository.findBestCandidate(originalAppointmentId, scope)
            ?: return@transaction null
        confirmRescheduleInTransaction(
            scope = scope,
            candidateId = best.id.requireNotNull("best.id"),
            originalAppointmentId = originalAppointmentId,
            commandContext = commandContext,
        )
    }

    private fun confirmRescheduleInTransaction(
        scope: TenantClinicScope,
        candidateId: Long,
        originalAppointmentId: Long,
        commandContext: AppointmentCommandContext,
    ): Long {
        val candidate = rescheduleCandidateRepository.findByIdAndScope(candidateId, originalAppointmentId, scope)
            ?: throw IllegalArgumentException("Reschedule candidate not found: $candidateId")
        val original = appointmentRepository.findByIdAndScope(candidate.originalAppointmentId, scope)
            ?: throw IllegalArgumentException("Original appointment not found: ${candidate.originalAppointmentId}")

        val candidateDoctor = doctorRepository.findByIdAndScope(candidate.doctorId, scope)
            ?: throw IllegalArgumentException("Candidate doctor not found: ${candidate.doctorId}")
        require(candidateDoctor.clinicId == original.clinicId) {
            "Candidate doctor ${candidate.doctorId} does not belong to appointment clinic ${original.clinicId}"
        }

        val appointmentRecord = AppointmentRecord(
            clinicId = original.clinicId,
            doctorId = candidate.doctorId,
            treatmentTypeId = original.treatmentTypeId,
            equipmentId = original.equipmentId,
            consultationTopicId = original.consultationTopicId,
            consultationMethod = original.consultationMethod,
            rescheduleFromId = original.id,
            patientName = original.patientName,
            patientPhone = original.patientPhone,
            memberId = original.memberId,
            appointmentDate = candidate.candidateDate,
            startTime = candidate.startTime,
            endTime = candidate.endTime,
            status = AppointmentState.CONFIRMED,
        )

        val newAppointment = appointmentRepository.save(appointmentRecord)
        val originalId = original.id.requireNotNull("original.id")
        check(
            appointmentRepository.updateLegacyStatus(
                scope = scope,
                appointmentId = originalId,
                expectedVersion = original.version,
                newStatus = AppointmentState.RESCHEDULED,
            )
        ) {
            "Original appointment changed concurrently"
        }
        stateHistoryRepository.save(
            AppointmentStateHistoryRecord(
                appointmentId = originalId,
                fromState = original.status,
                toState = AppointmentState.RESCHEDULED,
                reason = "재배정 확정",
            )
        )
        check(rescheduleCandidateRepository.markSelected(candidateId, originalId, scope) == 1) {
            "Reschedule candidate changed concurrently"
        }
        val updatedOriginal = appointmentRepository.findByIdAndScope(originalId, scope)
            ?: error("Original appointment is unavailable after reschedule")
        notificationWriter.rescheduled(
            tenantGroupId = scope.tenantGroupId,
            original = updatedOriginal,
            replacement = newAppointment,
            version = updatedOriginal.version,
            commandContext = commandContext,
        )

        return newAppointment.id.requireNotNull("newAppointment.id")
    }
}

/**
 * `appointment-core`가 알림 모듈에 의존하지 않으면서 caller transaction을 공유하는 port다.
 */
fun interface AppointmentRescheduleNotificationWriter {
    fun rescheduled(
        tenantGroupId: Long,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        version: Long,
    )

    /** 새 command context를 전달하는 확장 경로. 기존 4-인자 구현과 호환됩니다. */
    fun rescheduled(
        tenantGroupId: Long,
        original: AppointmentRecord,
        replacement: AppointmentRecord,
        version: Long,
        commandContext: AppointmentCommandContext,
    ) = rescheduled(tenantGroupId, original, replacement, version)
}

/** 상태 전이와 durable event intent를 caller transaction에 연결하는 dependency-neutral port다. */
fun interface AppointmentStatusEventWriter {
    fun statusChanged(
        scope: TenantClinicScope,
        appointment: AppointmentRecord,
        fromState: AppointmentState,
        toState: AppointmentState,
        commandContext: AppointmentCommandContext,
    )
}

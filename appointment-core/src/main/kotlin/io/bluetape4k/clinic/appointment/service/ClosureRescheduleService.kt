package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.model.service.SlotQuery
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
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
 */
class ClosureRescheduleService(
    private val slotCalculationService: SlotCalculationService,
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val rescheduleCandidateRepository: RescheduleCandidateRepository = RescheduleCandidateRepository(),
    private val stateHistoryRepository: AppointmentStateHistoryRepository = AppointmentStateHistoryRepository(),
    private val doctorRepository: DoctorRepository = DoctorRepository(),
    private val notificationWriter: AppointmentRescheduleNotificationWriter? = null,
    private val clinicRepository: ClinicRepository = ClinicRepository(),
) {
    companion object: KLogging() {
        private val ACTIVE_STATUSES = AppointmentState.ACTIVE_STATUSES
    }

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
    ): Map<Long, List<RescheduleCandidateRecord>> =
        transaction {
            require(searchDays > 0) { "searchDays must be positive" }
            requireNotNull(clinicRepository.findByIdAndTenant(scope.clinicId, scope.tenantGroupId)) {
                "Clinic ${scope.clinicId} does not belong to tenant ${scope.tenantGroupId}"
            }
            val affected = appointmentRepository.findActiveByClinicAndDate(scope, closureDate, ACTIVE_STATUSES)
            if (affected.isEmpty()) return@transaction emptyMap()

            for (appointment in affected) {
                check(
                    appointmentRepository.updateLegacyStatus(
                        scope = scope,
                        appointmentId = appointment.id.requireNotNull("appointment.id"),
                        expectedVersion = appointment.version,
                        newStatus = AppointmentState.PENDING_RESCHEDULE,
                    )
                ) {
                    "Appointment changed concurrently during closure reschedule"
                }
                stateHistoryRepository.save(
                    AppointmentStateHistoryRecord(
                        appointmentId = appointment.id.requireNotNull("appointment.id"),
                        fromState = appointment.status,
                        toState = AppointmentState.PENDING_RESCHEDULE,
                        reason = "임시휴진으로 인한 재배정",
                    )
                )
            }

            val result = mutableMapOf<Long, List<RescheduleCandidateRecord>>()

            for (appointment in affected) {
                val appointmentId = appointment.id.requireNotNull("appointment.id")
                val candidates = mutableListOf<RescheduleCandidateRecord>()
                var priority = 0

                for (dayOffset in 1..searchDays) {
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
                        val rcRecord = RescheduleCandidateRecord(
                            originalAppointmentId = appointmentId,
                            candidateDate = candidateDate,
                            startTime = slot.startTime,
                            endTime = slot.endTime,
                            doctorId = appointment.doctorId,
                            priority = priority,
                        )
                        val saved = rescheduleCandidateRepository.save(rcRecord, scope)
                        candidates.add(saved)
                        priority++
                    }
                }
                result[appointmentId] = candidates
            }
            result
        }

    /**
     * Processes closure reschedule one appointment at a time and reports progress via callback.
     *
     * ## Behavior / Contract
     * - Each appointment is processed in its own transaction so the DB connection is released
     *   between appointments. [onProgress] is called AFTER each per-appointment transaction commits,
     *   never while a DB connection is held. This is safe for callers that perform network I/O
     *   (e.g. SSE flush) inside the callback.
     * - Does NOT emit a terminal signal; the caller is responsible for signalling completion.
     * - Status-update and state-history writes use a single shared transaction before per-appointment processing.
     *
     * @param scope verified tenant-clinic scope
     * @param closureDate date of the clinic closure
     * @param searchDays number of days to search for alternative slots (default 7)
     * @param onProgress called AFTER each appointment's transaction commits, with (appointmentId, candidateCount)
     * @return total number of appointments processed
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
            // Per-appointment transaction — releases DB connection before onProgress callback
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
            // DB connection released — safe to call onProgress with network I/O
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
        transaction {
            confirmRescheduleInTransaction(scope, candidateId, originalAppointmentId)
        }

    /**
     * 자동 재배정: 가장 높은 우선순위(가장 가까운 날짜/시간)의 후보를 자동 선택합니다.
     *
     * @param scope 검증된 테넌트-병원 범위
     * @param originalAppointmentId 원래 예약 ID
     * @return 새로 생성된 예약 ID, 후보가 없으면 null
     */
    fun autoReschedule(scope: TenantClinicScope, originalAppointmentId: Long): Long? =
        transaction {
            val best = rescheduleCandidateRepository.findBestCandidate(originalAppointmentId, scope)
                ?: return@transaction null
            confirmRescheduleInTransaction(scope, best.id.requireNotNull("best.id"), originalAppointmentId)
        }

    private fun confirmRescheduleInTransaction(
        scope: TenantClinicScope,
        candidateId: Long,
        originalAppointmentId: Long,
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
        notificationWriter?.rescheduled(
            tenantGroupId = scope.tenantGroupId,
            original = updatedOriginal,
            replacement = newAppointment,
            version = updatedOriginal.version,
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
}

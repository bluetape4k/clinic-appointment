package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.model.service.SlotQuery
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate

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
 */
class ClosureRescheduleService(
    private val slotCalculationService: SlotCalculationService,
    private val appointmentRepository: AppointmentRepository = AppointmentRepository(),
    private val rescheduleCandidateRepository: RescheduleCandidateRepository = RescheduleCandidateRepository(),
    private val stateHistoryRepository: AppointmentStateHistoryRepository = AppointmentStateHistoryRepository(),
    private val doctorRepository: DoctorRepository = DoctorRepository(),
) {
    companion object: KLogging() {
        private val ACTIVE_STATUSES = AppointmentState.ACTIVE_STATUSES
    }

    /**
     * 임시휴진 선언 시 해당 날짜의 활성 예약을 PENDING_RESCHEDULE로 전환하고
     * 각 예약에 대해 재배정 후보를 탐색합니다.
     *
     * @param clinicId 병원 ID
     * @param closureDate 휴진 날짜
     * @param searchDays 후보 탐색 일수 (기본 7일)
     * @return 영향받은 예약 ID → 후보 목록
     */
    fun processClosureReschedule(
        clinicId: Long,
        closureDate: LocalDate,
        searchDays: Int = 7,
    ): Map<Long, List<RescheduleCandidateRecord>> =
        transaction {
            val affected = appointmentRepository.findActiveByClinicAndDate(clinicId, closureDate, ACTIVE_STATUSES)
            if (affected.isEmpty()) return@transaction emptyMap()

            appointmentRepository.updateStatusByClinicAndDate(
                clinicId,
                closureDate,
                ACTIVE_STATUSES,
                AppointmentState.PENDING_RESCHEDULE
            )

            for (appointment in affected) {
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
                        SlotQuery(clinicId, appointment.doctorId, appointment.treatmentTypeId, candidateDate)
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
                        val saved = rescheduleCandidateRepository.save(rcRecord)
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
     * @param clinicId clinic to reschedule for
     * @param closureDate date of the clinic closure
     * @param searchDays number of days to search for alternative slots (default 7)
     * @param onProgress called AFTER each appointment's transaction commits, with (appointmentId, candidateCount)
     * @return total number of appointments processed
     */
    fun streamClosureReschedule(
        clinicId: Long,
        closureDate: LocalDate,
        searchDays: Int = 7,
        onProgress: (appointmentId: Long, candidateCount: Int) -> Unit,
    ): Int {
        val affected = transaction {
            val list = appointmentRepository.findActiveByClinicAndDate(clinicId, closureDate, ACTIVE_STATUSES)
            if (list.isEmpty()) return@transaction emptyList()

            appointmentRepository.updateStatusByClinicAndDate(
                clinicId, closureDate, ACTIVE_STATUSES, AppointmentState.PENDING_RESCHEDULE
            )
            for (appointment in list) {
                stateHistoryRepository.save(
                    AppointmentStateHistoryRecord(
                        appointmentId = appointment.id.requireNotNull("appointment.id"),
                        fromState = appointment.status,
                        toState = AppointmentState.PENDING_RESCHEDULE,
                        reason = "임시휴진으로 인한 재배정",
                    )
                )
            }
            list
        }

        if (affected.isEmpty()) return 0

        var processed = 0
        for (appointment in affected) {
            val appointmentId = appointment.id.requireNotNull("appointment.id")
            // Per-appointment transaction — releases DB connection before onProgress callback
            val candidateCount = transaction {
                var count = 0
                var priority = 0
                for (dayOffset in 1..searchDays) {
                    val candidateDate = closureDate.plusDays(dayOffset.toLong())
                    val slots = slotCalculationService.findAvailableSlots(
                        SlotQuery(clinicId, appointment.doctorId, appointment.treatmentTypeId, candidateDate)
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
                            )
                        )
                        count++
                        priority++
                    }
                }
                count
            }
            // DB connection released — safe to call onProgress with network I/O
            onProgress(appointmentId, candidateCount)
            processed++
        }
        return processed
    }

    /**
     * 관리자가 재배정 후보를 선택하여 확정합니다.
     * 원래 예약은 RESCHEDULED로 전환하고, 새 예약을 생성합니다.
     *
     * @param candidateId 선택한 후보 ID
     * @param originalAppointmentId 원본 예약 ID — 후보가 이 예약에 속하는지 검증
     * @return 새로 생성된 예약 ID
     */
    internal fun confirmReschedule(candidateId: Long, originalAppointmentId: Long): Long =
        transaction {
            confirmRescheduleInTransaction(candidateId, originalAppointmentId, tenantGroupId = null)
        }

    /**
     * Tenant-scoped reschedule confirmation for API callers.
     */
    fun confirmReschedule(candidateId: Long, originalAppointmentId: Long, tenantGroupId: Long): Long =
        transaction {
            confirmRescheduleInTransaction(candidateId, originalAppointmentId, tenantGroupId)
        }

    /**
     * 자동 재배정: 가장 높은 우선순위(가장 가까운 날짜/시간)의 후보를 자동 선택합니다.
     *
     * @param originalAppointmentId 원래 예약 ID
     * @return 새로 생성된 예약 ID, 후보가 없으면 null
     */
    internal fun autoReschedule(originalAppointmentId: Long): Long? =
        transaction {
            val best = rescheduleCandidateRepository.findBestCandidate(originalAppointmentId)
                ?: return@transaction null
            confirmRescheduleInTransaction(best.id.requireNotNull("best.id"), originalAppointmentId, tenantGroupId = null)
        }

    /**
     * Tenant-scoped automatic reschedule for API callers.
     */
    fun autoReschedule(originalAppointmentId: Long, tenantGroupId: Long): Long? =
        transaction {
            val best = rescheduleCandidateRepository.findBestCandidate(originalAppointmentId)
                ?: return@transaction null
            confirmRescheduleInTransaction(best.id.requireNotNull("best.id"), originalAppointmentId, tenantGroupId)
        }

    private fun confirmRescheduleInTransaction(
        candidateId: Long,
        originalAppointmentId: Long,
        tenantGroupId: Long?,
    ): Long {
        val candidate = rescheduleCandidateRepository.findByIdOrNull(candidateId)
            ?: throw IllegalArgumentException("Reschedule candidate not found: $candidateId")

        require(candidate.originalAppointmentId == originalAppointmentId) {
            "Candidate $candidateId does not belong to appointment $originalAppointmentId"
        }

        val original = if (tenantGroupId == null) {
            appointmentRepository.findByIdOrNull(candidate.originalAppointmentId)
        } else {
            appointmentRepository.findByIdAndTenant(candidate.originalAppointmentId, tenantGroupId)
        }
            ?: throw IllegalArgumentException("Original appointment not found: ${candidate.originalAppointmentId}")

        if (tenantGroupId != null) {
            val candidateDoctor = doctorRepository.findByIdAndTenant(candidate.doctorId, tenantGroupId)
                ?: throw IllegalArgumentException("Candidate doctor not found: ${candidate.doctorId}")
            require(candidateDoctor.clinicId == original.clinicId) {
                "Candidate doctor ${candidate.doctorId} does not belong to appointment clinic ${original.clinicId}"
            }
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
            patientExternalId = original.patientExternalId,
            appointmentDate = candidate.candidateDate,
            startTime = candidate.startTime,
            endTime = candidate.endTime,
            status = AppointmentState.CONFIRMED,
        )

        val newAppointment = appointmentRepository.save(appointmentRecord)
        appointmentRepository.updateStatus(original.id.requireNotNull("original.id"), AppointmentState.RESCHEDULED)
        stateHistoryRepository.save(
            AppointmentStateHistoryRecord(
                appointmentId = original.id,
                fromState = original.status,
                toState = AppointmentState.RESCHEDULED,
                reason = "재배정 확정",
            )
        )
        rescheduleCandidateRepository.markSelected(candidateId)

        return newAppointment.id.requireNotNull("newAppointment.id")
    }
}

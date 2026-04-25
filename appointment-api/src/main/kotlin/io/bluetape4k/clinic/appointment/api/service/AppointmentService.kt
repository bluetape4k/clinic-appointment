package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.event.AppointmentDomainEvent
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentEvent
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 예약 API 유스케이스 서비스.
 *
 * @param appointmentRepository 예약 Repository
 * @param stateMachine 예약 상태 전이 검증기
 * @param eventPublisher 예약 도메인 이벤트 발행기
 * @param stateHistoryRepository 예약 상태 이력 Repository
 */
@Service
class AppointmentService(
    private val appointmentRepository: AppointmentRepository,
    private val stateMachine: AppointmentStateMachine,
    private val eventPublisher: ApplicationEventPublisher,
    private val stateHistoryRepository: AppointmentStateHistoryRepository,
) {
    companion object : KLogging()

    fun getByDateRange(clinicId: Long, startDate: LocalDate, endDate: LocalDate): List<AppointmentRecord> {
        log.debug { "getByDateRange: clinicId=$clinicId, $startDate..$endDate" }
        return transaction { appointmentRepository.findByClinicAndDateRange(clinicId, startDate..endDate) }
    }

    fun getById(id: Long): AppointmentRecord {
        log.debug { "getById: id=$id" }
        return transaction { appointmentRepository.findByIdOrNull(id) }
            ?: throw NoSuchElementException("Appointment not found: $id")
    }

    fun create(request: CreateAppointmentRequest): AppointmentRecord {
        log.debug { "create: patient=${request.patientName}" }
        val record = AppointmentRecord(
            clinicId = request.clinicId,
            doctorId = request.doctorId,
            treatmentTypeId = request.treatmentTypeId,
            equipmentId = request.equipmentId,
            patientName = request.patientName,
            patientPhone = request.patientPhone,
            appointmentDate = request.appointmentDate,
            startTime = request.startTime,
            endTime = request.endTime,
            status = AppointmentState.REQUESTED,
        )
        val saved = transaction { appointmentRepository.save(record) }
        eventPublisher.publishEvent(
            AppointmentDomainEvent.Created(
                appointmentId = saved.id!!,
                clinicId = saved.clinicId,
            )
        )
        return saved
    }

    suspend fun updateStatus(id: Long, targetStatus: String, reason: String?): AppointmentRecord {
        log.debug { "updateStatus: id=$id, target=$targetStatus" }
        val record = transaction { appointmentRepository.findByIdOrNull(id) }
            ?: throw NoSuchElementException("Appointment not found: $id")

        val currentState = record.status
        val event = parseEvent(targetStatus, reason)
        val nextState = stateMachine.transition(currentState, event)

        transaction {
            appointmentRepository.updateStatus(id, nextState)
            stateHistoryRepository.save(
                AppointmentStateHistoryRecord(
                    appointmentId = id,
                    fromState = currentState,
                    toState = nextState,
                    reason = reason,
                )
            )
        }

        eventPublisher.publishEvent(
            AppointmentDomainEvent.StatusChanged(
                appointmentId = id,
                clinicId = record.clinicId,
                fromState = currentState.name,
                toState = nextState.name,
                reason = reason,
            )
        )

        return transaction { appointmentRepository.findByIdOrNull(id) }!!
    }

    suspend fun cancel(id: Long): AppointmentRecord {
        log.debug { "cancel: id=$id" }
        val record = transaction { appointmentRepository.findByIdOrNull(id) }
            ?: throw NoSuchElementException("Appointment not found: $id")

        val currentState = record.status
        stateMachine.transition(currentState, AppointmentEvent.Cancel(reason = "Cancelled by user"))

        transaction {
            appointmentRepository.updateStatus(id, AppointmentState.CANCELLED)
            stateHistoryRepository.save(
                AppointmentStateHistoryRecord(
                    appointmentId = id,
                    fromState = currentState,
                    toState = AppointmentState.CANCELLED,
                    reason = "Cancelled by user",
                )
            )
        }

        eventPublisher.publishEvent(
            AppointmentDomainEvent.Cancelled(
                appointmentId = id,
                clinicId = record.clinicId,
                reason = "Cancelled by user",
            )
        )

        return transaction { appointmentRepository.findByIdOrNull(id) }!!
    }
}

internal fun parseEvent(targetStatus: String, reason: String? = null): AppointmentEvent = when (targetStatus) {
    "REQUESTED" -> AppointmentEvent.Request
    "CONFIRMED" -> AppointmentEvent.Confirm
    "CHECKED_IN" -> AppointmentEvent.CheckIn
    "IN_PROGRESS" -> AppointmentEvent.StartTreatment
    "COMPLETED" -> AppointmentEvent.Complete
    "CANCELLED" -> AppointmentEvent.Cancel(reason = reason ?: "Cancelled")
    "NO_SHOW" -> AppointmentEvent.MarkNoShow
    "PENDING_RESCHEDULE" -> AppointmentEvent.RequestReschedule(reason = reason ?: "Reschedule requested")
    "RESCHEDULED" -> AppointmentEvent.ConfirmReschedule
    "PENDING" -> AppointmentEvent.Reschedule
    else -> throw IllegalArgumentException("Unknown target status: $targetStatus")
}

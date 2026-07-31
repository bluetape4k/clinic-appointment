package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.notification.AppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.event.AppointmentDomainEvent
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.model.dto.AppointmentIdempotencyRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistoryRecord
import io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentEvent
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
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
    private val idempotencyRepository: AppointmentIdempotencyRepository,
    private val idempotencyProperties: AppointmentIdempotencyProperties,
    private val idempotencyClock: Clock,
    private val clinicRepository: ClinicRepository,
    private val notificationWriter: AppointmentNotificationWriter,
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

    fun getById(id: Long, tenantGroupId: Long): AppointmentRecord {
        log.debug { "getById: id=$id, tenantGroupId=$tenantGroupId" }
        return transaction { appointmentRepository.findByIdAndTenant(id, tenantGroupId) }
            ?: throw NoSuchElementException("Appointment not found: $id")
    }

    fun create(
        tenantGroupId: Long,
        request: CreateAppointmentRequest,
        idempotencyKey: String?,
        resolution: MemberResolution,
    ): AppointmentCreationResult {
        val key = idempotencyKey?.also(::validateIdempotencyKey)
        if (key == null) {
            val saved = transaction {
                appointmentRepository.save(newAppointmentRecord(request)).also {
                    notificationWriter.appointmentCreated(tenantGroupId, it, it.version, resolution)
                }
            }
            publishCreated(saved)
            return AppointmentCreationResult(saved, replayed = false)
        }

        val fingerprint = request.fingerprint()
        val result = try {
            transaction {
                createIdempotently(
                    tenantGroupId = tenantGroupId,
                    request = request,
                    idempotencyKey = key,
                    fingerprint = fingerprint,
                    now = Instant.now(idempotencyClock),
                    resolution = resolution,
                )
            }
        } catch (ex: ExposedSQLException) {
            if (!ex.isUniqueConstraintViolation()) {
                throw ex
            }
            resolveConcurrentIdempotencyResult(tenantGroupId, request.clinicId, key, fingerprint)
                ?: throw ex
        }
        if (!result.replayed) {
            publishCreated(result.appointment)
        }
        return result
    }

    private fun createIdempotently(
        tenantGroupId: Long,
        request: CreateAppointmentRequest,
        idempotencyKey: String,
        fingerprint: String,
        now: Instant,
        resolution: MemberResolution,
    ): AppointmentCreationResult {
        idempotencyRepository.deleteExpired(tenantGroupId, request.clinicId, idempotencyKey, now)
        val existing = idempotencyRepository.findByTenantGroupAndClinicAndKey(
            tenantGroupId,
            request.clinicId,
            idempotencyKey,
        )
        if (existing != null) {
            return replay(existing, fingerprint)
        }

        val saved = appointmentRepository.save(newAppointmentRecord(request))
        idempotencyRepository.save(
            AppointmentIdempotencyRecord(
                tenantGroupId = tenantGroupId,
                clinicId = request.clinicId,
                idempotencyKey = idempotencyKey,
                requestFingerprint = fingerprint,
                appointmentId = saved.id.requireNotNull("saved.id"),
                expiresAt = now.plus(idempotencyProperties.ttl),
            )
        )
        notificationWriter.appointmentCreated(tenantGroupId, saved, saved.version, resolution)
        return AppointmentCreationResult(saved, replayed = false)
    }

    private fun resolveConcurrentIdempotencyResult(
        tenantGroupId: Long,
        clinicId: Long,
        idempotencyKey: String,
        fingerprint: String,
    ): AppointmentCreationResult? = transaction {
        val existing = idempotencyRepository.findByTenantGroupAndClinicAndKey(tenantGroupId, clinicId, idempotencyKey)
            ?: return@transaction null
        if (existing.expiresAt <= Instant.now(idempotencyClock)) {
            return@transaction null
        }
        replay(existing, fingerprint)
    }

    private fun replay(
        existing: AppointmentIdempotencyRecord,
        fingerprint: String,
    ): AppointmentCreationResult {
        if (existing.requestFingerprint != fingerprint) {
            throw IdempotencyKeyConflictException()
        }
        val appointment = appointmentRepository.findByIdOrNull(existing.appointmentId)
            ?: throw IllegalStateException("Idempotency record points to a missing appointment")
        return AppointmentCreationResult(appointment, replayed = true)
    }

    private fun newAppointmentRecord(request: CreateAppointmentRequest): AppointmentRecord =
        AppointmentRecord(
            clinicId = request.clinicId,
            doctorId = request.doctorId,
            treatmentTypeId = request.treatmentTypeId,
            equipmentId = request.equipmentId,
            memberId = request.memberId?.let(::MemberId),
            patientName = request.patientName,
            patientPhone = request.patientPhone,
            appointmentDate = request.appointmentDate,
            startTime = request.startTime,
            endTime = request.endTime,
            status = AppointmentState.REQUESTED,
        )

    private fun publishCreated(saved: AppointmentRecord) {
        eventPublisher.publishEvent(
            AppointmentDomainEvent.Created(
                appointmentId = saved.id.requireNotNull("saved.id"),
                clinicId = saved.clinicId,
            )
        )
    }

    internal suspend fun updateStatus(id: Long, targetStatus: String, reason: String?): AppointmentRecord {
        val tenantGroupId = tenantGroupIdForAppointment(id)
        return updateStatus(id, tenantGroupId, targetStatus, reason)
    }

    suspend fun updateStatus(id: Long, tenantGroupId: Long, targetStatus: String, reason: String?): AppointmentRecord {
        log.debug { "updateStatus: id=$id, tenantGroupId=$tenantGroupId, target=$targetStatus" }
        val transition = transaction {
            rejectCommitmentV2Mutation(id, tenantGroupId)
            val record = appointmentRepository.findByIdAndTenant(id, tenantGroupId)
                ?: throw NoSuchElementException("Appointment not found: $id")
            val currentState = record.status
            val nextState = stateMachine.nextState(currentState, parseEvent(targetStatus, reason))
            check(appointmentRepository.updateLegacyStatus(id, record.version, nextState)) {
                "Appointment changed concurrently"
            }
            stateHistoryRepository.save(
                AppointmentStateHistoryRecord(
                    appointmentId = id,
                    fromState = currentState,
                    toState = nextState,
                    reason = reason,
                )
            )
            val updated = appointmentRepository.findByIdAndTenant(id, tenantGroupId)
                ?: throw NoSuchElementException("Appointment not found after status update: $id")
            notificationWriter.statusChanged(
                tenantGroupId = tenantGroupId,
                record = updated,
                version = updated.version,
                from = currentState,
                to = nextState,
            )
            AppointmentTransitionResult(updated, currentState, nextState)
        }

        eventPublisher.publishEvent(
            AppointmentDomainEvent.StatusChanged(
                appointmentId = id,
                clinicId = transition.record.clinicId,
                fromState = transition.from.name,
                toState = transition.to.name,
                reason = reason,
            )
        )
        return transition.record
    }

    internal fun getStateHistory(appointmentId: Long): List<AppointmentStateHistoryRecord> {
        log.debug { "getStateHistory: appointmentId=$appointmentId" }
        return transaction {
            appointmentRepository.findByIdOrNull(appointmentId)
                ?: throw NoSuchElementException("Appointment not found: $appointmentId")
            stateHistoryRepository.findByAppointmentId(appointmentId)
        }
    }

    fun getStateHistory(appointmentId: Long, tenantGroupId: Long): List<AppointmentStateHistoryRecord> {
        log.debug { "getStateHistory: appointmentId=$appointmentId, tenantGroupId=$tenantGroupId" }
        return transaction {
            appointmentRepository.findByIdAndTenant(appointmentId, tenantGroupId)
                ?: throw NoSuchElementException("Appointment not found: $appointmentId")
            stateHistoryRepository.findByAppointmentId(appointmentId)
        }
    }

    internal suspend fun cancel(id: Long, reason: String? = null): AppointmentRecord {
        val tenantGroupId = tenantGroupIdForAppointment(id)
        return cancel(id, tenantGroupId, reason)
    }

    suspend fun cancel(id: Long, tenantGroupId: Long, reason: String? = null): AppointmentRecord {
        log.debug { "cancel: id=$id, tenantGroupId=$tenantGroupId, reason=$reason" }
        val effectiveReason = reason ?: "Cancelled by user"
        val cancelled = transaction {
            rejectCommitmentV2Mutation(id, tenantGroupId)
            val record = appointmentRepository.findByIdAndTenant(id, tenantGroupId)
                ?: throw NoSuchElementException("Appointment not found: $id")
            val currentState = record.status
            stateMachine.nextState(currentState, AppointmentEvent.Cancel(reason = effectiveReason))
            check(appointmentRepository.updateLegacyStatus(id, record.version, AppointmentState.CANCELLED)) {
                "Appointment changed concurrently"
            }
            stateHistoryRepository.save(
                AppointmentStateHistoryRecord(
                    appointmentId = id,
                    fromState = currentState,
                    toState = AppointmentState.CANCELLED,
                    reason = effectiveReason,
                )
            )
            val updated = appointmentRepository.findByIdAndTenant(id, tenantGroupId)
                ?: throw NoSuchElementException("Appointment not found after cancel: $id")
            notificationWriter.cancelled(
                tenantGroupId = tenantGroupId,
                record = updated,
                version = updated.version,
                reasonCode = reason?.toRegisteredCancellationReasonCode(),
            )
            updated
        }

        eventPublisher.publishEvent(
            AppointmentDomainEvent.Cancelled(
                appointmentId = id,
                clinicId = cancelled.clinicId,
                reason = effectiveReason,
            )
        )
        return cancelled
    }

    private fun tenantGroupIdForAppointment(appointmentId: Long): Long =
        transaction {
            val appointment = appointmentRepository.findByIdOrNull(appointmentId)
                ?: throw NoSuchElementException("Appointment not found: $appointmentId")
            clinicRepository.findByIdOrNull(appointment.clinicId)?.tenantGroupId
                ?: throw NoSuchElementException("Clinic not found: ${appointment.clinicId}")
        }

    /**
     * commitment v2 row가 legacy 상태 변경 경로로 우회하지 못하게 한다.
     *
     * v2 변경은 ETag CAS, 동의, 자원 allocation, outbox를 하나의 transaction으로 처리한다.
     * projection이 완성됐다는 이유로 legacy 상태 머신만 실행하면 이 불변식이 깨진다.
     */
    private fun rejectCommitmentV2Mutation(
        appointmentId: Long,
        tenantGroupId: Long,
    ) {
        if (appointmentRepository.isCommitmentV2(appointmentId, tenantGroupId)) {
            throw AppointmentCommitmentApiException(
                AppointmentCommitmentApiError.NEW_APPOINTMENT_API_REQUIRED,
            )
        }
    }
}

data class AppointmentCreationResult(
    val appointment: AppointmentRecord,
    val replayed: Boolean,
)

private data class AppointmentTransitionResult(
    val record: AppointmentRecord,
    val from: AppointmentState,
    val to: AppointmentState,
)

private fun String.toRegisteredCancellationReasonCode(): CancellationReasonCode? =
    runCatching(::CancellationReasonCode).getOrNull()

private fun validateIdempotencyKey(idempotencyKey: String) {
    require(idempotencyKey.isNotBlank()) { "Idempotency-Key must not be blank" }
    require(idempotencyKey.length <= 255) { "Idempotency-Key must be at most 255 characters" }
}

private fun CreateAppointmentRequest.fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .apply {
            updateField("clinicId", clinicId.toString())
            updateField("doctorId", doctorId.toString())
            updateField("treatmentTypeId", treatmentTypeId.toString())
            updateField("equipmentId", equipmentId?.toString())
            updateField("memberId", memberId)
            updateField("patientName", patientName)
            updateField("patientPhone", patientPhone)
            updateField("appointmentDate", appointmentDate.toString())
            updateField("startTime", startTime.toString())
            updateField("endTime", endTime.toString())
        }
        .digest()
        .joinToString("") { "%02x".format(it) }

private fun MessageDigest.updateField(name: String, value: String?) {
    update(name.toByteArray(StandardCharsets.UTF_8))
    update(0)
    if (value == null) {
        update(-1)
    } else {
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        update(valueBytes.size.toString().toByteArray(StandardCharsets.UTF_8))
        update(0)
        update(valueBytes)
    }
    update(0)
}

private fun ExposedSQLException.isUniqueConstraintViolation(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == "23505" || it.sqlState == "23000" }

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

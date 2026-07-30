package io.bluetape4k.clinic.appointment.event.profile

import io.bluetape4k.clinic.appointment.event.integration.ProtectedQuarantineEnvelope
import io.bluetape4k.clinic.appointment.event.integration.QuarantineDetection
import io.bluetape4k.clinic.appointment.event.integration.QuarantineEnvelopeProtector
import io.bluetape4k.clinic.appointment.event.integration.QuarantineRetentionClass
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventRepository
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventTrustVerifier
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineRepository
import io.bluetape4k.clinic.appointment.event.integration.SchedulingTrustException
import io.bluetape4k.clinic.appointment.event.integration.UntrustedSchedulingEventEnvelope
import io.bluetape4k.clinic.appointment.model.dto.UpsertProfileChange
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Duration

enum class ProfileReevaluationEventStatus {
    PROCESSED,
    NO_MATERIAL_CHANGE,
    DUPLICATE,
    QUARANTINED,
}

data class ProfileReevaluationEventResult(
    val status: ProfileReevaluationEventStatus,
    val reasonCode: String? = null,
)

/**
 * 신뢰 검증된 CRM 프로필 변경 신호를 최신 재평가 작업으로 병합합니다.
 *
 * 원본 환자 식별자나 프로필 본문을 받지 않으며, 불신 이벤트는 예약·job transaction과
 * 분리해 암호화 격리합니다.
 */
class ProfileReevaluationEventService(
    private val trustVerifier: SchedulingEventTrustVerifier,
    private val eventRepository: SchedulingEventRepository,
    private val reevaluationRepository: ProfileReevaluationRepository,
    private val quarantineEnvelopeProtector: QuarantineEnvelopeProtector,
    private val quarantineRepository: SchedulingQuarantineRepository,
    private val clock: Clock,
    private val quarantineRetention: Duration,
    private val quarantineRetentionClass: QuarantineRetentionClass,
    private val heldTarget: Duration,
    private val proposedTarget: Duration,
    private val targetPolicyRef: String,
    private val targetPolicyGeneration: Long,
) {
    init {
        require(!quarantineRetention.isNegative) { "quarantineRetention must be non-negative" }
        require(!heldTarget.isNegative && !heldTarget.isZero) { "heldTarget must be positive" }
        require(!proposedTarget.isNegative && !proposedTarget.isZero) { "proposedTarget must be positive" }
        require(targetPolicyRef.isNotBlank()) { "targetPolicyRef must not be blank" }
        require(targetPolicyGeneration > 0) { "targetPolicyGeneration must be positive" }
    }

    fun accept(
        rawEnvelope: UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged>,
    ): ProfileReevaluationEventResult {
        val protectedEnvelope = quarantineEnvelopeProtector.protect(rawEnvelope)
        val trusted = try {
            trustVerifier.verifyProfileAssessment(rawEnvelope)
        } catch (failure: SchedulingTrustException) {
            return quarantine(rawEnvelope, protectedEnvelope, failure.reasonCode)
        }

        try {
            ProfileReferenceFingerprintValidator.validate(trusted.payload.patientReferenceFingerprint)
        } catch (_: IllegalArgumentException) {
            return quarantine(
                rawEnvelope,
                protectedEnvelope,
                PROFILE_REFERENCE_FINGERPRINT_INVALID,
            )
        }

        return try {
            transaction {
                if (eventRepository.findInbox(trusted.eventId) != null ||
                    quarantineRepository.findByEventId(trusted.eventId) != null
                ) {
                    return@transaction ProfileReevaluationEventResult(
                        ProfileReevaluationEventStatus.DUPLICATE,
                    )
                }
                verifyClinicScope(trusted.payload)
                val inboxId = eventRepository.insertReceivedProfileAssessment(trusted)
                if (!trusted.payload.materialChange) {
                    eventRepository.markProcessed(
                        inboxId = inboxId,
                        processedAt = clock.instant(),
                        reasonCode = NO_MATERIAL_CHANGE,
                    )
                    return@transaction ProfileReevaluationEventResult(
                        ProfileReevaluationEventStatus.NO_MATERIAL_CHANGE,
                        NO_MATERIAL_CHANGE,
                    )
                }

                val payload = trusted.payload
                reevaluationRepository.upsertEvent(
                    UpsertProfileChange(
                        scope = ProfileReferenceFingerprintValidator.scope(
                            payload.tenantGroupId,
                            payload.clinicId,
                            payload.patientReferenceFingerprint,
                        ),
                        revision = payload.profileRevision,
                        eventId = payload.eventId,
                        assessmentRef = payload.assessmentRef,
                        assessmentHash = payload.assessmentHash,
                        occurredAt = payload.occurredAt,
                        heldTarget = heldTarget,
                        proposedTarget = proposedTarget,
                        targetPolicyRef = targetPolicyRef,
                        targetPolicyGeneration = targetPolicyGeneration,
                    ),
                )
                eventRepository.markProcessed(inboxId, clock.instant())
                ProfileReevaluationEventResult(ProfileReevaluationEventStatus.PROCESSED)
            }
        } catch (failure: SchedulingTrustException) {
            quarantine(rawEnvelope, protectedEnvelope, failure.reasonCode)
        } catch (failure: ExposedSQLException) {
            findDuplicate(rawEnvelope.eventId) ?: throw failure
        }
    }

    private fun verifyClinicScope(event: PatientSchedulingAssessmentChanged) {
        val clinicMatchesTenant = Clinics
            .selectAll()
            .where {
                (Clinics.id eq event.clinicId) and
                    (Clinics.tenantGroupId eq event.tenantGroupId)
            }
            .limit(1)
            .any()
        if (!clinicMatchesTenant) {
            throw SchedulingTrustException("TENANT_CLINIC_MISMATCH")
        }
    }

    private fun quarantine(
        rawEnvelope: UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged>,
        protectedEnvelope: ProtectedQuarantineEnvelope,
        reasonCode: String,
    ): ProfileReevaluationEventResult =
        try {
            transaction {
                eventRepository.findInbox(rawEnvelope.eventId)?.let {
                    return@transaction ProfileReevaluationEventResult(
                        ProfileReevaluationEventStatus.DUPLICATE,
                    )
                }
                quarantineRepository.findByEventId(rawEnvelope.eventId)?.let {
                    return@transaction ProfileReevaluationEventResult(
                        ProfileReevaluationEventStatus.DUPLICATE,
                        it.reasonCode,
                    )
                }
                val detectedAt = clock.instant()
                quarantineRepository.recordDetected(
                    QuarantineDetection(
                        eventId = rawEnvelope.eventId,
                        eventType = rawEnvelope.eventType,
                        protectedEnvelope = protectedEnvelope,
                        producer = rawEnvelope.producer,
                        sourceAuthority = PROFILE_ASSESSMENT_SOURCE_AUTHORITY,
                        schemaVersion = rawEnvelope.schemaVersion,
                        sourceAggregateId = rawEnvelope.eventId,
                        sourceAggregateVersion = rawEnvelope.payload.profileRevision.coerceAtLeast(1L),
                        tenantGroupId = rawEnvelope.payload.tenantGroupId,
                        clinicId = rawEnvelope.payload.clinicId,
                        reasonCode = reasonCode,
                        detectedAt = detectedAt,
                        correlationId = rawEnvelope.correlationId,
                        retentionClass = quarantineRetentionClass,
                        payloadExpiresAt = detectedAt.plus(quarantineRetention),
                    ),
                )
                ProfileReevaluationEventResult(
                    ProfileReevaluationEventStatus.QUARANTINED,
                    reasonCode,
                )
            }
        } catch (failure: ExposedSQLException) {
            findDuplicate(rawEnvelope.eventId) ?: throw failure
        }

    private fun findDuplicate(eventId: String): ProfileReevaluationEventResult? =
        transaction {
            eventRepository.findInbox(eventId)?.let {
                return@transaction ProfileReevaluationEventResult(
                    ProfileReevaluationEventStatus.DUPLICATE,
                )
            }
            quarantineRepository.findByEventId(eventId)?.let {
                return@transaction ProfileReevaluationEventResult(
                    ProfileReevaluationEventStatus.DUPLICATE,
                    it.reasonCode,
                )
            }
            null
        }

    private companion object {
        const val PROFILE_ASSESSMENT_SOURCE_AUTHORITY = "crm-assessment"
        const val PROFILE_REFERENCE_FINGERPRINT_INVALID = "PROFILE_REFERENCE_FINGERPRINT_INVALID"
        const val NO_MATERIAL_CHANGE = "NO_MATERIAL_CHANGE"
    }
}

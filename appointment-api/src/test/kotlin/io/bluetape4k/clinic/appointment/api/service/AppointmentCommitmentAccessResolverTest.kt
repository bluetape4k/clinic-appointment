package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.commitment.VisitCommitmentCommandTestSupport
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.junit.jupiter.api.Test

/**
 * server-side resolver가 Gateway scope, Plan 환자 소유권, 동의 authority를 함께 검증해
 * request body 식별자를 신뢰 경계로 승격시키지 않는지 검증한다.
 */
internal class AppointmentCommitmentAccessResolverTest : VisitCommitmentCommandTestSupport() {

    @Test
    fun `patient can resolve only a plan with the same protected subject fingerprint`() {
        val resolver = accessResolver()

        val access = resolver.resolvePlan(patientActor(), clinic.planId)

        access.tenantGroupId shouldBeEqualTo TENANT_ID
        access.clinicId shouldBeEqualTo clinic.clinicId
        access.plan.id shouldBeEqualTo clinic.planId
    }

    @Test
    fun `different patient subject cannot use another patients plan`() {
        val resolver = accessResolver(
            fingerprints = mapOf("different-patient" to "e".repeat(64)),
        )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            resolver.resolvePlan(patientActor("different-patient"), clinic.planId)
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_FORBIDDEN
    }

    @Test
    fun `patient appointment access repeats tenant clinic and subject ownership checks`() {
        val appointment = confirmDirect(commandService(), "access-resolver")
        val resolver = accessResolver(
            fingerprints = mapOf(
                "patient-subject-ok" to clinic.patientReferenceFingerprint,
                "different-patient" to "e".repeat(64),
            ),
        )

        resolver.requireAppointmentAccess(
            patientActor(),
            appointment.commitment.appointmentId,
        ).appointmentId shouldBeEqualTo appointment.commitment.appointmentId

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            resolver.requireAppointmentAccess(
                patientActor("different-patient"),
                appointment.commitment.appointmentId,
            )
        }
        exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_FORBIDDEN
    }

    @Test
    fun `consent authority must use the exact authenticated tenant namespace`() {
        val resolver = accessResolver()

        resolver.requireConsentAuthority(patientActor(), "tenant-task6:consent-service")

        listOf(
            "other-tenant:consent-service",
            "tenant-task60:consent-service",
        ).forEach { authority ->
            val exception = assertFailsWith<AppointmentCommitmentApiException> {
                resolver.requireConsentAuthority(patientActor(), authority)
            }
            exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_FORBIDDEN
        }
    }

    @Test
    fun `administrator uses selected clinic scope without requiring a patient subject`() {
        var fingerprintCalls = 0
        val resolver = AppointmentCommitmentAccessResolver(
            database = database,
            patientSubjectFingerprintResolver = PatientSubjectFingerprintResolver { _, _ ->
                fingerprintCalls += 1
                clinic.patientReferenceFingerprint
            },
        )

        val access = resolver.resolvePlan(adminActor(), clinic.planId)

        access.clinicId shouldBeEqualTo clinic.clinicId
        fingerprintCalls shouldBeEqualTo 0
    }

    private fun accessResolver(
        fingerprints: Map<String, String> =
            mapOf("patient-subject-ok" to clinic.patientReferenceFingerprint),
    ) = AppointmentCommitmentAccessResolver(
        database = database,
        patientSubjectFingerprintResolver = PatientSubjectFingerprintResolver { _, patientSubjectId ->
            fingerprints[patientSubjectId] ?: "0".repeat(64)
        },
    )

    private fun patientActor(patientSubjectId: String = "patient-subject-ok") =
        actor(
            actorType = ActorType.PATIENT,
            roles = setOf(ActorRole.PATIENT),
            patientSubjectId = patientSubjectId,
        )

    private fun adminActor() =
        actor(
            actorType = ActorType.ADMIN,
            roles = setOf(ActorRole.ADMIN),
        )

    private fun actor(
        actorType: ActorType,
        roles: Set<ActorRole>,
        patientSubjectId: String? = null,
    ) = ActorContext(
        actorId = "actor-${actorType.name.lowercase()}",
        actorType = actorType,
        roles = roles,
        scopes = emptySet(),
        allowedTenantCodes = setOf("tenant-task6"),
        allowedClinicIds = setOf(clinic.clinicId),
        patientSubjectId = patientSubjectId,
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-${actorType.name.lowercase()}",
        authenticatedAt = NOW,
        correlationId = "correlation-${actorType.name.lowercase()}",
        selectedClinicId = clinic.clinicId,
    )
}

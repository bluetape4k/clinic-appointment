package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.dto.PatientCancellationHistoryQuery
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.repository.AppointmentCancellationHistoryRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.mockk.mockk
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.time.Instant

class PatientCancellationHistoryServiceTest {
    private val service = PatientCancellationHistoryService(
        database = mockk<Database>(),
        tenantGroupRepository = mockk<TenantGroupRepository>(),
        historyRepository = mockk<AppointmentCancellationHistoryRepository>(),
        patientSubjectFingerprintResolver = PatientSubjectFingerprintResolver { _, _ -> "a".repeat(64) },
        cursorCodec = mockk<PatientHistoryCursorCodec>(),
        referenceCodec = mockk<PatientHistoryReferenceCodec>(),
        etagCodec = mockk<PatientHistoryEtagCodec>(),
    )

    @Test
    fun `patient history rejects out of range limit before database access`() {
        val failure = assertFailsWith<PatientHistoryApiException> {
            service.read(patientActor(), "tenant-task6", PatientCancellationHistoryQuery(limit = 0), null)
        }

        failure.error shouldBeEqualTo PatientHistoryApiError.LIMIT_INVALID
        failure.error.httpStatus.value() shouldBeEqualTo 400
    }

    @Test
    fun `history service rejects non patient actors before database access`() {
        val failure = assertFailsWith<PatientHistoryApiException> {
            service.read(adminActor(), "tenant-task6", PatientCancellationHistoryQuery(), null)
        }

        failure.error shouldBeEqualTo PatientHistoryApiError.SCOPE_FORBIDDEN
        failure.error.retryable shouldBeEqualTo false
    }

    private fun patientActor() = actor(ActorType.PATIENT, setOf(ActorRole.PATIENT), "patient-1")

    private fun adminActor() = actor(ActorType.ADMIN, setOf(ActorRole.ADMIN), null)

    private fun actor(type: ActorType, roles: Set<ActorRole>, patientSubjectId: String?) = ActorContext(
        actorId = "actor-${type.name.lowercase()}",
        actorType = type,
        roles = roles,
        scopes = emptySet(),
        allowedTenantCodes = setOf("tenant-task6"),
        allowedClinicIds = emptySet(),
        patientSubjectId = patientSubjectId,
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-${type.name.lowercase()}",
        authenticatedAt = Instant.parse("2026-08-14T00:00:00Z"),
        correlationId = "correlation-${type.name.lowercase()}",
        selectedTenantCode = "tenant-task6",
    )
}

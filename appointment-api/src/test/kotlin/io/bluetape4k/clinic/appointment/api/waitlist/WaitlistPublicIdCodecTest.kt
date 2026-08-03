package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.junit.jupiter.api.Test
import java.time.Instant

class WaitlistPublicIdCodecTest {
    private val codec = WaitlistPublicIdCodec()

    @Test
    fun `scoped public ids round trip without exposing raw numeric id`() {
        val scope = scope(tenantGroupId = 11, clinicId = 7)

        val publicId = codec.encode(scope, WaitlistPublicIdKind.ENTRY, 123)

        publicId.startsWith("e_") shouldBeEqualTo true
        publicId.contains("123") shouldBeEqualTo false
        codec.decode(scope, WaitlistPublicIdKind.ENTRY, publicId) shouldBeEqualTo 123L
    }

    @Test
    fun `wrong clinic scope is hidden as not found`() {
        val publicId = codec.encode(scope(tenantGroupId = 11, clinicId = 7), WaitlistPublicIdKind.OFFER, 44)

        val failure = assertFailsWith<WaitlistApiException> {
            codec.decode(scope(tenantGroupId = 11, clinicId = 8), WaitlistPublicIdKind.OFFER, publicId)
        }

        failure.error shouldBeEqualTo WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND
    }

    @Test
    fun `canonical fixture refs decode only for matching resource kind`() {
        val scope = scope()

        codec.decode(scope, WaitlistPublicIdKind.ENTRY, "e-1") shouldBeEqualTo 1L

        val failure = assertFailsWith<WaitlistApiException> {
            codec.decode(scope, WaitlistPublicIdKind.OFFER, "e-1")
        }
        failure.error shouldBeEqualTo WaitlistApiError.WAITLIST_REFERENCE_NOT_FOUND
    }

    @Test
    fun `idempotency key requires printable ascii length between 16 and 128`() {
        WaitlistIdempotencyKeys.requireValid("0123456789abcdef") shouldBeEqualTo "0123456789abcdef"

        listOf(
            null,
            "",
            "0123456789abcde",
            "x".repeat(129),
            "clinic-명령-00000001",
        ).forEach { candidate ->
            val failure = assertFailsWith<WaitlistApiException> {
                WaitlistIdempotencyKeys.requireValid(candidate)
            }
            failure.error shouldBeEqualTo WaitlistApiError.INVALID_IDEMPOTENCY_KEY
        }
    }

    private fun scope(
        tenantGroupId: Long = 1,
        clinicId: Long = 1,
    ): WaitlistTenantScope =
        WaitlistTenantScope(
            tenantGroupId = tenantGroupId,
            tenantCode = "tenant-a",
            clinicId = clinicId,
            actor = ActorContext(
                actorId = "staff-1",
                actorType = ActorType.STAFF,
                roles = setOf(ActorRole.STAFF),
                scopes = setOf("waitlist:read", "waitlist:write"),
                allowedTenantCodes = setOf("tenant-a"),
                allowedClinicIds = setOf(clinicId),
                patientSubjectId = null,
                assurance = AuthenticationAssurance.PASSWORD,
                issuer = "issuer",
                tokenId = "token-1",
                authenticatedAt = Instant.parse("2026-08-03T00:00:00Z"),
                correlationId = "corr-1",
                selectedClinicId = clinicId,
            ),
            correlationId = "corr-1",
        )
}

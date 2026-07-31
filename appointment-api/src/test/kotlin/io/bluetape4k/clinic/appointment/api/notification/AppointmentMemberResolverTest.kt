package io.bluetape4k.clinic.appointment.api.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.service.ResolvedAppointmentPlanAccess
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AppointmentMemberResolverTest {

    @Test
    fun `ENFORCE에서는 legacy 회원 ID 누락을 거절한다`() {
        val resolver = resolver(directoryResult = MemberDirectoryResult.Resolved(MemberId("member-1")))

        val error = assertFailsWith<NotificationMemberApiException> {
            resolver.resolveLegacy(10L, 20L, null)
        }

        error.error shouldBeEqualTo NotificationMemberApiError.MEMBER_ID_REQUIRED
    }

    @Test
    fun `유효한 OBSERVE 예외에서는 legacy 회원 ID 누락을 한시적으로 허용한다`() {
        val properties = NotificationMemberIdProperties(
            memberIdOverrides = listOf(
                NotificationMemberIdOverride(
                    tenantGroupId = 10L,
                    clinicId = 20L,
                    mode = MemberIdEnforcementMode.OBSERVE,
                    expiresAt = Instant.parse("2026-08-02T00:00:00Z"),
                    owner = "clinic-migration-owner",
                ),
            ),
        )
        val resolver = resolver(properties = properties)

        resolver.resolveLegacy(10L, 20L, null) shouldBeEqualTo MemberResolution.LegacyMissing
    }

    @Test
    fun `만료된 OBSERVE 예외는 ENFORCE로 돌아간다`() {
        val properties = NotificationMemberIdProperties(
            memberIdOverrides = listOf(
                NotificationMemberIdOverride(
                    tenantGroupId = 10L,
                    clinicId = 20L,
                    mode = MemberIdEnforcementMode.OBSERVE,
                    expiresAt = Instant.parse("2026-07-30T00:00:00Z"),
                    owner = "clinic-migration-owner",
                ),
            ),
        )
        val resolver = resolver(properties = properties)

        val error = assertFailsWith<NotificationMemberApiException> {
            resolver.resolveLegacy(10L, 20L, null)
        }

        error.error shouldBeEqualTo NotificationMemberApiError.MEMBER_ID_REQUIRED
    }

    @Test
    fun `OBSERVE 예외에는 담당자와 만료 시각이 필요하다`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationMemberIdProperties(
                memberIdOverrides = listOf(
                    NotificationMemberIdOverride(
                        tenantGroupId = 10L,
                        clinicId = 20L,
                        mode = MemberIdEnforcementMode.OBSERVE,
                        expiresAt = null,
                        owner = "",
                    ),
                ),
            )
        }
    }

    @Test
    fun `회원 디렉터리 결과를 안정적인 API 오류로 변환한다`() {
        val cases = listOf(
            MemberDirectoryResult.NotFound to NotificationMemberApiError.MEMBER_NOT_FOUND,
            MemberDirectoryResult.ScopeMismatch to NotificationMemberApiError.MEMBER_SCOPE_MISMATCH,
            MemberDirectoryResult.Ambiguous to NotificationMemberApiError.MEMBER_REFERENCE_AMBIGUOUS,
            MemberDirectoryResult.Unavailable to NotificationMemberApiError.MEMBER_DIRECTORY_UNAVAILABLE,
        )

        cases.forEach { (directoryResult, expected) ->
            val error = assertFailsWith<NotificationMemberApiException> {
                resolver(directoryResult).resolveLegacy(10L, 20L, MemberId("member-secret"))
            }
            error.error shouldBeEqualTo expected
            error.message shouldBeEqualTo expected.name
        }
    }

    @Test
    fun `Plan 해석은 인증 주체와 보호된 Plan 참조를 디렉터리에만 전달한다`() {
        val directory = RecordingMemberDirectory(MemberDirectoryResult.Resolved(MemberId("member-7")))
        val resolver = DefaultAppointmentMemberResolver(
            directory = directory,
            properties = NotificationMemberIdProperties(),
            clock = FIXED_CLOCK,
        )

        resolver.resolvePlan(patientActor(), planAccess()) shouldBeEqualTo MemberId("member-7")
        directory.lastPlanRequest shouldBeEqualTo MemberPlanDirectoryRequest(
            tenantGroupId = 10L,
            clinicId = 20L,
            patientSubjectId = "patient-subject-7",
            patientReferenceFingerprint = "f".repeat(64),
        )
    }

    private fun resolver(
        directoryResult: MemberDirectoryResult = MemberDirectoryResult.Resolved(MemberId("member-1")),
        properties: NotificationMemberIdProperties = NotificationMemberIdProperties(),
    ): AppointmentMemberResolver =
        DefaultAppointmentMemberResolver(
            directory = RecordingMemberDirectory(directoryResult),
            properties = properties,
            clock = FIXED_CLOCK,
        )

    private class RecordingMemberDirectory(
        private val result: MemberDirectoryResult,
    ) : AppointmentMemberDirectory {
        var lastPlanRequest: MemberPlanDirectoryRequest? = null

        override fun resolveMember(request: MemberDirectoryRequest): MemberDirectoryResult = result

        override fun resolvePlan(request: MemberPlanDirectoryRequest): MemberDirectoryResult {
            lastPlanRequest = request
            return result
        }
    }

    private fun patientActor() = ActorContext(
        actorId = "patient-actor-7",
        actorType = ActorType.PATIENT,
        roles = setOf(ActorRole.PATIENT),
        scopes = emptySet(),
        allowedTenantCodes = setOf("tenant-default"),
        allowedClinicIds = setOf(20L),
        patientSubjectId = "patient-subject-7",
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-7",
        authenticatedAt = Instant.parse("2026-07-30T00:00:00Z"),
        correlationId = "correlation-7",
        selectedClinicId = 20L,
    )

    private fun planAccess() = ResolvedAppointmentPlanAccess(
        tenantGroupId = 10L,
        clinicId = 20L,
        plan = AppointmentPlanRecord(
            id = 30L,
            tenantGroupId = 10L,
            clinicId = 20L,
            catalogProjectionId = 40L,
            sourcePurchaseAuthority = "commerce",
            sourcePurchaseId = "purchase-1",
            patientReferenceCiphertext = "ciphertext",
            patientReferenceKeyId = "key-1",
            patientReferenceFingerprint = "f".repeat(64),
            catalogSourceAuthority = "catalog",
            productId = "product-1",
            catalogVersion = 1L,
            catalogPayloadHash = "a".repeat(64),
            productName = "검진",
            bookingPreference = BookingPreferenceSnapshot.NotProvided,
            status = AppointmentPlanStatus.ACTIVE,
        ),
    )

    private companion object {
        val FIXED_CLOCK: Clock =
            Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC)
    }
}

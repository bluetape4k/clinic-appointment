package io.bluetape4k.clinic.appointment.api.profile

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.dto.RedriveProfileReevaluationJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import java.time.Instant

class ProfileReevaluationEndpointTest {

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `redrive 감사 actor는 요청이 아니라 인증된 token subject에서 가져온다`() {
        val principal =
            SchedulingUserPrincipal(
                userId = "trusted-profile-ops",
                clinicId = null,
                roles = setOf(SchedulingRole.ADMIN),
                allowedTenants = setOf("clinic-a"),
                actorType = ActorType.ADMIN,
                allowedClinicIds = emptySet(),
                assurance = AuthenticationAssurance.MFA,
                issuer = "https://gateway.example.test",
                tokenId = "token-123",
                authenticatedAt = Instant.parse("2026-07-31T00:00:00Z"),
            )
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
        val auditEvents = mutableListOf<ProfileReevaluationAdminAuditEvent>()
        val service =
            ProfileReevaluationAdminService(
                store = EmptyAdminStore,
                redriveCooldown = Duration.ZERO,
                auditSink = auditEvents::add,
            )
        val endpoint = ProfileReevaluationEndpoint(service)

        endpoint.redrive(
            action = ProfileReevaluationAdminAction.PREVIEW,
            reason = "CRM 복구 확인 후 재처리 대상 점검",
            idempotencyKey = "redrive-20260731-0001",
        )

        auditEvents.single().actor shouldBeEqualTo principal.userId
        ProfileReevaluationEndpoint::class.java
            .getDeclaredMethod(
                "redrive",
                ProfileReevaluationAdminAction::class.java,
                String::class.java,
                String::class.java,
                java.lang.Long::class.java,
                java.lang.Long::class.java,
                java.lang.Long::class.java,
                Int::class.javaPrimitiveType!!,
            )
            .parameterCount shouldBeEqualTo 7
    }

    private object EmptyAdminStore : ProfileReevaluationAdminStore {
        override suspend fun snapshot() = ProfileReevaluationOperationalSnapshot()

        override suspend fun findFailed(
            scope: ProfileReevaluationAdminScope,
            limit: Int,
        ): List<ProfileReevaluationJobRecord> = emptyList()

        override suspend fun redrive(
            command: RedriveProfileReevaluationJob,
        ): ProfileReevaluationJobRecord? = null
    }
}

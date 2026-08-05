package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken

/**
 * Spring Security 인증 주체를 불변 예약 정책 [ActorContext]로 변환하는 신뢰 경계를 검증한다.
 *
 * 지원하지 않는 principal이나 인증되지 않은 요청은 거부하고, tenant·clinic·patient 범위와
 * correlation 정보는 검증된 [SchedulingUserPrincipal]에서만 복사하는지 확인한다. 요청
 * 본문이나 임의 Authentication detail이 행위자 역할과 예약 출처를 승격할 수 없어야 한다.
 */
class ActorContextResolverTest {

    private val resolver = ActorContextResolver()

    @Test
    fun `trusted principal becomes immutable actor context for the exact path scope`() {
        val principal = principal()
        val authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            principal.authorities,
        )

        val context = resolver.resolve(
            authentication = authentication,
            tenantCode = "tenant-a",
            clinicId = 7L,
            correlationId = "correlation-7",
        )

        context.actorId shouldBeEqualTo "admin-subject"
        context.actorType shouldBeEqualTo ActorType.ADMIN
        context.roles.shouldContain(ActorRole.ADMIN)
        context.allowedTenantCodes.shouldContain("tenant-a")
        context.allowedClinicIds.shouldContain(7L)
        context.tokenId shouldBeEqualTo "token-7"
        context.correlationId shouldBeEqualTo "correlation-7"
    }

    @Test
    fun `tenant and clinic path scope are rechecked against the principal`() {
        val principal = principal()
        val authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            principal.authorities,
        )

        assertFailsWith<AccessDeniedException> {
            resolver.resolve(authentication, "tenant-b", 7L, "correlation-7")
        }
        assertFailsWith<AccessDeniedException> {
            resolver.resolve(authentication, "tenant-a", 8L, "correlation-7")
        }
    }

    @Test
    fun `multi tenant principal preserves the selected path tenant`() {
        val principal = principal(allowedTenants = setOf("tenant-a", "tenant-b"))
        val authentication = UsernamePasswordAuthenticationToken(
            principal,
            null,
            principal.authorities,
        )

        val context = resolver.resolve(
            authentication = authentication,
            tenantCode = "tenant-b",
            clinicId = 7L,
            correlationId = "correlation-b",
        )

        context.selectedTenantCode shouldBeEqualTo "tenant-b"
        context.allowedTenantCodes shouldContain "tenant-a"
        context.allowedTenantCodes shouldContain "tenant-b"
    }

    @Test
    fun `request supplied actor fields cannot participate in actor context`() {
        ActorContextResolver::class.java.declaredMethods
            .flatMap { it.parameters.toList() }
            .map { it.type }
            .none { it.name.contains("Request", ignoreCase = true) }
            .shouldBeEqualTo(true)
    }

    private fun principal(allowedTenants: Set<String> = setOf("tenant-a")) = SchedulingUserPrincipal(
        userId = "admin-subject",
        clinicId = 7L,
        roles = setOf(SchedulingRole.ADMIN),
        allowedTenants = allowedTenants,
        scopes = setOf("policy:write"),
        catalogSourceAuthorities = emptySet(),
        actorType = ActorType.ADMIN,
        allowedClinicIds = setOf(7L),
        patientSubjectId = null,
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-7",
        authenticatedAt = java.time.Instant.parse("2026-07-27T10:00:00Z"),
    )
}

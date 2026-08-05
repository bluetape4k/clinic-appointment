package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.WeakKeyException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration
import java.util.Base64
import java.util.Date

/**
 * [JwtTokenParser] 테스트.
 */
@ExtendWith(OutputCaptureExtension::class)
class JwtTokenParserTest {

    private val parser = JwtTokenParser(
        JwtSecurityProperties(
            enabled = true,
            secret = TestJwtProvider.secret,
            issuer = TestJwtProvider.issuer,
            audience = TestJwtProvider.audience,
            allowedClockSkew = Duration.ofSeconds(30),
        ),
    )

    @Test
    fun `유효한 토큰 파싱 - 사용자 정보 추출`() {
        val token = TestJwtProvider.createToken(
            userId = "user-123",
            clinicId = 5L,
            roles = listOf(SchedulingRole.ADMIN, SchedulingRole.STAFF),
            allowedTenants = listOf("tenant-a", "tenant-b"),
            scopes = setOf("catalog:write", "appointment:read"),
            catalogSourceAuthorities = setOf("product-catalog"),
            tokenId = "token-123",
        )

        val principal = parser.parse(token)

        principal.shouldNotBeNull()
        principal.userId.shouldBeEqualTo("user-123")
        principal.clinicId.shouldBeEqualTo(5L)
        principal.roles.shouldContain(SchedulingRole.ADMIN)
        principal.roles.shouldContain(SchedulingRole.STAFF)
        principal.allowedTenants.shouldContain("tenant-a")
        principal.allowedTenants.shouldContain("tenant-b")
        principal.scopes.shouldContain("catalog:write")
        principal.catalogSourceAuthorities.shouldContain("product-catalog")
        principal.actorType shouldBeEqualTo ActorType.ADMIN
        principal.allowedClinicIds.shouldContain(5L)
        principal.assurance shouldBeEqualTo AuthenticationAssurance.MFA
        principal.tokenId shouldBeEqualTo "token-123"
        principal.issuer shouldBeEqualTo TestJwtProvider.issuer
    }

    @Test
    fun `만료된 토큰 - null 반환`() {
        val token = TestJwtProvider.expiredToken()

        val principal = parser.parse(token)

        principal.shouldBeNull()
    }

    @Test
    fun `잘못된 토큰 - null 반환`() {
        val principal = parser.parse("invalid-token-string")

        principal.shouldBeNull()
    }

    @Test
    fun `blank malformed and undersized signing keys fail parser construction`() {
        assertFailsWith<WeakKeyException> {
            JwtTokenParser(properties(secret = ""))
        }
        assertFailsWith<IllegalArgumentException> {
            JwtTokenParser(properties(secret = "not-base64%%%"))
        }
        assertFailsWith<WeakKeyException> {
            JwtTokenParser(properties(secret = Base64.getEncoder().encodeToString(ByteArray(16))))
        }
    }

    @Test
    fun `clinicId 없는 토큰 - clinicId null`() {
        val token = TestJwtProvider.createToken(clinicId = null)

        val principal = parser.parse(token)

        principal.shouldNotBeNull()
        principal.clinicId.shouldBeNull()
        principal.allowedTenants.shouldContain(TenantGroups.DEFAULT_TENANT_CODE)
    }

    @Test
    fun `authorities에 ROLE_ prefix 포함`() {
        val token = TestJwtProvider.createToken(
            scopes = setOf("catalog:write"),
            catalogSourceAuthorities = setOf("product-catalog"),
        )

        val principal = parser.parse(token)

        principal.shouldNotBeNull()
        val authorityNames = principal.authorities.map { it.authority }
        authorityNames.shouldContain("ROLE_ADMIN")
        authorityNames.shouldContain("SCOPE_catalog:write")
    }

    @Test
    fun `issuer audience jti and authentication time are mandatory`() {
        parser.parse(TestJwtProvider.createToken(issuer = "wrong-issuer")).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(audience = null)).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(audience = "wrong-audience")).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(tokenId = null)).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(tokenId = " ")).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(issuedAt = null, authenticatedAt = Date())).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(authenticatedAt = null)).shouldBeNull()
    }

    @Test
    fun `rejected token logs only the generic authentication outcome`(output: CapturedOutput) {
        val attackerSubject = "attacker-subject-value"
        val attackerAudience = "attacker-audience-value"
        val attackerTokenId = "attacker-jti-value"

        parser.parse(
            TestJwtProvider.createToken(
                userId = attackerSubject,
                audience = attackerAudience,
                tokenId = attackerTokenId,
            )
        ).shouldBeNull()

        output.out.shouldContain("Gateway JWT authentication rejected")
        output.out.shouldNotContain(attackerSubject)
        output.out.shouldNotContain(attackerAudience)
        output.out.shouldNotContain(attackerTokenId)
        output.out.shouldNotContain("claim")
        output.out.shouldNotContain("parser")
    }

    @Test
    fun `only HS256 is accepted even when the configured secret can verify stronger HMAC algorithms`() {
        parser.parse(TestJwtProvider.createToken(algorithm = Jwts.SIG.HS384)).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(algorithm = Jwts.SIG.HS512)).shouldBeNull()
    }

    @Test
    fun `expiration not-before and future authentication claims honor only bounded skew`() {
        val now = System.currentTimeMillis()
        parser.parse(
            TestJwtProvider.createToken(
                notBefore = Date(now + 31_000),
                expiration = Date(now + 60_000),
            )
        ).shouldBeNull()
        parser.parse(
            TestJwtProvider.createToken(
                issuedAt = Date(now + 31_000),
                authenticatedAt = Date(now + 31_000),
                expiration = Date(now + 60_000),
            )
        ).shouldBeNull()
    }

    @Test
    fun `actor type role and patient subject contradictions fail closed`() {
        parser.parse(
            TestJwtProvider.createToken(
                roles = listOf(SchedulingRole.PATIENT),
                actorType = ActorType.ADMIN,
                patientSubjectId = "patient-7",
            )
        ).shouldBeNull()
        parser.parse(
            TestJwtProvider.createToken(
                clinicId = null,
                roles = listOf(SchedulingRole.PATIENT),
                actorType = ActorType.PATIENT,
                patientSubjectId = null,
            )
        ).shouldBeNull()
        parser.parse(
            TestJwtProvider.createToken(
                roles = listOf(SchedulingRole.SYSTEM),
                actorType = ActorType.SYSTEM,
                patientSubjectId = "patient-7",
            )
        ).shouldBeNull()
    }

    @Test
    fun `closed claims reject unknown roles unsafe scopes and invalid clinic sets`() {
        parser.parse(
            TestJwtProvider.createToken(
                roles = listOf("OWNER"),
                actorType = ActorType.ADMIN,
            )
        ).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(scopes = setOf("policy<script>"))).shouldBeNull()
        parser.parse(TestJwtProvider.createToken(allowedClinicIds = setOf(0L))).shouldBeNull()
        parser.parse(
            TestJwtProvider.createToken(
                clinicId = null,
                allowedClinicIds = listOf(1.5),
            )
        ).shouldBeNull()

        val principal = parser.parse(TestJwtProvider.createToken(scopes = setOf("policy:write")))
        principal.shouldNotBeNull()
        principal.authorities.map { it.authority }.shouldContain("SCOPE_policy:write")
        principal.authorities.map { it.authority }.shouldNotContain("SCOPE_policy write")
    }

    @Test
    fun `allowed tenant claims use the canonical path slug without implicit normalization`() {
        parser.parse(
            TestJwtProvider.createToken(allowedTenants = listOf("Tenant-A")),
        ).shouldBeNull()
        parser.parse(
            TestJwtProvider.createToken(allowedTenants = listOf("tenant_a")),
        ).shouldBeNull()
        parser.parse(
            TestJwtProvider.createToken(allowedTenants = listOf("v1")),
        ).shouldBeNull()

        parser.parse(
            TestJwtProvider.createToken(allowedTenants = listOf("tenant-a")),
        ).shouldNotBeNull()
    }

    @Test
    fun `allowed tenant claims deduplicate and remain bounded`() {
        val duplicateValues = List(64) { "tenant-a" }
        val maximumValues = (0 until 64).map { "tenant-$it" }

        val duplicatePrincipal = parser.parse(
            TestJwtProvider.createToken(allowedTenants = duplicateValues),
        )
        duplicatePrincipal.shouldNotBeNull()
        duplicatePrincipal.allowedTenants shouldBeEqualTo setOf("tenant-a")

        val maximumPrincipal = parser.parse(
            TestJwtProvider.createToken(allowedTenants = maximumValues),
        )
        maximumPrincipal.shouldNotBeNull()
        maximumPrincipal.allowedTenants.size shouldBeEqualTo 64

        parser.parse(
            TestJwtProvider.createToken(allowedTenants = maximumValues + "tenant-overflow"),
        ).shouldBeNull()
    }

    private fun properties(secret: String): JwtSecurityProperties =
        JwtSecurityProperties(
            enabled = true,
            secret = secret,
            issuer = TestJwtProvider.issuer,
            audience = TestJwtProvider.audience,
            allowedClockSkew = Duration.ofSeconds(30),
        )
}

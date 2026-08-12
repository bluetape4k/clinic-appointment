package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

class PatientJwtIssuerTest {

    private val issuedAt = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS)
    private val properties = JwtSecurityProperties(
        enabled = true,
        secret = TestJwtProvider.secret,
        issuer = TestJwtProvider.issuer,
        audience = TestJwtProvider.audience,
        allowedClockSkew = Duration.ofSeconds(30),
    )
    private val issuer = PatientJwtIssuer(properties, Clock.fixed(issuedAt, java.time.ZoneOffset.UTC))
    private val parser = JwtTokenParser(properties, Clock.fixed(issuedAt, java.time.ZoneOffset.UTC))

    @Test
    fun `patient token carries strict subject tenant and nbf claims`() {
        val expectedExpiresAt = issuedAt.plus(Duration.ofHours(1))
        val token = issuer.issue("tenant-a", "patient-subject-1", expectedExpiresAt)
        val signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TestJwtProvider.secret))
        val claims = Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(TestJwtProvider.issuer)
            .requireAudience(TestJwtProvider.audience)
            .build()
            .parseSignedClaims(token)
            .payload

        claims.subject shouldBeEqualTo "patient-subject-1"
        claims["patientSubject"] shouldBeEqualTo "patient-subject-1"
        claims["allowedTenants"] shouldBeEqualTo listOf("tenant-a")
        claims["roles"] shouldBeEqualTo listOf(SchedulingRole.PATIENT)
        claims.notBefore shouldBeEqualTo claims.issuedAt
        parser.parse(token).shouldNotBeNull().apply {
            actorType shouldBeEqualTo ActorType.PATIENT
            roles shouldBeEqualTo setOf(SchedulingRole.PATIENT)
            allowedTenants shouldBeEqualTo setOf("tenant-a")
            patientSubjectId shouldBeEqualTo "patient-subject-1"
            expiresAt shouldBeEqualTo expectedExpiresAt
        }
    }

    @Test
    fun `issuer rejects non canonical tenant and unsafe patient subject`() {
        val expiresAt = issuedAt.plus(Duration.ofHours(1))

        assertFailsWith<IllegalArgumentException> {
            issuer.issue("Tenant-A", "patient-subject-1", expiresAt)
        }
        assertFailsWith<IllegalArgumentException> {
            issuer.issue("tenant-a", "patient subject", expiresAt)
        }
    }
}

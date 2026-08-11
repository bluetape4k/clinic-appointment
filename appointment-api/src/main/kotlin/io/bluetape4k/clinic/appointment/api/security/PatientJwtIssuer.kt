package io.bluetape4k.clinic.appointment.api.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/** 기존 JWT verification contract와 같은 signing key로 PATIENT token을 발급합니다. */
class PatientJwtIssuer(
    private val properties: JwtSecurityProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(properties.secret))

    /** opaque patient subject와 단일 tenant grant를 가진 token을 발급합니다. */
    fun issue(
        tenantCode: String,
        patientSubject: String,
        expiresAt: Instant,
    ): String {
        require(tenantCode.isNotBlank()) { "tenantCode must not be blank" }
        require(patientSubject.isNotBlank()) { "patientSubject must not be blank" }
        val issuedAt = Instant.now(clock)
        require(expiresAt.isAfter(issuedAt)) { "patient token expiration must be in the future" }
        return Jwts.builder()
            .subject(patientSubject)
            .id(UUID.randomUUID().toString())
            .issuer(properties.issuer)
            .audience().add(properties.audience).and()
            .issuedAt(Date.from(issuedAt))
            .notBefore(Date.from(issuedAt))
            .expiration(Date.from(expiresAt))
            .claim("roles", listOf(SchedulingRole.PATIENT))
            .claim("actorType", ActorType.PATIENT.name)
            .claim("allowedTenants", listOf(tenantCode))
            .claim("allowedClinicIds", emptyList<Long>())
            .claim("scope", "")
            .claim("assurance", AuthenticationAssurance.PASSWORD.name)
            .claim("patientSubject", patientSubject)
            .claim("auth_time", issuedAt.epochSecond)
            .signWith(signingKey, Jwts.SIG.HS256)
            .compact()
    }
}

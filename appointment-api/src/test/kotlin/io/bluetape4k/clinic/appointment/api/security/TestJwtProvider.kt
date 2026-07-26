package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.Base64
import java.util.Date

/**
 * 테스트용 JWT 토큰 생성 유틸리티.
 */
object TestJwtProvider {

    private const val TEST_SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1hcHBvaW50bWVudC1zY2hlZHVsaW5nLXN5c3RlbS0yNTY="
    private const val TEST_ISSUER = "appointment-auth-service"

    private val signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET))

    val secret: String = TEST_SECRET
    val issuer: String = TEST_ISSUER

    fun createToken(
        userId: String = "test-user",
        clinicId: Long? = 1L,
        roles: List<String> = listOf(SchedulingRole.ADMIN),
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
        scopes: Set<String> = emptySet(),
        catalogSourceAuthorities: Set<String> = emptySet(),
        expirationMs: Long = 3600000,
    ): String {
        val now = Date()
        val builder = Jwts.builder()
            .subject(userId)
            .issuer(TEST_ISSUER)
            .issuedAt(now)
            .expiration(Date(now.time + expirationMs))
            .claim("roles", roles)
            .claim("allowedTenants", allowedTenants)
            .claim("scope", scopes.sorted().joinToString(" "))
            .claim("catalogSourceAuthorities", catalogSourceAuthorities.sorted())

        if (clinicId != null) {
            builder.claim("clinicId", clinicId)
        }

        return builder.signWith(signingKey).compact()
    }

    fun adminToken(
        clinicId: Long? = 1L,
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
    ): String =
        createToken(
            userId = "admin-user",
            clinicId = clinicId,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = allowedTenants,
        )

    fun staffToken(
        clinicId: Long = 1L,
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
    ): String =
        createToken(
            userId = "staff-user",
            clinicId = clinicId,
            roles = listOf(SchedulingRole.STAFF),
            allowedTenants = allowedTenants,
        )

    fun doctorToken(
        clinicId: Long = 1L,
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
    ): String =
        createToken(
            userId = "doctor-user",
            clinicId = clinicId,
            roles = listOf(SchedulingRole.DOCTOR),
            allowedTenants = allowedTenants,
        )

    fun patientToken(
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
    ): String =
        createToken(
            userId = "patient-user",
            clinicId = null,
            roles = listOf(SchedulingRole.PATIENT),
            allowedTenants = allowedTenants,
        )

    fun expiredToken(): String =
        createToken(expirationMs = -1000)
}

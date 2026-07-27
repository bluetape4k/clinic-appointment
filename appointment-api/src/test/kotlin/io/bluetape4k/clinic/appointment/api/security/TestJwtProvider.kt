package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.MacAlgorithm
import io.jsonwebtoken.security.Keys
import java.util.Base64
import java.util.Date

/**
 * 테스트용 JWT 토큰 생성 유틸리티.
 */
object TestJwtProvider {

    private const val TEST_SECRET =
        "dGVzdC1zZWNyZXQta2V5LWZvci1hcHBvaW50bWVudC1zY2hlZHVsaW5nLXN5c3RlbS01MTItYml0LW1hdGVyaWFsIQ=="
    private const val TEST_ISSUER = "appointment-auth-service"
    private const val TEST_AUDIENCE = "appointment-api"

    private val signingKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET))

    val secret: String = TEST_SECRET
    val issuer: String = TEST_ISSUER
    val audience: String = TEST_AUDIENCE

    fun createToken(
        userId: String = "test-user",
        clinicId: Long? = 1L,
        roles: List<String> = listOf(SchedulingRole.ADMIN),
        actorType: ActorType = roles.firstOrNull()
            ?.let(ActorType::valueOf)
            ?: ActorType.ADMIN,
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
        allowedClinicIds: Collection<Number> = clinicId?.let(::setOf) ?: emptySet(),
        scopes: Set<String> = emptySet(),
        catalogSourceAuthorities: Set<String> = emptySet(),
        patientSubjectId: String? = null,
        assurance: AuthenticationAssurance = AuthenticationAssurance.MFA,
        issuer: String = TEST_ISSUER,
        audience: String? = TEST_AUDIENCE,
        tokenId: String? = "test-jti",
        issuedAt: Date? = Date(),
        authenticatedAt: Date? = issuedAt,
        notBefore: Date? = null,
        expiration: Date = Date((issuedAt ?: Date()).time + 3_600_000),
        algorithm: MacAlgorithm = Jwts.SIG.HS256,
    ): String {
        val builder = Jwts.builder()
            .subject(userId)
            .issuer(issuer)
            .expiration(expiration)
            .claim("roles", roles)
            .claim("actorType", actorType.name)
            .claim("allowedTenants", allowedTenants)
            .claim("allowedClinicIds", allowedClinicIds.toList())
            .claim("scope", scopes.sorted().joinToString(" "))
            .claim("catalogSourceAuthorities", catalogSourceAuthorities.sorted())
            .claim("assurance", assurance.name)

        audience?.let { builder.audience().add(it).and() }
        tokenId?.let(builder::id)
        issuedAt?.let(builder::issuedAt)
        authenticatedAt?.let { builder.claim("auth_time", it.time / 1_000) }
        notBefore?.let(builder::notBefore)
        patientSubjectId?.let { builder.claim("patientSubject", it) }

        if (clinicId != null) {
            builder.claim("clinicId", clinicId)
        }

        return builder.signWith(signingKey, algorithm).compact()
    }

    fun adminToken(
        clinicId: Long? = 1L,
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
    ): String =
        createToken(
            userId = "admin-user",
            clinicId = clinicId,
            roles = listOf(SchedulingRole.ADMIN),
            actorType = ActorType.ADMIN,
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
            actorType = ActorType.STAFF,
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
            actorType = ActorType.DOCTOR,
            allowedTenants = allowedTenants,
        )

    fun patientToken(
        allowedTenants: List<String> = listOf(TenantGroups.DEFAULT_TENANT_CODE),
    ): String =
        createToken(
            userId = "patient-user",
            clinicId = null,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedTenants = allowedTenants,
            patientSubjectId = "patient-subject",
        )

    fun expiredToken(): String =
        createToken(expiration = Date(System.currentTimeMillis() - 31_000))
}

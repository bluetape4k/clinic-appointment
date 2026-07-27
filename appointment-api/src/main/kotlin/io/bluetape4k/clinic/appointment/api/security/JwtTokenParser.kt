package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * Fail-closed Gateway JWT verifier and principal mapper.
 *
 * Only HS256, the configured issuer/audience, bounded time claims, and the
 * closed scheduling claim contract are accepted. Rejections return `null` and
 * emit only a generic log message: raw tokens, claim values, and parser details
 * are never exposed.
 *
 * Required identity strings use safe ASCII and are limited to 160 characters;
 * tenant codes to 64 and individual scopes to 128. Role, tenant, clinic, scope,
 * and catalog-authority collections contain at most 64 entries. Clinic IDs are
 * positive integers. `iat`, `exp`, and numeric `auth_time` are epoch seconds;
 * `iat` and `auth_time` cannot be later than the verifier clock plus configured
 * skew, and `auth_time` cannot be later than `iat` plus that skew.
 *
 * @param properties Trusted verification and clock-skew configuration.
 * @param clock UTC-capable verifier clock. Production uses the system UTC
 * clock; tests may inject a fixed clock for boundary cases.
 */
class JwtTokenParser(
    private val properties: JwtSecurityProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object : KLogging() {
        private const val CLAIM_ALLOWED_TENANTS = "allowedTenants"
        private const val CLAIM_ALLOWED_CLINIC_IDS = "allowedClinicIds"
        private const val CLAIM_CATALOG_SOURCE_AUTHORITIES = "catalogSourceAuthorities"
        private const val CLAIM_CLINIC_ID = "clinicId"
        private const val CLAIM_ROLES = "roles"
        private const val CLAIM_SCOPE = "scope"
        private const val CLAIM_ACTOR_TYPE = "actorType"
        private const val CLAIM_PATIENT_SUBJECT = "patientSubject"
        private const val CLAIM_ASSURANCE = "assurance"
        private const val CLAIM_AUTH_TIME = "auth_time"
        private const val MAX_TOKEN_LENGTH = 8_192
        private const val MAX_COLLECTION_SIZE = 64
        private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,159}")
        private val SAFE_TENANT_CODE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        private val SAFE_SCOPE = Regex("[A-Za-z0-9][A-Za-z0-9:._/-]{0,127}")
    }

    private val signingKey = run {
        val keyBytes = Base64.getDecoder().decode(properties.secret)
        Keys.hmacShaKeyFor(keyBytes)
    }

    /**
     * Verifies one compact JWT and maps its closed claims to a principal.
     *
     * @param token Compact bearer token. Blank or values over 8,192 characters
     * are rejected before cryptographic parsing.
     * @return Verified immutable principal, or `null` for every authentication
     * failure without disclosing which claim failed.
     */
    fun parse(token: String): SchedulingUserPrincipal? {
        return try {
            require(token.isNotBlank() && token.length <= MAX_TOKEN_LENGTH)
            val claims: Claims = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer)
                .requireAudience(properties.audience)
                .clockSkewSeconds(properties.allowedClockSkew.seconds)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .build()
                .parseSignedClaims(token)
                .payload

            val userId = claims.subject.requireSafeIdentifier("subject")
            val tokenId = claims.id.requireSafeIdentifier("jti")
            val issuedAt = requireNotNull(claims.issuedAt) { "iat is required" }.toInstant()
            requireNotNull(claims.expiration) { "exp is required" }
            val authenticatedAt = claims.readEpochSeconds(CLAIM_AUTH_TIME)
            validateTimes(issuedAt, authenticatedAt)

            val actorType = claims.readEnum<ActorType>(CLAIM_ACTOR_TYPE)
            val roles = claims.readRequiredStringSet(CLAIM_ROLES)
                .onEach { require(it in ALLOWED_ROLES) { "unknown role" } }
            val allowedTenants = claims.readRequiredStringSet(CLAIM_ALLOWED_TENANTS)
                .onEach { require(SAFE_TENANT_CODE.matches(it)) { "invalid tenant code" } }
            val allowedClinicIds = claims.readLongSet(CLAIM_ALLOWED_CLINIC_IDS)
            val clinicId = claims[CLAIM_CLINIC_ID]?.let(::readPositiveLong)
            require(clinicId == null || clinicId in allowedClinicIds) {
                "legacy clinicId must belong to allowedClinicIds"
            }
            val scopes = claims.readScopes()
            val catalogSourceAuthorities = claims.readOptionalStringSet(CLAIM_CATALOG_SOURCE_AUTHORITIES)
            val patientSubjectId = claims[CLAIM_PATIENT_SUBJECT]?.toString()
                ?.requireSafeIdentifier(CLAIM_PATIENT_SUBJECT)
            val assurance = claims.readEnum<AuthenticationAssurance>(CLAIM_ASSURANCE)
            validateActorClaims(actorType, roles, patientSubjectId, assurance)

            SchedulingUserPrincipal(
                userId = userId,
                clinicId = clinicId,
                roles = roles,
                allowedTenants = allowedTenants,
                scopes = scopes,
                catalogSourceAuthorities = catalogSourceAuthorities,
                actorType = actorType,
                allowedClinicIds = allowedClinicIds,
                patientSubjectId = patientSubjectId,
                assurance = assurance,
                issuer = claims.issuer,
                tokenId = tokenId,
                authenticatedAt = authenticatedAt,
            )
        } catch (_: Exception) {
            log.warn { "Gateway JWT authentication rejected" }
            null
        }
    }

    private fun validateTimes(issuedAt: Instant, authenticatedAt: Instant) {
        val latestAllowed = Instant.now(clock).plus(properties.allowedClockSkew)
        require(!issuedAt.isAfter(latestAllowed)) { "iat is in the future" }
        require(!authenticatedAt.isAfter(latestAllowed)) { "auth_time is in the future" }
        require(!authenticatedAt.isAfter(issuedAt.plus(properties.allowedClockSkew))) {
            "auth_time must not be after iat"
        }
    }

    private fun validateActorClaims(
        actorType: ActorType,
        roles: Set<String>,
        patientSubjectId: String?,
        assurance: AuthenticationAssurance,
    ) {
        require(actorType.name in roles) { "actorType requires its matching role" }
        when (actorType) {
            ActorType.PATIENT -> {
                require(roles == setOf(SchedulingRole.PATIENT)) { "patient role conflict" }
                require(patientSubjectId != null) { "patientSubject is required" }
            }
            ActorType.SYSTEM -> {
                require(roles == setOf(SchedulingRole.SYSTEM)) { "system role conflict" }
                require(patientSubjectId == null) { "system cannot carry patientSubject" }
                require(assurance == AuthenticationAssurance.SERVICE) {
                    "system requires service assurance"
                }
            }
            else -> {
                require(SchedulingRole.PATIENT !in roles && SchedulingRole.SYSTEM !in roles) {
                    "workforce role conflict"
                }
                require(patientSubjectId == null) { "workforce cannot carry patientSubject" }
                require(assurance != AuthenticationAssurance.SERVICE) {
                    "workforce cannot use service assurance"
                }
            }
        }
    }

    private fun Claims.readRequiredStringSet(claimName: String): Set<String> {
        val values = requireNotNull(this[claimName] as? Collection<*>) { "$claimName is required" }
        require(values.isNotEmpty() && values.size <= MAX_COLLECTION_SIZE) { "$claimName has invalid size" }
        return values.map {
            (it as? String)?.requireSafeIdentifier(claimName)
                ?: throw IllegalArgumentException("$claimName must contain strings")
        }.toSet()
    }

    private fun Claims.readOptionalStringSet(claimName: String): Set<String> {
        val raw = this[claimName] ?: return emptySet()
        val values = raw as? Collection<*> ?: throw IllegalArgumentException("$claimName must be a collection")
        require(values.size <= MAX_COLLECTION_SIZE) { "$claimName has invalid size" }
        return values.map {
            (it as? String)?.requireSafeIdentifier(claimName)
                ?: throw IllegalArgumentException("$claimName must contain strings")
        }.toSet()
    }

    private fun Claims.readLongSet(claimName: String): Set<Long> {
        val values = requireNotNull(this[claimName] as? Collection<*>) { "$claimName is required" }
        require(values.size <= MAX_COLLECTION_SIZE) { "$claimName has invalid size" }
        return values.map(::readPositiveLong).toSet()
    }

    private fun Claims.readScopes(): Set<String> {
        val raw = this[CLAIM_SCOPE] as? String ?: throw IllegalArgumentException("scope is required")
        if (raw.isBlank()) return emptySet()
        val scopes = raw.split(Regex("\\s+"))
        require(scopes.size <= MAX_COLLECTION_SIZE && scopes.all(SAFE_SCOPE::matches)) {
            "scope contains invalid values"
        }
        return scopes.toSet()
    }

    private fun Claims.readEpochSeconds(claimName: String): Instant {
        val seconds = readIntegralLong(this[claimName], claimName)
        require(seconds >= 0) { "$claimName must be non-negative" }
        return Instant.ofEpochSecond(seconds)
    }

    private inline fun <reified T : Enum<T>> Claims.readEnum(claimName: String): T =
        enumValueOf(
            (this[claimName] as? String)?.requireSafeIdentifier(claimName)
                ?: throw IllegalArgumentException("$claimName is required")
        )

    private fun String?.requireSafeIdentifier(claimName: String): String {
        val value = this ?: throw IllegalArgumentException("$claimName is required")
        require(SAFE_IDENTIFIER.matches(value)) { "$claimName is invalid" }
        return value
    }

    private fun readPositiveLong(value: Any?): Long {
        return readIntegralLong(value, "clinic ID")
            .also { require(it > 0) { "clinic ID must be positive" } }
    }

    private fun readIntegralLong(value: Any?, claimName: String): Long =
        when (value) {
            is Byte,
            is Short,
            is Int,
            is Long,
            -> (value as Number).toLong()
            is BigInteger -> value.longValueExact()
            null -> throw IllegalArgumentException("$claimName is required")
            else -> throw IllegalArgumentException("$claimName must be an integer")
        }

    private val ALLOWED_ROLES = setOf(
        SchedulingRole.ADMIN,
        SchedulingRole.STAFF,
        SchedulingRole.DOCTOR,
        SchedulingRole.PATIENT,
        SchedulingRole.SYSTEM,
    )
}

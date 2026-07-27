package io.bluetape4k.clinic.appointment.api.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.io.Serializable
import java.time.Instant

/**
 * Immutable authenticated identity extracted only from a verified Gateway JWT.
 *
 * Production instances must come from [JwtTokenParser]. Public defaults and
 * the compatibility constructor exist for older tests and non-command read
 * paths; they do not create complete audit evidence. In particular, blank
 * [issuer], blank [tokenId], and [Instant.EPOCH] [authenticatedAt] are sentinel
 * values that [ActorContextResolver] rejects before a scheduling command.
 *
 * An empty [allowedClinicIds] set grants tenant-only authority, never access to
 * every clinic. [roles], [actorType], [patientSubjectId], and [assurance] are a
 * single invariant set: patient and system identities cannot be combined with
 * workforce identities, and only system identities may use service assurance.
 *
 * @property userId Stable non-secret Gateway subject. It becomes
 * [io.bluetape4k.clinic.appointment.api.security.ActorContext.actorId];
 * display names, email addresses, and access tokens are forbidden.
 * @property clinicId Legacy single-clinic claim, or `null` for tenant-wide and
 * multi-clinic identities. When present it must belong to [allowedClinicIds].
 * @property roles Closed scheduling role names. JWT parsing rejects unknown or
 * actor-type-conflicting combinations before this principal is created.
 * @property allowedTenants Non-empty set of bounded tenant codes authorized by
 * the Gateway. Path authorization performs an exact membership check.
 * @property scopes Bounded OAuth-style capabilities exposed as `SCOPE_*`
 * Spring authorities. Values never include whitespace.
 * @property catalogSourceAuthorities Legacy bounded catalog producer
 * authorities retained for the catalog-sync API.
 * @property actorType Primary identity category. Workforce types may hold
 * compatible workforce roles; `PATIENT` and `SYSTEM` cannot mix with them.
 * @property allowedClinicIds Positive clinic IDs authorized by the Gateway.
 * An empty set means tenant-level access only, not unrestricted clinic access.
 * @property patientSubjectId Stable patient-domain subject required only for a
 * `PATIENT` actor. It is not a patient name or raw medical identifier.
 * @property assurance Authentication evidence asserted by the Gateway. This
 * service records and authorizes the evidence but does not perform MFA.
 * @property issuer Verified JWT issuer copied for audit.
 * @property tokenId Non-blank verified JWT `jti`, used as bounded audit
 * evidence rather than an idempotency key.
 * @property authenticatedAt UTC instant from the JWT `auth_time` claim.
 */
data class SchedulingUserPrincipal(
    val userId: String,
    val clinicId: Long?,
    val roles: Set<String>,
    val allowedTenants: Set<String>,
    val scopes: Set<String> = emptySet(),
    val catalogSourceAuthorities: Set<String> = emptySet(),
    val actorType: ActorType = roles.firstOrNull()
        ?.let(ActorType::valueOf)
        ?: ActorType.ADMIN,
    val allowedClinicIds: Set<Long> = clinicId?.let(::setOf) ?: emptySet(),
    val patientSubjectId: String? = null,
    val assurance: AuthenticationAssurance = AuthenticationAssurance.PASSWORD,
    val issuer: String = "",
    val tokenId: String = "",
    val authenticatedAt: Instant = Instant.EPOCH,
) : UserDetails, Serializable {
    companion object {
        private const val serialVersionUID = 4L
    }

    constructor(
        userId: String,
        clinicId: Long?,
        roles: List<String>,
        allowedTenants: List<String>,
    ) : this(
        userId = userId,
        clinicId = clinicId,
        roles = roles.toSet(),
        allowedTenants = allowedTenants.toSet(),
    )

    override fun getAuthorities(): Collection<GrantedAuthority> =
        buildSet {
            roles.mapTo(this) { SimpleGrantedAuthority("ROLE_$it") }
            scopes.mapTo(this) { SimpleGrantedAuthority("SCOPE_$it") }
        }

    override fun getPassword(): String = ""
    override fun getUsername(): String = userId
}

/** Closed scheduling roles accepted from the trusted token contract. */
object SchedulingRole {
    /** Hospital administrator eligible for tenant administration policies. */
    const val ADMIN = "ADMIN"
    /** Practitioner eligible for explicitly authorized clinical read paths. */
    const val DOCTOR = "DOCTOR"
    /** Clinic staff eligible for explicitly authorized operational policies. */
    const val STAFF = "STAFF"
    /** Patient identity restricted to patient-scoped policies. */
    const val PATIENT = "PATIENT"
    /** Non-human integration identity, always separated from human roles. */
    const val SYSTEM = "SYSTEM"
}

/** Closed identity category asserted by the trusted Gateway. */
enum class ActorType {
    /** Tenant or clinic administrator. */
    ADMIN,

    /** Operational clinic staff member. */
    STAFF,

    /** Medical practitioner. */
    DOCTOR,

    /** Customer receiving care; requires a patient-domain subject. */
    PATIENT,

    /** Non-human service identity; requires service assurance. */
    SYSTEM,
}

/** Authentication evidence level asserted by the trusted Gateway. */
enum class AuthenticationAssurance {
    /** Authenticated with a single knowledge/possession factor. */
    PASSWORD,

    /** Authenticated with verified multi-factor evidence. */
    MFA,

    /** Authenticated non-human workload identity. */
    SERVICE,
}

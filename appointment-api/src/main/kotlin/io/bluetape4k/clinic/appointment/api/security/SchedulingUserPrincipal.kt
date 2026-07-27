package io.bluetape4k.clinic.appointment.api.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.io.Serializable

/**
 * User identity extracted from JWT claims.
 *
 * @property userId user ID
 * @property clinicId clinic ID associated with the user, when available
 * @property roles scheduling roles
 * @property allowedTenants tenant codes this user can access
 * @property scopes OAuth-style authorities exposed as `SCOPE_*` Spring authorities
 * @property catalogSourceAuthorities catalog source authorities this caller may write through the catalog sync API
 */
data class SchedulingUserPrincipal(
    val userId: String,
    val clinicId: Long?,
    val roles: Set<String>,
    val allowedTenants: Set<String>,
    val scopes: Set<String> = emptySet(),
    val catalogSourceAuthorities: Set<String> = emptySet(),
) : UserDetails, Serializable {
    companion object {
        private const val serialVersionUID = 3L
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

/**
 * 사용자 역할.
 */
object SchedulingRole {
    const val ADMIN = "ADMIN"
    const val DOCTOR = "DOCTOR"
    const val STAFF = "STAFF"
    const val PATIENT = "PATIENT"
}

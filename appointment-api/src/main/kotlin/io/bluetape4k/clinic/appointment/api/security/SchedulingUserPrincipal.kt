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
 */
data class SchedulingUserPrincipal(
    val userId: String,
    val clinicId: Long?,
    val roles: List<String>,
    val allowedTenants: List<String>,
) : UserDetails, Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }

    override fun getAuthorities(): Collection<GrantedAuthority> =
        roles.map { SimpleGrantedAuthority("ROLE_$it") }

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

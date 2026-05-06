package io.bluetape4k.clinic.appointment.api.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.io.Serializable

/**
 * JWT Claims에서 추출한 사용자 정보.
 *
 * @property userId 사용자 ID
 * @property clinicId 사용자가 속한 병원 ID
 * @property roles 사용자 역할 목록
 */
data class SchedulingUserPrincipal(
    val userId: String,
    val clinicId: Long?,
    val roles: List<String>,
) : UserDetails, Serializable {
    companion object {
        private const val serialVersionUID = 1L
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

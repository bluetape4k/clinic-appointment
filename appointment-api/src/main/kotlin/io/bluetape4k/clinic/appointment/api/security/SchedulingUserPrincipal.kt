package io.bluetape4k.clinic.appointment.api.security

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.io.Serializable
import java.time.Instant

/**
 * 검증된 Gateway JWT에서만 추출되는 불변 authenticated identity이다.
 *
 * 운영 instance는 [JwtTokenParser]에서 만들어져야 한다. public default와 호환성 constructor는
 * 오래된 테스트와 non-command read path를 위해 남아 있으며, 완전한 audit evidence를 만들지 않는다.
 * 특히 blank [issuer], blank [tokenId], [Instant.EPOCH] [authenticatedAt]은 스케줄링 명령 전에
 * [ActorContextResolver]가 거절하는 sentinel 값이다.
 *
 * 빈 [allowedClinicIds] 집합은 tenant-only authority를 의미하며 모든 clinic 접근 권한이 아니다.
 * [roles], [actorType], [patientSubjectId], [assurance]는 하나의 invariant set이다. patient와
 * system identity는 workforce identity와 섞일 수 없고, service assurance는 system identity만 사용할 수 있다.
 *
 * @property userId 안정적인 비밀 없는 Gateway subject. 이 값은
 * [io.bluetape4k.clinic.appointment.api.security.ActorContext.actorId]가 된다.
 * display name, email address, access token은 금지된다.
 * @property clinicId legacy single-clinic claim. tenant-wide 또는 multi-clinic identity이면 `null`.
 * 존재할 때는 [allowedClinicIds]에 포함되어야 한다.
 * @property roles 닫힌 scheduling role 이름 집합. JWT parsing은 이 principal을 만들기 전에
 * 알 수 없는 role 또는 actor-type과 충돌하는 조합을 거절한다.
 * @property allowedTenants Gateway가 허가한 길이 제한 tenant code의 non-empty set.
 * path authorization은 정확한 membership check를 수행한다.
 * @property scopes `SCOPE_*` Spring authority로 노출되는 길이 제한 OAuth-style capability.
 * 값에는 whitespace가 포함되지 않는다.
 * @property catalogSourceAuthorities catalog-sync API를 위해 유지하는 legacy bounded catalog producer authority.
 * @property actorType 기본 identity category. workforce type은 호환되는 workforce role을 가질 수 있지만,
 * `PATIENT`와 `SYSTEM`은 workforce와 섞일 수 없다.
 * @property allowedClinicIds Gateway가 허가한 양수 clinic ID 집합. 빈 집합은 tenant-level access만
 * 의미하며 unrestricted clinic access가 아니다.
 * @property patientSubjectId `PATIENT` actor에만 필요한 안정적인 patient-domain subject.
 * 환자 이름 또는 raw medical identifier가 아니다.
 * @property assurance Gateway가 주장한 authentication evidence. 이 service는 evidence를 기록하고
 * 인가에 사용하지만 MFA를 직접 수행하지 않는다.
 * @property issuer 감사 목적으로 복사한 검증된 JWT issuer.
 * @property tokenId 검증된 non-blank JWT `jti`. idempotency key가 아니라 길이 제한 audit evidence이다.
 * @property authenticatedAt JWT `auth_time` claim에서 온 UTC instant.
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

/** 신뢰된 token contract에서 허용하는 닫힌 scheduling role 집합. */
object SchedulingRole {
    /** tenant administration policy를 수행할 수 있는 병원 관리자. */
    const val ADMIN = "ADMIN"
    /** 명시적으로 허가된 clinical read path를 사용할 수 있는 의료진. */
    const val DOCTOR = "DOCTOR"
    /** 명시적으로 허가된 operational policy를 수행할 수 있는 clinic staff. */
    const val STAFF = "STAFF"
    /** patient-scoped policy로 제한되는 patient identity. */
    const val PATIENT = "PATIENT"
    /** human role과 항상 분리되는 non-human integration identity. */
    const val SYSTEM = "SYSTEM"
}

/** 신뢰된 Gateway가 주장하는 닫힌 identity category. */
enum class ActorType {
    /** tenant 또는 clinic administrator. */
    ADMIN,

    /** clinic 운영 staff. */
    STAFF,

    /** 진료를 수행하는 practitioner. */
    DOCTOR,

    /** 진료를 받는 고객. patient-domain subject가 필요하다. */
    PATIENT,

    /** non-human service identity. service assurance가 필요하다. */
    SYSTEM,
}

/** 신뢰된 Gateway가 주장하는 authentication evidence level. */
enum class AuthenticationAssurance {
    /** 단일 knowledge/possession factor로 인증됨. */
    PASSWORD,

    /** 검증된 multi-factor evidence로 인증됨. */
    MFA,

    /** 인증된 non-human workload identity. */
    SERVICE,
}

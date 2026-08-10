package io.bluetape4k.clinic.appointment.model.service

import java.io.Serializable

/**
 * 검증된 데이터베이스 권위 저장소의 테넌트-병원 범위다.
 *
 * 이 값은 인증 객체가 아니다. HTTP 또는 작업 경계에서 테넌트와 병원의 소속을 확인한 뒤
 * 생성해야 하며, 이후 core·solver·event·notification 경계에 명시적으로 전달한다.
 */
data class TenantClinicScope(
    val tenantGroupId: Long,
    val clinicId: Long,
) : Serializable {

    companion object {
        private const val serialVersionUID = 1L
    }

    init {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
    }

    /** 두 식별자를 충돌 없이 구분하는 캐시 키 표현이다. */
    fun cacheKey(): String = "$tenantGroupId:$clinicId"
}

/**
 * 데이터베이스 소속 검사를 통과한 tenant-clinic 범위입니다.
 *
 * 공개 구현 생성자가 없습니다. repository factory가 소속 검사를 통과한 구현만 반환합니다.
 * 외부 adapter는 raw ID를 직접 조합하지
 * 말고 [io.bluetape4k.clinic.appointment.repository.AppointmentRepository.findVerifiedScope]
 * 또는 [io.bluetape4k.clinic.appointment.repository.AppointmentRepository.findVerifiedScopeByIdAndTenant]
 * 결과를 전달해야 합니다. 기존 [TenantClinicScope]는 호환성을 위해 유지되는 구조적 값이며
 * 인증 또는 데이터베이스 소속 증명으로 사용하면 안 됩니다.
 */
sealed interface VerifiedTenantClinicScope : Serializable {
    val tenantGroupId: Long
    val clinicId: Long

    /** 기존 scope 기반 API와 연결해야 하는 호환 변환입니다. */
    fun toScope(): TenantClinicScope

    /** 두 식별자를 충돌 없이 구분하는 캐시 키 표현입니다. */
    fun cacheKey(): String = "$tenantGroupId:$clinicId"

    companion object {
        const val serialVersionUID: Long = 1L

        internal fun fromRepository(
            tenantGroupId: Long,
            clinicId: Long,
        ): VerifiedTenantClinicScope {
            require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
            require(clinicId > 0L) { "clinicId must be positive" }
            return RepositoryVerifiedTenantClinicScope.create(tenantGroupId, clinicId)
        }
    }
}

/** 외부에서 생성할 수 없고 repository factory가 반환하는 verified scope 구현입니다. */
private class RepositoryVerifiedTenantClinicScope private constructor(
    override val tenantGroupId: Long,
    override val clinicId: Long,
) : VerifiedTenantClinicScope {
    companion object {
        private const val serialVersionUID: Long = 1L

        fun create(tenantGroupId: Long, clinicId: Long): VerifiedTenantClinicScope =
            RepositoryVerifiedTenantClinicScope(tenantGroupId, clinicId)
    }

    override fun toScope(): TenantClinicScope = TenantClinicScope(tenantGroupId, clinicId)

    override fun equals(other: Any?): Boolean =
        this === other || (other is VerifiedTenantClinicScope &&
            tenantGroupId == other.tenantGroupId && clinicId == other.clinicId)

    override fun hashCode(): Int = 31 * tenantGroupId.hashCode() + clinicId.hashCode()

    override fun toString(): String =
        "VerifiedTenantClinicScope(tenantGroupId=$tenantGroupId, clinicId=$clinicId)"
}

/**
 * raw tenant/clinic ID를 database membership 검사 뒤 verified scope로 바꾸는 내부 경계입니다.
 * production 경로는 repository가 소유한 membership predicate만 주입합니다.
 */
internal class TenantClinicScopeResolver(
    private val belongsToTenant: (tenantGroupId: Long, clinicId: Long) -> Boolean,
) {

    fun resolve(
        tenantGroupId: Long,
        clinicId: Long,
    ): VerifiedTenantClinicScope? {
        require(tenantGroupId > 0L) { "tenantGroupId must be positive" }
        require(clinicId > 0L) { "clinicId must be positive" }
        return if (belongsToTenant(tenantGroupId, clinicId)) {
            VerifiedTenantClinicScope.fromRepository(tenantGroupId, clinicId)
        } else {
            null
        }
    }
}

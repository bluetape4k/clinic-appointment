package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import org.springframework.security.access.AccessDeniedException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * 요청 tenant를 해석하고, 해석한 tenant group을 기준으로 clinic 소유권을 검증합니다.
 */
class TenantClinicAccessChecker(
    private val tenantGroupRepository: TenantGroupRepository,
    private val clinicRepository: ClinicRepository,
    private val doctorRepository: DoctorRepository,
    private val treatmentTypeRepository: TreatmentTypeRepository,
    private val equipmentRepository: EquipmentRepository,
) {

    /**
     * [tenantCode]를 활성 tenant로 해석합니다. 현재 [TenantContext]를 사용할 수 있으면
     * 재사용하고, local no-op 보안 테스트와 filter를 거치지 않는 실행 경로에서는
     * DB 조회를 fallback으로 사용합니다.
     */
    fun requireTenant(tenantCode: String): TenantInfo {
        tenantCode.requireNotBlank("tenantCode")

        val currentTenant = TenantContext.current()
        if (currentTenant?.tenantCode == tenantCode) {
            return currentTenant
        }

        return transaction {
            tenantGroupRepository.findActiveByCode(tenantCode)?.let(TenantInfo::from)
        } ?: throw NoSuchElementException("Tenant not found")
    }

    /**
     * [clinicId]가 활성 [tenantCode]에 속하는지 검증합니다.
     */
    fun verifyClinic(tenantCode: String, clinicId: Long): TenantInfo {
        clinicId.requirePositiveNumber("clinicId")
        val tenant = requireTenant(tenantCode)

        val clinicExists = transaction {
            clinicRepository.findByIdAndTenant(clinicId, tenant.id) != null
        }

        if (!clinicExists) {
            throw NoSuchElementException("Clinic not found")
        }

        return tenant
    }

    /** tenant ownership와 인증 principal의 정확한 clinic allow-list를 함께 검증합니다. */
    fun verifyClinicForPrincipal(
        tenantCode: String,
        clinicId: Long,
        principal: SchedulingUserPrincipal,
    ): TenantInfo {
        val tenant = verifyClinic(tenantCode, clinicId)
        if (tenantCode !in principal.allowedTenants) {
            throw AccessDeniedException("Tenant scope is not authorized")
        }
        if (principal.roles.none { it == SchedulingRole.ADMIN || it == SchedulingRole.STAFF }) {
            throw AccessDeniedException("Clinic mutation role is not authorized")
        }
        if (principal.allowedClinicIds.isEmpty() || clinicId !in principal.allowedClinicIds) {
            throw AccessDeniedException("Clinic scope is not authorized")
        }
        return tenant
    }

    /**
     * 예약 범위 resource가 모두 활성 [tenantCode]의 [clinicId]에 속하는지 검증합니다.
     */
    fun verifySchedulingResources(
        tenantCode: String,
        clinicId: Long,
        doctorId: Long,
        treatmentTypeId: Long,
        equipmentId: Long?,
    ): TenantInfo {
        clinicId.requirePositiveNumber("clinicId")
        doctorId.requirePositiveNumber("doctorId")
        treatmentTypeId.requirePositiveNumber("treatmentTypeId")
        equipmentId?.requirePositiveNumber("equipmentId")

        val tenant = verifyClinic(tenantCode, clinicId)
        val valid = transaction {
            val doctor = doctorRepository.findByIdAndTenant(doctorId, tenant.id)
            val treatmentType = treatmentTypeRepository.findByIdAndTenant(treatmentTypeId, tenant.id)
            val equipment = equipmentId?.let { equipmentRepository.findByIdAndTenant(it, tenant.id) }

            doctor?.clinicId == clinicId &&
                treatmentType?.clinicId == clinicId &&
                (equipmentId == null || equipment?.clinicId == clinicId)
        }

        if (!valid) {
            throw NoSuchElementException("Scheduling resource not found")
        }

        return tenant
    }

    /**
     * [equipmentId]가 활성 tenant의 [clinicId]에 속하는지 검증합니다.
     */
    fun verifyEquipment(tenantCode: String, clinicId: Long, equipmentId: Long): TenantInfo {
        clinicId.requirePositiveNumber("clinicId")
        equipmentId.requirePositiveNumber("equipmentId")

        val tenant = verifyClinic(tenantCode, clinicId)
        val equipmentExists = transaction {
            equipmentRepository.findByIdAndTenant(equipmentId, tenant.id)?.clinicId == clinicId
        }

        if (!equipmentExists) {
            throw NoSuchElementException("Equipment not found")
        }

        return tenant
    }
}

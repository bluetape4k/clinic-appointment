package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.EquipmentRepository
import io.bluetape4k.clinic.appointment.repository.TenantGroupRepository
import io.bluetape4k.clinic.appointment.repository.TreatmentTypeRepository
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Resolves request tenants and verifies clinic ownership against the resolved
 * tenant group.
 */
class TenantClinicAccessChecker(
    private val tenantGroupRepository: TenantGroupRepository,
    private val clinicRepository: ClinicRepository,
    private val doctorRepository: DoctorRepository,
    private val treatmentTypeRepository: TreatmentTypeRepository,
    private val equipmentRepository: EquipmentRepository,
) {

    /**
     * Resolves [tenantCode] to an active tenant. The current [TenantContext] is
     * reused when available, and a DB lookup is used as a fallback for local
     * no-op security tests and non-filtered execution paths.
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
     * Verifies that [clinicId] belongs to the active [tenantCode].
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

    /**
     * Verifies that appointment-scoped resources all belong to [clinicId] in
     * the active [tenantCode].
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
     * Verifies that [equipmentId] belongs to [clinicId] in the active tenant.
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

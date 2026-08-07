package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Repository

/**
 * 장비 정보 저장소.
 *
 * 병원의 장비 목록 및 개별 장비 정보를 조회합니다.
 */
@Repository
class EquipmentRepository : LongJdbcRepository<EquipmentRecord> {
    companion object : KLogging()

    override val table = Equipments
    override fun extractId(entity: EquipmentRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): EquipmentRecord = toEquipmentRecord()

    /**
     * 소유 clinic이 [tenantGroupId]에 속할 때만 ID로 장비를 조회합니다.
     */
    fun findByIdAndTenant(equipmentId: Long, tenantGroupId: Long): EquipmentRecord? =
        Equipments
            .selectAll()
            .where {
                (Equipments.id eq equipmentId) and (Equipments.clinicId inSubQuery tenantClinicIds(tenantGroupId))
            }
            .firstOrNull()
            ?.toEquipmentRecord()

    /** 검증된 테넌트-병원 범위에 속한 장비만 조회합니다. */
    fun findByIdAndScope(equipmentId: Long, scope: TenantClinicScope): EquipmentRecord? =
        Equipments
            .selectAll()
            .where {
                (Equipments.id eq equipmentId) and
                    (Equipments.clinicId eq scope.clinicId) and
                    (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .firstOrNull()
            ?.toEquipmentRecord()

    /** 테넌트와 병원을 모두 포함하는 안정적인 캐시 키로 장비 목록을 조회합니다. */
    @Cacheable(cacheNames = ["clinic-equipments"], key = "#scope.cacheKey()", unless = "#result == null || #result.isEmpty()")
    fun findByScope(scope: TenantClinicScope): List<EquipmentRecord> =
        Equipments
            .selectAll()
            .where {
                (Equipments.clinicId eq scope.clinicId) and
                    (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toEquipmentRecord() }

    /** 테넌트-병원 범위를 SQL predicate에 포함한 페이징 목록을 조회합니다. */
    fun findPage(scope: TenantClinicScope, page: Int, size: Int): ExposedPage<EquipmentRecord> =
        findPage(page, size) {
            (Equipments.clinicId eq scope.clinicId) and
                (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }
}

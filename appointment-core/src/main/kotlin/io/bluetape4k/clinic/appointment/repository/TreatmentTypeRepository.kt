package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.ClinicKeysetCursor
import io.bluetape4k.clinic.appointment.model.dto.ClinicKeysetPage
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentEquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.exposed.core.ExposedPage
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Repository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select

/**
 * 시술 유형(TreatmentType) 저장소.
 *
 * 병원의 시술 목록 조회, 시술별 필수 장비 ID 목록 조회, 장비 수량 조회를 담당합니다.
 */
@Repository
class TreatmentTypeRepository : LongJdbcRepository<TreatmentTypeRecord> {
    companion object : KLogging() {
        private const val MAX_KEYSET_PAGE_SIZE = 100
    }

    override val table = TreatmentTypes
    override fun extractId(entity: TreatmentTypeRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): TreatmentTypeRecord = toTreatmentTypeRecord()

    /**
     * 소유 clinic이 [tenantGroupId]에 속할 때만 ID로 진료 유형을 조회합니다.
     */
    fun findByIdAndTenant(treatmentTypeId: Long, tenantGroupId: Long): TreatmentTypeRecord? =
        TreatmentTypes
            .selectAll()
            .where {
                (TreatmentTypes.id eq treatmentTypeId) and
                    (TreatmentTypes.clinicId inSubQuery tenantClinicIds(tenantGroupId))
            }
            .firstOrNull()
            ?.toTreatmentTypeRecord()

    /** 검증된 테넌트-병원 범위에 속한 진료 유형만 조회합니다. */
    fun findByIdAndScope(treatmentTypeId: Long, scope: TenantClinicScope): TreatmentTypeRecord? =
        TreatmentTypes
            .selectAll()
            .where {
                (TreatmentTypes.id eq treatmentTypeId) and
                    (TreatmentTypes.clinicId eq scope.clinicId) and
                    (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .firstOrNull()
            ?.toTreatmentTypeRecord()

    /**
     * 특정 시술 유형에 필요한 장비 ID 목록을 조회합니다.
     *
     * @param treatmentTypeId 시술 유형 ID
     * @return 필수 장비 ID 목록
     */
    internal fun findRequiredEquipmentIds(treatmentTypeId: Long): List<Long> =
        TreatmentEquipments
            .selectAll()
            .where { TreatmentEquipments.treatmentTypeId eq treatmentTypeId }
            .map { it[TreatmentEquipments.equipmentId].value }

    fun findRequiredEquipmentIds(treatmentTypeId: Long, scope: TenantClinicScope): List<Long> =
        TreatmentEquipments
            .selectAll()
            .where {
                (TreatmentEquipments.treatmentTypeId eq treatmentTypeId) and
                    (TreatmentEquipments.treatmentTypeId inSubQuery tenantTreatmentTypeIds(scope)) and
                    (TreatmentEquipments.equipmentId inSubQuery tenantEquipmentIds(scope))
            }
            .map { it[TreatmentEquipments.equipmentId].value }

    /**
     * 장비 ID 목록에 해당하는 장비별 수량을 조회합니다.
     *
     * @param equipmentIds 장비 ID 목록
     * @return 장비 ID → 수량 매핑
     */
    internal fun findEquipmentQuantities(equipmentIds: List<Long>): Map<Long, Int> =
        if (equipmentIds.isEmpty()) emptyMap()
        else Equipments
            .selectAll()
            .where { Equipments.id inList equipmentIds }
            .associate { it[Equipments.id].value to it[Equipments.quantity] }

    fun findEquipmentQuantities(equipmentIds: List<Long>, scope: TenantClinicScope): Map<Long, Int> =
        if (equipmentIds.isEmpty()) emptyMap()
        else Equipments
            .selectAll()
            .where {
                (Equipments.id inList equipmentIds) and
                    (Equipments.clinicId eq scope.clinicId) and
                    (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .associate { it[Equipments.id].value to it[Equipments.quantity] }

    /** 테넌트와 병원을 모두 포함하는 안정적인 캐시 키로 진료 유형을 조회합니다. */
    @Cacheable(cacheNames = ["clinic-treatment-types"], key = "#scope.cacheKey()", unless = "#result == null || #result.isEmpty()")
    fun findByScope(scope: TenantClinicScope): List<TreatmentTypeRecord> =
        TreatmentTypes
            .selectAll()
            .where {
                (TreatmentTypes.clinicId eq scope.clinicId) and
                    (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it.toTreatmentTypeRecord() }

    /** 테넌트-병원 범위를 SQL predicate에 포함한 페이징 목록을 조회합니다. */
    fun findPage(scope: TenantClinicScope, page: Int, size: Int): ExposedPage<TreatmentTypeRecord> =
        findPage(page, size) {
            (TreatmentTypes.clinicId eq scope.clinicId) and
                (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }

    /** tenant·clinic 범위에서 `(clinic_id, id)` 순서의 다음 진료 유형 묶음을 조회합니다. */
    fun findKeysetPage(
        scope: TenantClinicScope,
        cursor: ClinicKeysetCursor?,
        limit: Int,
    ): ClinicKeysetPage<TreatmentTypeRecord> {
        require(limit in 1..MAX_KEYSET_PAGE_SIZE) {
            "limit must be between 1 and $MAX_KEYSET_PAGE_SIZE"
        }
        require(cursor == null || cursor.clinicId == scope.clinicId) {
            "cursor clinicId must match scope clinicId"
        }

        val predicate =
            ((TreatmentTypes.clinicId eq scope.clinicId) and
                (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))) and
                treatmentTypeKeysetCondition(cursor)
        val rows = TreatmentTypes
            .selectAll()
            .where { predicate }
            .orderBy(TreatmentTypes.clinicId to SortOrder.ASC, TreatmentTypes.id to SortOrder.ASC)
            .limit(limit + 1)
            .toList()
        val hasNext = rows.size > limit
        val pageRows = rows.take(limit)

        return ClinicKeysetPage(
            content = pageRows.map { it.toTreatmentTypeRecord() },
            nextCursor = if (hasNext) {
                pageRows.lastOrNull()?.let { ClinicKeysetCursor(scope.clinicId, it[TreatmentTypes.id].value) }
            } else {
                null
            },
        )
    }

    fun findAllTreatmentEquipments(scope: TenantClinicScope): List<TreatmentEquipmentRecord> {
        val treatmentIds = TreatmentTypes
            .selectAll()
            .where {
                (TreatmentTypes.clinicId eq scope.clinicId) and
                    (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
            }
            .map { it[TreatmentTypes.id].value }

        if (treatmentIds.isEmpty()) return emptyList()

        return TreatmentEquipments
            .selectAll()
            .where {
                (TreatmentEquipments.treatmentTypeId inList treatmentIds) and
                    (TreatmentEquipments.equipmentId inSubQuery tenantEquipmentIds(scope))
            }
            .map { it.toTreatmentEquipmentRecord() }
    }
}

private fun tenantTreatmentTypeIds(scope: TenantClinicScope) =
    TreatmentTypes
        .select(TreatmentTypes.id)
        .where {
            (TreatmentTypes.clinicId eq scope.clinicId) and
                (TreatmentTypes.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }

private fun tenantEquipmentIds(scope: TenantClinicScope) =
    Equipments
        .select(Equipments.id)
        .where {
            (Equipments.clinicId eq scope.clinicId) and
                (Equipments.clinicId inSubQuery tenantClinicIds(scope.tenantGroupId))
        }

private fun treatmentTypeKeysetCondition(cursor: ClinicKeysetCursor?): Op<Boolean> =
    cursor?.let {
        (TreatmentTypes.clinicId greater it.clinicId) or
            ((TreatmentTypes.clinicId eq it.clinicId) and (TreatmentTypes.id greater it.id))
    } ?: Op.TRUE

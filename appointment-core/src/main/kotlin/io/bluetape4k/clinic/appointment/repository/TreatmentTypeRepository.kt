package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentEquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Repository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 시술 유형(TreatmentType) 저장소.
 *
 * 병원의 시술 목록 조회, 시술별 필수 장비 ID 목록 조회, 장비 수량 조회를 담당합니다.
 */
@Repository
class TreatmentTypeRepository : LongJdbcRepository<TreatmentTypeRecord> {
    companion object : KLogging()

    override val table = TreatmentTypes
    override fun extractId(entity: TreatmentTypeRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): TreatmentTypeRecord = toTreatmentTypeRecord()

    /**
     * 특정 시술 유형에 필요한 장비 ID 목록을 조회합니다.
     *
     * @param treatmentTypeId 시술 유형 ID
     * @return 필수 장비 ID 목록
     */
    fun findRequiredEquipmentIds(treatmentTypeId: Long): List<Long> =
        TreatmentEquipments
            .selectAll()
            .where { TreatmentEquipments.treatmentTypeId eq treatmentTypeId }
            .map { it[TreatmentEquipments.equipmentId].value }

    /**
     * 장비 ID 목록에 해당하는 장비별 수량을 조회합니다.
     *
     * @param equipmentIds 장비 ID 목록
     * @return 장비 ID → 수량 매핑
     */
    fun findEquipmentQuantities(equipmentIds: List<Long>): Map<Long, Int> =
        if (equipmentIds.isEmpty()) emptyMap()
        else Equipments
            .selectAll()
            .where { Equipments.id inList equipmentIds }
            .associate { it[Equipments.id].value to it[Equipments.quantity] }

    /**
     * 병원의 시술 유형 목록을 조회합니다.
     *
     * 결과는 Spring Cache 추상화를 통해 NearCache(Caffeine L1 + Redis L2)에 캐싱됩니다.
     *
     * @param clinicId 병원 ID
     * @return 시술 유형 목록 (빈 결과는 캐싱하지 않음)
     */
    @Cacheable(cacheNames = ["clinic-treatment-types"], key = "#clinicId", unless = "#result.isEmpty()")
    fun findByClinicId(clinicId: Long): List<TreatmentTypeRecord> =
        TreatmentTypes.selectAll()
            .where { TreatmentTypes.clinicId eq clinicId }
            .map { it.toTreatmentTypeRecord() }

    /**
     * 병원의 장비 목록을 조회합니다.
     *
     * @param clinicId 병원 ID
     * @return 장비 목록
     * @deprecated Equipment 도메인 책임은 [EquipmentRepository.findByClinicId]를 사용하세요.
     */
    @Deprecated("Equipment 도메인 책임은 EquipmentRepository.findByClinicId() 를 사용하세요.")
    fun findEquipmentsByClinicId(clinicId: Long): List<EquipmentRecord> =
        Equipments
            .selectAll()
            .where { Equipments.clinicId eq clinicId }
            .map { it.toEquipmentRecord() }

    /**
     * 병원 내 모든 시술-장비 연결 정보를 조회합니다.
     *
     * @param clinicId 병원 ID
     * @return 시술-장비 연결 목록
     */
    fun findAllTreatmentEquipments(clinicId: Long): List<TreatmentEquipmentRecord> {
        val treatmentIds = TreatmentTypes
            .selectAll()
            .where { TreatmentTypes.clinicId eq clinicId }
            .map { it[TreatmentTypes.id].value }

        if (treatmentIds.isEmpty()) return emptyList()

        return TreatmentEquipments
            .selectAll()
            .where { TreatmentEquipments.treatmentTypeId inList treatmentIds }
            .map { it.toTreatmentEquipmentRecord() }
    }
}

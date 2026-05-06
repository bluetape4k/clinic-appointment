package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
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
     * 병원의 장비 목록을 조회합니다.
     *
     * 결과는 Spring Cache 추상화를 통해 NearCache(Caffeine L1 + Redis L2)에 캐싱됩니다.
     *
     * @param clinicId 병원 ID
     * @return 장비 목록 (빈 결과는 캐싱하지 않음)
     */
    @Cacheable(cacheNames = ["clinic-equipments"], key = "#clinicId", unless = "#result == null || #result.isEmpty()")
    fun findByClinicId(clinicId: Long): List<EquipmentRecord> =
        Equipments.selectAll()
            .where { Equipments.clinicId eq clinicId }
            .map { it.toEquipmentRecord() }
}

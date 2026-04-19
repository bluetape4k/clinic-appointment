package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * 장비 정보 저장소.
 *
 * 병원의 장비 목록 및 개별 장비 정보를 조회합니다.
 */
class EquipmentRepository : LongJdbcRepository<EquipmentRecord> {
    companion object : KLogging()

    override val table = Equipments
    override fun extractId(entity: EquipmentRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): EquipmentRecord = toEquipmentRecord()

    /**
     * 병원의 장비 목록을 조회합니다.
     *
     * @param clinicId 병원 ID
     * @return 장비 목록
     */
    fun findByClinicId(clinicId: Long): List<EquipmentRecord> =
        Equipments
            .selectAll()
            .where { Equipments.clinicId eq clinicId }
            .map { it.toEquipmentRecord() }
}

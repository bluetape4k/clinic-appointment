package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.cache.nearcache.NearCacheOperations
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
class EquipmentRepository(
    private val clinicEquipmentsCache: NearCacheOperations<List<EquipmentRecord>>? = null,
) : LongJdbcRepository<EquipmentRecord> {
    companion object : KLogging()

    override val table = Equipments
    override fun extractId(entity: EquipmentRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): EquipmentRecord = toEquipmentRecord()

    /**
     * 병원의 장비 목록을 조회합니다.
     *
     * 결과는 NearCache(Caffeine + Redis)에 캐싱됩니다. 이 캐시는 **read-mostly** 마스터 데이터
     * 전용이며, 장비 정보가 변경될 경우 캐시 무효화가 자동으로 수행되지 않습니다.
     * 데이터 변경이 발생하면 캐시 TTL(기본 5분) 만료 후 자동 갱신되거나,
     * [NearCacheOperations.evict]를 직접 호출하여 무효화해야 합니다.
     *
     * @param clinicId 병원 ID
     * @return 장비 목록 (빈 결과는 캐싱하지 않음)
     */
    fun findByClinicId(clinicId: Long): List<EquipmentRecord> {
        val cacheKey = clinicId.toString()
        return clinicEquipmentsCache?.get(cacheKey) ?: Equipments
            .selectAll()
            .where { Equipments.clinicId eq clinicId }
            .map { it.toEquipmentRecord() }
            .also { if (it.isNotEmpty()) clinicEquipmentsCache?.put(cacheKey, it) }
    }
}

package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.TenantGroupRecord
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.exposed.jdbc.repository.LongJdbcRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Tenant group repository입니다.
 */
class TenantGroupRepository : LongJdbcRepository<TenantGroupRecord> {
    companion object : KLogging()

    override val table = TenantGroups
    override fun extractId(entity: TenantGroupRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): TenantGroupRecord = toTenantGroupRecord()

    /**
     * URL tenant code로 활성 tenant group을 조회합니다.
     *
     * 호출자는 Exposed `transaction {}` 안에서 이 메서드를 실행해야 합니다.
     */
    fun findActiveByCode(tenantCode: String): TenantGroupRecord? =
        TenantGroups
            .selectAll()
            .where {
                (TenantGroups.tenantCode eq tenantCode) and (TenantGroups.active eq true)
            }
            .firstOrNull()
            ?.toTenantGroupRecord()
}

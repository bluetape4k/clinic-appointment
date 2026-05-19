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
 * Tenant group repository.
 */
class TenantGroupRepository : LongJdbcRepository<TenantGroupRecord> {
    companion object : KLogging()

    override val table = TenantGroups
    override fun extractId(entity: TenantGroupRecord): Long = entity.id.requireNotNull("id")
    override fun ResultRow.toEntity(): TenantGroupRecord = toTenantGroupRecord()

    /**
     * Finds an active tenant group by URL tenant code.
     *
     * Callers must execute this method inside an Exposed `transaction {}`.
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

package io.bluetape4k.clinic.appointment.api.tenant

import io.bluetape4k.clinic.appointment.model.dto.TenantGroupRecord
import io.bluetape4k.support.requireNotNull
import java.io.Serializable

/**
 * Resolved tenant identity for the current API request.
 *
 * @property id tenant group ID used by database rows
 * @property tenantCode stable tenant code from the request path
 * @property displayName human-readable tenant name
 */
data class TenantInfo(
    val id: Long,
    val tenantCode: String,
    val displayName: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun from(record: TenantGroupRecord): TenantInfo =
            TenantInfo(
                id = record.id.requireNotNull("record.id"),
                tenantCode = record.tenantCode,
                displayName = record.displayName,
            )
    }
}

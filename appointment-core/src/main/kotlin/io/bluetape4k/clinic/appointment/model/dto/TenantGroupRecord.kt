package io.bluetape4k.clinic.appointment.model.dto

import java.io.Serializable
import java.time.Instant

/**
 * Tenant group record.
 *
 * @property id tenant group ID
 * @property tenantCode stable tenant code used in URL paths
 * @property displayName human-readable tenant name
 * @property active whether this tenant can be resolved for requests
 * @property createdAt creation timestamp
 */
data class TenantGroupRecord(
    val id: Long? = null,
    val tenantCode: String,
    val displayName: String,
    val active: Boolean = true,
    val createdAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

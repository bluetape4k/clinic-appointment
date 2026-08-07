package io.bluetape4k.clinic.appointment.model.catalog

import java.io.Serializable

/**
 * 불변 catalog version 동기화의 안정적인 결과 분류입니다.
 */
enum class CatalogSyncStatus {
    CREATED,
    UNCHANGED,
    STALE_IGNORED,
    VERSION_CONFLICT,
}

/**
 * HTTP adapter와 향후 event adapter가 공유하는 내부 동기화 결과입니다.
 */
data class CatalogSyncResult(
    val status: CatalogSyncStatus,
    val productId: String,
    val catalogVersion: Long,
    val payloadHash: String,
    val existingPayloadHash: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

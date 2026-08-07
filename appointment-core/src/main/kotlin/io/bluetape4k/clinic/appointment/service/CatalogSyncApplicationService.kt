package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncResult
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * appointment planning에 사용하는 불변 product catalog version을 동기화합니다.
 *
 * 검증과 canonical hash 계산은 의도적으로 DB connection을 획득하기 전에 수행합니다.
 * repository 비교와 aggregate 삽입은 하나의 transaction을 공유합니다.
 */
class CatalogSyncApplicationService(
    private val repository: ProductCatalogRepository,
) {
    companion object : KLogging() {
        private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
    }

    fun synchronize(
        definition: ProductCatalogDefinition,
        claimedPayloadHash: String,
    ): CatalogSyncResult {
        val valid = CatalogDefinitionValidator.validate(definition)
        require(claimedPayloadHash.matches(LOWERCASE_SHA_256)) {
            "payloadHash must be a lowercase SHA-256 value"
        }
        val actualHash = CatalogPayloadHasher.hash(valid)
        require(actualHash == claimedPayloadHash) { "payloadHash does not match the canonical catalog definition" }

        val result = try {
            transaction {
                repository.resolveSync(valid, actualHash)
            }
        } catch (failure: ExposedSQLException) {
            if (!failure.sqlState.startsWith("23")) throw failure
            transaction {
                repository.classifyExistingSync(valid, actualHash)
            } ?: throw failure
        }
        log.info {
            "Catalog synchronization completed: tenantGroupId=${valid.tenantGroupId}, " +
                "clinicId=${valid.clinicId}, productId=${valid.productId}, " +
                "catalogVersion=${valid.catalogVersion}, status=${result.status}"
        }
        return result
    }

}

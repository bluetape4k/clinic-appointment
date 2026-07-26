package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncResult
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Synchronizes immutable product-catalog versions for appointment planning.
 *
 * Validation and canonical hashing deliberately run before acquiring a
 * database connection. The repository comparison and aggregate insert share
 * one transaction.
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

        val result = transaction {
            repository.resolveSync(valid, actualHash)
        }
        log.info {
            "Catalog synchronization completed: tenantGroupId=${valid.tenantGroupId}, " +
                "clinicId=${valid.clinicId}, productId=${valid.productId}, " +
                "catalogVersion=${valid.catalogVersion}, status=${result.status}"
        }
        return result
    }

}

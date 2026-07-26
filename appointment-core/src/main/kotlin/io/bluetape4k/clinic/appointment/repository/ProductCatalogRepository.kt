package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncResult
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncStatus
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidator
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Stores and loads complete immutable catalog projections inside a caller-owned transaction.
 */
fun interface CatalogSyncWriteObserver {
    /**
     * Transaction-local race-test hook. Implementations must not perform external I/O.
     */
    fun afterVersionAbsent()

    companion object {
        val NOOP = CatalogSyncWriteObserver { }
    }
}

class ProductCatalogRepository(
    private val writeObserver: CatalogSyncWriteObserver = CatalogSyncWriteObserver.NOOP,
) {

    /**
     * Resolves an immutable catalog synchronization against the latest version
     * in the same tenant, clinic, and product scope.
     */
    fun resolveSync(
        definition: ProductCatalogDefinition,
        payloadHash: String,
    ): CatalogSyncResult {
        val exactVersion = findByScopeVersion(
            tenantGroupId = definition.tenantGroupId,
            clinicId = definition.clinicId,
            sourceAuthority = definition.sourceAuthority,
            productId = definition.productId,
            catalogVersion = definition.catalogVersion,
        )
        if (exactVersion != null) {
            return classifyExisting(definition, payloadHash, exactVersion)
        }
        writeObserver.afterVersionAbsent()

        val latestVersion = ProductCatalogProjections
            .selectAll()
            .where {
                (ProductCatalogProjections.tenantGroupId eq definition.tenantGroupId) and
                    (ProductCatalogProjections.clinicId eq definition.clinicId) and
                    (ProductCatalogProjections.sourceAuthority eq definition.sourceAuthority) and
                    (ProductCatalogProjections.productId eq definition.productId)
            }
            .orderBy(ProductCatalogProjections.catalogVersion, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(ProductCatalogProjections.catalogVersion)
        if (latestVersion != null && latestVersion > definition.catalogVersion) {
            return CatalogSyncResult(
                status = CatalogSyncStatus.STALE_IGNORED,
                productId = definition.productId,
                catalogVersion = definition.catalogVersion,
                payloadHash = payloadHash,
            )
        }

        saveAggregate(ProductCatalogProjectionRecord(definition = definition, payloadHash = payloadHash))
        return CatalogSyncResult(
            status = CatalogSyncStatus.CREATED,
            productId = definition.productId,
            catalogVersion = definition.catalogVersion,
            payloadHash = payloadHash,
        )
    }

    /**
     * Re-reads and classifies the immutable version after a concurrent unique-key conflict.
     */
    fun classifyExistingSync(
        definition: ProductCatalogDefinition,
        payloadHash: String,
    ): CatalogSyncResult? =
        findByScopeVersion(
            tenantGroupId = definition.tenantGroupId,
            clinicId = definition.clinicId,
            sourceAuthority = definition.sourceAuthority,
            productId = definition.productId,
            catalogVersion = definition.catalogVersion,
        )?.let { existing -> classifyExisting(definition, payloadHash, existing) }

    private fun classifyExisting(
        definition: ProductCatalogDefinition,
        payloadHash: String,
        existing: ProductCatalogProjectionRecord,
    ): CatalogSyncResult =
        CatalogSyncResult(
            status = if (existing.payloadHash == payloadHash) {
                CatalogSyncStatus.UNCHANGED
            } else {
                CatalogSyncStatus.VERSION_CONFLICT
            },
            productId = definition.productId,
            catalogVersion = definition.catalogVersion,
            payloadHash = payloadHash,
            existingPayloadHash = existing.payloadHash,
        )

    /**
     * Inserts a catalog root and all of its BOM children atomically in the current transaction.
     */
    fun saveAggregate(record: ProductCatalogProjectionRecord): ProductCatalogProjectionRecord {
        val definition = CatalogDefinitionValidator.validate(record.definition)
        require(record.payloadHash.matches(Regex("[0-9a-f]{64}"))) {
            "payloadHash must be a lowercase SHA-256 value"
        }
        val clinicTenantId = Clinics
            .selectAll()
            .where { Clinics.id eq definition.clinicId }
            .singleOrNull()
            ?.get(Clinics.tenantGroupId)
            ?.value
        requireNotNull(clinicTenantId) { "clinic does not exist" }
        require(clinicTenantId == definition.tenantGroupId) {
            "clinic does not belong to catalog tenant"
        }
        val initialRule = definition.initialBookingRule
        val projectionId = ProductCatalogProjections.insertAndGetId {
            it[tenantGroupId] = definition.tenantGroupId
            it[clinicId] = definition.clinicId
            it[sourceAuthority] = definition.sourceAuthority
            it[productId] = definition.productId
            it[catalogVersion] = definition.catalogVersion
            it[productName] = definition.productName
            it[schemaVersion] = definition.schemaVersion
            it[sourceUpdatedAt] = definition.sourceUpdatedAt
            it[status] = definition.status
            it[payloadHash] = record.payloadHash
            it[initialBookingRuleType] = when (initialRule) {
                null -> null
                is InitialBookingRule.WithinDaysAfterPurchase -> "WITHIN_DAYS_AFTER_PURCHASE"
            }
            it[initialBookingMaximumDays] = when (initialRule) {
                null -> null
                is InitialBookingRule.WithinDaysAfterPurchase -> initialRule.maximumDays
            }
        }.value

        definition.items.forEachIndexed { bomOrder, item ->
            ProductCatalogBomItems.insert {
                it[catalogProjectionId] = projectionId
                it[bomItemId] = item.bomItemId
                it[ProductCatalogBomItems.bomOrder] = bomOrder
                it[representativeTreatmentName] = item.representativeTreatmentName
                it[detailedTreatmentCodesJson] = encodeStringList(item.detailedTreatmentCodes)
                it[repeatCount] = item.repeatCount
                it[durationMinutes] = item.durationMinutes
                it[minimumIntervalDays] = item.minimumIntervalDays
                it[preferredIntervalDays] = item.preferredIntervalDays
                it[maximumIntervalDays] = item.maximumIntervalDays
                it[practitionerQualificationsJson] = encodeStringList(item.practitionerQualifications)
                it[equipmentTypesJson] = encodeStringList(item.equipmentTypes)
                it[roomTypesJson] = encodeStringList(item.roomTypes)
            }
        }

        definition.dependencies.forEach { dependency ->
            ProductCatalogBomDependencies.insert {
                it[catalogProjectionId] = projectionId
                it[predecessorBomItemId] = dependency.predecessorBomItemId
                it[predecessorSequenceNo] = dependency.predecessorSequenceNo.sequenceToSentinel()
                it[successorBomItemId] = dependency.successorBomItemId
                it[successorSequenceNo] = dependency.successorSequenceNo.sequenceToSentinel()
                it[minimumIntervalDays] = dependency.minimumIntervalDays
                it[preferredIntervalDays] = dependency.preferredIntervalDays
                it[maximumIntervalDays] = dependency.maximumIntervalDays
            }
        }

        return requireNotNull(findById(projectionId))
    }

    /**
     * Finds one catalog projection by its tenant, clinic, product, and immutable version.
     */
    fun findByScopeVersion(
        tenantGroupId: Long,
        clinicId: Long,
        sourceAuthority: String,
        productId: String,
        catalogVersion: Long,
    ): ProductCatalogProjectionRecord? =
        ProductCatalogProjections
            .selectAll()
            .where {
                (ProductCatalogProjections.tenantGroupId eq tenantGroupId) and
                    (ProductCatalogProjections.clinicId eq clinicId) and
                    (ProductCatalogProjections.sourceAuthority eq sourceAuthority) and
                    (ProductCatalogProjections.productId eq productId) and
                    (ProductCatalogProjections.catalogVersion eq catalogVersion)
            }
            .singleOrNull()
            ?.let(::mapAggregate)

    /**
     * Finds one catalog projection by its internal identifier.
     */
    fun findById(id: Long): ProductCatalogProjectionRecord? =
        ProductCatalogProjections
            .selectAll()
            .where { ProductCatalogProjections.id eq id }
            .singleOrNull()
            ?.let(::mapAggregate)

    /**
     * Deletes an unreferenced projection. Database ancestry constraints reject referenced versions.
     */
    fun deleteProjection(id: Long): Int =
        ProductCatalogProjections.deleteWhere { ProductCatalogProjections.id eq id }

    private fun mapAggregate(row: org.jetbrains.exposed.v1.core.ResultRow): ProductCatalogProjectionRecord {
        val projectionId = row[ProductCatalogProjections.id]
        val items = ProductCatalogBomItems
            .selectAll()
            .where { ProductCatalogBomItems.catalogProjectionId eq projectionId }
            .orderBy(ProductCatalogBomItems.bomOrder, SortOrder.ASC)
            .map { itemRow -> itemRow.toCatalogBomItem() }
        val dependencies = ProductCatalogBomDependencies
            .selectAll()
            .where { ProductCatalogBomDependencies.catalogProjectionId eq projectionId }
            .orderBy(ProductCatalogBomDependencies.id, SortOrder.ASC)
            .map { dependencyRow -> dependencyRow.toCatalogBomDependency() }
        return row.toProductCatalogProjectionRecord(items, dependencies)
    }
}

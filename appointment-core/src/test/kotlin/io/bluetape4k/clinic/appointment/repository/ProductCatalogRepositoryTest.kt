package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncStatus
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidator
import io.bluetape4k.clinic.appointment.service.CatalogPayloadHasher
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ProductCatalogRepositoryTest : AbstractExposedTest() {
    private val repository = ProductCatalogRepository()

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `round trips an immutable catalog aggregate and enforces scoped version uniqueness`(testDB: TestDB) {
        withTables(
            testDB,
            Clinics,
            ProductCatalogProjections,
            ProductCatalogBomItems,
            ProductCatalogBomDependencies,
        ) {
            val clinicId = createClinic()
            val record = catalogRecord(clinicId)

            val saved = repository.saveAggregate(record)
            val found = repository.findByScopeVersion(1L, clinicId, "product-catalog", "product-1", 7L)

            saved.id.shouldNotBeNull()
            found.shouldNotBeNull()
            found.definition shouldBeEqualTo record.definition
            found.payloadHash shouldBeEqualTo record.payloadHash

            assertFailsWith<IllegalArgumentException> {
                repository.saveAggregate(
                    record.copy(
                        definition = record.definition.copy(tenantGroupId = 2L),
                    )
                )
            }
            val alternateAuthority = record.copy(
                definition = record.definition.copy(
                    sourceAuthority = "legacy-catalog",
                    status = CatalogProjectionStatus.RETIRED,
                ),
            )
            repository.saveAggregate(alternateAuthority)
            val retired = repository.findByScopeVersion(1L, clinicId, "legacy-catalog", "product-1", 7L)
                .shouldNotBeNull()
            retired.definition.status shouldBeEqualTo CatalogProjectionStatus.RETIRED

            assertFailsWith<ExposedSQLException> {
                repository.saveAggregate(record)
            }
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `classifies same-version BOM reorder as a catalog content conflict`(testDB: TestDB) {
        withTables(
            testDB,
            Clinics,
            ProductCatalogProjections,
            ProductCatalogBomItems,
            ProductCatalogBomDependencies,
        ) {
            val clinicId = createClinic()
            val original = catalogRecord(clinicId).let { record ->
                record.copy(payloadHash = CatalogPayloadHasher.hash(record.definition))
            }
            val reorderedDefinition = original.definition.copy(items = original.definition.items.reversed())
            val reorderedHash = CatalogPayloadHasher.hash(reorderedDefinition)

            repository.resolveSync(original.definition, original.payloadHash)
            val result = repository.resolveSync(reorderedDefinition, reorderedHash)

            result.status shouldBeEqualTo CatalogSyncStatus.VERSION_CONFLICT
            result.existingPayloadHash shouldBeEqualTo original.payloadHash
        }
    }

    @Test
    fun `saves a maximum catalog graph without row-by-row child insert statements`() {
        withTables(
            TestDB.H2,
            Clinics,
            ProductCatalogProjections,
            ProductCatalogBomItems,
            ProductCatalogBomDependencies,
        ) {
            val counter = CatalogChildInsertCounter()
            registerInterceptor(counter)
            val clinicId = createClinic()
            val record = maxCatalogRecord(clinicId)

            repository.saveAggregate(record)

            counter.itemInsertStatements.get() shouldBeEqualTo 1
            counter.dependencyInsertStatements.get() shouldBeEqualTo 1
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.createClinic(): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = 1L
            it[name] = "Catalog Clinic"
        }.value

    private fun catalogRecord(clinicId: Long) = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = 1L,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = "product-1",
            catalogVersion = 7L,
            productName = "Laser package",
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
            status = CatalogProjectionStatus.ACTIVE,
            items = listOf(
                CatalogBomItem(
                    bomItemId = "laser",
                    representativeTreatmentName = "Laser",
                    detailedTreatmentCodes = listOf("laser-a", "laser-b"),
                    repeatCount = 3,
                    durationMinutes = 30,
                    minimumIntervalDays = 7,
                    preferredIntervalDays = 14,
                    maximumIntervalDays = 21,
                    practitionerQualifications = listOf("doctor"),
                    equipmentTypes = listOf("laser"),
                    roomTypes = listOf("procedure-room"),
                ),
                CatalogBomItem(
                    bomItemId = "care",
                    representativeTreatmentName = "After care",
                    detailedTreatmentCodes = listOf("care"),
                    repeatCount = 1,
                    durationMinutes = 20,
                    minimumIntervalDays = null,
                    preferredIntervalDays = null,
                    maximumIntervalDays = null,
                    practitionerQualifications = listOf("nurse"),
                    equipmentTypes = emptyList(),
                    roomTypes = listOf("care-room"),
                ),
            ),
            dependencies = listOf(
                CatalogBomDependency(
                    predecessorBomItemId = "laser",
                    successorBomItemId = "care",
                    minimumIntervalDays = 1,
                    preferredIntervalDays = 2,
                    maximumIntervalDays = 3,
                )
            ),
            initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(30),
        ),
        payloadHash = "a".repeat(64),
    )

    private fun maxCatalogRecord(clinicId: Long): ProductCatalogProjectionRecord {
        val itemIds = (1..CatalogDefinitionValidator.MAX_BOM_ITEMS).map { index -> "i%03d".format(index) }
        val definition = ProductCatalogDefinition(
            tenantGroupId = 1L,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = "product-max",
            catalogVersion = 99L,
            productName = "Max package",
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
            status = CatalogProjectionStatus.ACTIVE,
            items = itemIds.map { itemId ->
                CatalogBomItem(
                    bomItemId = itemId,
                    representativeTreatmentName = "T$itemId",
                    detailedTreatmentCodes = emptyList(),
                    repeatCount = 1,
                    durationMinutes = 5,
                    minimumIntervalDays = 0,
                    preferredIntervalDays = 1,
                    maximumIntervalDays = 2,
                    practitionerQualifications = emptyList(),
                    equipmentTypes = emptyList(),
                    roomTypes = emptyList(),
                )
            },
            dependencies = maxAcyclicDependencies(itemIds),
            initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(30),
        )
        return ProductCatalogProjectionRecord(
            definition = definition,
            payloadHash = CatalogPayloadHasher.hash(definition),
        )
    }

    private fun maxAcyclicDependencies(itemIds: List<String>): List<CatalogBomDependency> = buildList {
        for (predecessorIndex in 0 until itemIds.lastIndex) {
            for (successorIndex in predecessorIndex + 1 until itemIds.size) {
                add(
                    CatalogBomDependency(
                        predecessorBomItemId = itemIds[predecessorIndex],
                        successorBomItemId = itemIds[successorIndex],
                        minimumIntervalDays = 0,
                        preferredIntervalDays = 1,
                        maximumIntervalDays = 2,
                    )
                )
                if (size == CatalogDefinitionValidator.MAX_CATALOG_DEPENDENCIES) {
                    return@buildList
                }
            }
        }
    }

    private class CatalogChildInsertCounter : StatementInterceptor {
        val itemInsertStatements = AtomicInteger(0)
        val dependencyInsertStatements = AtomicInteger(0)

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            val context = contexts.firstOrNull() ?: return
            val sql = context.sql(transaction).lowercase()
            when {
                sql.startsWith("insert") && ProductCatalogBomItems.tableName in sql ->
                    itemInsertStatements.incrementAndGet()
                sql.startsWith("insert") && ProductCatalogBomDependencies.tableName in sql ->
                    dependencyInsertStatements.incrementAndGet()
            }
        }
    }
}

package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncStatus
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.time.Instant

class CatalogSyncApplicationServiceTest {

    private val service = CatalogSyncApplicationService(ProductCatalogRepository())

    @Test
    fun `creates a new immutable catalog version`() = withCatalogTables {
        val definition = catalogDefinition(clinicId = createClinic())

        val result = service.synchronize(definition, CatalogPayloadHasher.hash(definition))

        result.status shouldBeEqualTo CatalogSyncStatus.CREATED
        ProductCatalogProjections.selectAll().count() shouldBeEqualTo 1L
        ProductCatalogBomItems.selectAll().count() shouldBeEqualTo 2L
        ProductCatalogBomDependencies.selectAll().count() shouldBeEqualTo 1L
    }

    @Test
    fun `returns unchanged for the same version and canonical hash`() = withCatalogTables {
        val definition = catalogDefinition(clinicId = createClinic())
        val hash = CatalogPayloadHasher.hash(definition)

        service.synchronize(definition, hash)
        val replay = service.synchronize(definition, hash)

        replay.status shouldBeEqualTo CatalogSyncStatus.UNCHANGED
        ProductCatalogProjections.selectAll().count() shouldBeEqualTo 1L
    }

    @Test
    fun `ignores a lower catalog version`() = withCatalogTables {
        val clinicId = createClinic()
        val latest = catalogDefinition(clinicId = clinicId, catalogVersion = 8L)
        service.synchronize(latest, CatalogPayloadHasher.hash(latest))
        val stale = catalogDefinition(clinicId = clinicId, catalogVersion = 7L)

        val result = service.synchronize(stale, CatalogPayloadHasher.hash(stale))

        result.status shouldBeEqualTo CatalogSyncStatus.STALE_IGNORED
        ProductCatalogProjections.selectAll().count() shouldBeEqualTo 1L
    }

    @Test
    fun `reports a conflict for the same version with a different definition`() = withCatalogTables {
        val clinicId = createClinic()
        val original = catalogDefinition(clinicId = clinicId)
        service.synchronize(original, CatalogPayloadHasher.hash(original))
        val conflicting = original.copy(productName = "Changed name")

        val result = service.synchronize(conflicting, CatalogPayloadHasher.hash(conflicting))

        result.status shouldBeEqualTo CatalogSyncStatus.VERSION_CONFLICT
        ProductCatalogProjections.selectAll().count() shouldBeEqualTo 1L
    }

    @Test
    fun `rejects a cyclic dependency graph before persistence`() = withCatalogTables {
        val clinicId = createClinic()
        val definition = catalogDefinition(clinicId = clinicId).let { valid ->
            valid.copy(
                dependencies = valid.dependencies + CatalogBomDependency(
                    predecessorBomItemId = "care",
                    successorBomItemId = "laser",
                    successorSequenceNo = 1,
                    minimumIntervalDays = 1,
                    preferredIntervalDays = 2,
                    maximumIntervalDays = 3,
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            service.synchronize(definition, "a".repeat(64))
        }
        ProductCatalogProjections.selectAll().count() shouldBeEqualTo 0L
    }

    @Test
    fun `rolls back the aggregate when a child insert fails`() = withCatalogTables {
        val definition = catalogDefinition(clinicId = createClinic())
        exec(
            """
            ALTER TABLE scheduling_product_catalog_bom_items
            ADD CONSTRAINT reject_laser_item CHECK (bom_item_id <> 'laser')
            """.trimIndent()
        )

        assertFailsWith<Exception> {
            service.synchronize(definition, CatalogPayloadHasher.hash(definition))
        }

        ProductCatalogProjections.selectAll().count() shouldBeEqualTo 0L
        ProductCatalogBomItems.selectAll().count() shouldBeEqualTo 0L
        ProductCatalogBomDependencies.selectAll().count() shouldBeEqualTo 0L
    }

    private fun withCatalogTables(block: JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.H2,
            Clinics,
            ProductCatalogProjections,
            ProductCatalogBomItems,
            ProductCatalogBomDependencies,
        ) {
            block()
        }
    }

    private fun JdbcTransaction.createClinic(): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = 1L
            it[name] = "Catalog Clinic"
        }.value

    private fun catalogDefinition(
        clinicId: Long,
        catalogVersion: Long = 7L,
    ) = ProductCatalogDefinition(
        tenantGroupId = 1L,
        clinicId = clinicId,
        sourceAuthority = "product-catalog",
        productId = "product-1",
        catalogVersion = catalogVersion,
        productName = "Laser package",
        schemaVersion = 1,
        sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
        items = listOf(
            CatalogBomItem(
                bomItemId = "laser",
                representativeTreatmentName = "Laser",
                detailedTreatmentCodes = listOf("laser-a"),
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
    )
}

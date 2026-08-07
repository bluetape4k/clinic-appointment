package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncResult
import io.bluetape4k.clinic.appointment.model.catalog.CatalogSyncStatus
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.CatalogSyncWriteObserver
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.CatalogPayloadHasher
import io.bluetape4k.clinic.appointment.service.CatalogSyncApplicationService
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * 활성화된 각 데이터베이스 dialect에서 불변 catalog-version이 수렴하는지 증명하고,
 * API 모듈의 singleton 데이터베이스 launcher를 재사용한다.
 */
class CatalogSyncDialectIntegrationTest : AbstractApiIntegrationTest() {

    private val prefix = UUID.randomUUID().toString().replace("-", "")
    private var tenantGroupId = 0L
    private var clinicId = 0L

    @BeforeEach
    fun setUpScope() {
        transaction {
            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "catalog-race-$prefix"
                it[displayName] = "Catalog Race Tenant"
                it[active] = true
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] =
                    EntityID(this@CatalogSyncDialectIntegrationTest.tenantGroupId, TenantGroups)
                it[name] = "Catalog Race Clinic"
            }.value
        }
    }

    @AfterEach
    fun cleanUpCatalog() {
        transaction {
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
        }
    }

    @Test
    fun `concurrent immutable catalog versions converge on the active database dialect`() {
        val original = catalogDefinition()

        race(original, original).map(CatalogSyncResult::status).toSet() shouldBeEqualTo
            setOf(CatalogSyncStatus.CREATED, CatalogSyncStatus.UNCHANGED)

        transaction {
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
        }

        val conflicting = original.copy(productName = "Conflicting catalog name")
        race(original, conflicting).map(CatalogSyncResult::status).toSet() shouldBeEqualTo
            setOf(CatalogSyncStatus.CREATED, CatalogSyncStatus.VERSION_CONFLICT)
    }

    private fun race(
        first: ProductCatalogDefinition,
        second: ProductCatalogDefinition,
    ): List<CatalogSyncResult> {
        val barrier = CyclicBarrier(2)
        val service = CatalogSyncApplicationService(
            ProductCatalogRepository(CatalogSyncWriteObserver { barrier.await() }),
        )
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { definition ->
                executor.submit<CatalogSyncResult> {
                    service.synchronize(definition, CatalogPayloadHasher.hash(definition))
                }
            }.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun catalogDefinition() = ProductCatalogDefinition(
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        sourceAuthority = "product-catalog",
        productId = "product-$prefix",
        catalogVersion = 7L,
        productName = "Laser package",
        schemaVersion = 1,
        sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
        initialBookingRule = null,
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
            ),
        ),
    )
}

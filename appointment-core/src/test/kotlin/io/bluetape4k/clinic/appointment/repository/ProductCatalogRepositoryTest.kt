package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

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
}

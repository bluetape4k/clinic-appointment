package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import org.junit.jupiter.api.Test
import java.time.Instant

class CatalogPayloadHasherTest {

    @Test
    fun `produces the same hash for unordered treatment attribute lists`() {
        val first = definition(
            items = listOf(item("b", listOf("code-2", "code-1")), item("a")),
            dependencies = listOf(dependency("a", "b")),
        )
        val reordered = first.copy(
            items = first.items.map { item ->
                item.copy(
                    detailedTreatmentCodes = item.detailedTreatmentCodes.reversed(),
                    equipmentTypes = item.equipmentTypes.reversed(),
                )
            },
        )

        CatalogPayloadHasher.hash(first) shouldBeEqualTo CatalogPayloadHasher.hash(reordered)
    }

    @Test
    fun `BOM order participates in the catalog payload hash`() {
        val first = definition(
            items = listOf(item("laser"), item("care")),
            dependencies = listOf(dependency("laser", "care")),
        )
        val reordered = first.copy(
            items = first.items.reversed(),
        )

        (CatalogPayloadHasher.hash(first) == CatalogPayloadHasher.hash(reordered)) shouldBeEqualTo false
    }

    @Test
    fun `dependency order participates in the catalog payload hash`() {
        val first = definition(
            items = listOf(item("laser"), item("care"), item("toning")),
            dependencies = listOf(dependency("laser", "care"), dependency("care", "toning")),
        )
        val reordered = first.copy(
            dependencies = first.dependencies.reversed(),
        )

        (CatalogPayloadHasher.hash(first) == CatalogPayloadHasher.hash(reordered)) shouldBeEqualTo false
    }

    @Test
    fun `distinguishes null from an empty value and rejects invalid definitions before hashing`() {
        val withNull = definition(items = listOf(item("a").copy(minimumIntervalDays = null)))
        val withZero = definition(items = listOf(item("a").copy(minimumIntervalDays = 0)))

        (CatalogPayloadHasher.hash(withNull) == CatalogPayloadHasher.hash(withZero)) shouldBeEqualTo false

        assertFailsWith<IllegalArgumentException> {
            CatalogPayloadHasher.hash(definition(productId = "unsafe id"))
        }
    }

    @Test
    fun `catalog lifecycle status participates in the payload hash`() {
        val active = definition(status = CatalogProjectionStatus.ACTIVE)
        val retired = definition(status = CatalogProjectionStatus.RETIRED)

        (CatalogPayloadHasher.hash(active) == CatalogPayloadHasher.hash(retired)) shouldBeEqualTo false
    }

    private fun definition(
        productId: String = "product-1",
        status: CatalogProjectionStatus = CatalogProjectionStatus.ACTIVE,
        items: List<CatalogBomItem> = listOf(item("a")),
        dependencies: List<CatalogBomDependency> = emptyList(),
    ) = ProductCatalogDefinition(
        tenantGroupId = 1L,
        clinicId = 2L,
        sourceAuthority = "product-catalog",
        productId = productId,
        catalogVersion = 7L,
        productName = "Package",
        schemaVersion = 1,
        sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
        status = status,
        items = items,
        dependencies = dependencies,
        initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(30),
    )

    private fun item(
        id: String,
        codes: List<String> = listOf("code-$id"),
    ) = CatalogBomItem(
        bomItemId = id,
        representativeTreatmentName = "Treatment $id",
        detailedTreatmentCodes = codes,
        repeatCount = 1,
        durationMinutes = 30,
        minimumIntervalDays = 1,
        preferredIntervalDays = 7,
        maximumIntervalDays = 14,
        practitionerQualifications = listOf("doctor", "specialist"),
        equipmentTypes = listOf("laser-b", "laser-a"),
        roomTypes = listOf("room"),
    )

    private fun dependency(
        predecessor: String,
        successor: String,
    ) = CatalogBomDependency(
        predecessorBomItemId = predecessor,
        successorBomItemId = successor,
        minimumIntervalDays = 1,
        preferredIntervalDays = 7,
        maximumIntervalDays = 14,
    )
}

package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import org.junit.jupiter.api.Test
import java.time.Instant

class CatalogDefinitionValidatorTest {

    @Test
    fun `accepts repeat and package items with an acyclic dependency graph`() {
        val definition = catalogDefinition(
            items = listOf(
                bomItem(id = "laser", repeatCount = 3),
                bomItem(id = "care"),
            ),
            dependencies = listOf(dependency("laser", "care")),
        )

        CatalogDefinitionValidator.validate(definition) shouldBeEqualTo definition
    }

    @Test
    fun `rejects dependency cycles and unknown item references`() {
        assertFailsWith<IllegalArgumentException> {
            CatalogDefinitionValidator.validate(
                catalogDefinition(
                    items = listOf(bomItem("a"), bomItem("b")),
                    dependencies = listOf(dependency("a", "b"), dependency("b", "a")),
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            CatalogDefinitionValidator.validate(
                catalogDefinition(
                    items = listOf(bomItem("known")),
                    dependencies = listOf(dependency("known", "missing")),
                )
            )
        }
    }

    @Test
    fun `rejects duplicate items invalid counts durations and intervals`() {
        listOf(
            catalogDefinition(items = listOf(bomItem("same"), bomItem("same"))),
            catalogDefinition(items = listOf(bomItem("item", repeatCount = 0))),
            catalogDefinition(items = listOf(bomItem("item", durationMinutes = 0))),
            catalogDefinition(items = listOf(bomItem("item", minimumIntervalDays = -1))),
            catalogDefinition(items = listOf(bomItem("item", minimumIntervalDays = 3, preferredIntervalDays = 2))),
            catalogDefinition(items = listOf(bomItem("item", preferredIntervalDays = 3, maximumIntervalDays = 2))),
        ).forEach { definition ->
            assertFailsWith<IllegalArgumentException> {
                CatalogDefinitionValidator.validate(definition)
            }
        }
    }

    @Test
    fun `rejects invalid initial booking horizon and dependency occurrence`() {
        assertFailsWith<IllegalArgumentException> {
            CatalogDefinitionValidator.validate(
                catalogDefinition(initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(0))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogDefinitionValidator.validate(
                catalogDefinition(initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(3_651))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CatalogDefinitionValidator.validate(
                catalogDefinition(
                    items = listOf(bomItem("a", repeatCount = 2), bomItem("b")),
                    dependencies = listOf(dependency("a", "b", predecessorSequenceNo = 3)),
                )
            )
        }
    }

    @Test
    fun `rejects unsafe identifiers duplicate normalized requirements and control characters`() {
        listOf(
            catalogDefinition(productId = "unsafe id"),
            catalogDefinition(items = listOf(bomItem("item", equipmentTypes = listOf("laser", " LASER ")))),
            catalogDefinition(productName = "unsafe\u0000name"),
            catalogDefinition(items = listOf(bomItem("item", detailedTreatmentCodes = listOf("")))),
        ).forEach { definition ->
            assertFailsWith<IllegalArgumentException> {
                CatalogDefinitionValidator.validate(definition)
            }
        }
    }

    @Test
    fun `rejects central count duration horizon and payload bounds`() {
        listOf(
            catalogDefinition(items = (1..201).map { bomItem("item-$it") }),
            catalogDefinition(items = listOf(bomItem("item", repeatCount = 101))),
            catalogDefinition(items = listOf(bomItem("item", durationMinutes = 481))),
            catalogDefinition(items = listOf(bomItem("item", maximumIntervalDays = 3_651))),
            catalogDefinition(
                items = listOf(
                    bomItem(
                        "item",
                        practitionerQualifications = (1..65).map { "qualification-$it" },
                    )
                )
            ),
            catalogDefinition(
                items = (1..21).map {
                    bomItem(
                        id = "item-$it",
                        repeatCount = 100,
                    )
                }
            ),
            catalogDefinition(
                items = (1..200).map {
                    bomItem(
                        id = "item-$it",
                        detailedTreatmentCodes = (1..64).map { code -> "code-${it}-$code-${"x".repeat(100)}" },
                        practitionerQualifications = (1..64).map { code -> "q-${it}-$code-${"x".repeat(100)}" },
                        equipmentTypes = (1..64).map { code -> "e-${it}-$code-${"x".repeat(100)}" },
                        roomTypes = (1..64).map { code -> "r-${it}-$code-${"x".repeat(100)}" },
                    )
                }
            ),
        ).forEach { definition ->
            assertFailsWith<IllegalArgumentException> {
                CatalogDefinitionValidator.validate(definition)
            }
        }
    }

    private fun catalogDefinition(
        productId: String = "product-1",
        productName: String = "Laser package",
        items: List<CatalogBomItem> = listOf(bomItem("laser")),
        dependencies: List<CatalogBomDependency> = emptyList(),
        initialBookingRule: InitialBookingRule? = InitialBookingRule.WithinDaysAfterPurchase(30),
    ) = ProductCatalogDefinition(
        tenantGroupId = 1L,
        clinicId = 2L,
        sourceAuthority = "product-catalog",
        productId = productId,
        catalogVersion = 1L,
        productName = productName,
        schemaVersion = 1,
        sourceUpdatedAt = Instant.parse("2026-07-26T00:00:00Z"),
        items = items,
        dependencies = dependencies,
        initialBookingRule = initialBookingRule,
    )

    private fun bomItem(
        id: String,
        repeatCount: Int = 1,
        durationMinutes: Int = 30,
        minimumIntervalDays: Int? = 1,
        preferredIntervalDays: Int? = 7,
        maximumIntervalDays: Int? = 14,
        detailedTreatmentCodes: List<String> = listOf("treatment-$id"),
        practitionerQualifications: List<String> = listOf("doctor"),
        equipmentTypes: List<String> = listOf("laser"),
        roomTypes: List<String> = listOf("treatment-room"),
    ) = CatalogBomItem(
        bomItemId = id,
        representativeTreatmentName = "Treatment $id",
        detailedTreatmentCodes = detailedTreatmentCodes,
        repeatCount = repeatCount,
        durationMinutes = durationMinutes,
        minimumIntervalDays = minimumIntervalDays,
        preferredIntervalDays = preferredIntervalDays,
        maximumIntervalDays = maximumIntervalDays,
        practitionerQualifications = practitionerQualifications,
        equipmentTypes = equipmentTypes,
        roomTypes = roomTypes,
    )

    private fun dependency(
        predecessor: String,
        successor: String,
        predecessorSequenceNo: Int? = null,
        successorSequenceNo: Int? = null,
    ) = CatalogBomDependency(
        predecessorBomItemId = predecessor,
        predecessorSequenceNo = predecessorSequenceNo,
        successorBomItemId = successor,
        successorSequenceNo = successorSequenceNo,
        minimumIntervalDays = 1,
        preferredIntervalDays = 7,
        maximumIntervalDays = 14,
    )
}

package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidator
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant

class CatalogProductVersionPayloadBoundTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `maximum reachable compact graph fits the canonical API payload bound`() {
        val request = maximumReachableRequest()

        CatalogDefinitionValidator.validate(request.toDefinition())
        val payloadBytes = objectMapper.writeValueAsBytes(request).size

        println(
            "CATALOG_PAYLOAD_BOUND items=${request.items.size} " +
                "dependencies=${request.dependencies.size} bytes=$payloadBytes " +
                "maximum=${CatalogDefinitionValidator.MAX_PAYLOAD_BYTES}"
        )
        (payloadBytes <= CatalogDefinitionValidator.MAX_PAYLOAD_BYTES).shouldBeTrue()
    }

    private fun maximumReachableRequest(): CatalogProductVersionRequest {
        val items = (0 until 20).map { index ->
            CatalogBomItemRequest(
                bomItemId = "item$index",
                representativeTreatmentName = "Treatment item$index",
                detailedTreatmentCodes = listOf("CODE_item$index"),
                repeatCount = 100,
                durationMinutes = 30,
                minimumIntervalDays = 1,
                preferredIntervalDays = 7,
                maximumIntervalDays = 14,
                practitionerQualifications = listOf("DOCTOR"),
                equipmentTypes = listOf("DEVICE"),
                roomTypes = listOf("ROOM"),
            )
        }
        val dependencies = buildList {
            for (predecessor in 0 until 10) {
                for (successor in 10 until 20) {
                    for (sequence in 1..10) {
                        add(
                            CatalogBomDependencyRequest(
                                predecessorBomItemId = "item$predecessor",
                                predecessorSequenceNo = sequence,
                                successorBomItemId = "item$successor",
                                successorSequenceNo = sequence,
                                minimumIntervalDays = 1,
                                preferredIntervalDays = 7,
                                maximumIntervalDays = 14,
                            )
                        )
                    }
                }
            }
        }
        check(dependencies.size == CatalogDefinitionValidator.MAX_CATALOG_DEPENDENCIES)
        return CatalogProductVersionRequest(
            sourceAuthority = "product-catalog",
            tenantGroupId = 1,
            clinicId = 1,
            productId = "maximum-compact-product",
            catalogVersion = 1,
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T05:00:00Z"),
            status = CatalogProjectionStatus.ACTIVE,
            productName = "Maximum Reachable Compact Product",
            items = items,
            dependencies = dependencies,
            payloadHash = "a".repeat(64),
        )
    }
}

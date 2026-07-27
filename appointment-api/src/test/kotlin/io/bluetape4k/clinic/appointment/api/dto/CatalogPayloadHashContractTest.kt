package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

class CatalogPayloadHashContractTest {

    @Test
    fun `documented fixture hash is reproducible without the production hasher`() {
        val expectedHash = "664839668b617f88b14a92091b092266642b5f739b15d9218828825aa5431046"

        independentFixtureHash() shouldBeEqualTo expectedHash

        val contract = catalogHashContractPath().toFile().readText()
        contract.shouldContain(expectedHash)
        contract.shouldContain(
            "/api/tenant-default/clinics/2/catalog-sources/product-catalog/catalog-products/laser-care/versions/7"
        )
    }

    private fun independentFixtureHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField("tenantGroupId", 1L)
        digest.updateField("clinicId", 2L)
        digest.updateField("sourceAuthority", "product-catalog")
        digest.updateField("productId", "laser-care")
        digest.updateField("catalogVersion", 7L)
        digest.updateField("productName", "Laser Care")
        digest.updateField("schemaVersion", 1)
        digest.updateField("sourceUpdatedAt", "2026-07-26T05:00:00Z")
        digest.updateField("status", "ACTIVE")
        digest.updateField("items.size", 1)
        digest.updateField("items[0].bomItemId", "laser")
        digest.updateField("items[0].representativeTreatmentName", "Laser")
        digest.updateSortedList("items[0].detailedTreatmentCodes", listOf("LASER"))
        digest.updateField("items[0].repeatCount", 3)
        digest.updateField("items[0].durationMinutes", 30)
        digest.updateField("items[0].minimumIntervalDays", 21)
        digest.updateField("items[0].preferredIntervalDays", 28)
        digest.updateField("items[0].maximumIntervalDays", 42)
        digest.updateSortedList("items[0].practitionerQualifications", listOf("DERMATOLOGIST"))
        digest.updateSortedList("items[0].equipmentTypes", listOf("LASER_A"))
        digest.updateSortedList("items[0].roomTypes", listOf("PROCEDURE"))
        digest.updateField("dependencies.size", 0)
        digest.updateField("initialBookingRule.type", "WITHIN_DAYS_AFTER_PURCHASE")
        digest.updateField("initialBookingRule.maximumDays", 14)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun catalogHashContractPath(): Path =
        listOf(
            Path.of("docs/api/catalog-payload-hash.md"),
            Path.of("../docs/api/catalog-payload-hash.md"),
        ).first { path -> path.toFile().isFile }

    private fun MessageDigest.updateSortedList(
        name: String,
        values: List<String>,
    ) {
        val sortedValues = values.sorted()
        updateField("$name.size", sortedValues.size)
        sortedValues.forEachIndexed { index, value ->
            updateField("$name[$index]", value)
        }
    }

    private fun MessageDigest.updateField(
        name: String,
        value: Any?,
    ) {
        update(name.toByteArray(StandardCharsets.UTF_8))
        update(0)
        if (value == null) {
            update(-1)
        } else {
            val valueBytes = value.toString().toByteArray(StandardCharsets.UTF_8)
            update(valueBytes.size.toString().toByteArray(StandardCharsets.UTF_8))
            update(0)
            update(valueBytes)
        }
        update(0)
    }
}

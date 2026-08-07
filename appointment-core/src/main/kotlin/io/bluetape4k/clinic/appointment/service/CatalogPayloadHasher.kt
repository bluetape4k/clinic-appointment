package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 검증한 catalog definition의 canonical SHA-256 hash를 생성합니다.
 */
object CatalogPayloadHasher {

    /**
     * 이름과 길이를 함께 프레임화한 필드로 [definition]을 검증하고 hash를 계산합니다.
     */
    fun hash(definition: ProductCatalogDefinition): String {
        val valid = CatalogDefinitionValidator.validate(definition)
        return MessageDigest.getInstance("SHA-256")
            .apply { updateDefinition(valid) }
            .digest()
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun MessageDigest.updateDefinition(definition: ProductCatalogDefinition) {
        updateField("tenantGroupId", definition.tenantGroupId)
        updateField("clinicId", definition.clinicId)
        updateField("sourceAuthority", definition.sourceAuthority)
        updateField("productId", definition.productId)
        updateField("catalogVersion", definition.catalogVersion)
        updateField("productName", definition.productName)
        updateField("schemaVersion", definition.schemaVersion)
        updateField("sourceUpdatedAt", definition.sourceUpdatedAt)
        updateField("status", definition.status)

        updateField("items.size", definition.items.size)
        definition.items.forEachIndexed { index, item ->
            updateItem("items[$index]", item)
        }

        updateField("dependencies.size", definition.dependencies.size)
        definition.dependencies.forEachIndexed { index, dependency ->
            updateDependency("dependencies[$index]", dependency)
        }

        when (val rule = definition.initialBookingRule) {
            null -> updateField("initialBookingRule.type", null)
            is InitialBookingRule.WithinDaysAfterPurchase -> {
                updateField("initialBookingRule.type", "WITHIN_DAYS_AFTER_PURCHASE")
                updateField("initialBookingRule.maximumDays", rule.maximumDays)
            }
        }
    }

    private fun MessageDigest.updateItem(
        prefix: String,
        item: CatalogBomItem,
    ) {
        updateField("$prefix.bomItemId", item.bomItemId)
        updateField("$prefix.representativeTreatmentName", item.representativeTreatmentName)
        updateSortedList("$prefix.detailedTreatmentCodes", item.detailedTreatmentCodes)
        updateField("$prefix.repeatCount", item.repeatCount)
        updateField("$prefix.durationMinutes", item.durationMinutes)
        updateField("$prefix.minimumIntervalDays", item.minimumIntervalDays)
        updateField("$prefix.preferredIntervalDays", item.preferredIntervalDays)
        updateField("$prefix.maximumIntervalDays", item.maximumIntervalDays)
        updateSortedList("$prefix.practitionerQualifications", item.practitionerQualifications)
        updateSortedList("$prefix.equipmentTypes", item.equipmentTypes)
        updateSortedList("$prefix.roomTypes", item.roomTypes)
    }

    private fun MessageDigest.updateDependency(
        prefix: String,
        dependency: CatalogBomDependency,
    ) {
        updateField("$prefix.predecessorBomItemId", dependency.predecessorBomItemId)
        updateField("$prefix.predecessorSequenceNo", dependency.predecessorSequenceNo)
        updateField("$prefix.successorBomItemId", dependency.successorBomItemId)
        updateField("$prefix.successorSequenceNo", dependency.successorSequenceNo)
        updateField("$prefix.minimumIntervalDays", dependency.minimumIntervalDays)
        updateField("$prefix.preferredIntervalDays", dependency.preferredIntervalDays)
        updateField("$prefix.maximumIntervalDays", dependency.maximumIntervalDays)
    }

    private fun MessageDigest.updateSortedList(
        name: String,
        values: List<String>,
    ) {
        val sorted = values.sorted()
        updateField("$name.size", sorted.size)
        sorted.forEachIndexed { index, value -> updateField("$name[$index]", value) }
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

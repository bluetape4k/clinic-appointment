package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidator
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

data class CatalogProductVersionRequest(
    @field:NotBlank
    @field:Size(max = CatalogDefinitionValidator.MAX_IDENTIFIER_LENGTH)
    val sourceAuthority: String,
    @field:Positive
    val tenantGroupId: Long,
    @field:Positive
    val clinicId: Long,
    @field:NotBlank
    @field:Size(max = CatalogDefinitionValidator.MAX_IDENTIFIER_LENGTH)
    val productId: String,
    @field:Positive
    val catalogVersion: Long,
    @field:Positive
    val schemaVersion: Int,
    val sourceUpdatedAt: Instant,
    val status: CatalogProjectionStatus,
    @field:NotBlank
    @field:Size(max = CatalogDefinitionValidator.MAX_NAME_LENGTH)
    val productName: String,
    @field:NotEmpty
    @field:Size(max = CatalogDefinitionValidator.MAX_BOM_ITEMS)
    @field:Valid
    val items: List<CatalogBomItemRequest>,
    @field:Size(max = CatalogDefinitionValidator.MAX_CATALOG_DEPENDENCIES)
    @field:Valid
    val dependencies: List<CatalogBomDependencyRequest> = emptyList(),
    @field:Valid
    val initialBookingRule: InitialBookingRuleRequest? = null,
    @field:Pattern(regexp = "[0-9a-f]{64}")
    val payloadHash: String,
) {
    fun toDefinition() = ProductCatalogDefinition(
        sourceAuthority = sourceAuthority,
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        productId = productId,
        catalogVersion = catalogVersion,
        schemaVersion = schemaVersion,
        sourceUpdatedAt = sourceUpdatedAt,
        status = status,
        productName = productName,
        items = items.map(CatalogBomItemRequest::toDomain),
        dependencies = dependencies.map(CatalogBomDependencyRequest::toDomain),
        initialBookingRule = initialBookingRule?.toDomain(),
    )
}

data class CatalogBomItemRequest(
    @field:NotBlank
    @field:Size(max = CatalogDefinitionValidator.MAX_IDENTIFIER_LENGTH)
    val bomItemId: String,
    @field:NotBlank
    @field:Size(max = CatalogDefinitionValidator.MAX_NAME_LENGTH)
    val representativeTreatmentName: String,
    @field:Size(max = CatalogDefinitionValidator.MAX_REQUIREMENT_VALUES)
    val detailedTreatmentCodes: List<String> = emptyList(),
    @field:Min(1)
    @field:Max(CatalogDefinitionValidator.MAX_REPEATS_PER_ITEM.toLong())
    val repeatCount: Int,
    @field:Min(1)
    @field:Max(CatalogDefinitionValidator.MAX_DURATION_MINUTES.toLong())
    val durationMinutes: Int,
    @field:Min(0)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val minimumIntervalDays: Int? = null,
    @field:Min(0)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val preferredIntervalDays: Int? = null,
    @field:Min(0)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val maximumIntervalDays: Int? = null,
    @field:Size(max = CatalogDefinitionValidator.MAX_REQUIREMENT_VALUES)
    val practitionerQualifications: List<String> = emptyList(),
    @field:Size(max = CatalogDefinitionValidator.MAX_REQUIREMENT_VALUES)
    val equipmentTypes: List<String> = emptyList(),
    @field:Size(max = CatalogDefinitionValidator.MAX_REQUIREMENT_VALUES)
    val roomTypes: List<String> = emptyList(),
) {
    fun toDomain() = CatalogBomItem(
        bomItemId = bomItemId,
        representativeTreatmentName = representativeTreatmentName,
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
}

data class CatalogBomDependencyRequest(
    @field:NotBlank
    val predecessorBomItemId: String,
    @field:Positive
    val predecessorSequenceNo: Int? = null,
    @field:NotBlank
    val successorBomItemId: String,
    @field:Positive
    val successorSequenceNo: Int? = null,
    @field:Min(0)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val minimumIntervalDays: Int,
    @field:Min(0)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val preferredIntervalDays: Int,
    @field:Min(0)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val maximumIntervalDays: Int,
) {
    fun toDomain() = CatalogBomDependency(
        predecessorBomItemId = predecessorBomItemId,
        predecessorSequenceNo = predecessorSequenceNo,
        successorBomItemId = successorBomItemId,
        successorSequenceNo = successorSequenceNo,
        minimumIntervalDays = minimumIntervalDays,
        preferredIntervalDays = preferredIntervalDays,
        maximumIntervalDays = maximumIntervalDays,
    )
}

data class InitialBookingRuleRequest(
    val type: String,
    @field:Min(1)
    @field:Max(CatalogDefinitionValidator.MAX_INTERVAL_DAYS.toLong())
    val maximumDays: Int,
) {
    fun toDomain(): InitialBookingRule {
        require(type == "WITHIN_DAYS_AFTER_PURCHASE") { "initialBookingRule.type is unsupported" }
        return InitialBookingRule.WithinDaysAfterPurchase(maximumDays)
    }
}

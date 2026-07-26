package io.bluetape4k.clinic.appointment.model.catalog

import java.io.Serializable
import java.time.Instant

/**
 * Immutable product catalog version consumed by appointment planning.
 */
data class ProductCatalogDefinition(
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val productName: String,
    val schemaVersion: Int,
    val sourceUpdatedAt: Instant,
    val items: List<CatalogBomItem>,
    val dependencies: List<CatalogBomDependency>,
    val initialBookingRule: InitialBookingRule?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * One treatment definition in a product bill of materials.
 */
data class CatalogBomItem(
    val bomItemId: String,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val repeatCount: Int,
    val durationMinutes: Int,
    val minimumIntervalDays: Int?,
    val preferredIntervalDays: Int?,
    val maximumIntervalDays: Int?,
    val practitionerQualifications: List<String>,
    val equipmentTypes: List<String>,
    val roomTypes: List<String>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * A directed dependency between two BOM item occurrences.
 *
 * A null predecessor sequence selects the last predecessor occurrence, while a
 * null successor sequence selects the first successor occurrence.
 */
data class CatalogBomDependency(
    val predecessorBomItemId: String,
    val predecessorSequenceNo: Int? = null,
    val successorBomItemId: String,
    val successorSequenceNo: Int? = null,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Rule used only when a purchase does not contain a customer booking preference.
 */
sealed interface InitialBookingRule : Serializable {

    /**
     * Requires an initial provisional booking within [maximumDays] after purchase.
     */
    data class WithinDaysAfterPurchase(
        val maximumDays: Int,
    ) : InitialBookingRule {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

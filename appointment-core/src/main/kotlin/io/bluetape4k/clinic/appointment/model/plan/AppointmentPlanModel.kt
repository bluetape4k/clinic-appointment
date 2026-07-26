package io.bluetape4k.clinic.appointment.model.plan

import java.io.Serializable
import java.time.Instant

/**
 * Lifecycle state of a purchased appointment plan.
 */
enum class AppointmentPlanStatus {
    ACTIVE,
    PARTIALLY_FULFILLED,
    FULFILLED,
    CANCELLED,
}

/**
 * Lifecycle state of one treatment obligation in a plan.
 */
enum class PlannedTreatmentStatus {
    PLANNED,
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    BLOCKED_REVIEW,
}

/**
 * Tenant-scoped read view of an immutable purchase plan.
 */
data class AppointmentPlanView(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val productId: String,
    val catalogVersion: Long,
    val catalogPayloadHash: String,
    val productName: String,
    val bookingPreference: BookingPreferenceSnapshot,
    val status: AppointmentPlanStatus,
    val treatments: List<PlannedTreatmentView>,
    val dependencies: List<TreatmentDependencyView>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Read view of one treatment obligation copied from the catalog snapshot.
 */
data class PlannedTreatmentView(
    val id: Long,
    val bomItemId: String,
    val sequenceNo: Int,
    val bomOrder: Int,
    val representativeTreatmentName: String,
    val detailedTreatmentCodes: List<String>,
    val durationMinutes: Int,
    val minimumIntervalDays: Int?,
    val preferredIntervalDays: Int?,
    val maximumIntervalDays: Int?,
    val practitionerQualifications: List<String>,
    val equipmentTypes: List<String>,
    val roomTypes: List<String>,
    val earliestStartAt: Instant?,
    val latestStartAt: Instant?,
    val status: PlannedTreatmentStatus,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Read view of a directed dependency between two planned treatments.
 */
data class TreatmentDependencyView(
    val predecessorTreatmentId: Long,
    val successorTreatmentId: Long,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import java.io.Serializable
import java.time.Instant

/**
 * Persisted appointment-plan root without raw patient identifiers.
 */
data class AppointmentPlanRecord(
    val id: Long? = null,
    val tenantGroupId: Long,
    val clinicId: Long,
    val catalogProjectionId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val patientReferenceCiphertext: String,
    val patientReferenceKeyId: String,
    val patientReferenceFingerprint: String,
    val productId: String,
    val catalogVersion: Long,
    val catalogPayloadHash: String,
    val productName: String,
    val bookingPreference: BookingPreferenceSnapshot,
    val status: AppointmentPlanStatus,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Complete appointment-plan aggregate used at the repository boundary.
 */
data class AppointmentPlanAggregateRecord(
    val plan: AppointmentPlanRecord,
    val treatments: List<PlannedTreatmentRecord>,
    val dependencies: List<TreatmentDependencyRecord>,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

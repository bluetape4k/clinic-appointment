package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import java.io.Serializable

data class PurchaseCompletedEvent(
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val patientReferenceToken: String,
    val productId: String,
    val catalogVersion: Long,
    val bookingPreference: BookingPreferenceSnapshot,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

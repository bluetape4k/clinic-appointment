package io.bluetape4k.clinic.appointment.model.dto

import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus
import java.io.Serializable
import java.time.Instant

/**
 * Stable logical key of a treatment occurrence inside one plan.
 */
data class PlannedTreatmentKey(
    val bomItemId: String,
    val sequenceNo: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Persisted treatment obligation copied from a catalog snapshot.
 */
data class PlannedTreatmentRecord(
    val id: Long? = null,
    val planId: Long? = null,
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

    val key: PlannedTreatmentKey
        get() = PlannedTreatmentKey(bomItemId, sequenceNo)
}

/**
 * Persisted directed edge between two treatment occurrences.
 */
data class TreatmentDependencyRecord(
    val id: Long? = null,
    val planId: Long? = null,
    val predecessorTreatmentId: Long? = null,
    val successorTreatmentId: Long? = null,
    val predecessor: PlannedTreatmentKey,
    val successor: PlannedTreatmentKey,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

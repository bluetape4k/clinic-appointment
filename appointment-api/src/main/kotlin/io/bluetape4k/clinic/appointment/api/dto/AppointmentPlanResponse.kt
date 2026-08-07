package io.bluetape4k.clinic.appointment.api.dto

import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanView
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.LocalTimeWindow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

data class AppointmentPlanResponse(
    val id: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val catalogSourceAuthority: String,
    val productId: String,
    val catalogVersion: Long,
    val catalogPayloadHash: String,
    val productName: String,
    val bookingPreference: BookingPreferenceResponse,
    val status: String,
    val treatments: List<PlannedTreatmentResponse>,
    val dependencies: List<TreatmentDependencyResponse>,
) {
    companion object {
        fun from(view: AppointmentPlanView) = AppointmentPlanResponse(
            id = view.id,
            tenantGroupId = view.tenantGroupId,
            clinicId = view.clinicId,
            sourcePurchaseAuthority = view.sourcePurchaseAuthority,
            sourcePurchaseId = view.sourcePurchaseId,
            catalogSourceAuthority = view.catalogSourceAuthority,
            productId = view.productId,
            catalogVersion = view.catalogVersion,
            catalogPayloadHash = view.catalogPayloadHash,
            productName = view.productName,
            bookingPreference = BookingPreferenceResponse.from(view.bookingPreference),
            status = view.status.name,
            treatments = view.treatments.map(PlannedTreatmentResponse::from),
            dependencies = view.dependencies.map(TreatmentDependencyResponse::from),
        )
    }
}

/**
 * 성공한 appointment plan envelope를 위한 구체적인 OpenAPI 스키마입니다.
 */
data class AppointmentPlanApiResponse(
    val success: Boolean,
    val data: AppointmentPlanResponse,
    val error: String? = null,
)

data class BookingPreferenceResponse(
    val type: String,
    val originalLocalDateTime: LocalDateTime? = null,
    val originalOffset: ZoneOffset? = null,
    val normalizedInstant: Instant? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val weekdays: List<DayOfWeek>? = null,
    val localTimeWindows: List<LocalTimeWindow>? = null,
    val zoneId: String? = null,
) {
    companion object {
        fun from(preference: BookingPreferenceSnapshot): BookingPreferenceResponse =
            when (preference) {
                is BookingPreferenceSnapshot.ExactDateTime ->
                    BookingPreferenceResponse(
                        type = "EXACT_DATE_TIME",
                        originalLocalDateTime = preference.originalLocalDateTime,
                        originalOffset = preference.originalOffset,
                        normalizedInstant = preference.normalizedInstant,
                        zoneId = preference.zoneId.id,
                    )

                is BookingPreferenceSnapshot.DateRange ->
                    BookingPreferenceResponse(
                        type = "DATE_RANGE",
                        startDate = preference.startDate,
                        endDate = preference.endDate,
                        zoneId = preference.zoneId.id,
                    )

                is BookingPreferenceSnapshot.PreferredWeekdaysAndWindows ->
                    BookingPreferenceResponse(
                        type = "PREFERRED_WEEKDAYS_AND_WINDOWS",
                        weekdays = preference.weekdays,
                        localTimeWindows = preference.localTimeWindows,
                        zoneId = preference.zoneId.id,
                    )

                BookingPreferenceSnapshot.NotProvided ->
                    BookingPreferenceResponse(type = "NOT_PROVIDED")
            }
    }
}

data class PlannedTreatmentResponse(
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
    val status: String,
) {
    companion object {
        fun from(view: io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentView) =
            PlannedTreatmentResponse(
                id = view.id,
                bomItemId = view.bomItemId,
                sequenceNo = view.sequenceNo,
                bomOrder = view.bomOrder,
                representativeTreatmentName = view.representativeTreatmentName,
                detailedTreatmentCodes = view.detailedTreatmentCodes,
                durationMinutes = view.durationMinutes,
                minimumIntervalDays = view.minimumIntervalDays,
                preferredIntervalDays = view.preferredIntervalDays,
                maximumIntervalDays = view.maximumIntervalDays,
                practitionerQualifications = view.practitionerQualifications,
                equipmentTypes = view.equipmentTypes,
                roomTypes = view.roomTypes,
                earliestStartAt = view.earliestStartAt,
                latestStartAt = view.latestStartAt,
                status = view.status.name,
            )
    }
}

data class TreatmentDependencyResponse(
    val predecessorTreatmentId: Long,
    val successorTreatmentId: Long,
    val minimumIntervalDays: Int,
    val preferredIntervalDays: Int,
    val maximumIntervalDays: Int,
) {
    companion object {
        fun from(view: io.bluetape4k.clinic.appointment.model.plan.TreatmentDependencyView) =
            TreatmentDependencyResponse(
                predecessorTreatmentId = view.predecessorTreatmentId,
                successorTreatmentId = view.successorTreatmentId,
                minimumIntervalDays = view.minimumIntervalDays,
                preferredIntervalDays = view.preferredIntervalDays,
                maximumIntervalDays = view.maximumIntervalDays,
            )
    }
}

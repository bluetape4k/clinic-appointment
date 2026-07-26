package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanRecord
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentRecord
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentDependencyRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentStatus

data class AppointmentPlanFactoryInput(
    val sourcePurchaseAuthority: String,
    val sourcePurchaseId: String,
    val patientReferenceCiphertext: String,
    val patientReferenceKeyId: String,
    val patientReferenceFingerprint: String,
    val bookingPreference: BookingPreferenceSnapshot,
)

/**
 * Expands one immutable catalog snapshot into a persistence-ready plan draft.
 *
 * The factory is deterministic and performs no I/O. Appointment dates remain
 * unset; customer preferences are copied without interpreting them as holds or
 * confirmed bookings.
 */
class AppointmentPlanFactory {

    fun create(
        catalog: ProductCatalogProjectionRecord,
        input: AppointmentPlanFactoryInput,
    ): AppointmentPlanAggregateRecord {
        val catalogId = requireNotNull(catalog.id) { "catalog projection id is required" }
        val definition = CatalogDefinitionValidator.validate(catalog.definition)
        require(definition.status == CatalogProjectionStatus.ACTIVE) {
            "retired catalog projections cannot create appointment plans"
        }
        require(input.sourcePurchaseAuthority.isNotBlank()) { "sourcePurchaseAuthority must not be blank" }
        require(input.sourcePurchaseId.isNotBlank()) { "sourcePurchaseId must not be blank" }
        require(input.patientReferenceCiphertext.isNotBlank()) { "patientReferenceCiphertext must not be blank" }
        require(input.patientReferenceKeyId.isNotBlank()) { "patientReferenceKeyId must not be blank" }
        require(input.patientReferenceFingerprint.isNotBlank()) { "patientReferenceFingerprint must not be blank" }

        val treatments = definition.items.flatMapIndexed { bomOrder, item ->
            (1..item.repeatCount).map { sequenceNo ->
                PlannedTreatmentRecord(
                    bomItemId = item.bomItemId,
                    sequenceNo = sequenceNo,
                    bomOrder = bomOrder,
                    representativeTreatmentName = item.representativeTreatmentName,
                    detailedTreatmentCodes = item.detailedTreatmentCodes.toList(),
                    durationMinutes = item.durationMinutes,
                    minimumIntervalDays = item.minimumIntervalDays,
                    preferredIntervalDays = item.preferredIntervalDays,
                    maximumIntervalDays = item.maximumIntervalDays,
                    practitionerQualifications = item.practitionerQualifications.toList(),
                    equipmentTypes = item.equipmentTypes.toList(),
                    roomTypes = item.roomTypes.toList(),
                    earliestStartAt = null,
                    latestStartAt = null,
                    status = PlannedTreatmentStatus.PLANNED,
                )
            }
        }
        val repeatCounts = definition.items.associate { item -> item.bomItemId to item.repeatCount }
        val dependencies = definition.dependencies.map { dependency ->
            val predecessorSequence = dependency.predecessorSequenceNo
                ?: requireNotNull(repeatCounts[dependency.predecessorBomItemId])
            val successorSequence = dependency.successorSequenceNo ?: 1
            TreatmentDependencyRecord(
                predecessor = PlannedTreatmentKey(dependency.predecessorBomItemId, predecessorSequence),
                successor = PlannedTreatmentKey(dependency.successorBomItemId, successorSequence),
                minimumIntervalDays = dependency.minimumIntervalDays,
                preferredIntervalDays = dependency.preferredIntervalDays,
                maximumIntervalDays = dependency.maximumIntervalDays,
            )
        }

        return AppointmentPlanAggregateRecord(
            plan = AppointmentPlanRecord(
                tenantGroupId = definition.tenantGroupId,
                clinicId = definition.clinicId,
                catalogProjectionId = catalogId,
                sourcePurchaseAuthority = input.sourcePurchaseAuthority,
                sourcePurchaseId = input.sourcePurchaseId,
                patientReferenceCiphertext = input.patientReferenceCiphertext,
                patientReferenceKeyId = input.patientReferenceKeyId,
                patientReferenceFingerprint = input.patientReferenceFingerprint,
                catalogSourceAuthority = definition.sourceAuthority,
                productId = definition.productId,
                catalogVersion = definition.catalogVersion,
                catalogPayloadHash = catalog.payloadHash,
                productName = definition.productName,
                bookingPreference = input.bookingPreference,
                status = AppointmentPlanStatus.ACTIVE,
            ),
            treatments = treatments,
            dependencies = dependencies,
        )
    }
}

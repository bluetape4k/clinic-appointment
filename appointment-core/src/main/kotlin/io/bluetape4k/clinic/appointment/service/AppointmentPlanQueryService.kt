package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanView
import io.bluetape4k.clinic.appointment.model.plan.PlannedTreatmentView
import io.bluetape4k.clinic.appointment.model.plan.TreatmentDependencyView
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Tenant and clinic scoped read boundary for appointment plans.
 */
class AppointmentPlanQueryService(
    private val repository: AppointmentPlanRepository,
) {
    fun findById(
        tenantGroupId: Long,
        clinicId: Long,
        planId: Long,
    ): AppointmentPlanView? =
        transaction {
            repository.findByIdAndTenantClinic(planId, tenantGroupId, clinicId)?.toView()
        }

    fun findBySourcePurchase(
        tenantGroupId: Long,
        clinicId: Long,
        sourcePurchaseAuthority: String,
        sourcePurchaseId: String,
    ): AppointmentPlanView? =
        transaction {
            repository.findBySourcePurchaseAndTenantClinic(
                sourcePurchaseAuthority = sourcePurchaseAuthority,
                sourcePurchaseId = sourcePurchaseId,
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
            )?.toView()
        }

    private fun AppointmentPlanAggregateRecord.toView(): AppointmentPlanView =
        AppointmentPlanView(
            id = requireNotNull(plan.id),
            tenantGroupId = plan.tenantGroupId,
            clinicId = plan.clinicId,
            sourcePurchaseAuthority = plan.sourcePurchaseAuthority,
            sourcePurchaseId = plan.sourcePurchaseId,
            productId = plan.productId,
            catalogVersion = plan.catalogVersion,
            catalogPayloadHash = plan.catalogPayloadHash,
            productName = plan.productName,
            bookingPreference = plan.bookingPreference,
            status = plan.status,
            treatments = treatments.map { treatment ->
                PlannedTreatmentView(
                    id = requireNotNull(treatment.id),
                    bomItemId = treatment.bomItemId,
                    sequenceNo = treatment.sequenceNo,
                    bomOrder = treatment.bomOrder,
                    representativeTreatmentName = treatment.representativeTreatmentName,
                    detailedTreatmentCodes = treatment.detailedTreatmentCodes,
                    durationMinutes = treatment.durationMinutes,
                    minimumIntervalDays = treatment.minimumIntervalDays,
                    preferredIntervalDays = treatment.preferredIntervalDays,
                    maximumIntervalDays = treatment.maximumIntervalDays,
                    practitionerQualifications = treatment.practitionerQualifications,
                    equipmentTypes = treatment.equipmentTypes,
                    roomTypes = treatment.roomTypes,
                    earliestStartAt = treatment.earliestStartAt,
                    latestStartAt = treatment.latestStartAt,
                    status = treatment.status,
                )
            },
            dependencies = dependencies.map { dependency ->
                TreatmentDependencyView(
                    predecessorTreatmentId = requireNotNull(dependency.predecessorTreatmentId),
                    successorTreatmentId = requireNotNull(dependency.successorTreatmentId),
                    minimumIntervalDays = dependency.minimumIntervalDays,
                    preferredIntervalDays = dependency.preferredIntervalDays,
                    maximumIntervalDays = dependency.maximumIntervalDays,
                )
            },
        )
}

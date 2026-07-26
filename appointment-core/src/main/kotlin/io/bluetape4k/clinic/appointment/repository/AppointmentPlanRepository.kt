package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.AppointmentPlanAggregateRecord
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentKey
import io.bluetape4k.clinic.appointment.model.dto.PlannedTreatmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentDependencyRecord
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Stores and loads complete appointment-plan aggregates inside a caller-owned transaction.
 */
class AppointmentPlanRepository {

    /**
     * Inserts a plan, its treatment obligations, and materialized dependency edges atomically.
     */
    fun saveAggregate(aggregate: AppointmentPlanAggregateRecord): AppointmentPlanAggregateRecord {
        val plan = aggregate.plan
        require(plan.patientReferenceCiphertext.isNotBlank()) { "patientReferenceCiphertext must not be blank" }
        require(plan.patientReferenceKeyId.isNotBlank()) { "patientReferenceKeyId must not be blank" }
        require(plan.patientReferenceFingerprint.isNotBlank()) { "patientReferenceFingerprint must not be blank" }
        require(aggregate.treatments.isNotEmpty()) { "treatments must not be empty" }
        require(aggregate.treatments.map(PlannedTreatmentRecord::key).distinct().size == aggregate.treatments.size) {
            "treatments must have unique logical keys"
        }

        val catalogScope = ProductCatalogProjections
            .selectAll()
            .where { ProductCatalogProjections.id eq plan.catalogProjectionId }
            .singleOrNull()
        requireNotNull(catalogScope) { "catalog projection does not exist" }
        require(catalogScope[ProductCatalogProjections.tenantGroupId].value == plan.tenantGroupId) {
            "catalog tenant does not match plan tenant"
        }
        require(catalogScope[ProductCatalogProjections.clinicId].value == plan.clinicId) {
            "catalog clinic does not match plan clinic"
        }
        require(catalogScope[ProductCatalogProjections.productId] == plan.productId) {
            "catalog product does not match plan product"
        }
        require(catalogScope[ProductCatalogProjections.catalogVersion] == plan.catalogVersion) {
            "catalog version does not match plan catalogVersion"
        }
        require(catalogScope[ProductCatalogProjections.payloadHash] == plan.catalogPayloadHash) {
            "catalog payload hash does not match plan catalogPayloadHash"
        }
        require(catalogScope[ProductCatalogProjections.productName] == plan.productName) {
            "catalog product name does not match plan productName"
        }

        val encodedPreference = encodeBookingPreference(plan.bookingPreference)
        val planId = AppointmentPlans.insertAndGetId {
            it[tenantGroupId] = plan.tenantGroupId
            it[clinicId] = plan.clinicId
            it[catalogProjectionId] = plan.catalogProjectionId
            it[sourcePurchaseAuthority] = plan.sourcePurchaseAuthority
            it[sourcePurchaseId] = plan.sourcePurchaseId
            it[patientReferenceCiphertext] = plan.patientReferenceCiphertext
            it[patientReferenceKeyId] = plan.patientReferenceKeyId
            it[patientReferenceFingerprint] = plan.patientReferenceFingerprint
            it[productId] = plan.productId
            it[catalogVersion] = plan.catalogVersion
            it[catalogPayloadHash] = plan.catalogPayloadHash
            it[productName] = plan.productName
            it[bookingPreferenceType] = encodedPreference.first
            it[bookingPreferencePayload] = encodedPreference.second
            it[status] = plan.status
        }.value

        val treatmentIds = LinkedHashMap<PlannedTreatmentKey, Long>(aggregate.treatments.size)
        aggregate.treatments.forEach { treatment ->
            val treatmentId = PlannedTreatments.insertAndGetId {
                it[PlannedTreatments.planId] = planId
                it[bomItemId] = treatment.bomItemId
                it[sequenceNo] = treatment.sequenceNo
                it[bomOrder] = treatment.bomOrder
                it[representativeTreatmentName] = treatment.representativeTreatmentName
                it[detailedTreatmentCodesJson] = encodeStringList(treatment.detailedTreatmentCodes)
                it[durationMinutes] = treatment.durationMinutes
                it[minimumIntervalDays] = treatment.minimumIntervalDays
                it[preferredIntervalDays] = treatment.preferredIntervalDays
                it[maximumIntervalDays] = treatment.maximumIntervalDays
                it[practitionerQualificationsJson] = encodeStringList(treatment.practitionerQualifications)
                it[equipmentTypesJson] = encodeStringList(treatment.equipmentTypes)
                it[roomTypesJson] = encodeStringList(treatment.roomTypes)
                it[earliestStartAt] = treatment.earliestStartAt
                it[latestStartAt] = treatment.latestStartAt
                it[status] = treatment.status
            }.value
            treatmentIds[treatment.key] = treatmentId
        }

        aggregate.dependencies.forEach { dependency ->
            val predecessorId = requireNotNull(treatmentIds[dependency.predecessor]) {
                "unknown predecessor treatment(${dependency.predecessor})"
            }
            val successorId = requireNotNull(treatmentIds[dependency.successor]) {
                "unknown successor treatment(${dependency.successor})"
            }
            TreatmentDependencies.insertAndGetId {
                it[TreatmentDependencies.planId] = planId
                it[predecessorTreatmentId] = predecessorId
                it[successorTreatmentId] = successorId
                it[minimumIntervalDays] = dependency.minimumIntervalDays
                it[preferredIntervalDays] = dependency.preferredIntervalDays
                it[maximumIntervalDays] = dependency.maximumIntervalDays
            }
        }

        return requireNotNull(findByIdAndTenantClinic(planId, plan.tenantGroupId, plan.clinicId))
    }

    /**
     * Finds a plan only within the exact tenant and clinic scope.
     */
    fun findByIdAndTenantClinic(
        id: Long,
        tenantGroupId: Long,
        clinicId: Long,
    ): AppointmentPlanAggregateRecord? =
        AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.id eq id) and
                    (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId)
            }
            .singleOrNull()
            ?.let(::mapAggregate)

    /**
     * Finds a plan by source purchase only within the exact tenant and clinic scope.
     */
    fun findBySourcePurchaseAndTenantClinic(
        sourcePurchaseAuthority: String,
        sourcePurchaseId: String,
        tenantGroupId: Long,
        clinicId: Long,
    ): AppointmentPlanAggregateRecord? =
        AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.tenantGroupId eq tenantGroupId) and
                    (AppointmentPlans.clinicId eq clinicId) and
                    (AppointmentPlans.sourcePurchaseAuthority eq sourcePurchaseAuthority) and
                    (AppointmentPlans.sourcePurchaseId eq sourcePurchaseId)
            }
            .singleOrNull()
            ?.let(::mapAggregate)

    /**
     * Finds the globally unique source purchase regardless of request scope.
     *
     * This method is for trusted event convergence only. Tenant-facing reads
     * must use [findBySourcePurchaseAndTenantClinic].
     */
    fun findBySourcePurchase(
        sourcePurchaseAuthority: String,
        sourcePurchaseId: String,
    ): AppointmentPlanAggregateRecord? =
        AppointmentPlans
            .selectAll()
            .where {
                (AppointmentPlans.sourcePurchaseAuthority eq sourcePurchaseAuthority) and
                    (AppointmentPlans.sourcePurchaseId eq sourcePurchaseId)
            }
            .singleOrNull()
            ?.let(::mapAggregate)

    private fun mapAggregate(row: org.jetbrains.exposed.v1.core.ResultRow): AppointmentPlanAggregateRecord {
        val plan = row.toAppointmentPlanRecord()
        val planId = requireNotNull(plan.id)
        val treatments = PlannedTreatments
            .selectAll()
            .where { PlannedTreatments.planId eq planId }
            .orderBy(PlannedTreatments.bomOrder to SortOrder.ASC, PlannedTreatments.sequenceNo to SortOrder.ASC)
            .map { treatmentRow -> treatmentRow.toPlannedTreatmentRecord() }
        val keysByTreatmentId = treatments.associate { treatment ->
            requireNotNull(treatment.id) to treatment.key
        }
        val dependencies = TreatmentDependencies
            .selectAll()
            .where { TreatmentDependencies.planId eq planId }
            .orderBy(
                TreatmentDependencies.predecessorTreatmentId to SortOrder.ASC,
                TreatmentDependencies.successorTreatmentId to SortOrder.ASC,
            )
            .map { dependencyRow -> dependencyRow.toTreatmentDependencyRecord(keysByTreatmentId) }
        return AppointmentPlanAggregateRecord(plan, treatments, dependencies)
    }
}

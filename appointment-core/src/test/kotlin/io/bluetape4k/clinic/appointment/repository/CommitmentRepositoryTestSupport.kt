package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.ConsentDecisions
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionGroupingConstraints
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.model.tables.ResourceCapacityBuckets
import io.bluetape4k.clinic.appointment.model.tables.TreatmentSpaces
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

internal data class CommitmentSeed(
    val clinicId: Long,
    val appointmentId: Long,
    val planId: Long,
)

internal fun withCommitmentTables(
    statement: JdbcTransaction.(CommitmentSeed) -> Unit,
) = withTables(
    TestDB.H2_COMMITMENT,
    Clinics,
    Doctors,
    TreatmentTypes,
    Equipments,
    ConsultationTopics,
    Appointments,
    ProductCatalogProjections,
    AppointmentPlans,
    AppointmentPlanRevisions,
    PlanRevisionTreatments,
    PlanRevisionDependencies,
    PlanRevisionGroupingConstraints,
    AppointmentCommitments,
    AppointmentProposals,
    ConsentDecisions,
    AppointmentItems,
    TreatmentSpaces,
    ResourceCapacityBuckets,
    ResourceAllocations,
    AppointmentOperationalExceptions,
    AppointmentCommandIdempotencies,
) {
    val clinicId = Clinics.insertAndGetId {
        it[name] = "Clinic"
        it[timezone] = "Asia/Seoul"
    }.value
    val doctorId = Doctors.insertAndGetId {
        it[Doctors.clinicId] = clinicId
        it[name] = "Doctor"
    }.value
    val treatmentTypeId = TreatmentTypes.insertAndGetId {
        it[TreatmentTypes.clinicId] = clinicId
        it[name] = "Treatment"
        it[defaultDurationMinutes] = 30
    }.value
    val appointmentId = Appointments.insertAndGetId {
        it[Appointments.clinicId] = clinicId
        it[Appointments.doctorId] = doctorId
        it[Appointments.treatmentTypeId] = treatmentTypeId
        it[patientName] = "Patient"
        it[appointmentDate] = LocalDate.of(2026, 8, 10)
        it[startTime] = LocalTime.of(10, 0)
        it[endTime] = LocalTime.of(10, 30)
    }.value
    val catalogId = ProductCatalogProjections.insertAndGetId {
        it[tenantGroupId] = 1L
        it[ProductCatalogProjections.clinicId] = clinicId
        it[sourceAuthority] = "product-service"
        it[productId] = "product"
        it[catalogVersion] = 1L
        it[productName] = "Package"
        it[schemaVersion] = 1
        it[sourceUpdatedAt] = Instant.parse("2026-08-01T00:00:00Z")
        it[status] = CatalogProjectionStatus.ACTIVE
        it[payloadHash] = "a".repeat(64)
    }.value
    val planId = AppointmentPlans.insertAndGetId {
        it[tenantGroupId] = 1L
        it[AppointmentPlans.clinicId] = clinicId
        it[catalogProjectionId] = catalogId
        it[sourcePurchaseAuthority] = "purchase-service"
        it[sourcePurchaseId] = "purchase-1"
        it[patientReferenceCiphertext] = "ciphertext"
        it[patientReferenceKeyId] = "key-1"
        it[patientReferenceFingerprint] = "f".repeat(64)
        it[catalogSourceAuthority] = "product-service"
        it[productId] = "product"
        it[catalogVersion] = 1L
        it[catalogPayloadHash] = "a".repeat(64)
        it[productName] = "Package"
        it[bookingPreferenceType] = "NOT_PROVIDED"
        it[bookingPreferencePayload] = "{}"
        it[status] = AppointmentPlanStatus.ACTIVE
    }.value
    statement(CommitmentSeed(clinicId, appointmentId, planId))
}

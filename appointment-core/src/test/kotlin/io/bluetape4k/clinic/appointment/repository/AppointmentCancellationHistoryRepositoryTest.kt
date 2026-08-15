package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.dto.CancellationHistoryBoundary
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCancellationDetails
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.AppointmentItems
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.AppointmentProposals
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Instant

class AppointmentCancellationHistoryRepositoryTest {

    @Test
    fun `patient scope와 boundary가 다른 취소 detail은 page에서 제외한다`() {
        val database = Database.connect(
            "jdbc:h2:mem:appointment-cancellation-history-${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                Appointments,
                AppointmentCommitments,
                AppointmentProposals,
                ProductCatalogProjections,
                AppointmentPlans,
                AppointmentPlanRevisions,
                PlanRevisionTreatments,
                AppointmentItems,
                AppointmentCancellationDetails,
            )

            val tenantId = TenantGroups.insertAndGetId {
                it[tenantCode] = "history-${System.nanoTime()}"
                it[displayName] = "History tenant"
            }.value
            val clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(tenantId, TenantGroups)
                it[name] = "History clinic"
            }.value

            val patientA = "a".repeat(64)
            val patientB = "b".repeat(64)
            val first = insertCancellation(clinicId, tenantId, patientA, Instant.parse("2026-08-10T01:00:00Z"))
            val second = insertCancellation(clinicId, tenantId, patientA, Instant.parse("2026-08-10T02:00:00Z"))
            val third = insertCancellation(clinicId, tenantId, patientA, Instant.parse("2026-08-10T03:00:00Z"))
            insertCancellation(clinicId, tenantId, patientB, Instant.parse("2026-08-10T04:00:00Z"))

            val repository = AppointmentCancellationHistoryRepository()
            val firstPage = repository.findPage(
                tenantGroupId = tenantId,
                patientScopeFingerprint = patientA,
                boundary = null,
                limit = 2,
            )

            firstPage.entries.map { it.detailId } shouldBeEqualTo listOf(third, second)
            firstPage.hasNext.shouldBeTrue()
            firstPage.entries.none { it.patientScopeFingerprint != patientA }.shouldBeTrue()

            val nextPage = repository.findPage(
                tenantGroupId = tenantId,
                patientScopeFingerprint = patientA,
                boundary = CancellationHistoryBoundary(firstPage.entries.last().occurredAt, second),
                limit = 2,
            )
            nextPage.entries.map { it.detailId } shouldBeEqualTo listOf(first)
            nextPage.hasNext.shouldBeFalse()

            assertFailsWith<CancellationHistoryAnchorMissingException> {
                repository.findPage(
                    tenantGroupId = tenantId,
                    patientScopeFingerprint = patientA,
                    boundary = CancellationHistoryBoundary(
                        occurredAt = Instant.parse("2026-08-10T02:00:00Z"),
                        detailId = Long.MAX_VALUE,
                    ),
                    limit = 2,
                )
            }
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertCancellation(
        clinicId: Long,
        tenantId: Long,
        patientScopeFingerprint: String,
        occurredAt: Instant,
    ): Long {
        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = EntityID(clinicId, Clinics)
            it[Doctors.name] = "Doctor-$occurredAt"
        }.value
        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = EntityID(clinicId, Clinics)
            it[TreatmentTypes.name] = "Treatment-$occurredAt"
            it[defaultDurationMinutes] = 30
        }.value
        val appointmentId = Appointments.insertAndGetId {
            it[Appointments.clinicId] = EntityID(clinicId, Clinics)
            it[Appointments.doctorId] = EntityID(doctorId, Doctors)
            it[Appointments.treatmentTypeId] = EntityID(treatmentTypeId, TreatmentTypes)
            it[patientName] = "Patient"
            it[Appointments.patientReferenceFingerprint] = patientScopeFingerprint
        }.value
        val commitmentId = AppointmentCommitments.insertAndGetId {
            it[AppointmentCommitments.appointmentId] = EntityID(appointmentId, Appointments)
            it[status] = AppointmentCommitmentStatus.CANCELLED
            it[origin] = AppointmentOrigin.PATIENT
            it[effectivePolicySnapshotId] = 1L
            it[version] = 1L
        }.value
        val proposalId = AppointmentProposals.insertAndGetId {
            it[AppointmentProposals.commitmentId] = EntityID(commitmentId, AppointmentCommitments)
            it[revision] = 1L
            it[proposedStartAt] = occurredAt.plusSeconds(3600)
            it[proposedEndAt] = occurredAt.plusSeconds(5400)
            it[expiresAt] = occurredAt.plusSeconds(7200)
            it[representativeTreatmentName] = "Treatment"
            it[proposalHash] = "h".repeat(64)
            it[policySnapshotId] = 1L
            it[createdByActor] = "test"
        }.value
        return AppointmentCancellationDetails.insertAndGetId {
            it[AppointmentCancellationDetails.tenantGroupId] = EntityID(tenantId, TenantGroups)
            it[AppointmentCancellationDetails.clinicId] = EntityID(clinicId, Clinics)
            it[AppointmentCancellationDetails.appointmentId] = EntityID(appointmentId, Appointments)
            it[AppointmentCancellationDetails.commitmentId] = EntityID(commitmentId, AppointmentCommitments)
            it[AppointmentCancellationDetails.proposalId] = EntityID(proposalId, AppointmentProposals)
            it[reasonCode] = "CUSTOMER_REQUEST"
            it[actorRole] = "PATIENT"
            it[actorScopeHash] = "s".repeat(64)
            it[detailHash] = "d".repeat(64)
            it[AppointmentCancellationDetails.patientScopeFingerprint] = patientScopeFingerprint
            it[AppointmentCancellationDetails.fromCommitmentStatus] = AppointmentCommitmentStatus.CONFIRMED
            it[AppointmentCancellationDetails.occurredAt] = occurredAt
        }.value
    }
}

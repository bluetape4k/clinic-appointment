package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.Test

class ProfileReevaluationAppointmentQueryTest {
    private val repository = AppointmentRepository()
    private val fingerprint = "a".repeat(64)

    @Test
    fun `재평가 대상은 tenant clinic 환자 범위의 proposed와 held만 keyset으로 조회한다`() {
        withProfileReevaluationAppointmentTables {
            TenantGroups.insert {
                it[id] = EntityID(2L, TenantGroups)
                it[tenantCode] = "tenant-other"
                it[displayName] = "Other Tenant"
            }
            insertClinic(10L, tenantGroupId = 1L)
            insertClinic(11L, tenantGroupId = 1L)
            insertClinic(12L, tenantGroupId = 2L)

            insertCandidate(99L, 10L, fingerprint, AppointmentCommitmentStatus.PROPOSED)
            insertCandidate(103L, 10L, fingerprint, AppointmentCommitmentStatus.HELD)
            insertCandidate(101L, 10L, fingerprint, AppointmentCommitmentStatus.PROPOSED)
            insertCandidate(102L, 10L, fingerprint, AppointmentCommitmentStatus.HELD)
            insertCandidate(104L, 10L, fingerprint, AppointmentCommitmentStatus.CONFIRMED)
            insertCandidate(105L, 10L, "b".repeat(64), AppointmentCommitmentStatus.PROPOSED)
            insertCandidate(106L, 11L, fingerprint, AppointmentCommitmentStatus.PROPOSED)
            insertCandidate(107L, 12L, fingerprint, AppointmentCommitmentStatus.PROPOSED)

            val firstPage =
                repository.findProfileReevaluationCandidates(
                    tenantGroupId = 1L,
                    clinicId = 10L,
                    patientReferenceFingerprint = fingerprint,
                    afterAppointmentId = 100L,
                    limit = 2,
                )

            firstPage.map { it.appointmentId } shouldBeEqualTo listOf(101L, 102L)
            firstPage.map { it.commitmentStatus } shouldBeEqualTo
                listOf(AppointmentCommitmentStatus.PROPOSED, AppointmentCommitmentStatus.HELD)

            val secondPage =
                repository.findProfileReevaluationCandidates(
                    tenantGroupId = 1L,
                    clinicId = 10L,
                    patientReferenceFingerprint = fingerprint,
                    afterAppointmentId = firstPage.last().appointmentId,
                    limit = 2,
                )

            secondPage.map { it.appointmentId } shouldBeEqualTo listOf(103L)
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertClinic(
        clinicId: Long,
        tenantGroupId: Long,
    ) {
        Clinics.insert {
            it[id] = EntityID(clinicId, Clinics)
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[name] = "Clinic $clinicId"
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.insertCandidate(
        appointmentId: Long,
        clinicId: Long,
        patientFingerprint: String,
        commitmentStatus: AppointmentCommitmentStatus,
    ) {
        Appointments.insert {
            it[id] = EntityID(appointmentId, Appointments)
            it[Appointments.clinicId] = EntityID(clinicId, Clinics)
            it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
            it[patientName] = "Patient $appointmentId"
            it[patientReferenceFingerprint] = patientFingerprint
        }
        AppointmentCommitments.insert {
            it[id] = EntityID(1_000L + appointmentId, AppointmentCommitments)
            it[AppointmentCommitments.appointmentId] = EntityID(appointmentId, Appointments)
            it[status] = commitmentStatus
            it[origin] = AppointmentOrigin.SYSTEM
            it[confirmedProposalId] =
                if (commitmentStatus == AppointmentCommitmentStatus.CONFIRMED) 2_000L + appointmentId else null
            it[effectivePolicySnapshotId] = 1L
            it[version] = 1L
        }
    }

    private fun withProfileReevaluationAppointmentTables(
        statement: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit,
    ) {
        withTables(
            TestDB.H2_COMMITMENT,
            Clinics,
            Doctors,
            TreatmentTypes,
            Equipments,
            ConsultationTopics,
            Appointments,
            AppointmentCommitments,
        ) {
            statement()
        }
    }
}

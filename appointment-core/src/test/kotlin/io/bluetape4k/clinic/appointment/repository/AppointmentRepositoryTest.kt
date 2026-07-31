package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.dto.AppointmentVisitIdentityDraft
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.support.requireNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AppointmentRepositoryTest {
    private lateinit var database: Database
    private val repository = AppointmentRepository()

    @BeforeEach
    fun setUp() {
        database =
            Database.connect(
                url =
                    "jdbc:h2:mem:appointment_repository_${System.nanoTime()};" +
                        "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver",
            )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics, Doctors, TreatmentTypes)
            SchemaUtils.createMissingTablesAndColumns(Appointments)
            Appointments.deleteAll()
            TreatmentTypes.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            seedReferences()
        }
    }

    @Test
    fun `member id를 compatibility column에 저장하고 도메인 타입으로 조회한다`() {
        val saved =
            transaction(database) {
                repository.save(appointment(memberId = MemberId("member-1")))
            }

        val found =
            transaction(database) {
                repository.findLegacyById(saved.id.requireNotNull("saved.id"))
            }

        found?.memberId shouldBeEqualTo MemberId("member-1")
        transaction(database) {
            Appointments
                .selectAll()
                .where { Appointments.id eq saved.id.requireNotNull("saved.id") }
                .single()[Appointments.patientExternalId]
        } shouldBeEqualTo "member-1"
    }

    @Test
    fun `legacy 상태 변경은 명시적 version CAS로 한 번만 증가한다`() {
        val saved = transaction(database) {
            repository.save(appointment(memberId = MemberId("member-1")))
        }
        saved.version shouldBeEqualTo 0L

        transaction(database) {
            repository.updateLegacyStatus(
                appointmentId = saved.id.requireNotNull("saved.id"),
                expectedVersion = 0L,
                newStatus = AppointmentState.CANCELLED,
            )
        }.shouldBeTrue()
        transaction(database) {
            repository.updateLegacyStatus(
                appointmentId = saved.id.requireNotNull("saved.id"),
                expectedVersion = 0L,
                newStatus = AppointmentState.COMPLETED,
            )
        }.shouldBeFalse()

        val updated = transaction(database) {
            repository.findLegacyById(saved.id.requireNotNull("saved.id"))
        }
        updated?.version shouldBeEqualTo 1L
        updated?.status shouldBeEqualTo AppointmentState.CANCELLED
    }

    @Test
    fun `legacy null compatibility column은 nullable member id로 조회한다`() {
        val saved =
            transaction(database) {
                repository.save(appointment(memberId = null))
            }

        val found =
            transaction(database) {
                repository.findLegacyById(saved.id.requireNotNull("saved.id"))
            }

        found?.memberId.shouldBeNull()
        transaction(database) {
            Appointments
                .selectAll()
                .where { Appointments.id eq saved.id.requireNotNull("saved.id") }
                .single()[Appointments.patientExternalId]
        }.shouldBeNull()
    }

    @Test
    fun `legacy blank compatibility column은 nullable member id로 격리한다`() {
        val appointmentId =
            transaction(database) {
                Appointments
                    .insertAndGetId {
                        it[clinicId] = CLINIC_ID
                        it[doctorId] = DOCTOR_ID
                        it[treatmentTypeId] = TREATMENT_TYPE_ID
                        it[patientName] = "홍길동"
                        it[patientPhone] = "010-1234-5678"
                        it[patientExternalId] = "  \t"
                        it[appointmentDate] = LocalDate.of(2026, 8, 1)
                        it[startTime] = LocalTime.of(9, 0)
                        it[endTime] = LocalTime.of(9, 30)
                        it[status] = AppointmentState.CONFIRMED
                    }.value
            }

        val found =
            transaction(database) {
                repository.findLegacyById(appointmentId)
            }

        found?.memberId.shouldBeNull()
    }

    @Test
    fun `legacy nonblank compatibility column은 원문 공백까지 보존한다`() {
        val appointmentId =
            transaction(database) {
                Appointments
                    .insertAndGetId {
                        it[clinicId] = CLINIC_ID
                        it[doctorId] = DOCTOR_ID
                        it[treatmentTypeId] = TREATMENT_TYPE_ID
                        it[patientName] = "홍길동"
                        it[patientPhone] = "010-1234-5678"
                        it[patientExternalId] = "  member-1  "
                        it[appointmentDate] = LocalDate.of(2026, 8, 1)
                        it[startTime] = LocalTime.of(9, 0)
                        it[endTime] = LocalTime.of(9, 30)
                        it[status] = AppointmentState.CONFIRMED
                    }.value
            }

        val found =
            transaction(database) {
                repository.findLegacyById(appointmentId)
            }

        found?.memberId shouldBeEqualTo MemberId("  member-1  ")
    }

    @Test
    fun `commitment 방문 회원 식별자는 tenant clinic 범위에서만 조회한다`() {
        val appointmentId =
            transaction(database) {
                repository.createCommitmentVisitIdentity(
                    clinicId = CLINIC_ID,
                    identity = AppointmentVisitIdentityDraft(
                        patientName = "홍길동",
                        patientPhone = null,
                        memberId = MemberId("member-v2"),
                        patientReferenceFingerprint = "fingerprint-v2",
                    ),
                )
            }

        transaction(database) {
            repository.findCommitmentMemberId(
                appointmentId = appointmentId,
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                clinicId = CLINIC_ID,
            )
        } shouldBeEqualTo MemberId("member-v2")
        transaction(database) {
            repository.findCommitmentMemberId(
                appointmentId = appointmentId,
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID + 1,
                clinicId = CLINIC_ID,
            )
        }.shouldBeNull()
        transaction(database) {
            repository.findCommitmentMemberId(
                appointmentId = appointmentId,
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                clinicId = CLINIC_ID + 1,
            )
        }.shouldBeNull()
    }

    private fun appointment(memberId: MemberId?) =
        AppointmentRecord(
            clinicId = CLINIC_ID,
            doctorId = DOCTOR_ID,
            treatmentTypeId = TREATMENT_TYPE_ID,
            patientName = "홍길동",
            patientPhone = "010-1234-5678",
            memberId = memberId,
            appointmentDate = LocalDate.of(2026, 8, 1),
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(9, 30),
            status = AppointmentState.CONFIRMED,
        )

    private fun seedReferences() {
        TenantGroups.insert {
            it[id] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[tenantCode] = "default"
            it[displayName] = "Default Tenant"
            it[active] = true
        }
        Clinics.insert {
            it[id] = EntityID(CLINIC_ID, Clinics)
            it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
            it[name] = "Test Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }
        Doctors.insert {
            it[id] = EntityID(DOCTOR_ID, Doctors)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[name] = "Dr. Kim"
        }
        TreatmentTypes.insert {
            it[id] = EntityID(TREATMENT_TYPE_ID, TreatmentTypes)
            it[clinicId] = EntityID(CLINIC_ID, Clinics)
            it[name] = "General Care"
            it[defaultDurationMinutes] = 30
        }
    }

    private companion object {
        private const val CLINIC_ID = 1L
        private const val DOCTOR_ID = 10L
        private const val TREATMENT_TYPE_ID = 20L
    }
}

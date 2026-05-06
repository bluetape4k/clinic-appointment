package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class AppointmentControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val BASE_URL = "/api/appointments"
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient

    private var clinicId: Long = 0
    private var doctorId: Long = 0
    private var treatmentTypeId: Long = 0

    @BeforeEach
    fun setup() {
        client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()

        transaction {
            SchemaUtils.create(
                Clinics, OperatingHoursTable, ClinicDefaultBreakTimes, BreakTimes, ClinicClosures,
                Doctors, DoctorSchedules, DoctorAbsences,
                TreatmentTypes, Equipments, TreatmentEquipments,
                ConsultationTopics, Holidays,
                Appointments, AppointmentNotes, AppointmentStateHistory,
                RescheduleCandidates, AppointmentEventLogs,
            )

            AppointmentEventLogs.deleteAll()
            AppointmentStateHistory.deleteAll()
            RescheduleCandidates.deleteAll()
            AppointmentNotes.deleteAll()
            Appointments.deleteAll()
            TreatmentEquipments.deleteAll()
            Equipments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            DoctorAbsences.deleteAll()
            DoctorSchedules.deleteAll()
            Doctors.deleteAll()
            Holidays.deleteAll()
            ClinicClosures.deleteAll()
            BreakTimes.deleteAll()
            ClinicDefaultBreakTimes.deleteAll()
            OperatingHoursTable.deleteAll()
            Clinics.deleteAll()

            clinicId = Clinics.insertAndGetId {
                it[name] = "Test Clinic"
                it[slotDurationMinutes] = 30
                it[timezone] = "Asia/Seoul"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 3
                it[openOnHolidays] = false
            }.value

            doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = this@AppointmentControllerTest.clinicId
                it[name] = "Dr. Test"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@AppointmentControllerTest.clinicId
                it[name] = "General Checkup"
                it[category] = "GENERAL"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }.value

            OperatingHoursTable.insertAndGetId {
                it[OperatingHoursTable.clinicId] = this@AppointmentControllerTest.clinicId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[openTime] = LocalTime.of(9, 0)
                it[closeTime] = LocalTime.of(18, 0)
                it[isActive] = true
            }

            DoctorSchedules.insertAndGetId {
                it[DoctorSchedules.doctorId] = this@AppointmentControllerTest.doctorId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(18, 0)
            }
        }
    }

    @Test
    fun `POST - create appointment`() {
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "patientName": "John Doe",
                "patientPhone": "010-1234-5678",
                "appointmentDate": "2026-04-06",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.CREATED
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<String>("$.data.patientName") shouldBeEqualTo "John Doe"
        response.jsonPath<String>("$.data.status") shouldBeEqualTo "REQUESTED"
        response.jsonPath<String>("$.data.timezone") shouldBeEqualTo "Asia/Seoul"
        response.jsonPath<String>("$.data.locale") shouldBeEqualTo "ko-KR"
    }

    @Test
    fun `GET - find appointment by id`() {
        val appointmentId = createTestAppointment()

        val response = client.get()
            .uri("$BASE_URL/{id}", appointmentId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<Int>("$.data.id") shouldBeEqualTo appointmentId.toInt()
        response.jsonPath<String>("$.data.patientName") shouldBeEqualTo "Jane Doe"
        response.jsonPath<String>("$.data.timezone") shouldBeEqualTo "Asia/Seoul"
        response.jsonPath<String>("$.data.locale") shouldBeEqualTo "ko-KR"
    }

    @Test
    fun `GET - return 404 for non-existent appointment`() {
        val response = client.get()
            .uri("$BASE_URL/{id}", 999999)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `PATCH - update appointment status`() {
        val appointmentId = createTestAppointment()

        val response = client.patch()
            .uri("$BASE_URL/{id}/status", appointmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"status": "CONFIRMED"}""")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<String>("$.data.status") shouldBeEqualTo "CONFIRMED"
    }

    @Test
    fun `PATCH - reject invalid status transition`() {
        val appointmentId = createTestAppointment()

        val response = client.patch()
            .uri("$BASE_URL/{id}/status", appointmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"status": "COMPLETED"}""")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.CONFLICT
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `DELETE - cancel appointment`() {
        val appointmentId = createTestAppointment()

        val response = client.delete()
            .uri("$BASE_URL/{id}", appointmentId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<String>("$.data.status") shouldBeEqualTo "CANCELLED"
    }

    @Test
    fun `GET - date range 로 예약 목록 조회`() {
        createTestAppointment()

        val response = client.get()
            .uri("$BASE_URL?clinicId={clinicId}&startDate={startDate}&endDate={endDate}",
                clinicId, "2026-04-01", "2026-04-30")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        val items = response.jsonPath<List<*>>("$.data").shouldNotBeNull()
        items.shouldNotBeEmpty()
        response.jsonPath<String>("$.data[0].patientName") shouldBeEqualTo "Jane Doe"
        response.jsonPath<String>("$.data[0].timezone") shouldBeEqualTo "Asia/Seoul"
    }

    @Test
    fun `GET - 범위 밖 날짜 조회 시 빈 목록 반환`() {
        createTestAppointment()

        val response = client.get()
            .uri("$BASE_URL?clinicId={clinicId}&startDate={startDate}&endDate={endDate}",
                clinicId, "2026-05-01", "2026-05-31")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `POST - 응답에 timezone과 locale이 클리닉 설정과 일치`() {
        val expatClinicId = transaction {
            Clinics.insertAndGetId {
                it[name] = "LA 교민 클리닉"
                it[slotDurationMinutes] = 30
                it[timezone] = "America/Los_Angeles"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 1
            }.value
        }
        val expatDoctorId = transaction {
            Doctors.insertAndGetId {
                it[Doctors.clinicId] = expatClinicId
                it[name] = "Dr. Kim"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value
        }
        val expatTreatmentId = transaction {
            TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = expatClinicId
                it[name] = "General Checkup"
                it[category] = "GENERAL"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }.value
        }

        val body = """
            {
                "clinicId": $expatClinicId,
                "doctorId": $expatDoctorId,
                "treatmentTypeId": $expatTreatmentId,
                "patientName": "김철수",
                "appointmentDate": "2026-04-06",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.CREATED
        response.jsonPath<String>("$.data.timezone") shouldBeEqualTo "America/Los_Angeles"
        response.jsonPath<String>("$.data.locale") shouldBeEqualTo "ko-KR"
    }

    private fun createTestAppointment(): Long =
        transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = this@AppointmentControllerTest.clinicId
                it[Appointments.doctorId] = this@AppointmentControllerTest.doctorId
                it[Appointments.treatmentTypeId] = this@AppointmentControllerTest.treatmentTypeId
                it[patientName] = "Jane Doe"
                it[patientPhone] = "010-9876-5432"
                it[appointmentDate] = LocalDate.of(2026, 4, 6)
                it[startTime] = LocalTime.of(11, 0)
                it[endTime] = LocalTime.of(11, 30)
                it[Appointments.status] = AppointmentState.REQUESTED
            }.value
        }
}

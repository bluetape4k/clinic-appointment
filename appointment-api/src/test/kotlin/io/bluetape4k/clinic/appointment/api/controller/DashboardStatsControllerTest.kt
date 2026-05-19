package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalTime

class DashboardStatsControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val BASE_URL = "/api/admin/stats"
        private val TEST_DATE: LocalDate = LocalDate.of(2026, 5, 1)
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient

    private var clinicId: Long = 0L
    private var doctorId: Long = 0L
    private var treatmentTypeId: Long = 0L

    @BeforeEach
    fun setup() {
        client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()

        transaction {
            SchemaUtils.create(Clinics, Doctors, Equipments, TreatmentTypes, ConsultationTopics, Appointments)
            Appointments.deleteAll()
            ConsultationTopics.deleteAll()
            TreatmentTypes.deleteAll()
            Equipments.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()

            clinicId = Clinics.insertAndGetId {
                it[name] = "Test Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 3
            }.value

            doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = this@DashboardStatsControllerTest.clinicId
                it[name] = "Dr. Kim"
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@DashboardStatsControllerTest.clinicId
                it[name] = "General"
                it[defaultDurationMinutes] = 30
            }.value
        }
    }

    private fun insertAppointment(date: LocalDate, status: AppointmentState) {
        val cId = clinicId
        val dId = doctorId
        val ttId = treatmentTypeId
        transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = cId
                it[Appointments.doctorId] = dId
                it[Appointments.treatmentTypeId] = ttId
                it[patientName] = "Patient"
                it[appointmentDate] = date
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[Appointments.status] = status
            }
        }
    }

    // =================== GET /api/admin/stats/appointments ===================

    @Test
    fun `GET appointments stats - 200 with appointment data`() {
        insertAppointment(TEST_DATE, AppointmentState.CONFIRMED)
        insertAppointment(TEST_DATE, AppointmentState.CANCELLED)

        val response = client.get()
            .uri("$BASE_URL/appointments?clinicId={cId}&from={date}&to={date}", clinicId, TEST_DATE, TEST_DATE)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<Int>("$.data.totals.CONFIRMED") shouldBeEqualTo 1
        response.jsonPath<Int>("$.data.totals.CANCELLED") shouldBeEqualTo 1
    }

    @Test
    fun `GET appointments stats - 200 empty for unknown clinic`() {
        val response = client.get()
            .uri("$BASE_URL/appointments?clinicId=999999&from={date}&to={date}", TEST_DATE, TEST_DATE)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
    }

    @Test
    fun `GET appointments stats - 400 when from after to`() {
        val response = client.get()
            .uri("$BASE_URL/appointments?clinicId={cId}&from=2026-05-10&to=2026-05-01", clinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `GET appointments stats - 400 when clinicId is zero`() {
        val response = client.get()
            .uri("$BASE_URL/appointments?clinicId=0&from={date}&to={date}", TEST_DATE, TEST_DATE)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    // =================== GET /api/admin/stats/doctors ===================

    @Test
    fun `GET doctor stats - 200 with doctor data`() {
        insertAppointment(TEST_DATE, AppointmentState.COMPLETED)

        val response = client.get()
            .uri("$BASE_URL/doctors?clinicId={cId}&from={date}&to={date}", clinicId, TEST_DATE, TEST_DATE)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        val doctors = response.jsonPath<List<*>>("$.data.doctors")
        doctors.size shouldBeEqualTo 1
    }

    @Test
    fun `GET doctor stats - 400 when limit is zero`() {
        val response = client.get()
            .uri("$BASE_URL/doctors?clinicId={cId}&from={date}&to={date}&limit=0", clinicId, TEST_DATE, TEST_DATE)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    // =================== GET /api/admin/stats/cancellations ===================

    @Test
    fun `GET cancellation stats - 200 with cancellation data`() {
        insertAppointment(TEST_DATE, AppointmentState.CANCELLED)
        insertAppointment(TEST_DATE, AppointmentState.COMPLETED)

        val response = client.get()
            .uri("$BASE_URL/cancellations?clinicId={cId}&from={date}&to={date}", clinicId, TEST_DATE, TEST_DATE)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<Int>("$.data.totalCancelled") shouldBeEqualTo 1
        response.jsonPath<Int>("$.data.totalCompleted") shouldBeEqualTo 1
    }

    @Test
    fun `GET cancellation stats - 400 when from after to`() {
        val response = client.get()
            .uri("$BASE_URL/cancellations?clinicId={cId}&from=2026-05-10&to=2026-05-01", clinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }
}

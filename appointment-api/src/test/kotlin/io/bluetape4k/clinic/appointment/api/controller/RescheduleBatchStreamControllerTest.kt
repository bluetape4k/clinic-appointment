package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
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
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.logging.KLogging
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class RescheduleBatchStreamControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val STREAM_URL = "/api/tenant-default/reschedule/batch/stream"
        // 2026-04-06 is Monday — in operating hours window
        private val CLOSURE_DATE: LocalDate = LocalDate.of(2026, 4, 6)
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
                it[name] = "Stream Test Clinic"
                it[slotDurationMinutes] = 30
                it[timezone] = "Asia/Seoul"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 3
                it[openOnHolidays] = false
            }.value

            doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = this@RescheduleBatchStreamControllerTest.clinicId
                it[name] = "Dr. Stream"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@RescheduleBatchStreamControllerTest.clinicId
                it[name] = "General Checkup"
                it[category] = "GENERAL"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }.value

            for (day in listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            )) {
                OperatingHoursTable.insertAndGetId {
                    it[OperatingHoursTable.clinicId] = this@RescheduleBatchStreamControllerTest.clinicId
                    it[dayOfWeek] = day
                    it[openTime] = LocalTime.of(9, 0)
                    it[closeTime] = LocalTime.of(18, 0)
                    it[isActive] = true
                }
                DoctorSchedules.insertAndGetId {
                    it[DoctorSchedules.doctorId] = this@RescheduleBatchStreamControllerTest.doctorId
                    it[dayOfWeek] = day
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(18, 0)
                }
            }
        }
    }

    @Test
    fun `searchDays below minimum returns 400`() {
        val response = client.get()
            .uri("$STREAM_URL?clinicId=$clinicId&closureDate=$CLOSURE_DATE&searchDays=0")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `searchDays above maximum returns 400`() {
        val response = client.get()
            .uri("$STREAM_URL?clinicId=$clinicId&closureDate=$CLOSURE_DATE&searchDays=31")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `no affected appointments emits only terminal done event`() {
        // No appointment inserted on CLOSURE_DATE
        val response = client.get()
            .uri("$STREAM_URL?clinicId=$clinicId&closureDate=$CLOSURE_DATE&searchDays=3")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.body shouldContain "\"done\":true"
        response.body shouldContain "\"totalProcessed\":0"
    }

    @Test
    fun `affected appointment emits progress event then terminal event`() {
        transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = this@RescheduleBatchStreamControllerTest.clinicId
                it[Appointments.doctorId] = this@RescheduleBatchStreamControllerTest.doctorId
                it[Appointments.treatmentTypeId] = this@RescheduleBatchStreamControllerTest.treatmentTypeId
                it[patientName] = "Patient SSE"
                it[patientPhone] = "010-5555-6666"
                it[appointmentDate] = CLOSURE_DATE
                it[startTime] = LocalTime.of(10, 0)
                it[endTime] = LocalTime.of(10, 30)
                it[Appointments.status] = AppointmentState.CONFIRMED
            }
        }

        val response = client.get()
            .uri("$STREAM_URL?clinicId=$clinicId&closureDate=$CLOSURE_DATE&searchDays=3")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        // progress event emitted for the appointment
        response.body shouldContain "event:progress"
        // terminal complete event
        response.body shouldContain "event:complete"
        response.body shouldContain "\"done\":true"
        response.body shouldContain "\"totalProcessed\":1"
        response.body shouldContain "\"done\":false"
    }
}

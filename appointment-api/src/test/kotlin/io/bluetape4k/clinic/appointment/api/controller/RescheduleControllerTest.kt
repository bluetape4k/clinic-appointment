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
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldBePositive
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
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

class RescheduleControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val BASE_URL = "/api/appointments"
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient

    private var clinicId: Long = 0
    private var doctorId: Long = 0
    private var treatmentTypeId: Long = 0
    private var appointmentId: Long = 0

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
                it[Doctors.clinicId] = this@RescheduleControllerTest.clinicId
                it[name] = "Dr. Reschedule"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@RescheduleControllerTest.clinicId
                it[name] = "General Checkup"
                it[category] = "GENERAL"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }.value

            // 월~금 운영시간
            for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
                OperatingHoursTable.insertAndGetId {
                    it[OperatingHoursTable.clinicId] = this@RescheduleControllerTest.clinicId
                    it[dayOfWeek] = day
                    it[openTime] = LocalTime.of(9, 0)
                    it[closeTime] = LocalTime.of(18, 0)
                    it[isActive] = true
                }
                DoctorSchedules.insertAndGetId {
                    it[DoctorSchedules.doctorId] = this@RescheduleControllerTest.doctorId
                    it[dayOfWeek] = day
                    it[startTime] = LocalTime.of(9, 0)
                    it[endTime] = LocalTime.of(18, 0)
                }
            }

            appointmentId = Appointments.insertAndGetId {
                it[Appointments.clinicId] = this@RescheduleControllerTest.clinicId
                it[Appointments.doctorId] = this@RescheduleControllerTest.doctorId
                it[Appointments.treatmentTypeId] = this@RescheduleControllerTest.treatmentTypeId
                it[patientName] = "Patient A"
                it[patientPhone] = "010-1111-2222"
                it[appointmentDate] = LocalDate.of(2026, 4, 6)
                it[startTime] = LocalTime.of(10, 0)
                it[endTime] = LocalTime.of(10, 30)
                it[Appointments.status] = AppointmentState.CONFIRMED
            }.value
        }
    }

    @Test
    fun `POST closure - 휴진 일괄 재배정 후보 생성`() {
        val response = client.post()
            .uri("$BASE_URL/{id}/reschedule/closure?clinicId={clinicId}&closureDate={date}",
                appointmentId, clinicId, "2026-04-06")
            .contentType(MediaType.APPLICATION_JSON)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
    }

    @Test
    fun `GET candidates - 재배정 후보 조회 (후보 없을 때 빈 목록)`() {
        val response = client.get()
            .uri("$BASE_URL/{id}/reschedule/candidates", appointmentId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `GET candidates - 재배정 후보 조회 (후보 존재 시)`() {
        transaction {
            RescheduleCandidates.insertAndGetId {
                it[originalAppointmentId] = this@RescheduleControllerTest.appointmentId
                it[candidateDate] = LocalDate.of(2026, 4, 7)
                it[startTime] = LocalTime.of(10, 0)
                it[endTime] = LocalTime.of(10, 30)
                it[RescheduleCandidates.doctorId] = this@RescheduleControllerTest.doctorId
                it[priority] = 1
                it[selected] = false
            }
        }

        val response = client.get()
            .uri("$BASE_URL/{id}/reschedule/candidates", appointmentId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull() shouldHaveSize 1
        response.jsonPath<String>("$.data[0].candidateDate") shouldBeEqualTo "2026-04-07"
    }

    @Test
    fun `POST confirm - 재배정 확정`() {
        val candidateId = transaction {
            RescheduleCandidates.insertAndGetId {
                it[originalAppointmentId] = this@RescheduleControllerTest.appointmentId
                it[candidateDate] = LocalDate.of(2026, 4, 7)
                it[startTime] = LocalTime.of(10, 0)
                it[endTime] = LocalTime.of(10, 30)
                it[RescheduleCandidates.doctorId] = this@RescheduleControllerTest.doctorId
                it[priority] = 1
                it[selected] = false
            }.value
        }

        val response = client.post()
            .uri("$BASE_URL/{id}/reschedule/confirm/{candidateId}", appointmentId, candidateId)
            .contentType(MediaType.APPLICATION_JSON)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<Int>("$.data").shouldNotBeNull().shouldBePositive()
    }

    @Test
    fun `POST auto - 자동 재배정 (후보 없을 때 null)`() {
        val response = client.post()
            .uri("$BASE_URL/{id}/reschedule/auto", appointmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<Any?>("$.data").shouldBeNull()
    }

    @Test
    fun `POST auto - 자동 재배정 (후보 존재 시 최우선 후보 선택)`() {
        transaction {
            RescheduleCandidates.insertAndGetId {
                it[originalAppointmentId] = this@RescheduleControllerTest.appointmentId
                it[candidateDate] = LocalDate.of(2026, 4, 7)
                it[startTime] = LocalTime.of(10, 0)
                it[endTime] = LocalTime.of(10, 30)
                it[RescheduleCandidates.doctorId] = this@RescheduleControllerTest.doctorId
                it[priority] = 1
                it[selected] = false
            }
        }

        val response = client.post()
            .uri("$BASE_URL/{id}/reschedule/auto", appointmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<Int>("$.data").shouldNotBeNull().shouldBePositive()
    }
}

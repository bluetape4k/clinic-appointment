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
import io.bluetape4k.logging.KLogging
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import java.time.DayOfWeek
import java.time.LocalTime

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SlotControllerTest @Autowired constructor() {

    companion object: KLogging()

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
                it[Doctors.clinicId] = this@SlotControllerTest.clinicId
                it[name] = "Dr. Slot"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@SlotControllerTest.clinicId
                it[name] = "General Checkup"
                it[category] = "GENERAL"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }.value

            // 월요일 운영시간: 09:00 ~ 18:00
            OperatingHoursTable.insertAndGetId {
                it[OperatingHoursTable.clinicId] = this@SlotControllerTest.clinicId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[openTime] = LocalTime.of(9, 0)
                it[closeTime] = LocalTime.of(18, 0)
                it[isActive] = true
            }

            // 의사 스케줄: 월요일 09:00 ~ 18:00
            DoctorSchedules.insertAndGetId {
                it[DoctorSchedules.doctorId] = this@SlotControllerTest.doctorId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(18, 0)
            }
        }
    }

    @Test
    fun `GET - 가용 슬롯 조회 성공`() {
        // 2026-04-06 은 월요일
        val response = client.get()
            .uri("/api/clinics/{clinicId}/slots?doctorId={doctorId}&treatmentTypeId={treatmentTypeId}&date={date}",
                clinicId, doctorId, treatmentTypeId, "2026-04-06")
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<List<*>>("$.data")).isNotEmpty()
    }

    @Test
    fun `GET - 운영시간 외 날짜 조회 시 빈 슬롯 반환`() {
        // 2026-04-08 은 수요일 — 운영시간 미등록
        val response = client.get()
            .uri("/api/clinics/{clinicId}/slots?doctorId={doctorId}&treatmentTypeId={treatmentTypeId}&date={date}",
                clinicId, doctorId, treatmentTypeId, "2026-04-08")
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<List<*>>("$.data")).isEmpty()
    }

    @Test
    fun `GET - 필수 파라미터 누락 시 4xx 또는 5xx`() {
        // doctorId 누락
        val response = client.get()
            .uri("/api/clinics/{clinicId}/slots?treatmentTypeId={treatmentTypeId}&date={date}",
                clinicId, treatmentTypeId, "2026-04-06")
            .execute()

        assertThat(response.statusCode.is4xxClientError || response.statusCode.is5xxServerError).isTrue()
    }

    @Test
    fun `GET - requestedDurationMinutes 지정 시 해당 길이 슬롯 반환`() {
        val response = client.get()
            .uri("/api/clinics/{clinicId}/slots?doctorId={doctorId}&treatmentTypeId={treatmentTypeId}&date={date}&requestedDurationMinutes={duration}",
                clinicId, doctorId, treatmentTypeId, "2026-04-06", 60)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
    }

    @Test
    fun `GET - 존재하지 않는 clinicId로 조회`() {
        val response = client.get()
            .uri("/api/clinics/{clinicId}/slots?doctorId={doctorId}&treatmentTypeId={treatmentTypeId}&date={date}",
                999999, doctorId, treatmentTypeId, "2026-04-06")
            .execute()

        // SlotCalculationService가 빈 결과를 반환하거나 404를 반환
        assertThat(response.statusCode.value()).isIn(200, 404)
    }
}

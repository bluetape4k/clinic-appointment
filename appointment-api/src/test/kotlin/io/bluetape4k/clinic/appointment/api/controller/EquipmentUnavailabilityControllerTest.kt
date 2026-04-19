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
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilities
import io.bluetape4k.clinic.appointment.model.tables.EquipmentUnavailabilityExceptions
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EquipmentUnavailabilityControllerTest @Autowired constructor() {

    companion object: KLogging()

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient

    private var clinicId: Long = 0
    private var equipmentId: Long = 0

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
                EquipmentUnavailabilities, EquipmentUnavailabilityExceptions,
            )

            EquipmentUnavailabilityExceptions.deleteAll()
            EquipmentUnavailabilities.deleteAll()
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

            equipmentId = Equipments.insertAndGetId {
                it[Equipments.clinicId] = this@EquipmentUnavailabilityControllerTest.clinicId
                it[name] = "X-Ray Machine"
                it[usageDurationMinutes] = 15
                it[quantity] = 1
            }.value
        }
    }

    @Test
    fun `POST - 장비 사용불가 스케줄 등록 (일회성)`() {
        val body = """
            {
                "unavailableDate": "2026-04-10",
                "isRecurring": false,
                "effectiveFrom": "2026-04-10",
                "startTime": "09:00",
                "endTime": "12:00",
                "reason": "정기 점검"
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities", clinicId, equipmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<String>("$.data.unavailableDate")).isEqualTo("2026-04-10")
        assertThat(response.jsonPath<Boolean>("$.data.isRecurring")).isFalse()
        assertThat(response.jsonPath<String>("$.data.reason")).isEqualTo("정기 점검")
    }

    @Test
    fun `POST - 장비 사용불가 스케줄 등록 (반복)`() {
        val body = """
            {
                "isRecurring": true,
                "recurringDayOfWeek": "WEDNESDAY",
                "effectiveFrom": "2026-04-01",
                "effectiveUntil": "2026-06-30",
                "startTime": "14:00",
                "endTime": "16:00",
                "reason": "주간 유지보수"
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities", clinicId, equipmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<Boolean>("$.data.isRecurring")).isTrue()
        assertThat(response.jsonPath<String>("$.data.recurringDayOfWeek")).isEqualTo("WEDNESDAY")
    }

    @Test
    fun `GET - 장비 사용불가 목록 조회`() {
        createTestUnavailability()

        val response = client.get()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities?from={from}&to={to}",
                clinicId, equipmentId, "2026-04-01", "2026-04-30")
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<List<*>>("$.data")).isNotEmpty()
    }

    @Test
    fun `GET - 범위 밖 날짜 조회 시 빈 목록`() {
        createTestUnavailability()

        // effectiveFrom=2026-04-10 이전 범위를 조회하면 결과가 없어야 함
        val response = client.get()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities?from={from}&to={to}",
                clinicId, equipmentId, "2026-03-01", "2026-03-31")
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<List<*>>("$.data")).isEmpty()
    }

    @Test
    fun `PUT - 장비 사용불가 스케줄 수정`() {
        val unavailabilityId = createTestUnavailability()

        val body = """
            {
                "unavailableDate": "2026-04-15",
                "isRecurring": false,
                "effectiveFrom": "2026-04-15",
                "startTime": "10:00",
                "endTime": "14:00",
                "reason": "수정된 점검"
            }
        """.trimIndent()

        val response = client.put()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}",
                clinicId, equipmentId, unavailabilityId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<String>("$.data.unavailableDate")).isEqualTo("2026-04-15")
        assertThat(response.jsonPath<String>("$.data.reason")).isEqualTo("수정된 점검")
    }

    @Test
    fun `PUT - 존재하지 않는 ID 수정 시 에러`() {
        val body = """
            {
                "unavailableDate": "2026-04-15",
                "isRecurring": false,
                "effectiveFrom": "2026-04-15",
                "startTime": "10:00",
                "endTime": "14:00"
            }
        """.trimIndent()

        val response = client.put()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}",
                clinicId, equipmentId, 999999)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode.value()).isIn(404, 500)
    }

    @Test
    fun `DELETE - 장비 사용불가 스케줄 삭제`() {
        val unavailabilityId = createTestUnavailability()

        val response = client.delete()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}",
                clinicId, equipmentId, unavailabilityId)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `POST exceptions - 예외 추가 (SKIP)`() {
        val unavailabilityId = createRecurringUnavailability()

        val body = """
            {
                "originalDate": "2026-04-09",
                "exceptionType": "SKIP",
                "reason": "특별 운영일"
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/exceptions",
                clinicId, equipmentId, unavailabilityId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<String>("$.data.exceptionType")).isEqualTo("SKIP")
        assertThat(response.jsonPath<String>("$.data.originalDate")).isEqualTo("2026-04-09")
    }

    @Test
    fun `POST exceptions - 예외 추가 (RESCHEDULE)`() {
        val unavailabilityId = createRecurringUnavailability()

        val body = """
            {
                "originalDate": "2026-04-09",
                "exceptionType": "RESCHEDULE",
                "rescheduledDate": "2026-04-10",
                "rescheduledStartTime": "14:00",
                "rescheduledEndTime": "16:00",
                "reason": "일정 변경"
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/exceptions",
                clinicId, equipmentId, unavailabilityId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<String>("$.data.exceptionType")).isEqualTo("RESCHEDULE")
        assertThat(response.jsonPath<String>("$.data.rescheduledDate")).isEqualTo("2026-04-10")
    }

    @Test
    fun `DELETE exceptions - 예외 삭제`() {
        val unavailabilityId = createRecurringUnavailability()
        val exceptionId = createTestException(unavailabilityId)

        val response = client.delete()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/exceptions/{exId}",
                clinicId, equipmentId, unavailabilityId, exceptionId)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `GET conflicts - 충돌 예약 조회 (충돌 없을 때)`() {
        val unavailabilityId = createTestUnavailability()

        val response = client.get()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/{id}/conflicts",
                clinicId, equipmentId, unavailabilityId)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<Int>("$.data.conflictCount")).isZero()
        assertThat(response.jsonPath<List<*>>("$.data.conflicts")).isEmpty()
    }

    @Test
    fun `POST preview-conflicts - 충돌 미리보기`() {
        val body = """
            {
                "unavailableDate": "2026-04-10",
                "isRecurring": false,
                "effectiveFrom": "2026-04-10",
                "startTime": "09:00",
                "endTime": "18:00"
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/clinics/{clinicId}/equipments/{equipmentId}/unavailabilities/preview-conflicts",
                clinicId, equipmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.jsonPath<Boolean>("$.success")).isTrue()
        assertThat(response.jsonPath<Int>("$.data.conflictCount")).isNotNull()
    }

    private fun createTestUnavailability(): Long =
        transaction {
            EquipmentUnavailabilities.insertAndGetId {
                it[EquipmentUnavailabilities.equipmentId] = this@EquipmentUnavailabilityControllerTest.equipmentId
                it[EquipmentUnavailabilities.clinicId] = this@EquipmentUnavailabilityControllerTest.clinicId
                it[unavailableDate] = LocalDate.of(2026, 4, 10)
                it[isRecurring] = false
                it[effectiveFrom] = LocalDate.of(2026, 4, 10)
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(12, 0)
                it[reason] = "정기 점검"
            }.value
        }

    private fun createRecurringUnavailability(): Long =
        transaction {
            EquipmentUnavailabilities.insertAndGetId {
                it[EquipmentUnavailabilities.equipmentId] = this@EquipmentUnavailabilityControllerTest.equipmentId
                it[EquipmentUnavailabilities.clinicId] = this@EquipmentUnavailabilityControllerTest.clinicId
                it[isRecurring] = true
                it[recurringDayOfWeek] = DayOfWeek.WEDNESDAY
                it[effectiveFrom] = LocalDate.of(2026, 4, 1)
                it[effectiveUntil] = LocalDate.of(2026, 6, 30)
                it[startTime] = LocalTime.of(14, 0)
                it[endTime] = LocalTime.of(16, 0)
                it[reason] = "주간 유지보수"
            }.value
        }

    private fun createTestException(unavailabilityId: Long): Long =
        transaction {
            EquipmentUnavailabilityExceptions.insertAndGetId {
                it[EquipmentUnavailabilityExceptions.unavailabilityId] = unavailabilityId
                it[originalDate] = LocalDate.of(2026, 4, 9)
                it[exceptionType] = io.bluetape4k.clinic.appointment.model.tables.ExceptionType.SKIP
                it[reason] = "특별 운영일"
            }.value
        }
}

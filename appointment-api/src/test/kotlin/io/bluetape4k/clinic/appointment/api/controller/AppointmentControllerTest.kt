package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.event.AppointmentEventLogs
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
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
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.api.notification.AppointmentMemberDirectory
import io.bluetape4k.clinic.appointment.api.notification.MemberDirectoryResult
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(AppointmentControllerTest.MemberDirectoryTestConfig::class)
class AppointmentControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val BASE_URL = "/api/tenant-default/appointments"
        private val futureDate: LocalDate = LocalDate.now().plusMonths(6)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class MemberDirectoryTestConfig {
        @Bean
        @Primary
        internal fun appointmentMemberDirectory(): AppointmentMemberDirectory =
            object : AppointmentMemberDirectory {
                override fun resolveMember(
                    request: io.bluetape4k.clinic.appointment.api.notification.MemberDirectoryRequest,
                ): MemberDirectoryResult =
                    when (request.memberId.value) {
                        "missing-member" -> MemberDirectoryResult.NotFound
                        "other-scope-member" -> MemberDirectoryResult.ScopeMismatch
                        "ambiguous-member" -> MemberDirectoryResult.Ambiguous
                        "unavailable-member" -> MemberDirectoryResult.Unavailable
                        else -> MemberDirectoryResult.Resolved(MemberId(request.memberId.value))
                    }

                override fun resolvePlan(
                    request: io.bluetape4k.clinic.appointment.api.notification.MemberPlanDirectoryRequest,
                ): MemberDirectoryResult =
                    MemberDirectoryResult.Resolved(MemberId("member-v2"))
            }
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
                Appointments, AppointmentIdempotencies, AppointmentNotes, AppointmentStateHistory,
                RescheduleCandidates, AppointmentEventLogs,
            )

            AppointmentEventLogs.deleteAll()
            AppointmentStateHistory.deleteAll()
            RescheduleCandidates.deleteAll()
            AppointmentNotes.deleteAll()
            AppointmentIdempotencies.deleteAll()
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
                "memberId": "member-1",
                "patientName": "John Doe",
                "patientPhone": "010-1234-5678",
                "appointmentDate": "$futureDate",
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
        transaction {
            Appointments.selectAll().single()[Appointments.patientExternalId] shouldBeEqualTo "member-1"
        }
    }

    @Test
    fun `POST - rejects a legacy appointment without memberId`() {
        val response = postAppointment(memberId = null)

        response.statusCode shouldBeEqualTo HttpStatus.UNPROCESSABLE_CONTENT
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "MEMBER_ID_REQUIRED"
        response.body.contains("privacy-name").shouldBeFalse()
        response.body.contains("010-9999-9999").shouldBeFalse()
    }

    @Test
    fun `POST - maps member directory failures without exposing protected values`() {
        val cases = listOf(
            Triple("missing-member", HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"),
            Triple("other-scope-member", HttpStatus.FORBIDDEN, "MEMBER_SCOPE_MISMATCH"),
            Triple("ambiguous-member", HttpStatus.CONFLICT, "MEMBER_REFERENCE_AMBIGUOUS"),
            Triple("unavailable-member", HttpStatus.SERVICE_UNAVAILABLE, "MEMBER_DIRECTORY_UNAVAILABLE"),
        )

        cases.forEach { (memberId, status, errorCode) ->
            val response = postAppointment(memberId)

            response.statusCode shouldBeEqualTo status
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo errorCode
            response.body.contains(memberId).shouldBeFalse()
            response.body.contains("privacy-name").shouldBeFalse()
            response.body.contains("010-9999-9999").shouldBeFalse()
            if (status == HttpStatus.SERVICE_UNAVAILABLE) {
                response.headers.getFirst(HttpHeaders.RETRY_AFTER) shouldBeEqualTo "5"
            }
        }
    }

    private fun postAppointment(memberId: String?): TestResponse {
        val memberField = memberId?.let { "\"memberId\": \"$it\"," }.orEmpty()
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                $memberField
                "patientName": "privacy-name",
                "patientPhone": "010-9999-9999",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()
        return client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()
    }

    @Test
    fun `legacy status endpoint rejects commitment v2 appointment`() {
        val appointmentId = transaction {
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = this@AppointmentControllerTest.clinicId
                it[Appointments.doctorId] = this@AppointmentControllerTest.doctorId
                it[Appointments.treatmentTypeId] = this@AppointmentControllerTest.treatmentTypeId
                it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
                it[patientName] = "Commitment patient"
                it[appointmentDate] = futureDate
                it[startTime] = LocalTime.of(11, 0)
                it[endTime] = LocalTime.of(11, 30)
                it[status] = AppointmentState.CONFIRMED
            }.value
        }

        val response = client.patch()
            .uri("$BASE_URL/$appointmentId/status")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"status":"COMPLETED","reason":"completed"}""")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.CONFLICT
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "NEW_APPOINTMENT_API_REQUIRED"
        transaction {
            Appointments.selectAll()
                .where { Appointments.id eq appointmentId }
                .single()[Appointments.status] shouldBeEqualTo AppointmentState.CONFIRMED
        }
    }

    @Test
    fun `POST - replays same appointment for the same idempotency key`() {
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "patientPhone": "010-1234-5678",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val first = client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "retry-key-001")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()
        val second = client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "retry-key-001")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        first.statusCode shouldBeEqualTo HttpStatus.CREATED
        second.statusCode shouldBeEqualTo HttpStatus.OK
        second.jsonPath<Int>("$.data.id") shouldBeEqualTo first.jsonPath<Int>("$.data.id")
        transaction {
            Appointments.selectAll().count() shouldBeEqualTo 1L
            AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `POST - rejects idempotency key reused for a different member`() {
        val firstBody = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()
        val changedBody = firstBody.replace("member-1", "member-2")

        client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "retry-key-002")
            .contentType(MediaType.APPLICATION_JSON)
            .body(firstBody)
            .execute()
            .statusCode shouldBeEqualTo HttpStatus.CREATED

        val response = client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "retry-key-002")
            .contentType(MediaType.APPLICATION_JSON)
            .body(changedBody)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.CONFLICT
        transaction { Appointments.selectAll().count() shouldBeEqualTo 1L }
    }

    @Test
    fun `POST - rejects blank idempotency key`() {
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "   ")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `POST - rejects idempotency key longer than 255 characters`() {
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "x".repeat(256))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `POST - creates a new appointment when an idempotency key has expired`() {
        val oldAppointmentId = createTestAppointment()
        transaction {
            AppointmentIdempotencies.insertAndGetId {
                it[tenantGroupId] = TenantGroups.DEFAULT_TENANT_GROUP_ID
                it[clinicId] = this@AppointmentControllerTest.clinicId
                it[idempotencyKey] = "expired-key-001"
                it[requestFingerprint] = "f".repeat(64)
                it[appointmentId] = oldAppointmentId
                it[expiresAt] = Instant.now().minusSeconds(1)
            }
        }
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .header("Idempotency-Key", "expired-key-001")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.CREATED
        transaction {
            Appointments.selectAll().count() shouldBeEqualTo 2L
            AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `POST - concurrent requests converge on one appointment for the same idempotency key`() {
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val responses = (1..2).map {
                executor.submit<Int> {
                    start.await()
                    client.post()
                        .uri(BASE_URL)
                        .header("Idempotency-Key", "concurrent-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .execute()
                        .statusCode.value()
                }
            }
            start.countDown()
            val statuses = responses.map { it.get(15, TimeUnit.SECONDS) }

            statuses.count { it == HttpStatus.CREATED.value() } shouldBeEqualTo 1
            statuses.count { it == HttpStatus.OK.value() } shouldBeEqualTo 1
            transaction {
                Appointments.selectAll().count() shouldBeEqualTo 1L
                AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 1L
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `POST - return 400 when patientName is blank`() {
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `POST - return 400 when clinicId is zero`() {
        val body = """
            {
                "clinicId": 0,
                "doctorId": $doctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `POST - return 400 when request body is malformed`() {
        val body = """{ "clinicId": "not-a-number" }"""

        val response = client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
    }

    @Test
    fun `POST - return 404 when doctor belongs to another clinic`() {
        val otherDoctorId = transaction {
            val otherClinicId = Clinics.insertAndGetId {
                it[name] = "Other Clinic"
                it[slotDurationMinutes] = 30
                it[timezone] = "Asia/Seoul"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 1
            }.value

            Doctors.insertAndGetId {
                it[Doctors.clinicId] = otherClinicId
                it[name] = "Dr. Other"
                it[specialty] = "General"
                it[providerType] = "DOCTOR"
                it[maxConcurrentPatients] = 1
            }.value
        }
        val body = """
            {
                "clinicId": $clinicId,
                "doctorId": $otherDoctorId,
                "treatmentTypeId": $treatmentTypeId,
                "memberId": "member-1",
                "patientName": "John Doe",
                "appointmentDate": "$futureDate",
                "startTime": "10:00",
                "endTime": "10:30"
            }
        """.trimIndent()

        val response = client.post()
            .uri(BASE_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
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
    fun `GET - state history after status change`() {
        val appointmentId = createTestAppointment()

        client.patch()
            .uri("$BASE_URL/{id}/status", appointmentId)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"status": "CONFIRMED"}""")
            .execute()

        val response = client.get()
            .uri("$BASE_URL/{id}/history", appointmentId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        val history = response.jsonPath<List<*>>("$.data").shouldNotBeNull()
        history.shouldNotBeEmpty()
        response.jsonPath<String>("$.data[0].fromState") shouldBeEqualTo "REQUESTED"
        response.jsonPath<String>("$.data[0].toState") shouldBeEqualTo "CONFIRMED"
    }

    @Test
    fun `GET - history returns empty list for new appointment`() {
        val appointmentId = createTestAppointment()

        val response = client.get()
            .uri("$BASE_URL/{id}/history", appointmentId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull().shouldBeEmpty()
    }

    @Test
    fun `GET - history returns 404 for non-existent appointment`() {
        val response = client.get()
            .uri("$BASE_URL/{id}/history", 999999)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
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
                clinicId, futureDate.withDayOfMonth(1), futureDate.withDayOfMonth(futureDate.lengthOfMonth()))
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
                "memberId": "member-1",
                "patientName": "김철수",
                "appointmentDate": "$futureDate",
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
                it[appointmentDate] = futureDate
                it[startTime] = LocalTime.of(11, 0)
                it[endTime] = LocalTime.of(11, 30)
                it[Appointments.status] = AppointmentState.REQUESTED
            }.value
        }
}

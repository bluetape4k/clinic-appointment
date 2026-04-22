package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldHaveSize
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
import org.springframework.web.client.RestClient
import java.time.DayOfWeek
import java.time.LocalTime

class ClinicControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val BASE_URL = "/api/clinics"
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient

    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()

        transaction {
            SchemaUtils.create(
                Clinics, OperatingHoursTable, ClinicDefaultBreakTimes, BreakTimes,
            )

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

            OperatingHoursTable.insertAndGetId {
                it[OperatingHoursTable.clinicId] = this@ClinicControllerTest.clinicId
                it[dayOfWeek] = DayOfWeek.MONDAY
                it[openTime] = LocalTime.of(9, 0)
                it[closeTime] = LocalTime.of(18, 0)
                it[isActive] = true
            }

            ClinicDefaultBreakTimes.insertAndGetId {
                it[ClinicDefaultBreakTimes.clinicId] = this@ClinicControllerTest.clinicId
                it[name] = "Lunch Break"
                it[startTime] = LocalTime.of(12, 0)
                it[endTime] = LocalTime.of(13, 0)
            }
        }
    }

    @Test
    fun `GET - all clinics`() {
        val response = client.get()
            .uri(BASE_URL)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull().shouldNotBeEmpty()
    }

    @Test
    fun `GET - clinic by id`() {
        val response = client.get()
            .uri("$BASE_URL/{id}", clinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<String>("$.data.name") shouldBeEqualTo "Test Clinic"
        response.jsonPath<String>("$.data.timezone") shouldBeEqualTo "Asia/Seoul"
        response.jsonPath<String>("$.data.locale") shouldBeEqualTo "ko-KR"
    }

    @Test
    fun `GET - return 404 for non-existent clinic`() {
        val response = client.get()
            .uri("$BASE_URL/{id}", 999999)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    @Test
    fun `GET - operating hours for clinic`() {
        val response = client.get()
            .uri("$BASE_URL/{id}/operating-hours", clinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull() shouldHaveSize 1
        response.jsonPath<String>("$.data[0].dayOfWeek") shouldBeEqualTo "MONDAY"
    }

    @Test
    fun `GET - break times for clinic`() {
        val response = client.get()
            .uri("$BASE_URL/{id}/break-times", clinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull() shouldHaveSize 1
        response.jsonPath<String>("$.data[0].name") shouldBeEqualTo "Lunch Break"
    }

    @Test
    fun `GET - return 400 for invalid clinic id`() {
        val response = client.get()
            .uri("$BASE_URL/{id}", -1)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }
}

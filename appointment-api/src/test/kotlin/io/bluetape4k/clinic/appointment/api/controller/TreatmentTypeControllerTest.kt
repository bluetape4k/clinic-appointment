package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
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
import org.springframework.web.client.RestClient

class TreatmentTypeControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    companion object : KLogging() {
        private const val CLINICS_BASE_URL = "/api/clinics"
        private const val TREATMENT_TYPES_BASE_URL = "/api/treatment-types"
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient

    private var clinicId: Long = 0
    private var treatmentTypeId: Long = 0

    @BeforeEach
    fun setup() {
        client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()

        transaction {
            SchemaUtils.create(Clinics, TreatmentTypes)

            TreatmentTypes.deleteAll()
            Clinics.deleteAll()

            clinicId = Clinics.insertAndGetId {
                it[name] = "Test Clinic"
                it[slotDurationMinutes] = 30
                it[timezone] = "Asia/Seoul"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 3
                it[openOnHolidays] = false
            }.value

            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@TreatmentTypeControllerTest.clinicId
                it[name] = "General Checkup"
                it[category] = "GENERAL"
                it[defaultDurationMinutes] = 30
                it[requiredProviderType] = "DOCTOR"
                it[requiresEquipment] = false
                it[maxConcurrentPatients] = 1
            }.value
        }
    }

    @Test
    fun `GET - treatment types by clinic`() {
        val response = client.get()
            .uri("$CLINICS_BASE_URL/{clinicId}/treatment-types", clinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull() shouldHaveSize 1
        response.jsonPath<String>("$.data[0].name") shouldBeEqualTo "General Checkup"
    }

    @Test
    fun `GET - treatment type by id`() {
        val response = client.get()
            .uri("$TREATMENT_TYPES_BASE_URL/{id}", treatmentTypeId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<String>("$.data.name") shouldBeEqualTo "General Checkup"
        response.jsonPath<String>("$.data.category") shouldBeEqualTo "GENERAL"
        response.jsonPath<Int>("$.data.defaultDurationMinutes") shouldBeEqualTo 30
    }

    @Test
    fun `GET - return 404 for non-existent treatment type`() {
        val response = client.get()
            .uri("$TREATMENT_TYPES_BASE_URL/{id}", 999999)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    @Test
    fun `GET - treatment types returns empty list for clinic with no types`() {
        val emptyClinicId = transaction {
            Clinics.insertAndGetId {
                it[name] = "Empty Clinic"
                it[slotDurationMinutes] = 30
                it[timezone] = "UTC"
                it[locale] = "ko-KR"
                it[maxConcurrentPatients] = 1
                it[openOnHolidays] = false
            }.value
        }

        val response = client.get()
            .uri("$CLINICS_BASE_URL/{clinicId}/treatment-types", emptyClinicId)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
        response.jsonPath<List<*>>("$.data").shouldNotBeNull().shouldBeEmpty()
    }
}

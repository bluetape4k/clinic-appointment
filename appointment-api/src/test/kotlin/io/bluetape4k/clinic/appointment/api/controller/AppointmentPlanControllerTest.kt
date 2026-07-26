package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["appointment.plan-foundation.plan-read-enabled=true"],
)
class AppointmentPlanControllerTest : AbstractApiIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient
    private var clinicId: Long = 0
    private var otherClinicId: Long = 0
    private var planId: Long = 0

    @BeforeEach
    fun setUpPlan() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
        transaction {
            clearPlanTables()
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = 1L
                it[name] = "Plan Read Clinic"
            }.value
            otherClinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = 1L
                it[name] = "Other Plan Clinic"
            }.value
            val catalog = ProductCatalogRepository().saveAggregate(catalog())
            val draft = AppointmentPlanFactory().create(
                catalog,
                AppointmentPlanFactoryInput(
                    sourcePurchaseAuthority = "commerce",
                    sourcePurchaseId = "purchase-read-1",
                    patientReferenceCiphertext = "encrypted-patient",
                    patientReferenceKeyId = "key-1",
                    patientReferenceFingerprint = "fingerprint",
                    bookingPreference = BookingPreferenceSnapshot.DateRange(
                        LocalDate.parse("2026-08-01"),
                        LocalDate.parse("2026-08-07"),
                        ZoneId.of("Asia/Seoul"),
                    ),
                ),
            )
            planId = requireNotNull(AppointmentPlanRepository().saveAggregate(draft).plan.id)
        }
    }

    @AfterEach
    fun cleanUpPlan() {
        transaction {
            clearPlanTables()
        }
    }

    @Test
    fun `reads the same scoped ordered plan by id and authority qualified purchase`() {
        val byId = get("/api/tenant-default/clinics/$clinicId/appointment-plans/$planId")
        val byPurchase = get(
            "/api/tenant-default/clinics/$clinicId/appointment-plans/by-purchase/commerce/purchase-read-1",
        )

        listOf(byId, byPurchase).forEach { response ->
            response.statusCode shouldBeEqualTo HttpStatus.OK
            response.jsonPath<Number>("$.data.id").toLong() shouldBeEqualTo planId
            response.jsonPath<String>("$.data.catalogSourceAuthority") shouldBeEqualTo "product-catalog"
            response.jsonPath<Number>("$.data.catalogVersion").toLong() shouldBeEqualTo 7L
            response.jsonPath<String>("$.data.catalogPayloadHash") shouldBeEqualTo "a".repeat(64)
            response.jsonPath<String>("$.data.bookingPreference.type") shouldBeEqualTo "DATE_RANGE"
            response.jsonPath<String>("$.data.treatments[0].bomItemId") shouldBeEqualTo "laser"
            response.jsonPath<Int>("$.data.treatments[0].sequenceNo") shouldBeEqualTo 1
            response.jsonPath<Int>("$.data.treatments[1].sequenceNo") shouldBeEqualTo 2
            response.jsonPath<String>("$.data.treatments[2].bomItemId") shouldBeEqualTo "care"
            response.jsonPath<Int>("$.data.dependencies.length()") shouldBeEqualTo 1
            response.body.contains("encrypted-patient").shouldBeFalse()
            response.body.contains("fingerprint").shouldBeFalse()
            response.body.contains("patientReference").shouldBeFalse()
        }
    }

    @Test
    fun `hides missing cross tenant and cross clinic plans`() {
        listOf(
            get("/api/tenant-default/clinics/$clinicId/appointment-plans/999999"),
            get("/api/unknown-tenant/clinics/$clinicId/appointment-plans/$planId"),
            get("/api/tenant-default/clinics/$otherClinicId/appointment-plans/$planId"),
            get(
                "/api/tenant-default/clinics/$otherClinicId/appointment-plans/by-purchase/commerce/purchase-read-1",
            ),
        ).forEach { response ->
            response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
            assertSanitized(response, "RESOURCE_NOT_FOUND")
        }
    }

    @Test
    fun `uses sanitized foundation errors for invalid and unexpected reads`() {
        val invalid = get("/api/tenant-default/clinics/$clinicId/appointment-plans/not-a-number")
        invalid.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        assertSanitized(invalid, "VALIDATION_FAILED")
        val unsafePurchase = get(
            "/api/tenant-default/clinics/$clinicId/appointment-plans/by-purchase/commerce/-unsafe",
        )
        unsafePurchase.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        assertSanitized(unsafePurchase, "VALIDATION_FAILED")

        transaction {
            AppointmentPlans.update({ AppointmentPlans.id eq planId }) {
                it[bookingPreferenceType] = "CORRUPTED"
            }
        }
        val internal = get("/api/tenant-default/clinics/$clinicId/appointment-plans/$planId")
        internal.statusCode shouldBeEqualTo HttpStatus.INTERNAL_SERVER_ERROR
        assertSanitized(internal, "INTERNAL_ERROR")
        internal.body.contains("CORRUPTED").shouldBeFalse()
        internal.body.contains("purchase-read-1").shouldBeFalse()
    }

    private fun get(path: String) = client.get().uri(path).execute()

    private fun assertSanitized(response: TestResponse, errorCode: String) {
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo errorCode
        response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
        response.body.contains("encrypted-patient").shouldBeFalse()
        response.body.contains("fingerprint").shouldBeFalse()
    }

    private fun clearPlanTables() {
        SchedulingOutboxEvents.deleteAll()
        SchedulingInboxEvents.deleteAll()
        TreatmentDependencies.deleteAll()
        PlannedTreatments.deleteAll()
        AppointmentPlans.deleteAll()
        ProductCatalogBomDependencies.deleteAll()
        ProductCatalogBomItems.deleteAll()
        ProductCatalogProjections.deleteAll()
    }

    private fun catalog() = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = 1L,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = "laser-care",
            catalogVersion = 7L,
            productName = "Laser Care",
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T05:00:00Z"),
            items = listOf(
                item("laser", "Laser", repeatCount = 2, bomOrderInterval = true),
                item("care", "After Care", repeatCount = 1, bomOrderInterval = false),
            ),
            dependencies = listOf(
                CatalogBomDependency(
                    predecessorBomItemId = "laser",
                    successorBomItemId = "care",
                    minimumIntervalDays = 1,
                    preferredIntervalDays = 2,
                    maximumIntervalDays = 3,
                ),
            ),
            initialBookingRule = null,
        ),
        payloadHash = "a".repeat(64),
    )

    private fun item(
        id: String,
        name: String,
        repeatCount: Int,
        bomOrderInterval: Boolean,
    ) = CatalogBomItem(
        bomItemId = id,
        representativeTreatmentName = name,
        detailedTreatmentCodes = listOf(id.uppercase()),
        repeatCount = repeatCount,
        durationMinutes = 30,
        minimumIntervalDays = if (bomOrderInterval) 21 else null,
        preferredIntervalDays = if (bomOrderInterval) 28 else null,
        maximumIntervalDays = if (bomOrderInterval) 42 else null,
        practitionerQualifications = listOf("DOCTOR"),
        equipmentTypes = listOf("EQUIPMENT"),
        roomTypes = listOf("ROOM"),
    )
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["appointment.plan-foundation.plan-read-enabled=false"],
)
class AppointmentPlanDisabledControllerTest : AbstractApiIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `keeps OpenAPI discoverable while returning sanitized feature disabled`() {
        val client = RestClient.builder().baseUrl("http://localhost:$port").build()
        val disabled = client.get()
            .uri("/api/tenant-default/clinics/1/appointment-plans/1")
            .execute()

        disabled.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        disabled.jsonPath<String>("$.errorCode") shouldBeEqualTo "FEATURE_DISABLED"

        val openApi = client.get().uri("/v3/api-docs").execute()
        openApi.statusCode shouldBeEqualTo HttpStatus.OK
        openApi.body.contains("/appointment-plans/{planId}").shouldBeTrue()
        openApi.body.contains("/appointment-plans/by-purchase/").shouldBeTrue()
    }
}

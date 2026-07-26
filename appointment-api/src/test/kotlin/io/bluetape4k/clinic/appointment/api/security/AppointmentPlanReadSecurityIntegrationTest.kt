package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
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
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryInput
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.time.Instant

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["appointment.plan-foundation.plan-read-enabled=true"],
)
@ActiveProfiles("test", "integration-test")
class AppointmentPlanReadSecurityIntegrationTest {

    companion object {
        private const val TENANT_A_ID = 31L
        private const val TENANT_B_ID = 32L
        private const val TENANT_A = "plan-a"
        private const val TENANT_B = "plan-b"

        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient
    private var clinicAId: Long = 0
    private var clinicBId: Long = 0
    private var planId: Long = 0

    @BeforeEach
    fun setUp() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
        transaction {
            clearFoundationTables()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            insertTenant(TENANT_A_ID, TENANT_A)
            insertTenant(TENANT_B_ID, TENANT_B)
            clinicAId = insertClinic(TENANT_A_ID, "Plan A Clinic")
            clinicBId = insertClinic(TENANT_B_ID, "Plan B Clinic")
            val catalog = ProductCatalogRepository().saveAggregate(catalog())
            val draft = AppointmentPlanFactory().create(
                catalog,
                AppointmentPlanFactoryInput(
                    sourcePurchaseAuthority = "commerce",
                    sourcePurchaseId = "purchase-secure",
                    patientReferenceCiphertext = "encrypted",
                    patientReferenceKeyId = "key-1",
                    patientReferenceFingerprint = "fingerprint",
                    bookingPreference = BookingPreferenceSnapshot.NotProvided,
                ),
            )
            planId = requireNotNull(AppointmentPlanRepository().saveAggregate(draft).plan.id)
        }
    }

    @AfterEach
    fun cleanUp() {
        transaction {
            clearFoundationTables()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
        }
    }

    private fun clearFoundationTables() {
        SchedulingOutboxEvents.deleteAll()
        SchedulingInboxEvents.deleteAll()
        TreatmentDependencies.deleteAll()
        PlannedTreatments.deleteAll()
        AppointmentPlans.deleteAll()
        ProductCatalogBomDependencies.deleteAll()
        ProductCatalogBomItems.deleteAll()
        ProductCatalogProjections.deleteAll()
    }

    @Test
    fun `plan reads require operator role tenant membership and exact clinic claim`() {
        request(token = null).statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED
        request(TestJwtProvider.patientToken(listOf(TENANT_A))).statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
        request(
            TestJwtProvider.staffToken(clinicAId, listOf(TENANT_B)),
        ).statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
        request(
            TestJwtProvider.doctorToken(clinicBId, listOf(TENANT_A)),
        ).statusCode shouldBeEqualTo HttpStatus.FORBIDDEN

        listOf(
            TestJwtProvider.adminToken(clinicAId, listOf(TENANT_A)),
            TestJwtProvider.staffToken(clinicAId, listOf(TENANT_A)),
            TestJwtProvider.doctorToken(clinicAId, listOf(TENANT_A)),
        ).forEach { token ->
            request(token).statusCode shouldBeEqualTo HttpStatus.OK
        }
    }

    private fun request(token: String?) =
        client.get()
            .uri("/api/$TENANT_A/clinics/$clinicAId/appointment-plans/$planId")
            .apply {
                if (token != null) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                }
            }
            .execute()

    private fun insertTenant(id: Long, code: String) {
        TenantGroups.insert {
            it[TenantGroups.id] = EntityID(id, TenantGroups)
            it[tenantCode] = code
            it[displayName] = code
            it[active] = true
        }
    }

    private fun insertClinic(tenantId: Long, name: String): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = EntityID(tenantId, TenantGroups)
            it[Clinics.name] = name
        }.value

    private fun catalog() = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = TENANT_A_ID,
            clinicId = clinicAId,
            sourceAuthority = "product-catalog",
            productId = "secure-plan",
            catalogVersion = 1L,
            productName = "Secure Plan",
            schemaVersion = 1,
            sourceUpdatedAt = Instant.parse("2026-07-26T05:00:00Z"),
            items = listOf(
                CatalogBomItem(
                    bomItemId = "care",
                    representativeTreatmentName = "Care",
                    detailedTreatmentCodes = listOf("CARE"),
                    repeatCount = 1,
                    durationMinutes = 30,
                    minimumIntervalDays = null,
                    preferredIntervalDays = null,
                    maximumIntervalDays = null,
                    practitionerQualifications = listOf("DOCTOR"),
                    equipmentTypes = emptyList(),
                    roomTypes = listOf("ROOM"),
                ),
            ),
            dependencies = emptyList(),
            initialBookingRule = null,
        ),
        payloadHash = "b".repeat(64),
    )
}

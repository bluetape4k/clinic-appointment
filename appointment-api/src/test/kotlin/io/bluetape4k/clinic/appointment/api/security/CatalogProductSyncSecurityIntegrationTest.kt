package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.service.CatalogPayloadHasher
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.time.Instant

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["appointment.plan-foundation.catalog-sync-enabled=true"],
)
@ActiveProfiles("test", "integration-test")
class CatalogProductSyncSecurityIntegrationTest {

    companion object {
        private const val TENANT_A_ID = 10L
        private const val TENANT_B_ID = 20L
        private const val TENANT_A = "catalog-a"
        private const val TENANT_B = "catalog-b"

        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient
    private var tenantAClinicId: Long = 0
    private var tenantBClinicId: Long = 0

    @BeforeEach
    fun setup() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
        transaction {
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            insertTenant(TENANT_A_ID, TENANT_A)
            insertTenant(TENANT_B_ID, TENANT_B)
            tenantAClinicId = insertClinic(TENANT_A_ID, "Catalog A Clinic")
            tenantBClinicId = insertClinic(TENANT_B_ID, "Catalog B Clinic")
        }
    }

    @AfterEach
    fun cleanupCatalog() {
        transaction {
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
        }
    }

    @Test
    fun `catalog write security requires authentication tenant scope and source authority`() {
        request(tenantCode = TENANT_A, clinicId = tenantAClinicId, token = null)
            .statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED

        request(
            tenantCode = TENANT_A,
            clinicId = tenantAClinicId,
            token = catalogWriterToken(allowedTenants = listOf(TENANT_B)),
        ).statusCode shouldBeEqualTo HttpStatus.FORBIDDEN

        request(
            tenantCode = TENANT_A,
            clinicId = tenantBClinicId,
            token = catalogWriterToken(allowedTenants = listOf(TENANT_A)),
            tenantGroupId = TENANT_B_ID,
        ).statusCode shouldBeEqualTo HttpStatus.NOT_FOUND

        request(TENANT_A, tenantAClinicId, TestJwtProvider.patientToken(listOf(TENANT_A)))
            .statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
        request(TENANT_A, tenantAClinicId, TestJwtProvider.doctorToken(tenantAClinicId, listOf(TENANT_A)))
            .statusCode shouldBeEqualTo HttpStatus.FORBIDDEN

        val staffWithoutSourceClaim = TestJwtProvider.createToken(
            userId = "catalog-staff",
            clinicId = tenantAClinicId,
            roles = listOf(SchedulingRole.STAFF),
            allowedTenants = listOf(TENANT_A),
            scopes = setOf("catalog:write"),
        )
        request(TENANT_A, tenantAClinicId, staffWithoutSourceClaim)
            .statusCode shouldBeEqualTo HttpStatus.FORBIDDEN

        request(TENANT_A, tenantAClinicId, catalogWriterToken(listOf(TENANT_A)))
            .statusCode shouldBeEqualTo HttpStatus.CREATED
    }

    private fun request(
        tenantCode: String,
        clinicId: Long,
        token: String?,
        tenantGroupId: Long = TENANT_A_ID,
    ) = client.put()
        .uri("/api/$tenantCode/clinics/$clinicId/catalog-products/laser-care/versions/7")
        .contentType(MediaType.APPLICATION_JSON)
        .apply {
            if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }
        .body(requestJson(tenantGroupId, clinicId))
        .execute()

    private fun catalogWriterToken(allowedTenants: List<String>) =
        TestJwtProvider.createToken(
            userId = "catalog-writer",
            clinicId = tenantAClinicId,
            roles = listOf(SchedulingRole.STAFF),
            allowedTenants = allowedTenants,
            scopes = setOf("catalog:write"),
            catalogSourceAuthorities = setOf("product-catalog"),
        )

    private fun requestJson(tenantGroupId: Long, clinicId: Long): String {
        val definition = definition(tenantGroupId, clinicId)
        return """
            {
              "sourceAuthority": "product-catalog",
              "tenantGroupId": $tenantGroupId,
              "clinicId": $clinicId,
              "productId": "laser-care",
              "catalogVersion": 7,
              "schemaVersion": 1,
              "sourceUpdatedAt": "2026-07-26T05:00:00Z",
              "status": "ACTIVE",
              "productName": "Laser Care",
              "items": [{
                "bomItemId": "laser",
                "representativeTreatmentName": "Laser",
                "detailedTreatmentCodes": ["LASER"],
                "repeatCount": 1,
                "durationMinutes": 30,
                "practitionerQualifications": ["DERMATOLOGIST"],
                "equipmentTypes": ["LASER_A"],
                "roomTypes": ["PROCEDURE"]
              }],
              "dependencies": [],
              "initialBookingRule": {"type": "WITHIN_DAYS_AFTER_PURCHASE", "maximumDays": 14},
              "payloadHash": "${CatalogPayloadHasher.hash(definition)}"
            }
        """.trimIndent()
    }

    private fun definition(tenantGroupId: Long, clinicId: Long) = ProductCatalogDefinition(
        sourceAuthority = "product-catalog",
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        productId = "laser-care",
        catalogVersion = 7,
        schemaVersion = 1,
        sourceUpdatedAt = Instant.parse("2026-07-26T05:00:00Z"),
        productName = "Laser Care",
        items = listOf(
            CatalogBomItem(
                bomItemId = "laser",
                representativeTreatmentName = "Laser",
                detailedTreatmentCodes = listOf("LASER"),
                repeatCount = 1,
                durationMinutes = 30,
                minimumIntervalDays = null,
                preferredIntervalDays = null,
                maximumIntervalDays = null,
                practitionerQualifications = listOf("DERMATOLOGIST"),
                equipmentTypes = listOf("LASER_A"),
                roomTypes = listOf("PROCEDURE"),
            )
        ),
        dependencies = emptyList(),
        initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(14),
    )

    private fun insertTenant(id: Long, tenantCode: String) {
        TenantGroups.insert {
            it[TenantGroups.id] = EntityID(id, TenantGroups)
            it[TenantGroups.tenantCode] = tenantCode
            it[displayName] = tenantCode
            it[active] = true
        }
    }

    private fun insertClinic(tenantGroupId: Long, name: String): Long =
        Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[Clinics.name] = name
        }.value
}

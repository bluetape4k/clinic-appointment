package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.catalog.InitialBookingRule
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.service.CatalogDefinitionValidator
import io.bluetape4k.clinic.appointment.service.CatalogPayloadHasher
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["appointment.plan-foundation.catalog-sync-enabled=true"],
)
class CatalogProductSyncControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient
    private var clinicId: Long = 0

    @BeforeEach
    fun setupCatalog() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                ProductCatalogProjections,
                ProductCatalogBomItems,
                ProductCatalogBomDependencies,
            )
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = 1L
                it[name] = "Catalog API Clinic"
            }.value
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
    fun `creates replays ignores stale and rejects conflicting catalog versions`() {
        putCatalog(catalogDefinition(clinicId = clinicId, version = 7L))
            .also { response ->
                response.statusCode shouldBeEqualTo HttpStatus.CREATED
                response.jsonPath<Boolean>("$.success").shouldBeTrue()
                response.jsonPath<String>("$.data.status") shouldBeEqualTo "CREATED"
            }
        putCatalog(catalogDefinition(clinicId = clinicId, version = 7L))
            .also { response ->
                response.statusCode shouldBeEqualTo HttpStatus.OK
                response.jsonPath<String>("$.data.status") shouldBeEqualTo "UNCHANGED"
            }
        putCatalog(catalogDefinition(clinicId = clinicId, version = 6L))
            .also { response ->
                response.statusCode shouldBeEqualTo HttpStatus.ACCEPTED
                response.jsonPath<String>("$.data.status") shouldBeEqualTo "STALE_IGNORED"
            }
        putCatalog(catalogDefinition(clinicId = clinicId, version = 7L, productName = "Changed"))
            .also { response ->
                response.statusCode shouldBeEqualTo HttpStatus.CONFLICT
                assertSanitizedError(response, "CATALOG_VERSION_CONFLICT")
            }
    }

    @Test
    fun `rejects path and body identity mismatches with a sanitized error`() {
        val definition = catalogDefinition(clinicId = clinicId)
        val otherClinicId = transaction {
            Clinics.insertAndGetId {
                it[tenantGroupId] = 1L
                it[name] = "Other Catalog Clinic"
            }.value
        }

        listOf(
            putCatalog(definition.copy(tenantGroupId = 999L)),
            putCatalog(definition, pathClinicId = otherClinicId),
            putCatalog(definition, pathSourceAuthority = "other-catalog"),
            putCatalog(definition, pathProductId = "different-product"),
            putCatalog(definition, pathVersion = 8L),
        ).forEach { response ->
            response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
            assertSanitizedError(response, "VALIDATION_FAILED")
        }
    }

    @Test
    fun `requires and persists the explicit catalog lifecycle status`() {
        val retired = catalogDefinition(clinicId = clinicId, version = 8L)
            .copy(status = CatalogProjectionStatus.RETIRED)

        putCatalog(retired).statusCode shouldBeEqualTo HttpStatus.CREATED

        transaction {
            ProductCatalogProjections.selectAll().single()[ProductCatalogProjections.status] shouldBeEqualTo
                CatalogProjectionStatus.RETIRED
        }
    }

    @Test
    fun `hides a clinic outside the requested tenant`() {
        val otherTenantId = transaction {
            TenantGroups.insertAndGetId {
                it[tenantCode] = "other-tenant"
                it[displayName] = "Other Tenant"
                it[active] = true
            }.value
        }
        transaction {
            Clinics.insertAndGetId {
                it[tenantGroupId] = otherTenantId
                it[name] = "Other Tenant Clinic"
            }
        }
        val definition = catalogDefinition(clinicId = clinicId)

        val response = putCatalog(definition, tenantCode = "other-tenant")

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        assertSanitizedError(response, "RESOURCE_NOT_FOUND")
    }

    @Test
    fun `rejects a cyclic BOM without persisting partial rows`() {
        val body = """
            {
              "sourceAuthority": "product-catalog",
              "tenantGroupId": 1,
              "clinicId": $clinicId,
              "productId": "laser-care",
              "catalogVersion": 7,
              "schemaVersion": 1,
              "sourceUpdatedAt": "2026-07-26T05:00:00Z",
              "status": "ACTIVE",
              "productName": "Laser Care",
              "items": [
                {
                  "bomItemId": "laser",
                  "representativeTreatmentName": "Laser",
                  "repeatCount": 1,
                  "durationMinutes": 30
                },
                {
                  "bomItemId": "care",
                  "representativeTreatmentName": "Care",
                  "repeatCount": 1,
                  "durationMinutes": 20
                }
              ],
              "dependencies": [
                {
                  "predecessorBomItemId": "laser",
                  "successorBomItemId": "care",
                  "minimumIntervalDays": 1,
                  "preferredIntervalDays": 2,
                  "maximumIntervalDays": 3
                },
                {
                  "predecessorBomItemId": "care",
                  "successorBomItemId": "laser",
                  "minimumIntervalDays": 1,
                  "preferredIntervalDays": 2,
                  "maximumIntervalDays": 3
                }
              ],
              "payloadHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            }
        """.trimIndent()

        val response = client.put()
            .uri(path("tenant-default", clinicId, "product-catalog", "laser-care", 7L))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        assertSanitizedError(response, "VALIDATION_FAILED")
        transaction {
            ProductCatalogProjections.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `rejects canonical hash mismatch without echoing the supplied hash`() {
        val definition = catalogDefinition(clinicId = clinicId)

        val response = putCatalog(definition, payloadHash = "a".repeat(64))

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        assertSanitizedError(response, "VALIDATION_FAILED")
        response.bodyText().contains("a".repeat(64)).shouldBeFalse()
    }

    @Test
    fun `rejects oversized catalog payload before JSON deserialization without content length`() {
        val payload = ByteArray(CatalogDefinitionValidator.MAX_PAYLOAD_BYTES + 1) { '{'.code.toByte() }

        val response = HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder()
                    .uri(
                        URI.create(
                            "http://localhost:$port" +
                                path("tenant-default", clinicId, "product-catalog", "laser-care", 7L)
                        )
                    )
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .PUT(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(payload) })
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        response.statusCode() shouldBeEqualTo HttpStatus.CONTENT_TOO_LARGE.value()
        val error = com.jayway.jsonpath.JsonPath.parse(response.body())
        error.read<Map<String, Any?>>("$").keys shouldBeEqualTo
            setOf("success", "data", "error", "errorCode", "correlationId")
        error.read<Boolean>("$.success").shouldBeFalse()
        error.read<String>("$.errorCode") shouldBeEqualTo "PAYLOAD_TOO_LARGE"
        error.read<String>("$.correlationId").isNotBlank().shouldBeTrue()
    }

    @Test
    fun `rejects invalid bounds and malformed instants`() {
        val invalidBounds = requestJson(catalogDefinition(clinicId = clinicId))
            .replace("\"repeatCount\": 3", "\"repeatCount\": 0")
        client.put()
            .uri(path("tenant-default", clinicId, "product-catalog", "laser-care", 7L))
            .contentType(MediaType.APPLICATION_JSON)
            .body(invalidBounds)
            .execute()
            .also { response ->
                response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
                assertSanitizedError(response, "VALIDATION_FAILED")
            }

        val invalidInstant = requestJson(catalogDefinition(clinicId = clinicId))
            .replace("2026-07-26T05:00:00Z", "2026-07-26 14:00 Asia/Seoul")
        client.put()
            .uri(path("tenant-default", clinicId, "product-catalog", "laser-care", 7L))
            .contentType(MediaType.APPLICATION_JSON)
            .body(invalidInstant)
            .execute()
            .also { response ->
                response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
                assertSanitizedError(response, "VALIDATION_FAILED")
            }
    }

    @Test
    fun `sanitizes unexpected persistence failures`() {
        transaction {
            exec(
                """
                ALTER TABLE scheduling_product_catalog_bom_items
                ADD CONSTRAINT reject_catalog_api_laser CHECK (bom_item_id <> 'laser')
                """.trimIndent()
            )
        }
        val definition = catalogDefinition(clinicId = clinicId)

        val response = try {
            putCatalog(definition)
        } finally {
            transaction {
                exec("ALTER TABLE scheduling_product_catalog_bom_items DROP CONSTRAINT reject_catalog_api_laser")
            }
        }

        response.statusCode shouldBeEqualTo HttpStatus.INTERNAL_SERVER_ERROR
        assertSanitizedError(response, "INTERNAL_ERROR")
        response.body.contains(definition.productName).shouldBeFalse()
        response.body.contains(CatalogPayloadHasher.hash(definition)).shouldBeFalse()
    }

    @Test
    fun `documents source-qualified catalog sync path security responses and hash contract`() {
        val openApi = client.get().uri("/v3/api-docs").execute()

        openApi.statusCode shouldBeEqualTo HttpStatus.OK
        openApi.jsonPath<String>(
            "$.paths['/api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}'].put.externalDocs.url"
        ) shouldBeEqualTo "https://github.com/bluetape4k/clinic-appointment/blob/main/docs/api/catalog-payload-hash.md"
        openApi.jsonPath<String>(
            "$.paths['/api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}'].put.responses['401'].description"
        ) shouldBeEqualTo "Missing or invalid bearer token"
        openApi.jsonPath<String>(
            "$.paths['/api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}'].put.responses['403'].description"
        ) shouldBeEqualTo "Authenticated caller lacks tenant or catalog-source write authority"
        listOf("200", "201", "202").forEach { status ->
            openApi.jsonPath<String>(
                "$.paths['/api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}'].put.responses['$status'].content['application/json'].schema['\$ref']"
            ) shouldBeEqualTo "#/components/schemas/CatalogSyncApiResponse"
        }
        listOf("400", "401", "403", "404", "409", "413", "500").forEach { status ->
            openApi.jsonPath<String>(
                "$.paths['/api/{tenantCode}/clinics/{clinicId}/catalog-sources/{sourceAuthority}/catalog-products/{productId}/versions/{catalogVersion}'].put.responses['$status'].content['application/json'].schema['\$ref']"
            ) shouldBeEqualTo "#/components/schemas/SchedulingApiErrorResponse"
        }
        openApi.jsonPath<Map<String, Any?>>(
            "$.components.schemas.SchedulingApiErrorResponse.properties"
        ).keys shouldBeEqualTo setOf("success", "data", "error", "errorCode", "correlationId")
    }

    private fun putCatalog(
        definition: ProductCatalogDefinition,
        payloadHash: String = CatalogPayloadHasher.hash(definition),
        tenantCode: String = "tenant-default",
        pathClinicId: Long = definition.clinicId,
        pathSourceAuthority: String = definition.sourceAuthority,
        pathProductId: String = definition.productId,
        pathVersion: Long = definition.catalogVersion,
    ) = client.put()
        .uri(path(tenantCode, pathClinicId, pathSourceAuthority, pathProductId, pathVersion))
        .contentType(MediaType.APPLICATION_JSON)
        .body(requestJson(definition, payloadHash))
        .execute()

    private fun path(
        tenantCode: String,
        clinicId: Long,
        sourceAuthority: String,
        productId: String,
        version: Long,
    ) =
        "/api/$tenantCode/clinics/$clinicId/catalog-sources/$sourceAuthority/catalog-products/$productId/versions/$version"

    private fun requestJson(
        definition: ProductCatalogDefinition,
        payloadHash: String = CatalogPayloadHasher.hash(definition),
    ): String =
        """
        {
          "sourceAuthority": "${definition.sourceAuthority}",
          "tenantGroupId": ${definition.tenantGroupId},
          "clinicId": ${definition.clinicId},
          "productId": "${definition.productId}",
          "catalogVersion": ${definition.catalogVersion},
          "schemaVersion": ${definition.schemaVersion},
          "sourceUpdatedAt": "${definition.sourceUpdatedAt}",
          "status": "${definition.status}",
          "productName": "${definition.productName}",
          "items": [{
            "bomItemId": "laser",
            "representativeTreatmentName": "Laser",
            "detailedTreatmentCodes": ["LASER"],
            "repeatCount": 3,
            "durationMinutes": 30,
            "minimumIntervalDays": 21,
            "preferredIntervalDays": 28,
            "maximumIntervalDays": 42,
            "practitionerQualifications": ["DERMATOLOGIST"],
            "equipmentTypes": ["LASER_A"],
            "roomTypes": ["PROCEDURE"]
          }],
          "dependencies": [],
          "initialBookingRule": {
            "type": "WITHIN_DAYS_AFTER_PURCHASE",
            "maximumDays": 14
          },
          "payloadHash": "$payloadHash"
        }
        """.trimIndent()

    private fun assertSanitizedError(
        response: TestResponse,
        errorCode: String,
    ) {
        response.jsonPath<Boolean>("$.success").shouldBeFalse()
        response.jsonPath<Any?>("$.data").shouldBeNull()
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo errorCode
        response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
        response.jsonPath<Map<String, Any?>>("$").keys shouldBeEqualTo setOf(
            "success",
            "data",
            "error",
            "errorCode",
            "correlationId",
        )
    }

    private fun TestResponse.bodyText(): String = body

    private fun catalogDefinition(
        clinicId: Long,
        version: Long = 7L,
        productName: String = "Laser Care",
    ) = ProductCatalogDefinition(
        sourceAuthority = "product-catalog",
        tenantGroupId = 1L,
        clinicId = clinicId,
        productId = "laser-care",
        catalogVersion = version,
        schemaVersion = 1,
        sourceUpdatedAt = Instant.parse("2026-07-26T05:00:00Z"),
        productName = productName,
        items = listOf(
            CatalogBomItem(
                bomItemId = "laser",
                representativeTreatmentName = "Laser",
                detailedTreatmentCodes = listOf("LASER"),
                repeatCount = 3,
                durationMinutes = 30,
                minimumIntervalDays = 21,
                preferredIntervalDays = 28,
                maximumIntervalDays = 42,
                practitionerQualifications = listOf("DERMATOLOGIST"),
                equipmentTypes = listOf("LASER_A"),
                roomTypes = listOf("PROCEDURE"),
            )
        ),
        dependencies = emptyList(),
        initialBookingRule = InitialBookingRule.WithinDaysAfterPurchase(14),
    )
}

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["appointment.plan-foundation.catalog-sync-enabled=false"],
)
class CatalogProductSyncDisabledControllerTest @Autowired constructor() : AbstractApiIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private var tenantGroupId: Long = 0
    private var clinicId: Long = 0
    private lateinit var tenantCode: String

    @BeforeEach
    fun setUpDisabledScope() {
        tenantCode = "catalog-disabled-${System.nanoTime()}"
        transaction {
            tenantGroupId = TenantGroups.insertAndGetId {
                it[TenantGroups.tenantCode] = this@CatalogProductSyncDisabledControllerTest.tenantCode
                it[displayName] = "Catalog Disabled Tenant"
                it[active] = true
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] = EntityID(
                    this@CatalogProductSyncDisabledControllerTest.tenantGroupId,
                    TenantGroups,
                )
                it[name] = "Catalog Disabled Clinic"
            }.value
        }
    }

    @Test
    fun `returns a sanitized feature disabled response`() {
        val response = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
            .put()
            .uri("/api/$tenantCode/clinics/$clinicId/catalog-sources/product-catalog/catalog-products/laser-care/versions/7")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                """
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
                    "repeatCount": 1,
                    "durationMinutes": 30
                  }],
                  "dependencies": [],
                  "payloadHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                }
                """.trimIndent()
            )
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "FEATURE_DISABLED"
        response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
    }
}

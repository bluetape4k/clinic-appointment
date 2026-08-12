package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class NotificationOperationsSecurityIntegrationTest {

    companion object {
        private const val TENANT_ID = 121L
        private const val TENANT_CODE = "notification-security-tenant"

        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient
    private var clinicId: Long = 0
    private var siblingClinicId: Long = 0

    @BeforeEach
    fun setUp() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
        transaction {
            Clinics.deleteWhere {
                Clinics.tenantGroupId eq EntityID(TENANT_ID, TenantGroups)
            }
            TenantGroups.deleteWhere {
                TenantGroups.id eq EntityID(TENANT_ID, TenantGroups)
            }
            TenantGroups.insert {
                it[id] = EntityID(TENANT_ID, TenantGroups)
                it[tenantCode] = TENANT_CODE
                it[displayName] = "Notification Security Tenant"
                it[active] = true
            }
            clinicId = insertClinic("Notification Clinic")
            siblingClinicId = insertClinic("Other Notification Clinic")
        }
    }

    @Test
    fun `read matcher requires notification read capability and exact clinic membership`() {
        readRequest(token = null).status shouldBeEqualTo HttpStatus.UNAUTHORIZED.value()
        readRequest(token = staffToken(scopes = emptySet())).assertForbidden()
        readRequest(token = TestJwtProvider.patientToken(listOf(TENANT_CODE))).assertForbidden()
        readRequest(token = staffToken(scopes = setOf("notification:read"), allowedTenants = listOf("other"))).assertForbidden()

        readRequest(
            token = staffToken(scopes = setOf("notification:read"), allowedClinicIds = setOf(siblingClinicId)),
        ).assertForbidden()

        val allowed = readRequest(token = staffToken(scopes = setOf("notification:read")))
        allowed.status shouldBeEqualTo HttpStatus.SERVICE_UNAVAILABLE.value()
        allowed.body.contains("NOTIFICATION_OPERATION_UNAVAILABLE").shouldBeTrue()
    }

    @Test
    fun `re notify matcher is separate from generic write and read capabilities`() {
        reNotifyRequest(token = staffToken(scopes = setOf("notification:read"))).assertForbidden()
        reNotifyRequest(token = staffToken(scopes = emptySet())).assertForbidden()
        reNotifyRequest(token = staffToken(scopes = setOf("notification:renotify"))).assertForbidden()
        reNotifyRequest(
            token = platformToken(scopes = emptySet()),
        ).assertForbidden()
        reNotifyRequest(
            token = platformToken(
                scopes = setOf("notification:renotify"),
                allowedClinicIds = setOf(siblingClinicId),
            ),
        ).assertForbidden()

        val allowed = reNotifyRequest(
            token = platformToken(scopes = setOf("notification:renotify")),
            body = """
                {
                  "appointmentIds": [1],
                  "generation": "security-test",
                  "platformApproval": {"authority": "platform", "reference": "PLAT-1"},
                  "clinicApproval": {"authority": "clinic", "reference": "CLINIC-1"},
                  "dryRun": true
                }
            """.trimIndent(),
        )
        allowed.status shouldBeEqualTo HttpStatus.SERVICE_UNAVAILABLE.value()
        allowed.body.contains("NOTIFICATION_OPERATION_UNAVAILABLE").shouldBeTrue()
    }

    private fun readRequest(token: String?): Response =
        client.get()
            .uri("/api/$TENANT_CODE/clinics/$clinicId/notifications/appointments/1/status")
            .header(CorrelationIdFilter.HEADER_NAME, "notification-security")
            .apply {
                if (token != null) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                }
            }
            .exchange { _, response ->
                Response(response.statusCode.value(), response.bodyTo(String::class.java).orEmpty())
            }

    private fun reNotifyRequest(
        token: String?,
        body: String = "{}",
    ): Response =
        client.post()
            .uri("/api/$TENANT_CODE/clinics/$clinicId/notifications/re-notify")
            .header(CorrelationIdFilter.HEADER_NAME, "notification-security")
            .contentType(MediaType.APPLICATION_JSON)
            .apply {
                if (token != null) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                }
            }
            .body(body)
            .exchange { _, response ->
                Response(response.statusCode.value(), response.bodyTo(String::class.java).orEmpty())
            }

    private fun staffToken(
        scopes: Set<String>,
        allowedTenants: List<String> = listOf(TENANT_CODE),
        allowedClinicIds: Set<Long> = setOf(clinicId),
    ): String =
        TestJwtProvider.createToken(
            userId = "notification-staff",
            clinicId = allowedClinicIds.singleOrNull(),
            roles = listOf(SchedulingRole.STAFF),
            actorType = ActorType.STAFF,
            allowedTenants = allowedTenants,
            allowedClinicIds = allowedClinicIds,
            scopes = scopes,
        )

    private fun platformToken(
        scopes: Set<String>,
        allowedClinicIds: Set<Long> = setOf(clinicId),
    ): String =
        TestJwtProvider.createToken(
            userId = "notification-platform-service",
            clinicId = allowedClinicIds.singleOrNull(),
            roles = listOf(SchedulingRole.SYSTEM),
            actorType = ActorType.SYSTEM,
            allowedTenants = listOf(TENANT_CODE),
            allowedClinicIds = allowedClinicIds,
            scopes = scopes,
            assurance = AuthenticationAssurance.SERVICE,
        )

    private fun insertClinic(name: String): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = EntityID(TENANT_ID, TenantGroups)
            it[Clinics.name] = name
        }.value

    private fun Response.assertForbidden() {
        status shouldBeEqualTo HttpStatus.FORBIDDEN.value()
        body.contains("\"errorCode\":\"FORBIDDEN\"").shouldBeTrue()
    }

    private data class Response(
        val status: Int,
        val body: String,
    )
}

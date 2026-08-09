package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.dto.RescheduleCandidateRecord
import io.bluetape4k.clinic.appointment.service.ClosureRescheduleService
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Import
import org.springframework.web.client.RestClient

/** Closure mutation이 generic tenant-wide write 권한으로 확장되지 않는지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@Import(RescheduleClosureSecurityIntegrationTest.Fixture::class)
class RescheduleClosureSecurityIntegrationTest {

    companion object {
        private const val TENANT_ID = 203L
        private const val TENANT_CODE = "closure-security-tenant"

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
            SchemaUtils.createMissingTablesAndColumns(TenantGroups, Clinics)
            Clinics.deleteWhere { Clinics.tenantGroupId eq EntityID(TENANT_ID, TenantGroups) }
            TenantGroups.deleteWhere { TenantGroups.id eq EntityID(TENANT_ID, TenantGroups) }
            TenantGroups.insert {
                it[id] = EntityID(TENANT_ID, TenantGroups)
                it[tenantCode] = TENANT_CODE
                it[displayName] = "Closure Security Tenant"
                it[active] = true
            }
            clinicId = insertClinic("Closure Clinic")
            siblingClinicId = insertClinic("Sibling Closure Clinic")
        }

    }

    @Test
    fun `허가된 clinic만 closure mutation을 controller까지 통과한다`() {
        closureRequest(operatorToken(setOf(clinicId))) shouldBeEqualTo HttpStatus.OK.value()
    }

    @Test
    fun `다른 clinic allow-list와 빈 allow-list는 closure mutation을 거절한다`() {
        closureRequest(operatorToken(setOf(siblingClinicId))) shouldBeEqualTo HttpStatus.FORBIDDEN.value()
        closureRequest(operatorToken(emptySet())) shouldBeEqualTo HttpStatus.FORBIDDEN.value()
    }

    private fun insertClinic(name: String): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = EntityID(TENANT_ID, TenantGroups)
            it[Clinics.name] = name
        }.value

    private fun closureRequest(token: String): Int =
        client.post()
            .uri(
                "/api/$TENANT_CODE/appointments/999/reschedule/closure?clinicId=$clinicId&closureDate=2099-08-06",
            )
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(CorrelationIdFilter.HEADER_NAME, "closure-security")
            .execute()
            .statusCode.value()

    private fun operatorToken(allowedClinicIds: Set<Long>): String =
        TestJwtProvider.createToken(
            userId = "closure-operator",
            clinicId = allowedClinicIds.singleOrNull(),
            roles = listOf(SchedulingRole.ADMIN),
            actorType = ActorType.ADMIN,
            allowedTenants = listOf(TENANT_CODE),
            allowedClinicIds = allowedClinicIds,
        )

    @TestConfiguration(proxyBeanMethods = false)
    class Fixture {
        @Bean
        @Primary
        fun testClosureRescheduleService(): ClosureRescheduleService = mockk {
            every { processClosureReschedule(any(), any(), any(), any()) } returns
                emptyMap<Long, List<RescheduleCandidateRecord>>()
        }
    }
}

package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.security.TestJwtProvider
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import jakarta.servlet.ServletContext
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteAll
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class MultitenancyIntegrationTest @Autowired constructor() {

    companion object {
        private const val TENANT_A_ID = 10L
        private const val TENANT_B_ID = 20L
        private const val TENANT_A = "tenant-a"
        private const val TENANT_B = "tenant-b"

        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var servletContext: ServletContext

    private lateinit var client: RestClient
    private var tenantAClinicId: Long = 0
    private var tenantBClinicId: Long = 0

    @BeforeEach
    fun setup() {
        client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()

        transaction {
            ClinicDefaultBreakTimes.deleteAll()
            OperatingHoursTable.deleteAll()
            DoctorSchedules.deleteAll()
            Doctors.deleteAll()
            TreatmentTypes.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            insertTenant(TENANT_A_ID, TENANT_A, "Tenant A")
            insertTenant(TENANT_B_ID, TENANT_B, "Tenant B")
            tenantAClinicId = insertClinic(TENANT_A_ID, "Tenant A Clinic")
            tenantBClinicId = insertClinic(TENANT_B_ID, "Tenant B Clinic")
        }
    }

    @Test
    fun `allowed tenant can access tenant clinic`() {
        val response = client.get()
            .uri("/api/{tenantCode}/clinics/{clinicId}", TENANT_A, tenantAClinicId)
            .bearer(TestJwtProvider.adminToken(allowedTenants = listOf(TENANT_A)))
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<Boolean>("$.success").shouldBeTrue()
    }

    @Test
    fun `unknown tenant without token returns unauthorized`() {
        val response = client.get()
            .uri("/api/missing/clinics")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED
    }

    @Test
    fun `clinic owned by another tenant returns not found`() {
        val response = client.get()
            .uri("/api/{tenantCode}/clinics/{clinicId}", TENANT_A, tenantBClinicId)
            .bearer(TestJwtProvider.adminToken(allowedTenants = listOf(TENANT_A)))
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
    }

    @Test
    fun `custom security filters are owned only by the security chain`() {
        servletContext.getFilterRegistration("jwtAuthenticationFilter").shouldBeNull()
        servletContext.getFilterRegistration("tenantContextFilter").shouldBeNull()
        servletContext.getFilterRegistration("correlationIdFilter").shouldBeNull()
    }

    private fun RestClient.RequestHeadersSpec<*>.bearer(token: String): RestClient.RequestHeadersSpec<*> =
        header(HttpHeaders.AUTHORIZATION, "Bearer $token")

    private fun insertTenant(id: Long, tenantCode: String, displayName: String) {
        TenantGroups.insert {
            it[TenantGroups.id] = EntityID(id, TenantGroups)
            it[TenantGroups.tenantCode] = tenantCode
            it[TenantGroups.displayName] = displayName
            it[TenantGroups.active] = true
        }
    }

    private fun insertClinic(tenantGroupId: Long, clinicName: String): Long =
        Clinics.insertAndGetId {
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[name] = clinicName
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 2
        }.value
}

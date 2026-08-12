package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.mockk.mockk
import jakarta.servlet.FilterChain
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
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient

/**
 * 실제 HTTP 보안 필터 체인에서 예약 정책 관리 경계가 Gateway JWT claim을 강제하는지 검증한다.
 *
 * 서명·audience·만료·tenant·clinic 범위가 유효한 token만 요청을 통과시키고, body 또는 path
 * 조작으로 행위자 역할과 소속을 확장할 수 없는지 확인한다. 오류 응답의 correlation ID는
 * 진단에 사용할 수 있어야 하지만 bearer token이나 전체 claim은 노출하지 않아야 한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "scheduling.policy.shadow-compile-enabled=true",
        "scheduling.policy.effective-read-enabled=true",
        "scheduling.policy.admin-write-enabled=true",
        "scheduling.policy.idempotency-hash-secret=BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    ],
)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SchedulingPolicySecurityIntegrationTest {

    companion object {
        private const val TENANT_ID = 91L
        private const val TENANT_CODE = "policy-security-tenant"

        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
            if ("test-postgresql" in System.getProperty("spring.profiles.active", "")) {
                val postgres = Containers.Postgres
                registry.add("spring.datasource.url") { postgres.jdbcUrl }
                registry.add("spring.datasource.username") { postgres.username ?: "test" }
                registry.add("spring.datasource.password") { postgres.password ?: "" }
                registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
                registry.add("spring.flyway.enabled") { "true" }
            }
        }
    }

    @LocalServerPort
    private var port: Int = 0

    private val filter = CorrelationIdFilter()
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
                it[displayName] = "Policy Tenant"
                it[active] = true
            }
            clinicId = insertClinic("Policy Clinic")
            siblingClinicId = insertClinic("Sibling Clinic")
        }
    }

    @Test
    fun `bounded safe correlation id is preserved in request and response`() {
        val request = MockHttpServletRequest().apply {
            addHeader(CorrelationIdFilter.HEADER_NAME, "policy-flow-7")
        }
        val response = MockHttpServletResponse()
        var observed: String? = null

        filter.doFilter(
            request,
            response,
            FilterChain { filteredRequest, _ ->
                observed = filteredRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as String
            },
        )

        observed shouldBeEqualTo "policy-flow-7"
        response.getHeader(CorrelationIdFilter.HEADER_NAME) shouldBeEqualTo "policy-flow-7"
    }

    @Test
    fun `unsafe correlation id is replaced without reflecting attacker input`() {
        val attackerValue = "raw-token\\nclaim-secret"
        val request = MockHttpServletRequest().apply {
            addHeader(CorrelationIdFilter.HEADER_NAME, attackerValue)
        }
        val response = MockHttpServletResponse()
        var observed = ""

        filter.doFilter(
            request,
            response,
            FilterChain { filteredRequest, _ ->
                observed = filteredRequest.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE) as String
            },
        )

        (observed == attackerValue).shouldBeFalse()
        observed.matches(Regex("[0-9a-f-]{36}")).shouldBeTrue()
        response.getHeader(CorrelationIdFilter.HEADER_NAME) shouldBeEqualTo observed
    }

    @Test
    fun `request completion clears authentication installed by downstream filters`() {
        val downstreamPrincipal = SchedulingUserPrincipal(
            userId = "downstream-admin",
            clinicId = 7L,
            roles = listOf(SchedulingRole.ADMIN),
            allowedTenants = listOf("tenant-a"),
        )

        JwtAuthenticationFilter(mockk(relaxed = true)).doFilter(
            MockHttpServletRequest(),
            MockHttpServletResponse(),
            FilterChain { _, _ ->
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(
                        downstreamPrincipal,
                        null,
                        downstreamPrincipal.authorities,
                    )
            },
        )

        SecurityContextHolder.getContext().authentication.shouldBeNull()
    }

    @Test
    fun `authentication failure exposes only generic code and the established correlation id`() {
        val attackerToken = "raw-claim-secret.invalid.jwt"
        val response = client.get()
            .uri("/api/tenant-default/admin/scheduling-policies")
            .header(CorrelationIdFilter.HEADER_NAME, "policy-auth-7")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $attackerToken")
            .exchange { _, serverResponse ->
                AuthenticationFailureResponse(
                    status = serverResponse.statusCode.value(),
                    correlationId = serverResponse.headers.getFirst(CorrelationIdFilter.HEADER_NAME),
                    body = serverResponse.bodyTo(String::class.java).orEmpty(),
                )
            }

        response.status shouldBeEqualTo HttpStatus.UNAUTHORIZED.value()
        response.correlationId shouldBeEqualTo "policy-auth-7"
        response.body.contains("\"errorCode\":\"UNAUTHORIZED\"").shouldBeTrue()
        response.body.contains("\"correlationId\":\"policy-auth-7\"").shouldBeTrue()
        response.body.contains(attackerToken).shouldBeFalse()
        response.body.contains("raw-claim-secret").shouldBeFalse()
        response.body.contains("parser").shouldBeFalse()
    }

    @Test
    fun `policy matcher requires operator role capability tenant and exact clinic membership`() {
        policyRequest(
            "/api/$TENANT_CODE/admin/scheduling-policies/effective",
            token = null,
        ).status shouldBeEqualTo HttpStatus.UNAUTHORIZED.value()

        policyRequest(
            "/api/$TENANT_CODE/admin/scheduling-policies/effective",
            token = policyToken(scopes = emptySet()),
        ).assertPolicyForbidden()

        policyRequest(
            "/api/$TENANT_CODE/admin/scheduling-policies/effective",
            token = policyToken(
                roles = listOf(SchedulingRole.DOCTOR),
                actorType = ActorType.DOCTOR,
            ),
        ).assertPolicyForbidden()

        policyRequest(
            "/api/$TENANT_CODE/admin/scheduling-policies/effective",
            token = policyToken(allowedTenants = listOf("other-tenant")),
        ).assertPolicyForbidden()

        // 권위 정책이 없는 503 controller 응답에 도달했다는 것은 broad ADMIN matcher보다
        // 앞의 ADMIN|STAFF + policy:write matcher를 통과했다는 뜻이다.
        val tenantAllowed = policyRequest(
            "/api/$TENANT_CODE/admin/scheduling-policies/effective",
            token = policyToken(),
        )
        tenantAllowed.status shouldBeEqualTo HttpStatus.SERVICE_UNAVAILABLE.value()
        tenantAllowed.body.contains("\"errorCode\":\"POLICY_EFFECTIVE_READ_UNAVAILABLE\"").shouldBeTrue()

        val clinicAllowed = policyRequest(
            "/api/$TENANT_CODE/admin/clinics/$clinicId/scheduling-policies/effective",
            token = policyToken(allowedClinicIds = setOf(clinicId)),
        )
        clinicAllowed.status shouldBeEqualTo HttpStatus.SERVICE_UNAVAILABLE.value()
        clinicAllowed.body.contains("\"errorCode\":\"POLICY_EFFECTIVE_READ_UNAVAILABLE\"").shouldBeTrue()

        policyRequest(
            "/api/$TENANT_CODE/admin/clinics/$siblingClinicId/scheduling-policies/effective",
            token = policyToken(allowedClinicIds = setOf(clinicId)),
        ).assertPolicyForbidden()
    }

    private fun insertClinic(name: String): Long =
        Clinics.insertAndGetId {
            it[tenantGroupId] = EntityID(TENANT_ID, TenantGroups)
            it[Clinics.name] = name
        }.value

    private fun policyRequest(path: String, token: String?): AuthenticationFailureResponse =
        client.get()
            .uri("$path?decisionAt=2026-07-28T00:00:00Z&serviceAt=2026-07-28T01:00:00Z")
            .header(CorrelationIdFilter.HEADER_NAME, "policy-security")
            .apply {
                if (token != null) {
                    header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                }
            }
            .exchange { _, serverResponse ->
                AuthenticationFailureResponse(
                    status = serverResponse.statusCode.value(),
                    correlationId = serverResponse.headers.getFirst(CorrelationIdFilter.HEADER_NAME),
                    body = serverResponse.bodyTo(String::class.java).orEmpty(),
                )
            }

    private fun policyToken(
        roles: List<String> = listOf(SchedulingRole.STAFF),
        actorType: ActorType = ActorType.STAFF,
        allowedTenants: List<String> = listOf(TENANT_CODE),
        allowedClinicIds: Set<Long> = emptySet(),
        scopes: Set<String> = setOf("policy:write"),
    ): String =
        TestJwtProvider.createToken(
            userId = "policy-operator",
            clinicId = allowedClinicIds.singleOrNull(),
            roles = roles,
            actorType = actorType,
            allowedTenants = allowedTenants,
            allowedClinicIds = allowedClinicIds,
            scopes = scopes,
        )

    private fun AuthenticationFailureResponse.assertPolicyForbidden() {
        status shouldBeEqualTo HttpStatus.FORBIDDEN.value()
        body.contains("\"errorCode\":\"POLICY_ACTOR_FORBIDDEN\"").shouldBeTrue()
        body.contains("\"retryable\":false").shouldBeTrue()
        body.contains("\"correlationId\":\"policy-security\"").shouldBeTrue()
    }

    private data class AuthenticationFailureResponse(
        val status: Int,
        val correlationId: String?,
        val body: String,
    )
}

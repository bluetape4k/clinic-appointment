package io.bluetape4k.clinic.appointment.api.security

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.clinic.appointment.api.controller.execute
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.mockingDetails
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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * tenant path commitment API가 Gateway JWT envelope와 actor role을 controller 전에 강제하는지 검증한다.
 *
 * application bean은 Task 9 feature wiring 전에는 등록되지 않는다. 이 검사는 endpoint
 * 업무 결과가 아니라 Security filter가 누락된 envelope, 관리자 위장, 서비스 principal을
 * fail-closed로 거절하는 transport 경계만 검증한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "appointment.commitment.api-enabled=true",
        "appointment.commitment.idempotency-hash-secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    ],
)
@ActiveProfiles("test", "integration-test")
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AppointmentCommitmentSecurityIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun configureRedis(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { Containers.Redis.url }
        }

        const val CREATE_BODY =
            """{"appointmentPlanId":101,"preferredStartAt":"2026-08-01T01:00:00Z","preferredEndAt":"2026-08-01T02:00:00Z","evidence":{"evidenceAuthority":"tenant-default:consent-service","evidenceId":"ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7"}}"""
        const val TENANT_OTHER = "issue38-other"
        const val TENANT_INACTIVE = "issue38-inactive"
        const val TENANT_DEFAULT = "tenant-default"
        val TENANT_VARIANT_CODES = listOf(TENANT_OTHER, TENANT_INACTIVE)
    }

    @MockitoBean
    private lateinit var appointmentCommitmentApplicationService: AppointmentCommitmentApplicationService

    @LocalServerPort
    private var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun seedTenantVariants() {
        transaction {
            if (TenantGroups.selectAll().where { TenantGroups.tenantCode eq TENANT_DEFAULT }.empty()) {
                TenantGroups.insert {
                    it[TenantGroups.id] = EntityID(38_000L, TenantGroups)
                    it[TenantGroups.tenantCode] = TENANT_DEFAULT
                    it[TenantGroups.displayName] = "Issue 38 Default Tenant"
                    it[TenantGroups.active] = true
                }
            }
            TENANT_VARIANT_CODES.forEach { code ->
                TenantGroups.deleteWhere { TenantGroups.tenantCode eq code }
            }
            TenantGroups.insert {
                it[TenantGroups.id] = EntityID(38_001L, TenantGroups)
                it[TenantGroups.tenantCode] = TENANT_OTHER
                it[TenantGroups.displayName] = "Issue 38 Other Tenant"
                it[TenantGroups.active] = true
            }
            TenantGroups.insert {
                it[TenantGroups.id] = EntityID(38_002L, TenantGroups)
                it[TenantGroups.tenantCode] = TENANT_INACTIVE
                it[TenantGroups.displayName] = "Issue 38 Inactive Tenant"
                it[TenantGroups.active] = false
            }
        }
    }

    @AfterEach
    fun cleanupTenantVariants() {
        transaction {
            TENANT_VARIANT_CODES.forEach { code ->
                TenantGroups.deleteWhere { TenantGroups.tenantCode eq code }
            }
        }
    }

    @Test
    fun `missing Gateway envelope is unauthorized`() {
        val response = request("/api/tenant-default/appointment-requests", token = null)

        response.statusCode shouldBeEqualTo HttpStatus.UNAUTHORIZED
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "UNAUTHORIZED"
        response.jsonPath<String>("$.correlationId").isNotBlank().shouldBeTrue()
    }

    @Test
    fun `reserved and non canonical tenant paths fail before JWT authorization`() {
        val validToken = token(SchedulingRole.PATIENT)

        listOf(
            "/api/v2/appointment-requests",
            "/api/Tenant-A/appointment-requests",
        ).forEach { path ->
            val response = request(path, validToken)

            response.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "RESOURCE_NOT_FOUND"
        }
    }

    @Test
    fun `workforce and service principals cannot impersonate a patient request`() {
        listOf(
            TestJwtProvider.adminToken(clinicId = 7L),
            TestJwtProvider.createToken(
                userId = "scheduling-service",
                clinicId = 7L,
                roles = listOf(SchedulingRole.SYSTEM),
                actorType = ActorType.SYSTEM,
                allowedClinicIds = setOf(7L),
                assurance = AuthenticationAssurance.SERVICE,
            ),
        ).forEach { token ->
            val response = request("/api/tenant-default/appointment-requests", token)
            response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "SCOPE_FORBIDDEN"
        }
    }

    @Test
    fun `patient and service principals cannot invoke administrator creation`() {
        val patientToken = TestJwtProvider.createToken(
            userId = "patient-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedClinicIds = setOf(7L),
            patientSubjectId = "patient-subject-7",
        )
        val systemToken = TestJwtProvider.createToken(
            userId = "scheduling-service",
            clinicId = 7L,
            roles = listOf(SchedulingRole.SYSTEM),
            actorType = ActorType.SYSTEM,
            allowedClinicIds = setOf(7L),
            assurance = AuthenticationAssurance.SERVICE,
        )

        listOf(patientToken, systemToken).forEach { token ->
            val response = request("/api/tenant-default/admin/appointments", token)
            response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "SCOPE_FORBIDDEN"
        }
    }

    @Test
    fun `tenant-aware commitment matchers grant patient ingress before generic tenant writes`() {
        val patientToken = TestJwtProvider.createToken(
            userId = "patient-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedClinicIds = setOf(7L),
            patientSubjectId = "patient-subject-7",
        )
        val response = request(
            path = "/api/tenant-default/appointment-requests",
            token = patientToken,
        )

// DTO/header validation이 client error를 만들기 전에 Authorization을 통과해야 한다.
        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `tenant-aware commitment matchers keep staff out of administrator mutations`() {
        val staffToken = TestJwtProvider.createToken(
            userId = "staff-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.STAFF),
            actorType = ActorType.STAFF,
            allowedClinicIds = setOf(7L),
        )
        val response = request(
            path = "/api/tenant-default/appointments/7/approve",
            token = staffToken,
        )

        response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "SCOPE_FORBIDDEN"
    }

    @Test
    fun `all commitment routes reject a role outside their explicit matrix`() {
        val deniedRequests = listOf(
            request("/api/tenant-default/appointment-requests", token(SchedulingRole.ADMIN)),
            request("/api/tenant-default/admin/appointments", token(SchedulingRole.PATIENT)),
            request("/api/tenant-default/appointments/7/approve", token(SchedulingRole.STAFF)),
            request("/api/tenant-default/appointments/7/confirm", token(SchedulingRole.STAFF)),
            request("/api/tenant-default/appointments/7/proposals/31/expire", token(SchedulingRole.STAFF)),
            request(
                "/api/tenant-default/appointments/7/cancel",
                TestJwtProvider.createToken(
                    userId = "scheduling-service",
                    clinicId = 7L,
                    roles = listOf(SchedulingRole.SYSTEM),
                    actorType = ActorType.SYSTEM,
                    allowedTenants = listOf(TENANT_DEFAULT),
                    allowedClinicIds = setOf(7L),
                    assurance = AuthenticationAssurance.SERVICE,
                ),
            ),
            request("/api/tenant-default/appointments/7/change-proposals", token(SchedulingRole.STAFF)),
            request("/api/tenant-default/appointments/7/proposals/31/accept", token(SchedulingRole.ADMIN)),
            request("/api/tenant-default/appointments/7/proposals/31/decline", token(SchedulingRole.ADMIN)),
        )

        deniedRequests.forEach { response ->
            response.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
            response.jsonPath<String>("$.errorCode") shouldBeEqualTo "SCOPE_FORBIDDEN"
        }
    }

    @Test
    fun `each allowed commitment route reaches its application service`() {
        request(
            "/api/tenant-default/appointment-requests",
            token(SchedulingRole.PATIENT),
            body = CREATE_BODY,
            extraHeaders = creationHeaders("request"),
        )
        request(
            "/api/tenant-default/admin/appointments",
            token(SchedulingRole.ADMIN),
            body = CREATE_BODY,
            extraHeaders = creationHeaders("direct"),
        )
        request(
            "/api/tenant-default/appointments/7/approve",
            token(SchedulingRole.ADMIN),
            body = "{\"proposalId\":31}",
            extraHeaders = mutationHeaders("approve"),
        )
        request(
            "/api/tenant-default/appointments/7/confirm",
            token(SchedulingRole.ADMIN),
            body = "{\"proposalId\":31,\"evidence\":{\"evidenceAuthority\":\"tenant-default:consent-service\",\"evidenceId\":\"ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7\"}}",
            extraHeaders = mutationHeaders("confirm"),
        )
        request(
            "/api/tenant-default/appointments/7/proposals/31/expire",
            token(SchedulingRole.ADMIN),
            extraHeaders = mutationHeaders("expire"),
        )
        request(
            "/api/tenant-default/appointments/7/cancel",
            token(SchedulingRole.ADMIN),
            body = "{\"reasonCode\":\"CUSTOMER_REQUEST\"}",
            extraHeaders = mutationHeaders("cancel"),
        )
        request(
            "/api/tenant-default/appointments/7/change-proposals",
            token(SchedulingRole.ADMIN),
            body = "{\"preferredStartAt\":\"2026-08-01T01:00:00Z\",\"preferredEndAt\":\"2026-08-01T02:00:00Z\"}",
            extraHeaders = mutationHeaders("change"),
        )
        request(
            "/api/tenant-default/appointments/7/proposals/31/accept",
            token(SchedulingRole.PATIENT),
            body = "{\"evidence\":{\"evidenceAuthority\":\"tenant-default:consent-service\",\"evidenceId\":\"ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7\"}}",
            extraHeaders = mutationHeaders("accept"),
        )
        request(
            "/api/tenant-default/appointments/7/proposals/31/decline",
            token(SchedulingRole.PATIENT),
            body = "{\"reasonCode\":\"SCHEDULE_NOT_ACCEPTED\"}",
            extraHeaders = mutationHeaders("decline"),
        )
        getRequest("/api/tenant-default/appointments/7/commitment", token(SchedulingRole.PATIENT))
        getRequest("/api/tenant-default/appointments/7/commitment", token(SchedulingRole.STAFF))

        val invokedMethods = mockingDetails(appointmentCommitmentApplicationService)
            .invocations
            .map { it.method.name }
            .toSet()
        listOf(
            "requestAppointment",
            "directCreate",
            "approveProposal",
            "directConfirm",
            "expireProposal",
            "cancelAppointment",
            "createChangeProposal",
            "decideProposal",
            "declineProposal",
            "query",
        ).forEach { method -> invokedMethods.contains(method).shouldBeTrue() }
    }

    @Test
    fun `tenant filter distinguishes membership, inactive, and missing tenant`() {
        val defaultOnlyToken = token(SchedulingRole.PATIENT)
        val allowedBothToken = token(
            SchedulingRole.PATIENT,
            allowedTenants = listOf("tenant-default", TENANT_OTHER, TENANT_INACTIVE),
        )

        val membershipDenied = request(
            "/api/$TENANT_OTHER/appointment-requests",
            defaultOnlyToken,
        )
        membershipDenied.statusCode shouldBeEqualTo HttpStatus.FORBIDDEN
        membershipDenied.jsonPath<String>("$.errorCode") shouldBeEqualTo "FORBIDDEN"

        val inactive = request(
            "/api/$TENANT_INACTIVE/appointment-requests",
            allowedBothToken,
        )
        inactive.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        inactive.jsonPath<String>("$.errorCode") shouldBeEqualTo "RESOURCE_NOT_FOUND"

        val missing = request(
            "/api/issue38-missing/appointment-requests",
            allowedBothToken,
        )
        missing.statusCode shouldBeEqualTo HttpStatus.NOT_FOUND
        missing.jsonPath<String>("$.errorCode") shouldBeEqualTo "RESOURCE_NOT_FOUND"
    }

    @Test
    fun `multi tenant principal selects the path tenant before service scope`() {
        request(
            "/api/$TENANT_OTHER/appointment-requests",
            token(
                SchedulingRole.PATIENT,
                allowedTenants = listOf("tenant-default", TENANT_OTHER),
            ),
            body = CREATE_BODY.replace("tenant-default", TENANT_OTHER),
            extraHeaders = creationHeaders("other"),
        )

        val invocation = mockingDetails(appointmentCommitmentApplicationService)
            .invocations
            .single { it.method.name == "requestAppointment" }
        (invocation.arguments[0] as ActorContext).selectedTenantCode shouldBeEqualTo TENANT_OTHER
    }

    @Test
    fun `request body cannot inject actor tenant clinic or policy fields`() {
        val patientToken = TestJwtProvider.createToken(
            userId = "patient-user",
            clinicId = 7L,
            roles = listOf(SchedulingRole.PATIENT),
            actorType = ActorType.PATIENT,
            allowedClinicIds = setOf(7L),
            patientSubjectId = "patient-subject-7",
        )
        val response = request(
            path = "/api/tenant-default/appointment-requests",
            token = patientToken,
            body = """
                {
                  "appointmentPlanId": 101,
                  "preferredStartAt": "2026-08-01T01:00:00Z",
                  "preferredEndAt": "2026-08-01T02:00:00Z",
                  "actorId": "forged-admin",
                  "clinicId": 999,
                  "policyMode": "DIRECT_CONFIRM",
                  "evidence": {
                    "evidenceAuthority": "tenant-default:consent-service",
                    "evidenceId": "ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7"
                  }
                }
            """.trimIndent(),
            extraHeaders = mapOf(
                "Idempotency-Key" to "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                HttpHeaders.IF_NONE_MATCH to "*",
            ),
        )

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
        response.jsonPath<String>("$.errorCode") shouldBeEqualTo "PAYLOAD_INVALID"
    }

    @Test
    fun `OpenAPI publishes provisional consent expiry conflict and ETag mutation contract`() {
        val content = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        val root = mapper.readTree(content)

        content shouldContain "/api/{tenantCode}/appointment-requests"
        content shouldContain "/api/{tenantCode}/appointments/{id}/approve"
        content shouldContain "/api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept"
        content shouldContain "PROPOSED"
        content shouldContain "expired"
        content shouldContain "conflict"

        assertRequiredHeaders(
            root,
            "/api/{tenantCode}/appointment-requests",
            "Idempotency-Key",
            HttpHeaders.IF_NONE_MATCH,
        )
        assertRequiredHeaders(
            root,
            "/api/{tenantCode}/admin/appointments",
            "Idempotency-Key",
            HttpHeaders.IF_NONE_MATCH,
        )
        listOf(
            "/api/{tenantCode}/appointments/{id}/approve",
            "/api/{tenantCode}/appointments/{id}/confirm",
            "/api/{tenantCode}/appointments/{id}/change-proposals",
            "/api/{tenantCode}/appointments/{id}/cancel",
            "/api/{tenantCode}/appointments/{id}/proposals/{proposalId}/expire",
            "/api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept",
            "/api/{tenantCode}/appointments/{id}/proposals/{proposalId}/decline",
        ).forEach { path ->
            assertRequiredHeaders(root, path, "Idempotency-Key", HttpHeaders.IF_MATCH)
        }

        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointment-requests",
            successCode = "202",
            expectedErrorCodes = listOf("400", "401", "403", "409", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/admin/appointments",
            successCode = "201",
            expectedErrorCodes = listOf("400", "401", "403", "409", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointments/{id}/approve",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointments/{id}/confirm",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointments/{id}/change-proposals",
            successCode = "202",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "422", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointments/{id}/proposals/{proposalId}/decline",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "409", "410", "412", "428", "500"),
        )
        assertErrorResponses(
            root,
            "/api/{tenantCode}/appointments/{id}/commitment",
            successCode = "200",
            expectedErrorCodes = listOf("400", "401", "403", "404", "500"),
            method = "get",
        )

        val errorProperties = root.at("/components/schemas/SchedulingApiErrorResponse/properties")
        listOf("errorCode", "correlationId", "retryable", "action").forEach { property ->
            errorProperties.has(property).shouldBeTrue()
        }
    }

    /**
     * Spring MVC에서는 필수 header 누락을 공통 `428`로 정규화하기 위해 nullable로 받지만,
     * 생성된 OpenAPI는 caller가 실행 전에 계약을 알 수 있도록 필수 header로 선언해야 한다.
     */
    private fun assertRequiredHeaders(
        root: JsonNode,
        path: String,
        vararg expectedHeaders: String,
    ) {
        val parameters = root.at("/paths/${pointer(path)}/post/parameters")
            .associateBy { it.path("name").stringValue() }
        expectedHeaders.forEach { header ->
            val parameter = requireNotNull(parameters[header]) { "$path must publish $header" }
            parameter.path("in").stringValue() shouldBeEqualTo "header"
            parameter.path("required").asBoolean().shouldBeTrue()
        }
    }

    /**
     * 각 operation이 안정 오류 code뿐 아니라 실제 공통 오류 envelope schema까지
     * 연결하는지 검증합니다. 문서상 status만 있고 body 계약이 빠지는 회귀를 막습니다.
     */
    private fun assertErrorResponses(
        root: JsonNode,
        path: String,
        successCode: String,
        expectedErrorCodes: List<String>,
        method: String = "post",
    ) {
        val responses = root.at("/paths/${pointer(path)}/$method/responses")
        responses.has(successCode).shouldBeTrue()
        expectedErrorCodes.forEach { responseCode ->
            val response = responses.path(responseCode)
            response.isMissingNode.shouldBeEqualTo(false)
            response
                .at("/content/application~1json/schema/\$ref")
                .stringValue() shouldBeEqualTo "#/components/schemas/SchedulingApiErrorResponse"
        }
    }

    /** OpenAPI 경로 키에 적용하는 RFC 6901 JSON Pointer 세그먼트 이스케이프. */
    private fun pointer(value: String): String =
        value.replace("~", "~0").replace("/", "~1")

    private fun request(
        path: String,
        token: String?,
        body: String = "{}",
        extraHeaders: Map<String, String> = emptyMap(),
    ) = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .build()
        .post()
        .uri(path)
        .apply {
            if (token != null) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }
            extraHeaders.forEach(::header)
        }
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .execute()

    private fun getRequest(
        path: String,
        token: String?,
    ) = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .build()
        .get()
        .uri(path)
        .apply {
            if (token != null) {
                header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }
        }
        .execute()

    private fun token(
        role: String,
        allowedTenants: List<String> = listOf("tenant-default"),
    ): String =
        TestJwtProvider.createToken(
            userId = "issue38-${role.lowercase()}",
            clinicId = 7L,
            roles = listOf(role),
            actorType = when (role) {
                SchedulingRole.PATIENT -> ActorType.PATIENT
                SchedulingRole.ADMIN -> ActorType.ADMIN
                SchedulingRole.STAFF -> ActorType.STAFF
                else -> ActorType.SYSTEM
            },
            allowedTenants = allowedTenants,
            allowedClinicIds = setOf(7L),
            patientSubjectId = if (role == SchedulingRole.PATIENT) "patient-subject-7" else null,
        )

    private fun creationHeaders(prefix: String): Map<String, String> =
        mapOf(
            "Idempotency-Key" to "${prefix}_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            HttpHeaders.IF_NONE_MATCH to "*",
        )

    private fun mutationHeaders(prefix: String): Map<String, String> =
        mapOf(
            "Idempotency-Key" to "${prefix}_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            HttpHeaders.IF_MATCH to "\"3\"",
        )

}

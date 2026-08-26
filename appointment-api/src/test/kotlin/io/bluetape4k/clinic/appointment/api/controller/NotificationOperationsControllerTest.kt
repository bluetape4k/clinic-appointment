package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyAuditSink
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyApprovalVerifier
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyEligibility
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyEligibilityPort
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyEnqueuePort
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyEnqueueResult
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyRateLimiter
import io.bluetape4k.clinic.appointment.api.notification.NotificationReNotifyService
import io.bluetape4k.clinic.appointment.api.notification.ApprovalReference
import io.bluetape4k.clinic.appointment.api.notification.VerifiedNotificationApproval
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.TestJwtProvider
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.notification.NotificationRecommendedAction
import io.bluetape4k.clinic.appointment.notification.NotificationStatusQueryService
import io.bluetape4k.clinic.appointment.notification.NotificationStatusQueryStore
import io.bluetape4k.clinic.appointment.notification.NotificationStatusSnapshot
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.client.RestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test", "integration-test")
@Import(NotificationOperationsControllerTest.NotificationOperationsTestConfig::class)
class NotificationOperationsControllerTest : AbstractApiIntegrationTest() {

    @LocalServerPort
    private var port: Int = 0

    private lateinit var client: RestClient
    private var clinicId: Long = 0

    @BeforeEach
    fun setUp() {
        client = RestClient.builder().baseUrl("http://localhost:$port").build()
        transaction {
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = 1L
                it[name] = "Notification Operations Clinic"
            }.value
        }
    }

    @Test
    fun `status endpoint exposes only safe operational fields`() {
        val response = client.get()
            .uri("/api/tenant-default/clinics/$clinicId/notifications/appointments/777/status")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${readToken()}")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<String>("$.data.status") shouldBeEqualTo "RETRY_WAIT"
        response.jsonPath<String>("$.data.reasonCode") shouldBeEqualTo "PROVIDER_UNAVAILABLE"
        response.jsonPath<String>("$.data.recommendedAction") shouldBeEqualTo NotificationRecommendedAction.WAIT_FOR_RETRY.name
        response.jsonPath<Boolean>("$.data.patientVisible").shouldBeTrue()
        response.body.contains("member").shouldBeFalse()
        response.body.contains("outbox").shouldBeFalse()
        response.body.contains("attempt").shouldBeFalse()
        response.body.contains("providerMessage").shouldBeFalse()
    }

    @Test
    fun `patient audience hides operational suppression and provider details`() {
        val response = client.get()
            .uri("/api/tenant-default/clinics/$clinicId/notifications/appointments/777/status?audience=PATIENT")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${readToken()}")
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<String>("$.data.status") shouldBeEqualTo "RETRY_WAIT"
        response.jsonPath<String?>("$.data.reasonCode") shouldBeEqualTo null
        response.jsonPath<String>("$.data.recommendedAction") shouldBeEqualTo NotificationRecommendedAction.NONE.name
    }

    @Test
    fun `re notify endpoint validates scope and returns bounded aggregate`() {
        val body = """
            {
              "appointmentIds": [1, 2],
              "generation": "gen-1",
              "platformApproval": {"authority": "platform-service-approval", "reference": "PLAT-1"},
              "clinicApproval": {"authority": "clinic-mfa-approval", "reference": "CLINIC-1"},
              "dryRun": false
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/tenant-default/clinics/$clinicId/notifications/re-notify")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${operatorToken()}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.jsonPath<String>("$.data.generation") shouldBeEqualTo "gen-1"
        response.jsonPath<Number>("$.data.requestedCount").toInt() shouldBeEqualTo 2
        response.jsonPath<Number>("$.data.acceptedCount").toInt() shouldBeEqualTo 2
        response.body.contains("appointmentIds").shouldBeFalse()
    }

    @Test
    fun `re notify endpoint rejects duplicate appointment IDs before normalization`() {
        val body = """
            {
              "appointmentIds": [1, 1],
              "generation": "gen-duplicate",
              "platformApproval": {"authority": "platform-service-approval", "reference": "PLAT-1"},
              "clinicApproval": {"authority": "clinic-mfa-approval", "reference": "CLINIC-1"},
              "dryRun": true
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/tenant-default/clinics/$clinicId/notifications/re-notify")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${operatorToken()}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    @Test
    fun `re notify endpoint rejects non-positive appointment IDs`() {
        val body = """
            {
              "appointmentIds": [-1],
              "generation": "gen-invalid-id",
              "platformApproval": {"authority": "platform-service-approval", "reference": "PLAT-1"},
              "clinicApproval": {"authority": "clinic-mfa-approval", "reference": "CLINIC-1"},
              "dryRun": true
            }
        """.trimIndent()

        val response = client.post()
            .uri("/api/tenant-default/clinics/$clinicId/notifications/re-notify")
            .header(HttpHeaders.AUTHORIZATION, "Bearer ${operatorToken()}")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .execute()

        response.statusCode shouldBeEqualTo HttpStatus.BAD_REQUEST
    }

    private fun operatorToken(): String =
        TestJwtProvider.createToken(
            userId = "notification-platform-service",
            clinicId = clinicId,
            roles = listOf(SchedulingRole.SYSTEM),
            actorType = ActorType.SYSTEM,
            allowedClinicIds = setOf(clinicId),
            scopes = setOf("notification:renotify"),
            assurance = AuthenticationAssurance.SERVICE,
        )

    private fun readToken(): String =
        TestJwtProvider.createToken(
            userId = "notification-reader",
            clinicId = clinicId,
            roles = listOf(SchedulingRole.STAFF),
            allowedClinicIds = setOf(clinicId),
            scopes = setOf("notification:read"),
        )

    @TestConfiguration(proxyBeanMethods = false)
    class NotificationOperationsTestConfig {
        @Bean
        fun notificationStatusQueryService(): NotificationStatusQueryService =
            NotificationStatusQueryService(
                NotificationStatusQueryStore {
                    NotificationStatusSnapshot(
                        status = NotificationOutboxStatus.RETRY_WAIT,
                        failureCode = io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode.PROVIDER_UNAVAILABLE,
                        nextAttemptAt = java.time.Instant.parse("2026-07-31T01:00:00Z"),
                    )
                }
            )

        @Bean
        fun notificationReNotifyService(): NotificationReNotifyService =
            NotificationReNotifyService(
                eligibilityPort = NotificationReNotifyEligibilityPort { command ->
                    command.appointmentIds.map(NotificationReNotifyEligibility::accept)
                },
                enqueuePort = NotificationReNotifyEnqueuePort { command, accepted ->
                    NotificationReNotifyEnqueueResult(command.generation, accepted.size, resumed = false)
                },
                auditSink = NotificationReNotifyAuditSink {},
                approvalVerifier = object : NotificationReNotifyApprovalVerifier {
                    override suspend fun verifyPlatform(
                        executor: SchedulingUserPrincipal,
                        reference: ApprovalReference,
                    ): VerifiedNotificationApproval =
                        VerifiedNotificationApproval(
                            subjectReference = executor.userId,
                            actorType = ActorType.SYSTEM,
                            assurance = AuthenticationAssurance.SERVICE,
                            roles = setOf(SchedulingRole.SYSTEM),
                            allowedClinicIds = setOf(executor.allowedClinicIds.single()),
                        )

                    override suspend fun verifyClinic(
                        tenantGroupId: Long,
                        clinicId: Long,
                        reference: ApprovalReference,
                    ): VerifiedNotificationApproval =
                        VerifiedNotificationApproval(
                            subjectReference = "clinic-approver",
                            actorType = ActorType.STAFF,
                            assurance = AuthenticationAssurance.MFA,
                            roles = setOf(SchedulingRole.STAFF),
                            allowedClinicIds = setOf(clinicId),
                        )
                },
                rateLimiter = NotificationReNotifyRateLimiter {},
            )
    }
}

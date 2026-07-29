package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.ApproveProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ConsentEvidenceRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.DirectCreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentApplicationService
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Instant

/**
 * 관리자용 commitment v2 계약이 Gateway actor와 HTTP precondition을 service 경계에
 * 명시하면서도 이를 body DTO에는 복제하지 않는지 검증한다.
 */
class AdminAppointmentV2Test {

    @Test
    fun `application service keeps actor idempotency and precondition as explicit parameters`() {
        val method = AppointmentCommitmentApplicationService::class.java.methods
            .single { it.name == "approveProposal" }

        method.parameterTypes.map(Class<*>::getSimpleName) shouldBeEqualTo listOf(
            "ActorContext",
            "long",
            "long",
            "String",
            "ApproveProposalRequest",
        )
    }

    @Test
    fun `admin request bodies cannot choose tenant clinic policy or resource mapping`() {
        val fields = sequenceOf(
            DirectCreateAppointmentRequest::class.java,
            ApproveProposalRequest::class.java,
        ).flatMap { type ->
            type.declaredFields.asSequence()
                .map { it.name }
                .filterNot { it.startsWith("$") || it == "Companion" }
        }.toSet()

        fields.intersect(FORBIDDEN_BODY_FIELDS) shouldBeEqualTo emptySet()
        fields shouldContain "appointmentPlanId"
        fields shouldContain "proposalId"
    }

    @Test
    fun `admin approval forwards If-Match version and returns result ETag`() {
        val service = FakeAppointmentCommitmentApplicationService()
        val controller = AdminAppointmentV2Controller(service, ActorContextResolver())

        val response = controller.approveProposal(
            authentication = authentication(adminPrincipal()),
            servletRequest = MockHttpServletRequest(),
            id = 11L,
            idempotencyKey = "approval_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            ifMatch = "\"1\"",
            request = ApproveProposalRequest(31L),
        )

        response.statusCode shouldBeEqualTo HttpStatus.OK
        response.headers.getFirst(HttpHeaders.ETAG) shouldBeEqualTo "\"2\""
        service.lastExpectedVersion shouldBeEqualTo 1L
        service.lastActor.shouldNotBeNull().allowedClinicIds shouldContain 7L
    }

    @Test
    fun `rollback closes only direct creation while existing administrator mutation stays available`() {
        val service = FakeAppointmentCommitmentApplicationService()
        val controller = AdminAppointmentV2Controller(
            service,
            ActorContextResolver(),
            ingressEnabled = false,
        )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            controller.directCreate(
                authentication = authentication(adminPrincipal()),
                servletRequest = MockHttpServletRequest(),
                idempotencyKey = "direct_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                ifNoneMatch = "*",
                request = DirectCreateAppointmentRequest(
                    appointmentPlanId = 101L,
                    preferredStartAt = Instant.parse("2026-08-01T01:00:00Z"),
                    preferredEndAt = Instant.parse("2026-08-01T02:00:00Z"),
                    evidence = ConsentEvidenceRequest(
                        evidenceAuthority = "tenant-a:consent-service",
                        evidenceId = "ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                    ),
                ),
            )
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.INGRESS_DISABLED

        val existingMutation = controller.approveProposal(
            authentication = authentication(adminPrincipal()),
            servletRequest = MockHttpServletRequest(),
            id = 11L,
            idempotencyKey = "approval_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            ifMatch = "\"1\"",
            request = ApproveProposalRequest(31L),
        )

        existingMutation.statusCode shouldBeEqualTo HttpStatus.OK
        service.lastExpectedVersion shouldBeEqualTo 1L
    }

    @Test
    fun `mutation accepts only a quoted strong ETag`() {
        requireExpectedVersion("\"3\"") shouldBeEqualTo 3L

        listOf("3", "W/\"3\"", "\"3\", \"4\"", "\"0\"").forEach { invalid ->
            val exception = assertFailsWith<AppointmentCommitmentApiException> {
                requireExpectedVersion(invalid)
            }
            exception.error shouldBeEqualTo AppointmentCommitmentApiError.PRECONDITION_REQUIRED
        }
    }

    @Test
    fun `service principal cannot invoke administrator booking`() {
        val controller = AdminAppointmentV2Controller(
            FakeAppointmentCommitmentApplicationService(),
            ActorContextResolver(),
        )
        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            controller.directCreate(
                authentication = authentication(systemPrincipal()),
                servletRequest = MockHttpServletRequest(),
                idempotencyKey = "direct_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                ifNoneMatch = "*",
                request = DirectCreateAppointmentRequest(
                    appointmentPlanId = 101L,
                    preferredStartAt = Instant.parse("2026-08-01T01:00:00Z"),
                    preferredEndAt = Instant.parse("2026-08-01T02:00:00Z"),
                    evidence = ConsentEvidenceRequest(
                        evidenceAuthority = "tenant-a:consent-service",
                        evidenceId = "ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                    ),
                ),
            )
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_FORBIDDEN
    }

    @Test
    fun `administrator booking rejects ambiguous clinic scope`() {
        val controller = AdminAppointmentV2Controller(
            FakeAppointmentCommitmentApplicationService(),
            ActorContextResolver(),
        )
        val principal = adminPrincipal().copy(
            clinicId = null,
            allowedClinicIds = setOf(7L, 8L),
        )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            controller.approveProposal(
                authentication = authentication(principal),
                servletRequest = MockHttpServletRequest(),
                id = 11L,
                idempotencyKey = "approval_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                ifMatch = "\"1\"",
                request = ApproveProposalRequest(31L),
            )
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_MISMATCH
    }

    @Test
    fun `administrator booking uses the gateway selected clinic within a multi clinic grant`() {
        val service = FakeAppointmentCommitmentApplicationService()
        val controller = AdminAppointmentV2Controller(service, ActorContextResolver())
        val principal = adminPrincipal().copy(
            clinicId = 7L,
            allowedClinicIds = setOf(7L, 8L),
        )

        val response = controller.approveProposal(
            authentication = authentication(principal),
            servletRequest = MockHttpServletRequest(),
            id = 11L,
            idempotencyKey = "approval_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            ifMatch = "\"1\"",
            request = ApproveProposalRequest(31L),
        )

        response.statusCode shouldBeEqualTo HttpStatus.OK
        service.lastActor.shouldNotBeNull().allowedClinicIds shouldContain 7L
        service.lastActor.shouldNotBeNull().selectedClinicId shouldBeEqualTo 7L
    }

    private fun authentication(principal: SchedulingUserPrincipal) =
        UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

    private fun adminPrincipal() = SchedulingUserPrincipal(
        userId = "admin-actor-7",
        clinicId = 7L,
        roles = setOf(SchedulingRole.ADMIN),
        allowedTenants = setOf("tenant-a"),
        actorType = ActorType.ADMIN,
        allowedClinicIds = setOf(7L),
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-admin-7",
        authenticatedAt = Instant.parse("2026-07-29T00:00:00Z"),
    )

    private fun systemPrincipal() = SchedulingUserPrincipal(
        userId = "system-actor",
        clinicId = 7L,
        roles = setOf(SchedulingRole.SYSTEM),
        allowedTenants = setOf("tenant-a"),
        actorType = ActorType.SYSTEM,
        allowedClinicIds = setOf(7L),
        assurance = AuthenticationAssurance.SERVICE,
        issuer = "appointment-auth-service",
        tokenId = "token-system-7",
        authenticatedAt = Instant.parse("2026-07-29T00:00:00Z"),
    )

    private companion object {
        val FORBIDDEN_BODY_FIELDS = setOf(
            "actor",
            "actorId",
            "actorType",
            "actorAuditRef",
            "tenantCode",
            "tenantGroupId",
            "clinicId",
            "patientId",
            "patientSubjectId",
            "expectedVersion",
            "idempotencyKey",
            "createOnly",
            "policyMode",
            "allowedEvidenceTypes",
            "termsHash",
            "doctorId",
            "treatmentTypeId",
            "practitionerResourceId",
        )
    }
}

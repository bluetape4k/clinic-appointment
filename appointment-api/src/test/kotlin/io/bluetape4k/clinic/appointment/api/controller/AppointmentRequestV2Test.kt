package io.bluetape4k.clinic.appointment.api.controller

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiError
import io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentApiException
import io.bluetape4k.clinic.appointment.api.dto.commitment.ConsentEvidenceRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CancelAppointmentRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.CreateAppointmentRequestV2
import io.bluetape4k.clinic.appointment.api.dto.commitment.DeclineProposalRequest
import io.bluetape4k.clinic.appointment.api.dto.commitment.ProposalDecisionRequest
import io.bluetape4k.clinic.appointment.api.security.ActorContextResolver
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.api.security.SchedulingRole
import io.bluetape4k.clinic.appointment.api.security.SchedulingUserPrincipal
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.time.Instant

/**
 * 고객용 commitment v2 요청 계약이 인증·정책 권위를 request body에 열지 않는지 검증한다.
 */
class AppointmentRequestV2Test {

    @Test
    fun `customer request body cannot forge actor scope patient or server policy`() {
        val publicFields = CreateAppointmentRequestV2::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" }
            .toSet()

        publicFields.intersect(FORBIDDEN_BODY_FIELDS) shouldBeEqualTo emptySet()
        publicFields shouldContain "appointmentPlanId"
        publicFields shouldContain "preferredStartAt"
        publicFields shouldContain "preferredEndAt"
    }

    @Test
    fun `customer acceptance body contains evidence reference but no actor or expected version`() {
        val publicFields = ProposalDecisionRequest::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") || it == "Companion" }
            .toSet()

        publicFields.intersect(FORBIDDEN_BODY_FIELDS) shouldBeEqualTo emptySet()
        publicFields shouldContain "evidence"
    }

    @Test
    fun `decline reason is a bounded code rather than free text`() {
        DeclineProposalRequest("SCHEDULE_NOT_ACCEPTED").reasonCode shouldBeEqualTo "SCHEDULE_NOT_ACCEPTED"

        assertFailsWith<IllegalArgumentException> {
            DeclineProposalRequest("환자 전화번호 010-1234-5678")
        }
    }

    @Test
    fun `operator cancellation detail is bounded and rejects control characters`() {
        CancelAppointmentRequest(
            reasonCode = "CUSTOMER_REQUEST",
            reasonDetail = "진료 일정이 변경되어 예약을 취소합니다.",
        ).reasonDetail shouldBeEqualTo "진료 일정이 변경되어 예약을 취소합니다."

        listOf(
            " ",
            "a".repeat(501),
            "환자\n요청",
            "환자\u0000요청",
        ).forEach { invalidDetail ->
            assertFailsWith<IllegalArgumentException> {
                CancelAppointmentRequest(
                    reasonCode = "CUSTOMER_REQUEST",
                    reasonDetail = invalidDetail,
                )
            }
        }
    }

    @Test
    fun `operator cancellation detail rejects patient medical and payment identifiers`() {
        listOf(
            "연락처는 010-1234-5678입니다.",
            "안내 email은 patient@example.com입니다.",
            "환자번호 A-1234",
            "진단명: 고혈압",
            "카드번호 4111 1111 1111 1111",
            "홍길동 환자의 고혈압으로 예약을 취소합니다.",
            "등록되지 않은 임의 안내 문구",
        ).forEach { sensitiveDetail ->
            assertFailsWith<IllegalArgumentException> {
                CancelAppointmentRequest(
                    reasonCode = "CUSTOMER_REQUEST",
                    reasonDetail = sensitiveDetail,
                )
            }
        }
    }

    @Test
    fun `cancellation reason must come from the closed registry`() {
        assertFailsWith<IllegalArgumentException> {
            CancelAppointmentRequest(reasonCode = "UNREGISTERED_REASON")
        }
    }

    @Test
    fun `patient request becomes proposed 202 and forwards only gateway actor and headers`() {
        val service = FakeAppointmentCommitmentApplicationService()
        val controller = CustomerAppointmentController(service, ActorContextResolver())

        val response = controller.requestAppointment(
            tenantCode = "tenant-a",
            authentication = authentication(patientPrincipal()),
            servletRequest = MockHttpServletRequest(),
            idempotencyKey = "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            ifNoneMatch = "*",
            request = createRequest(),
        )

        response.statusCode shouldBeEqualTo HttpStatus.ACCEPTED
        response.headers.getFirst(HttpHeaders.ETAG) shouldBeEqualTo "\"1\""
        response.body.shouldNotBeNull().status.name shouldBeEqualTo "PROPOSED"
        service.lastActor.shouldNotBeNull().patientSubjectId shouldBeEqualTo "patient-subject-7"
        service.lastActor.shouldNotBeNull().allowedClinicIds shouldContain 7L
        service.lastIdempotencyKey shouldBeEqualTo "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7"
    }

    @Test
    fun `rollback closes only new customer ingress while existing customer mutation stays available`() {
        val service = FakeAppointmentCommitmentApplicationService()
        val controller = CustomerAppointmentController(
            service,
            ActorContextResolver(),
            ingressEnabled = false,
        )

        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            controller.requestAppointment(
                tenantCode = "tenant-a",
                authentication = authentication(patientPrincipal()),
                servletRequest = MockHttpServletRequest(),
                idempotencyKey = "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                ifNoneMatch = "*",
                request = createRequest(),
            )
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.INGRESS_DISABLED

        val existingMutation = controller.acceptProposal(
            tenantCode = "tenant-a",
            authentication = authentication(patientPrincipal()),
            servletRequest = MockHttpServletRequest(),
            id = 11L,
            proposalId = 31L,
            idempotencyKey = "accept_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
            ifMatch = "\"1\"",
            request = ProposalDecisionRequest(
                ConsentEvidenceRequest(
                    evidenceAuthority = "tenant-a:consent-service",
                    evidenceId = "ev_01J1M6Y6XRK8N0W2M3P4Q5R6S8",
                ),
            ),
        )

        existingMutation.statusCode shouldBeEqualTo HttpStatus.OK
        service.lastExpectedVersion shouldBeEqualTo 1L
    }

    @Test
    fun `patient request rejects ambiguous clinic scope before application service`() {
        val controller = CustomerAppointmentController(
            FakeAppointmentCommitmentApplicationService(),
            ActorContextResolver(),
        )
        val exception = assertFailsWith<AppointmentCommitmentApiException> {
            controller.requestAppointment(
                tenantCode = "tenant-a",
                authentication = authentication(patientPrincipal(setOf(7L, 8L))),
                servletRequest = MockHttpServletRequest(),
                idempotencyKey = "request_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
                ifNoneMatch = "*",
                request = createRequest(),
            )
        }

        exception.error shouldBeEqualTo AppointmentCommitmentApiError.SCOPE_MISMATCH
    }

    private fun createRequest() = CreateAppointmentRequestV2(
        appointmentPlanId = 101L,
        preferredStartAt = Instant.parse("2026-08-01T01:00:00Z"),
        preferredEndAt = Instant.parse("2026-08-01T02:00:00Z"),
        evidence = ConsentEvidenceRequest(
            evidenceAuthority = "tenant-a:consent-service",
            evidenceId = "ev_01J1M6Y6XRK8N0W2M3P4Q5R6S7",
        ),
    )

    private fun authentication(principal: SchedulingUserPrincipal) =
        UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

    private fun patientPrincipal(
        clinics: Set<Long> = setOf(7L),
    ) = SchedulingUserPrincipal(
        userId = "patient-actor-7",
        clinicId = clinics.singleOrNull(),
        roles = setOf(SchedulingRole.PATIENT),
        allowedTenants = setOf("tenant-a"),
        actorType = ActorType.PATIENT,
        allowedClinicIds = clinics,
        patientSubjectId = "patient-subject-7",
        assurance = AuthenticationAssurance.MFA,
        issuer = "appointment-auth-service",
        tokenId = "token-patient-7",
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
            "memberId",
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

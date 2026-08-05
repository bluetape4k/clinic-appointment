package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.commitment.ProposalFailureCode
import io.bluetape4k.clinic.appointment.api.commitment.ProposalGenerationException
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 예상하지 못한 application 예외도 commitment v2의 안정 오류 envelope와
 * correlation 계약을 벗어나지 않는지 실제 MVC exception resolver로 검증한다.
 */
@ExtendWith(OutputCaptureExtension::class)
class AppointmentCommitmentExceptionResolutionTest {

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(FailingCommitmentController())
        .setControllerAdvice(GlobalExceptionHandler())
        .addFilters<StandaloneMockMvcBuilder>(CorrelationIdFilter())
        .build()

    @Test
    fun `missing aggregate fallback uses the stable commitment not found envelope`() {
        mockMvc.perform(
            get("/api/tenant-a/appointments/404/commitment")
                .header(CorrelationIdFilter.HEADER_NAME, "commitment-not-found-7")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "commitment-not-found-7"))
            .andExpect(jsonPath("$.errorCode").value("COMMITMENT_NOT_FOUND"))
            .andExpect(jsonPath("$.correlationId").value("commitment-not-found-7"))
            .andExpect(jsonPath("$.retryable").value(false))
    }

    @Test
    fun `unexpected state fallback is redacted and provides bounded retry guidance`(output: CapturedOutput) {
        val secretMarker = "secret-resource-and-patient-detail"

        mockMvc.perform(
            get("/api/tenant-a/appointments/500/commitment")
                .header(CorrelationIdFilter.HEADER_NAME, "commitment-internal-7")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isInternalServerError)
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "commitment-internal-7"))
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
            .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.correlationId").value("commitment-internal-7"))
            .andExpect(jsonPath("$.retryable").value(true))

        output.out.shouldContain("exception_type=IllegalStateException")
        output.out.shouldContain("correlation_id=commitment-internal-7")
        output.out.shouldNotContain(secretMarker)
    }

    @Test
    fun `proposal computation limit uses the stable plan allowance error`() {
        mockMvc.perform(
            get("/api/tenant-a/appointments/429/commitment")
                .header(CorrelationIdFilter.HEADER_NAME, "commitment-limit-7")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.errorCode").value("PLAN_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.correlationId").value("commitment-limit-7"))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(jsonPath("$.action").value("Review the plan size, dependency, and scheduling constraints."))
    }

    @Test
    fun `proposal without a feasible slot uses the stable resource conflict error`() {
        mockMvc.perform(
            get("/api/tenant-a/appointments/409/commitment")
                .header(CorrelationIdFilter.HEADER_NAME, "commitment-slot-7")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("RESOURCE_CONFLICT"))
            .andExpect(jsonPath("$.correlationId").value("commitment-slot-7"))
            .andExpect(jsonPath("$.retryable").value(false))
            .andExpect(jsonPath("$.action").value("Request another appointment proposal."))
    }

    @Test
    fun `unrelated v2 path does not inherit the commitment error registry`() {
        isAppointmentCommitmentRequestPath("/api/v2/other").shouldBeFalse()
        isAppointmentCommitmentRequestPath("/api/tenant-a/other").shouldBeFalse()
    }

    @Test
    fun `all public cancellation and proposal lifecycle paths use the commitment error registry`() {
        listOf(
            "/api/tenant-a/appointment-requests",
            "/api/tenant-a/admin/appointments",
            "/api/tenant-a/appointments/11/cancel",
            "/api/tenant-a/appointments/11/proposals/31/expire",
            "/api/tenant-a/appointments/11/proposals/31/accept",
            "/api/tenant-a/appointments/11/proposals/31/decline",
        ).forEach { path ->
            isAppointmentCommitmentRequestPath(path).shouldBeTrue()
        }

        listOf(
            "/api/v2/appointment-requests",
            "/api/v2/admin/appointments",
            "/api/v2/appointments/11/commitment",
        ).forEach { path ->
            isAppointmentCommitmentRequestPath(path).shouldBeFalse()
        }
    }

    @RestController
    private class FailingCommitmentController {

        @GetMapping("/api/{tenantCode}/appointments/{id}/commitment")
        fun fail(@PathVariable id: Long): Nothing =
            when (id) {
                404L -> throw NoSuchElementException("secret-appointment-$id")
                429L -> throw ProposalGenerationException(
                    code = ProposalFailureCode.PLAN_LIMIT_EXCEEDED,
                    partialProposals = emptyList(),
                )
                409L -> throw ProposalGenerationException(
                    code = ProposalFailureCode.NO_FEASIBLE_SLOT,
                    partialProposals = emptyList(),
                )
                else -> throw IllegalStateException("secret-resource-and-patient-detail:$id")
            }
    }
}

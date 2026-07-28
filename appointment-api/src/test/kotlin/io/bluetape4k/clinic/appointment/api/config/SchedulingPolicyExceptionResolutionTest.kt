package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.api.security.CorrelationIdFilter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Spring MVC가 가장 구체적인 예외 handler를 선택해도 예약 정책 오류 계약을 보존하는지 검증한다.
 *
 * handler 메서드를 직접 호출하지 않고 실제 MVC exception resolver를 사용한다. 따라서
 * `IllegalStateException`처럼 일반 `Exception`보다 구체적인 handler가 있는 예외도
 * 정책 전용 `500 POLICY_INTERNAL_ERROR`와 검증된 correlation ID를 반환해야 한다.
 */
@ExtendWith(OutputCaptureExtension::class)
class SchedulingPolicyExceptionResolutionTest {

    private val mockMvc = MockMvcBuilders
        .standaloneSetup(FailingPolicyController())
        .setControllerAdvice(GlobalExceptionHandler())
        .addFilters<StandaloneMockMvcBuilder>(CorrelationIdFilter())
        .build()

    @Test
    fun `illegal state follows the stable policy internal error contract`(output: CapturedOutput) {
        val secretMarker = "secret-policy-invariant"

        mockMvc.perform(
            post("/api/tenant-one/admin/scheduling-policies/fail")
                .header(CorrelationIdFilter.HEADER_NAME, "policy-resolution-7")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isInternalServerError)
            .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "policy-resolution-7"))
            .andExpect(jsonPath("$.errorCode").value("POLICY_INTERNAL_ERROR"))
            .andExpect(jsonPath("$.correlationId").value("policy-resolution-7"))
            .andExpect(jsonPath("$.retryable").value(false))

        output.out.shouldContain("Scheduling policy request failed with an internal error")
        output.out.shouldNotContain(secretMarker)
    }

    @RestController
    private class FailingPolicyController {

        @PostMapping("/api/{tenantCode}/admin/scheduling-policies/fail")
        fun fail(@PathVariable tenantCode: String): Nothing =
            throw IllegalStateException("secret-policy-invariant:$tenantCode")
    }
}

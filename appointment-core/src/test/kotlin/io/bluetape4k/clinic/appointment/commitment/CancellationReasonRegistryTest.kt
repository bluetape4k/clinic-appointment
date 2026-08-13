package io.bluetape4k.clinic.appointment.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test

class CancellationReasonRegistryTest {

    @Test
    fun `registered cancellation reasons are closed and stable`() {
        CancellationReasonRegistry.codes shouldBeEqualTo setOf(
            "CUSTOMER_REQUEST",
            "REFUND",
            "EQUIPMENT_FAILURE",
            "CLINIC_REQUEST",
        )

        CancellationReasonCode("CUSTOMER_REQUEST").value shouldBeEqualTo "CUSTOMER_REQUEST"
        assertFailsWith<IllegalArgumentException> {
            CancellationReasonCode("UNREGISTERED_REASON")
        }
    }

    @Test
    fun `canonical hash distinguishes null unicode and delimiter detail without normalization`() {
        val codeOnly = CancellationReasonRegistry.canonicalHashHex("CUSTOMER_REQUEST", null)
        val nonEmpty = CancellationReasonRegistry.canonicalHashHex("CUSTOMER_REQUEST", "a")
        val delimiter = CancellationReasonRegistry.canonicalHashHex("CUSTOMER_REQUEST", "a|b")
        val unicode = CancellationReasonRegistry.canonicalHashHex("CUSTOMER_REQUEST", "가나다")
        val decomposed = CancellationReasonRegistry.canonicalHashHex("CUSTOMER_REQUEST", "가나다")

        codeOnly shouldNotBeEqualTo nonEmpty
        delimiter shouldNotBeEqualTo codeOnly
        unicode shouldNotBeEqualTo decomposed
        codeOnly.length shouldBeEqualTo 64
    }

    @Test
    fun `cancellation detail rejects patient medical and payment identifiers without echoing them`() {
        val sensitiveDetails = listOf(
            "연락처는 010-1234-5678입니다.",
            "안내 email은 patient@example.com입니다.",
            "주민등록번호 900101-1234567",
            "환자번호 A-1234",
            "진단명: 고혈압",
            "카드번호 4111 1111 1111 1111",
        )

        sensitiveDetails.forEach { detail ->
            val failure = assertFailsWith<IllegalArgumentException> {
                CancellationReasonRegistry.requireDetail(detail)
            }

            failure.message.orEmpty().shouldNotContain(detail)
        }
    }

    @Test
    fun `cancellation detail accepts low risk schedule guidance`() {
        CancellationReasonRegistry.requireDetail("진료 일정이 변경되어 예약을 취소합니다.") shouldBeEqualTo
            "진료 일정이 변경되어 예약을 취소합니다."
        CancellationReasonRegistry.requireDetail("결제 환불 처리로 예약을 취소합니다.") shouldBeEqualTo
            "결제 환불 처리로 예약을 취소합니다."
        CancellationReasonRegistry.requireDetail("2026-08-13 10:30 일정으로 변경합니다.") shouldBeEqualTo
            "2026-08-13 10:30 일정으로 변경합니다."
    }
}

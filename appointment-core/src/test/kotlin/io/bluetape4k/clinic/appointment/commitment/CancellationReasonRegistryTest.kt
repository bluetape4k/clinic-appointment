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
    fun `canonical hash distinguishes null and registered unicode detail`() {
        val codeOnly = CancellationReasonRegistry.canonicalHashHex("CUSTOMER_REQUEST", null)
        val scheduleChanged = CancellationReasonRegistry.canonicalHashHex(
            "CUSTOMER_REQUEST",
            CancellationReasonRegistry.SCHEDULE_CHANGED_DETAIL,
        )
        val refundProcessing = CancellationReasonRegistry.canonicalHashHex(
            "CUSTOMER_REQUEST",
            CancellationReasonRegistry.REFUND_PROCESSING_DETAIL,
        )

        codeOnly shouldNotBeEqualTo scheduleChanged
        scheduleChanged shouldNotBeEqualTo refundProcessing
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
            "홍길동 환자의 고혈압으로 예약을 취소합니다.",
            "서울시 강남구 주소로 안내해 주세요.",
            "진료 일정이 변경되었습니다. ",
            "등록되지 않은 임의 안내 문구",
        )

        sensitiveDetails.forEach { detail ->
            val failure = assertFailsWith<IllegalArgumentException> {
                CancellationReasonRegistry.requireDetail(detail)
            }

            failure.message.orEmpty().shouldNotContain(detail)
        }
    }

    @Test
    fun `cancellation detail accepts only server owned guidance`() {
        CancellationReasonRegistry.allowedDetails.forEach { detail ->
            CancellationReasonRegistry.requireDetail(detail) shouldBeEqualTo detail
        }
    }
}

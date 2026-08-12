package io.bluetape4k.clinic.appointment.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
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
}

package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyDocumentCodec
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyValidationException
import org.junit.jupiter.api.Test

class WaitlistPolicyDocumentTest {
    private val codec = WaitlistPolicyDocumentCodec()

    @Test
    fun `unknown field oversized depth metadata duplicate keys are rejected`() {
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode("""{"unknown":1}""")
        }
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode("""{"mode":"STRICT","urgencyWeight":1}""")
        }
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode("""{"@class":"evil.Type","urgencyWeight":1}""")
        }
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode("""{"urgencyWeight":1,"urgencyWeight":2}""")
        }
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode("""{"container":{"level1":{"level2":{"level3":{"level4":{"level5":{"level6":{"level7":{"level8":1}}}}}}}}}""")
        }
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode("{\"payload\":\"${"x".repeat(65_536)}\"}")
        }
    }

    @Test
    fun `weights must stay inside the deterministic integer bound`() {
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode(policyJson(urgencyWeight = -1))
        }
        assertFailsWith<WaitlistPolicyValidationException> {
            codec.decode(policyJson(recoveryWeight = 10_001))
        }
    }

    @Test
    fun `semantically equivalent integer and key order inputs produce the same canonical digest`() {
        val first = codec.decode(
            """
            {
              "urgencyWeight": 10,
              "recoveryWeight": 3,
              "benefitWeight": 2,
              "reliabilityWeight": 4,
              "waitingAgeWeight": 6,
              "slotFitWeight": 8
            }
            """.trimIndent(),
        )
        val reordered = codec.decode(
            """
            {
              "slotFitWeight": 8.0,
              "waitingAgeWeight": 6,
              "reliabilityWeight": 4,
              "benefitWeight": 2.00,
              "recoveryWeight": 3,
              "urgencyWeight": 10
            }
            """.trimIndent(),
        )

        reordered.document shouldBeEqualTo first.document
        reordered.canonicalJson shouldBeEqualTo first.canonicalJson
        reordered.digest shouldBeEqualTo first.digest
    }

    private fun policyJson(
        urgencyWeight: Int = 1,
        recoveryWeight: Int = 1,
        benefitWeight: Int = 1,
        reliabilityWeight: Int = 1,
        waitingAgeWeight: Int = 1,
        slotFitWeight: Int = 1,
    ): String =
        """
        {
          "urgencyWeight": $urgencyWeight,
          "recoveryWeight": $recoveryWeight,
          "benefitWeight": $benefitWeight,
          "reliabilityWeight": $reliabilityWeight,
          "waitingAgeWeight": $waitingAgeWeight,
          "slotFitWeight": $slotFitWeight
        }
        """.trimIndent()
}

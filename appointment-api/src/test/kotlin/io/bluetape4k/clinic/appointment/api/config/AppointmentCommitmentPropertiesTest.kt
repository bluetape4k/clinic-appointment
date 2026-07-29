package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * commitment v2 rollout이 기본 OFF이며 상품·병원 설정으로 platform safety ceiling을
 * 완화할 수 없는지 검증한다.
 */
class AppointmentCommitmentPropertiesTest {

    @Test
    fun `defaults are fail closed and retain the approved platform ceilings`() {
        val properties = AppointmentCommitmentProperties()

        properties.mode shouldBeEqualTo AppointmentCommitmentMode.OFF
        properties.clinicAllowlist.isEmpty().shouldBeTrue()
        properties.isWriteEnabled(1L).shouldBeFalse()
        properties.proposalTtl shouldBeEqualTo Duration.ofMinutes(30)
        properties.retry.maxAttempts shouldBeEqualTo 3
        properties.ceiling.plannedTreatments shouldBeEqualTo 500
        properties.ceiling.relationshipEdges shouldBeEqualTo 4_000
        properties.ceiling.repeatCount shouldBeEqualTo 100
        properties.ceiling.searchDays shouldBeEqualTo 365
        properties.ceiling.candidateSlots shouldBeEqualTo 2_000
        properties.ceiling.resourcesPerSlot shouldBeEqualTo 200
        properties.ceiling.candidateResourceEntries shouldBeEqualTo 10_000
        properties.ceiling.returnedProposals shouldBeEqualTo 20
    }

    @Test
    fun `write requires both WRITE mode and an allowlisted clinic`() {
        val shadow = AppointmentCommitmentProperties(
            mode = AppointmentCommitmentMode.SHADOW,
            clinicAllowlist = setOf(11L),
        )
        val write = shadow.copy(mode = AppointmentCommitmentMode.WRITE)

        shadow.isWriteEnabled(11L).shouldBeFalse()
        write.isWriteEnabled(11L).shouldBeTrue()
        write.isWriteEnabled(12L).shouldBeFalse()
    }

    @Test
    fun `invalid identifiers durations retry and relaxed ceilings are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            AppointmentCommitmentProperties(clinicAllowlist = setOf(0L))
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentCommitmentProperties(proposalTtl = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentCommitmentProperties(retry = AppointmentCommitmentRetryProperties(maxAttempts = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentCommitmentProperties(
                ceiling = AppointmentCommitmentCeilingProperties(plannedTreatments = 501),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentCommitmentProperties(
                ceiling = AppointmentCommitmentCeilingProperties(resourcesPerSlot = 201),
            )
        }
    }
}

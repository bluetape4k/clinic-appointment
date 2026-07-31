package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class NotificationRolloutModeTest {

    @Test
    fun `rollout mode는 네 가지 닫힌 값만 제공한다`() {
        NotificationRolloutMode.entries.map(NotificationRolloutMode::name) shouldBeEqualTo
            listOf("SHADOW", "CANARY", "ACTIVE", "PAUSED")
    }

    @Test
    fun `CANARY는 양수 병원 allowlist를 요구한다`() {
        assertFailsWith<IllegalStateException> {
            NotificationProperties.RolloutProperties(
                mode = NotificationRolloutMode.CANARY,
                canaryClinicIds = emptySet(),
            ).validate()
        }
        assertFailsWith<IllegalStateException> {
            NotificationProperties.RolloutProperties(
                mode = NotificationRolloutMode.CANARY,
                canaryClinicIds = setOf(0L),
            ).validate()
        }
    }

    @Test
    fun `CANARY 외 mode의 stale allowlist는 시작 단계에서 거절한다`() {
        NotificationProperties.RolloutProperties(
            mode = NotificationRolloutMode.CANARY,
            canaryClinicIds = setOf(7L),
        ).validate()

        assertFailsWith<IllegalStateException> {
            NotificationProperties.RolloutProperties(
                mode = NotificationRolloutMode.ACTIVE,
                canaryClinicIds = setOf(7L),
            ).validate()
        }
    }
}

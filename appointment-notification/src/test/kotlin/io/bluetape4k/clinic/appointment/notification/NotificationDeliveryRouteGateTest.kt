package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test

internal class NotificationDeliveryRouteGateTest {

    @Test
    fun `기본 SHADOW는 전환기 event route만 허용한다`() {
        val gate = NotificationDeliveryRouteGate(NotificationProperties.RolloutProperties())

        gate.allows(NotificationDeliveryRoute.DIRECT_EVENT, clinicId = 1L) shouldBeEqualTo true
        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, clinicId = 1L) shouldBeEqualTo false
        gate.hasWorkerRoute shouldBeEqualTo false
    }

    @Test
    fun `CANARY는 allowlist 병원과 나머지 병원의 route를 상호 배타로 나눈다`() {
        val gate = NotificationDeliveryRouteGate(
            NotificationProperties.RolloutProperties(
                mode = NotificationRolloutMode.CANARY,
                canaryClinicIds = setOf(11L, 12L),
            )
        )

        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, clinicId = 11L) shouldBeEqualTo true
        gate.allows(NotificationDeliveryRoute.DIRECT_EVENT, clinicId = 11L) shouldBeEqualTo false
        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, clinicId = 21L) shouldBeEqualTo false
        gate.allows(NotificationDeliveryRoute.DIRECT_EVENT, clinicId = 21L) shouldBeEqualTo true
        gate.hasWorkerRoute shouldBeEqualTo true
    }

    @Test
    fun `ACTIVE는 worker만 PAUSED는 어떤 provider route도 허용하지 않는다`() {
        val active = NotificationDeliveryRouteGate(
            NotificationProperties.RolloutProperties(mode = NotificationRolloutMode.ACTIVE)
        )
        val paused = NotificationDeliveryRouteGate(
            NotificationProperties.RolloutProperties(mode = NotificationRolloutMode.PAUSED)
        )

        active.allows(NotificationDeliveryRoute.OUTBOX_WORKER, clinicId = 1L) shouldBeEqualTo true
        active.allows(NotificationDeliveryRoute.DIRECT_EVENT, clinicId = 1L) shouldBeEqualTo false
        paused.allows(NotificationDeliveryRoute.OUTBOX_WORKER, clinicId = 1L) shouldBeEqualTo false
        paused.allows(NotificationDeliveryRoute.DIRECT_EVENT, clinicId = 1L) shouldBeEqualTo false
        paused.hasWorkerRoute shouldBeEqualTo false
    }
}

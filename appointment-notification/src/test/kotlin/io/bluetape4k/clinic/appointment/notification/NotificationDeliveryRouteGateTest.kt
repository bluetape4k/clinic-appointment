package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.junit.jupiter.api.Test

internal class NotificationDeliveryRouteGateTest {

    private fun scope(clinicId: Long, tenantGroupId: Long = 1L) = TenantClinicScope(tenantGroupId, clinicId)

    @Test
    fun `기본 SHADOW는 전환기 event route만 허용한다`() {
        val gate = NotificationDeliveryRouteGate(NotificationProperties.RolloutProperties())

        gate.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope(1L)) shouldBeEqualTo true
        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, scope(1L)) shouldBeEqualTo false
        gate.hasWorkerRoute shouldBeEqualTo false
    }

    @Test
    fun `CANARY는 allowlist 병원과 나머지 병원의 route를 상호 배타로 나눈다`() {
        val gate = NotificationDeliveryRouteGate(
            NotificationProperties.RolloutProperties(
                mode = NotificationRolloutMode.CANARY,
                canaryScopes = setOf(scope(11L), scope(12L)),
            )
        )

        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, scope(11L)) shouldBeEqualTo true
        gate.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope(11L)) shouldBeEqualTo false
        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, scope(21L)) shouldBeEqualTo false
        gate.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope(21L)) shouldBeEqualTo true
        gate.allows(NotificationDeliveryRoute.OUTBOX_WORKER, scope(11L, tenantGroupId = 2L)) shouldBeEqualTo false
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

        active.allows(NotificationDeliveryRoute.OUTBOX_WORKER, scope(1L)) shouldBeEqualTo true
        active.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope(1L)) shouldBeEqualTo false
        paused.allows(NotificationDeliveryRoute.OUTBOX_WORKER, scope(1L)) shouldBeEqualTo false
        paused.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope(1L)) shouldBeEqualTo false
        paused.hasWorkerRoute shouldBeEqualTo false
    }
}

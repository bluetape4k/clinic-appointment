package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
/** 알림 provider 호출을 허용하는 rollout 단계입니다. */
enum class NotificationRolloutMode {
    /** Background worker는 발송하지 않고 privacy-safe 전환기 event route를 유지합니다. */
    SHADOW,

    /** Allowlist 병원은 worker, 나머지 병원은 전환기 event route를 사용합니다. */
    CANARY,

    /** 모든 병원이 worker route만 사용합니다. */
    ACTIVE,

    /** Provider 호출은 멈추되 enqueue와 retention은 유지합니다. */
    PAUSED,
}

/** Provider 호출 직전 선택하는 닫힌 전달 route입니다. */
enum class NotificationDeliveryRoute {
    DIRECT_EVENT,
    OUTBOX_WORKER,
}

/**
 * 병원별 rollout 설정을 단일 route 결정으로 변환합니다.
 *
 * 이 gate는 운영 의도를 표현하고, 실제 중복 발송 방지는 provider 호출 전 outbox
 * 조건부 claim이 보장합니다.
 */
class NotificationDeliveryRouteGate(
    rollout: NotificationProperties.RolloutProperties,
) {
    private val rollout = rollout.validate()

    val hasWorkerRoute: Boolean
        get() = rollout.mode == NotificationRolloutMode.CANARY ||
            rollout.mode == NotificationRolloutMode.ACTIVE

    val workerScopes: Set<TenantClinicScope>?
        get() = when (rollout.mode) {
            NotificationRolloutMode.CANARY -> rollout.canaryScopes
            NotificationRolloutMode.ACTIVE -> null
            NotificationRolloutMode.SHADOW,
            NotificationRolloutMode.PAUSED,
            -> emptySet()
        }

    fun allows(route: NotificationDeliveryRoute, scope: TenantClinicScope): Boolean {
        return when (rollout.mode) {
            NotificationRolloutMode.SHADOW -> route == NotificationDeliveryRoute.DIRECT_EVENT
            NotificationRolloutMode.CANARY -> {
                val canary = scope in rollout.canaryScopes
                if (canary) {
                    route == NotificationDeliveryRoute.OUTBOX_WORKER
                } else {
                    route == NotificationDeliveryRoute.DIRECT_EVENT
                }
            }
            NotificationRolloutMode.ACTIVE -> route == NotificationDeliveryRoute.OUTBOX_WORKER
            NotificationRolloutMode.PAUSED -> false
        }
    }

    companion object {
        fun active(): NotificationDeliveryRouteGate =
            NotificationDeliveryRouteGate(
                NotificationProperties.RolloutProperties(mode = NotificationRolloutMode.ACTIVE)
            )
    }
}

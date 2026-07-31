package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.notification.NotificationOutboxHealthIndicator
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxHealthStatus
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** 알림 outbox의 privacy-safe readiness/degraded 상태를 Actuator health component로 연결합니다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(NotificationOutboxHealthIndicator::class)
class NotificationHealthConfiguration {

    @Bean("notificationOutboxHealth")
    fun notificationOutboxActuatorHealth(
        indicator: NotificationOutboxHealthIndicator,
    ): HealthIndicator =
        HealthIndicator {
            val readiness = indicator.readiness()
            val liveness = indicator.liveness()
            val builder = if (readiness.status == NotificationOutboxHealthStatus.UP) Health.up() else Health.down()
            builder
                .withDetail("readiness", readiness.details)
                .withDetail("liveness", liveness.details)
                .build()
        }
}

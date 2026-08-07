package io.bluetape4k.clinic.appointment.messaging

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring scheduling only for the opt-in in-process consumer retention runner.
 *
 * Deployments that use an external CronJob must leave the property disabled so one retention
 * policy cannot be executed by two schedulers at the same time.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "appointment.messaging.retention",
    name = ["scheduler-enabled"],
    havingValue = "true",
)
internal class AppointmentConsumerRetentionSchedulingConfiguration

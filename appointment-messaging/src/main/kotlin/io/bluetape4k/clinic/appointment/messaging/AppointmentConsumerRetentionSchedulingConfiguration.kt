package io.bluetape4k.clinic.appointment.messaging

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * opt-in한 in-process consumer retention runner에만 Spring scheduling을 활성화합니다.
 *
 * 외부 CronJob을 사용하는 배포는 이 property를 비활성화해야 합니다. 하나의
 * retention policy가 두 scheduler에서 동시에 실행되지 않도록 하기 위함입니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "appointment.messaging.retention",
    name = ["scheduler-enabled"],
    havingValue = "true",
)
internal class AppointmentConsumerRetentionSchedulingConfiguration

package io.bluetape4k.clinic.appointment.messaging

import org.springframework.scheduling.annotation.Scheduled

/**
 * 동일 JVM에서 retention을 실행하는 선택적 scheduler입니다.
 *
 * 기본값은 꺼져 있으며, 외부 CronJob을 쓰는 배포에서는 이 bean을 만들지 않아야
 * 중복 cleanup과 lock contention을 피할 수 있습니다.
 */
class AppointmentConsumerRetentionScheduler(
    private val service: AppointmentConsumerRetentionService,
) {
    @Scheduled(fixedDelayString = "\${appointment.messaging.retention.interval:PT1H}")
    fun cleanup() {
        service.cleanup()
    }
}

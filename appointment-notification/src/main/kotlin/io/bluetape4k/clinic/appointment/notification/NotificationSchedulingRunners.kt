package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled

/** 애플리케이션 준비 직후와 고정 간격마다 outbox 한 tick을 bounded 처리합니다. */
class NotificationOutboxSchedulingRunner(
    private val dispatcher: NotificationOutboxDispatcher? = null,
) {
    companion object : KLogging()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        poll()
    }

    @Scheduled(fixedDelayString = "\${clinic.notification.worker.poll-interval:PT1S}")
    fun poll() {
        try {
            runSynchronously { dispatcher?.dispatchOnce() }
        } catch (e: Exception) {
            log.warn { "알림 outbox poll에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

/** ready backlog snapshot을 worker poll과 분리된 저빈도 주기로 갱신합니다. */
class NotificationObservationSchedulingRunner(
    private val metrics: NotificationOutboxMetrics,
) {
    companion object : KLogging()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        poll()
    }

    @Scheduled(fixedDelayString = "\${clinic.notification.observation.poll-interval:PT10S}")
    fun poll() {
        try {
            runSynchronously { metrics.refreshSnapshot() }
        } catch (e: Exception) {
            log.warn { "알림 outbox 관측 snapshot 갱신에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

/** 종료 outbox와 attempt를 설정된 주기마다 bounded page로 정리합니다. */
class NotificationRetentionSchedulingRunner(
    private val runner: NotificationRetentionRunner,
    private val healthSignals: NotificationRuntimeHealthSignals? = null,
) {
    companion object : KLogging()

    @Scheduled(fixedDelayString = "\${clinic.notification.retention.poll-interval:PT1H}")
    fun poll() {
        try {
            runSynchronously { runner.runOnce() }
            healthSignals?.recordRetentionSuccess()
        } catch (e: Exception) {
            healthSignals?.recordRetentionFailure()
            log.warn { "알림 outbox retention에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.leader.spring.scheduling.LeaderScheduled
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import java.time.Duration
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled

/** 애플리케이션 준비 직후와 고정 간격마다 outbox 한 tick을 bounded 처리합니다. */
class NotificationOutboxSchedulingRunner(
    private val dispatcher: NotificationOutboxDispatcher? = null,
    private val suspendBridgeTimeout: Duration = Duration.ofSeconds(30),
) {
    companion object : KLogging()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        poll()
    }

    @Scheduled(fixedDelayString = "\${clinic.notification.worker.poll-interval:PT1S}")
    fun poll() {
        try {
            runSynchronously(suspendBridgeTimeout) { dispatcher?.dispatchOnce() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "알림 outbox poll에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

/** ready backlog snapshot을 worker poll과 분리된 저빈도 주기로 갱신합니다. */
class NotificationObservationSchedulingRunner(
    private val metrics: NotificationOutboxMetrics,
    private val suspendBridgeTimeout: Duration = Duration.ofSeconds(30),
) {
    companion object : KLogging()

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        poll()
    }

    @Scheduled(fixedDelayString = "\${clinic.notification.observation.poll-interval:PT10S}")
    fun poll() {
        try {
            runSynchronously(suspendBridgeTimeout) { metrics.refreshSnapshot() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "알림 outbox 관측 snapshot 갱신에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

/** 종료 outbox와 attempt를 설정된 주기마다 bounded page로 정리합니다. */
class NotificationRetentionSchedulingRunner(
    private val runner: NotificationRetentionRunner,
    private val healthSignals: NotificationRuntimeHealthSignals? = null,
    private val suspendBridgeTimeout: Duration = Duration.ofSeconds(30),
) {
    companion object : KLogging()

    @Scheduled(fixedDelayString = "\${clinic.notification.retention.poll-interval:PT1H}")
    fun poll() {
        try {
            runSynchronously(suspendBridgeTimeout) { runner.runOnce() }
            healthSignals?.recordRetentionSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthSignals?.recordRetentionFailure()
            log.warn { "알림 outbox retention에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

/** 애플리케이션 준비 직후와 설정된 간격마다 누락된 reminder materialization을 보정합니다. */
open class NotificationReminderSchedulingRunner(
    private val scheduler: AppointmentReminderScheduler,
    private val metrics: NotificationOutboxMetrics? = null,
    private val suspendBridgeTimeout: Duration = Duration.ofSeconds(30),
) {
    companion object : KLogging()

    @LeaderScheduled(
        name = REMINDER_RECOVERY_LOCK_NAME,
        fixedDelayString = "\${clinic.notification.worker.reminder-recovery-interval:PT1H}",
        failureMode = LeaderAspectFailureMode.SKIP,
    )
    open fun poll() {
        try {
            val result = runSynchronously(suspendBridgeTimeout) { scheduler.triggerOnce() } ?: return
            metrics?.recordReminderRecovery(result)
            if (result.scanned > 0) {
                log.info {
                    "리마인더 보정 완료: enqueued=${result.enqueued}, suppressed=${result.suppressed}, " +
                        "alreadyExists=${result.alreadyExists}, notYetDue=${result.notYetDue}, scanned=${result.scanned}"
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "리마인더 보정에 실패했습니다: failure=${e.javaClass.simpleName}" }
        }
    }
}

/** 애플리케이션 준비 이벤트에서 AOP proxy를 거쳐 reminder recovery를 즉시 실행합니다. */
class NotificationReminderSchedulingBootstrap(
    private val runner: NotificationReminderSchedulingRunner,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        runner.poll()
    }
}

internal const val REMINDER_RECOVERY_LOCK_NAME = "appointment-reminder-recovery"

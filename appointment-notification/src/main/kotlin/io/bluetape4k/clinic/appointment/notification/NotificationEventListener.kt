package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.AppointmentDomainEvent
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.springframework.context.event.EventListener
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.time.Duration

/**
 * 코드 전환 기간에 legacy 예약 event를 같은 durable outbox 행으로 연결합니다.
 *
 * 예약 DTO, 이름, 전화번호, 완성 본문을 읽지 않으며 channel도 직접 호출하지 않습니다.
 * 실제 provider 호출은 [NotificationDirectDeliveryPort]가 조건부 claim한 행에 대해서만
 * 기존 worker 파이프라인으로 수행합니다.
 */
class NotificationEventListener(
    private val delivery: NotificationDirectDeliveryPort,
    private val properties: NotificationProperties,
    private val executor: Executor = Executor(Runnable::run),
    private val routeGate: NotificationDeliveryRouteGate =
        NotificationDeliveryRouteGate(properties.rollout),
    private val metrics: NotificationOutboxMetrics? = null,
) {

    private val suspendBridgeTimeout: Duration = properties.worker.validate().suspendBridgeTimeout

    companion object : KLogging()

    @EventListener
    fun onCreated(event: AppointmentDomainEvent.Created) {
        if (!properties.enabled || !properties.events.created) return
        deliver(event.scope, event.appointmentId, NotificationEventType.CREATED)
    }

    @EventListener
    fun onStatusChanged(event: AppointmentDomainEvent.StatusChanged) {
        if (!properties.enabled || !properties.events.confirmed || event.toState != "CONFIRMED") return
        deliver(event.scope, event.appointmentId, NotificationEventType.CONFIRMED)
    }

    @EventListener
    fun onCancelled(event: AppointmentDomainEvent.Cancelled) {
        if (!properties.enabled || !properties.events.cancelled) return
        deliver(event.scope, event.appointmentId, NotificationEventType.CANCELLED)
    }

    @EventListener
    fun onRescheduled(event: AppointmentDomainEvent.Rescheduled) {
        if (!properties.enabled || !properties.events.rescheduled) return
        deliver(event.scope, event.originalId, NotificationEventType.RESCHEDULED)
    }

    private fun deliver(
        scope: TenantClinicScope,
        appointmentId: Long,
        eventType: NotificationEventType,
    ) {
        if (!routeGate.allows(NotificationDeliveryRoute.DIRECT_EVENT, scope)) {
            metrics?.recordDirectEventScopeRejected(NotificationOutboxMetrics.DIRECT_EVENT_SCOPE_REJECTED)
            return
        }
        try {
            executor.execute {
                try {
                    runSynchronously(suspendBridgeTimeout) {
                        delivery.deliver(scope, appointmentId, eventType)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn { "전환기 알림 전달이 중단되었습니다: eventType=$eventType" }
                } catch (e: Exception) {
                    log.warn { "전환기 알림 전달에 실패했습니다: eventType=$eventType, failure=${e.javaClass.simpleName}" }
                }
            }
        } catch (e: RejectedExecutionException) {
            log.warn { "전환기 알림 executor가 포화되었습니다: eventType=$eventType" }
        }
    }
}

/**
 * 전환기 event route를 요청 thread 밖에서 제한된 수만 처리합니다.
 *
 * queue가 가득 차면 작업을 거절하고 이미 커밋된 outbox 행을 pending 상태로 남깁니다.
 * 예약 event thread는 provider I/O를 대신 수행하지 않으며, 실제 provider 동시성은
 * [NotificationDirectOutboxDelivery]가 같은 worker 설정으로 한 번 더 제한합니다.
 */
class NotificationDirectDeliveryExecutor(
    concurrency: Int,
    queueCapacity: Int,
) : Executor, AutoCloseable {
    private val threadSequence = AtomicLong()
    private val delegate: ThreadPoolExecutor

    init {
        require(concurrency > 0) { "concurrency must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        delegate = ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            { task ->
                Thread.ofPlatform()
                    .name("notification-direct-${threadSequence.incrementAndGet()}")
                    .daemon(true)
                    .unstarted(task)
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    override fun execute(command: Runnable) {
        delegate.execute(command)
    }

    override fun close() {
        delegate.shutdown()
        try {
            if (!delegate.awaitTermination(10, TimeUnit.SECONDS)) {
                delegate.shutdownNow()
                delegate.awaitTermination(5, TimeUnit.SECONDS)
            }
        } catch (e: InterruptedException) {
            delegate.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}

/**
 * 동기 Spring event 경계에서 suspend 발송 포트를 설정된 deadline까지 기다립니다.
 *
 * 이 함수는 suspend 함수 내부가 아닌 blocking Spring/event/scheduler 경계에서만 사용합니다.
 * timeout은 해당 bridge coroutine만 취소하며, [CancellationException]은 호출자에게 전파합니다.
 */
internal fun <T> runSynchronously(
    timeout: Duration = Duration.ofSeconds(30),
    block: suspend () -> T,
): T {
    require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
    try {
        return runBlocking {
            withTimeout(timeout.toMillis().coerceAtLeast(1L)) {
                block()
            }
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (e: TimeoutCancellationException) {
        throw NotificationSuspendBridgeTimeoutException(timeout, e)
    }
}

/** 동기 suspend bridge가 설정된 deadline을 넘겼음을 나타내는 retryable 경계 오류입니다. */
internal class NotificationSuspendBridgeTimeoutException(
    val timeout: Duration,
    cause: TimeoutCancellationException,
) : RuntimeException("notification suspend operation exceeded timeout=$timeout", cause) {
    companion object {
        private const val serialVersionUID = 1L
    }
}

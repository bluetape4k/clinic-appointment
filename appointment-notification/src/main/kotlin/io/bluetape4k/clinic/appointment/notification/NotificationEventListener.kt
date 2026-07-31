package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.AppointmentDomainEvent
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import org.springframework.context.event.EventListener
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

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
        NotificationDeliveryRouteGate(NotificationProperties.RolloutProperties()),
) {

    companion object : KLogging()

    @EventListener
    fun onCreated(event: AppointmentDomainEvent.Created) {
        if (!properties.enabled || !properties.events.created) return
        deliver(event.clinicId, event.appointmentId, NotificationEventType.CREATED)
    }

    @EventListener
    fun onStatusChanged(event: AppointmentDomainEvent.StatusChanged) {
        if (!properties.enabled || !properties.events.confirmed || event.toState != "CONFIRMED") return
        deliver(event.clinicId, event.appointmentId, NotificationEventType.CONFIRMED)
    }

    @EventListener
    fun onCancelled(event: AppointmentDomainEvent.Cancelled) {
        if (!properties.enabled || !properties.events.cancelled) return
        deliver(event.clinicId, event.appointmentId, NotificationEventType.CANCELLED)
    }

    @EventListener
    fun onRescheduled(event: AppointmentDomainEvent.Rescheduled) {
        if (!properties.enabled || !properties.events.rescheduled) return
        deliver(event.clinicId, event.originalId, NotificationEventType.RESCHEDULED)
    }

    private fun deliver(
        clinicId: Long,
        appointmentId: Long,
        eventType: NotificationEventType,
    ) {
        if (!routeGate.allows(NotificationDeliveryRoute.DIRECT_EVENT, clinicId)) return
        try {
            executor.execute {
                try {
                    runSynchronously {
                        delivery.deliver(clinicId, appointmentId, eventType)
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn { "전환기 알림 전달이 중단되었습니다: clinicId=$clinicId, eventType=$eventType" }
                } catch (e: Exception) {
                    log.warn { "전환기 알림 전달에 실패했습니다: clinicId=$clinicId, eventType=$eventType, failure=${e.javaClass.simpleName}" }
                }
            }
        } catch (e: RejectedExecutionException) {
            log.warn { "전환기 알림 executor가 포화되었습니다: clinicId=$clinicId, eventType=$eventType" }
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
 * 동기 Spring event 경계에서 suspend 발송 포트를 끝까지 기다립니다.
 *
 * coroutine builder의 바이너리 이름에 의존하지 않으므로 애플리케이션과 라이브러리가
 * 서로 다른 coroutine 호환 버전을 사용할 때도 event listener ABI를 안정적으로 유지합니다.
 */
internal fun <T> runSynchronously(block: suspend () -> T): T {
    val completed = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
                completed.countDown()
            }
        }
    )
    try {
        completed.await()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }
    return checkNotNull(outcome) { "suspend delivery completed without a result" }.getOrThrow()
}

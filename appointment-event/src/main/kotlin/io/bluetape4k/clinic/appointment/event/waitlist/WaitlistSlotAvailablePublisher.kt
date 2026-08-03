package io.bluetape4k.clinic.appointment.event.waitlist

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** vacancy event를 예약 transaction commit 이후에만 발행하는 port입니다. */
fun interface WaitlistSlotAvailablePublisher {
    fun publishAfterCommit(event: SlotAvailable)
}

/**
 * Spring event bridge입니다.
 *
 * listener 오류는 이미 예약 transaction이 commit된 이후의 fast-signal 장애이므로
 * caller transaction으로 전파하지 않습니다. durable vacancy job은 별도 scheduler가
 * 복구할 수 있습니다.
 */
class WaitlistSlotAvailableSpringPublisher(
    private val eventPublisher: ApplicationEventPublisher,
    private val onFailure: (Throwable) -> Unit = {},
) : WaitlistSlotAvailablePublisher {

    override fun publishAfterCommit(event: SlotAvailable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        publishSafely(event)
                    }
                },
            )
        } else {
            // caller가 transaction 밖에서 호출했다면 이미 commit된 경로로 취급한다.
            publishSafely(event)
        }
    }

    private fun publishSafely(event: SlotAvailable) {
        try {
            eventPublisher.publishEvent(event)
        } catch (failure: Throwable) {
            runCatching { onFailure(failure) }
            logger.warn("waitlist SlotAvailable fast signal failed; durable vacancy recovery remains authoritative")
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(WaitlistSlotAvailableSpringPublisher::class.java)
    }
}

/** 외부 wiring에서 흔히 사용하는 명칭을 유지하는 별칭입니다. */
typealias SpringWaitlistSlotAvailablePublisher = WaitlistSlotAvailableSpringPublisher

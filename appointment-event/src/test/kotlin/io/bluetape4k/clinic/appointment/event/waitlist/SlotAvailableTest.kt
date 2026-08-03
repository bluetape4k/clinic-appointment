package io.bluetape4k.clinic.appointment.event.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import kotlin.reflect.full.memberProperties

class SlotAvailableTest {

    @AfterEach
    fun cleanupSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `SlotAvailable은 member와 appointment detail을 노출하지 않는다`() {
        SlotAvailable::class.memberProperties.map { it.name }.toSet() shouldBeEqualTo
            setOf("vacancyJobId", "tenantGroupId", "clinicId", "correlationId", "occurredAt")
    }

    @Test
    fun `publisher는 transaction commit 전에는 Spring event를 발행하지 않는다`() {
        val published = mutableListOf<Any>()
        val publisher = WaitlistSlotAvailableSpringPublisher(
            eventPublisher = ApplicationEventPublisher { published += it },
        )
        val event = slotAvailable()

        TransactionSynchronizationManager.initSynchronization()
        publisher.publishAfterCommit(event)

        published shouldBeEqualTo emptyList()
        TransactionSynchronizationManager.getSynchronizations().single().afterCommit()

        published shouldBeEqualTo listOf(event)
    }

    @Test
    fun `after commit 발행 실패는 caller transaction으로 전파하지 않는다`() {
        var failure: Throwable? = null
        val publisher = WaitlistSlotAvailableSpringPublisher(
            eventPublisher = ApplicationEventPublisher { error("listener failed") },
            onFailure = { failure = it },
        )

        TransactionSynchronizationManager.initSynchronization()
        publisher.publishAfterCommit(slotAvailable())
        TransactionSynchronizationManager.getSynchronizations().single().afterCommit()

        (failure != null).shouldBeTrue()
        failure?.message shouldBeEqualTo "listener failed"
    }

    @Test
    fun `transaction이 없으면 이미 완료된 경로에서 즉시 발행한다`() {
        val published = mutableListOf<Any>()
        val publisher = WaitlistSlotAvailableSpringPublisher(
            eventPublisher = ApplicationEventPublisher { published += it },
        )

        publisher.publishAfterCommit(slotAvailable())

        published shouldBeEqualTo listOf(slotAvailable())
    }

    private fun slotAvailable() = SlotAvailable(
        vacancyJobId = 41L,
        tenantGroupId = 7L,
        clinicId = 11L,
        correlationId = "corr-170",
        occurredAt = Instant.parse("2026-08-03T10:00:00Z"),
    )
}

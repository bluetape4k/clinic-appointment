package io.bluetape4k.clinic.appointment.event.waitlist

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.assertFailsWith
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
    fun `fatal Error는 fast signal 격리 경계를 넘어 전파된다`() {
        val publisher = WaitlistSlotAvailableSpringPublisher(
            eventPublisher = ApplicationEventPublisher { throw AssertionError("fatal listener failure") },
        )

        TransactionSynchronizationManager.initSynchronization()
        publisher.publishAfterCommit(slotAvailable())

        assertFailsWith<AssertionError> {
            TransactionSynchronizationManager.getSynchronizations().single().afterCommit()
        }.message shouldBeEqualTo "fatal listener failure"
    }

    @Test
    fun `failure hook의 일반 예외는 원래 fast signal 실패를 보존하고 전파하지 않는다`() {
        var observed: Throwable? = null
        val publisher = WaitlistSlotAvailableSpringPublisher(
            eventPublisher = ApplicationEventPublisher { error("listener failed") },
            onFailure = {
                observed = it
                error("failure hook failed")
            },
        )

        TransactionSynchronizationManager.initSynchronization()
        publisher.publishAfterCommit(slotAvailable())
        TransactionSynchronizationManager.getSynchronizations().single().afterCommit()

        observed?.message shouldBeEqualTo "listener failed"
    }

    @Test
    fun `failure hook의 fatal Error는 삼키지 않는다`() {
        val publisher = WaitlistSlotAvailableSpringPublisher(
            eventPublisher = ApplicationEventPublisher { error("listener failed") },
            onFailure = { throw AssertionError("fatal failure hook") },
        )

        TransactionSynchronizationManager.initSynchronization()
        publisher.publishAfterCommit(slotAvailable())

        assertFailsWith<AssertionError> {
            TransactionSynchronizationManager.getSynchronizations().single().afterCommit()
        }.message shouldBeEqualTo "fatal failure hook"
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

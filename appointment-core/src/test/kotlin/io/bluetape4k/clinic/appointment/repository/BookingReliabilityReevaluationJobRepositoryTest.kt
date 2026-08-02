package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationCursor
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityReevaluationJobs
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.time.Instant

/**
 * 예약 신뢰성 재평가 작업의 durable lease와 cursor fencing을 검증합니다.
 */
class BookingReliabilityReevaluationJobRepositoryTest : AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `job 생성 replay claim checkpoint complete를 fencing한다`(testDB: TestDB) {
        withTables(testDB, BookingReliabilityReevaluationJobs) {
            val repository = BookingReliabilityReevaluationJobRepository(
                leaseDuration = Duration.ofMinutes(1),
                retryDelay = Duration.ofMinutes(5),
            )
            val created = repository.create(job(nextAttemptAt = Instant.now().minusSeconds(60)))
            val replay = repository.create(job(nextAttemptAt = Instant.now().minusSeconds(60)))

            replay.jobId shouldBeEqualTo created.jobId
            assertFailsWith<IllegalArgumentException> {
                repository.create(
                    job(
                        nextAttemptAt = Instant.now().minusSeconds(60),
                        commandHash = DIGEST_B,
                    ),
                )
            }

            repository.findDueJobIds(10) shouldHaveSize 1
            val claimed =
                repository.claimDue(created.jobId.shouldNotBeNull(), "worker-a").shouldNotBeNull()
            claimed.status shouldBeEqualTo BookingReliabilityReevaluationJobStatus.RUNNING
            claimed.attemptCount shouldBeEqualTo 1
            repository.claimDue(claimed.jobId.shouldNotBeNull(), "worker-b").shouldBeNull()

            repository.checkpoint(
                claimed.jobId.shouldNotBeNull(),
                "worker-a",
                BookingReliabilityReevaluationCursor(
                    cursorOccurredAt = Instant.parse("2026-08-01T00:00:00Z"),
                    cursorEventId = "event-001",
                    scannedCount = 2L,
                    decisionCount = 1L,
                ),
            ).shouldBeTrue()
            repository.checkpoint(
                claimed.jobId.shouldNotBeNull(),
                "worker-a",
                BookingReliabilityReevaluationCursor(
                    cursorOccurredAt = Instant.parse("2026-07-31T23:59:59Z"),
                    cursorEventId = "event-000",
                    scannedCount = 3L,
                    decisionCount = 1L,
                ),
            ).shouldBeFalse()
            repository.complete(claimed.jobId.shouldNotBeNull(), "worker-b").shouldBeFalse()
            repository.complete(claimed.jobId.shouldNotBeNull(), "worker-a").shouldBeTrue()
            repository.findJob(claimed.jobId.shouldNotBeNull()).shouldNotBeNull().status shouldBeEqualTo
                BookingReliabilityReevaluationJobStatus.COMPLETED
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `만료된 lease만 회수하고 retry wait는 due 전까지 숨긴다`(testDB: TestDB) {
        withTables(testDB, BookingReliabilityReevaluationJobs) {
            val repository = BookingReliabilityReevaluationJobRepository(
                leaseDuration = Duration.ofMinutes(1),
                retryDelay = Duration.ofMinutes(5),
                maxAttempts = 3,
            )
            val created = repository.create(job(nextAttemptAt = Instant.now().minusSeconds(60)))
            val claimed = repository.claimDue(created.jobId.shouldNotBeNull(), "worker-a").shouldNotBeNull()

            BookingReliabilityReevaluationJobs.update({
                BookingReliabilityReevaluationJobs.id eq claimed.jobId.shouldNotBeNull()
            }) {
                it[leaseExpiresAt] = Instant.EPOCH
            }

            val reclaimed = repository.claimDue(claimed.jobId.shouldNotBeNull(), "worker-b").shouldNotBeNull()
            reclaimed.attemptCount shouldBeEqualTo 2
            repository.scheduleRetry(reclaimed.jobId.shouldNotBeNull(), "worker-a", "TIMEOUT").shouldBeFalse()
            repository.scheduleRetry(reclaimed.jobId.shouldNotBeNull(), "worker-b", "TIMEOUT").shouldBeTrue()

            repository.findDueJobIds(10).shouldHaveSize(0)
            repository.findJob(reclaimed.jobId.shouldNotBeNull()).shouldNotBeNull().status shouldBeEqualTo
                BookingReliabilityReevaluationJobStatus.RETRY_WAIT
        }
    }

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `운영 pause resume와 retry exhaustion dead letter를 fencing한다`(testDB: TestDB) {
        withTables(testDB, BookingReliabilityReevaluationJobs) {
            val repository = BookingReliabilityReevaluationJobRepository(
                leaseDuration = Duration.ofMinutes(1),
                retryDelay = Duration.ZERO,
                maxAttempts = 1,
            )
            val created = repository.create(job(nextAttemptAt = Instant.now().minusSeconds(60)))
            repository.pause(created.jobId.shouldNotBeNull()).shouldBeTrue()
            repository.findDueJobIds(10).shouldHaveSize(0)
            repository.resume(created.jobId.shouldNotBeNull()).shouldBeTrue()
            val claimed = repository.claimDue(created.jobId.shouldNotBeNull(), "worker-a").shouldNotBeNull()
            repository.scheduleRetry(claimed.jobId.shouldNotBeNull(), "worker-a", "EVALUATION_FAILED").shouldBeTrue()
            repository.findJob(created.jobId.shouldNotBeNull()).shouldNotBeNull().status shouldBeEqualTo
                BookingReliabilityReevaluationJobStatus.DEAD_LETTER
            repository.resume(created.jobId.shouldNotBeNull()).shouldBeFalse()
        }
    }

    private fun job(
        nextAttemptAt: Instant,
        commandHash: String = DIGEST_A,
    ): BookingReliabilityReevaluationJobRecord =
        BookingReliabilityReevaluationJobRecord(
            tenantGroupId = 1L,
            clinicId = 10L,
            memberId = MemberId("member-176"),
            idempotencyKeyHash = DIGEST_KEY,
            commandHash = commandHash,
            status = BookingReliabilityReevaluationJobStatus.PENDING,
            nextAttemptAt = nextAttemptAt,
            policyVersionId = 7L,
        )

    companion object {
        private const val DIGEST_KEY = "1111111111111111111111111111111111111111111111111111111111111111"
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

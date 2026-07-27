package io.bluetape4k.clinic.appointment.api.policy

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyImpactRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyJobRepository
import io.bluetape4k.clinic.appointment.repository.SchedulingPolicyRepository
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실제 Exposed 트랜잭션에서 preview queue admission의 직렬화 경계를 검증한다.
 *
 * 두 호출을 같은 병원 scope와 capacity `1`로 동시에 시작한다. 일반 concurrency helper는
 * 두 트랜잭션이 admission row lock 앞에서 정확히 경쟁하는 시점을 보장하지 않으므로, 이 테스트는
 * 의도적으로 시작 latch를 사용한다. 결과는 정확히 한 durable job만 수락되고 다른 호출은
 * `null`이어야 하며, check와 insert가 분리된 구현이면 두 호출이 모두 수락되어 실패한다.
 */
class ExposedSchedulingPolicyPreviewStoreTest {

    @Test
    fun `same clinic capacity check and creation are one serialized admission`() {
        Database.connect(
            "jdbc:h2:mem:policy_preview_admission_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(SchedulingPolicyScopeHeads, SchedulingPolicyPreviewJobs)
        }
        val store = ExposedSchedulingPolicyPreviewStore(
            SchedulingPolicyJobRepository("preview-store-test-secret-32-bytes".toByteArray()),
            SchedulingPolicyImpactRepository(),
            SchedulingPolicyRepository(),
        )
        val command = CreateSchedulingPolicyPreviewCommand(
            scope = PolicyScopeRef(1L, PolicyScope.CLINIC_OVERRIDE, 41L),
            definitionId = 7L,
            draftRevision = 3L,
            generation = PolicyGenerationVector(2L, 1L),
            horizonFrom = Instant.parse("2026-07-27T00:00:00Z"),
            horizonUntil = Instant.parse("2026-08-27T00:00:00Z"),
            requestedAt = Instant.parse("2020-01-01T00:00:00Z"),
        )
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = (1..2).map {
                executor.submit<SchedulingPolicyPreviewAdmission?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "preview admission start timed out" }
                    store.tryCreate(command, capacity = 1, jobDeadline = Duration.ofMinutes(5))
                }
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "preview admission workers did not become ready" }
            start.countDown()

            val admissions = results.map { it.get(5, TimeUnit.SECONDS) }

            admissions.count { it != null } shouldBeEqualTo 1
            admissions.single { it != null }!!.let { admission ->
                admission.job.nextAttemptAt shouldBeEqualTo admission.acceptedAt
                admission.job.deadlineAt shouldBeEqualTo admission.acceptedAt.plus(Duration.ofMinutes(5))
            }
            admissions.single { it == null }.shouldBeNull()
            transaction {
                SchedulingPolicyPreviewJobs.selectAll().count()
            } shouldBeEqualTo 1L
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
}

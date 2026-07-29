package io.bluetape4k.clinic.appointment.api.commitment

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.dto.ResourceAllocationStatus
import io.bluetape4k.clinic.appointment.model.tables.ResourceAllocations
import io.bluetape4k.clinic.appointment.repository.ResourceAllocationRepository
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.math.ceil

/**
 * 운영과 같은 bounded JDBC pool에서 인기 전담 자원 100개 동시 확정의 정합성과
 * latency budget을 검증합니다.
 *
 * 측정 전에 pool connection, command path, resource mutex row를 준비해 컨테이너
 * connection 생성과 JVM cold start 비용을 command latency에 섞지 않습니다. 측정
 * 구간은 100개 caller가 동시에
 * [AppointmentCommitmentCommandService.confirmDirectAppointment]를 호출한 시점부터
 * 안정 결과를 받은 시점까지입니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class VisitCommitmentLoadIntegrationTest : VisitCommitmentCommandTestSupport() {

    private lateinit var dataSource: HikariDataSource

    override fun createDatabase(): Database {
        val postgres = Containers.Postgres
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    username = postgres.username ?: "test"
                    password = postgres.password ?: ""
                    driverClassName = "org.postgresql.Driver"
                    maximumPoolSize = POOL_SIZE
                    minimumIdle = POOL_SIZE
                    connectionTimeout = 5_000L
                    poolName = "visit-commitment-load"
                },
            )
        return Database.connect(dataSource)
    }

    @AfterEach
    fun closePool() {
        dataSource.close()
    }

    @Test
    fun `100 concurrent confirmations keep one allocation zero deadlocks and p95 below two seconds`() {
        warmConnectionPool()
        val service = commandService()
        confirmDirect(service, "load-warmup", "doctor-load-warmup")
        val resourceId = "doctor-popular-100"
        val sharedProposal = proposalInput(revision = 1L, resourceId = resourceId)
        transaction(database) {
            ResourceAllocationRepository().lockAndValidateAvailability(
                tenantGroupId = 1L,
                clinicId = clinic.clinicId,
                replacingProposalId = null,
                requests = sharedProposal.resourceRequests,
            )
        }
        val barrier = CyclicBarrier(CONCURRENT_CONFIRMATIONS)
        val results = ConcurrentLinkedQueue<Result<AppointmentCommitmentCommandResult>>()
        val elapsedMillis = ConcurrentLinkedQueue<Long>()
        val commands =
            List(CONCURRENT_CONFIRMATIONS) { index ->
                val key = "popular-$index"
                val command =
                    DirectAppointmentConfirmationCommand(
                        context = commandContext(key),
                        identity = appointmentIdentity(key),
                        proposal = sharedProposal,
                        expiresAt = ACTIVE_EXPIRY,
                        representativeTreatmentName = "인기 자원 동시 확정",
                        projectionTarget = confirmedProjectionTarget(resourceId),
                        policyDecision = directConfirmationPolicyDecision(),
                        consent = acceptedConsent(key),
                    )
                val task: () -> Unit = {
                    barrier.await()
                    val startedAt = System.nanoTime()
                    results += runCatching { service.confirmDirectAppointment(command) }
                    elapsedMillis += (System.nanoTime() - startedAt) / 1_000_000L
                }
                task
            }

        MultithreadingTester()
            .workers(CONCURRENT_CONFIRMATIONS)
            .rounds(1)
            .addAll(commands)
            .run()

        val successfulConfirmations =
            results.mapNotNull(Result<AppointmentCommitmentCommandResult>::getOrNull)
        successfulConfirmations shouldHaveSize 1
        val failures = results.mapNotNull(Result<AppointmentCommitmentCommandResult>::exceptionOrNull)
        failures shouldHaveSize CONCURRENT_CONFIRMATIONS - 1
        val commandFailures = failures.filterIsInstance<AppointmentCommitmentCommandException>()
        commandFailures shouldHaveSize failures.size
        val resourceConflicts =
            commandFailures.count { it.code == AppointmentCommitmentCommandError.RESOURCE_CONFLICT }
        resourceConflicts shouldBeEqualTo failures.size
        val unexpectedFailures = failures.size - resourceConflicts
        unexpectedFailures shouldBeEqualTo 0
        val p95Millis = elapsedMillis.percentile(95)
        writePerformanceEvidence(
            successfulConfirmations = successfulConfirmations.size,
            resourceConflicts = resourceConflicts,
            unexpectedFailures = unexpectedFailures,
            p95Millis = p95Millis,
        )
        (p95Millis <= CONCURRENCY_P95_BUDGET_MILLIS) shouldBeEqualTo true
        transaction(database) {
            ResourceAllocations
                .selectAll()
                .where {
                    (ResourceAllocations.resourceId eq resourceId) and
                        (ResourceAllocations.status eq ResourceAllocationStatus.ACTIVE)
                }.count() shouldBeEqualTo 1L
        }
    }

    /**
     * 고정 worker가 connection을 동시에 점유한 뒤 함께 반환하여 측정 전에 pool을 채웁니다.
     */
    private fun warmConnectionPool() {
        val barrier = CyclicBarrier(POOL_SIZE)
        MultithreadingTester()
            .workers(POOL_SIZE)
            .rounds(1)
            .addAll(
                List(POOL_SIZE) {
                    {
                        dataSource.connection.use {
                            barrier.await()
                            it.isValid(1).shouldBeTrue()
                        }
                    }
                },
            ).run()
    }

    /** 정렬된 표본에서 nearest-rank percentile을 계산합니다. */
    private fun Collection<Long>.percentile(percentile: Int): Long {
        require(isNotEmpty()) { "percentile requires samples" }
        val index = (ceil(size * percentile / 100.0).toInt() - 1).coerceAtLeast(0)
        return sorted()[index]
    }

    /** 동시성 acceptance 수치와 결과 분포를 검토 가능한 build report로 보존합니다. */
    private fun writePerformanceEvidence(
        successfulConfirmations: Int,
        resourceConflicts: Int,
        unexpectedFailures: Int,
        p95Millis: Long,
    ) {
        val report = Path.of("build/reports/performance/visit-commitment-concurrency.md")
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            """
            # Visit commitment concurrency

            - Backend: PostgreSQL
            - Concurrent confirmations: $CONCURRENT_CONFIRMATIONS
            - Successful confirmations: $successfulConfirmations
            - Stable resource conflicts: $resourceConflicts
            - Active allocations after completion: 1
            - Non-resource-conflict failures observed: $unexpectedFailures
            - Command latency p95: ${p95Millis} ms
            - Command latency budget: ${CONCURRENCY_P95_BUDGET_MILLIS} ms
            """.trimIndent(),
        )
    }

    private companion object {
        const val CONCURRENT_CONFIRMATIONS = 100
        const val POOL_SIZE = 20
        const val CONCURRENCY_P95_BUDGET_MILLIS = 2_000L
    }
}

package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AppointmentReplayServiceTest {
    private val database = Database.connect(
        url = "jdbc:h2:mem:appointment-replay-service;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(AppointmentConsumerReplayAuditTable)
            AppointmentConsumerReplayAuditTable.deleteAll()
        }
    }

    @Test
    fun `dry run writes audit and never invokes replay source`() {
        var calls = 0
        val service = AppointmentReplayService(database) { _, _ -> calls++ }

        val result = service.replay(
            requestId = "replay-dry-run-1",
            request = request(dryRun = true),
            actor = actor(),
        )

        result.status shouldBeEqualTo AppointmentReplayAuditStatus.DRY_RUN
        result.replayGroupId shouldBeEqualTo null
        calls shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerReplayAuditTable.selectAll().single()[AppointmentConsumerReplayAuditTable.status]
                .shouldBeEqualTo(AppointmentReplayAuditStatus.DRY_RUN)
        }
    }

    @Test
    fun `approved execution uses a separate replay group and records completion`() {
        var execution: AppointmentReplayExecution? = null
        val service = AppointmentReplayService(database) { _, context ->
            execution = context
            3
        }

        val result = service.replay(
            requestId = "replay-approved-1",
            request = request(dryRun = false),
            actor = actor(),
        )

        result.status shouldBeEqualTo AppointmentReplayAuditStatus.EXECUTED
        result.replayedRecords shouldBeEqualTo 3
        execution?.groupId shouldBeEqualTo "appointment-notification-replay-replay-approved-1-v1"
        execution?.identity?.consumerId shouldBeEqualTo request().identity.consumerId
        transaction(database) {
            AppointmentConsumerReplayAuditTable.selectAll().single()[AppointmentConsumerReplayAuditTable.status]
                .shouldBeEqualTo(AppointmentReplayAuditStatus.EXECUTED)
        }
    }

    @Test
    fun `same request id transitions a completed dry run into execution`() {
        var calls = 0
        val service = AppointmentReplayService(database) { _, _ -> calls++; 2 }

        service.replay("replay-transition-1", request(dryRun = true), actor())
        val result = service.replay("replay-transition-1", request(dryRun = false), actor())

        result.status shouldBeEqualTo AppointmentReplayAuditStatus.EXECUTED
        result.replayedRecords shouldBeEqualTo 2
        calls shouldBeEqualTo 1
    }

    @Test
    fun `same request id rejects a different partition scope`() {
        var calls = 0
        val service = AppointmentReplayService(database) { _, _ -> calls++; 1 }
        val first = request()
        val differentPartition = request().copy(partition = 1)

        service.replay("replay-partition-binding-1", first, actor())

        assertFailsWith<IllegalArgumentException> {
            service.replay("replay-partition-binding-1", differentPartition, actor())
        }
        calls shouldBeEqualTo 1
    }

    @Test
    fun `legacy audit hash requires a new request id after hash contract upgrade`() {
        val legacyRequest = request(dryRun = true)
        transaction(database) {
            AppointmentConsumerReplayAuditTable.insert {
                it[requestId] = "replay-legacy-hash-1"
                it[logicalConsumerId] = legacyRequest.identity.consumerId.value
                it[logicalStreamId] = legacyRequest.identity.streamId.value
                it[tenantGroupId] = legacyRequest.tenantGroupId
                it[clinicId] = legacyRequest.clinicId
                it[fromOffset] = legacyRequest.fromOffset
                it[toOffset] = legacyRequest.toOffset
                it[requestHash] = legacyRequestHash(legacyRequest)
                it[hashVersion] = 1
                it[dryRun] = true
                it[approvedBy] = legacyRequest.approver
                it[status] = AppointmentReplayAuditStatus.DRY_RUN
            }
        }

        val failure = assertFailsWith<IllegalArgumentException> {
            AppointmentReplayService(database) { _, _ -> 1 }
                .replay("replay-legacy-hash-1", legacyRequest, actor())
        }
        failure.message shouldBeEqualTo "replay requestId is bound to a legacy hash; issue a new requestId"
    }

    @Test
    fun `replay rejects an actor outside tenant scope or without operator role`() {
        var calls = 0
        val service = AppointmentReplayService(database) { _, _ -> calls++ }

        assertFailsWith<AppointmentReplayAuthorizationException> {
            service.replay(
                requestId = "replay-unauthorized-1",
                request = request(),
                actor = AppointmentReplayActor(
                    subject = "operator-1",
                    tenantGroupIds = setOf(99),
                    roles = emptySet(),
                ),
            )
        }
        calls shouldBeEqualTo 0
        transaction(database) {
            AppointmentConsumerReplayAuditTable.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `replay rejects an actor outside clinic scope`() {
        val service = AppointmentReplayService(database) { _, _ -> 1 }

        assertFailsWith<AppointmentReplayAuthorizationException> {
            service.replay(
                requestId = "replay-unauthorized-clinic-1",
                request = request().copy(clinicId = 99),
                actor = actor(),
            )
        }
    }

    @Test
    fun `concurrent execution claims the same request id only once`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val service = AppointmentReplayService(database) { _, _ ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS).shouldBeTrue()
            1
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<AppointmentReplayResult> {
                service.replay("replay-concurrent-1", request(), actor())
            }
            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val second = executor.submit<AppointmentReplayResult> {
                service.replay("replay-concurrent-1", request(), actor())
            }.get(5, TimeUnit.SECONDS)
            second.status shouldBeEqualTo AppointmentReplayAuditStatus.REQUESTED
            release.countDown()
            first.get(5, TimeUnit.SECONDS).status shouldBeEqualTo AppointmentReplayAuditStatus.EXECUTED
            calls.get() shouldBeEqualTo 1
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent first execution insert has a single winner`() {
        val start = CyclicBarrier(2)
        val calls = AtomicInteger()
        val service = AppointmentReplayService(database) { _, _ -> calls.incrementAndGet(); 1 }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<AppointmentReplayResult> {
                start.await(5, TimeUnit.SECONDS)
                service.replay("replay-concurrent-insert-1", request(), actor())
            }
            val second = executor.submit<AppointmentReplayResult> {
                start.await(5, TimeUnit.SECONDS)
                service.replay("replay-concurrent-insert-1", request(), actor())
            }
            val results = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            calls.get() shouldBeEqualTo 1
            results.any { it.status == AppointmentReplayAuditStatus.EXECUTED }.shouldBeTrue()
            results.all {
                it.status == AppointmentReplayAuditStatus.EXECUTED ||
                    it.status == AppointmentReplayAuditStatus.REQUESTED
            }.shouldBeTrue()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent insert conflict rejects a different partition binding`() {
        val start = CyclicBarrier(2)
        val first = request()
        val differentPartition = request().copy(partition = 1)
        val service = AppointmentReplayService(database) { _, _ -> 1 }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstResult = executor.submit<Result<AppointmentReplayResult>> {
                runCatching {
                    start.await(5, TimeUnit.SECONDS)
                    service.replay("replay-concurrent-partition-1", first, actor())
                }
            }
            val secondResult = executor.submit<Result<AppointmentReplayResult>> {
                runCatching {
                    start.await(5, TimeUnit.SECONDS)
                    service.replay("replay-concurrent-partition-1", differentPartition, actor())
                }
            }

            val results = listOf(firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS))
            results.count { it.isSuccess } shouldBeEqualTo 1
            results.count { it.exceptionOrNull() is IllegalArgumentException } shouldBeEqualTo 1
        } finally {
            executor.shutdownNow()
        }
    }

    private fun request(dryRun: Boolean = false) = AppointmentReplayRequest(
        identity = AppointmentConsumerIdentity(
            AppointmentLogicalConsumerId("notification"),
            AppointmentLogicalStreamId("appointment-events"),
        ),
        tenantGroupId = 7,
        clinicId = 31,
        approver = "operator-1",
        fromOffset = 10,
        toOffset = 12,
        dryRun = dryRun,
    )

    private fun actor() = AppointmentReplayActor(
        subject = "operator-1",
        tenantGroupIds = setOf(7),
        roles = setOf(TenantScopedAppointmentReplayAuthorizer.REPLAY_OPERATOR_ROLE),
        clinicIdsByTenant = mapOf(7L to setOf(31L)),
    )

    private fun legacyRequestHash(request: AppointmentReplayRequest): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                listOf(
                    request.identity.consumerId.value,
                    request.identity.streamId.value,
                    request.tenantGroupId,
                    request.clinicId,
                    request.approver,
                    request.fromOffset,
                    request.toOffset,
                ).joinToString("|").toByteArray(StandardCharsets.UTF_8),
            )
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

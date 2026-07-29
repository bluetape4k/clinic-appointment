package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier

/**
 * MySQL에서 commitment command의 멱등 재생과 자원 충돌 의미가 H2·PostgreSQL과
 * 동일한지 검증합니다.
 *
 * Flyway DDL 동등성만으로는 Exposed의 row lock, unique 위반 변환, transaction
 * rollback 동작을 증명할 수 없습니다. 따라서 실제 MySQL singleton container에서
 * 같은 direct-confirm command를 재생하고 겹치는 신규 확정을 거절합니다.
 */
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
internal class VisitCommitmentMySqlIntegrationTest : VisitCommitmentCommandTestSupport() {
    override fun createDatabase(): Database {
        val mysql = Containers.MySql8
        return Database.connect(
            url = mysql.jdbcUrl,
            driver = "com.mysql.cj.jdbc.Driver",
            user = mysql.username ?: "test",
            password = mysql.password ?: "",
        )
    }

    @Test
    fun `MySQL은 direct confirmation replay와 overlap conflict를 안정 계약으로 수렴한다`() {
        val service = commandService()
        val first = confirmDirect(service, "mysql-idempotent", "doctor-mysql-shared")
        val replay = confirmDirect(service, "mysql-idempotent", "doctor-mysql-shared")

        replay.commitment.id shouldBeEqualTo first.commitment.id
        replay.proposal.id shouldBeEqualTo first.proposal.id

        val conflict =
            assertFailsWith<AppointmentCommitmentCommandException> {
                confirmDirect(service, "mysql-conflict", "doctor-mysql-shared")
            }

        conflict.code shouldBeEqualTo AppointmentCommitmentCommandError.RESOURCE_CONFLICT
        currentConfirmation(first.commitment.appointmentId).allocations.size shouldBeEqualTo 1
    }

    @Test
    fun `MySQL 최초 mutex 생성 경쟁은 한 winner와 한 안정 충돌로 수렴한다`() {
        val service = commandService()
        val resourceId = "doctor-mysql-concurrent"
        val barrier = CyclicBarrier(2)
        val results = ConcurrentLinkedQueue<Result<AppointmentCommitmentCommandResult>>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .addAll(
                {
                    barrier.await()
                    results += runCatching { confirmDirect(service, "mysql-concurrent-a", resourceId) }
                },
                {
                    barrier.await()
                    results += runCatching { confirmDirect(service, "mysql-concurrent-b", resourceId) }
                },
            ).run()

        val successful = results.mapNotNull(Result<AppointmentCommitmentCommandResult>::getOrNull)
        val allFailures = results.mapNotNull(Result<AppointmentCommitmentCommandResult>::exceptionOrNull)
        successful shouldHaveSize 1
        val failures =
            allFailures
                .filterIsInstance<AppointmentCommitmentCommandException>()
        failures shouldHaveSize 1
        failures.single().code shouldBeEqualTo AppointmentCommitmentCommandError.RESOURCE_CONFLICT
    }
}

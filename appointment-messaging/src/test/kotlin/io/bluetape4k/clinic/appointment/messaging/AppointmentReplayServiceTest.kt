package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AppointmentReplayServiceTest {
    private val database = Database.connect(
        url = "jdbc:h2:mem:appointment-replay-service;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )

    @BeforeEach
    fun setUp() {
        transaction(database) {
            SchemaUtils.drop(AppointmentConsumerReplayAuditTable)
            SchemaUtils.create(AppointmentConsumerReplayAuditTable)
        }
    }

    @Test
    fun `dry run writes audit and never invokes replay source`() {
        var calls = 0
        val service = AppointmentReplayService(database) { _, _ -> calls++ }

        val result = service.replay(
            requestId = "replay-dry-run-1",
            request = request(dryRun = true),
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

        service.replay("replay-transition-1", request(dryRun = true))
        val result = service.replay("replay-transition-1", request(dryRun = false))

        result.status shouldBeEqualTo AppointmentReplayAuditStatus.EXECUTED
        result.replayedRecords shouldBeEqualTo 2
        calls shouldBeEqualTo 1
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
}

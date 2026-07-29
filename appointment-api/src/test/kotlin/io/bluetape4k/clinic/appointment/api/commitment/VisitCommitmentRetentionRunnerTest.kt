package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 운영 runner가 활성 SaaS scope를 열거하고 실제 retention과 low-cardinality metric을
 * 연결하는지 검증합니다.
 */
class VisitCommitmentRetentionRunnerTest {

    @Test
    fun `runs bounded retention for active clinics and records the operational result`() {
        val now = Instant.parse("2026-09-01T00:00:00Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val database = Database.connect(
            "jdbc:h2:mem:visit_retention_runner_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                AppointmentCommandIdempotencies,
                SchedulingInboxEvents,
                SchedulingOutboxEvents,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
            Clinics.insert {
                it[id] = EntityID(7L, Clinics)
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Retention Clinic"
            }
            Clinics.insert {
                it[id] = EntityID(8L, Clinics)
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Second Retention Clinic"
            }
            AppointmentCommandIdempotencies.insert {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[clinicId] = EntityID(7L, Clinics)
                it[actorScopeHash] = "a".repeat(64)
                it[idempotencyKeyHash] = "old-key"
                it[commandHash] = "b".repeat(64)
                it[createdAt] = now.minusSeconds(31L * 24 * 60 * 60)
            }
        }
        val registry = SimpleMeterRegistry()
        val runner = VisitCommitmentRetentionRunner(
            database = database,
            retentionService = VisitCommitmentRetentionService(database, clock),
            metrics = AppointmentCommitmentMetrics(registry),
            clock = clock,
            scopePageSize = 1,
        )

        val summary = runner.runOnce()

        summary shouldBeEqualTo VisitCommitmentRetentionRunSummary(
            totalScopes = 2,
            successfulScopes = 2,
            failedScopes = 0,
            affectedRecords = 1,
        )
        transaction(database) {
            AppointmentCommandIdempotencies.selectAll().count() shouldBeEqualTo 0L
        }
        registry
            .get("appointment.commitment.retention.run.latency")
            .tag("tenant", "tenant-one")
            .tag("clinic", "clinic-7")
            .tag("result", "SUCCESS")
            .timer()
            .count() shouldBeEqualTo 1L
    }
}

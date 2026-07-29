package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.integration.QuarantineStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommandIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * tenant별 bounded retention이 경계, 미전달·미해결·poison·legal hold 보존 계약을
 * 지키며 실제로 삭제·payload 만료한 ID를 반환하는지 검증한다.
 */
class VisitCommitmentRetentionServiceTest {

    private val now = Instant.parse("2026-09-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var database: Database
    private lateinit var service: VisitCommitmentRetentionService

    @BeforeEach
    fun setup() {
        database = Database.connect(
            "jdbc:h2:mem:visit_retention_${System.nanoTime()};DB_CLOSE_DELAY=-1",
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
            listOf(
                SchedulingQuarantineAuditEvents,
                SchedulingQuarantineEvents,
                SchedulingOutboxEvents,
                SchedulingInboxEvents,
                AppointmentCommandIdempotencies,
                Clinics,
                TenantGroups,
            ).forEach { it.deleteAll() }
            seedClinic()
        }
        service = VisitCommitmentRetentionService(database, clock, batchSizePerTenant = 2)
    }

    @Test
    fun `deletes only records strictly older than each retention boundary`() {
        val oldIdempotency = insertIdempotency("old", now.minusSeconds(DAYS_30 + 1))
        val boundaryIdempotency = insertIdempotency("boundary", now.minusSeconds(DAYS_30))
        val oldInbox = insertInbox("processed-old", SchedulingInboxStatus.PROCESSED, now.minusSeconds(DAYS_30 + 1))
        val boundaryInbox = insertInbox("processed-boundary", SchedulingInboxStatus.PROCESSED, now.minusSeconds(DAYS_30))
        val oldOutbox = insertOutbox("published-old", SchedulingOutboxStatus.PUBLISHED, now.minusSeconds(DAYS_7 + 1))
        val boundaryOutbox = insertOutbox("published-boundary", SchedulingOutboxStatus.PUBLISHED, now.minusSeconds(DAYS_7))

        val result = service.cleanupTenant(tenantGroupId = 1L, clinicId = 7L)

        result.deletedIdempotencyIds shouldBeEqualTo listOf(oldIdempotency)
        result.deletedInboxIds shouldBeEqualTo listOf(oldInbox)
        result.deletedOutboxIds shouldBeEqualTo listOf(oldOutbox)
        retainedIds(AppointmentCommandIdempotencies) shouldBeEqualTo listOf(boundaryIdempotency)
        retainedIds(SchedulingInboxEvents) shouldBeEqualTo listOf(boundaryInbox)
        retainedIds(SchedulingOutboxEvents) shouldBeEqualTo listOf(boundaryOutbox)
    }

    @Test
    fun `preserves undelivered unresolved poison and legal hold while enforcing the tenant batch cap`() {
        val poisonInbox = insertInbox("poison", SchedulingInboxStatus.QUARANTINED, now.minusSeconds(DAYS_30 + 100))
        val undeliveredOutbox = insertOutbox("pending", SchedulingOutboxStatus.PENDING, now.minusSeconds(DAYS_7 + 100))
        val openQuarantine = insertQuarantine("open", QuarantineStatus.OPEN, legalHold = false)
        val heldQuarantine = insertQuarantine("held", QuarantineStatus.RELEASE_APPROVED, legalHold = true)
        val releasable = listOf(
            insertQuarantine("resolved-1", QuarantineStatus.RELEASE_APPROVED, legalHold = false),
            insertQuarantine("resolved-2", QuarantineStatus.RELEASE_DENIED, legalHold = false),
            insertQuarantine("resolved-3", QuarantineStatus.RELEASE_APPROVED, legalHold = false),
        )

        val first = service.cleanupTenant(tenantGroupId = 1L, clinicId = 7L)

        first.expiredQuarantinePayloadIds shouldBeEqualTo releasable.take(2)
        retainedIds(SchedulingInboxEvents) shouldBeEqualTo listOf(poisonInbox)
        retainedIds(SchedulingOutboxEvents) shouldBeEqualTo listOf(undeliveredOutbox)
        retainedQuarantineIds() shouldBeEqualTo listOf(openQuarantine, heldQuarantine) + releasable.drop(2)
    }

    private fun seedClinic() {
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
    }

    private fun insertIdempotency(key: String, createdAt: Instant): Long = transaction(database) {
        AppointmentCommandIdempotencies.insertAndGetId {
            it[tenantGroupId] = EntityID(1L, TenantGroups)
            it[clinicId] = EntityID(7L, Clinics)
            it[actorScopeHash] = "a".repeat(64)
            it[idempotencyKeyHash] = key
            it[commandHash] = "b".repeat(64)
            it[AppointmentCommandIdempotencies.createdAt] = createdAt
        }.value
    }

    private fun insertInbox(eventId: String, status: SchedulingInboxStatus, receivedAt: Instant): Long =
        transaction(database) {
            SchedulingInboxEvents.insertAndGetId {
                it[SchedulingInboxEvents.eventId] = eventId
                it[eventType] = "TEST"
                it[producer] = "test"
                it[sourceAuthority] = "test"
                it[sourceAggregateId] = eventId
                it[sourceAggregateVersion] = 1L
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[clinicId] = EntityID(7L, Clinics)
                it[payloadHash] = "c".repeat(64)
                it[SchedulingInboxEvents.status] = status
                it[occurredAt] = receivedAt
                it[SchedulingInboxEvents.receivedAt] = receivedAt
                it[processedAt] = if (status == SchedulingInboxStatus.PROCESSED) receivedAt else null
            }.value
        }

    private fun insertOutbox(eventId: String, status: SchedulingOutboxStatus, createdAt: Instant): Long =
        transaction(database) {
            SchedulingOutboxEvents.insertAndGetId {
                it[SchedulingOutboxEvents.eventId] = eventId
                it[correlationId] = eventId
                it[eventType] = "TEST"
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[clinicId] = EntityID(7L, Clinics)
                it[aggregateType] = "TEST"
                it[aggregateId] = eventId
                it[schemaVersion] = 1
                it[payloadJson] = "{}"
                it[SchedulingOutboxEvents.status] = status
                it[SchedulingOutboxEvents.createdAt] = createdAt
                it[publishedAt] = if (status == SchedulingOutboxStatus.PUBLISHED) createdAt else null
            }.value
        }

    private fun insertQuarantine(eventId: String, status: QuarantineStatus, legalHold: Boolean): Long =
        transaction(database) {
            SchedulingQuarantineEvents.insertAndGetId {
                it[SchedulingQuarantineEvents.eventId] = eventId
                it[eventType] = "TEST"
                it[envelopeHash] = "d".repeat(64)
                it[encryptedOriginalEnvelope] = "ciphertext"
                it[encryptionKeyId] = "key"
                it[producer] = "test"
                it[sourceAuthority] = "test"
                it[schemaVersion] = 1
                it[sourceAggregateId] = eventId
                it[sourceAggregateVersion] = 1L
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[clinicId] = EntityID(7L, Clinics)
                it[reasonCode] = "TEST"
                it[detectedAt] = now.minusSeconds(DAYS_90 + 1)
                it[correlationId] = eventId
                it[retentionClass] = io.bluetape4k.clinic.appointment.event.integration.QuarantineRetentionClass.STANDARD
                it[payloadExpiresAt] = now.minusSeconds(DAYS_90 + 1)
                it[SchedulingQuarantineEvents.legalHold] = legalHold
                it[SchedulingQuarantineEvents.status] = status
            }.value
        }

    private fun retainedIds(table: org.jetbrains.exposed.v1.core.dao.id.LongIdTable): List<Long> =
        transaction(database) { table.selectAll().map { it[table.id].value }.sorted() }

    private fun retainedQuarantineIds(): List<Long> = transaction(database) {
        SchedulingQuarantineEvents.selectAll()
            .filter { it[SchedulingQuarantineEvents.encryptedOriginalEnvelope] != null }
            .map { it[SchedulingQuarantineEvents.id].value }
            .sorted()
    }

    private companion object {
        const val DAYS_7 = 7L * 24 * 60 * 60
        const val DAYS_30 = 30L * 24 * 60 * 60
        const val DAYS_90 = 90L * 24 * 60 * 60
    }
}

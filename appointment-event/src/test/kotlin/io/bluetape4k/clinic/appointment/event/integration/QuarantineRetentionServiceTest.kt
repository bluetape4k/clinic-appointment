package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class QuarantineRetentionServiceTest {

    private val now = Instant.parse("2026-09-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val repository = SchedulingQuarantineRepository(clock)
    private val service = QuarantineRetentionService(repository, clock)
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:quarantine_retention_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Retention Clinic"
            }.value
        }
    }

    @Test
    fun `expiry deletes ciphertext only and preserves metadata plus audit`() {
        val id = transaction {
            repository.recordDetected(detection("expired-event", now.minusSeconds(1))).id
        }

        val result = transaction {
            service.expireEligiblePayloads(actor = "retention-job", reason = "ttl")
        }

        result.expiredCount shouldBeEqualTo 1
        transaction {
            val record = repository.findById(id)!!
            record.encryptedOriginalEnvelope.shouldBeNull()
            record.envelopeHash shouldBeEqualTo "b".repeat(64)
            record.reasonCode shouldBeEqualTo "CATALOG_RETIRED"
            record.status shouldBeEqualTo QuarantineStatus.PAYLOAD_EXPIRED
            repository.auditTrail(id).map { it.action } shouldBeEqualTo
                listOf(QuarantineAuditAction.DETECTED, QuarantineAuditAction.PAYLOAD_EXPIRED)
        }
    }

    @Test
    fun `legal hold blocks expiry until the hold is removed`() {
        val id = transaction {
            val record = repository.recordDetected(detection("held-event", now.minusSeconds(1)))
            repository.setLegalHold(record.id, enabled = true, actor = "legal", reason = "case")
            record.id
        }

        transaction {
            service.expireEligiblePayloads(actor = "retention-job", reason = "ttl").expiredCount shouldBeEqualTo 0
            repository.findById(id)!!.encryptedOriginalEnvelope shouldBeEqualTo encryptedEnvelope("held-event")
            repository.setLegalHold(id, enabled = false, actor = "legal", reason = "released")
            service.expireEligiblePayloads(actor = "retention-job", reason = "ttl").expiredCount shouldBeEqualTo 1
            repository.findById(id)!!.encryptedOriginalEnvelope.shouldBeNull()
        }
    }

    private fun detection(eventId: String, expiresAt: Instant) = QuarantineDetection(
        eventId = eventId,
        eventType = "PurchaseCompleted",
        protectedEnvelope = ProtectedQuarantineEnvelope(
            ciphertext = encryptedEnvelope(eventId),
            keyId = "quarantine-key-1",
            envelopeHash = "b".repeat(64),
        ),
        producer = "commerce-service",
        sourceAuthority = "commerce",
        schemaVersion = 2,
        sourceAggregateId = "purchase-aggregate-$eventId",
        sourceAggregateVersion = 1L,
        tenantGroupId = 1L,
        clinicId = clinicId,
        reasonCode = "CATALOG_RETIRED",
        detectedAt = Instant.parse("2026-07-26T05:10:00Z"),
        correlationId = "correlation-$eventId",
        retentionClass = QuarantineRetentionClass.STANDARD,
        payloadExpiresAt = expiresAt,
    )

    private fun encryptedEnvelope(seed: String): String =
        Base64.getEncoder().encodeToString(seed.padEnd(32, '-').toByteArray())
}

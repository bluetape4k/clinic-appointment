package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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

        val result = service.expireEligiblePayloads(actor = "retention-job", reason = "ttl")

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

        service.expireEligiblePayloads(actor = "retention-job", reason = "ttl").expiredCount shouldBeEqualTo 0
        transaction {
            repository.findById(id)!!.encryptedOriginalEnvelope shouldBeEqualTo encryptedEnvelope("held-event")
            repository.setLegalHold(id, enabled = false, actor = "legal", reason = "released")
        }
        service.expireEligiblePayloads(actor = "retention-job", reason = "ttl").expiredCount shouldBeEqualTo 1
        transaction {
            repository.findById(id)!!.encryptedOriginalEnvelope.shouldBeNull()
        }
    }

    @Test
    fun `expiry rechecks legal hold after selecting a candidate`() {
        var selectedId = 0L
        val raceAwareRepository = SchedulingQuarantineRepository(
            clock = clock,
            expiryObserver = QuarantineExpiryObserver { quarantineId ->
                selectedId = quarantineId
                SchedulingQuarantineEvents.update({ SchedulingQuarantineEvents.id eq quarantineId }) {
                    it[legalHold] = true
                }
            },
        )
        val id = transaction {
            raceAwareRepository.recordDetected(detection("race-held-event", now.minusSeconds(1))).id
        }

        val result = QuarantineRetentionService(raceAwareRepository, clock)
            .expireEligiblePayloads(actor = "retention-job", reason = "ttl")

        selectedId shouldBeEqualTo id
        result.expiredCount shouldBeEqualTo 0
        transaction {
            raceAwareRepository.findById(id)!!.encryptedOriginalEnvelope shouldBeEqualTo
                encryptedEnvelope("race-held-event")
            raceAwareRepository.auditTrail(id).map { it.action } shouldBeEqualTo
                listOf(QuarantineAuditAction.DETECTED)
        }
    }

    @Test
    fun `expiry processes at most the configured batch size in deterministic order`() {
        val ids = transaction {
            listOf("batch-1", "batch-2", "batch-3").mapIndexed { index, eventId ->
                repository.recordDetected(detection(eventId, now.minusSeconds((3 - index).toLong()))).id
            }
        }
        val boundedService = QuarantineRetentionService(repository, clock, batchSize = 2)

        boundedService.expireEligiblePayloads("retention-job", "ttl").expiredCount shouldBeEqualTo 2

        transaction {
            repository.findById(ids[0])!!.encryptedOriginalEnvelope.shouldBeNull()
            repository.findById(ids[1])!!.encryptedOriginalEnvelope.shouldBeNull()
            repository.findById(ids[2])!!.encryptedOriginalEnvelope shouldBeEqualTo encryptedEnvelope("batch-3")
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

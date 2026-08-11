package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class SchedulingQuarantineRepositoryTest {

    private val now = Instant.parse("2026-07-26T05:10:00Z")
    private val repository = SchedulingQuarantineRepository(Clock.fixed(now, ZoneOffset.UTC))
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:scheduling_quarantine_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Quarantine Clinic"
            }.value
        }
    }

    @Test
    fun `detected quarantine stores encrypted content and immutable detected audit`() {
        val protectedEnvelope = AesGcmQuarantineEnvelopeProtector(
            encryptionKey = ByteArray(32) { index -> index.toByte() },
            keyId = "quarantine-key-1",
        ).protect(envelope())
        val record = transaction {
            repository.recordDetected(detection(protectedEnvelope = protectedEnvelope))
        }

        transaction {
            val quarantine = repository.findById(record.id)!!
            quarantine.encryptedOriginalEnvelope!!.contains("patient-token").shouldBeFalse()
            quarantine.encryptedOriginalEnvelope shouldBeEqualTo protectedEnvelope.ciphertext
            quarantine.envelopeHash shouldBeEqualTo protectedEnvelope.envelopeHash
            quarantine.encryptionKeyId shouldBeEqualTo "quarantine-key-1"
            quarantine.reasonCode shouldBeEqualTo "TRUST_FAILED"
            quarantine.status shouldBeEqualTo QuarantineStatus.OPEN
            repository.auditTrail(record.id).map { it.action } shouldBeEqualTo
                listOf(QuarantineAuditAction.DETECTED)
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 1L
            SchedulingQuarantineAuditEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `inspection dry run and release denial append privileged audit rows only`() {
        val record = transaction {
            repository.recordDetected(detection(reasonCode = "REFUND_REVIEW"))
        }

        transaction {
            repository.recordInspection(record.id, "ops-user", "case review")
            repository.recordDryRun(record.id, record.eventId, "ops-user", "dry run before release", "diff-hash")
            repository.denyRelease(record.id, "ops-user", "trust still failed")

            val actions = repository.auditTrail(record.id).map { it.action }
            actions shouldBeEqualTo listOf(
                QuarantineAuditAction.DETECTED,
                QuarantineAuditAction.INSPECTED,
                QuarantineAuditAction.DRY_RUN,
                QuarantineAuditAction.RELEASE_DENIED,
            )
            repository.findById(record.id)!!.status shouldBeEqualTo QuarantineStatus.RELEASE_DENIED
        }
    }

    @Test
    fun `release requires two approval references for sensitive reasons`() {
        val record = transaction {
            repository.recordDetected(detection(reasonCode = "CONSENT_REQUIRED"))
        }

        assertFailsWith<IllegalArgumentException> {
            transaction {
                repository.approveRelease(
                    record.id,
                    "ops-user",
                    "single approval",
                    QuarantineReleaseEvidence(listOf("manager-1")),
                )
            }
        }

        transaction {
            repository.approveRelease(
                record.id,
                "ops-user",
                "dual approval",
                QuarantineReleaseEvidence(listOf("manager-1", "security-1")),
            )
            repository.findById(record.id)!!.status shouldBeEqualTo QuarantineStatus.RELEASE_APPROVED
            repository.auditTrail(record.id).last().approvalReferences shouldBeEqualTo "manager-1,security-1"
        }
    }

    @Test
    fun `trust failure release requires source correction and trust revalidation`() {
        val record = transaction {
            repository.recordDetected(detection(reasonCode = "SIGNATURE_INVALID"))
        }

        assertFailsWith<IllegalArgumentException> {
            transaction {
                repository.approveRelease(
                    record.id,
                    "ops-user",
                    "missing revalidation",
                    QuarantineReleaseEvidence(listOf("security-1")),
                )
            }
        }

        transaction {
            repository.approveRelease(
                record.id,
                "ops-user",
                "source corrected and trust revalidated",
                QuarantineReleaseEvidence(
                    approvalReferences = listOf("security-1"),
                    sourceCorrectionReference = "source-fix-17",
                    trustRevalidated = true,
                ),
            )
            repository.findById(record.id)!!.status shouldBeEqualTo QuarantineStatus.RELEASE_APPROVED
        }
    }

    @Test
    fun `외부 fact 구조와 routing 실패도 source correction과 trust 재검증 없이는 release할 수 없다`() {
        listOf(
            "SCHEMA_VERSION_NOT_ALLOWED",
            "PAYLOAD_CONTRACT_INVALID",
            "ROUTING_METADATA_MISMATCH",
        ).forEachIndexed { index, reasonCode ->
            val record = transaction {
                repository.recordDetected(
                    detection(
                        eventId = "external-fact-release-$index",
                        reasonCode = reasonCode,
                    ),
                )
            }

            assertFailsWith<IllegalArgumentException> {
                transaction {
                    repository.approveRelease(
                        record.id,
                        "ops-user",
                        "missing source correction",
                        QuarantineReleaseEvidence(listOf("security-1")),
                    )
                }
            }
        }
    }

    @Test
    fun `non sensitive release requires one approval reference`() {
        val record = transaction {
            repository.recordDetected(detection(reasonCode = "UNKNOWN_CATALOG"))
        }

        transaction {
            repository.approveRelease(
                record.id,
                "ops-user",
                "catalog restored",
                QuarantineReleaseEvidence(listOf("catalog-owner-1")),
            )
            repository.findById(record.id)!!.status shouldBeEqualTo QuarantineStatus.RELEASE_APPROVED
        }
    }

    @Test
    fun `concurrent payload expiry wins over release approval without stale audit`() {
        val raceAwareRepository = SchedulingQuarantineRepository(
            clock = Clock.fixed(now, ZoneOffset.UTC),
            transitionObserver = QuarantineTransitionObserver { quarantineId, nextStatus ->
                if (nextStatus == QuarantineStatus.RELEASE_APPROVED) {
                    SchedulingQuarantineEvents.update({ SchedulingQuarantineEvents.id eq quarantineId }) {
                        it[encryptedOriginalEnvelope] = null
                        it[status] = QuarantineStatus.PAYLOAD_EXPIRED
                    }
                }
            },
        )
        val record = transaction {
            raceAwareRepository.recordDetected(detection(reasonCode = "UNKNOWN_CATALOG"))
        }

        transaction {
            assertFailsWith<IllegalStateException> {
                raceAwareRepository.approveRelease(
                    record.id,
                    "ops-user",
                    "catalog restored",
                    QuarantineReleaseEvidence(listOf("catalog-owner-1")),
                )
            }
            raceAwareRepository.findById(record.id)!!.status shouldBeEqualTo QuarantineStatus.PAYLOAD_EXPIRED
            raceAwareRepository.auditTrail(record.id).map { it.action } shouldBeEqualTo
                listOf(QuarantineAuditAction.DETECTED)
        }
    }

    @Test
    fun `payload expired quarantine rejects release dry run and redrive transitions`() {
        val record = transaction {
            repository.recordDetected(
                detection(
                    reasonCode = "UNKNOWN_CATALOG",
                    payloadExpiresAt = now,
                )
            ).also {
                repository.expireEligiblePayloads(now, "retention-job", "ttl", batchSize = 10)
            }
        }

        listOf<() -> Unit>(
            {
                repository.approveRelease(
                    record.id,
                    "ops-user",
                    "late approval",
                    QuarantineReleaseEvidence(listOf("catalog-owner-1")),
                )
            },
            { repository.denyRelease(record.id, "ops-user", "late denial") },
            { repository.recordDryRun(record.id, record.eventId, "ops-user", "late preview", "diff-hash") },
            {
                repository.recordRedriveAttempt(
                    record.id,
                    record.eventId,
                    record.envelopeHash,
                    "ops-user",
                    "late redrive",
                    listOf("catalog-owner-1"),
                )
            },
        ).forEach { transition ->
            assertFailsWith<IllegalArgumentException> {
                transaction { transition() }
            }
        }

        transaction {
            repository.findById(record.id)!!.status shouldBeEqualTo QuarantineStatus.PAYLOAD_EXPIRED
            repository.auditTrail(record.id).map { it.action } shouldBeEqualTo
                listOf(QuarantineAuditAction.DETECTED, QuarantineAuditAction.PAYLOAD_EXPIRED)
        }
    }

    @Test
    fun `invalid reason and malformed ciphertext are rejected before insert`() {
        assertFailsWith<IllegalArgumentException> {
            transaction {
                repository.recordDetected(detection(reasonCode = "event-123"))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            transaction {
                repository.recordDetected(
                    detection(
                        protectedEnvelope = ProtectedQuarantineEnvelope(
                            ciphertext = "not-valid-base64".repeat(3),
                            keyId = "quarantine-key-1",
                            envelopeHash = "a".repeat(64),
                        )
                    )
                )
            }
        }
        transaction {
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun detection(
        eventId: String = "event-1",
        reasonCode: String = "TRUST_FAILED",
        protectedEnvelope: ProtectedQuarantineEnvelope = ProtectedQuarantineEnvelope(
            ciphertext = encryptedEnvelope("ciphertext-v1"),
            keyId = "quarantine-key-1",
            envelopeHash = "a".repeat(64),
        ),
        payloadExpiresAt: Instant = Instant.parse("2026-08-25T05:10:00Z"),
    ) = QuarantineDetection(
        eventId = eventId,
        eventType = "PurchaseCompleted",
        protectedEnvelope = protectedEnvelope,
        producer = "commerce-service",
        sourceAuthority = "commerce",
        schemaVersion = 2,
        sourceAggregateId = "purchase-aggregate",
        sourceAggregateVersion = 1L,
        tenantGroupId = 1L,
        clinicId = clinicId,
        reasonCode = reasonCode,
        detectedAt = now,
        correlationId = "correlation-1",
        retentionClass = QuarantineRetentionClass.STANDARD,
        payloadExpiresAt = payloadExpiresAt,
    )

    private fun encryptedEnvelope(seed: String): String =
        Base64.getEncoder().encodeToString(seed.padEnd(32, '-').toByteArray())

    private fun envelope(): UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        val payload = PurchaseCompletedEvent(
            sourceAggregateId = "purchase-aggregate",
            sourceAggregateVersion = 1L,
            tenantGroupId = 1L,
            clinicId = clinicId,
            sourcePurchaseAuthority = "commerce",
            sourcePurchaseId = "purchase-1",
            patientReferenceToken = "patient-token",
            catalogSourceAuthority = "product-catalog",
            productId = "laser-care",
            catalogVersion = 7L,
            bookingPreference = BookingPreferenceSnapshot.NotProvided,
        )
        return UntrustedSchedulingEventEnvelope(
            eventId = "event-1",
            eventType = "PurchaseCompleted",
            occurredAt = now.minusSeconds(10),
            receivedAt = now,
            producer = "commerce-service",
            issuer = "commerce-issuer",
            audience = "appointment-service",
            keyId = "commerce-key",
            algorithm = "EdDSA",
            schemaVersion = 2,
            correlationId = "correlation-1",
            payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
            signature = "valid-signature",
            payload = payload,
        )
    }
}

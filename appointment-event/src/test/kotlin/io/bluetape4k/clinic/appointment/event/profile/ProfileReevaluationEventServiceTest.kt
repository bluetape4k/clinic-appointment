package io.bluetape4k.clinic.appointment.event.profile

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.event.integration.AesGcmQuarantineEnvelopeProtector
import io.bluetape4k.clinic.appointment.event.integration.QuarantineRetentionClass
import io.bluetape4k.clinic.appointment.event.integration.QuarantineEnvelopeProtector
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventRepository
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventSignatureVerifier
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventTrustVerifier
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxStatus
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineRepository
import io.bluetape4k.clinic.appointment.event.integration.UntrustedSchedulingEventEnvelope
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.full.memberProperties

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfileReevaluationEventServiceTest {

    private val now = Instant.parse("2026-07-30T09:30:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val fingerprint = "a".repeat(64)
    private val assessmentHash = "b".repeat(64)
    private val databaseUrl =
        "jdbc:h2:mem:profile_reevaluation_event_${System.nanoTime()};" +
            "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
    private val database = Database.connect(
        databaseUrl,
        driver = "org.h2.Driver",
        user = "sa",
        password = "",
    )

    @BeforeEach
    fun setup() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                SchedulingInboxEvents,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
                ProfileReevaluationHeads,
                ProfileReevaluationJobs,
                ProfileReevaluationOutcomes,
            )
            dropProfileEnumChecksCoveredByMigrationTests()
            ProfileReevaluationOutcomes.deleteAll()
            ProfileReevaluationJobs.deleteAll()
            ProfileReevaluationHeads.deleteAll()
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            SchedulingInboxEvents.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()
            seedTenantAndClinic(tenantGroupId = 1L, clinicId = 41L)
            seedTenantAndClinic(tenantGroupId = 2L, clinicId = 42L)
        }
    }

    /**
     * 이 테스트는 event transaction에 집중합니다. 세 CHECK의 방언 동등성은
     * ProfileReevaluationDialectIntegrationTest에서 별도로 검증합니다.
     */
    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.dropProfileEnumChecksCoveredByMigrationTests() {
        exec(
            """
            ALTER TABLE scheduling_profile_reevaluation_jobs
            DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_job_status
            """.trimIndent(),
        )
        exec(
            """
            ALTER TABLE scheduling_profile_reevaluation_jobs
            DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_priority_class
            """.trimIndent(),
        )
        exec(
            """
            ALTER TABLE scheduling_profile_reevaluation_outcomes
            DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_outcome_type
            """.trimIndent(),
        )
    }

    @Test
    fun `프로필 변경 계약은 재평가에 필요한 비식별 필드만 가진다`() {
        PatientSchedulingAssessmentChanged::class.memberProperties
            .map { it.name }
            .toSet() shouldBeEqualTo setOf(
                "eventId",
                "tenantGroupId",
                "clinicId",
                "patientReferenceFingerprint",
                "profileRevision",
                "materialChange",
                "assessmentRef",
                "assessmentHash",
                "occurredAt",
            )
    }

    @Test
    fun `중복과 역순 이벤트를 처리해도 최신 revision 작업 하나만 실행 가능하다`() {
        val service = service()

        service.accept(envelope(eventId = "profile-7", revision = 7L)).status shouldBeEqualTo
            ProfileReevaluationEventStatus.PROCESSED
        service.accept(envelope(eventId = "profile-7", revision = 7L)).status shouldBeEqualTo
            ProfileReevaluationEventStatus.DUPLICATE
        service.accept(envelope(eventId = "profile-6", revision = 6L)).status shouldBeEqualTo
            ProfileReevaluationEventStatus.PROCESSED
        service.accept(envelope(eventId = "profile-8", revision = 8L)).status shouldBeEqualTo
            ProfileReevaluationEventStatus.PROCESSED

        transaction {
            val scope = ProfileReferenceFingerprintValidator.scope(1L, 41L, fingerprint)
            val repository = ProfileReevaluationRepository()

            repository.findHead(scope)!!.latestRevision shouldBeEqualTo 8L
            repository.findRunnableJobs(scope).single().targetRevision shouldBeEqualTo 8L
            repository.findJobs(scope).shouldHaveSize(2)
            SchedulingInboxEvents.selectAll().toList().shouldHaveSize(3)
        }
    }

    @Test
    fun `비물질 변경은 inbox 처리만 완료하고 재평가 작업을 만들지 않는다`() {
        val result = service().accept(
            envelope(
                eventId = "profile-no-material-change",
                revision = 9L,
                materialChange = false,
            ),
        )

        result.status shouldBeEqualTo ProfileReevaluationEventStatus.NO_MATERIAL_CHANGE
        transaction {
            val inbox = SchedulingInboxEvents.selectAll().single()
            inbox[SchedulingInboxEvents.status] shouldBeEqualTo SchedulingInboxStatus.PROCESSED
            ProfileReevaluationHeads.selectAll().toList().shouldHaveSize(0)
            ProfileReevaluationJobs.selectAll().toList().shouldHaveSize(0)
        }
    }

    @Test
    fun `불신 이벤트는 멱등 격리하고 inbox와 재평가 작업을 만들지 않는다`() {
        val service = service()
        val rejected = listOf(
            envelope(eventId = "bad-producer").copy(producer = "unknown-crm"),
            envelope(eventId = "bad-signature").copy(signature = "invalid"),
            envelope(eventId = "bad-schema").copy(schemaVersion = 2),
            envelope(eventId = "bad-scope", clinicId = 42L),
            envelope(eventId = "bad-fingerprint", fingerprint = "A".repeat(64)),
        )

        rejected.forEach { raw ->
            service.accept(raw).status shouldBeEqualTo ProfileReevaluationEventStatus.QUARANTINED
        }
        service.accept(rejected[1]).status shouldBeEqualTo ProfileReevaluationEventStatus.DUPLICATE

        transaction {
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(rejected.size - 1)
            SchedulingQuarantineAuditEvents.selectAll().toList().shouldHaveSize(rejected.size - 1)
            SchedulingInboxEvents.selectAll().toList().shouldHaveSize(0)
            ProfileReevaluationHeads.selectAll().toList().shouldHaveSize(0)
            ProfileReevaluationJobs.selectAll().toList().shouldHaveSize(0)

            val quarantined = SchedulingQuarantineEvents.selectAll()
                .where { SchedulingQuarantineEvents.eventId eq "bad-schema" }
                .single()
            val ciphertext = quarantined[SchedulingQuarantineEvents.encryptedOriginalEnvelope]!!
            ciphertext.contains("A".repeat(64)).shouldBeFalse()
            ciphertext.contains("assessment:7").shouldBeFalse()
            SchedulingQuarantineEvents.selectAll()
                .where { SchedulingQuarantineEvents.eventId eq "bad-fingerprint" }
                .toList()
                .shouldHaveSize(0)
        }
    }

    @Test
    fun `계약 상한을 넘는 프로필 이벤트는 암호화 전에 닫힌 실패로 거절한다`() {
        var protectCalls = 0
        val service =
            service(
                protector =
                    QuarantineEnvelopeProtector {
                        protectCalls++
                        error("unbounded envelope must not reach encryption")
                    },
            )

        val result =
            service.accept(
                envelope(
                    eventId = "oversized-profile-event",
                    assessmentRef = "x".repeat(513),
                ),
            )

        result shouldBeEqualTo
            ProfileReevaluationEventResult(
                ProfileReevaluationEventStatus.QUARANTINED,
                "PAYLOAD_CONTRACT_INVALID",
            )
        protectCalls shouldBeEqualTo 0
        transaction {
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(0)
            SchedulingInboxEvents.selectAll().toList().shouldHaveSize(0)
        }
    }

    @Test
    fun `불신 또는 계약 위반 프로필 이벤트는 bounded rejected 관측 결과를 남긴다`() {
        val observed = mutableListOf<ProfileReevaluationEventObservationResult>()
        val service =
            service(
                observer = ProfileReevaluationEventObserver { result ->
                    observed += result
                },
            )

        service.accept(envelope(eventId = "metric-bad-signature").copy(signature = "invalid"))
        service.accept(
            envelope(
                eventId = "metric-oversized",
                assessmentRef = "x".repeat(513),
            ),
        )

        observed shouldBeEqualTo listOf(
            ProfileReevaluationEventObservationResult.REJECTED,
            ProfileReevaluationEventObservationResult.REJECTED,
        )
    }

    private fun service(
        protector: QuarantineEnvelopeProtector =
            AesGcmQuarantineEnvelopeProtector(
                encryptionKey = ByteArray(32) { index -> index.toByte() },
                keyId = "quarantine-key-1",
            ),
        observer: ProfileReevaluationEventObserver = ProfileReevaluationEventObserver.NoOp,
    ) = ProfileReevaluationEventService(
        trustVerifier = SchedulingEventTrustVerifier(
            signatureVerifier = SchedulingEventSignatureVerifier { it.signature == "valid" },
            allowedProducers = setOf("crm-service"),
            allowedKeyIds = setOf("crm-key"),
            allowedAlgorithms = setOf("EdDSA"),
            expectedIssuer = "crm-issuer",
            expectedAudience = "appointment-service",
            replayWindow = Duration.ofMinutes(15),
            clock = clock,
        ),
        eventRepository = SchedulingEventRepository(),
        reevaluationRepository = ProfileReevaluationRepository(),
        quarantineEnvelopeProtector = protector,
        quarantineRepository = SchedulingQuarantineRepository(clock),
        clock = clock,
        quarantineRetention = Duration.ofDays(30),
        quarantineRetentionClass = QuarantineRetentionClass.STANDARD,
        heldTarget = Duration.ofMinutes(2),
        proposedTarget = Duration.ofMinutes(30),
        targetPolicyRef = "profile-reevaluation/default",
        targetPolicyGeneration = 1L,
        eventObserver = observer,
    )

    private fun envelope(
        eventId: String,
        revision: Long = 7L,
        tenantGroupId: Long = 1L,
        clinicId: Long = 41L,
        fingerprint: String = this.fingerprint,
        materialChange: Boolean = true,
        assessmentRef: String = "assessment:$revision",
    ): UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged> {
        val occurredAt = now.minusSeconds(10)
        val payload = PatientSchedulingAssessmentChanged(
            eventId = eventId,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            patientReferenceFingerprint = fingerprint,
            profileRevision = revision,
            materialChange = materialChange,
            assessmentRef = assessmentRef,
            assessmentHash = assessmentHash,
            occurredAt = occurredAt,
        )
        return UntrustedSchedulingEventEnvelope(
            eventId = eventId,
            eventType = "PatientSchedulingAssessmentChanged",
            occurredAt = occurredAt,
            receivedAt = now,
            producer = "crm-service",
            issuer = "crm-issuer",
            audience = "appointment-service",
            keyId = "crm-key",
            algorithm = "EdDSA",
            schemaVersion = 1,
            correlationId = "correlation-$eventId",
            payloadHash = PatientSchedulingAssessmentChangedHasher.hash(payload),
            signature = "valid",
            payload = payload,
        )
    }

    private fun seedTenantAndClinic(tenantGroupId: Long, clinicId: Long) {
        TenantGroups.insert {
            it[id] = EntityID(tenantGroupId, TenantGroups)
            it[tenantCode] = "tenant-$tenantGroupId"
            it[displayName] = "Tenant $tenantGroupId"
            it[active] = true
        }
        Clinics.insert {
            it[id] = EntityID(clinicId, Clinics)
            it[Clinics.tenantGroupId] = EntityID(tenantGroupId, TenantGroups)
            it[name] = "Clinic $clinicId"
        }
    }
}

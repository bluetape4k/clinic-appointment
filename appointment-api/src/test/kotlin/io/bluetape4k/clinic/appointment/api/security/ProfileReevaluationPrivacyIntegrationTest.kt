package io.bluetape4k.clinic.appointment.api.security

import com.fasterxml.jackson.databind.json.JsonMapper
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.clinic.appointment.api.commitment.CustomerAppointmentRequestCommand
import io.bluetape4k.clinic.appointment.api.integration.ProfileReevaluationDatabaseIntegrationTestSupport
import io.bluetape4k.clinic.appointment.api.profile.FetchProfileAssessment
import io.bluetape4k.clinic.appointment.api.profile.ProfileAssessmentException
import io.bluetape4k.clinic.appointment.api.profile.ProfileAssessmentFailureCode
import io.bluetape4k.clinic.appointment.api.profile.ProfileAssessmentMetricResult
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecision
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDecisionService
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDrainState
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationEventMetricResult
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationHealthIndicator
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationHealthSource
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationMetrics
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationOperationalSnapshot
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationPlanner
import io.bluetape4k.clinic.appointment.api.profile.ProfileSchedulingAssessment
import io.bluetape4k.clinic.appointment.api.profile.RestClientProfileAssessmentClient
import io.bluetape4k.clinic.appointment.event.integration.AesGcmQuarantineEnvelopeProtector
import io.bluetape4k.clinic.appointment.event.integration.QuarantineRetentionClass
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventRepository
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventSignatureVerifier
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventTrustVerifier
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineRepository
import io.bluetape4k.clinic.appointment.event.integration.UntrustedSchedulingEventEnvelope
import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChanged
import io.bluetape4k.clinic.appointment.event.profile.PatientSchedulingAssessmentChangedHasher
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventService
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationJobStatus
import io.bluetape4k.clinic.appointment.model.profile.ProfileReevaluationOutcomeType
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.full.memberProperties

/**
 * 프로필 재평가 경계가 CRM 원본 개인정보와 평가 근거를 보존하지 않는지 통합 검증합니다.
 */
@ExtendWith(OutputCaptureExtension::class)
internal class ProfileReevaluationPrivacyIntegrationTest :
    ProfileReevaluationDatabaseIntegrationTestSupport() {

    @BeforeEach
    fun createPrivacyBoundaryTables() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                SchedulingInboxEvents,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            exec(
                "ALTER TABLE scheduling_profile_reevaluation_jobs " +
                    "DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_job_status",
            )
            exec(
                "ALTER TABLE scheduling_profile_reevaluation_jobs " +
                    "DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_priority_class",
            )
            exec(
                "ALTER TABLE scheduling_profile_reevaluation_outcomes " +
                    "DROP CONSTRAINT IF EXISTS ck_profile_reevaluation_outcome_type",
            )
        }
    }

    @AfterEach
    fun clearPrivacyBoundaryTables() {
        transaction(database) {
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            SchedulingInboxEvents.deleteAll()
        }
    }

    @Test
    fun `event와 assessment 계약은 예약 계산에 필요한 허용 필드만 가진다`() {
        assertEquals(
            setOf(
                "eventId",
                "tenantGroupId",
                "clinicId",
                "patientReferenceFingerprint",
                "profileRevision",
                "materialChange",
                "assessmentRef",
                "assessmentHash",
                "occurredAt",
            ),
            PatientSchedulingAssessmentChanged::class.memberProperties.map { it.name }.toSet(),
        )
        assertEquals(
            setOf(
                "tenantGroupId",
                "clinicId",
                "patientReferenceFingerprint",
                "profileRevision",
                "assessmentReference",
                "assessmentHash",
                "eligibleServiceCodes",
                "requiredResourceTags",
                "allowedTimeWindows",
            ),
            ProfileSchedulingAssessment::class.memberProperties.map { it.name }.toSet(),
        )
    }

    @Test
    fun `event부터 outbox까지 원본 개인정보와 평가 근거를 영속화하지 않는다`() {
        val service = eventService()
        val trusted = envelope(
            eventId = "profile-event-7",
            assessmentReference = "assessment-7",
        )
        assertEquals(ProfileReevaluationEventStatus.PROCESSED, service.accept(trusted).status)

        val quarantined = envelope(
            eventId = "profile-rejected",
            assessmentReference = RAW_PROFILE_MARKER,
            signature = "invalid",
        )
        assertEquals(ProfileReevaluationEventStatus.QUARANTINED, service.accept(quarantined).status)

        val held = commandService().requestCustomerAppointment(
            CustomerAppointmentRequestCommand(
                context = commandContext("privacy-held"),
                identity = appointmentIdentity("privacy-held"),
                proposal = proposalInput(revision = 1L, resourceId = "doctor-privacy"),
                expiresAt = ACTIVE_EXPIRY,
                representativeTreatmentName = "개인정보 경계 검증",
                consent = acceptedConsent("privacy-held"),
                holdResources = true,
            ),
        )
        val command = commandFor(held.commitment.id, held.commitment.appointmentId)
        val result = ProfileReevaluationDecisionService(
            database = database,
            planner = ProfileReevaluationPlanner { ProfileReevaluationDecision.KeepHeld },
            clock = CLOCK,
        ).reevaluate(command)
        assertEquals(ProfileReevaluationOutcomeType.HOLD_KEPT, result.outcomeType)

        transaction(database) {
            val persisted = listOf(
                SchedulingInboxEvents,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
                ProfileReevaluationHeads,
                ProfileReevaluationJobs,
                ProfileReevaluationOutcomes,
                SchedulingOutboxEvents,
            ).flatMap { table -> table.persistedText() }

            FORBIDDEN_VALUES.forEach { forbidden ->
                assertFalse(
                    persisted.any { it.contains(forbidden, ignoreCase = true) },
                    "금지된 개인정보 값이 영속 계층에 남았습니다: $forbidden",
                )
            }

            val outboxPayload = SchedulingOutboxEvents
                .selectAll()
                .single {
                    it[SchedulingOutboxEvents.eventType] ==
                        "PROFILE_REEVALUATION_HOLD_KEPT"
                }[SchedulingOutboxEvents.payloadJson]
            assertEquals(
                setOf(
                    "jobId",
                    "appointmentId",
                    "revision",
                    "outcomeType",
                    "policySnapshotId",
                    "assessmentReference",
                    "assessmentHash",
                    "emitter",
                    "eventId",
                    "completedAt",
                ),
                JsonMapper.builder().build()
                    .readTree(outboxPayload)
                    .fieldNames()
                    .asSequence()
                    .toSet(),
            )
        }
    }

    @Test
    fun `CRM 요청 예외 로그 metric과 health에 원본 값이나 식별자를 노출하지 않는다`(
        output: CapturedOutput,
    ) {
        val requestPath = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/assessments") { exchange ->
            requestPath.set(exchange.requestURI.rawPath)
            val body = """
                {
                  "tenantGroupId":1,
                  "clinicId":${clinic.clinicId},
                  "patientReferenceFingerprint":"$PATIENT_REFERENCE_FINGERPRINT",
                  "profileRevision":7,
                  "assessmentReference":"assessment-privacy",
                  "assessmentHash":"${"a".repeat(64)}",
                  "eligibleServiceCodes":["LASER"],
                  "requiredResourceTags":["LASER_ROOM"],
                  "allowedTimeWindows":[],
                  "diagnosis":"$RAW_PROFILE_MARKER"
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        val registry = SimpleMeterRegistry()
        try {
            val client = RestClientProfileAssessmentClient(
                baseUrl = URI("http://127.0.0.1:${server.address.port}/assessments"),
                allowedHosts = setOf("127.0.0.1"),
                connectTimeout = Duration.ofSeconds(1),
                readTimeout = Duration.ofSeconds(1),
                maxResponseBytes = 16 * 1024,
                maxConcurrency = 1,
                meterRegistry = registry,
                allowUnsafeTestEndpoint = true,
                addressResolver = { emptyList() },
            )
            val failure = assertThrows(ProfileAssessmentException::class.java) {
                client.fetch(
                    FetchProfileAssessment(
                        tenantGroupId = TENANT_ID,
                        clinicId = clinic.clinicId,
                        patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
                        profileRevision = 7L,
                        assessmentReference = "assessment-privacy",
                        assessmentHash = "a".repeat(64),
                        correlationId = "privacy-correlation",
                    ),
                )
            }

            assertEquals(ProfileAssessmentFailureCode.SCHEMA_INVALID, failure.code)
            assertFalse(failure.message.orEmpty().contains(RAW_PROFILE_MARKER))
            assertEquals("/assessments/assessment-privacy", requestPath.get())
            assertFalse(requestPath.get().contains(RAW_PROFILE_MARKER))

            val metrics = ProfileReevaluationMetrics(registry)
            metrics.recordEvent(ProfileReevaluationEventMetricResult.ACCEPTED)
            metrics.recordJob(ProfileReevaluationJobStatus.RUNNING)
            metrics.recordOutcome(
                AppointmentCommitmentStatus.HELD,
                ProfileReevaluationOutcomeType.HOLD_KEPT,
                Duration.ofMillis(15),
            )
            metrics.recordAssessment(ProfileAssessmentMetricResult.SUCCESS, Duration.ofMillis(5))
            val health = ProfileReevaluationHealthIndicator(
                source = ProfileReevaluationHealthSource {
                    ProfileReevaluationOperationalSnapshot(
                        pendingJobs = 1,
                        oldestBacklogAge = Duration.ofMinutes(31),
                        drainState = ProfileReevaluationDrainState.DRAINING,
                    )
                },
            ).health()

            registry.meters.forEach { meter ->
                val exposed = meter.id.tags.joinToString("|") { tag ->
                    "${tag.key}=${tag.value}"
                }
                FORBIDDEN_OBSERVABILITY_TOKENS.forEach { forbidden ->
                    assertFalse(
                        exposed.contains(forbidden, ignoreCase = true),
                        "metric이 금지된 식별자를 노출합니다: $exposed",
                    )
                }
            }
            val healthText = health.details.entries.joinToString("|") { "${it.key}=${it.value}" }
            FORBIDDEN_OBSERVABILITY_TOKENS.forEach { forbidden ->
                assertFalse(healthText.contains(forbidden, ignoreCase = true))
            }
            FORBIDDEN_VALUES.forEach { forbidden ->
                assertFalse(output.all.contains(forbidden, ignoreCase = true))
            }
        } finally {
            server.stop(0)
            registry.close()
        }
    }

    private fun eventService() =
        ProfileReevaluationEventService(
            trustVerifier = SchedulingEventTrustVerifier(
                signatureVerifier = SchedulingEventSignatureVerifier { it.signature == "valid" },
                allowedProducers = setOf("crm-service"),
                allowedKeyIds = setOf("crm-key"),
                allowedAlgorithms = setOf("EdDSA"),
                expectedIssuer = "crm-issuer",
                expectedAudience = "appointment-service",
                replayWindow = Duration.ofMinutes(15),
                clock = CLOCK,
            ),
            eventRepository = SchedulingEventRepository(),
            reevaluationRepository =
                io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository(),
            quarantineEnvelopeProtector = AesGcmQuarantineEnvelopeProtector(
                encryptionKey = ByteArray(32) { index -> index.toByte() },
                keyId = "privacy-quarantine-key",
            ),
            quarantineRepository = SchedulingQuarantineRepository(CLOCK),
            clock = CLOCK,
            quarantineRetention = Duration.ofDays(30),
            quarantineRetentionClass = QuarantineRetentionClass.STANDARD,
            heldTarget = Duration.ofMinutes(5),
            proposedTarget = Duration.ofMinutes(30),
            targetPolicyRef = "platform-default",
            targetPolicyGeneration = 1L,
        )

    private fun envelope(
        eventId: String,
        assessmentReference: String,
        signature: String = "valid",
    ): UntrustedSchedulingEventEnvelope<PatientSchedulingAssessmentChanged> {
        val event = PatientSchedulingAssessmentChanged(
            eventId = eventId,
            tenantGroupId = TENANT_ID,
            clinicId = clinic.clinicId,
            patientReferenceFingerprint = PATIENT_REFERENCE_FINGERPRINT,
            profileRevision = 7L,
            materialChange = true,
            assessmentRef = assessmentReference,
            assessmentHash = "a".repeat(64),
            occurredAt = NOW,
        )
        return UntrustedSchedulingEventEnvelope(
            eventId = eventId,
            eventType = "PatientSchedulingAssessmentChanged",
            occurredAt = NOW,
            receivedAt = NOW,
            producer = "crm-service",
            issuer = "crm-issuer",
            audience = "appointment-service",
            keyId = "crm-key",
            algorithm = "EdDSA",
            schemaVersion = 1,
            correlationId = "correlation-$eventId",
            payloadHash = PatientSchedulingAssessmentChangedHasher.hash(event),
            signature = signature,
            payload = event,
        )
    }

    private fun Table.persistedText(): List<String> =
        selectAll().flatMap { row ->
            columns.map { column -> row[column].toString() }
        }

    private companion object {
        const val RAW_PROFILE_MARKER = "raw-patient-42-diagnosis-high-risk"
        val FORBIDDEN_VALUES = setOf(
            RAW_PROFILE_MARKER,
            "birthDate",
            "feature",
            "score",
            "explanation",
            "correction",
            "rawProfile",
        )
        val FORBIDDEN_OBSERVABILITY_TOKENS = setOf(
            "tenant",
            "clinic",
            "patient",
            "appointment",
            "eventId",
            "jobId",
            "assessmentReference",
            RAW_PROFILE_MARKER,
        )
    }
}

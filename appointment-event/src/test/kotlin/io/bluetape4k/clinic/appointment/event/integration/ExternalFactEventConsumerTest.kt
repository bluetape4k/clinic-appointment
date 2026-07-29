package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.MigrationMappingType
import io.bluetape4k.clinic.appointment.model.plan.MigrationTarget
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.repository.AppointmentOperationalExceptionRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.PackageExecutionPlanner
import io.bluetape4k.clinic.appointment.service.PlanDirtySetResolver
import io.bluetape4k.clinic.appointment.service.ProductVersionMigrationPlanner
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Task 8 외부 fact의 production mutation 경계를 검증합니다.
 *
 * handler 단위 테스트와 달리 raw bytes부터 시작해 암호화, strict ingress, trust,
 * routing, source proof, handler까지 한 호출로 실행합니다. 실패가 평문이나 부분
 * revision을 남기지 않고 quarantine과 append-only audit로 끝나는지가 핵심입니다.
 */
class ExternalFactEventConsumerTest {

    private lateinit var fixture: ExternalFactEventTestFixture

    @BeforeEach
    fun setup() {
        fixture = ExternalFactEventTestFixture("external_fact_consumer")
    }

    @Test
    fun `승인된 상품 전환 raw event는 consumer 경계를 거쳐 미래 revision만 만든다`() {
        val migration = migrationEvent()
        val result = consumer(migration).acceptProductVersionMigration(
            rawEnvelope(
                eventId = "migration-consumer-success",
                eventType = "ProductVersionMigrationApproved",
                producer = "product-service",
                payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
            ),
            RAW_JSON,
            routing(migration),
        )

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 2L
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingQuarantineAuditEvents.selectAll().count() shouldBeEqualTo 0L
            UntrustedSchedulingEventRejections.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `전환 거부 진료이행 각각의 trust 실패는 암호화 quarantine과 감사 row로 수렴한다`() {
        val migration = migrationEvent()
        val decline = declineEvent()
        val fulfillment = fulfillmentEvent()
        val consumer = consumer(migration, decline, fulfillment)

        val results = listOf(
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-trust-failure",
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                    signature = "invalid-signature",
                ),
                RAW_JSON,
                routing(migration),
            ),
            consumer.acceptMigrationRescheduleDeclined(
                rawEnvelope(
                    eventId = "decline-trust-failure",
                    eventType = "ProductVersionMigrationRescheduleDeclined",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationRescheduleDeclinedPayloadHasher.hash(decline),
                    signature = "invalid-signature",
                ),
                RAW_JSON,
                routing(decline),
            ),
            consumer.acceptTreatmentFulfillment(
                rawEnvelope(
                    eventId = "fulfillment-trust-failure",
                    eventType = "TreatmentFulfillmentRecorded",
                    producer = "clinical-service",
                    payloadHash = TreatmentFulfillmentPayloadHasher.hash(fulfillment),
                    signature = "invalid-signature",
                ),
                RAW_JSON,
                routing(fulfillment),
            ),
        )

        results.map(PurchaseHandleResult::status) shouldBeEqualTo
            listOf(
                PurchaseHandleStatus.QUARANTINED,
                PurchaseHandleStatus.QUARANTINED,
                PurchaseHandleStatus.QUARANTINED,
            )
        results.map(PurchaseHandleResult::reasonCode) shouldBeEqualTo
            listOf("SIGNATURE_INVALID", "SIGNATURE_INVALID", "SIGNATURE_INVALID")
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 3L
            SchedulingQuarantineAuditEvents.selectAll().count() shouldBeEqualTo 3L
            UntrustedSchedulingEventRejections.selectAll().count() shouldBeEqualTo 3L
            SchedulingQuarantineEvents.selectAll().forEach { row ->
                val encrypted = checkNotNull(row[SchedulingQuarantineEvents.encryptedOriginalEnvelope])
                encrypted shouldNotContain RAW_JSON.decodeToString()
            }
        }
    }

    @Test
    fun `broker routing과 trusted payload가 다르면 handler를 호출하지 않고 격리한다`() {
        val migration = migrationEvent()
        val mismatchedRouting = routing(migration).copy(clinicId = fixture.clinicId + 1)

        val result = consumer(migration).acceptProductVersionMigration(
            rawEnvelope(
                eventId = "migration-routing-mismatch",
                eventType = "ProductVersionMigrationApproved",
                producer = "product-service",
                payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
            ),
            RAW_JSON,
            mismatchedRouting,
        )

        result.reasonCode shouldBeEqualTo "ROUTING_METADATA_MISMATCH"
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingQuarantineEvents.selectAll().single()[SchedulingQuarantineEvents.reasonCode] shouldBeEqualTo
                "ROUTING_METADATA_MISMATCH"
            UntrustedSchedulingEventRejections.selectAll().single()
                .get(UntrustedSchedulingEventRejections.claimedClinicId) shouldBeEqualTo
                mismatchedRouting.clinicId
        }
    }

    @Test
    fun `존재하지 않는 routing의 trust 실패는 FK 없는 terminal rejection에 보존한다`() {
        val migration = migrationEvent()
        val unknownRouting = routing(migration).copy(
            tenantGroupId = 99_001,
            clinicId = 99_002,
        )

        val result = consumer(migration).acceptProductVersionMigration(
            rawEnvelope(
                eventId = "migration-unknown-routing",
                eventType = "ProductVersionMigrationApproved",
                producer = "product-service",
                payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                signature = "invalid-signature",
            ),
            RAW_JSON,
            unknownRouting,
        )

        result.reasonCode shouldBeEqualTo "SIGNATURE_INVALID"
        transaction {
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingQuarantineAuditEvents.selectAll().count() shouldBeEqualTo 0L
            val rejection = UntrustedSchedulingEventRejections.selectAll().single()
            rejection[UntrustedSchedulingEventRejections.claimedTenantGroupId] shouldBeEqualTo 99_001L
            rejection[UntrustedSchedulingEventRejections.claimedClinicId] shouldBeEqualTo 99_002L
        }
    }

    @Test
    fun `과도한 envelope과 routing header는 bounded evidence로 보호한 뒤 격리한다`() {
        val migration = migrationEvent()
        val consumer = consumer(migration)
        val oversized = "x".repeat(200_000)

        val results = listOf(
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = oversized,
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                ),
                RAW_JSON,
                routing(migration),
            ),
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-large-event-type",
                    eventType = oversized,
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                ),
                RAW_JSON,
                routing(migration),
            ),
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-large-authority",
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                ),
                RAW_JSON,
                routing(migration).copy(sourceAuthority = oversized),
            ),
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-large-aggregate",
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                ),
                RAW_JSON,
                routing(migration).copy(sourceAggregateId = oversized),
            ),
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-large-signature",
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                    signature = oversized,
                ),
                RAW_JSON,
                routing(migration),
            ),
        )

        results.map(PurchaseHandleResult::reasonCode) shouldBeEqualTo
            listOf(
                "ENVELOPE_METADATA_INVALID",
                "EVENT_TYPE_NOT_ALLOWED",
                "ROUTING_METADATA_MISMATCH",
                "ROUTING_METADATA_MISMATCH",
                "ENVELOPE_METADATA_INVALID",
            )
        transaction {
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 5L
            SchedulingQuarantineEvents.selectAll().forEach { row ->
                checkNotNull(row[SchedulingQuarantineEvents.encryptedOriginalEnvelope]).length shouldBeLessOrEqualTo
                    8_192
            }
            UntrustedSchedulingEventRejections.selectAll().count() shouldBeEqualTo 5L
        }
    }

    @Test
    fun `ingress 상한을 넘긴 원문은 암호화하지 않고 hash 증거만 남긴다`() {
        val migration = migrationEvent()

        val result = consumer(migration).acceptProductVersionMigration(
            rawEnvelope(
                eventId = "migration-payload-too-large",
                eventType = "ProductVersionMigrationApproved",
                producer = "product-service",
                payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
            ),
            ByteArray(1_048_577) { 'a'.code.toByte() },
            routing(migration),
        )

        result.reasonCode shouldBeEqualTo "PAYLOAD_TOO_LARGE"
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            val quarantine = SchedulingQuarantineEvents.selectAll().single()
            quarantine[SchedulingQuarantineEvents.reasonCode] shouldBeEqualTo "PAYLOAD_TOO_LARGE"
            quarantine[SchedulingQuarantineEvents.encryptedOriginalEnvelope] shouldBeEqualTo null
            quarantine[SchedulingQuarantineEvents.envelopeHash].length shouldBeEqualTo 64
        }
    }

    @Test
    fun `source authority proof 장애는 handler 실행 전에 WAITING_GAP으로 staging하고 reason metric을 남긴다`() {
        val migration = migrationEvent().copy(sourceAggregateVersion = 4)
        val decline = declineEvent().copy(sourceAggregateVersion = 4)
        val fulfillment = fulfillmentEvent().copy(sourceAggregateVersion = 4)
        val metrics = ConcurrentLinkedQueue<Pair<String, String?>>()
        val consumer = consumer(
            migration = migration,
            decline = decline,
            fulfillment = fulfillment,
            migrationProofProvider = ProductVersionMigrationProofProvider { _, _ ->
                throw SourceAuthorityUnavailableException(SourceAuthorityFailureReason.TIMEOUT)
            },
            declineProofProvider = MigrationDeclineProofProvider { _, _ ->
                throw SourceAuthorityUnavailableException(SourceAuthorityFailureReason.CIRCUIT_OPEN)
            },
            fulfillmentProofProvider = TreatmentFulfillmentProofProvider { _, _ ->
                throw SourceAuthorityUnavailableException(SourceAuthorityFailureReason.TIMEOUT)
            },
            metrics = ExternalFactMetrics { result, reason -> metrics += result to reason },
        )

        val results = listOf(
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-authority-timeout",
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                ),
                RAW_JSON,
                routing(migration),
            ),
            consumer.acceptMigrationRescheduleDeclined(
                rawEnvelope(
                    eventId = "decline-authority-circuit-open",
                    eventType = "ProductVersionMigrationRescheduleDeclined",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationRescheduleDeclinedPayloadHasher.hash(decline),
                ),
                RAW_JSON,
                routing(decline),
            ),
            consumer.acceptTreatmentFulfillment(
                rawEnvelope(
                    eventId = "fulfillment-authority-timeout",
                    eventType = "TreatmentFulfillmentRecorded",
                    producer = "clinical-service",
                    payloadHash = TreatmentFulfillmentPayloadHasher.hash(fulfillment),
                ),
                RAW_JSON,
                routing(fulfillment),
            ),
        )

        results.map(PurchaseHandleResult::status) shouldBeEqualTo
            listOf(
                PurchaseHandleStatus.WAITING_GAP,
                PurchaseHandleStatus.WAITING_GAP,
                PurchaseHandleStatus.WAITING_GAP,
            )
        results.map(PurchaseHandleResult::reasonCode) shouldBeEqualTo
            listOf(
                "SOURCE_AUTHORITY_TIMEOUT",
                "SOURCE_AUTHORITY_CIRCUIT_OPEN",
                "SOURCE_AUTHORITY_TIMEOUT",
            )
        metrics.toList() shouldBeEqualTo
            listOf(
                "WAITING_GAP" to "SOURCE_AUTHORITY_TIMEOUT",
                "WAITING_GAP" to "SOURCE_AUTHORITY_CIRCUIT_OPEN",
                "WAITING_GAP" to "SOURCE_AUTHORITY_TIMEOUT",
            )
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll()
                .map { it[SchedulingInboxEvents.failureCode] }
                .toSet() shouldBeEqualTo
                setOf("SOURCE_AUTHORITY_TIMEOUT", "SOURCE_AUTHORITY_CIRCUIT_OPEN")
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `metric failure does not replace the durable external fact result`() {
        val migration = migrationEvent()
        val consumer =
            consumer(
                migration = migration,
                metrics = ExternalFactMetrics { _, _ -> error("registry unavailable") },
            )

        val result =
            consumer.acceptProductVersionMigration(
                rawEnvelope(
                    eventId = "migration-metric-failure",
                    eventType = "ProductVersionMigrationApproved",
                    producer = "product-service",
                    payloadHash = ProductVersionMigrationPayloadHasher.hash(migration),
                ),
                RAW_JSON,
                routing(migration),
            )

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        transaction {
            SchedulingInboxEvents
                .selectAll()
                .where { SchedulingInboxEvents.eventId eq "migration-metric-failure" }
                .count() shouldBeEqualTo 1L
        }
    }

    private fun consumer(
        migration: ProductVersionMigrationApprovedEvent = migrationEvent(),
        decline: ProductVersionMigrationRescheduleDeclinedEvent = declineEvent(),
        fulfillment: TreatmentFulfillmentEvent = fulfillmentEvent(),
        migrationProofProvider: ProductVersionMigrationProofProvider = ProductVersionMigrationProofProvider { _, _ ->
            null
        },
        declineProofProvider: MigrationDeclineProofProvider = MigrationDeclineProofProvider { _, _ -> null },
        fulfillmentProofProvider: TreatmentFulfillmentProofProvider = TreatmentFulfillmentProofProvider { _, _ -> null },
        metrics: ExternalFactMetrics = ExternalFactMetrics.NOOP,
    ): ExternalFactEventConsumer {
        val fixedClock = Clock.fixed(fixture.now, ZoneOffset.UTC)
        val quarantineRepository = SchedulingQuarantineRepository(fixedClock)
        val eventRepository = SchedulingEventRepository()
        val planRepository = AppointmentPlanRepository()
        val revisionRepository = AppointmentPlanRevisionRepository()
        val operationalExceptionRepository = AppointmentOperationalExceptionRepository()
        val versionVerifier = SourceAggregateVersionVerifier(fixedClock)
        val ingress = ExternalFactEventIngress(
            trustVerifier = SchedulingEventTrustVerifier(
                signatureVerifier = SchedulingEventSignatureVerifier { envelope ->
                    envelope.signature == "valid-signature"
                },
                allowedProducers = setOf("product-service", "clinical-service"),
                allowedKeyIds = setOf("external-fact-key"),
                allowedAlgorithms = setOf("EdDSA"),
                expectedIssuer = "clinic-platform",
                expectedAudience = "appointment-service",
                replayWindow = Duration.ofHours(1),
                clock = fixedClock,
            ),
            migrationDecoder = ProductVersionMigrationApprovedEventDecoder { migration },
            declineDecoder = ProductVersionMigrationRescheduleDeclinedEventDecoder { decline },
            fulfillmentDecoder = TreatmentFulfillmentEventDecoder { fulfillment },
        )
        return ExternalFactEventConsumer(
            ingress = ingress,
            rawEnvelopeProtector = AesGcmRawExternalFactEnvelopeProtector(
                encryptionKey = ByteArray(32) { index -> (index + 1).toByte() },
                keyId = "quarantine-key",
            ),
            quarantineRepository = quarantineRepository,
            rejectionRepository = UntrustedSchedulingEventRejectionRepository(),
            migrationProofProvider = migrationProofProvider,
            declineProofProvider = declineProofProvider,
            fulfillmentProofProvider = fulfillmentProofProvider,
            migrationHandler = ProductVersionMigrationHandler(
                eventRepository = eventRepository,
                quarantineRepository = quarantineRepository,
                planRepository = planRepository,
                executionPlanner = PackageExecutionPlanner(),
                migrationPlanner = ProductVersionMigrationPlanner(),
                revisionRepository = revisionRepository,
                operationalExceptionRepository = operationalExceptionRepository,
                versionVerifier = versionVerifier,
                clock = fixedClock,
            ),
            fulfillmentHandler = TreatmentFulfillmentHandler(
                eventRepository = eventRepository,
                quarantineRepository = quarantineRepository,
                planRepository = planRepository,
                revisionRepository = revisionRepository,
                operationalExceptionRepository = operationalExceptionRepository,
                dirtySetResolver = PlanDirtySetResolver(),
                versionVerifier = versionVerifier,
                clock = fixedClock,
            ),
            clock = fixedClock,
            metrics = metrics,
        )
    }

    private fun migrationEvent(): ProductVersionMigrationApprovedEvent {
        val mappings = listOf(
            MigrationMapping(
                MigrationMappingType.REPLACE,
                setOf("future-old"),
                listOf(MigrationTarget("future-new")),
            ),
            MigrationMapping(
                MigrationMappingType.REPLACE,
                setOf("blocked-next"),
                listOf(MigrationTarget("blocked-new")),
            ),
            MigrationMapping(
                MigrationMappingType.KEEP,
                setOf("independent"),
                listOf(MigrationTarget("independent")),
            ),
        )
        val mappingHash = ProductVersionMigrationPayloadHasher.mappingHash(mappings)
        return ProductVersionMigrationApprovedEvent(
            sourceAggregateId = "migration-1",
            sourceAggregateVersion = 1,
            tenantGroupId = fixture.tenantGroupId,
            clinicId = fixture.clinicId,
            sourcePurchaseAuthority = "purchase-service",
            sourcePurchaseId = "purchase-100",
            migrationId = "migration-1",
            fromProductVersionId = "product-v1",
            toProductVersionId = "product-v2",
            mappings = mappings,
            mappingHash = mappingHash,
            consent = ProductVersionMigrationConsentEvidence(
                migrationId = "migration-1",
                fromProductVersionId = "product-v1",
                toProductVersionId = "product-v2",
                mappingHash = mappingHash,
                consentedAt = fixture.now.minusSeconds(60),
                evidenceType = ProductVersionMigrationConsentEvidenceType.DIGITAL_SIGNATURE,
                evidenceReferenceHash = "e".repeat(64),
            ),
            targetExecutionSnapshot = fixture.targetSnapshot(
                dependencies = listOf(
                    ExecutionDependency(
                        "future-new",
                        "blocked-new",
                        ExecutionDependencyType.BLOCKING,
                        7,
                        14,
                        21,
                    ),
                    ExecutionDependency(
                        "future-new",
                        "independent",
                        ExecutionDependencyType.NON_BLOCKING,
                    ),
                ),
            ),
        )
    }

    private fun declineEvent() =
        ProductVersionMigrationRescheduleDeclinedEvent(
            sourceAggregateId = "migration-decline-1",
            sourceAggregateVersion = 1,
            tenantGroupId = fixture.tenantGroupId,
            clinicId = fixture.clinicId,
            sourcePurchaseAuthority = "purchase-service",
            sourcePurchaseId = "purchase-100",
            migrationId = "migration-1",
            appointmentId = null,
            reasonCode = "CUSTOMER_DECLINED_RESCHEDULE",
        )

    private fun fulfillmentEvent() =
        TreatmentFulfillmentEvent(
            sourceAggregateId = "fulfillment-1",
            sourceAggregateVersion = 1,
            tenantGroupId = fixture.tenantGroupId,
            clinicId = fixture.clinicId,
            sourcePurchaseAuthority = "purchase-service",
            sourcePurchaseId = "purchase-100",
            facts = listOf(TreatmentFulfillmentFact.completed("future-old", fixture.now.minusSeconds(20))),
        )

    private fun routing(event: ProductVersionMigrationApprovedEvent) =
        ExternalFactRoutingMetadata(
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
        )

    private fun routing(event: ProductVersionMigrationRescheduleDeclinedEvent) =
        ExternalFactRoutingMetadata(
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
        )

    private fun routing(event: TreatmentFulfillmentEvent) =
        ExternalFactRoutingMetadata(
            sourceAuthority = event.sourcePurchaseAuthority,
            sourceAggregateId = event.sourceAggregateId,
            sourceAggregateVersion = event.sourceAggregateVersion,
            tenantGroupId = event.tenantGroupId,
            clinicId = event.clinicId,
        )

    private fun rawEnvelope(
        eventId: String,
        eventType: String,
        producer: String,
        payloadHash: String,
        signature: String = "valid-signature",
    ) = UntrustedSchedulingEventEnvelope(
        eventId = eventId,
        eventType = eventType,
        occurredAt = fixture.now.minusSeconds(10),
        receivedAt = fixture.now,
        producer = producer,
        issuer = "clinic-platform",
        audience = "appointment-service",
        keyId = "external-fact-key",
        algorithm = "EdDSA",
        schemaVersion = 1,
        correlationId = "correlation-external-fact",
        payloadHash = payloadHash,
        signature = signature,
        payload = Unit,
    )

    private companion object {
        val RAW_JSON = """{"sourceAggregateId":"protected-raw"}""".encodeToByteArray()
    }
}

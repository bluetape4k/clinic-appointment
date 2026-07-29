package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionType
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.MigrationMapping
import io.bluetape4k.clinic.appointment.model.plan.MigrationMappingType
import io.bluetape4k.clinic.appointment.model.plan.MigrationTarget
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.repository.AppointmentOperationalExceptionRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.PackageExecutionPlanner
import io.bluetape4k.clinic.appointment.service.ProductVersionMigrationPlanner
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

class ProductVersionMigrationHandlerTest {

    private lateinit var fixture: ExternalFactEventTestFixture

    @BeforeEach
    fun setup() {
        fixture = ExternalFactEventTestFixture("product_version_migration")
    }

    @Test
    fun `승인된 전환은 완료 provenance를 수정하지 않고 동일 Plan의 미래 revision만 활성화한다`() {
        val envelope = envelope()

        val result = handler().handle(envelope, protectedEnvelope())

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        result.planId shouldBeEqualTo fixture.planId
        transaction {
            val revisions = AppointmentPlanRevisions.selectAll().toList()
            revisions.size shouldBeEqualTo 2
            revisions.single { it[AppointmentPlanRevisions.id].value == fixture.initialRevisionId }
                .get(AppointmentPlanRevisions.active) shouldBeEqualTo false
            val active = revisions.single { it[AppointmentPlanRevisions.active] }
            active[AppointmentPlanRevisions.productVersionId] shouldBeEqualTo "product-v2"
            active[AppointmentPlanRevisions.snapshotHash] shouldBeEqualTo envelope.payloadHash

            val oldCompleted = PlanRevisionTreatments.selectAll().single {
                it[PlanRevisionTreatments.planRevisionId].value == fixture.initialRevisionId &&
                    it[PlanRevisionTreatments.treatmentKey] == "completed"
            }
            oldCompleted[PlanRevisionTreatments.productVersionId] shouldBeEqualTo "product-v1"
            oldCompleted[PlanRevisionTreatments.status] shouldBeEqualTo PlanTreatmentStatus.COMPLETED

            val activeKeys = PlanRevisionTreatments.selectAll()
                .filter { it[PlanRevisionTreatments.planRevisionId].value == active[AppointmentPlanRevisions.id].value }
                .map { it[PlanRevisionTreatments.treatmentKey] }
                .toSet()
            activeKeys shouldBeEqualTo setOf("future-new", "blocked-new", "independent")

            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.eventType] shouldBeEqualTo "ProductVersionMigrationApplied"
            outbox[SchedulingOutboxEvents.payloadJson] shouldNotContain "대표 진료"
            outbox[SchedulingOutboxEvents.payloadJson] shouldContain "\"sourceFactReference\":\"migration-1\""
            outbox[SchedulingOutboxEvents.payloadJson] shouldContain "\"sourceFactHash\":\"${
                envelope.payload.mappingHash
            }\""
            outbox[SchedulingOutboxEvents.payloadJson] shouldContain "\"evidenceReferenceHash\":\"${
                envelope.payload.consent.evidenceReferenceHash
            }\""
        }
    }

    @Test
    fun `동의 hash 불일치와 from version 불일치는 활성 revision을 유지하고 redacted rejection을 발행한다`() {
        val mismatchedConsent = envelope(
            eventId = "migration-consent-mismatch",
            event = migrationEvent().copy(
                consent = migrationEvent().consent.copy(mappingHash = "f".repeat(64)),
            ),
        )
        val mismatchedVersion = envelope(
            eventId = "migration-version-mismatch",
            event = migrationEvent().copy(fromProductVersionId = "product-v0"),
        )
        val futureConsent = envelope(
            eventId = "migration-future-consent",
            event = migrationEvent().copy(
                consent = migrationEvent().consent.copy(
                    consentedAt = fixture.now.plusSeconds(1),
                ),
            ),
        )

        val consentResult = handler().handle(mismatchedConsent, protectedEnvelope())
        val versionResult = handler().handle(mismatchedVersion, protectedEnvelope())
        val futureConsentResult = handler().handle(futureConsent, protectedEnvelope())

        consentResult.reasonCode shouldBeEqualTo "CONSENT_SUBJECT_MISMATCH"
        versionResult.reasonCode shouldBeEqualTo "PRODUCT_VERSION_MISMATCH"
        futureConsentResult.reasonCode shouldBeEqualTo "CONSENT_SUBJECT_MISMATCH"
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            AppointmentPlanRevisions.selectAll().single()[AppointmentPlanRevisions.active] shouldBeEqualTo true
            val rejected = SchedulingOutboxEvents.selectAll().toList()
            rejected.map { it[SchedulingOutboxEvents.eventType] } shouldBeEqualTo
                listOf(
                    "ProductVersionMigrationRejected",
                    "ProductVersionMigrationRejected",
                    "ProductVersionMigrationRejected",
                )
            rejected.forEach {
                it[SchedulingOutboxEvents.payloadJson] shouldNotContain "CODE-A"
                it[SchedulingOutboxEvents.payloadJson] shouldNotContain "대표 진료"
            }
        }
    }

    @Test
    fun `mapping 누락 중복 cycle 완료항목 변경 시도는 모두 BOM mapping invalid로 격리한다`() {
        val invalidEvents = listOf(
            migrationEvent().copy(
                mappings = migrationEvent().mappings.filterNot {
                    "independent" in it.sourceTreatmentKeys
                },
            ),
            migrationEvent().copy(
                mappings = migrationEvent().mappings +
                    MigrationMapping(
                        MigrationMappingType.REPLACE,
                        setOf("future-old"),
                        listOf(MigrationTarget("duplicate-target")),
                    ),
            ),
            migrationEvent().copy(
                targetExecutionSnapshot = fixture.targetSnapshot(
                    dependencies = listOf(
                        ExecutionDependency("future-new", "blocked-new", ExecutionDependencyType.BLOCKING),
                        ExecutionDependency("blocked-new", "future-new", ExecutionDependencyType.BLOCKING),
                    ),
                ),
            ),
            migrationEvent().copy(
                mappings = migrationEvent().mappings +
                    MigrationMapping(
                        MigrationMappingType.REMOVE,
                        setOf("completed"),
                        emptyList(),
                    ),
            ),
        )

        invalidEvents.forEachIndexed { index, event ->
            handler().handle(
                envelope(eventId = "migration-invalid-$index", event = event),
                protectedEnvelope(),
            ).reasonCode shouldBeEqualTo "BOM_MAPPING_INVALID"
        }

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 4L
        }
    }

    @Test
    fun `동일 migration event replay는 revision과 outbox를 중복 생성하지 않는다`() {
        val envelope = envelope()
        val handler = handler()

        handler.handle(envelope, protectedEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.CREATED
        handler.handle(envelope, protectedEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE
        handler.handle(
            envelope(eventId = "migration-event-source-version-replay"),
            protectedEnvelope(),
        ).reasonCode shouldBeEqualTo "SOURCE_VERSION_REPLAY"

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 2L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `고객이 전환 후 일정 변경을 거부하면 기존 예약을 유지할 운영 예외와 CRM outbox를 추가한다`() {
        val decline = TrustedSchedulingEventEnvelope(
            eventId = "migration-decline-1",
            eventType = "ProductVersionMigrationRescheduleDeclined",
            occurredAt = fixture.now,
            receivedAt = fixture.now,
            producer = "appointment-api",
            issuer = "appointment-api",
            audience = "appointment-event",
            keyId = "internal-key",
            algorithm = "EdDSA",
            schemaVersion = 1,
            correlationId = "correlation-decline",
            payloadHash = "d".repeat(64),
            payload = ProductVersionMigrationRescheduleDeclinedEvent(
                sourceAggregateId = "migration-1",
                sourceAggregateVersion = 1L,
                tenantGroupId = fixture.tenantGroupId,
                clinicId = fixture.clinicId,
                sourcePurchaseAuthority = "purchase-service",
                sourcePurchaseId = "purchase-100",
                migrationId = "migration-1",
                appointmentId = null,
                reasonCode = "CUSTOMER_DECLINED_RESCHEDULE",
            ),
        )

        val handler = handler()
        handler.handleRescheduleDeclined(decline, protectedEnvelope()).status shouldBeEqualTo
            PurchaseHandleStatus.CREATED
        handler.handleRescheduleDeclined(
            decline.copy(eventId = "migration-decline-replay"),
            protectedEnvelope(),
        ).reasonCode shouldBeEqualTo "SOURCE_VERSION_REPLAY"
        handler.handleRescheduleDeclined(
            decline.copy(
                eventId = "migration-decline-conflict",
                payloadHash = "c".repeat(64),
            ),
            protectedEnvelope(),
        ).reasonCode shouldBeEqualTo "SOURCE_VERSION_HASH_CONFLICT"

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            AppointmentOperationalExceptions.selectAll().single()
                .get(AppointmentOperationalExceptions.type) shouldBeEqualTo
                AppointmentOperationalExceptionType.CUSTOMER_DECLINED_RESCHEDULE
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.eventType] shouldBeEqualTo "CustomerRescheduleDeclined"
            outbox[SchedulingOutboxEvents.payloadJson] shouldContain "CUSTOMER_DECLINED_RESCHEDULE"
            SchedulingQuarantineEvents.selectAll().single()[SchedulingQuarantineEvents.reasonCode] shouldBeEqualTo
                "SOURCE_VERSION_HASH_CONFLICT"
        }
    }

    @Test
    fun `source version gap은 Plan을 변경하지 않고 대기한 뒤 bounded retry 초과 시 격리한다`() {
        val gapEnvelope = envelope(
            eventId = "migration-gap",
            event = migrationEvent().copy(sourceAggregateVersion = 2L),
        )
        val handler = handler(maxGapAttempts = 2)

        handler.handle(gapEnvelope, protectedEnvelope()).status shouldBeEqualTo
            PurchaseHandleStatus.WAITING_GAP
        handler.handle(gapEnvelope, protectedEnvelope()).reasonCode shouldBeEqualTo
            "SOURCE_VERSION_GAP_EXHAUSTED"

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingQuarantineEvents.selectAll().single()[SchedulingQuarantineEvents.reasonCode] shouldBeEqualTo
                "SOURCE_VERSION_GAP_EXHAUSTED"
            SchedulingQuarantineAuditEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `같은 source version의 다른 payload는 활성 revision을 더 만들지 않고 격리한다`() {
        val handler = handler()
        handler.handle(envelope(), protectedEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.CREATED
        val conflicting = envelope(
            eventId = "migration-version-conflict",
            event = migrationEvent().copy(migrationId = "migration-conflict"),
        )

        handler.handle(conflicting, protectedEnvelope()).reasonCode shouldBeEqualTo
            "SOURCE_VERSION_HASH_CONFLICT"

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 2L
            SchedulingQuarantineEvents.selectAll().single()[SchedulingQuarantineEvents.reasonCode] shouldBeEqualTo
                "SOURCE_VERSION_HASH_CONFLICT"
        }
    }

    private fun handler(maxGapAttempts: Int = 5): ProductVersionMigrationHandler =
        ProductVersionMigrationHandler(
            eventRepository = SchedulingEventRepository(),
            quarantineRepository = SchedulingQuarantineRepository(
                Clock.fixed(fixture.now, ZoneOffset.UTC),
            ),
            planRepository = AppointmentPlanRepository(),
            executionPlanner = PackageExecutionPlanner(),
            migrationPlanner = ProductVersionMigrationPlanner(),
            revisionRepository = AppointmentPlanRevisionRepository(),
            operationalExceptionRepository = AppointmentOperationalExceptionRepository(),
            versionVerifier = SourceAggregateVersionVerifier(
                Clock.fixed(fixture.now, ZoneOffset.UTC),
            ),
            clock = Clock.fixed(fixture.now, ZoneOffset.UTC),
            maxGapAttempts = maxGapAttempts,
        )

    private fun envelope(
        eventId: String = "migration-event-1",
        event: ProductVersionMigrationApprovedEvent = migrationEvent(),
    ): TrustedSchedulingEventEnvelope<ProductVersionMigrationApprovedEvent> =
        TrustedSchedulingEventEnvelope(
            eventId = eventId,
            eventType = "ProductVersionMigrationApproved",
            occurredAt = fixture.now,
            receivedAt = fixture.now,
            producer = "product-service",
            issuer = "product-service",
            audience = "appointment-event",
            keyId = "product-key",
            algorithm = "EdDSA",
            schemaVersion = 1,
            correlationId = "correlation-migration",
            payloadHash = ProductVersionMigrationPayloadHasher.hash(event),
            payload = event,
        )

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
            sourceAggregateVersion = 1L,
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
            targetExecutionSnapshot = fixture.targetSnapshot(),
        )
    }

    private fun protectedEnvelope(): ProtectedQuarantineEnvelope =
        ProtectedQuarantineEnvelope(
            envelopeHash = "9".repeat(64),
            ciphertext = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            keyId = "quarantine-key",
        )
}

package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.ComponentSelection
import io.bluetape4k.clinic.appointment.model.plan.ComponentVersionRef
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.ExecutionTreatment
import io.bluetape4k.clinic.appointment.model.plan.PackageExecutionSnapshot
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingConstraint
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionDependencies
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionGroupingConstraints
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import io.bluetape4k.clinic.appointment.service.PackageExecutionPlanner
import io.bluetape4k.junit5.concurrency.MultithreadingTester
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class VisitPlanningEventHandlerTest {

    private val now = Instant.parse("2026-07-29T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val eventRepository = SchedulingEventRepository()
    private val quarantineRepository = SchedulingQuarantineRepository(clock)
    private val planRepository = AppointmentPlanRepository()
    private val revisionRepository = AppointmentPlanRevisionRepository()
    private val catalogRepository = ProductCatalogRepository()
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:visit_planning_handler_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                ProductCatalogProjections,
                ProductCatalogBomItems,
                ProductCatalogBomDependencies,
                AppointmentPlans,
                PlannedTreatments,
                TreatmentDependencies,
                AppointmentPlanRevisions,
                PlanRevisionTreatments,
                PlanRevisionDependencies,
                PlanRevisionGroupingConstraints,
                SchedulingInboxEvents,
                SchedulingOutboxEvents,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
                UntrustedSchedulingEventRejections,
            )
            UntrustedSchedulingEventRejections.deleteAll()
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            SchedulingOutboxEvents.deleteAll()
            SchedulingInboxEvents.deleteAll()
            PlanRevisionGroupingConstraints.deleteAll()
            PlanRevisionDependencies.deleteAll()
            PlanRevisionTreatments.deleteAll()
            AppointmentPlanRevisions.deleteAll()
            TreatmentDependencies.deleteAll()
            PlannedTreatments.deleteAll()
            AppointmentPlans.deleteAll()
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
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
                it[name] = "Plan Clinic"
            }.value
            catalogRepository.saveAggregate(catalogRecord())
            seedPlan()
            seedProcessedPurchaseVersion()
        }
    }

    @Test
    fun `WRITE는 inbox revision children redacted outbox processed를 한 transaction에 저장한다`() {
        val envelope = envelope()

        val result = handler().handle(envelope, protectedQuarantineEnvelope())

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 1L
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            PlanRevisionTreatments.selectAll().count() shouldBeEqualTo 6L
            PlanRevisionDependencies.selectAll().count() shouldBeEqualTo 1L
            PlanRevisionGroupingConstraints.selectAll().count() shouldBeEqualTo 1L
            val inbox = SchedulingInboxEvents.selectAll()
                .single { it[SchedulingInboxEvents.eventId] == envelope.eventId }
            inbox[SchedulingInboxEvents.status] shouldBeEqualTo
                SchedulingInboxStatus.PROCESSED
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.eventType] shouldBeEqualTo "AppointmentPlanRevisionCreated"
            outbox[SchedulingOutboxEvents.causationEventId] shouldBeEqualTo envelope.eventId
            outbox[SchedulingOutboxEvents.aggregateType] shouldBeEqualTo "APPOINTMENT_PLAN_REVISION"
            outbox[SchedulingOutboxEvents.payloadJson] shouldNotContain "미백"
            outbox[SchedulingOutboxEvents.payloadJson] shouldNotContain "WHITENING"
            val expectedSnapshotHash =
                "\"sourceSnapshotHash\":\"${envelope.payload.executionSnapshot.snapshotHash}\""
            outbox[SchedulingOutboxEvents.payloadJson] shouldContain expectedSnapshotHash
        }
    }

    @Test
    fun `SHADOW는 planner와 plan lookup만 평가하고 아무 row도 쓰지 않는다`() {
        val result = handler(mode = PurchaseHandlingMode.SHADOW).handle(envelope(), protectedQuarantineEnvelope())

        result.status shouldBeEqualTo PurchaseHandleStatus.SHADOW
        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 1L
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `handler metrics는 result와 reason code만 기록한다`() {
        val recorded = mutableListOf<Pair<String, String?>>()

        handler(metrics = VisitPlanningMetrics { result, reason -> recorded += result to reason })
            .handle(envelope(), protectedQuarantineEnvelope())

        recorded shouldBeEqualTo listOf("CREATED" to null)
    }

    @Test
    fun `동일 event replay와 동일 source version hash replay는 revision을 늘리지 않는다`() {
        val first = envelope()
        val handler = handler()

        handler.handle(first, protectedQuarantineEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.CREATED
        handler.handle(first, protectedQuarantineEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE
        handler.handle(envelope(eventId = "package-event-2"), protectedQuarantineEnvelope()).status shouldBeEqualTo
            PurchaseHandleStatus.DUPLICATE
        handler.handle(envelope(eventId = "package-event-3"), protectedQuarantineEnvelope()).status shouldBeEqualTo
            PurchaseHandleStatus.DUPLICATE

        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 4L
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `동일 source version의 동시 event는 Plan root lock으로 revision 하나에 수렴한다`() {
        val handler = handler()
        val events = listOf(envelope(eventId = "package-race-1"), envelope(eventId = "package-race-2"))
        val eventIndex = AtomicInteger()
        val results = ConcurrentLinkedQueue<PurchaseHandleResult>()

        MultithreadingTester()
            .workers(2)
            .rounds(1)
            .add {
                val event = events[eventIndex.getAndIncrement()]
                results += handler.handle(event, protectedQuarantineEnvelope())
            }
            .run()

        results.map(PurchaseHandleResult::status).toSet() shouldBeEqualTo
            setOf(PurchaseHandleStatus.CREATED, PurchaseHandleStatus.DUPLICATE)
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll()
                .count { it[SchedulingInboxEvents.eventId].startsWith("package-race-") } shouldBeEqualTo 2
        }
    }

    @Test
    fun `source version gap은 대기하고 같은 version의 다른 hash만 quarantine으로 수렴한다`() {
        handler().handle(envelope(), protectedQuarantineEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.CREATED

        val gap = envelope(
            eventId = "package-gap",
            payload = packageEvent(sourceAggregateVersion = 4L, snapshotHash = "b".repeat(64)),
        )
        handler().handle(gap, protectedQuarantineEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.WAITING_GAP

        val changedHash = envelope(
            eventId = "package-conflict",
            payload = packageEvent(snapshotHash = "c".repeat(64)),
        )
        handler().handle(changedHash, protectedQuarantineEnvelope()).status shouldBeEqualTo
            PurchaseHandleStatus.QUARANTINED

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll()
                .any {
                    it[SchedulingInboxEvents.failureCode] == "SOURCE_VERSION_GAP" &&
                        it[SchedulingInboxEvents.status] == SchedulingInboxStatus.WAITING_GAP
                }
                .shouldBeTrue()
            SchedulingInboxEvents.selectAll()
                .any { it[SchedulingInboxEvents.failureCode] == "SOURCE_VERSION_HASH_CONFLICT" }
                .shouldBeTrue()
        }
    }

    @Test
    fun `version gap 재시도가 상한에 도달하면 encrypted quarantine으로 전환한다`() {
        val gap = envelope(
            eventId = "package-gap-exhausted",
            payload = packageEvent(sourceAggregateVersion = 4L),
        )
        val handler = handler(maxGapAttempts = 2)

        handler.handle(gap, protectedQuarantineEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.WAITING_GAP
        val exhausted = handler.handle(gap, protectedQuarantineEnvelope())

        exhausted.status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        exhausted.reasonCode shouldBeEqualTo "SOURCE_VERSION_GAP_EXHAUSTED"
        transaction {
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 1L
            val inbox = SchedulingInboxEvents.selectAll()
                .single { it[SchedulingInboxEvents.eventId] == gap.eventId }
            inbox[SchedulingInboxEvents.attemptCount] shouldBeEqualTo 2
        }
    }

    @Test
    fun `선택되지 않은 component provenance와 구매 상품 불일치는 revision 없이 격리한다`() {
        val invalidProvenanceSnapshot = packageEvent().executionSnapshot.let { snapshot ->
            snapshot.copy(
                expandedTreatmentItems = snapshot.expandedTreatmentItems.mapIndexed { index, treatment ->
                    if (index == 0) treatment.copy(componentProductId = "not-selected") else treatment
                },
            )
        }
        val invalidProvenance = envelope(
            eventId = "package-invalid-provenance",
            payload = packageEvent(snapshot = invalidProvenanceSnapshot),
        )
        val wrongProductSnapshot = packageEvent(snapshotHash = "b".repeat(64)).executionSnapshot
            .copy(packageProductId = "other-product")
        val wrongProduct = envelope(
            eventId = "package-wrong-product",
            payload = packageEvent(
                sourceAggregateVersion = 3L,
                snapshotHash = "b".repeat(64),
                snapshot = wrongProductSnapshot,
            ),
        )

        val invalidResult = handler().handle(invalidProvenance, protectedQuarantineEnvelope())
        val wrongProductResult = handler().handle(wrongProduct, protectedQuarantineEnvelope())

        invalidResult.reasonCode shouldBeEqualTo "PACKAGE_EXECUTION_INVALID"
        wrongProductResult.reasonCode shouldBeEqualTo "PACKAGE_EXECUTION_PRODUCT_MISMATCH"
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingQuarantineEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `tenant clinic 불일치는 FK 없는 terminal rejection만 남긴다`() {
        val mismatch = envelope(payload = packageEvent(tenantGroupId = 2L))

        val result = handler().handle(mismatch, protectedQuarantineEnvelope())

        result.status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        result.reasonCode shouldBeEqualTo "TENANT_CLINIC_MISMATCH"
        transaction {
            UntrustedSchedulingEventRejections.selectAll().count() shouldBeEqualTo 1L
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 0L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `기존 구매 Plan이 없으면 payload 없는 quarantine만 남긴다`() {
        val missing = envelope(payload = packageEvent(sourcePurchaseId = "missing-purchase"))

        val result = handler().handle(missing, protectedQuarantineEnvelope())

        result.status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        result.reasonCode shouldBeEqualTo "APPOINTMENT_PLAN_NOT_FOUND"
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 0L
            SchedulingQuarantineEvents.selectAll().single()[SchedulingQuarantineEvents.encryptionKeyId] shouldBeEqualTo
                "quarantine-key-1"
        }
    }

    @Test
    fun `revision 저장 뒤 실패하면 inbox revision children outbox가 모두 rollback된다`() {
        val failing = handler(observer = AtomicPlanRevisionWriteObserver { error("forced") })

        assertFailsWith<IllegalStateException> {
            failing.handle(envelope(), protectedQuarantineEnvelope())
        }

        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 1L
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 0L
            PlanRevisionTreatments.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun handler(
        mode: PurchaseHandlingMode = PurchaseHandlingMode.WRITE,
        maxGapAttempts: Int = 5,
        observer: AtomicPlanRevisionWriteObserver = AtomicPlanRevisionWriteObserver.NOOP,
        metrics: VisitPlanningMetrics = VisitPlanningMetrics.NOOP,
    ) = VisitPlanningEventHandler(
        eventRepository = eventRepository,
        quarantineRepository = quarantineRepository,
        planRepository = planRepository,
        planner = PackageExecutionPlanner(),
        revisionRepository = revisionRepository,
        versionVerifier = SourceAggregateVersionVerifier(clock),
        clock = clock,
        mode = mode,
        maxGapAttempts = maxGapAttempts,
        gapJitter = 0.0,
        writeObserver = observer,
        metrics = metrics,
    )

    private fun seedPlan() {
        val catalog = requireNotNull(
            catalogRepository.findByScopeVersion(1L, clinicId, "product-catalog", "laser-care", 7L),
        )
        planRepository.saveAggregate(
            AppointmentPlanFactory().create(
                catalog = catalog,
                input = io.bluetape4k.clinic.appointment.service.AppointmentPlanFactoryInput(
                    sourcePurchaseAuthority = "commerce",
                    sourcePurchaseId = "purchase-1",
                    patientReferenceCiphertext = "encrypted-reference",
                    patientReferenceKeyId = "key-1",
                    patientReferenceFingerprint = "patient-fingerprint",
                    bookingPreference = BookingPreferenceSnapshot.NotProvided,
                ),
            ),
        )
    }

    private fun seedProcessedPurchaseVersion() {
        val seededClinicId = clinicId
        SchedulingInboxEvents.insert {
            it[eventId] = "purchase-completed-anchor"
            it[eventType] = "PurchaseCompleted"
            it[producer] = "commerce-service"
            it[sourceAuthority] = "commerce"
            it[sourceAggregateId] = "purchase-aggregate"
            it[sourceAggregateVersion] = 1L
            it[tenantGroupId] = EntityID(1L, TenantGroups)
            it[SchedulingInboxEvents.clinicId] = EntityID(seededClinicId, Clinics)
            it[payloadHash] = "f".repeat(64)
            it[status] = SchedulingInboxStatus.PROCESSED
            it[attemptCount] = 0
            it[occurredAt] = now.minusSeconds(60)
            it[receivedAt] = now.minusSeconds(59)
            it[processedAt] = now.minusSeconds(58)
        }
    }

    private fun envelope(
        eventId: String = "package-event-1",
        payload: PackageExecutionEvent = packageEvent(),
    ) = TrustedSchedulingEventEnvelope(
        eventId = eventId,
        eventType = "PackageExecutionPlanned",
        occurredAt = now.minusSeconds(10),
        receivedAt = now,
        producer = "commerce-service",
        issuer = "commerce-issuer",
        audience = "appointment-service",
        keyId = "commerce-key",
        algorithm = "EdDSA",
        schemaVersion = 1,
        correlationId = "correlation-1",
        payloadHash = PackageExecutionPayloadHasher.hash(payload),
        payload = payload,
    )

    private fun packageEvent(
        sourceAggregateVersion: Long = 2L,
        sourcePurchaseId: String = "purchase-1",
        snapshotHash: String = "a".repeat(64),
        tenantGroupId: Long = 1L,
        snapshot: PackageExecutionSnapshot = executionSnapshot(snapshotHash),
    ) = PackageExecutionEvent(
        sourceAggregateId = "purchase-aggregate",
        sourceAggregateVersion = sourceAggregateVersion,
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = sourcePurchaseId,
        executionSnapshot = snapshot,
    )

    private fun executionSnapshot(snapshotHash: String): PackageExecutionSnapshot =
        PackageExecutionSnapshot(
            packageProductId = "laser-care",
            packageProductVersionId = "laser-package-v1",
            selectedComponentVersions = listOf(
                ComponentVersionRef("whitening", "whitening-v2", quantity = 5, selectionGroupId = "care"),
                ComponentVersionRef("peeling", "peeling-v4", quantity = 1, selectionGroupId = "care"),
            ),
            componentSelections = listOf(
                ComponentSelection("care", candidateCount = 3, requiredSelectionCount = 2),
            ),
            expandedTreatmentItems = (1..5).map { sequence ->
                treatment("whitening-$sequence", "whitening", "whitening-v2", sequence)
            } + listOf(
                treatment("peeling-1", "peeling", "peeling-v4", 1),
            ),
            executionDependencies = listOf(
                ExecutionDependency("whitening-5", "peeling-1", ExecutionDependencyType.BLOCKING, 21, 28, 42),
            ),
            visitGroupingConstraints = listOf(
                VisitGroupingConstraint("whitening-1", "peeling-1", VisitGroupingType.MUST_SEPARATE_VISIT),
            ),
            snapshotHash = snapshotHash,
        )

    private fun treatment(
        key: String,
        componentProductId: String,
        componentProductVersionId: String,
        sequence: Int,
    ) = ExecutionTreatment(
        treatmentKey = key,
        componentProductId = componentProductId,
        componentProductVersionId = componentProductVersionId,
        sourceBomItemId = "$componentProductId-bom-$sequence",
        sequence = sequence,
        representativeTreatmentName = if (componentProductId == "whitening") "미백 진료" else "필링 진료",
        detailedTreatmentCodes = listOf(componentProductId.uppercase()),
        preparationMinutes = 10,
        treatmentMinutes = 20,
        recoveryMinutes = 10,
        practitionerQualifications = listOf("DOCTOR"),
        equipmentTypes = listOf("LASER"),
        spaceCapabilities = listOf("LASER_ROOM"),
    )

    private fun protectedQuarantineEnvelope() = ProtectedQuarantineEnvelope(
        ciphertext = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        keyId = "quarantine-key-1",
        envelopeHash = "b".repeat(64),
    )

    private fun catalogRecord() = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = 1L,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = "laser-care",
            catalogVersion = 7L,
            productName = "Laser Care",
            schemaVersion = 1,
            sourceUpdatedAt = now.minusSeconds(60),
            items = listOf(
                CatalogBomItem(
                    bomItemId = "laser",
                    representativeTreatmentName = "Laser",
                    detailedTreatmentCodes = listOf("LASER"),
                    repeatCount = 1,
                    durationMinutes = 30,
                    minimumIntervalDays = 21,
                    preferredIntervalDays = 28,
                    maximumIntervalDays = 42,
                    practitionerQualifications = listOf("DERMATOLOGIST"),
                    equipmentTypes = listOf("LASER_A"),
                    roomTypes = listOf("PROCEDURE"),
                )
            ),
            dependencies = emptyList(),
            initialBookingRule = null,
        ),
        payloadHash = "a".repeat(64),
    )
}

package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.CatalogProjectionStatus
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.PlannedTreatments
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomDependencies
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogBomItems
import io.bluetape4k.clinic.appointment.model.tables.ProductCatalogProjections
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentDependencies
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PurchaseCompletedHandlerTest {

    private val now = Instant.parse("2026-07-26T05:10:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val eventRepository = SchedulingEventRepository()
    private val catalogRepository = ProductCatalogRepository()
    private val planRepository = AppointmentPlanRepository()
    private val factory = AppointmentPlanFactory()
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:purchase_handler_${System.nanoTime()};DB_CLOSE_DELAY=-1",
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
                SchedulingInboxEvents,
                SchedulingOutboxEvents,
                UntrustedSchedulingEventRejections,
                SchedulingQuarantineEvents,
                SchedulingQuarantineAuditEvents,
            )
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            UntrustedSchedulingEventRejections.deleteAll()
            SchedulingOutboxEvents.deleteAll()
            SchedulingInboxEvents.deleteAll()
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
            TenantGroups.insert {
                it[id] = EntityID(2L, TenantGroups)
                it[tenantCode] = "tenant-two"
                it[displayName] = "Tenant Two"
                it[active] = true
            }
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Plan Clinic"
            }.value
            catalogRepository.saveAggregate(catalogRecord(clinicId))
        }
    }

    @Test
    fun `valid event writes inbox plan children and redacted outbox atomically`() {
        val envelope = envelope()

        val result = handler().handle(envelope, null, protected())

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 1L
            PlannedTreatments.selectAll().count() shouldBeEqualTo 3L
            SchedulingInboxEvents.selectAll().single()[SchedulingInboxEvents.status] shouldBeEqualTo
                SchedulingInboxStatus.PROCESSED
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.status] shouldBeEqualTo SchedulingOutboxStatus.PENDING
            (outbox[SchedulingOutboxEvents.eventId] == envelope.eventId).shouldBeFalse()
            outbox[SchedulingOutboxEvents.causationEventId] shouldBeEqualTo envelope.eventId
            outbox[SchedulingOutboxEvents.correlationId] shouldBeEqualTo envelope.correlationId
            outbox[SchedulingOutboxEvents.aggregateType] shouldBeEqualTo "APPOINTMENT_PLAN"
            outbox[SchedulingOutboxEvents.aggregateId] shouldBeEqualTo
                outbox[SchedulingOutboxEvents.planId]?.value.toString()
            outbox[SchedulingOutboxEvents.planId]?.value shouldBeEqualTo
                AppointmentPlans.selectAll().single()[AppointmentPlans.id].value
            val payload = outbox[SchedulingOutboxEvents.payloadJson]
            payload.contains("patient-token").shouldBeFalse()
            payload.contains("Laser").shouldBeFalse()
            payload.contains("\"sourcePurchaseAuthority\":\"commerce\"").shouldBeTrue()
            payload.contains("\"sourcePurchaseId\":\"purchase-1\"").shouldBeTrue()
            payload.contains("\"sourceAggregateVersion\":1").shouldBeTrue()

            val convergence = eventRepository.readOutboxDualWriteConvergence()
            convergence.aggregateIdentityMissingCount shouldBeEqualTo 0L
            convergence.legacyPlanRowCount shouldBeEqualTo 1L
            convergence.legacyPlanMismatchCount shouldBeEqualTo 0L
            convergence.dualWriteParityGauge shouldBeEqualTo 1.0
        }
    }

    @Test
    fun `operator convergence query detects a plan aggregate mismatch`() {
        handler().handle(envelope(), null, protected())

        transaction {
            SchedulingOutboxEvents.update {
                it[aggregateId] = "wrong-plan"
            }
            val convergence = eventRepository.readOutboxDualWriteConvergence()
            convergence.aggregateIdentityMissingCount shouldBeEqualTo 0L
            convergence.legacyPlanRowCount shouldBeEqualTo 1L
            convergence.legacyPlanMismatchCount shouldBeEqualTo 1L
            convergence.dualWriteParityGauge shouldBeEqualTo 0.0
        }
    }

    @Test
    fun `operator convergence query detects missing generic aggregate identity`() {
        handler().handle(envelope(), null, protected())

        transaction {
            SchedulingOutboxEvents.update {
                it[aggregateId] = null
            }
            val convergence = eventRepository.readOutboxDualWriteConvergence()
            convergence.aggregateIdentityMissingCount shouldBeEqualTo 1L
            convergence.legacyPlanRowCount shouldBeEqualTo 1L
            convergence.legacyPlanMismatchCount shouldBeEqualTo 1L
            convergence.converged.shouldBeFalse()
            convergence.dualWriteParityGauge shouldBeEqualTo 0.0
        }
    }

    @Test
    fun `plan outbox allow-list escapes trusted string metadata without field injection`() {
        val sourcePurchaseId = "purchase-\\\"},\\\"injected\\\":\\\"secret\\nline"
        val correlationId = "correlation-\\\\quoted\\\""
        handler().handle(envelope(), null, protected())
        val craftedEnvelope = envelope(
            correlationId = correlationId,
            payload = payload(sourcePurchaseId = sourcePurchaseId),
        )

        transaction {
            val planId = AppointmentPlans.selectAll().single()[AppointmentPlans.id].value
            SchedulingOutboxEvents.deleteAll()
            eventRepository.insertPlanCreatedOutbox(craftedEnvelope, planId)
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.correlationId] shouldBeEqualTo correlationId
            val payloadJson = outbox[SchedulingOutboxEvents.payloadJson]
            payloadJson.contains(""","injected":""").shouldBeFalse()
            payloadJson.contains("injected").shouldBeTrue()
            payloadJson.contains("\\\\").shouldBeTrue()
            payloadJson.contains("\\nline").shouldBeTrue()
            payloadJson.contains('\n').shouldBeFalse()
            payloadJson.contains("patient-token").shouldBeFalse()
        }
    }

    @Test
    fun `duplicate events and source purchases converge without changing ownership`() {
        val first = envelope()
        handler().handle(first, null, protected())

        handler().handle(first, null, protected()).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE
        val replay = envelope(eventId = "event-2")
        handler().handle(replay, null, protected()).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE
        val changedPatient = envelope(eventId = "event-3")
        handler().handle(changedPatient, null, protected(fingerprint = "changed"))
            .status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED

        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 3L
            SchedulingInboxEvents.selectAll()
                .first { it[SchedulingInboxEvents.eventId] == "event-3" }[SchedulingInboxEvents.failureCode] shouldBeEqualTo
                "PURCHASE_OWNERSHIP_CONFLICT"
        }
    }

    @Test
    fun `the same authority-local purchase identity is independent across tenant and clinic scope`() {
        handler().handle(envelope(), null, protected()).status shouldBeEqualTo PurchaseHandleStatus.CREATED
        val otherClinicId = transaction {
            val createdClinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(2L, TenantGroups)
                it[name] = "Other Tenant Clinic"
            }.value
            catalogRepository.saveAggregate(catalogRecord(createdClinicId, tenantGroupId = 2L))
            createdClinicId
        }
        val otherScope = envelope(
            eventId = "other-scope-event",
            payload = payload(
                tenantGroupId = 2L,
                clinicId = otherClinicId,
            ),
        )

        handler().handle(otherScope, null, protected(fingerprint = "other-patient"))
            .status shouldBeEqualTo PurchaseHandleStatus.CREATED

        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 2L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `scope mismatch is terminally rejected while catalog failures are quarantined without plan or outbox`() {
        val wrongTenant = envelope(
            eventId = "scope-event",
            payload = payload(tenantGroupId = 2L),
        )
        handler().handle(wrongTenant, null, protected()).status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        val unknownCatalog = envelope(
            eventId = "catalog-event",
            payload = payload(sourceAggregateId = "purchase-aggregate-2", sourcePurchaseId = "purchase-2", catalogVersion = 99L),
        )
        handler().handle(unknownCatalog, null, protected()).status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        transaction {
            catalogRepository.saveAggregate(
                catalogRecord(
                    clinicId = clinicId,
                    sourceAuthority = "legacy-catalog",
                    status = CatalogProjectionStatus.RETIRED,
                )
            )
        }
        val retiredCatalog = envelope(
            eventId = "retired-catalog-event",
            payload = payload(
                sourceAggregateId = "purchase-aggregate-3",
                sourcePurchaseId = "purchase-3",
                catalogSourceAuthority = "legacy-catalog",
            ),
        )
        val retiredResult = handler().handle(retiredCatalog, null, protected(fingerprint = "retired"))
        retiredResult.status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        retiredResult.reasonCode shouldBeEqualTo "CATALOG_RETIRED"

        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 2L
            UntrustedSchedulingEventRejections.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `failure after plan insert rolls back inbox plan children and outbox`() {
        val failing = handler(observer = AtomicPlanWriteObserver { error("forced") })

        assertFailsWith<IllegalStateException> {
            failing.handle(envelope(), null, protected())
        }

        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
            AppointmentPlans.selectAll().count() shouldBeEqualTo 0L
            PlannedTreatments.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `current and previous schemas normalize to one typed command`() {
        val adapter = PurchaseCompletedEventAdapter()

        adapter.adapt(envelope(schemaVersion = 1)).schemaVersion shouldBeEqualTo 2
        adapter.adapt(envelope(schemaVersion = 2)).schemaVersion shouldBeEqualTo 2
        assertFailsWith<IllegalArgumentException> {
            adapter.adapt(envelope(schemaVersion = 0))
        }
    }

    @Test
    fun `stale versions converge and gaps retry with bounded deterministic backoff then quarantine`() {
        handler().handle(envelope(), null, protected())
        val stale = envelope(
            eventId = "stale-event",
            payload = payload(sourcePurchaseId = "purchase-stale"),
        )
        handler().handle(stale, null, protected(fingerprint = "stale")).status shouldBeEqualTo PurchaseHandleStatus.STALE

        val gap = envelope(
            eventId = "gap-event",
            payload = payload(
                sourceAggregateId = "gap-aggregate",
                sourceAggregateVersion = 3L,
                sourcePurchaseId = "gap-purchase",
            ),
        )
        val gapHandler = handler()
        val first = gapHandler.handle(gap, null, protected(fingerprint = "gap"))
        first.status shouldBeEqualTo PurchaseHandleStatus.WAITING_GAP
        first.replayAfter!!.isAfter(now.plusSeconds(3)).shouldBeTrue()
        repeat(3) {
            gapHandler.handle(gap, null, protected(fingerprint = "gap")).status shouldBeEqualTo
                PurchaseHandleStatus.WAITING_GAP
        }
        gapHandler.handle(gap, null, protected(fingerprint = "gap")).status shouldBeEqualTo
            PurchaseHandleStatus.QUARANTINED

        transaction {
            val row = SchedulingInboxEvents.selectAll()
                .single { it[SchedulingInboxEvents.eventId] == "gap-event" }
            row[SchedulingInboxEvents.attemptCount] shouldBeEqualTo 5
            row[SchedulingInboxEvents.failureCode] shouldBeEqualTo "SOURCE_VERSION_GAP_EXHAUSTED"
            AppointmentPlans.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `a version proof is accepted only for the exact tenant clinic producer and source authority`() {
        val gap = envelope(
            eventId = "scoped-proof-event",
            payload = payload(
                sourceAggregateId = "scoped-proof-aggregate",
                sourceAggregateVersion = 3L,
                sourcePurchaseId = "scoped-proof-purchase",
            ),
        )
        val wrongScopeProof = proof(gap.payload).copy(tenantGroupId = 2L)

        handler().handle(gap, wrongScopeProof, protected(fingerprint = "scoped-proof"))
            .status shouldBeEqualTo PurchaseHandleStatus.WAITING_GAP

        handler().handle(
            gap,
            proof(gap.payload).copy(producer = "other-producer"),
            protected(fingerprint = "scoped-proof"),
        ).status shouldBeEqualTo PurchaseHandleStatus.WAITING_GAP

        handler().handle(gap, proof(gap.payload), protected(fingerprint = "scoped-proof"))
            .status shouldBeEqualTo PurchaseHandleStatus.CREATED
    }

    @Test
    fun `trusted event with unknown clinic scope is terminally rejected before the foreign key inbox`() {
        val invalidScope = envelope(
            eventId = "unknown-clinic-scope",
            payload = payload(
                clinicId = 99_999L,
                sourcePurchaseId = "unknown-clinic-purchase",
            ),
        )

        handler().handle(invalidScope, null, protected()).status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        handler().handle(invalidScope, null, protected()).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE

        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
            val rejection = UntrustedSchedulingEventRejections.selectAll().single()
            rejection[UntrustedSchedulingEventRejections.reasonCode] shouldBeEqualTo "TENANT_CLINIC_MISMATCH"
            rejection[UntrustedSchedulingEventRejections.claimedClinicId] shouldBeEqualTo 99_999L
        }
    }

    @Test
    fun `shadow mode evaluates but writes no inbox plan or outbox`() {
        val result = handler(mode = PurchaseHandlingMode.SHADOW).handle(envelope(), null, protected())

        result.status shouldBeEqualTo PurchaseHandleStatus.SHADOW
        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
            AppointmentPlans.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `concurrent event and purchase races each converge to one plan`() {
        val sameEventResults = race(envelope(), envelope())
        sameEventResults.count { it.status == PurchaseHandleStatus.CREATED } shouldBeEqualTo 1
        sameEventResults.count { it.status == PurchaseHandleStatus.DUPLICATE } shouldBeEqualTo 1

        val purchasePayload = payload(
            sourceAggregateId = "race-aggregate",
            sourcePurchaseId = "race-purchase",
        )
        val purchaseResults = race(
            envelope(eventId = "race-event-a", payload = purchasePayload),
            envelope(eventId = "race-event-b", payload = purchasePayload),
            protected = protected(fingerprint = "race"),
        )
        purchaseResults.count { it.status == PurchaseHandleStatus.CREATED } shouldBeEqualTo 1
        purchaseResults.count { it.status == PurchaseHandleStatus.DUPLICATE } shouldBeEqualTo 1

        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 2L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    private fun race(
        first: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        second: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        protected: ProtectedPatientReference = protected(),
    ): List<PurchaseHandleResult> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { event ->
                executor.submit<PurchaseHandleResult> {
                    ready.countDown()
                    start.await()
                    handler().handle(event, null, protected)
                }
            }.also {
                ready.await()
                start.countDown()
            }.map { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun handler(
        mode: PurchaseHandlingMode = PurchaseHandlingMode.WRITE,
        observer: AtomicPlanWriteObserver = AtomicPlanWriteObserver.NOOP,
    ) = PurchaseCompletedHandler(
        eventRepository = eventRepository,
        quarantineRepository = SchedulingQuarantineRepository(),
        catalogRepository = catalogRepository,
        planRepository = planRepository,
        planFactory = factory,
        versionVerifier = SourceAggregateVersionVerifier(clock),
        clock = clock,
        mode = mode,
        writeObserver = observer,
    )

    private fun PurchaseCompletedHandler.handle(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        versionProof: SourceAuthorityVersionProof?,
        protectedPatientReference: ProtectedPatientReference,
    ): PurchaseHandleResult =
        handle(envelope, versionProof, protectedPatientReference, protectedQuarantineEnvelope())

    private fun protectedQuarantineEnvelope() = ProtectedQuarantineEnvelope(
        ciphertext = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        keyId = "quarantine-key-1",
        envelopeHash = "b".repeat(64),
    )

    private fun protected(fingerprint: String = "patient-fingerprint") =
        ProtectedPatientReference("encrypted-reference", "key-1", fingerprint)

    private fun proof(payload: PurchaseCompletedEvent) = SourceAuthorityVersionProof(
        tenantGroupId = payload.tenantGroupId,
        clinicId = payload.clinicId,
        producer = "commerce-service",
        sourceAuthority = payload.sourcePurchaseAuthority,
        sourceAggregateId = payload.sourceAggregateId,
        verifiedVersion = payload.sourceAggregateVersion,
        verifiedAt = now.minusSeconds(1),
        expiresAt = now.plusSeconds(60),
    )

    private fun envelope(
        eventId: String = "event-1",
        schemaVersion: Int = 2,
        correlationId: String = "correlation-1",
        payload: PurchaseCompletedEvent = payload(),
    ) = TrustedSchedulingEventEnvelope(
        eventId = eventId,
        eventType = "PurchaseCompleted",
        occurredAt = now.minusSeconds(10),
        receivedAt = now,
        producer = "commerce-service",
        issuer = "commerce-issuer",
        audience = "appointment-service",
        keyId = "commerce-key",
        algorithm = "EdDSA",
        schemaVersion = schemaVersion,
        correlationId = correlationId,
        payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
        payload = payload,
    )

    private fun payload(
        sourceAggregateId: String = "purchase-aggregate",
        sourceAggregateVersion: Long = 1L,
        tenantGroupId: Long = 1L,
        clinicId: Long = this.clinicId,
        sourcePurchaseId: String = "purchase-1",
        catalogSourceAuthority: String = "product-catalog",
        catalogVersion: Long = 7L,
    ) = PurchaseCompletedEvent(
        sourceAggregateId = sourceAggregateId,
        sourceAggregateVersion = sourceAggregateVersion,
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = sourcePurchaseId,
        patientReferenceToken = "patient-token",
        catalogSourceAuthority = catalogSourceAuthority,
        productId = "laser-care",
        catalogVersion = catalogVersion,
        bookingPreference = BookingPreferenceSnapshot.NotProvided,
    )

    private fun catalogRecord(
        clinicId: Long,
        tenantGroupId: Long = 1L,
        sourceAuthority: String = "product-catalog",
        status: CatalogProjectionStatus = CatalogProjectionStatus.ACTIVE,
    ) = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            sourceAuthority = sourceAuthority,
            productId = "laser-care",
            catalogVersion = 7L,
            productName = "Laser Care",
            schemaVersion = 1,
            sourceUpdatedAt = now.minusSeconds(60),
            status = status,
            items = listOf(
                CatalogBomItem(
                    bomItemId = "laser",
                    representativeTreatmentName = "Laser",
                    detailedTreatmentCodes = listOf("LASER"),
                    repeatCount = 3,
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

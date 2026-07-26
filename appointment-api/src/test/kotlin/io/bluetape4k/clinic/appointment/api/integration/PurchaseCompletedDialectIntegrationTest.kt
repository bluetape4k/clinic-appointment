package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.event.integration.AtomicPlanWriteObserver
import io.bluetape4k.clinic.appointment.event.integration.ProtectedPatientReference
import io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedEvent
import io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedHandler
import io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedPayloadHasher
import io.bluetape4k.clinic.appointment.event.integration.PurchaseHandleResult
import io.bluetape4k.clinic.appointment.event.integration.PurchaseHandleStatus
import io.bluetape4k.clinic.appointment.event.integration.PurchaseHandlingMode
import io.bluetape4k.clinic.appointment.event.integration.PurchasePlanMetrics
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventRepository
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SourceAggregateVersionVerifier
import io.bluetape4k.clinic.appointment.event.integration.TrustedSchedulingEventEnvelope
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
import io.bluetape4k.clinic.appointment.model.catalog.ProductCatalogDefinition
import io.bluetape4k.clinic.appointment.model.dto.ProductCatalogProjectionRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs through the API module's H2/PostgreSQL/MySQL database matrix without
 * creating containers independently from the shared singleton launchers.
 */
class PurchaseCompletedDialectIntegrationTest : AbstractApiIntegrationTest() {

    private val now = Instant.parse("2026-07-26T05:10:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val eventRepository = SchedulingEventRepository()
    private val catalogRepository = ProductCatalogRepository()
    private val planRepository = AppointmentPlanRepository()
    private val prefix = UUID.randomUUID().toString().replace("-", "")
    private var tenantGroupId: Long = 0
    private var clinicId: Long = 0

    @BeforeEach
    fun setUpCatalog() {
        transaction {
            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "dialect-$prefix"
                it[displayName] = "Dialect Integration Tenant"
                it[active] = true
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] =
                    EntityID(this@PurchaseCompletedDialectIntegrationTest.tenantGroupId, TenantGroups)
                it[name] = "Dialect Integration Clinic"
            }.value
            catalogRepository.saveAggregate(catalog())
        }
    }

    @Test
    fun `purchase convergence remains atomic and deterministic on the active database dialect`() {
        val metricsInsideTransaction = AtomicBoolean(false)
        val metrics = PurchasePlanMetrics { _, _ ->
            if (TransactionManager.currentOrNull() != null) {
                metricsInsideTransaction.set(true)
            }
        }

        val sameEventEnvelope = envelope("same-event", payload("same-purchase", "same-aggregate"))
        val sameEvent = race(sameEventEnvelope, sameEventEnvelope, metrics)
        sameEvent.count { it.status == PurchaseHandleStatus.CREATED } shouldBeEqualTo 1
        sameEvent.count { it.status == PurchaseHandleStatus.DUPLICATE } shouldBeEqualTo 1

        val samePurchasePayload = payload("purchase-race", "purchase-race-aggregate")
        val samePurchase = race(
            envelope("purchase-race-a", samePurchasePayload),
            envelope("purchase-race-b", samePurchasePayload),
            metrics,
        )
        samePurchase.count { it.status == PurchaseHandleStatus.CREATED } shouldBeEqualTo 1
        samePurchase.count { it.status == PurchaseHandleStatus.DUPLICATE } shouldBeEqualTo 1

        val rollbackHandler = handler(
            metrics = metrics,
            observer = AtomicPlanWriteObserver { error("forced rollback") },
        )
        assertFailsWith<IllegalStateException> {
            rollbackHandler.handle(
                envelope("rollback-event", payload("rollback-purchase", "rollback-aggregate")),
                null,
                protected("rollback"),
            )
        }

        val gapEnvelope = envelope(
            "gap-event",
            payload("gap-purchase", "gap-aggregate", sourceAggregateVersion = 3L),
        )
        val gapHandler = handler(metrics)
        repeat(4) {
            gapHandler.handle(gapEnvelope, null, protected("gap")).status shouldBeEqualTo
                PurchaseHandleStatus.WAITING_GAP
        }
        gapHandler.handle(gapEnvelope, null, protected("gap")).status shouldBeEqualTo
            PurchaseHandleStatus.QUARANTINED

        transaction {
            AppointmentPlans.selectAll().count() shouldBeEqualTo 2L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 2L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 4L
        }
        metricsInsideTransaction.get().shouldBeFalse()
    }

    private fun race(
        first: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        second: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        metrics: PurchasePlanMetrics,
    ): List<PurchaseHandleResult> {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { event ->
                executor.submit<PurchaseHandleResult> {
                    ready.countDown()
                    start.await()
                    handler(metrics).handle(event, null, protected())
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
        metrics: PurchasePlanMetrics,
        observer: AtomicPlanWriteObserver = AtomicPlanWriteObserver.NOOP,
    ) = PurchaseCompletedHandler(
        eventRepository = eventRepository,
        catalogRepository = catalogRepository,
        planRepository = planRepository,
        planFactory = AppointmentPlanFactory(),
        versionVerifier = SourceAggregateVersionVerifier(clock),
        clock = clock,
        mode = PurchaseHandlingMode.WRITE,
        writeObserver = observer,
        metrics = metrics,
    )

    private fun envelope(
        eventId: String,
        payload: PurchaseCompletedEvent,
    ) = TrustedSchedulingEventEnvelope(
        eventId = "$prefix-$eventId",
        eventType = "PurchaseCompleted",
        occurredAt = now.minusSeconds(10),
        receivedAt = now,
        producer = "commerce-service",
        issuer = "commerce-issuer",
        audience = "appointment-service",
        keyId = "commerce-key",
        schemaVersion = 2,
        correlationId = "$prefix-correlation",
        payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
        payload = payload,
    )

    private fun payload(
        sourcePurchaseId: String,
        sourceAggregateId: String,
        sourceAggregateVersion: Long = 1L,
    ) = PurchaseCompletedEvent(
        sourceAggregateId = "$prefix-$sourceAggregateId",
        sourceAggregateVersion = sourceAggregateVersion,
        tenantGroupId = tenantGroupId,
        clinicId = clinicId,
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = "$prefix-$sourcePurchaseId",
        patientReferenceToken = "patient-token",
        productId = "$prefix-laser-care",
        catalogVersion = 7L,
        bookingPreference = BookingPreferenceSnapshot.NotProvided,
    )

    private fun protected(fingerprint: String = "patient") =
        ProtectedPatientReference("encrypted-reference", "key-1", "$prefix-$fingerprint")

    private fun catalog() = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = "$prefix-laser-care",
            catalogVersion = 7L,
            productName = "Laser Care",
            schemaVersion = 1,
            sourceUpdatedAt = now.minusSeconds(60),
            items = listOf(
                CatalogBomItem(
                    bomItemId = "laser",
                    representativeTreatmentName = "Laser",
                    detailedTreatmentCodes = listOf("LASER"),
                    repeatCount = 2,
                    durationMinutes = 30,
                    minimumIntervalDays = 21,
                    preferredIntervalDays = 28,
                    maximumIntervalDays = 42,
                    practitionerQualifications = listOf("DERMATOLOGIST"),
                    equipmentTypes = listOf("LASER_A"),
                    roomTypes = listOf("PROCEDURE"),
                ),
            ),
            dependencies = emptyList(),
            initialBookingRule = null,
        ),
        payloadHash = "a".repeat(64),
    )
}

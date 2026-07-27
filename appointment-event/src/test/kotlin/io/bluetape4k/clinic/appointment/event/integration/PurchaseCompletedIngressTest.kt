package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.ProductCatalogRepository
import io.bluetape4k.clinic.appointment.service.AppointmentPlanFactory
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PurchaseCompletedIngressTest {

    private val now = Instant.parse("2026-07-26T05:10:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:purchase_ingress_${System.nanoTime()};DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
                TenantGroups,
                Clinics,
                UntrustedSchedulingEventRejections,
                SchedulingInboxEvents,
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
                it[name] = "Ingress Clinic"
            }.value
        }
    }

    @Test
    fun `concurrent invalid signatures converge to one terminal quarantine`() {
        val barrier = CyclicBarrier(2)
        val ingress = ingress(
            signatureVerifier = SchedulingEventSignatureVerifier { false },
            inboxInsertObserver = InboxInsertObserver {
                barrier.await(5, TimeUnit.SECONDS)
            },
        )

        val results = acceptConcurrently(ingress, envelope(signature = "invalid"))

        results.map { it.status }.toSet() shouldBeEqualTo
            setOf(PurchaseHandleStatus.QUARANTINED, PurchaseHandleStatus.DUPLICATE)
        transaction {
            UntrustedSchedulingEventRejections.selectAll().toList().shouldHaveSize(1)
            SchedulingInboxEvents.selectAll().toList().shouldHaveSize(0)
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(0)
            SchedulingQuarantineAuditEvents.selectAll().toList().shouldHaveSize(0)
        }
    }

    @Test
    fun `trust failure with unknown claimed scope is durably rejected and duplicate retry converges`() {
        val ingress = ingress(
            signatureVerifier = SchedulingEventSignatureVerifier { false },
            inboxInsertObserver = InboxInsertObserver.NOOP,
        )
        val poison = envelope(signature = "invalid").let {
            val payload = it.payload.copy(tenantGroupId = 99_001L, clinicId = 99_002L)
            it.copy(payload = payload, payloadHash = PurchaseCompletedPayloadHasher.hash(payload))
        }

        ingress.accept(poison).status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        ingress.accept(poison).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE

        transaction {
            val rejection = UntrustedSchedulingEventRejections.selectAll().single()
            rejection[UntrustedSchedulingEventRejections.claimedTenantGroupId] shouldBeEqualTo 99_001L
            rejection[UntrustedSchedulingEventRejections.claimedClinicId] shouldBeEqualTo 99_002L
            SchedulingInboxEvents.selectAll().toList().shouldHaveSize(0)
        }
    }

    @Test
    fun `concurrent authority timeouts converge to one bounded waiting attempt`() {
        val barrier = CyclicBarrier(2)
        val ingress = ingress(
            signatureVerifier = SchedulingEventSignatureVerifier { true },
            versionProofProvider = SourceAuthorityVersionProofProvider { _, _ ->
                throw SourceAuthorityUnavailableException(SourceAuthorityFailureReason.TIMEOUT)
            },
            inboxInsertObserver = InboxInsertObserver {
                barrier.await(5, TimeUnit.SECONDS)
            },
        )

        val results = acceptConcurrently(ingress, envelope())

        results.map { it.status }.toSet() shouldBeEqualTo setOf(PurchaseHandleStatus.WAITING_GAP)
        results.map { it.reasonCode }.toSet() shouldBeEqualTo setOf("SOURCE_AUTHORITY_TIMEOUT")
        transaction {
            val inboxRows = SchedulingInboxEvents.selectAll().toList()
            inboxRows.shouldHaveSize(1)
            inboxRows.single()[SchedulingInboxEvents.attemptCount] shouldBeEqualTo 1
            SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(0)
        }
    }

    private fun ingress(
        signatureVerifier: SchedulingEventSignatureVerifier,
        versionProofProvider: SourceAuthorityVersionProofProvider = SourceAuthorityVersionProofProvider { _, _ -> null },
        inboxInsertObserver: InboxInsertObserver,
    ): PurchaseCompletedIngress {
        val handler = PurchaseCompletedHandler(
            eventRepository = SchedulingEventRepository(),
            quarantineRepository = SchedulingQuarantineRepository(clock),
            catalogRepository = ProductCatalogRepository(),
            planRepository = AppointmentPlanRepository(),
            planFactory = AppointmentPlanFactory(),
            versionVerifier = SourceAggregateVersionVerifier(clock),
            clock = clock,
            mode = PurchaseHandlingMode.WRITE,
            inboxInsertObserver = inboxInsertObserver,
        )
        return PurchaseCompletedIngress(
            trustVerifier = SchedulingEventTrustVerifier(
                signatureVerifier = signatureVerifier,
                allowedProducers = setOf("commerce-service"),
                allowedKeyIds = setOf("commerce-key"),
                allowedAlgorithms = setOf("EdDSA"),
                expectedIssuer = "commerce-issuer",
                expectedAudience = "appointment-service",
                replayWindow = Duration.ofMinutes(15),
                clock = clock,
            ),
            eventAdapter = PurchaseCompletedEventAdapter(),
            versionProofProvider = versionProofProvider,
            patientReferenceProtector = PatientReferenceProtector { _, _ ->
                error("patient protection must not run before authority recovery")
            },
            quarantineEnvelopeProtector = AesGcmQuarantineEnvelopeProtector(
                encryptionKey = ByteArray(32) { index -> index.toByte() },
                keyId = "quarantine-key-1",
            ),
            handler = handler,
        )
    }

    private fun acceptConcurrently(
        ingress: PurchaseCompletedIngress,
        envelope: UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): List<PurchaseHandleResult> {
        val executor = Executors.newFixedThreadPool(2)
        return try {
            List(2) {
                executor.submit<PurchaseHandleResult> { ingress.accept(envelope) }
            }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun envelope(
        signature: String = "valid",
    ): UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
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
            signature = signature,
            payload = payload,
        )
    }
}

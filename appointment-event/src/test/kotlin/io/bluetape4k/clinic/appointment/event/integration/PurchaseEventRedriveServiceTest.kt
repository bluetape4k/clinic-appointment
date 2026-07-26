package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomItem
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
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PurchaseEventRedriveServiceTest {

    private val now = Instant.parse("2026-07-26T05:10:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val eventRepository = SchedulingEventRepository()
    private val catalogRepository = ProductCatalogRepository()
    private val planRepository = AppointmentPlanRepository()
    private var clinicId: Long = 0

    @BeforeEach
    fun setup() {
        Database.connect(
            "jdbc:h2:mem:purchase_redrive_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(
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
            )
            TenantGroups.insert {
                it[id] = EntityID(1L, TenantGroups)
                it[tenantCode] = "tenant-one"
                it[displayName] = "Tenant One"
                it[active] = true
            }
            clinicId = Clinics.insertAndGetId {
                it[tenantGroupId] = EntityID(1L, TenantGroups)
                it[name] = "Redrive Clinic"
            }.value
            catalogRepository.saveAggregate(catalog())
        }
    }

    @Test
    fun `dry run validates and returns a redacted diff without writes`() {
        val service = redriveService()

        val result = service.redrive(raw(), "event-1", 1L, dryRun = true)

        result.status shouldBeEqualTo PurchaseHandleStatus.SHADOW
        result.wouldCreatePlan shouldBeEqualTo true
        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
            AppointmentPlans.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `named trusted redrive writes through the atomic handler`() {
        val result = redriveService().redrive(raw(), "event-1", 1L, dryRun = false)

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 1L
            AppointmentPlans.selectAll().count() shouldBeEqualTo 1L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `generic redrive cannot bypass trust or identity confirmation`() {
        val service = redriveService()
        assertFailsWith<IllegalArgumentException> {
            service.redrive(raw(), "different-event", 1L, dryRun = true)
        }
        assertFailsWith<IllegalArgumentException> {
            service.redrive(raw(), "event-1", 2L, dryRun = true)
        }
        listOf(
            raw(signature = "invalid"),
            raw(issuer = "wrong-issuer"),
            raw(audience = "wrong-audience"),
            raw(producer = "wrong-producer"),
            raw(occurredAt = now.minus(Duration.ofMinutes(16))),
        ).forEach { untrusted ->
            assertFailsWith<SchedulingTrustException> {
                service.redrive(untrusted, untrusted.eventId, 1L, dryRun = true)
            }
        }
        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `ingress quarantines trust failures but invalid bounds write nothing`() {
        val ingress = ingress()
        listOf(
            raw(eventId = "bad-signature", signature = "invalid"),
            raw(eventId = "bad-issuer", issuer = "wrong-issuer"),
            raw(eventId = "bad-audience", audience = "wrong-audience"),
            raw(eventId = "bad-producer", producer = "wrong-producer"),
            raw(eventId = "replayed", occurredAt = now.minus(Duration.ofMinutes(16))),
        ).forEach { untrusted ->
            ingress.accept(untrusted).status shouldBeEqualTo PurchaseHandleStatus.QUARANTINED
        }
        assertFailsWith<IllegalArgumentException> {
            ingress.accept(raw(eventId = "unsafe event id"))
        }
        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 5L
            AppointmentPlans.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `patient protector stores neither raw token nor a reversible fingerprint`() {
        val protector = AesGcmPatientReferenceProtector(
            encryptionKey = ByteArray(16) { index -> index.toByte() },
            fingerprintKey = ByteArray(32) { index -> (index + 1).toByte() },
            keyId = "patient-key-1",
        )

        val protected = protector.protect(tenantGroupId = 1L, patientReferenceToken = "patient-token")
        val sameTenant = protector.protect(tenantGroupId = 1L, patientReferenceToken = "patient-token")
        val otherTenant = protector.protect(tenantGroupId = 2L, patientReferenceToken = "patient-token")

        protected.ciphertext.contains("patient-token").shouldBeFalse()
        protected.fingerprint.contains("patient-token").shouldBeFalse()
        protected.keyId shouldBeEqualTo "patient-key-1"
        sameTenant.fingerprint shouldBeEqualTo protected.fingerprint
        otherTenant.fingerprint.equals(protected.fingerprint).shouldBeFalse()
    }

    @Test
    fun `invalid booking preference is rejected before any durable write`() {
        val invalid = payload().copy(
            bookingPreference = BookingPreferenceSnapshot.DateRange(
                startDate = LocalDate.parse("2026-08-02"),
                endDate = LocalDate.parse("2026-08-01"),
                zoneId = ZoneOffset.UTC,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ingress().accept(raw(payload = invalid))
        }

        transaction {
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 0L
            AppointmentPlans.selectAll().count() shouldBeEqualTo 0L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun ingress() = PurchaseCompletedIngress(
        trustVerifier = trustVerifier(),
        eventAdapter = PurchaseCompletedEventAdapter(),
        versionProofProvider = SourceAuthorityVersionProofProvider { null },
        patientReferenceProtector = PatientReferenceProtector { _, _ -> protected() },
        handler = handler(PurchaseHandlingMode.WRITE),
        eventRepository = eventRepository,
        clock = clock,
    )

    private fun redriveService() = PurchaseEventRedriveService(
        trustVerifier = trustVerifier(),
        eventAdapter = PurchaseCompletedEventAdapter(),
        versionProofProvider = SourceAuthorityVersionProofProvider { null },
        patientReferenceProtector = PatientReferenceProtector { _, _ -> protected() },
        writeHandler = handler(PurchaseHandlingMode.WRITE),
        shadowHandler = handler(PurchaseHandlingMode.SHADOW),
    )

    private fun trustVerifier() = SchedulingEventTrustVerifier(
        signatureVerifier = SchedulingEventSignatureVerifier { it.signature == "valid" },
        allowedProducers = setOf("commerce-service"),
        allowedKeyIds = setOf("commerce-key"),
        expectedIssuer = "commerce-issuer",
        expectedAudience = "appointment-service",
        replayWindow = Duration.ofMinutes(15),
        clock = clock,
    )

    private fun handler(mode: PurchaseHandlingMode) = PurchaseCompletedHandler(
        eventRepository = eventRepository,
        catalogRepository = catalogRepository,
        planRepository = planRepository,
        planFactory = AppointmentPlanFactory(),
        versionVerifier = SourceAggregateVersionVerifier(clock),
        clock = clock,
        mode = mode,
    )

    private fun protected() = ProtectedPatientReference("encrypted", "key-1", "fingerprint")

    private fun raw(
        eventId: String = "event-1",
        signature: String = "valid",
        producer: String = "commerce-service",
        issuer: String = "commerce-issuer",
        audience: String = "appointment-service",
        occurredAt: Instant = now.minusSeconds(10),
        payload: PurchaseCompletedEvent = payload(),
    ): UntrustedSchedulingEventEnvelope<PurchaseCompletedEvent> {
        return UntrustedSchedulingEventEnvelope(
            eventId = eventId,
            eventType = "PurchaseCompleted",
            occurredAt = occurredAt,
            receivedAt = now,
            producer = producer,
            issuer = issuer,
            audience = audience,
            keyId = "commerce-key",
            schemaVersion = 2,
            correlationId = "correlation-1",
            payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
            signature = signature,
            payload = payload,
        )
    }

    private fun payload() = PurchaseCompletedEvent(
        sourceAggregateId = "purchase-aggregate",
        sourceAggregateVersion = 1L,
        tenantGroupId = 1L,
        clinicId = clinicId,
        sourcePurchaseAuthority = "commerce",
        sourcePurchaseId = "purchase-1",
        patientReferenceToken = "patient-token",
        productId = "laser-care",
        catalogVersion = 7L,
        bookingPreference = BookingPreferenceSnapshot.NotProvided,
    )

    private fun catalog() = ProductCatalogProjectionRecord(
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
                    minimumIntervalDays = null,
                    preferredIntervalDays = null,
                    maximumIntervalDays = null,
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

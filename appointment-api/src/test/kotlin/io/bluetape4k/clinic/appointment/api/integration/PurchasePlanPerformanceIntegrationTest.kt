package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.AbstractApiIntegrationTest
import io.bluetape4k.clinic.appointment.event.integration.ProtectedPatientReference
import io.bluetape4k.clinic.appointment.event.integration.ProtectedQuarantineEnvelope
import io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedEvent
import io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedHandler
import io.bluetape4k.clinic.appointment.event.integration.PurchaseCompletedPayloadHasher
import io.bluetape4k.clinic.appointment.event.integration.PurchaseHandleStatus
import io.bluetape4k.clinic.appointment.event.integration.PurchaseHandlingMode
import io.bluetape4k.clinic.appointment.event.integration.PurchaseTransactionObserver
import io.bluetape4k.clinic.appointment.event.integration.SchedulingEventRepository
import io.bluetape4k.clinic.appointment.event.integration.SchedulingInboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineAuditEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineEvents
import io.bluetape4k.clinic.appointment.event.integration.SchedulingQuarantineRepository
import io.bluetape4k.clinic.appointment.event.integration.SourceAggregateVersionVerifier
import io.bluetape4k.clinic.appointment.event.integration.TrustedSchedulingEventEnvelope
import io.bluetape4k.clinic.appointment.model.catalog.CatalogBomDependency
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
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.ceil
import kotlin.time.Duration.Companion.nanoseconds

class PurchasePlanPerformanceIntegrationTest : AbstractApiIntegrationTest() {

    private val now = Instant.parse("2026-07-26T05:10:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val prefix = UUID.randomUUID().toString().replace("-", "")
    private val catalogRepository = ProductCatalogRepository()
    private var tenantGroupId: Long = 0
    private var clinicId: Long = 0

    @BeforeEach
    fun setupFixtures() {
        assumeTrue(isExternalDialect(), "performance proof runs only on PostgreSQL or MySQL")
        transaction {
            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "perf-$prefix"
                it[displayName] = "Plan Performance Tenant"
                it[active] = true
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] =
                    EntityID(this@PurchasePlanPerformanceIntegrationTest.tenantGroupId, TenantGroups)
                it[name] = "Plan Performance Clinic"
            }.value
            catalogRepository.saveAggregate(typicalCatalog())
            catalogRepository.saveAggregate(maximumReachableCatalog())
        }
    }

    @AfterEach
    fun cleanupFixtures() {
        if (!isExternalDialect()) return
        transaction {
            SchedulingQuarantineAuditEvents.deleteAll()
            SchedulingQuarantineEvents.deleteAll()
            SchedulingOutboxEvents.deleteAll()
            SchedulingInboxEvents.deleteAll()
            TreatmentDependencies.deleteAll()
            PlannedTreatments.deleteAll()
            AppointmentPlans.deleteAll()
            ProductCatalogBomDependencies.deleteAll()
            ProductCatalogBomItems.deleteAll()
            ProductCatalogProjections.deleteAll()
        }
    }

    @Test
    fun `typical and maximum reachable plans stay below the purchase to plan SLO with bounded SQL`() {
        val typical = measureFixture(
            fixture = "typical-4-treatment",
            productId = "$prefix-typical",
            expectedTreatments = 4,
            expectedDependencies = 1,
        )
        val maximum = measureFixture(
            fixture = "maximum-2000-treatment-1000-edge",
            productId = "$prefix-maximum",
            expectedTreatments = 2_000,
            expectedDependencies = 1_000,
        )

        typical.p95Millis.shouldBeBelowSlo()
        maximum.p95Millis.shouldBeBelowSlo()
    }

    private fun measureFixture(
        fixture: String,
        productId: String,
        expectedTreatments: Int,
        expectedDependencies: Int,
    ): PerformanceEvidence {
        executePurchase("$fixture-warmup", productId)
        val samples = ArrayList<Long>(MEASURED_EVENTS)
        repeat(MEASURED_EVENTS) { index ->
            val execution = executePurchase("$fixture-$index", productId)
            samples += execution.elapsedNanos.nanoseconds.inWholeMilliseconds
            assertBoundedSql(execution.statements, "$fixture-$index")
            transaction {
                val plan = AppointmentPlanRepository().findBySourcePurchaseAndTenantClinic(
                    sourcePurchaseAuthority = "commerce",
                    sourcePurchaseId = "$prefix-$fixture-$index",
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                )!!
                plan.treatments.size shouldBeEqualTo expectedTreatments
                plan.dependencies.size shouldBeEqualTo expectedDependencies
            }
        }
        val sorted = samples.sorted()
        val p95 = sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]
        println("PURCHASE_PLAN_PERF fixture=$fixture samplesMs=$samples p95Ms=$p95")
        return PerformanceEvidence(p95)
    }

    private fun executePurchase(eventKey: String, productId: String): PurchaseExecution {
        val capture = SqlStatementCapture()
        val payload = PurchaseCompletedEvent(
            sourceAggregateId = "$prefix-aggregate-$eventKey",
            sourceAggregateVersion = 1L,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            sourcePurchaseAuthority = "commerce",
            sourcePurchaseId = "$prefix-$eventKey",
            patientReferenceToken = "patient-token",
            catalogSourceAuthority = "product-catalog",
            productId = productId,
            catalogVersion = 1L,
            bookingPreference = BookingPreferenceSnapshot.NotProvided,
        )
        val envelope = TrustedSchedulingEventEnvelope(
            eventId = "$prefix-event-$eventKey",
            eventType = "PurchaseCompleted",
            occurredAt = now.minusSeconds(1),
            receivedAt = now,
            producer = "commerce-service",
            issuer = "commerce-issuer",
            audience = "appointment-service",
            keyId = "commerce-key",
            algorithm = "EdDSA",
            schemaVersion = 2,
            correlationId = "$prefix-correlation",
            payloadHash = PurchaseCompletedPayloadHasher.hash(payload),
            payload = payload,
        )
        val handler = PurchaseCompletedHandler(
            eventRepository = SchedulingEventRepository(),
            quarantineRepository = SchedulingQuarantineRepository(clock),
            catalogRepository = catalogRepository,
            planRepository = AppointmentPlanRepository(),
            planFactory = AppointmentPlanFactory(),
            versionVerifier = SourceAggregateVersionVerifier(clock),
            clock = clock,
            mode = PurchaseHandlingMode.WRITE,
            transactionObserver = PurchaseTransactionObserver {
                requireNotNull(TransactionManager.currentOrNull()).registerInterceptor(capture)
            },
        )

        val startedAt = System.nanoTime()
        val result = handler.handle(
            envelope = envelope,
            versionProof = null,
            protectedPatientReference = ProtectedPatientReference(
                ciphertext = "encrypted-reference",
                keyId = "patient-key-1",
                fingerprint = "$prefix-fingerprint-$eventKey",
            ),
            protectedQuarantineEnvelope = ProtectedQuarantineEnvelope(
                ciphertext = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                keyId = "quarantine-key-1",
                envelopeHash = "b".repeat(64),
            ),
        )
        val elapsed = System.nanoTime() - startedAt
        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        return PurchaseExecution(elapsed, capture.statements.toList())
    }

    private fun assertBoundedSql(
        statements: List<String>,
        sample: String,
    ) {
        if (statements.size != EXPECTED_STATEMENT_COUNT) {
            statements.forEachIndexed { index, statement ->
                println("PURCHASE_PLAN_SQL_DEBUG sample=$sample index=$index sql=${statement.replace(Regex("\\s+"), " ")}")
            }
        }
        statements.size shouldBeEqualTo EXPECTED_STATEMENT_COUNT
        statements.countSql("insert into scheduling_inbox_events") shouldBeEqualTo 1
        statements.countSql("from scheduling_inbox_events").shouldBeBetween(2, 2)
        statements.countSql("from scheduling_untrusted_event_rejections") shouldBeEqualTo 1
        statements.countSql("from scheduling_clinics").shouldBeBetween(1, 1)
        statements.countSql("from scheduling_appointment_plans").shouldBeBetween(2, 2)
        statements.countSql("from scheduling_product_catalog_projections").shouldBeBetween(2, 2)
        statements.countSql("from scheduling_product_catalog_bom_items").shouldBeBetween(1, 1)
        statements.countSql("from scheduling_product_catalog_bom_dependencies").shouldBeBetween(1, 1)
        statements.countSql("insert into scheduling_appointment_plans") shouldBeEqualTo 1
        statements.countSql("insert into scheduling_planned_treatments") shouldBeEqualTo 1
        statements.countSql("insert into scheduling_treatment_dependencies") shouldBeEqualTo 1
        statements.countSql("insert into scheduling_outbox_events") shouldBeEqualTo 1
        statements.countSql("update scheduling_inbox_events") shouldBeEqualTo 1
        (statements.countSql("from scheduling_planned_treatments") <= 1).shouldBeTrue()
        (statements.countSql("from scheduling_treatment_dependencies") <= 1).shouldBeTrue()
        println(
            "PURCHASE_PLAN_SQL sample=$sample total=${statements.size} " +
                "inboxReads=${statements.countSql("from scheduling_inbox_events")} " +
                "planReads=${statements.countSql("from scheduling_appointment_plans")} " +
                "catalogReads=${statements.countSql("from scheduling_product_catalog_projections")} " +
                "treatmentWrites=${statements.countSql("insert into scheduling_planned_treatments")} " +
                "dependencyWrites=${statements.countSql("insert into scheduling_treatment_dependencies")}"
        )
    }

    private fun typicalCatalog() = ProductCatalogProjectionRecord(
        definition = ProductCatalogDefinition(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            sourceAuthority = "product-catalog",
            productId = "$prefix-typical",
            catalogVersion = 1L,
            productName = "Typical Plan",
            schemaVersion = 1,
            sourceUpdatedAt = now,
            items = listOf(
                item("laser", repeatCount = 3),
                item("care", repeatCount = 1),
            ),
            dependencies = listOf(dependency("laser", null, "care", null)),
            initialBookingRule = null,
        ),
        payloadHash = "a".repeat(64),
    )

    private fun maximumReachableCatalog(): ProductCatalogProjectionRecord {
        val items = (0 until 20).map { item("item$it", repeatCount = 100) }
        val dependencies = buildList {
            for (predecessor in 0 until 10) {
                for (successor in 10 until 20) {
                    for (sequence in 1..10) {
                        add(dependency("item$predecessor", sequence, "item$successor", sequence))
                    }
                }
            }
        }
        dependencies.size shouldBeEqualTo 1_000
        return ProductCatalogProjectionRecord(
            definition = ProductCatalogDefinition(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                sourceAuthority = "product-catalog",
                productId = "$prefix-maximum",
                catalogVersion = 1L,
                productName = "Maximum Reachable Plan",
                schemaVersion = 1,
                sourceUpdatedAt = now,
                items = items,
                dependencies = dependencies,
                initialBookingRule = null,
            ),
            payloadHash = "c".repeat(64),
        )
    }

    private fun item(id: String, repeatCount: Int) = CatalogBomItem(
        bomItemId = id,
        representativeTreatmentName = "Treatment $id",
        detailedTreatmentCodes = listOf("CODE_$id"),
        repeatCount = repeatCount,
        durationMinutes = 30,
        minimumIntervalDays = 1,
        preferredIntervalDays = 7,
        maximumIntervalDays = 14,
        practitionerQualifications = listOf("DOCTOR"),
        equipmentTypes = listOf("DEVICE"),
        roomTypes = listOf("ROOM"),
    )

    private fun dependency(
        predecessor: String,
        predecessorSequence: Int?,
        successor: String,
        successorSequence: Int?,
    ) = CatalogBomDependency(
        predecessorBomItemId = predecessor,
        predecessorSequenceNo = predecessorSequence,
        successorBomItemId = successor,
        successorSequenceNo = successorSequence,
        minimumIntervalDays = 1,
        preferredIntervalDays = 7,
        maximumIntervalDays = 14,
    )

    private fun isExternalDialect(): Boolean {
        val profiles = System.getProperty("spring.profiles.active", "")
        return "test-postgresql" in profiles || "test-mysql" in profiles
    }

    private fun Long.shouldBeBelowSlo() {
        (this < PURCHASE_TO_PLAN_SLO_MILLIS).shouldBeTrue()
    }

    private fun List<String>.countSql(fragment: String): Int =
        count { statement -> fragment in statement }

    private fun Int.shouldBeBetween(
        minimum: Int,
        maximum: Int,
    ) {
        (this in minimum..maximum).shouldBeTrue()
    }

    private data class PurchaseExecution(
        val elapsedNanos: Long,
        val statements: List<String>,
    )

    private data class PerformanceEvidence(
        val p95Millis: Long,
    )

    private class SqlStatementCapture : StatementInterceptor {
        val statements = mutableListOf<String>()

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { context ->
                statements += context.sql(transaction).lowercase()
            }
        }
    }

    private companion object {
        const val EXPECTED_STATEMENT_COUNT = 18
        const val MEASURED_EVENTS = 10
        const val PURCHASE_TO_PLAN_SLO_MILLIS = 30_000L
    }
}

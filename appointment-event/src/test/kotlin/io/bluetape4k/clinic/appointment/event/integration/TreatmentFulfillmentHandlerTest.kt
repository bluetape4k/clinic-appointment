package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.clinic.appointment.model.operation.AppointmentOperationalExceptionType
import io.bluetape4k.clinic.appointment.model.plan.AppointmentPlanStatus
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.tables.AppointmentOperationalExceptions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlanRevisions
import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.PlanRevisionTreatments
import io.bluetape4k.clinic.appointment.repository.AppointmentOperationalExceptionRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentPlanRevisionRepository
import io.bluetape4k.clinic.appointment.service.PlanDirtySetResolver
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.ZoneOffset

class TreatmentFulfillmentHandlerTest {

    private lateinit var fixture: ExternalFactEventTestFixture

    @BeforeEach
    fun setup() {
        fixture = ExternalFactEventTestFixture("treatment_fulfillment")
    }

    @Test
    fun `완료 사실은 원본 revision을 보존하고 새 revision의 항목 상태와 Plan 상태를 갱신한다`() {
        val result = handler().handle(
            envelope(
                eventId = "fulfillment-completed",
                facts = listOf(TreatmentFulfillmentFact.completed("future-old", fixture.now)),
            ),
            protectedEnvelope(),
        )

        result.status shouldBeEqualTo PurchaseHandleStatus.CREATED
        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 2L
            revisionStatus(fixture.initialRevisionId, "future-old") shouldBeEqualTo PlanTreatmentStatus.PENDING
            revisionStatus(activeRevisionId(), "future-old") shouldBeEqualTo PlanTreatmentStatus.COMPLETED
            AppointmentPlans.selectAll().single()[AppointmentPlans.status] shouldBeEqualTo
                AppointmentPlanStatus.PARTIALLY_FULFILLED
            val payload = SchedulingOutboxEvents.selectAll().single()[SchedulingOutboxEvents.payloadJson]
            payload shouldContain "\"dirtyTreatmentKeys\":[\"blocked-next\",\"future-old\"]"
            payload shouldContain "\"future-old\":\"2026-07-29T12:00:00Z\""
            payload shouldNotContain "\"independent\""
        }
    }

    @Test
    fun `부분 이행은 완료된 원 항목과 별도 예약 가능한 잔여 항목을 새 revision에 기록한다`() {
        val remaining = fixture.executionTreatment(
            treatmentKey = "future-old:remaining:2",
            detailedCodes = listOf("CODE-B"),
        )
        val completed = fixture.executionTreatment(
            treatmentKey = "future-old",
            detailedCodes = listOf("CODE-A"),
        )

        handler().handle(
            envelope(
                eventId = "fulfillment-partial",
                facts = listOf(
                    TreatmentFulfillmentFact.partiallyFulfilled(
                        treatmentKey = "future-old",
                        completedAt = fixture.now,
                        completedTreatment = completed,
                        remainingTreatment = remaining,
                    ),
                ),
            ),
            protectedEnvelope(),
        ).status shouldBeEqualTo PurchaseHandleStatus.CREATED

        transaction {
            revisionStatus(fixture.initialRevisionId, "future-old") shouldBeEqualTo PlanTreatmentStatus.PENDING
            val activeRevisionId = activeRevisionId()
            revisionStatus(activeRevisionId, "future-old") shouldBeEqualTo PlanTreatmentStatus.COMPLETED
            revisionStatus(activeRevisionId, "future-old:remaining:2") shouldBeEqualTo PlanTreatmentStatus.PENDING
            PlanRevisionTreatments.selectAll().single {
                it[PlanRevisionTreatments.planRevisionId].value == activeRevisionId &&
                    it[PlanRevisionTreatments.treatmentKey] == "future-old"
            }[PlanRevisionTreatments.detailedTreatmentCodesPayload] shouldBeEqualTo "[\"CODE-A\"]"
            val outbox = SchedulingOutboxEvents.selectAll().single()
            outbox[SchedulingOutboxEvents.eventType] shouldBeEqualTo "TreatmentFulfillmentApplied"
        }
    }

    @Test
    fun `장비 고장 부분 이행은 잔여 항목을 분리하고 운영 예외를 연다`() {
        val remaining = fixture.executionTreatment(
            treatmentKey = "future-old:equipment-recovery:2",
            detailedCodes = listOf("CODE-B"),
        )
        val completed = fixture.executionTreatment(
            treatmentKey = "future-old",
            detailedCodes = listOf("CODE-A"),
        )

        handler().handle(
            envelope(
                eventId = "fulfillment-equipment-failure",
                facts = listOf(
                    TreatmentFulfillmentFact.resourceDisrupted(
                        treatmentKey = "future-old",
                        occurredAt = fixture.now,
                        completedTreatment = completed,
                        remainingTreatment = remaining,
                        reasonCode = "EQUIPMENT_FAILURE",
                    ),
                ),
            ),
            protectedEnvelope(),
        ).status shouldBeEqualTo PurchaseHandleStatus.CREATED

        transaction {
            revisionStatus(activeRevisionId(), "future-old:equipment-recovery:2") shouldBeEqualTo
                PlanTreatmentStatus.PENDING
            AppointmentOperationalExceptions.selectAll().single()
                .get(AppointmentOperationalExceptions.type) shouldBeEqualTo
                AppointmentOperationalExceptionType.RESOURCE_DISRUPTION
        }
    }

    @Test
    fun `환불은 직접 대상과 BLOCKING 후속만 취소하고 NON_BLOCKING 항목은 예약 가능하게 유지한다`() {
        handler().handle(
            envelope(
                eventId = "fulfillment-refund",
                facts = listOf(TreatmentFulfillmentFact.refunded("future-old", fixture.now)),
            ),
            protectedEnvelope(),
        ).status shouldBeEqualTo PurchaseHandleStatus.CREATED

        transaction {
            val activeRevisionId = activeRevisionId()
            revisionStatus(activeRevisionId, "future-old") shouldBeEqualTo PlanTreatmentStatus.CANCELLED
            revisionStatus(activeRevisionId, "blocked-next") shouldBeEqualTo PlanTreatmentStatus.CANCELLED
            revisionStatus(activeRevisionId, "independent") shouldBeEqualTo PlanTreatmentStatus.PENDING
            AppointmentPlans.selectAll().single()[AppointmentPlans.status] shouldBeEqualTo
                AppointmentPlanStatus.PARTIALLY_FULFILLED
        }
    }

    @Test
    fun `동일 fulfillment replay는 revision과 outbox를 중복 생성하지 않는다`() {
        val envelope = envelope(
            eventId = "fulfillment-replay",
            facts = listOf(TreatmentFulfillmentFact.completed("future-old", fixture.now)),
        )
        val handler = handler()

        handler.handle(envelope, protectedEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.CREATED
        handler.handle(envelope, protectedEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.DUPLICATE
        handler.handle(envelope.copy(eventId = "fulfillment-source-version-replay"), protectedEnvelope())
            .reasonCode shouldBeEqualTo "SOURCE_VERSION_REPLAY"

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 2L
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll().count() shouldBeEqualTo 2L
        }
    }

    @Test
    fun `잘못된 fulfillment fact는 활성 revision을 유지하고 암호화 원문과 감사 기록을 격리한다`() {
        val unknown = envelope(
            eventId = "fulfillment-invalid",
            facts = listOf(TreatmentFulfillmentFact.completed("unknown", fixture.now)),
        )

        handler().handle(unknown, protectedEnvelope()).reasonCode shouldBeEqualTo
            "FULFILLMENT_FACT_INVALID"

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingQuarantineEvents.selectAll().single()[SchedulingQuarantineEvents.reasonCode] shouldBeEqualTo
                "FULFILLMENT_FACT_INVALID"
            SchedulingQuarantineAuditEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `source version gap은 revision을 만들지 않고 WAITING_GAP으로 남긴다`() {
        val gap = envelope(
            eventId = "fulfillment-gap",
            facts = listOf(TreatmentFulfillmentFact.completed("future-old", fixture.now)),
            sourceAggregateVersion = 3L,
        )

        handler().handle(gap, protectedEnvelope()).status shouldBeEqualTo PurchaseHandleStatus.WAITING_GAP

        transaction {
            AppointmentPlanRevisions.selectAll().count() shouldBeEqualTo 1L
            SchedulingInboxEvents.selectAll().single()[SchedulingInboxEvents.status] shouldBeEqualTo
                SchedulingInboxStatus.WAITING_GAP
            SchedulingOutboxEvents.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun handler(): TreatmentFulfillmentHandler =
        TreatmentFulfillmentHandler(
            eventRepository = SchedulingEventRepository(),
            quarantineRepository = SchedulingQuarantineRepository(
                Clock.fixed(fixture.now, ZoneOffset.UTC),
            ),
            planRepository = AppointmentPlanRepository(),
            revisionRepository = AppointmentPlanRevisionRepository(),
            operationalExceptionRepository = AppointmentOperationalExceptionRepository(),
            dirtySetResolver = PlanDirtySetResolver(),
            versionVerifier = SourceAggregateVersionVerifier(
                Clock.fixed(fixture.now, ZoneOffset.UTC),
            ),
            clock = Clock.fixed(fixture.now, ZoneOffset.UTC),
        )

    private fun envelope(
        eventId: String,
        facts: List<TreatmentFulfillmentFact>,
        sourceAggregateVersion: Long = 1L,
    ): TrustedSchedulingEventEnvelope<TreatmentFulfillmentEvent> {
        val event = TreatmentFulfillmentEvent(
            sourceAggregateId = "clinical-visit-1",
            sourceAggregateVersion = sourceAggregateVersion,
            tenantGroupId = fixture.tenantGroupId,
            clinicId = fixture.clinicId,
            sourcePurchaseAuthority = "purchase-service",
            sourcePurchaseId = "purchase-100",
            facts = facts,
        )
        return TrustedSchedulingEventEnvelope(
            eventId = eventId,
            eventType = "TreatmentFulfillmentRecorded",
            occurredAt = fixture.now,
            receivedAt = fixture.now,
            producer = "clinical-service",
            issuer = "clinical-service",
            audience = "appointment-event",
            keyId = "clinical-key",
            algorithm = "EdDSA",
            schemaVersion = 1,
            correlationId = "correlation-fulfillment",
            payloadHash = TreatmentFulfillmentPayloadHasher.hash(event),
            payload = event,
        )
    }

    private fun activeRevisionId(): Long =
        AppointmentPlanRevisions.selectAll()
            .single { it[AppointmentPlanRevisions.active] }
            .get(AppointmentPlanRevisions.id)
            .value

    private fun revisionStatus(revisionId: Long, treatmentKey: String): PlanTreatmentStatus =
        PlanRevisionTreatments.selectAll().single {
            it[PlanRevisionTreatments.planRevisionId].value == revisionId &&
                it[PlanRevisionTreatments.treatmentKey] == treatmentKey
        }[PlanRevisionTreatments.status]

    private fun protectedEnvelope(): ProtectedQuarantineEnvelope =
        ProtectedQuarantineEnvelope(
            envelopeHash = "8".repeat(64),
            ciphertext = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            keyId = "quarantine-key",
        )
}

package io.bluetape4k.clinic.appointment.reliability

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityPolicySnapshot
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityResponsibility
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityRestrictionMode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTriggerType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityInput
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityPort
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityQuery
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityReadResult
import io.bluetape4k.clinic.appointment.service.reliability.BookingReliabilityEvaluator
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BookingReliabilityEvaluatorTest {

    private val memberId = MemberId("member-opaque-001")
    private val asOf = Instant.parse("2026-08-01T03:00:00Z")
    private val query = BookingEligibilityQuery(
        tenantGroupId = 10L,
        clinicId = 20L,
        memberId = memberId,
        asOf = asOf,
    )

    @Test
    fun `반복 no-show 기준을 넘으면 자동 당일 제안 제한 결과를 반환한다`() {
        val evaluator = evaluator(
            events = listOf(
                noShow(101L, asOf.minus(Duration.ofDays(5))),
                noShow(102L, asOf.minus(Duration.ofDays(3))),
            ),
            policy = policy(noShowThreshold = 2),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.RESTRICTED
        decision.noShowCount shouldBeEqualTo 2
        decision.lateCancellationCount shouldBeEqualTo 0
        decision.reasonCodes shouldBeEqualTo setOf(
            BookingReliabilityReasonCode.NO_SHOW_THRESHOLD_EXCEEDED,
            BookingReliabilityReasonCode.COOLING_OFF_ACTIVE,
        )
        decision.triggers.map { it.appointmentId } shouldBeEqualTo listOf(101L, 102L)
        decision.triggers.map { it.type }.toSet() shouldBeEqualTo setOf(BookingReliabilityTriggerType.NO_SHOW)
    }

    @Test
    fun `late cancellation 기준을 넘으면 staff approval 결과를 반환한다`() {
        val evaluator = evaluator(
            events = listOf(
                lateCancellation(
                    appointmentId = 201L,
                    scheduledStartAt = asOf.plus(Duration.ofMinutes(90)),
                    occurredAt = asOf,
                ),
            ),
            policy = policy(
                lateCancellationThreshold = 1,
                restrictionMode = BookingReliabilityRestrictionMode.REQUIRE_STAFF_APPROVAL,
            ),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.REQUIRES_STAFF_APPROVAL
        decision.noShowCount shouldBeEqualTo 0
        decision.lateCancellationCount shouldBeEqualTo 1
        decision.reasonCodes shouldBeEqualTo setOf(
            BookingReliabilityReasonCode.LATE_CANCELLATION_THRESHOLD_EXCEEDED,
            BookingReliabilityReasonCode.COOLING_OFF_ACTIVE,
        )
        decision.triggers.single().type shouldBeEqualTo BookingReliabilityTriggerType.LATE_CANCELLATION
    }

    @Test
    fun `환자 책임이 아닌 취소와 lookback 밖 사건은 제외한다`() {
        val evaluator = evaluator(
            events = listOf(
                noShow(301L, asOf.minus(Duration.ofDays(91))),
                lateCancellation(
                    appointmentId = 302L,
                    scheduledStartAt = asOf.plus(Duration.ofMinutes(30)),
                    occurredAt = asOf,
                    responsibility = BookingReliabilityResponsibility.CLINIC,
                ),
                lateCancellation(
                    appointmentId = 303L,
                    scheduledStartAt = asOf.plus(Duration.ofMinutes(30)),
                    occurredAt = asOf,
                    responsibility = BookingReliabilityResponsibility.OPERATIONAL_EXCEPTION,
                ),
            ),
            policy = policy(
                lookbackDays = 90,
                noShowThreshold = 1,
                lateCancellationThreshold = 1,
            ),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.ELIGIBLE
        decision.noShowCount shouldBeEqualTo 0
        decision.lateCancellationCount shouldBeEqualTo 0
        decision.triggers shouldHaveSize 0
        decision.reasonCodes shouldBeEqualTo setOf(BookingReliabilityReasonCode.NO_PATIENT_RESPONSIBLE_TRIGGER)
    }

    @Test
    fun `unknown responsibility is excluded and remains visible as an audit reason`() {
        val evaluator = evaluator(
            events = listOf(
                noShow(304L, asOf.minus(Duration.ofDays(1)), BookingReliabilityResponsibility.UNKNOWN),
            ),
            policy = policy(noShowThreshold = 1),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.ELIGIBLE
        decision.noShowCount shouldBeEqualTo 0
        decision.reasonCodes shouldBeEqualTo setOf(BookingReliabilityReasonCode.UNATTRIBUTED_EVENT_EXCLUDED)
    }

    @Test
    fun `활성 staff override는 제한 기준보다 우선한다`() {
        val evaluator = evaluator(
            events = listOf(
                noShow(401L, asOf.minus(Duration.ofDays(2))),
                noShow(402L, asOf.minus(Duration.ofDays(1))),
            ),
            overrides = listOf(
                BookingReliabilityOverrideRecord(
                    memberId = memberId,
                    verdict = BookingReliabilityVerdict.ELIGIBLE,
                    reasonCode = BookingReliabilityReasonCode.STAFF_OVERRIDE_ACTIVE,
                    effectiveFrom = asOf.minus(Duration.ofHours(1)),
                    expiresAt = asOf.plus(Duration.ofHours(1)),
                ),
            ),
            policy = policy(noShowThreshold = 1),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.OVERRIDDEN
        decision.reasonCodes shouldBeEqualTo setOf(BookingReliabilityReasonCode.MANUAL_OVERRIDE)
        decision.triggers shouldHaveSize 0
    }

    @Test
    fun `manual clear는 같은 threshold 이력도 eligible로 만들고 새 사건에서 다시 평가한다`() {
        val events = listOf(
            noShow(403L, asOf.minus(Duration.ofDays(1))),
        )
        val clear = BookingReliabilityOverrideRecord(
            memberId = memberId,
            verdict = BookingReliabilityVerdict.ELIGIBLE,
            reasonCode = BookingReliabilityReasonCode.MANUAL_CLEAR,
            effectiveFrom = asOf.minus(Duration.ofHours(1)),
            expiresAt = null,
            policyVersionId = 77L,
        )

        val cleared = evaluator(events = events, overrides = listOf(clear), policy = policy(noShowThreshold = 1))
            .evaluate(query)
        cleared.verdict shouldBeEqualTo BookingReliabilityVerdict.ELIGIBLE
        cleared.reasonCodes shouldBeEqualTo setOf(BookingReliabilityReasonCode.MANUAL_CLEAR)

        val afterNewEvent = evaluator(
            events = events + noShow(404L, asOf.minus(Duration.ofMinutes(10))),
            overrides = listOf(clear),
            policy = policy(noShowThreshold = 1),
        ).evaluate(query)
        afterNewEvent.verdict shouldBeEqualTo BookingReliabilityVerdict.RESTRICTED
    }

    @Test
    fun `만료된 override는 무시하고 정책 기준을 평가한다`() {
        val evaluator = evaluator(
            events = listOf(noShow(501L, asOf.minus(Duration.ofDays(1)))),
            overrides = listOf(
                BookingReliabilityOverrideRecord(
                    memberId = memberId,
                    verdict = BookingReliabilityVerdict.ELIGIBLE,
                    reasonCode = BookingReliabilityReasonCode.STAFF_OVERRIDE_ACTIVE,
                    effectiveFrom = asOf.minus(Duration.ofDays(3)),
                    expiresAt = asOf.minus(Duration.ofDays(1)),
                ),
            ),
            policy = policy(noShowThreshold = 1),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.RESTRICTED
        decision.reasonCodes shouldBeEqualTo setOf(
            BookingReliabilityReasonCode.NO_SHOW_THRESHOLD_EXCEEDED,
            BookingReliabilityReasonCode.COOLING_OFF_ACTIVE,
        )
    }

    @Test
    fun `정책이 비활성 상태이면 이력을 읽어도 제한하지 않는다`() {
        val evaluator = evaluator(
            events = listOf(noShow(601L, asOf.minus(Duration.ofDays(1)))),
            policy = policy(enabled = false, noShowThreshold = 1),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.POLICY_DISABLED
        decision.reasonCodes shouldBeEqualTo setOf(BookingReliabilityReasonCode.POLICY_DISABLED)
        decision.triggers shouldHaveSize 0
    }

    @Test
    fun `threshold별 DISABLE은 해당 count를 관찰하되 제한 기준에서 제외한다`() {
        val evaluator = evaluator(
            events = listOf(
                noShow(602L, asOf.minus(Duration.ofDays(1))),
                lateCancellation(603L, asOf.plus(Duration.ofMinutes(30)), asOf),
            ),
            policy = policy(
                noShowThreshold = 1,
                lateCancellationThreshold = 1,
                noShowThresholdEnabled = false,
            ),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.RESTRICTED
        decision.noShowCount shouldBeEqualTo 1
        decision.lateCancellationCount shouldBeEqualTo 1
        decision.reasonCodes shouldBeEqualTo setOf(
            BookingReliabilityReasonCode.POLICY_DISABLED,
            BookingReliabilityReasonCode.LATE_CANCELLATION_THRESHOLD_EXCEEDED,
            BookingReliabilityReasonCode.COOLING_OFF_ACTIVE,
        )
        decision.triggers.map { it.type }.toSet() shouldBeEqualTo
            setOf(BookingReliabilityTriggerType.LATE_CANCELLATION)
    }

    @Test
    fun `stale 또는 unavailable 포트 결과는 fail-closed outcome으로 보존한다`() {
        evaluator(BookingEligibilityReadResult.Stale).evaluate(query).verdict shouldBeEqualTo
            BookingReliabilityVerdict.STALE
        evaluator(BookingEligibilityReadResult.Unavailable).evaluate(query).verdict shouldBeEqualTo
            BookingReliabilityVerdict.UNAVAILABLE
    }

    @Test
    fun `decision digest는 입력 순서와 무관하게 결정적이다`() {
        val first = evaluator(
            events = listOf(
                noShow(702L, asOf.minus(Duration.ofDays(2))),
                noShow(701L, asOf.minus(Duration.ofDays(1))),
            ),
            policy = policy(noShowThreshold = 2),
        ).evaluate(query)
        val second = evaluator(
            events = listOf(
                noShow(701L, asOf.minus(Duration.ofDays(1))),
                noShow(702L, asOf.minus(Duration.ofDays(2))),
            ),
            policy = policy(noShowThreshold = 2),
        ).evaluate(query)

        first.decisionDigest shouldBeEqualTo second.decisionDigest
        first.decisionDigest.length shouldBeEqualTo 64
    }

    @Test
    fun `freshness guard용 policy snapshot id는 decision digest를 바꾸지 않는다`() {
        val evaluator = evaluator(
            events = listOf(noShow(703L, asOf.minus(Duration.ofDays(1)))),
            policy = policy(noShowThreshold = 1),
        )

        val initial = evaluator.evaluate(query)
        val guarded = evaluator.evaluate(query.copy(requestedPolicySnapshotId = 77L))

        guarded.decisionDigest shouldBeEqualTo initial.decisionDigest
        guarded.verdict shouldBeEqualTo initial.verdict
    }

    @Test
    fun `직전 제한 snapshot을 재사용하면 시각이 지나도 digest와 만료가 안정적이다`() {
        val events = listOf(noShow(704L, asOf.minus(Duration.ofHours(1))))
        val first = evaluator(events = events, policy = policy(noShowThreshold = 1)).evaluate(query)
        val later = evaluator(
            events = events,
            policy = policy(noShowThreshold = 1),
            previousDecision = first,
        ).evaluate(query.copy(asOf = asOf.plus(Duration.ofHours(1))))

        later.verdict shouldBeEqualTo BookingReliabilityVerdict.RESTRICTED
        later.decisionDigest shouldBeEqualTo first.decisionDigest
        later.effectiveFrom shouldBeEqualTo first.effectiveFrom
        later.expiresAt shouldBeEqualTo first.expiresAt
    }

    @Test
    fun `cooling-off가 만료되면 같은 이력으로 제한을 갱신하지 않는다`() {
        val events = listOf(noShow(705L, asOf.minus(Duration.ofHours(1))))
        val first = evaluator(events = events, policy = policy(noShowThreshold = 1)).evaluate(query)
        val expired = evaluator(
            events = events,
            policy = policy(noShowThreshold = 1),
            previousDecision = first,
        ).evaluate(query.copy(asOf = asOf.plus(Duration.ofHours(25))))

        expired.verdict shouldBeEqualTo BookingReliabilityVerdict.ELIGIBLE
        expired.reasonCodes shouldBeEqualTo setOf(
            BookingReliabilityReasonCode.NO_SHOW_THRESHOLD_EXCEEDED,
            BookingReliabilityReasonCode.COOLING_OFF_EXPIRED,
        )
        expired.expiresAt shouldBeEqualTo first.expiresAt
    }

    @Test
    fun `같은 event id는 가장 높은 source version 하나만 집계한다`() {
        val correctionId = "event-correction-703"
        val evaluator = evaluator(
            events = listOf(
                noShow(703L, asOf.minus(Duration.ofDays(1))).copy(
                    eventId = correctionId,
                    sourceVersion = 1L,
                ),
                noShow(703L, asOf.minus(Duration.ofDays(1))).copy(
                    eventId = correctionId,
                    sourceVersion = 2L,
                    responsibility = BookingReliabilityResponsibility.CLINIC,
                ),
            ),
            policy = policy(noShowThreshold = 1),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.ELIGIBLE
        decision.noShowCount shouldBeEqualTo 0
        decision.triggers shouldHaveSize 0
    }

    @Test
    fun `trigger 응답은 32개로 제한하고 추가 trigger cursor를 발급한다`() {
        val evaluator = evaluator(
            events = (1L..40L).map { appointmentId ->
                noShow(appointmentId, asOf.minus(Duration.ofHours(appointmentId)))
            },
            policy = policy(noShowThreshold = 1),
        )

        val decision = evaluator.evaluate(query)

        decision.verdict shouldBeEqualTo BookingReliabilityVerdict.RESTRICTED
        decision.noShowCount shouldBeEqualTo 40
        decision.triggers shouldHaveSize 32
        decision.hasAdditionalTriggers shouldBeEqualTo true
        decision.auditCursor?.startsWith("v1.") shouldBeEqualTo true
    }

    private fun evaluator(
        events: List<BookingReliabilityEventRecord>,
        overrides: List<BookingReliabilityOverrideRecord> = emptyList(),
        policy: BookingReliabilityPolicySnapshot = policy(),
        previousDecision: BookingReliabilityDecisionRecord? = null,
    ): BookingReliabilityEvaluator =
        evaluator(
            BookingEligibilityReadResult.Available(
                BookingEligibilityInput(
                    policy = policy,
                    events = events,
                    overrides = overrides,
                    previousDecision = previousDecision,
                ),
            ),
        )

    private fun evaluator(result: BookingEligibilityReadResult): BookingReliabilityEvaluator =
        BookingReliabilityEvaluator(
            object : BookingEligibilityPort {
                override fun loadBookingEligibility(query: BookingEligibilityQuery): BookingEligibilityReadResult =
                    result
            },
        )

    private fun policy(
        enabled: Boolean = true,
        lookbackDays: Int = 180,
        lateCancellationWindowMinutes: Int = 120,
        noShowThreshold: Int = 3,
        lateCancellationThreshold: Int = 3,
        noShowThresholdEnabled: Boolean = true,
        lateCancellationThresholdEnabled: Boolean = true,
        restrictionMode: BookingReliabilityRestrictionMode =
            BookingReliabilityRestrictionMode.EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS,
    ): BookingReliabilityPolicySnapshot =
        BookingReliabilityPolicySnapshot(
            policyVersionId = 77L,
            policyHash = "a".repeat(64),
            enabled = enabled,
            lookbackDays = lookbackDays,
            lateCancellationWindowMinutes = lateCancellationWindowMinutes,
            noShowThreshold = noShowThreshold,
            lateCancellationThreshold = lateCancellationThreshold,
            coolingOffHours = 24,
            restrictionMode = restrictionMode,
            noShowThresholdEnabled = noShowThresholdEnabled,
            lateCancellationThresholdEnabled = lateCancellationThresholdEnabled,
        )

    private fun noShow(
        appointmentId: Long,
        occurredAt: Instant,
        responsibility: BookingReliabilityResponsibility = BookingReliabilityResponsibility.PATIENT,
    ): BookingReliabilityEventRecord =
        BookingReliabilityEventRecord(
            appointmentId = appointmentId,
            memberId = memberId,
            eventType = BookingReliabilityEventType.NO_SHOW,
            responsibility = responsibility,
            scheduledStartAt = occurredAt,
            occurredAt = occurredAt,
        )

    private fun lateCancellation(
        appointmentId: Long,
        scheduledStartAt: Instant,
        occurredAt: Instant,
        responsibility: BookingReliabilityResponsibility = BookingReliabilityResponsibility.PATIENT,
    ): BookingReliabilityEventRecord =
        BookingReliabilityEventRecord(
            appointmentId = appointmentId,
            memberId = memberId,
            eventType = BookingReliabilityEventType.CANCELLED,
            responsibility = responsibility,
            scheduledStartAt = scheduledStartAt,
            occurredAt = occurredAt,
        )
}

package io.bluetape4k.clinic.appointment.service.reliability

import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityPolicySnapshot
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityResponsibility
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityRestrictionMode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTrigger
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTriggerType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * 예약 신뢰성 정책과 이력 snapshot으로 제안 적격성 결정을 계산합니다.
 *
 * 이 evaluator는 저장소에 접근하지 않습니다. [BookingEligibilityPort]가 제공한 bounded 입력만
 * 사용하고, 고객 이름·전화번호·자유 입력 사유를 요구하지 않습니다.
 */
class BookingReliabilityEvaluator(
    private val eligibilityPort: BookingEligibilityPort,
) {

    /**
     * 지정 회원의 예약 신뢰성 적격성을 평가합니다.
     */
    fun evaluate(query: BookingEligibilityQuery): BookingReliabilityDecisionRecord =
        when (val readResult = eligibilityPort.loadBookingEligibility(query)) {
            is BookingEligibilityReadResult.Available -> evaluateAvailable(query, readResult.input)
            BookingEligibilityReadResult.Stale -> terminalDecision(
                query = query,
                verdict = BookingReliabilityVerdict.STALE,
                reasonCode = BookingReliabilityReasonCode.POLICY_OR_HISTORY_STALE,
            )
            BookingEligibilityReadResult.Unavailable -> terminalDecision(
                query = query,
                verdict = BookingReliabilityVerdict.UNAVAILABLE,
                reasonCode = BookingReliabilityReasonCode.POLICY_OR_HISTORY_UNAVAILABLE,
            )
        }

    private fun evaluateAvailable(
        query: BookingEligibilityQuery,
        input: BookingEligibilityInput,
    ): BookingReliabilityDecisionRecord {
        val policy = input.policy
        if (query.requestedPolicySnapshotId != null &&
            query.requestedPolicySnapshotId != policy.policyVersionId
        ) {
            return terminalDecision(
                query = query,
                verdict = BookingReliabilityVerdict.STALE,
                reasonCode = BookingReliabilityReasonCode.POLICY_SNAPSHOT_STALE,
            )
        }
        val lookbackStart = query.asOf.minus(Duration.ofDays(policy.lookbackDays.toLong()))
        // 같은 opaque eventId의 정정본은 가장 높은 sourceVersion 하나만 관찰한다.
        // sourceVersion이 같으면 deterministic 입력 순서를 위해 appointmentId/type을 tie-breaker로 쓴다.
        val candidateEvents = input.events
            .groupBy { it.eventId }
            .values
            .map { versions ->
                versions.maxWith(
                    compareBy<BookingReliabilityEventRecord> { it.sourceVersion }
                        .thenBy { it.occurredAt }
                        .thenBy { it.appointmentId }
                        .thenBy { it.eventType.name },
                )
            }
            .asSequence()
            .filter { it.memberId == query.memberId }
            .filter { !it.occurredAt.isBefore(lookbackStart) && !it.occurredAt.isAfter(query.asOf) }
            .toList()

        val patientEvents = candidateEvents
            .filter { it.responsibility == BookingReliabilityResponsibility.PATIENT }
        val hasUnattributedEvents = candidateEvents.any {
            it.responsibility == BookingReliabilityResponsibility.UNKNOWN
        }

        val activeOverride = input.overrides
            .filter { it.memberId == query.memberId && it.isActiveAt(query.asOf) }
            .maxByOrNull { it.effectiveFrom }

        if (activeOverride != null) {
            val clearPolicyMatches =
                activeOverride.reasonCode != BookingReliabilityReasonCode.MANUAL_CLEAR ||
                    activeOverride.policyVersionId == null ||
                    activeOverride.policyVersionId == policy.policyVersionId
            val clearHasNewEvidence = activeOverride.reasonCode == BookingReliabilityReasonCode.MANUAL_CLEAR &&
                patientEvents.any { event ->
                    event.occurredAt.isAfter(activeOverride.effectiveFrom) &&
                        (event.eventType == BookingReliabilityEventType.NO_SHOW ||
                            event.isLateCancellation(policy, query.asOf))
                }
            if (activeOverride.reasonCode != BookingReliabilityReasonCode.MANUAL_CLEAR ||
                (clearPolicyMatches && !clearHasNewEvidence)
            ) {
                return overrideDecision(query, policy, activeOverride)
            }
        }

        if (!policy.enabled) {
            return policyDecision(
                query = query,
                policy = policy,
                verdict = BookingReliabilityVerdict.POLICY_DISABLED,
                reasonCodes = setOf(BookingReliabilityReasonCode.POLICY_DISABLED),
                triggers = emptyList(),
                noShowCount = 0,
                lateCancellationCount = 0,
                effectiveFrom = null,
                expiresAt = null,
            )
        }

        val noShowEvents = patientEvents
            .filter { it.eventType == BookingReliabilityEventType.NO_SHOW }
            .sortedBy { it.appointmentId }
        val lateCancellationEvents = patientEvents
            .filter { it.isLateCancellation(policy, query.asOf) }
            .sortedBy { it.appointmentId }

        val noShowTriggers = if (policy.noShowThresholdEnabled) {
            noShowEvents.map { BookingReliabilityTrigger(it.appointmentId, BookingReliabilityTriggerType.NO_SHOW) }
        } else {
            emptyList()
        }
        val lateCancellationTriggers = if (policy.lateCancellationThresholdEnabled) {
            lateCancellationEvents.map {
                BookingReliabilityTrigger(
                    appointmentId = it.appointmentId,
                    type = BookingReliabilityTriggerType.LATE_CANCELLATION,
                )
            }
        } else {
            emptyList()
        }

        val reasonCodes = buildSet {
            if (!policy.noShowThresholdEnabled || !policy.lateCancellationThresholdEnabled) {
                add(BookingReliabilityReasonCode.POLICY_DISABLED)
            }
            if (hasUnattributedEvents) {
                add(BookingReliabilityReasonCode.UNATTRIBUTED_EVENT_EXCLUDED)
            }
            if (policy.noShowThresholdEnabled && noShowEvents.size >= policy.noShowThreshold) {
                add(BookingReliabilityReasonCode.NO_SHOW_THRESHOLD_EXCEEDED)
            }
            if (policy.lateCancellationThresholdEnabled &&
                lateCancellationEvents.size >= policy.lateCancellationThreshold
            ) {
                add(BookingReliabilityReasonCode.LATE_CANCELLATION_THRESHOLD_EXCEEDED)
            }
        }

        val thresholdReasons = reasonCodes - setOf(
            BookingReliabilityReasonCode.POLICY_DISABLED,
            BookingReliabilityReasonCode.UNATTRIBUTED_EVENT_EXCLUDED,
        )
        if (thresholdReasons.isEmpty()) {
            val eligibleReasons = buildSet {
                addAll(reasonCodes)
                if (none { it == BookingReliabilityReasonCode.POLICY_DISABLED ||
                        it == BookingReliabilityReasonCode.UNATTRIBUTED_EVENT_EXCLUDED
                    }
                ) {
                    add(BookingReliabilityReasonCode.NO_PATIENT_RESPONSIBLE_TRIGGER)
                }
            }
            val verdict = if (!policy.noShowThresholdEnabled && !policy.lateCancellationThresholdEnabled) {
                BookingReliabilityVerdict.POLICY_DISABLED
            } else {
                BookingReliabilityVerdict.ELIGIBLE
            }
            return policyDecision(
                query = query,
                policy = policy,
                verdict = verdict,
                reasonCodes = eligibleReasons,
                triggers = emptyList(),
                noShowCount = noShowEvents.size,
                lateCancellationCount = lateCancellationEvents.size,
                effectiveFrom = null,
                expiresAt = null,
            )
        }

        val previousCooldownDecision = input.previousDecision?.takeIf { previous ->
            previous.memberId == query.memberId &&
                previous.policyVersionId == policy.policyVersionId &&
                previous.reasonCodes.any {
                    it == BookingReliabilityReasonCode.COOLING_OFF_ACTIVE ||
                        it == BookingReliabilityReasonCode.COOLING_OFF_EXPIRED
                } &&
                previous.effectiveFrom != null &&
                previous.expiresAt != null
        }
        val coolingOffActive = previousCooldownDecision?.expiresAt?.isAfter(query.asOf) == true
        val latestQualifyingEventAt = patientEvents
            .filter { event ->
                event.eventType == BookingReliabilityEventType.NO_SHOW ||
                    event.isLateCancellation(policy, query.asOf)
            }
            .maxOfOrNull { it.occurredAt }
        val newQualifyingEvidence = previousCooldownDecision?.effectiveFrom?.let { effectiveFrom ->
            latestQualifyingEventAt?.isAfter(effectiveFrom) == true
        } ?: true

        // 한 번 만료된 제한은 동일한 lookback 사건으로 자동 연장하지 않습니다.
        // 새 책임 사건 또는 정책 version 변경이 들어오면 새로운 제한을 발행합니다.
        if (previousCooldownDecision != null && !coolingOffActive && !newQualifyingEvidence) {
            return policyDecision(
                query = query,
                policy = policy,
                verdict = BookingReliabilityVerdict.ELIGIBLE,
                reasonCodes = reasonCodes + BookingReliabilityReasonCode.COOLING_OFF_EXPIRED,
                triggers = emptyList(),
                noShowCount = noShowEvents.size,
                lateCancellationCount = lateCancellationEvents.size,
                effectiveFrom = previousCooldownDecision.effectiveFrom,
                expiresAt = previousCooldownDecision.expiresAt,
            )
        }

        val verdict = when (policy.restrictionMode) {
            BookingReliabilityRestrictionMode.EXCLUDE_AUTOMATIC_SAME_DAY_OFFERS ->
                BookingReliabilityVerdict.RESTRICTED
            BookingReliabilityRestrictionMode.REQUIRE_STAFF_APPROVAL ->
                BookingReliabilityVerdict.REQUIRES_STAFF_APPROVAL
        }
        val triggers = (noShowTriggers + lateCancellationTriggers).sortedWith(
            compareBy<BookingReliabilityTrigger> { it.type.name }.thenBy { it.appointmentId },
        )

        return policyDecision(
            query = query,
            policy = policy,
            verdict = verdict,
            reasonCodes = reasonCodes + BookingReliabilityReasonCode.COOLING_OFF_ACTIVE,
            triggers = triggers,
            noShowCount = noShowEvents.size,
            lateCancellationCount = lateCancellationEvents.size,
            effectiveFrom = if (coolingOffActive) previousCooldownDecision.effectiveFrom else query.asOf,
            expiresAt = if (coolingOffActive) {
                previousCooldownDecision.expiresAt
            } else {
                query.asOf.plus(Duration.ofHours(policy.coolingOffHours.toLong()))
            },
        )
    }

    private fun BookingReliabilityEventRecord.isLateCancellation(
        policy: BookingReliabilityPolicySnapshot,
        asOf: Instant,
    ): Boolean {
        if (eventType != BookingReliabilityEventType.CANCELLED) {
            return false
        }
        if (occurredAt.isAfter(asOf)) {
            return false
        }
        val minutesBeforeStart = Duration.between(occurredAt, scheduledStartAt).toMinutes()
        return minutesBeforeStart in 0..policy.lateCancellationWindowMinutes.toLong()
    }

    private fun overrideDecision(
        query: BookingEligibilityQuery,
        policy: BookingReliabilityPolicySnapshot,
        override: BookingReliabilityOverrideRecord,
    ): BookingReliabilityDecisionRecord =
        policyDecision(
            query = query,
            policy = policy,
            verdict = if (override.reasonCode == BookingReliabilityReasonCode.MANUAL_CLEAR) {
                BookingReliabilityVerdict.ELIGIBLE
            } else {
                BookingReliabilityVerdict.OVERRIDDEN
            },
            reasonCodes = setOf(
                when (override.reasonCode) {
                    BookingReliabilityReasonCode.STAFF_OVERRIDE_ACTIVE ->
                        BookingReliabilityReasonCode.MANUAL_OVERRIDE
                    else -> override.reasonCode
                },
            ),
            triggers = emptyList(),
            noShowCount = 0,
            lateCancellationCount = 0,
            effectiveFrom = override.effectiveFrom,
            expiresAt = override.expiresAt,
        )

    private fun terminalDecision(
        query: BookingEligibilityQuery,
        verdict: BookingReliabilityVerdict,
        reasonCode: BookingReliabilityReasonCode,
    ): BookingReliabilityDecisionRecord =
        decision(
            query = query,
            policy = null,
            verdict = verdict,
            reasonCodes = setOf(reasonCode),
            triggers = emptyList(),
            noShowCount = 0,
            lateCancellationCount = 0,
            effectiveFrom = null,
            expiresAt = null,
        )

    private fun policyDecision(
        query: BookingEligibilityQuery,
        policy: BookingReliabilityPolicySnapshot,
        verdict: BookingReliabilityVerdict,
        reasonCodes: Set<BookingReliabilityReasonCode>,
        triggers: List<BookingReliabilityTrigger>,
        noShowCount: Int,
        lateCancellationCount: Int,
        effectiveFrom: Instant?,
        expiresAt: Instant?,
    ): BookingReliabilityDecisionRecord =
        decision(
            query = query,
            policy = policy,
            verdict = verdict,
            reasonCodes = reasonCodes,
            triggers = triggers,
            noShowCount = noShowCount,
            lateCancellationCount = lateCancellationCount,
            effectiveFrom = effectiveFrom,
            expiresAt = expiresAt,
        )

    private fun decision(
        query: BookingEligibilityQuery,
        policy: BookingReliabilityPolicySnapshot?,
        verdict: BookingReliabilityVerdict,
        reasonCodes: Set<BookingReliabilityReasonCode>,
        triggers: List<BookingReliabilityTrigger>,
        noShowCount: Int,
        lateCancellationCount: Int,
        effectiveFrom: Instant?,
        expiresAt: Instant?,
    ): BookingReliabilityDecisionRecord {
        val normalizedTriggers = triggers.sortedWith(
            compareBy<BookingReliabilityTrigger> { it.type.name }.thenBy { it.appointmentId },
        )
        val visibleTriggers = normalizedTriggers.take(MAX_VISIBLE_TRIGGERS)
        val hasAdditionalTriggers = normalizedTriggers.size > visibleTriggers.size
        val auditCursor = if (hasAdditionalTriggers) {
            opaqueAuditCursor(normalizedTriggers)
        } else {
            null
        }
        val normalizedReasons = reasonCodes.toSortedSet(compareBy { it.name })
        val digest = decisionDigest(
            query = query,
            policy = policy,
            verdict = verdict,
            reasonCodes = normalizedReasons,
            triggers = normalizedTriggers,
            noShowCount = noShowCount,
            lateCancellationCount = lateCancellationCount,
            effectiveFrom = effectiveFrom,
            expiresAt = expiresAt,
            hasAdditionalTriggers = hasAdditionalTriggers,
            auditCursor = auditCursor,
        )

        return BookingReliabilityDecisionRecord(
            tenantGroupId = query.tenantGroupId,
            clinicId = query.clinicId,
            memberId = query.memberId,
            policyVersionId = policy?.policyVersionId,
            policyHash = policy?.policyHash,
            evaluatedAt = query.asOf,
            verdict = verdict,
            reasonCodes = normalizedReasons,
            triggers = visibleTriggers,
            noShowCount = noShowCount,
            lateCancellationCount = lateCancellationCount,
            effectiveFrom = effectiveFrom,
            expiresAt = expiresAt,
            decisionDigest = digest,
            hasAdditionalTriggers = hasAdditionalTriggers,
            auditCursor = auditCursor,
        )
    }

    private fun decisionDigest(
        query: BookingEligibilityQuery,
        policy: BookingReliabilityPolicySnapshot?,
        verdict: BookingReliabilityVerdict,
        reasonCodes: Set<BookingReliabilityReasonCode>,
        triggers: List<BookingReliabilityTrigger>,
        noShowCount: Int,
        lateCancellationCount: Int,
        effectiveFrom: Instant?,
        expiresAt: Instant?,
        hasAdditionalTriggers: Boolean,
        auditCursor: String?,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .apply {
                updateField("tenantGroupId", query.tenantGroupId)
                updateField("clinicId", query.clinicId)
                updateField("memberId", query.memberId.value)
                updateField("policyVersionId", policy?.policyVersionId)
                updateField("policyHash", policy?.policyHash)
                updateField("verdict", verdict.name)
                updateField("noShowCount", noShowCount)
                updateField("lateCancellationCount", lateCancellationCount)
                updateField("effectiveFrom", effectiveFrom)
                updateField("expiresAt", expiresAt)
                updateField("hasAdditionalTriggers", hasAdditionalTriggers)
                updateField("auditCursor", auditCursor)
                updateField("reasonCount", reasonCodes.size)
                reasonCodes.forEachIndexed { index, reasonCode ->
                    updateField("reason[$index]", reasonCode.name)
                }
                updateField("triggerCount", triggers.size)
                triggers.forEachIndexed { index, trigger ->
                    updateField("trigger[$index].appointmentId", trigger.appointmentId)
                    updateField("trigger[$index].type", trigger.type.name)
                }
            }
            .digest()
            .joinToString("") { "%02x".format(it) }

    private fun MessageDigest.updateField(name: String, value: Any?) {
        val encoded = value?.toString() ?: "<null>"
        update(name.toByteArray(Charsets.UTF_8))
        update(0)
        update(encoded.length.toString().toByteArray(Charsets.UTF_8))
        update(0)
        update(encoded.toByteArray(Charsets.UTF_8))
        update(0)
    }

    private fun opaqueAuditCursor(triggers: List<BookingReliabilityTrigger>): String {
        val payload = triggers.joinToString(",") { "${it.type.name}:${it.appointmentId}" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        const val MAX_VISIBLE_TRIGGERS = 32
    }
}

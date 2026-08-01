package io.bluetape4k.clinic.appointment.api.reliability

import io.bluetape4k.clinic.appointment.api.policy.EffectivePolicyReadUnavailableException
import io.bluetape4k.clinic.appointment.api.policy.EffectiveSchedulingPolicyService
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.policy.ActorRole
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAction
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAuditRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityPolicySnapshot
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityIdempotencyConflictException
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityRepository
import io.bluetape4k.clinic.appointment.repository.BookingReliabilityStaleDecisionException
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityPort
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityInput
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityQuery
import io.bluetape4k.clinic.appointment.service.reliability.BookingEligibilityReadResult
import io.bluetape4k.clinic.appointment.service.reliability.BookingReliabilityEvaluator
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * effective scheduling policy와 reliability 원장을 연결하는 실제 application port입니다.
 *
 * 정책 snapshot 조회와 원장 read는 분리된 권위 경계에서 수행합니다. evaluator와 원장
 * 저장은 호출자가 소유한 transaction을 재사용해 고정한 bounded 입력만 소비합니다.
 * member profile/연락처 조회는 의도적으로 수행하지 않습니다.
 */
class DefaultBookingReliabilityApplicationAdapter(
    private val effectivePolicyService: EffectiveSchedulingPolicyService,
    private val repository: BookingReliabilityRepository,
    private val properties: BookingReliabilityProperties,
    private val metrics: BookingReliabilityMetrics? = null,
    private val clock: Clock = Clock.systemUTC(),
) : BookingReliabilityApplicationPort {

    override fun evaluate(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        at: Instant,
        requestedPolicySnapshotId: Long?,
    ): BookingReliabilityDecisionRecord {
        val startedAtNanos = System.nanoTime()
        val query = BookingEligibilityQuery(
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            memberId = memberId,
            asOf = at,
            requestedPolicySnapshotId = requestedPolicySnapshotId,
        )
        return try {
            val effective = effectivePolicyService.getEffective(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                decisionAt = at,
                serviceAt = at,
            )
            val policy = effective.toReliabilitySnapshot()
                ?: return unavailable(query).also { recordMetric(it, startedAtNanos) }
            inCallerTransaction {
                val events = repository.findEvents(
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                    memberId = memberId,
                    fromInclusive = at.minus(Duration.ofDays(policy.lookbackDays.toLong())),
                    untilInclusive = at,
                    limit = properties.maxHistoryRows,
                )
                val activeOverride = repository.findLatestActiveOverride(
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                    memberId = memberId,
                    at = at,
                )
                val previousDecision = if (requestedPolicySnapshotId != null) {
                    repository.findLatestDecisionForUpdate(
                        tenantGroupId = tenantGroupId,
                        clinicId = clinicId,
                        memberId = memberId,
                    )
                } else {
                    repository.findLatestDecision(
                        tenantGroupId = tenantGroupId,
                        clinicId = clinicId,
                        memberId = memberId,
                    )
                }
                val evaluator = BookingReliabilityEvaluator(
                    object : BookingEligibilityPort {
                        override fun loadBookingEligibility(query: BookingEligibilityQuery): BookingEligibilityReadResult =
                            BookingEligibilityReadResult.Available(
                            BookingEligibilityInput(
                                policy = policy,
                                events = events,
                                overrides = listOfNotNull(activeOverride),
                                previousDecision = previousDecision,
                            ),
                            )
                    },
                )
                val decision = evaluator.evaluate(query)
                repository.saveDecision(
                    record = decision,
                    actorRef = ACTOR_REF,
                    correlationId = CORRELATION_ID,
                ).also { recordMetric(it, startedAtNanos) }
            }
        } catch (_: EffectivePolicyReadUnavailableException) {
            unavailable(query).also { recordMetric(it, startedAtNanos) }
        } catch (_: RuntimeException) {
            // Policy/decision storage 장애는 관대한 기본값이 아니라 명시적 unavailable로 닫습니다.
            unavailable(query).also { recordMetric(it, startedAtNanos) }
        }
    }

    private fun recordMetric(decision: BookingReliabilityDecisionRecord, startedAtNanos: Long) {
        metrics?.recordDecision(
            mode = properties.mode,
            verdict = decision.verdict,
            reasonCodes = decision.reasonCodes,
            duration = Duration.ofNanos((System.nanoTime() - startedAtNanos).coerceAtLeast(0L)),
        )
    }

    /** command service가 이미 소유한 transaction을 재사용하고 독립 호출만 새로 엽니다. */
    private fun <T> inCallerTransaction(block: () -> T): T {
        val current = runCatching { TransactionManager.current() }.getOrNull()
        return if (current != null) block() else transaction { block() }
    }

    override fun override(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        command: BookingReliabilityOverrideCommand,
    ): BookingReliabilityDecisionRecord {
        requireAuthorizedActor(command.actor)
        val commandHash = commandHash(
            action = BookingReliabilityOverrideAction.OVERRIDE,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            memberId = memberId,
            idempotencyKey = command.idempotencyKey,
            decisionId = command.expectedDecisionId,
            evaluationDigest = command.expectedEvaluationDigest,
            verdict = command.verdict,
            reasonCode = command.reasonCode,
            effectiveFrom = command.effectiveFrom,
            expiresAt = command.expiresAt,
        )
        val result = try {
            inCallerTransaction {
                val policyVersionId = repository.findLatestDecisionForUpdate(
                    tenantGroupId,
                    clinicId,
                    memberId,
                )?.policyVersionId
                repository.appendOverride(
                    BookingReliabilityOverrideAuditRecord(
                        tenantGroupId = tenantGroupId,
                        clinicId = clinicId,
                        memberId = memberId,
                        action = BookingReliabilityOverrideAction.OVERRIDE,
                        verdict = command.verdict,
                        reasonCode = command.reasonCode,
                        policyVersionId = policyVersionId,
                        effectiveFrom = command.effectiveFrom,
                        expiresAt = command.expiresAt,
                        actorId = command.actor.actorId,
                        actorType = command.actor.actorType.name,
                        idempotencyKeyHash = sha256(command.idempotencyKey),
                        commandHash = commandHash,
                        resultDigest = sha256("result|$commandHash"),
                        expectedVersion = command.expectedDecisionId ?: 0L,
                        decisionId = command.expectedDecisionId,
                        previousDecisionDigest = command.expectedEvaluationDigest,
                        correlationId = command.actor.correlationId,
                    ),
                )
                evaluate(
                    tenantGroupId = tenantGroupId,
                    clinicId = clinicId,
                    memberId = memberId,
                    at = maxOf(clock.instant(), command.effectiveFrom),
                )
            }
        } catch (error: BookingReliabilityStaleDecisionException) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE, error)
        } catch (error: BookingReliabilityIdempotencyConflictException) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE, error)
        }
        return result
    }

    override fun clear(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        command: BookingReliabilityClearCommand,
    ): BookingReliabilityDecisionRecord {
        requireAuthorizedActor(command.actor)
        val effectiveFrom = clock.instant()
        val commandHash = commandHash(
            action = BookingReliabilityOverrideAction.CLEAR,
            tenantGroupId = tenantGroupId,
            clinicId = clinicId,
            memberId = memberId,
            idempotencyKey = command.idempotencyKey,
            decisionId = command.expectedDecisionId,
            evaluationDigest = command.expectedEvaluationDigest,
            verdict = null,
            reasonCode = command.reasonCode,
            effectiveFrom = effectiveFrom,
            expiresAt = null,
        )
        val result = try {
            inCallerTransaction {
                val policyVersionId = repository.findLatestDecisionForUpdate(
                    tenantGroupId,
                    clinicId,
                    memberId,
                )?.policyVersionId
                repository.appendOverride(
                    BookingReliabilityOverrideAuditRecord(
                        tenantGroupId = tenantGroupId,
                        clinicId = clinicId,
                        memberId = memberId,
                        action = BookingReliabilityOverrideAction.CLEAR,
                        verdict = null,
                        reasonCode = command.reasonCode,
                        policyVersionId = policyVersionId,
                        effectiveFrom = effectiveFrom,
                        expiresAt = null,
                        actorId = command.actor.actorId,
                        actorType = command.actor.actorType.name,
                        idempotencyKeyHash = sha256(command.idempotencyKey),
                        commandHash = commandHash,
                        resultDigest = sha256("result|$commandHash"),
                        expectedVersion = command.expectedDecisionId ?: 0L,
                        decisionId = command.expectedDecisionId,
                        previousDecisionDigest = command.expectedEvaluationDigest,
                        correlationId = command.actor.correlationId,
                    ),
                )
                evaluate(tenantGroupId, clinicId, memberId, effectiveFrom)
            }
        } catch (error: BookingReliabilityStaleDecisionException) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE, error)
        } catch (error: BookingReliabilityIdempotencyConflictException) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_DECISION_STALE, error)
        }
        return result
    }

    override fun audit(
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        cursor: String?,
        limit: Int,
    ): BookingReliabilityAuditPage {
        require(limit in 1..properties.maxAuditPageSize) { "limit is outside the bounded audit page size" }
        return inCallerTransaction {
            val all = repository.findOverrideAudit(
                tenantGroupId = tenantGroupId,
                clinicId = clinicId,
                memberId = memberId,
                limit = BookingReliabilityRepository.DEFAULT_AUDIT_LIMIT,
            )
            val offset = cursor?.let { requested ->
                val index = all.indexOfFirst { cursorFor(tenantGroupId, clinicId, memberId, it.auditId) == requested }
                if (index < 0) throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_PAYLOAD_INVALID)
                index + 1
            } ?: 0
            val page = all.drop(offset).take(limit)
            val next = all.drop(offset + page.size).firstOrNull()?.let {
                cursorFor(tenantGroupId, clinicId, memberId, it.auditId)
            }
            BookingReliabilityAuditPage(
                entries = page.map { audit ->
                    BookingReliabilityAuditEntry(
                        decisionId = audit.decisionId,
                        evaluatedAt = audit.effectiveFrom,
                        verdict = audit.verdict ?: BookingReliabilityVerdict.ELIGIBLE,
                        reasonCodes = setOf(audit.reasonCode),
                        evaluationDigest = audit.resultDigest,
                        actorRef = "${audit.actorType}:${audit.actorId}",
                    )
                },
                nextCursor = next,
            )
        }
    }

    private fun unavailable(query: BookingEligibilityQuery): BookingReliabilityDecisionRecord =
        BookingReliabilityEvaluator(object : BookingEligibilityPort {
            override fun loadBookingEligibility(query: BookingEligibilityQuery): BookingEligibilityReadResult =
                BookingEligibilityReadResult.Unavailable
        })
            .evaluate(query)

    private fun EffectiveSchedulingPolicy.toReliabilitySnapshot(): BookingReliabilityPolicySnapshot? {
        val policy = payload.priorityAndReliability ?: return null
        val source = sourceVersions[SchedulingPolicyKind.PRIORITY_AND_RELIABILITY] ?: return null
        val version = source.clinicVersion ?: source.tenantVersion
        if (version <= 0L) return null
        return BookingReliabilityPolicySnapshot(
            policyVersionId = version,
            policyHash = snapshotHash,
            enabled = policy.thresholdsPresent,
            lookbackDays = policy.lookbackDays,
            lateCancellationWindowMinutes = policy.lateCancellationWindowMinutes,
            noShowThreshold = policy.noShowThreshold,
            lateCancellationThreshold = policy.lateCancellationThreshold,
            coolingOffHours = policy.coolingOffHours,
            restrictionMode = properties.restrictionMode.toDomain(),
            noShowThresholdEnabled =
                policy.thresholdsPresent &&
                    "priorityAndReliability.noShowThreshold" !in disabledFeatures,
            lateCancellationThresholdEnabled =
                policy.thresholdsPresent &&
                    "priorityAndReliability.lateCancellationThreshold" !in disabledFeatures,
        )
    }

    private fun requireAuthorizedActor(actor: ActorContext) {
        if (actor.roles.none { it in AUTHORIZED_ROLES }) {
            throw BookingReliabilityApiException(BookingReliabilityApiError.BOOKING_RELIABILITY_FORBIDDEN)
        }
    }

    private fun commandHash(
        action: BookingReliabilityOverrideAction,
        tenantGroupId: Long,
        clinicId: Long,
        memberId: MemberId,
        idempotencyKey: String,
        decisionId: Long?,
        evaluationDigest: String,
        verdict: BookingReliabilityVerdict?,
        reasonCode: BookingReliabilityReasonCode,
        effectiveFrom: Instant,
        expiresAt: Instant?,
    ): String = sha256(
        listOf(
            action.name, tenantGroupId, clinicId, memberId.value, idempotencyKey,
            decisionId, evaluationDigest, verdict?.name, reasonCode.name, effectiveFrom, expiresAt,
        ).joinToString("|"),
    )

    private fun cursorFor(tenantGroupId: Long, clinicId: Long, memberId: MemberId, auditId: Long?): String =
        "v1.${sha256("$tenantGroupId|$clinicId|${memberId.value}|${auditId ?: "none"}")}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ACTOR_REF = "booking-reliability-api"
        const val CORRELATION_ID = "booking-reliability-api"
        val AUTHORIZED_ROLES = setOf(ActorRole.ADMIN, ActorRole.STAFF, ActorRole.DOCTOR, ActorRole.SYSTEM)
    }
}

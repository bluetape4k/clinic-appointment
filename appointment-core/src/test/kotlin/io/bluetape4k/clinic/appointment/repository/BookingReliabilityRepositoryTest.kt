package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityDecisionRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventSource
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityEventType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAction
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityOverrideAuditRecord
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityReasonCode
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityResponsibility
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTrigger
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityTriggerType
import io.bluetape4k.clinic.appointment.model.reliability.BookingReliabilityVerdict
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityDecisions
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityEvents
import io.bluetape4k.clinic.appointment.model.tables.BookingReliabilityOverrides
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant

/**
 * 예약 신뢰성 append-only ledger와 결정 snapshot의 방언별 저장 계약을 검증합니다.
 */
class BookingReliabilityRepositoryTest : AbstractExposedTest() {

    @ParameterizedTest
    @MethodSource(ENABLE_DIALECTS_METHOD)
    fun `사건 결정 override 감사 ledger를 idempotent하게 저장한다`(testDB: TestDB) {
        withTables(testDB, BookingReliabilityEvents, BookingReliabilityDecisions, BookingReliabilityOverrides) {
            val repository = BookingReliabilityRepository()
            val memberId = MemberId("member-176")
            val baseTime = Instant.parse("2026-08-01T00:00:00Z")
            val event = event(memberId, sourceVersion = 1L, occurredAt = baseTime.plusSeconds(60))
            val corrected =
                event(
                    memberId,
                    sourceVersion = 2L,
                    occurredAt = baseTime.plusSeconds(90),
                    responsibility = BookingReliabilityResponsibility.DATA_CORRECTION,
                )

            repository.recordEvent(TENANT_GROUP_ID, CLINIC_ID, event)
            repository.recordEvent(TENANT_GROUP_ID, CLINIC_ID, event)
            repository.recordEvent(TENANT_GROUP_ID, CLINIC_ID, corrected)

            repository
                .findEvents(TENANT_GROUP_ID, CLINIC_ID, memberId, baseTime, baseTime.plusSeconds(120))
                .map { it.sourceVersion } shouldBeEqualTo listOf(1L, 2L)

            val decision =
                decision(
                    memberId = memberId,
                    evaluatedAt = baseTime.plusSeconds(120),
                    digest = DIGEST_A,
                )
            val saved = repository.saveDecision(decision)
            val replay = repository.saveDecision(decision)

            replay.decisionId shouldBeEqualTo saved.decisionId
            repository
                .findDecisionByDigest(TENANT_GROUP_ID, CLINIC_ID, memberId, DIGEST_A)
                .shouldNotBeNull()
                .triggers shouldBeEqualTo decision.triggers
            repository.findLatestDecision(TENANT_GROUP_ID, CLINIC_ID, memberId)
                .shouldNotBeNull()
                .decisionDigest shouldBeEqualTo DIGEST_A

            val override =
                overrideAudit(
                    memberId = memberId,
                    action = BookingReliabilityOverrideAction.OVERRIDE,
                    verdict = BookingReliabilityVerdict.OVERRIDDEN,
                    effectiveFrom = baseTime.plusSeconds(180),
                    keyHash = DIGEST_B,
                    commandHash = DIGEST_C,
                    resultDigest = DIGEST_D,
                    decisionId = saved.decisionId,
                    previousDigest = saved.decisionDigest,
                    reasonCode = BookingReliabilityReasonCode.MANUAL_OVERRIDE,
                )
            val savedOverride = repository.appendOverride(override)
            val overrideReplay = repository.appendOverride(override)

            overrideReplay.auditId shouldBeEqualTo savedOverride.auditId
            repository
                .findLatestActiveOverride(TENANT_GROUP_ID, CLINIC_ID, memberId, baseTime.plusSeconds(181))
                .shouldNotBeNull()
                .verdict shouldBeEqualTo BookingReliabilityVerdict.OVERRIDDEN

            repository.appendOverride(
                overrideAudit(
                    memberId = memberId,
                    action = BookingReliabilityOverrideAction.CLEAR,
                    verdict = null,
                    effectiveFrom = baseTime.plusSeconds(240),
                    keyHash = DIGEST_E,
                    commandHash = DIGEST_F,
                    resultDigest = DIGEST_G,
                    decisionId = saved.decisionId,
                    previousDigest = saved.decisionDigest,
                    reasonCode = BookingReliabilityReasonCode.MANUAL_CLEAR,
                ),
            )

            repository.findLatestActiveOverride(TENANT_GROUP_ID, CLINIC_ID, memberId, baseTime.plusSeconds(241))
                .shouldNotBeNull()
                .reasonCode shouldBeEqualTo BookingReliabilityReasonCode.MANUAL_CLEAR
            repository.findOverrideAudit(TENANT_GROUP_ID, CLINIC_ID, memberId) shouldHaveSize 2
        }
    }

    private fun event(
        memberId: MemberId,
        sourceVersion: Long,
        occurredAt: Instant,
        responsibility: BookingReliabilityResponsibility = BookingReliabilityResponsibility.PATIENT,
    ): BookingReliabilityEventRecord =
        BookingReliabilityEventRecord(
            appointmentId = 101L,
            memberId = memberId,
            eventType = BookingReliabilityEventType.NO_SHOW,
            responsibility = responsibility,
            scheduledStartAt = Instant.parse("2026-08-01T01:00:00Z"),
            occurredAt = occurredAt,
            eventId = "appointment-101",
            sourceVersion = sourceVersion,
            source = BookingReliabilityEventSource.APPOINTMENT,
        )

    private fun decision(
        memberId: MemberId,
        evaluatedAt: Instant,
        digest: String,
    ): BookingReliabilityDecisionRecord =
        BookingReliabilityDecisionRecord(
            tenantGroupId = TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            memberId = memberId,
            policyVersionId = 7L,
            policyHash = DIGEST_POLICY,
            evaluatedAt = evaluatedAt,
            verdict = BookingReliabilityVerdict.RESTRICTED,
            reasonCodes =
                setOf(
                    BookingReliabilityReasonCode.NO_SHOW_THRESHOLD_REACHED,
                    BookingReliabilityReasonCode.COOLING_OFF_ACTIVE,
                ),
            triggers = listOf(BookingReliabilityTrigger(101L, BookingReliabilityTriggerType.NO_SHOW)),
            noShowCount = 1,
            lateCancellationCount = 0,
            effectiveFrom = evaluatedAt,
            expiresAt = evaluatedAt.plusSeconds(3600),
            decisionDigest = digest,
        )

    private fun overrideAudit(
        memberId: MemberId,
        action: BookingReliabilityOverrideAction,
        verdict: BookingReliabilityVerdict?,
        effectiveFrom: Instant,
        keyHash: String,
        commandHash: String,
        resultDigest: String,
        decisionId: Long?,
        previousDigest: String?,
        reasonCode: BookingReliabilityReasonCode,
    ): BookingReliabilityOverrideAuditRecord =
        BookingReliabilityOverrideAuditRecord(
            tenantGroupId = TENANT_GROUP_ID,
            clinicId = CLINIC_ID,
            memberId = memberId,
            action = action,
            verdict = verdict,
            reasonCode = reasonCode,
            effectiveFrom = effectiveFrom,
            expiresAt = null,
            actorId = "staff-1",
            actorType = "STAFF",
            idempotencyKeyHash = keyHash,
            commandHash = commandHash,
            resultDigest = resultDigest,
            expectedVersion = 0L,
            decisionId = decisionId,
            previousDecisionDigest = previousDigest,
            correlationId = "corr-176",
        )

    companion object {
        private const val TENANT_GROUP_ID = 1L
        private const val CLINIC_ID = 10L
        private const val DIGEST_POLICY = "0000000000000000000000000000000000000000000000000000000000000001"
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val DIGEST_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private const val DIGEST_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        private const val DIGEST_F = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        private const val DIGEST_G = "9999999999999999999999999999999999999999999999999999999999999999"
    }
}

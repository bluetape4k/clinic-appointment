package io.bluetape4k.clinic.appointment.event.integration

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * durable inbound scheduling event 하나를 표현하는 read model이다.
 *
 * @property id 데이터베이스 identity.
 * @property eventId deduplication에 사용하는 안정적인 producer event identity.
 * @property sourceAggregateVersion 양수 producer aggregate version.
 * @property status 현재 convergence lifecycle.
 * @property attemptCount 길이가 제한된 gap/replay attempt 횟수.
 * @property failureCode 정제된 안정 reason code. 없으면 `null`.
 * @property replayAfter 가장 이른 UTC replay instant. 대기 중이 아니면 `null`.
 */
data class SchedulingInboxRecord(
    val id: Long,
    val eventId: String,
    val sourceAggregateVersion: Long,
    val status: SchedulingInboxStatus,
    val attemptCount: Int,
    val failureCode: String?,
    val replayAfter: Instant?,
)

/**
 * 데이터베이스가 계산한 V9 outbox dual-write convergence evidence이다.
 *
 * @property aggregateIdentityMissingCount `aggregate_type` 또는 `aggregate_id`가 없는 row 수.
 * 정책 publication과 V10 cutover를 위해서는 0이어야 한다.
 * @property legacyPlanRowCount 아직 legacy plan foreign key를 가진 row 수. 이런 row는
 * equivalent generic plan identity도 함께 가져야 한다.
 * @property legacyPlanMismatchCount generic type이 `APPOINTMENT_PLAN`이 아니거나 generic ID가
 * decimal plan ID와 다른 legacy plan row 수.
 * @property dualWriteParityGauge generic identity가 일치하는 legacy plan row 비율. 범위는
 * `0.0..1.0`이며 legacy row가 없으면 `1.0`을 보고한다.
 */
data class OutboxDualWriteConvergence(
    val aggregateIdentityMissingCount: Long,
    val legacyPlanRowCount: Long,
    val legacyPlanMismatchCount: Long,
) {
    /** 현재 모든 row가 V9 writer contract를 만족할 때만 true. */
    val converged: Boolean
        get() = aggregateIdentityMissingCount == 0L && legacyPlanMismatchCount == 0L

    /** operator gauge로 노출되는 legacy plan identity parity. 범위는 `0.0..1.0`. */
    val dualWriteParityGauge: Double
        get() = if (legacyPlanRowCount == 0L) {
            1.0
        } else {
            (legacyPlanRowCount - legacyPlanMismatchCount).toDouble() / legacyPlanRowCount
        }
}

/**
 * redacted inbox/outbox convergence state를 다루는 caller-transaction repository이다.
 *
 * 모든 메서드는 caller가 소유한 Exposed `transaction {}` 안에서 실행되어야 한다. 그래야 inbox
 * state, appointment-plan creation, outbox publication evidence가 원자적으로 commit되거나
 * rollback된다.
 */
class SchedulingEventRepository {

    /**
     * 현재 transaction에서 보이는 inbox record 하나를 반환한다.
     *
     * @param eventId 안정적이고 길이가 제한된 producer event ID.
     * @return record. 보이는 row가 없으면 `null`.
     */
    fun findInbox(eventId: String): SchedulingInboxRecord? =
        SchedulingInboxEvents
            .selectAll()
            .where { SchedulingInboxEvents.eventId eq eventId }
            .singleOrNull()
            ?.let { row ->
                SchedulingInboxRecord(
                    id = row[SchedulingInboxEvents.id].value,
                    eventId = row[SchedulingInboxEvents.eventId],
                    sourceAggregateVersion = row[SchedulingInboxEvents.sourceAggregateVersion],
                    status = row[SchedulingInboxEvents.status],
                    attemptCount = row[SchedulingInboxEvents.attemptCount],
                    failureCode = row[SchedulingInboxEvents.failureCode],
                    replayAfter = row[SchedulingInboxEvents.replayAfter],
                )
            }

    /**
     * 정확한 producer aggregate 하나에 대해 마지막으로 처리된 version을 반환한다.
     *
     * tenant, clinic, producer, authority, source ID가 isolation boundary를 이룬다.
     * 다른 boundary의 version이 이 event 처리를 억제해서는 안 된다.
     *
     * @return 처리된 가장 큰 양수 version. 아직 convergence 전이면 `null`.
     */
    fun latestProcessedSourceVersion(
        tenantGroupId: Long,
        clinicId: Long,
        producer: String,
        sourceAuthority: String,
        sourceAggregateId: String,
    ): Long? =
        SchedulingInboxEvents
            .selectAll()
            .where {
                (SchedulingInboxEvents.tenantGroupId eq tenantGroupId) and
                    (SchedulingInboxEvents.clinicId eq clinicId) and
                    (SchedulingInboxEvents.producer eq producer) and
                    (SchedulingInboxEvents.sourceAuthority eq sourceAuthority) and
                    (SchedulingInboxEvents.sourceAggregateId eq sourceAggregateId) and
                    (SchedulingInboxEvents.status eq SchedulingInboxStatus.PROCESSED)
            }
            .orderBy(SchedulingInboxEvents.sourceAggregateVersion, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(SchedulingInboxEvents.sourceAggregateVersion)

    /**
     * trusted envelope에서 redacted `RECEIVED` inbox row를 삽입한다.
     *
     * 검증된 metadata와 payload hash만 영속화한다. patient reference token과 signature는
     * inbox column을 갖지 않는다.
     *
     * @return 생성된 데이터베이스 identity.
     */
    fun insertReceived(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
    ): Long =
        SchedulingInboxEvents.insertAndGetId {
            it[eventId] = envelope.eventId
            it[eventType] = envelope.eventType
            it[producer] = envelope.producer
            it[sourceAuthority] = envelope.payload.sourcePurchaseAuthority
            it[sourceAggregateId] = envelope.payload.sourceAggregateId
            it[sourceAggregateVersion] = envelope.payload.sourceAggregateVersion
            it[tenantGroupId] = envelope.payload.tenantGroupId
            it[clinicId] = envelope.payload.clinicId
            it[payloadHash] = envelope.payloadHash
            it[status] = SchedulingInboxStatus.RECEIVED
            it[attemptCount] = 0
            it[occurredAt] = envelope.occurredAt
            it[receivedAt] = envelope.receivedAt
        }.value

    /**
     * inbox row 하나를 주어진 UTC instant에 processed로 표시한다.
     *
     * [reasonCode]는 선택적인 정제된 convergence code이며, 원본 exception text 또는
     * payload data가 아니어야 한다.
     */
    fun markProcessed(
        inboxId: Long,
        processedAt: Instant,
        reasonCode: String? = null,
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.PROCESSED
            it[failureCode] = reasonCode
            it[SchedulingInboxEvents.processedAt] = processedAt
            it[replayAfter] = null
        }
    }

    /**
     * 길이가 제한된 source-version gap retry를 기록한다.
     *
     * [attemptCount]는 전체 attempt 번호이고 [replayAfter]는 domain backoff contract가
     * 결정한 가장 이른 UTC retry instant이다.
     */
    fun markWaitingGap(
        inboxId: Long,
        attemptCount: Int,
        replayAfter: Instant,
        reasonCode: String = "SOURCE_VERSION_GAP",
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.WAITING_GAP
            it[SchedulingInboxEvents.attemptCount] = attemptCount
            it[SchedulingInboxEvents.replayAfter] = replayAfter
            it[failureCode] = reasonCode
        }
    }

    /**
     * trusted inbox row 하나를 안정적인 reason code와 함께 terminal quarantine 상태로 만든다.
     *
     * [processedAt]은 UTC terminal instant이다. [attemptCount]는 caller가 최종 bounded retry
     * count를 제공한 경우에만 교체한다.
     */
    fun markQuarantined(
        inboxId: Long,
        reasonCode: String,
        processedAt: Instant,
        attemptCount: Int? = null,
    ) {
        SchedulingInboxEvents.update({ SchedulingInboxEvents.id eq inboxId }) {
            it[status] = SchedulingInboxStatus.QUARANTINED
            it[failureCode] = reasonCode
            it[SchedulingInboxEvents.processedAt] = processedAt
            it[replayAfter] = null
            attemptCount?.let { count -> it[SchedulingInboxEvents.attemptCount] = count }
        }
    }

    /**
     * redacted `AppointmentPlanCreated` event 하나를 추가한다.
     *
     * deterministic event ID는 trusted inbound event와 새로 생성된 양수 [planId]를 묶는다.
     * 모든 consumer가 migration을 끝낼 때까지 이 row는 legacy plan foreign key와 generic
     * `APPOINTMENT_PLAN` aggregate identity를 dual-write한다. 실제 inbound event를 causation으로
     * 보존하지만 patient reference, signature, treatment detail, credential은 절대 복사하지 않는다.
     *
     * 이 메서드는 transaction을 열거나 commit하지 않는다. caller는 plan을 영속화하고 inbox event를
     * processed로 표시하는 같은 Exposed transaction 안에서 호출해야 하며, 그래야 세 효과가 함께 rollback된다.
     *
     * @param envelope 인증 및 integrity check가 완료된 purchase event envelope.
     * @param planId 같은 transaction에서 생성된 plan의 양수 데이터베이스 identity.
     */
    fun insertPlanCreatedOutbox(
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        planId: Long,
    ) {
        require(planId > 0) { "planId must be positive" }
        val outboxEventId = UUID.nameUUIDFromBytes(
            "AppointmentPlanCreated:${envelope.eventId}:$planId".toByteArray(StandardCharsets.UTF_8)
        ).toString()
        val payload = envelope.payload
        SchedulingOutboxEvents.insertAndGetId {
            it[eventId] = outboxEventId
            it[causationEventId] = envelope.eventId
            it[correlationId] = envelope.correlationId
            it[eventType] = "AppointmentPlanCreated"
            it[tenantGroupId] = payload.tenantGroupId
            it[clinicId] = payload.clinicId
            it[SchedulingOutboxEvents.planId] = planId
            it[aggregateType] = APPOINTMENT_PLAN_AGGREGATE_TYPE
            it[aggregateId] = planId.toString()
            it[schemaVersion] = 1
            it[payloadJson] = planCreatedPayloadJson(outboxEventId, envelope, planId)
            it[status] = SchedulingOutboxStatus.PENDING
            it[attemptCount] = 0
        }
    }

    /**
     * 명시적인 privacy-safe allow-list에서 안정적인 plan-event JSON을 생성한다.
     *
     * 이미 trusted envelope에서 온 metadata를 포함해 모든 문자열을 JSON escape한다.
     * 이렇게 하면 contract를 patient reference 또는 treatment detail로 넓히지 않고도
     * malformed payload나 field injection을 막을 수 있다.
     */
    private fun planCreatedPayloadJson(
        outboxEventId: String,
        envelope: TrustedSchedulingEventEnvelope<PurchaseCompletedEvent>,
        planId: Long,
    ): String {
        val payload = envelope.payload
        return buildString {
            append('{')
            append("\"eventId\":").appendJsonString(outboxEventId)
            append(",\"causationEventId\":").appendJsonString(envelope.eventId)
            append(",\"correlationId\":").appendJsonString(envelope.correlationId)
            append(",\"planId\":").append(planId)
            append(",\"tenantGroupId\":").append(payload.tenantGroupId)
            append(",\"clinicId\":").append(payload.clinicId)
            append(",\"sourcePurchaseAuthority\":").appendJsonString(payload.sourcePurchaseAuthority)
            append(",\"sourcePurchaseId\":").appendJsonString(payload.sourcePurchaseId)
            append(",\"sourceAggregateVersion\":").append(payload.sourceAggregateVersion)
            append('}')
        }
    }

    /** control character와 metacharacter를 escape하여 JSON string 하나를 추가한다. */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    /**
     * 모든 V9 outbox writer가 dual-write하고 있음을 보여주는 데이터베이스 계산 evidence를 읽는다.
     *
     * aggregation은 SQL에서 실행되며 outbox 크기와 관계없이 길이가 제한된 row 하나를 반환한다.
     * missing 또는 mismatch count 중 하나라도 non-zero이면 운영자는 policy publication을 비활성 상태로
     * 유지해야 한다. 이 메서드는 observational이며 legacy row를 repair하지 않는다.
     */
    fun readOutboxDualWriteConvergence(): OutboxDualWriteConvergence {
        val dialect = TransactionManager.current().db.dialect.name
        val planIdAsText = if (dialect.contains("mysql", ignoreCase = true)) {
            "CAST(plan_id AS CHAR)"
        } else {
            "CAST(plan_id AS VARCHAR)"
        }
        var result: OutboxDualWriteConvergence? = null
        TransactionManager.current().exec(
            """
            SELECT
                SUM(CASE
                    WHEN aggregate_type IS NULL OR aggregate_id IS NULL THEN 1
                    ELSE 0
                END) AS aggregate_identity_missing_count,
                SUM(CASE WHEN plan_id IS NOT NULL THEN 1 ELSE 0 END) AS legacy_plan_row_count,
                SUM(CASE
                    WHEN plan_id IS NOT NULL AND (
                        aggregate_type IS NULL
                        OR aggregate_id IS NULL
                        OR aggregate_type <> '$APPOINTMENT_PLAN_AGGREGATE_TYPE'
                        OR aggregate_id <> $planIdAsText
                    ) THEN 1
                    ELSE 0
                END) AS legacy_plan_mismatch_count
            FROM ${SchedulingOutboxEvents.tableName}
            """.trimIndent(),
        ) { rows ->
            check(rows.next()) { "Outbox convergence query returned no aggregate row" }
            result = OutboxDualWriteConvergence(
                aggregateIdentityMissingCount = rows.getLong("aggregate_identity_missing_count"),
                legacyPlanRowCount = rows.getLong("legacy_plan_row_count"),
                legacyPlanMismatchCount = rows.getLong("legacy_plan_mismatch_count"),
            )
        }
        return checkNotNull(result) { "Outbox convergence query produced no result" }
    }

    private companion object {
        const val APPOINTMENT_PLAN_AGGREGATE_TYPE = "APPOINTMENT_PLAN"
    }
}

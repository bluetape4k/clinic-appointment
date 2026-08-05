package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.AppointmentPlans
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * downstream publication을 기다리는 generic durable scheduling event 테이블이다.
 *
 * 새 writer는 항상 [aggregateType]과 [aggregateId]를 채운다. 이 column들이 nullable로
 * 남아 있는 이유는 pre-V9 writer와의 rolling compatibility뿐이다. 이후 migration에서
 * mandatory로 전환하기 전에는 [SchedulingEventRepository.readOutboxDualWriteConvergence]가
 * missing identity 0건을 보고해야 한다.
 *
 * event-driven plan row는 [causationEventId], [clinicId], [planId]를 보존한다.
 * command-driven tenant policy row는 세 값이 모두 `null`일 수 있으며, event lineage를
 * 꾸며내지 않고 trace continuity를 위해 [correlationId]를 사용한다.
 *
 * V22 appointment row는 [occurredAt], [topic], [partitionKey]와 relay lease metadata를
 * 함께 채운다. 기존 generic plan/policy row는 이 열들이 `null`인 legacy 상태를 그대로
 * 보존한다. appointment root command의 causation은 correlation ID를 사용하고, root가
 * 아닌 row는 실제 upstream event ID를 [causationEventId]에 기록한다.
 */
object SchedulingOutboxEvents : LongIdTable("scheduling_outbox_events") {
    /** publisher deduplication에 사용하는 안정적인 deterministic event identity. */
    val eventId = varchar("event_id", 128)

    /**
     * 실제 upstream event identity. command-driven event이면 `null`.
     *
     * correlation ID나 이 event의 자체 ID를 누락된 cause 대신 넣으면 event lineage를
     * 허위로 만들게 되므로 절대 대체하지 않는다.
     */
    val causationEventId = varchar("causation_event_id", 128).nullable()

    /** 길이가 제한된 request/workflow trace identifier. causation ID가 아니다. */
    val correlationId = varchar("correlation_id", 128)

    /** consumer-facing event contract의 닫힌 이름. */
    val eventType = varchar("event_type", 128)

    /** 모든 outbox row에 필요한 tenant ownership boundary. */
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)

    /**
     * clinic-scoped event의 clinic ownership boundary.
     *
     * tenant-scoped aggregate에서만 `null`이다. plan writer는 항상 실제 clinic foreign key를 보존한다.
     */
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT).nullable()

    /**
     * backward-compatible consumer를 위해 유지하는 legacy plan foreign key.
     *
     * `APPOINTMENT_PLAN` event에서는 non-null이고 non-plan aggregate에서는 `null`이다.
     * generic routing은 [aggregateType]과 [aggregateId]를 사용한다.
     */
    val planId = reference("plan_id", AppointmentPlans, onDelete = ReferenceOption.RESTRICT).nullable()

    /**
     * `APPOINTMENT_PLAN` 또는 `SCHEDULING_POLICY` 같은 generic aggregate category.
     *
     * 새 writer는 반드시 값을 채워야 한다. `null`은 아직 converged되지 않은 legacy writer를
     * 의미하며 policy publication/cutover를 차단한다.
     */
    val aggregateType = varchar("aggregate_type", 64).nullable()

    /**
     * 길이가 제한된 text로 encode한 안정적인 aggregate-local identity.
     *
     * 새 writer는 반드시 값을 채워야 한다. plan event는 decimal plan ID를 사용하고,
     * policy event는 decimal immutable definition ID를 사용한다.
     */
    val aggregateId = varchar("aggregate_id", 160).nullable()

    /** envelope가 실제로 발생한 UTC instant. legacy row에서는 `null`일 수 있다. */
    val occurredAt = timestamp("occurred_at").nullable()

    /** relay가 publish할 bounded Kafka topic. legacy row에서는 `null`일 수 있다. */
    val topic = varchar("topic", 249).nullable()

    /** 같은 aggregate의 순서를 유지하는 bounded partition routing key. */
    val partitionKey = varchar("partition_key", 512).nullable()

    /** 현재 row를 claim한 relay owner. 미청구 row에서는 `null`이다. */
    val leaseOwner = varchar("lease_owner", 160).nullable()

    /** lease fencing token. 미청구 row에서는 `null`이다. */
    val leaseToken = varchar("lease_token", 128).nullable()

    /** DB clock 기준 lease 만료 시각. 미청구 row에서는 `null`이다. */
    val leaseUntil = timestamp("lease_until").nullable()

    /** 마지막 실패의 bounded stable reason code. 아직 실패하지 않은 row에서는 `null`이다. */
    val lastFailureCode = varchar("last_failure_code", 64).nullable()

    /** 마지막 retry/failure가 관측된 UTC instant. 아직 실패하지 않은 row에서는 `null`이다. */
    val lastFailureAt = timestamp("last_failure_at").nullable()

    /** [payloadJson]의 양수 wire-schema version. */
    val schemaVersion = integer("schema_version")

    /**
     * redacted event payload JSON.
     *
     * stable ID, generation, hash, 길이가 제한된 actor audit reference는 포함할 수 있지만,
     * credential, bearer token, patient reference, raw policy payload, idempotency key는
     * 절대 포함하지 않는다.
     */
    val payloadJson = text("payload_json")

    /** 현재 publisher lifecycle. */
    val status = enumerationByName<SchedulingOutboxStatus>("status", 32)

    /** 완료된 publication attempt 횟수. 0에서 시작한다. */
    val attemptCount = integer("attempt_count").default(0)

    /** 가장 이른 UTC retry instant. 예약된 retry가 없으면 `null`. */
    val nextAttemptAt = timestamp("next_attempt_at").nullable()

    /** 데이터베이스 삽입 UTC instant. */
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    /** publication 성공 UTC instant. 아직 published 전이면 `null`. */
    val publishedAt = timestamp("published_at").nullable()

    init {
        uniqueIndex("uq_outbox_event_id", eventId)
        index("idx_outbox_plan_id", false, planId)
        index("idx_outbox_status_created_at", false, status, createdAt)
        index("idx_outbox_status_next_attempt", false, status, nextAttemptAt)
        index(
            "idx_outbox_retention",
            false,
            tenantGroupId,
            clinicId,
            status,
            publishedAt,
            id,
        )
        index("idx_outbox_aggregate", false, aggregateType, aggregateId, createdAt)
        index(
            "idx_outbox_appointment_ready",
            false,
            status,
            aggregateType,
            eventType,
            nextAttemptAt,
            leaseUntil,
            createdAt,
            id,
        )
        index(
            "idx_outbox_appointment_lease_recovery",
            false,
            status,
            aggregateType,
            eventType,
            leaseUntil,
            id,
        )
    }
}

/** generic scheduling event 하나에 대한 durable publisher lifecycle. */
enum class SchedulingOutboxStatus {
    /** 첫 번째 또는 다음 publication attempt를 기다리는 상태. */
    PENDING,

    /** 성공적으로 published됨. [SchedulingOutboxEvents.publishedAt]이 설정된다. */
    PUBLISHED,

    /** retry policy가 소진되었거나 operator 개입이 필요한 상태. */
    FAILED,
}

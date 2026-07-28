package io.bluetape4k.clinic.appointment.model.tables

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * 예약 aggregate의 append-only 감사 조회 projection입니다.
 *
 * proposal, consent, Plan revision, 상태 전이처럼 서로 다른 원본 table의 보존 수명주기를
 * 바꾸지 않고 tenant/clinic/aggregate/time 축으로 감사 이력을 조회하기 위한 최소 metadata만
 * 저장한다. [payloadHash]는 원문이나 개인정보를 대신하는 검증 hash이며 raw payload를 저장하거나
 * 로그에 출력하면 안 된다.
 */
object AppointmentAuditEvents : LongIdTable("scheduling_appointment_audit_events") {
    /** 감사 event를 소유하는 SaaS tenant 경계입니다. */
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)

    /** 감사 event가 발생한 병원 경계입니다. */
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.CASCADE)

    /** `APPOINTMENT`, `COMMITMENT`, `PLAN`처럼 안정적인 aggregate 분류입니다. */
    val aggregateType = varchar("aggregate_type", 64)

    /** 원본 서비스와 aggregate type 범위에서 안정적인 비민감 식별자입니다. */
    val aggregateId = varchar("aggregate_id", 160)

    /** 상태 전이나 명령 결과를 나타내는 안정적인 event type입니다. */
    val eventType = varchar("event_type", 128)

    /** 인증 원문 대신 행위자 범위를 비교하는 비가역 hash입니다. */
    val actorScopeHash = varchar("actor_scope_hash", 128).nullable()

    /** 감사 payload 원문을 보관하지 않고 동일성을 검증하는 canonical hash입니다. */
    val payloadHash = varchar("payload_hash", 64)

    /** 원본 업무 event가 발생한 UTC 시각이며 저장 시각으로 대체하면 안 됩니다. */
    val occurredAt = timestamp("occurred_at")

    init {
        index(
            "idx_appointment_audit_aggregate",
            false,
            tenantGroupId,
            clinicId,
            aggregateId,
            occurredAt,
        )
    }
}

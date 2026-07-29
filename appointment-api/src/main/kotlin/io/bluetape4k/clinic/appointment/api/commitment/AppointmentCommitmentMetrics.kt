package io.bluetape4k.clinic.appointment.api.commitment

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 예약 command의 성공 여부와 자원 충돌을 기록하는 관측 port입니다.
 *
 * 업무 transaction은 이 port의 실패와 독립적이어야 합니다. 구현체 또는 registry가
 * 예외를 던지더라도 예약 결과를 성공에서 실패로 바꾸거나 이미 commit된 command를
 * caller가 재시도하게 만들면 안 됩니다.
 */
interface AppointmentCommitmentCommandMetrics {
    fun recordProposalLatency(
        tenant: String,
        clinic: String,
        result: CommitmentMetricResult,
        latency: Duration,
    )

    fun recordAllocationConflict(
        tenant: String,
        clinic: String,
        reason: CommitmentConflictReason,
    )
}

/**
 * commitment v2 운영 신호를 안정적인 저카디널리티 tag로 기록합니다.
 *
 * 허용 tag는 tenant code, clinic code, 닫힌 enum뿐입니다. patient, product,
 * appointment, proposal, event 식별자는 tag나 metric 이름에 포함하지 않습니다.
 * duration은 음수가 될 수 없으며 Micrometer의 timer로 기록합니다.
 */
class AppointmentCommitmentMetrics(
    private val registry: MeterRegistry,
) : AppointmentCommitmentCommandMetrics {

    /** proposal command 지연을 병원 scope와 닫힌 [result] 분류로 기록합니다. */
    override fun recordProposalLatency(
        tenant: String,
        clinic: String,
        result: CommitmentMetricResult,
        latency: Duration,
    ) = timer("appointment.commitment.proposal.latency", tags(tenant, clinic, "result", result.name), latency)

    /** proposal 만료 1건을 PII가 없는 [reason] counter로 기록합니다. */
    fun recordProposalExpiry(
        tenant: String,
        clinic: String,
        reason: CommitmentExpiryReason,
    ) = counter("appointment.commitment.proposal.expired", tags(tenant, clinic, "reason", reason.name))

    /** 자원 점유 실패 1건을 안정적인 [reason] counter로 기록합니다. */
    override fun recordAllocationConflict(
        tenant: String,
        clinic: String,
        reason: CommitmentConflictReason,
    ) = counter("appointment.commitment.allocation.conflict", tags(tenant, clinic, "reason", reason.name))

    /** 전달 완료 전 outbox 대기 시간을 음수 없는 duration으로 기록합니다. */
    fun recordOutboxLag(
        tenant: String,
        clinic: String,
        lag: Duration,
    ) = timer("appointment.commitment.outbox.lag", tags(tenant, clinic), lag)

    /** 격리 1건과 현재 age를 같은 저카디널리티 [reason]으로 기록합니다. */
    fun recordQuarantine(
        tenant: String,
        clinic: String,
        reason: CommitmentQuarantineReason,
        age: Duration,
    ) {
        val stableTags = tags(tenant, clinic, "reason", reason.name)
        counter("appointment.commitment.quarantine.detected", stableTags)
        timer("appointment.commitment.quarantine.age", stableTags, age)
    }

    /** 상품 version 전환 거부 1건을 제한된 [reason]으로 기록합니다. */
    fun recordMigrationRejection(
        tenant: String,
        clinic: String,
        reason: CommitmentMigrationReason,
    ) = counter("appointment.commitment.migration.rejected", tags(tenant, clinic, "reason", reason.name))

    /** CRM 운영 예외 접수까지 걸린 시간을 제한된 [type]으로 기록합니다. */
    fun recordOperationalExceptionAckLatency(
        tenant: String,
        clinic: String,
        type: CommitmentExceptionType,
        latency: Duration,
    ) = timer(
        "appointment.commitment.operational.exception.ack.latency",
        tags(tenant, clinic, "type", type.name),
        latency,
    )

    /** tenant·clinic retention 실행 지연과 종결 [result]를 기록합니다. */
    fun recordRetentionRun(
        tenant: String,
        clinic: String,
        result: CommitmentRetentionRunResult,
        latency: Duration,
    ) = timer("appointment.commitment.retention.run.latency", tags(tenant, clinic, "result", result.name), latency)

    private fun counter(name: String, tags: List<Tag>) {
        registry.counter(name, tags).increment()
    }

    private fun timer(name: String, tags: List<Tag>, duration: Duration) {
        require(!duration.isNegative) { "metric duration must not be negative" }
        registry.timer(name, tags).record(duration.toNanos(), TimeUnit.NANOSECONDS)
    }

    private fun tags(
        tenant: String,
        clinic: String,
        dimension: String? = null,
        value: String? = null,
    ): List<Tag> {
        requireStableTagValue(tenant, "tenant")
        requireStableTagValue(clinic, "clinic")
        return buildList {
            add(Tag.of("tenant", tenant))
            add(Tag.of("clinic", clinic))
            if (dimension != null && value != null) {
                add(Tag.of(dimension, value))
            }
        }
    }

    private fun requireStableTagValue(
        value: String,
        name: String,
    ) {
        require(value.length in 1..64 && STABLE_TAG_VALUE.matches(value)) {
            "$name metric tag must contain 1..64 stable ASCII characters"
        }
    }

    private companion object {
        val STABLE_TAG_VALUE = Regex("[A-Za-z0-9._:-]{1,64}")
    }
}

/** proposal 계산 결과의 닫힌 분류입니다. */
enum class CommitmentMetricResult { SUCCESS, REJECTED, FAILED }

/** proposal 만료 원인의 닫힌 분류입니다. */
enum class CommitmentExpiryReason { TTL, SUPERSEDED, POLICY_CHANGED }

/** 자원 배정 충돌 원인의 닫힌 분류입니다. */
enum class CommitmentConflictReason { OVERLAP, CAPACITY, VERSION }

/** 격리 원인의 dashboard 집계 분류입니다. */
enum class CommitmentQuarantineReason { VERSION_GAP, SCHEMA, TRUST, POISON }

/** 상품 version 전환 거부 원인의 dashboard 집계 분류입니다. */
enum class CommitmentMigrationReason { CONSENT_REQUIRED, MAPPING_MISSING, PROVENANCE_CONFLICT }

/** 운영 예외 ACK SLA를 집계할 안정적인 분류입니다. */
enum class CommitmentExceptionType { RESOURCE_DISRUPTION, CUSTOMER_DECLINED, PRODUCT_MIGRATION, OTHER }

/** retention 실행 결과의 닫힌 집계 분류입니다. */
enum class CommitmentRetentionRunResult { SUCCESS, PARTIAL, FAILED }

package io.bluetape4k.clinic.appointment.api.commitment

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * commitment v2 운영 신호를 안정적인 저카디널리티 tag로 기록합니다.
 *
 * 허용 tag는 tenant code, clinic code, 닫힌 enum뿐입니다. patient, product,
 * appointment, proposal, event 식별자는 tag나 metric 이름에 포함하지 않습니다.
 * duration은 음수가 될 수 없으며 Micrometer의 timer로 기록합니다.
 */
class AppointmentCommitmentMetrics(
    private val registry: MeterRegistry,
) {

    fun recordProposalLatency(
        tenant: String,
        clinic: String,
        result: CommitmentMetricResult,
        latency: Duration,
    ) = timer("appointment.commitment.proposal.latency", tags(tenant, clinic, "result", result.name), latency)

    fun recordProposalExpiry(
        tenant: String,
        clinic: String,
        reason: CommitmentExpiryReason,
    ) = counter("appointment.commitment.proposal.expired", tags(tenant, clinic, "reason", reason.name))

    fun recordAllocationConflict(
        tenant: String,
        clinic: String,
        reason: CommitmentConflictReason,
    ) = counter("appointment.commitment.allocation.conflict", tags(tenant, clinic, "reason", reason.name))

    fun recordOutboxLag(
        tenant: String,
        clinic: String,
        lag: Duration,
    ) = timer("appointment.commitment.outbox.lag", tags(tenant, clinic), lag)

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

    fun recordMigrationRejection(
        tenant: String,
        clinic: String,
        reason: CommitmentMigrationReason,
    ) = counter("appointment.commitment.migration.rejected", tags(tenant, clinic, "reason", reason.name))

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
        require(tenant.isNotBlank() && clinic.isNotBlank()) { "tenant and clinic metric tags must not be blank" }
        return buildList {
            add(Tag.of("tenant", tenant))
            add(Tag.of("clinic", clinic))
            if (dimension != null && value != null) {
                add(Tag.of(dimension, value))
            }
        }
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

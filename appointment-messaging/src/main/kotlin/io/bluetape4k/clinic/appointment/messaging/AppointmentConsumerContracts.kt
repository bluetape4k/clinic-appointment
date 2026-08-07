package io.bluetape4k.clinic.appointment.messaging

import java.io.Serializable

/** Kafka group과 무관하게 dedup을 유지하는 logical consumer identity입니다. */
@JvmInline
value class AppointmentLogicalConsumerId(val value: String) : Serializable {
    init {
        require(value.length in 1..128) { "logical consumer id must be bounded" }
        require(value.matches(IDENTIFIER_PATTERN)) { "logical consumer id is not canonical" }
    }
}

/** topic migration에도 유지되는 logical stream identity입니다. */
@JvmInline
value class AppointmentLogicalStreamId(val value: String) : Serializable {
    init {
        require(value.length in 1..128) { "logical stream id must be bounded" }
        require(value.matches(IDENTIFIER_PATTERN)) { "logical stream id is not canonical" }
    }
}

data class AppointmentConsumerIdentity(
    val consumerId: AppointmentLogicalConsumerId,
    val streamId: AppointmentLogicalStreamId,
) : Serializable

/** Kafka record의 provenance입니다. payload 원문은 의도적으로 포함하지 않습니다. */
data class AppointmentConsumerProvenance(
    val topic: AppointmentTopic,
    val partition: Int,
    val offset: Long,
    val schemaVersion: Int,
    val tenantGroupId: Long,
    val clinicId: Long,
    val payloadSha256: String,
) : Serializable {
    init {
        require(partition >= 0) { "partition must not be negative" }
        require(offset >= 0) { "offset must not be negative" }
        require(tenantGroupId > 0 && clinicId > 0) { "tenant/clinic scope must be positive" }
        require(payloadSha256.matches(SHA256_PATTERN)) { "payloadSha256 must be a SHA-256 digest" }
    }
}

enum class AppointmentConsumerStatus {
    PROCESSING,
    PROCESSED,
    RETRYABLE,
    QUARANTINED,
}

enum class AppointmentConsumerFailureCode {
    INVALID_ENVELOPE,
    UNSUPPORTED_SCHEMA,
    SCOPE_MISMATCH,
    PARTITION_KEY_MISMATCH,
    PROVENANCE_MISMATCH,
    HANDLER_RETRYABLE,
    HANDLER_FAILED,
    LEASE_EXPIRED,
    ATTEMPT_EXHAUSTED,
}

data class AppointmentConsumerIdentityKey(
    val identity: AppointmentConsumerIdentity,
    val eventId: AppointmentEventId,
) : Serializable

data class AppointmentSchemaReadiness(
    val subject: String,
    val localSchemaValid: Boolean,
    val registryReachable: Boolean,
    val compatibilityLevel: String,
) : Serializable {
    val ready: Boolean
        get() = localSchemaValid && registryReachable && compatibilityLevel == EXPECTED_COMPATIBILITY

    companion object {
        const val EXPECTED_COMPATIBILITY = "BACKWARD_TRANSITIVE"
    }
}

/** 승인된 replay의 입력 계약입니다. 운영 group offset rewind는 이 타입으로 표현하지 않습니다. */
data class AppointmentReplayRequest(
    val identity: AppointmentConsumerIdentity,
    val tenantGroupId: Long,
    val clinicId: Long,
    val approver: String,
    val fromOffset: Long,
    val toOffset: Long,
    val dryRun: Boolean,
    /** null이면 허용된 scope의 모든 partition을 bounded range로 읽습니다. */
    val partition: Int? = null,
) : Serializable {
    init {
        require(tenantGroupId > 0 && clinicId > 0) { "replay scope must be positive" }
        require(approver.length in 1..128) { "replay approver must be bounded" }
        require(approver.matches(IDENTIFIER_PATTERN)) { "replay approver is not canonical" }
        require(fromOffset >= 0 && toOffset >= fromOffset) { "replay offsets must be ordered" }
        require(toOffset - fromOffset <= MAX_REPLAY_OFFSET_RANGE) {
            "replay range is too large"
        }
        require(partition == null || partition >= 0) { "replay partition must not be negative" }
    }

    companion object {
        private const val MAX_REPLAY_OFFSET_RANGE = 100_000L
    }
}

/** replay source가 decode한 envelope에 적용하는 tenant/clinic 경계입니다. */
data class AppointmentReplayScope(
    val tenantGroupId: Long,
    val clinicId: Long,
) : Serializable {
    init {
        require(tenantGroupId > 0 && clinicId > 0) { "replay scope must be positive" }
    }
}

enum class AppointmentReplayAuditStatus {
    REQUESTED,
    DRY_RUN,
    EXECUTED,
    REJECTED,
}

private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

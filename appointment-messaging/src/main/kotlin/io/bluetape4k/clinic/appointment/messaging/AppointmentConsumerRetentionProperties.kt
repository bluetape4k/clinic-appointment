package io.bluetape4k.clinic.appointment.messaging

import java.time.Duration

/**
 * consumer metadata 보존 기간입니다.
 *
 * 기본값은 비활성화이며, 운영자는 각 terminal table의 보존 기간과 bounded batch를
 * 명시적으로 설정한 뒤 scheduler/job에서 [AppointmentConsumerRetentionService.cleanup]을 호출합니다.
 */
data class AppointmentConsumerRetentionProperties(
    val enabled: Boolean = false,
    /** Spring scheduler를 사용할 때만 true로 설정합니다. 외부 CronJob과 중복 실행하지 않습니다. */
    val schedulerEnabled: Boolean = false,
    val interval: Duration = Duration.ofHours(1),
    val processedAge: Duration = Duration.ofDays(30),
    val rejectedAge: Duration = Duration.ofDays(30),
    val quarantineAge: Duration = Duration.ofDays(90),
    val replayAuditAge: Duration = Duration.ofDays(365),
    val batchSize: Int = 500,
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "retention interval must be positive" }
        require(!processedAge.isNegative && !processedAge.isZero) { "processedAge must be positive" }
        require(!rejectedAge.isNegative && !rejectedAge.isZero) { "rejectedAge must be positive" }
        require(!quarantineAge.isNegative && !quarantineAge.isZero) { "quarantineAge must be positive" }
        require(!replayAuditAge.isNegative && !replayAuditAge.isZero) { "replayAuditAge must be positive" }
        require(batchSize in 1..1_000) { "retention batchSize must be bounded" }
    }
}

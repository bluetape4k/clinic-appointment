package io.bluetape4k.clinic.appointment.messaging

import java.time.Duration

/** 검증된 consumer binding입니다. group과 logical identity는 서로 다른 경계입니다. */
data class AppointmentConsumerProperties(
    val enabled: Boolean = false,
    val groupId: String = "appointment-consumer-v1",
    val logicalConsumerId: AppointmentLogicalConsumerId = AppointmentLogicalConsumerId("appointment-consumer"),
    val logicalStreamId: AppointmentLogicalStreamId = AppointmentLogicalStreamId("appointment-events"),
    val topic: AppointmentTopic = AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC),
    val maxAttempts: Int = 8,
    val processingLease: Duration = Duration.ofMinutes(5),
    val maxPollInterval: Duration = Duration.ofMinutes(5),
    val maxPollRecords: Int = 1,
    val shutdownTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        require(groupId.length in 1..128) { "consumer group id must be bounded" }
        require(groupId.matches(IDENTIFIER_PATTERN)) { "consumer group id is not canonical" }
        require(maxAttempts in 1..100) { "consumer maxAttempts must be bounded" }
        require(!processingLease.isNegative && !processingLease.isZero) { "consumer processingLease must be positive" }
        require(!maxPollInterval.isNegative && !maxPollInterval.isZero) { "consumer maxPollInterval must be positive" }
        require(maxPollInterval >= processingLease) {
            "consumer maxPollInterval must cover the processing lease"
        }
        require(maxPollRecords in 1..100) { "consumer maxPollRecords must be bounded" }
        require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) {
            "consumer shutdownTimeout must be positive"
        }
    }

    companion object {
        private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}

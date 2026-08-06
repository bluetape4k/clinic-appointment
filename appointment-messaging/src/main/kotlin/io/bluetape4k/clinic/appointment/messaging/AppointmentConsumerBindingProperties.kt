package io.bluetape4k.clinic.appointment.messaging

import java.time.Duration

/** 애플리케이션별 consumer group과 logical identity를 Spring binding으로 받습니다. */
data class AppointmentConsumerBindingProperties(
    val enabled: Boolean = false,
    val groupId: String = "appointment-consumer-v1",
    val logicalConsumerId: String = "appointment-consumer",
    val logicalStreamId: String = "appointment-events",
    val topic: String = DefaultAppointmentOutboxWriter.DEFAULT_TOPIC,
    val maxAttempts: Int = 8,
    val processingLease: Duration = Duration.ofMinutes(5),
    val maxPollInterval: Duration = Duration.ofMinutes(5),
    val maxPollRecords: Int = 1,
    val shutdownTimeout: Duration = Duration.ofSeconds(10),
)

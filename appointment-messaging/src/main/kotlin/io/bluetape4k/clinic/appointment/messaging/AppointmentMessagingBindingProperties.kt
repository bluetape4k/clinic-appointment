package io.bluetape4k.clinic.appointment.messaging

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Spring Boot가 외부 설정을 먼저 바인딩하는 단순한 문자열/기간 경계다.
 *
 * Kafka topic은 바인딩 이후 [AppointmentTopic]으로 승격되어 allow-list와 동일한
 * 검증을 다시 거친다. 따라서 설정 문자열이 writer/relay 계약을 우회하지 않는다.
 */
@ConfigurationProperties(prefix = "appointment.messaging")
data class AppointmentMessagingBindingProperties(
    val topic: String = DefaultAppointmentOutboxWriter.DEFAULT_TOPIC,
    val allowedTopics: Set<String> = setOf(topic),
    val leaseDuration: Duration = Duration.ofSeconds(30),
    val sendTimeout: Duration = Duration.ofSeconds(5),
    val retryBaseDelay: Duration = Duration.ofSeconds(2),
    val maxRetryDelay: Duration = Duration.ofMinutes(1),
    val kafkaClientRetryBudget: Duration = Duration.ofSeconds(5),
    val terminalDbUpdateBudget: Duration = Duration.ofSeconds(3),
    val safetyMargin: Duration = Duration.ofSeconds(10),
    val pollInterval: Duration = Duration.ofSeconds(1),
    val shutdownTimeout: Duration = Duration.ofSeconds(10),
    val claimSize: Int = 2,
    val maxInFlight: Int = 1,
    val maxClinicBatch: Int = 1,
    val maxAttempts: Int = 8,
    val enabled: Boolean = true,
    val producerAcks: String = "all",
    val producerEnableIdempotence: Boolean = true,
    val producerAllowAutoCreateTopics: Boolean = false,
    val producerRequestTimeout: Duration = Duration.ofSeconds(5),
    val producerDeliveryTimeout: Duration = Duration.ofSeconds(15),
    val producerMetadataTimeout: Duration = Duration.ofSeconds(5),
    val producerSecurityProtocol: String = "PLAINTEXT",
    val producerCredentialReference: String? = null,
    val consumer: AppointmentConsumerBindingProperties = AppointmentConsumerBindingProperties(),
)

package io.bluetape4k.clinic.appointment.messaging

import java.time.Duration

/** Kafka4 relay의 fail-closed 운영 설정이다. */
data class AppointmentMessagingProperties(
    val topic: AppointmentTopic = AppointmentTopic(DefaultAppointmentOutboxWriter.DEFAULT_TOPIC),
    val allowedTopics: Set<AppointmentTopic> = setOf(topic),
    val leaseDuration: Duration = Duration.ofSeconds(30),
    val sendTimeout: Duration = Duration.ofSeconds(5),
    val retryBaseDelay: Duration = Duration.ofSeconds(2),
    val maxRetryDelay: Duration = Duration.ofMinutes(1),
    /** Kafka client 내부 재시도에 할당하는 relay lease budget이다. */
    val kafkaClientRetryBudget: Duration = Duration.ofSeconds(5),
    val terminalDbUpdateBudget: Duration = Duration.ofSeconds(3),
    val safetyMargin: Duration = Duration.ofSeconds(10),
    val pollInterval: Duration = Duration.ofSeconds(1),
    val shutdownTimeout: Duration = Duration.ofSeconds(10),
    /** 현재 relay 구현은 순차 전송이므로 lease 안에 모두 처리할 수 있는 크기만 허용한다. */
    val claimSize: Int = 2,
    val maxInFlight: Int = 1,
    val maxClinicBatch: Int = 1,
    val maxAttempts: Int = 8,
    val enabled: Boolean = true,
    /** producer contract는 operator가 약화할 수 없도록 typed value로 고정한다. */
    val producerAcks: String = "all",
    val producerEnableIdempotence: Boolean = true,
    val producerAllowAutoCreateTopics: Boolean = false,
    val producerRequestTimeout: Duration = Duration.ofSeconds(5),
    val producerDeliveryTimeout: Duration = Duration.ofSeconds(15),
    val producerMetadataTimeout: Duration = Duration.ofSeconds(5),
    val producerSecurityProtocol: String = "PLAINTEXT",
    val producerCredentialReference: String? = null,
    val consumer: AppointmentConsumerProperties = AppointmentConsumerProperties(),
) {
    init {
        require(allowedTopics.isNotEmpty()) { "allowedTopics must not be empty" }
        require(topic in allowedTopics) { "default topic must be allow-listed" }
        require(consumer.topic in allowedTopics) { "consumer topic must be allow-listed" }
        require(claimSize in 1..32) { "claimSize must be between 1 and 32" }
        require(maxInFlight == 1) { "maxInFlight must be 1 until structured concurrent relay is enabled" }
        require(maxClinicBatch in 1..4) { "maxClinicBatch must be between 1 and 4" }
        require(maxAttempts in 1..100) { "maxAttempts must be bounded" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
        require(!sendTimeout.isNegative && !sendTimeout.isZero) { "sendTimeout must be positive" }
        require(!retryBaseDelay.isNegative && !retryBaseDelay.isZero) { "retryBaseDelay must be positive" }
        require(!maxRetryDelay.isNegative && !maxRetryDelay.isZero) { "maxRetryDelay must be positive" }
        require(maxRetryDelay >= retryBaseDelay) { "maxRetryDelay must cover retryBaseDelay" }
        require(!kafkaClientRetryBudget.isNegative && !kafkaClientRetryBudget.isZero) {
            "kafkaClientRetryBudget must be positive"
        }
        require(!terminalDbUpdateBudget.isNegative && !terminalDbUpdateBudget.isZero) {
            "terminalDbUpdateBudget must be positive"
        }
        require(!safetyMargin.isNegative && !safetyMargin.isZero) { "safetyMargin must be positive" }
        require(!pollInterval.isNegative && !pollInterval.isZero) { "pollInterval must be positive" }
        require(!shutdownTimeout.isNegative && !shutdownTimeout.isZero) {
            "shutdownTimeout must be positive"
        }
        val sequentialSendBudget = sendTimeout.multipliedBy(claimSize.toLong())
        require(leaseDuration >= sequentialSendBudget
            .plus(kafkaClientRetryBudget)
            .plus(terminalDbUpdateBudget)
            .plus(safetyMargin)
        ) {
            "leaseDuration must cover the sequential claim window, terminal update budget, and safety margin"
        }
        require(producerAcks == "all") { "producerAcks must be all" }
        require(producerEnableIdempotence) { "producerEnableIdempotence must be true" }
        require(!producerAllowAutoCreateTopics) { "producerAllowAutoCreateTopics must be false" }
        require(!producerRequestTimeout.isNegative && !producerRequestTimeout.isZero) {
            "producerRequestTimeout must be positive"
        }
        require(!producerDeliveryTimeout.isNegative && !producerDeliveryTimeout.isZero) {
            "producerDeliveryTimeout must be positive"
        }
        require(!producerMetadataTimeout.isNegative && !producerMetadataTimeout.isZero) {
            "producerMetadataTimeout must be positive"
        }
        require(producerDeliveryTimeout >= producerRequestTimeout) {
            "producerDeliveryTimeout must cover producerRequestTimeout"
        }
        require(producerMetadataTimeout <= producerDeliveryTimeout) {
            "producerMetadataTimeout must fit inside producerDeliveryTimeout"
        }
        require(producerSecurityProtocol in SUPPORTED_SECURITY_PROTOCOLS) {
            "producerSecurityProtocol must be PLAINTEXT, SSL, or SASL_SSL"
        }
        if (producerSecurityProtocol != "PLAINTEXT") {
            require(!producerCredentialReference.isNullOrBlank()) {
                "producerCredentialReference is required for secured Kafka protocols"
            }
        }
    }

    companion object {
        private val SUPPORTED_SECURITY_PROTOCOLS = setOf("PLAINTEXT", "SSL", "SASL_SSL")
    }
}

/** writer가 구성된 상태와 broker relay 상태를 분리하는 readiness snapshot이다. */
data class AppointmentMessagingReadiness(
    val enabled: Boolean = true,
    val configurationValid: Boolean,
    val brokerAvailable: Boolean,
    val relayPaused: Boolean,
    val relayHeld: Boolean = false,
    val schemaValid: Boolean = true,
    val serializerValid: Boolean = true,
) {
    val ready: Boolean
        get() = enabled && configurationValid && brokerAvailable && !relayPaused && !relayHeld &&
            schemaValid && serializerValid
}

/** relay lifecycle이 broker와 pause 상태를 bounded하게 노출하는 mutable probe다. */
class AppointmentMessagingReadinessProbe(
    enabled: Boolean = true,
    configurationValid: Boolean = true,
    brokerAvailable: Boolean = false,
) {
    @Volatile
    private var enabled: Boolean = enabled

    @Volatile
    private var configurationValid: Boolean = configurationValid

    @Volatile
    private var brokerAvailable: Boolean = brokerAvailable

    @Volatile
    private var operatorPaused: Boolean = false

    @Volatile
    private var automaticPause: Boolean = false

    @Volatile
    private var relayHeld: Boolean = false

    @Volatile
    private var schemaValid: Boolean = true

    @Volatile
    private var serializerValid: Boolean = true

    fun markConfigurationInvalid() {
        configurationValid = false
    }

    fun markEnabled() {
        enabled = true
    }

    fun markDisabled() {
        enabled = false
    }

    fun markBrokerAvailable() {
        brokerAvailable = true
    }

    fun markBrokerUnavailable() {
        brokerAvailable = false
    }

    fun markRelayPaused() {
        operatorPaused = true
    }

    fun markRelayResumed() {
        operatorPaused = false
        automaticPause = false
    }

    /** circuit-breaker pause를 수동 operator pause와 분리해 자동 만료 시에만 해제한다. */
    fun markAutomaticRelayPaused() {
        automaticPause = true
    }

    fun markAutomaticRelayResumed() {
        automaticPause = false
    }

    /** schema rollback/redrive hold는 operator가 명시적으로 해제할 때까지 claim을 막는다. */
    fun markRelayHeld() {
        relayHeld = true
    }

    fun releaseRelayHold() {
        relayHeld = false
    }

    fun markSchemaInvalid() {
        schemaValid = false
    }

    fun markSchemaAvailable() {
        schemaValid = true
    }

    fun markSerializerInvalid() {
        serializerValid = false
    }

    fun markSerializerAvailable() {
        serializerValid = true
    }

    fun snapshot(): AppointmentMessagingReadiness = AppointmentMessagingReadiness(
        enabled = enabled,
        configurationValid = configurationValid,
        brokerAvailable = brokerAvailable,
        relayPaused = operatorPaused || automaticPause,
        relayHeld = relayHeld,
        schemaValid = schemaValid,
        serializerValid = serializerValid,
    )
}

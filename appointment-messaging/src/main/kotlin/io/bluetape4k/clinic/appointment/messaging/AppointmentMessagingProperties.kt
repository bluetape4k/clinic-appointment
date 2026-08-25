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
    val schemaRegistry: AppointmentSchemaRegistryProperties = AppointmentSchemaRegistryProperties(),
    val consumer: AppointmentConsumerProperties = AppointmentConsumerProperties(),
    val retention: AppointmentConsumerRetentionProperties = AppointmentConsumerRetentionProperties(),
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

/** readiness 실패를 운영자가 안전하게 분류할 수 있도록 bounded하게 보존하는 진단입니다. */
data class AppointmentReadinessDiagnostic(
    val operation: String,
    val target: String,
    val code: String,
    val errorClass: String? = null,
    val retryable: Boolean,
) {
    init {
        require(operation.length in 1..64 && operation.matches(IDENTIFIER_PATTERN)) {
            "readiness diagnostic operation must be canonical and bounded"
        }
        require(target.length in 1..128 && target.all { !it.isWhitespace() && !it.isISOControl() }) {
            "readiness diagnostic target must be bounded"
        }
        require(code.length in 1..64 && code.matches(IDENTIFIER_PATTERN)) {
            "readiness diagnostic code must be canonical and bounded"
        }
        require(errorClass == null ||
            (errorClass.length in 1..128 && errorClass.all { !it.isWhitespace() && !it.isISOControl() })) {
            "readiness diagnostic error class must be bounded"
        }
    }

    /** Actuator health detail로 노출할 수 있는 안전한 key/value 구조입니다. */
    fun toHealthDetail(): Map<String, Any> = buildMap {
        put("operation", operation)
        put("target", target)
        put("code", code)
        errorClass?.let { put("errorClass", it) }
        put("retryable", retryable)
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
    val registryValid: Boolean = true,
    val serializerValid: Boolean = true,
    val diagnostics: List<AppointmentReadinessDiagnostic> = emptyList(),
) {
    val ready: Boolean
        get() = enabled && configurationValid && brokerAvailable && !relayPaused && !relayHeld &&
            schemaValid && registryValid && serializerValid
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
    private var registryValid: Boolean = true

    @Volatile
    private var serializerValid: Boolean = true

    @Volatile
    private var diagnostics: List<AppointmentReadinessDiagnostic> = emptyList()

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

    fun markRegistryInvalid() {
        registryValid = false
    }

    fun markRegistryAvailable() {
        registryValid = true
    }

    fun markSerializerInvalid() {
        serializerValid = false
    }

    fun markSerializerAvailable() {
        serializerValid = true
    }

    /** validator가 이전 검사 결과를 지우고 현재 bounded diagnostic만 교체합니다. */
    internal fun replaceDiagnostics(values: Collection<AppointmentReadinessDiagnostic>) {
        diagnostics = values.distinct().take(MAX_DIAGNOSTICS)
    }

    fun snapshot(): AppointmentMessagingReadiness = AppointmentMessagingReadiness(
        enabled = enabled,
        configurationValid = configurationValid,
        brokerAvailable = brokerAvailable,
        relayPaused = operatorPaused || automaticPause,
        relayHeld = relayHeld,
        schemaValid = schemaValid,
        registryValid = registryValid,
        serializerValid = serializerValid,
        diagnostics = diagnostics,
    )

    private companion object {
        const val MAX_DIAGNOSTICS = 8
    }
}

private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

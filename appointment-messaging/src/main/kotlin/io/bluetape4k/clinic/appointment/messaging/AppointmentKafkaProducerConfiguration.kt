package io.bluetape4k.clinic.appointment.messaging

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.kafka.core.ProducerFactory

/** secret-manager reference를 Kafka SSL/SASL producer 설정으로 승격하는 애플리케이션 port다. */
fun interface AppointmentKafkaCredentialResolver {
    /** 반환값에는 `ssl.*` 또는 `sasl.*` Kafka client property만 포함해야 한다. */
    fun resolve(reference: String): Map<String, Any>
}

/**
 * Spring Kafka producer factory에 적용하는 fail-closed 설정의 증거다.
 * credential 자체는 public snapshot에 보관하지 않고 producer factory에만 적용한다.
 */
class AppointmentKafkaProducerConfiguration private constructor(
    /** credential 값이 제거된 effective producer configuration snapshot이다. */
    val kafkaProperties: Map<String, Any>,
    /** broker/admin 계약이며 이 consumer-side 키는 producer factory로 전송하지 않는다. */
    val contractProperties: Map<String, Any>,
    /** secret-manager에서 적용된 credential key 이름만 남긴 bounded evidence다. */
    val appliedCredentialKeys: Set<String>,
) {
    companion object {
        fun apply(
            properties: AppointmentMessagingProperties,
            producerFactory: ProducerFactory<*, *>,
            credentialResolver: AppointmentKafkaCredentialResolver? = null,
        ): AppointmentKafkaProducerConfiguration {
            val contractProperties: Map<String, Any> = mapOf(
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to properties.producerAllowAutoCreateTopics,
            )
            val credentialProperties = if (properties.producerSecurityProtocol == "PLAINTEXT") {
                emptyMap()
            } else {
                val reference = requireNotNull(properties.producerCredentialReference) {
                    "producerCredentialReference is required for secured Kafka protocols"
                }
                val resolver = requireNotNull(credentialResolver) {
                    "AppointmentKafkaCredentialResolver is required for secured Kafka protocols"
                }
                resolver.resolve(reference).also(::validateCredentialProperties)
            }
            val baseKafkaProperties: Map<String, Any> = mapOf(
                ProducerConfig.ACKS_CONFIG to properties.producerAcks,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to properties.producerEnableIdempotence,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to properties.producerRequestTimeout.toBoundedMillis("producerRequestTimeout"),
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to properties.producerDeliveryTimeout.toBoundedMillis("producerDeliveryTimeout"),
                ProducerConfig.MAX_BLOCK_MS_CONFIG to properties.producerMetadataTimeout.toBoundedMillis("producerMetadataTimeout"),
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG to properties.producerSecurityProtocol,
            )
            producerFactory.updateConfigs(baseKafkaProperties + credentialProperties)
            return AppointmentKafkaProducerConfiguration(
                kafkaProperties = baseKafkaProperties,
                contractProperties = contractProperties,
                appliedCredentialKeys = credentialProperties.keys,
            )
        }

        private fun validateCredentialProperties(values: Map<String, Any>) {
            require(values.isNotEmpty()) { "secured Kafka credential properties must not be empty" }
            require(values.keys.all { it.startsWith("ssl.") || it.startsWith("sasl.") }) {
                "secured Kafka credential properties must use ssl.* or sasl.* keys"
            }
        }

        private fun java.time.Duration.toBoundedMillis(name: String): Int {
            val millis = toMillis()
            require(millis in 1..Int.MAX_VALUE) { "$name must fit a positive Kafka timeout" }
            return millis.toInt()
        }
    }
}

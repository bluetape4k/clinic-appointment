package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.mockk
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.util.backoff.FixedBackOff
import org.junit.jupiter.api.Test

class AppointmentKafkaConsumerConfigurationTest {
    @Test
    fun `consumer factory uses manual immediate acknowledgement and disables topic auto creation`() {
        val consumerFactory = DefaultKafkaConsumerFactory<String, String>(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:9092",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ),
        )

        val factory: ConcurrentKafkaListenerContainerFactory<String, String> =
            AppointmentKafkaConsumerConfiguration().appointmentKafkaConsumerContainerFactory(consumerFactory)

        factory.containerProperties.ackMode shouldBeEqualTo
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE
        consumerFactory.configurationProperties[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG]
            .shouldBeEqualTo(false)
        consumerFactory.configurationProperties[ConsumerConfig.MAX_POLL_RECORDS_CONFIG]
            .toString()
            .shouldBeEqualTo("1")
        consumerFactory.configurationProperties[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG]
            .toString()
            .shouldBeEqualTo("300000")
        factory.containerProperties.isMissingTopicsFatal.shouldBeTrue()
        factory.containerProperties.shutdownTimeout shouldBeEqualTo 10_000L
        factory.createContainer("appointment-test").getCommonErrorHandler()
            .shouldBeInstanceOf<DefaultErrorHandler>()
    }

    @Test
    fun `recoverer classifies retryable cause inside spring kafka listener wrapper`() {
        val database = Database.connect(
            "jdbc:h2:mem:appointment_consumer_recoverer_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(AppointmentConsumerRejectedRecordTable)
        }
        val properties = AppointmentMessagingProperties(
            consumer = AppointmentConsumerProperties(maxAttempts = 1),
        )
        val handler = DefaultErrorHandler(
            appointmentConsumerRecoverer(
                properties = properties,
                inboxStore = JdbcAppointmentConsumerInboxStore(database),
                metrics = null,
            ),
            FixedBackOff(0, 0),
        )
        val record = ConsumerRecord<String, String>("clinic.appointment.events", 0, 1L, "key", "payload")
        val consumer = mockk<Consumer<Any, Any>>(relaxed = true)
        val container = mockk<MessageListenerContainer>(relaxed = true)

        handler.handleOne(
            ListenerExecutionFailedException(
                "listener failed",
                AppointmentConsumerRetryableException("scope authority unavailable"),
            ),
            record,
            consumer,
            container,
        ).shouldBeTrue()

        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().single()[AppointmentConsumerRejectedRecordTable.failureCode]
                .shouldBeEqualTo(AppointmentConsumerFailureCode.HANDLER_RETRYABLE.name)
        }
    }
}

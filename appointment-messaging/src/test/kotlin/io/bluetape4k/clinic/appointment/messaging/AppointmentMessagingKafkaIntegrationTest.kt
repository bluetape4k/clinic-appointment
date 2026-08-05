package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.mq.KafkaServer
import io.bluetape4k.testcontainers.mq.Spring
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Kafka4 broker에서 non-creating readiness와 실제 ACK 경계를 검증한다. */
@ResourceLock(
    value = AppointmentMessagingKafkaServerLauncher.RESOURCE_LOCK,
    mode = ResourceAccessMode.READ_WRITE,
)
class AppointmentMessagingKafkaIntegrationTest {

    @Test
    fun `admin readiness does not create a topic and publisher receives broker ack`() {
        val kafka = AppointmentMessagingKafkaServerLauncher.kafka
        val topicName = "clinic.appointment.integration.${UUID.randomUUID()}"
        val topic = AppointmentTopic(topicName)
        val adminProperties = KafkaServer.Launcher.getProducerProperties(kafka)
            .mapNotNull { (key, value) -> value?.let { key to it } }
            .toMap()
        val kafkaAdmin = KafkaAdmin(adminProperties).apply {
            setAutoCreate(false)
            setOperationTimeout(5)
        }
        val producerFactory = KafkaServer.Launcher.Spring.getStringProducerFactory(kafka)
        val template = KafkaTemplate(producerFactory, true)
        val publisher = SpringKafkaAppointmentPublisher(
            kafkaTemplate = template,
            kafkaAdmin = kafkaAdmin,
            metadataTimeout = Duration.ofSeconds(5),
        )

        try {
            publisher.probe(topic).shouldBeFalse()
            AdminClient.create(adminProperties).use { client ->
                client.listTopics().names().get(10, TimeUnit.SECONDS).contains(topicName).shouldBeFalse()
            }

            kafkaAdmin.createOrModifyTopics(NewTopic(topicName, 1, 1.toShort()))
            publisher.probe(topic).shouldBeTrue()
            publisher.publish(
                topic = topic,
                key = AppointmentPartitionKey("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"),
                value = "{\"eventId\":\"integration-event\"}",
            ).toCompletableFuture().get(10, TimeUnit.SECONDS)

            KafkaServer.Launcher.createStringConsumer(kafka).use { consumer ->
                consumer.subscribe(listOf(topicName))
                var received: String? = null
                repeat(20) {
                    if (received == null) {
                        received = consumer.poll(Duration.ofMillis(500)).firstOrNull()?.value()
                    }
                }
                received.shouldNotBeNull()
            }
        } finally {
            publisher.close()
            template.destroy()
        }
    }
}

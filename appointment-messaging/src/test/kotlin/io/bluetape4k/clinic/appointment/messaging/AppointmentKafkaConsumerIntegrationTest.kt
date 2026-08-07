package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.testcontainers.mq.KafkaServer
import io.bluetape4k.testcontainers.mq.Spring
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.listener.AcknowledgingMessageListener
import org.springframework.kafka.support.Acknowledgment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Kafka 4 singleton에서 실제 record를 받아 manual ack와 inbox dedup을 함께 검증합니다. */
@ResourceLock(
    value = AppointmentMessagingKafkaServerLauncher.RESOURCE_LOCK,
    mode = ResourceAccessMode.READ_WRITE,
)
class AppointmentKafkaConsumerIntegrationTest {

    @Test
    fun `Kafka event is processed once and duplicate redelivery is acknowledged`() {
        val kafka = AppointmentMessagingKafkaServerLauncher.kafka
        val topicName = "clinic.appointment.consumer.${UUID.randomUUID()}"
        val topic = AppointmentTopic(topicName)
        val key = AppointmentPartitionKeyFactory.create(7, 31, 42).value
        val value = AppointmentEventEnvelopeCodec().encode(envelope())
        val adminProperties = KafkaServer.Launcher.getProducerProperties(kafka)
            .mapNotNull { (property, propertyValue) -> propertyValue?.let { property to it } }
            .toMap()
        val kafkaAdmin = KafkaAdmin(adminProperties).apply {
            setAutoCreate(false)
            setOperationTimeout(5)
        }
        val producerFactory = KafkaServer.Launcher.Spring.getStringProducerFactory(kafka)
        val template = KafkaTemplate(producerFactory, true)

        try {
            kafkaAdmin.createOrModifyTopics(NewTopic(topicName, 1, 1.toShort()))
            template.send(topicName, key, value).get(10, java.util.concurrent.TimeUnit.SECONDS)
            val record = pollOne(kafka, topicName).shouldNotBeNull()

            val database = Database.connect(
                "jdbc:h2:mem:appointment_consumer_kafka_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                driver = "org.h2.Driver",
            )
            transaction(database) {
                SchemaUtils.create(
                    AppointmentConsumerInboxTable,
                    AppointmentConsumerQuarantineTable,
                    AppointmentConsumerRejectedRecordTable,
                )
            }
            val runtime = AppointmentConsumerRuntime(
                codec = AppointmentEventEnvelopeCodec(),
                inboxStore = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 2),
                allowedTopics = setOf(topic),
            )
            val calls = AtomicInteger()
            val acknowledgments = RecordingAcknowledgment()
            val identity = AppointmentConsumerIdentity(
                consumerId = AppointmentLogicalConsumerId("notification"),
                streamId = AppointmentLogicalStreamId("appointment-events"),
            )
            val handler = AppointmentConsumerHandler { _, _ -> calls.incrementAndGet() }

            runtime.consume(record, acknowledgments, identity, handler) shouldBeEqualTo AppointmentConsumerOutcome.PROCESSED
            runtime.consume(record, acknowledgments, identity, handler) shouldBeEqualTo AppointmentConsumerOutcome.DUPLICATE

            calls.get() shouldBeEqualTo 1
            acknowledgments.count shouldBeEqualTo 2
        } finally {
            template.destroy()
        }
    }

    @Test
    fun `container crash before ack is recovered by a second group member after rebalance`() {
        val kafka = AppointmentMessagingKafkaServerLauncher.kafka
        val topicName = "clinic.appointment.consumer.rebalance.${UUID.randomUUID()}"
        val topic = AppointmentTopic(topicName)
        val key = AppointmentPartitionKeyFactory.create(7, 31, 42).value
        val value = AppointmentEventEnvelopeCodec().encode(envelope())
        val adminProperties = KafkaServer.Launcher.getProducerProperties(kafka)
            .mapNotNull { (property, propertyValue) -> propertyValue?.let { property to it } }
            .toMap()
        val kafkaAdmin = KafkaAdmin(adminProperties).apply {
            setAutoCreate(false)
            setOperationTimeout(5)
        }
        val producerFactory = KafkaServer.Launcher.Spring.getStringProducerFactory(kafka)
        val template = KafkaTemplate(producerFactory, true)
        val groupId = "appointment-consumer-rebalance-${UUID.randomUUID()}"
        val firstCrashed = CountDownLatch(1)
        val recovered = CountDownLatch(1)
        val recoveredRuntimeReturned = CountDownLatch(1)
        val handlerCalls = AtomicInteger()
        val identity = AppointmentConsumerIdentity(
            consumerId = AppointmentLogicalConsumerId("notification"),
            streamId = AppointmentLogicalStreamId("appointment-events"),
        )
        val database = Database.connect(
            "jdbc:h2:mem:appointment_consumer_rebalance_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(
                AppointmentConsumerInboxTable,
                AppointmentConsumerQuarantineTable,
                AppointmentConsumerRejectedRecordTable,
            )
        }
        val runtime = AppointmentConsumerRuntime(
            codec = AppointmentEventEnvelopeCodec(),
            inboxStore = JdbcAppointmentConsumerInboxStore(database, maxAttempts = 3),
            allowedTopics = setOf(topic),
        )
        val firstFactory = listenerFactory(kafka, groupId)
        val secondFactory = listenerFactory(kafka, groupId)
        val firstContainer = firstFactory.createContainer(topicName).apply {
            containerProperties.setGroupId(groupId)
            containerProperties.setMessageListener(
                AcknowledgingMessageListener<String, String> { record, acknowledgment ->
                    runtime.consume(record, acknowledgment, identity) { _, _ ->
                        if (handlerCalls.getAndIncrement() == 0) {
                            firstCrashed.countDown()
                            throw AppointmentConsumerRetryableException("simulated consumer crash")
                        }
                    }
                },
            )
        }
        val secondContainer = secondFactory.createContainer(topicName).apply {
            containerProperties.setGroupId(groupId)
            containerProperties.setMessageListener(
                AcknowledgingMessageListener<String, String> { record, acknowledgment ->
                    AppointmentKafkaConsumerListener(
                        runtime = runtime,
                        identity = identity,
                        handler = { _, _ -> recovered.countDown() },
                    ).onMessage(record, acknowledgment)
                    recoveredRuntimeReturned.countDown()
                },
            )
        }

        try {
            kafkaAdmin.createOrModifyTopics(NewTopic(topicName, 1, 1.toShort()))
            template.send(topicName, key, value).get(10, TimeUnit.SECONDS)
            firstContainer.start()
            check(firstCrashed.await(20, TimeUnit.SECONDS)) { "first container did not reach crash handler" }
// error-handler retry 전에 실패한 member를 중지한다. commit되지 않은 offset은
// 같은 group의 두 번째 member에 재할당되어야 한다.
            firstContainer.stop()
            secondContainer.start()
            check(recovered.await(30, TimeUnit.SECONDS)) { "second member did not recover record" }
            check(recoveredRuntimeReturned.await(30, TimeUnit.SECONDS)) {
                "recovered runtime did not return after processing"
            }

            transaction(database) {
                AppointmentConsumerInboxTable
                    .selectAll()
                    .single()[AppointmentConsumerInboxTable.status]
                    .shouldBeEqualTo(AppointmentConsumerStatus.PROCESSED)
            }
            handlerCalls.get().shouldBeEqualTo(1)
        } finally {
            if (firstContainer.isRunning) firstContainer.stop()
            if (secondContainer.isRunning) secondContainer.stop()
            template.destroy()
        }
    }

    private fun listenerFactory(
        kafka: KafkaServer,
        groupId: String,
    ): ConcurrentKafkaListenerContainerFactory<String, String> =
        AppointmentKafkaConsumerConfiguration().appointmentKafkaConsumerContainerFactory(
            KafkaServer.Launcher.Spring.getStringConsumerFactory(
                KafkaServer.Launcher.getConsumerProperties(kafka).apply {
                    this[org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG] = groupId
                    this[org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
                },
            ),
        )

    private fun pollOne(kafka: KafkaServer, topic: String): ConsumerRecord<String, String>? {
        KafkaServer.Launcher.createStringConsumer(kafka).use { consumer ->
            consumer.subscribe(listOf(topic))
            repeat(30) {
                val records: ConsumerRecords<String, String> = consumer.poll(Duration.ofMillis(500))
                records.firstOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun envelope() = AppointmentEventEnvelope(
        eventId = AppointmentEventId("event-kafka-consumer-42"),
        eventType = AppointmentEventType.CREATED,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        occurredAt = Instant.parse("2026-08-06T00:00:00Z"),
        tenantGroupId = 7,
        clinicId = 31,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(42),
        correlationId = io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId("correlation-kafka-42"),
        causationId = io.bluetape4k.clinic.appointment.service.AppointmentCausationId("causation-kafka-42"),
        payload = AppointmentCreatedPayload(
            appointmentId = AppointmentAggregateId(42),
            version = 1,
            status = io.bluetape4k.clinic.appointment.statemachine.AppointmentState.CONFIRMED,
        ),
    )

    private class RecordingAcknowledgment : Acknowledgment {
        var count: Int = 0

        override fun acknowledge() {
            count += 1
        }
    }
}

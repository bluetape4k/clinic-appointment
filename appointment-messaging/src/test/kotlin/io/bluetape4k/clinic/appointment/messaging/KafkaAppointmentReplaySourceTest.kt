@file:Suppress("DEPRECATION")

package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.MockConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.TopicPartition
import org.springframework.kafka.core.ConsumerFactory
import io.bluetape4k.clinic.appointment.service.AppointmentCausationId
import io.bluetape4k.clinic.appointment.service.AppointmentCorrelationId
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Properties

class KafkaAppointmentReplaySourceTest {
    private val topic = AppointmentTopic("clinic.appointment.events")
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            "jdbc:h2:mem:replay-source-${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(
                AppointmentConsumerInboxTable,
                AppointmentConsumerQuarantineTable,
                AppointmentConsumerRejectedRecordTable,
            )
        }
    }

    @Test
    fun `source rejects caller controlled logical identity before opening a consumer`() {
        val expectedIdentity = AppointmentConsumerIdentity(
            AppointmentLogicalConsumerId("notification"),
            AppointmentLogicalStreamId("appointment-events"),
        )
        val source = KafkaAppointmentReplaySource(
            consumerFactory = NoopConsumerFactory(),
            topic = topic,
            runtime = AppointmentConsumerRuntime(
                codec = AppointmentEventEnvelopeCodec(),
                inboxStore = JdbcAppointmentConsumerInboxStore(database),
                allowedTopics = setOf(topic),
            ),
            handler = AppointmentConsumerHandler { _, _ -> error("handler must not be reached") },
            expectedIdentity = expectedIdentity,
        )

        assertFailsWith<IllegalArgumentException> {
            source.replay(
                request = AppointmentReplayRequest(
                    identity = AppointmentConsumerIdentity(
                        AppointmentLogicalConsumerId("statistics"),
                        AppointmentLogicalStreamId("appointment-events"),
                    ),
                    tenantGroupId = 7,
                    clinicId = 31,
                    approver = "operator-1",
                    fromOffset = 1,
                    toOffset = 1,
                    dryRun = false,
                ),
                execution = AppointmentReplayExecution("replay-group", expectedIdentity),
            )
        }
    }

    @Test
    fun `quarantined runtime outcome rejects replay instead of counting it`() {
        val expectedIdentity = identity()
        val consumer = MockConsumer<String, String>(OffsetResetStrategy.NONE)
        val partition = TopicPartition(topic.value, 0)
        consumer.updatePartitions(topic.value, listOf(PartitionInfo(topic.value, 0, null, null, null)))
        consumer.updateBeginningOffsets(mapOf(partition to 0L))
        consumer.updateEndOffsets(mapOf(partition to 1L))
        consumer.schedulePollTask {
            consumer.addRecord(
                org.apache.kafka.clients.consumer.ConsumerRecord(
                    topic.value,
                    0,
                    0L,
                    expectedIdentity.consumerId.value,
                    "{\"eventId\":\"invalid\"}",
                ),
            )
        }
        val source = KafkaAppointmentReplaySource(
            consumerFactory = FixedConsumerFactory(consumer),
            topic = topic,
            runtime = AppointmentConsumerRuntime(
                codec = AppointmentEventEnvelopeCodec(),
                inboxStore = JdbcAppointmentConsumerInboxStore(database),
                allowedTopics = setOf(topic),
            ),
            handler = AppointmentConsumerHandler { _, _ -> error("handler must not be reached") },
            expectedIdentity = expectedIdentity,
        )

        assertFailsWith<AppointmentReplayException> {
            source.replay(
                request = request(expectedIdentity),
                execution = AppointmentReplayExecution("replay-group", expectedIdentity),
            )
        }
        transaction(database) {
            AppointmentConsumerRejectedRecordTable.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `duplicate runtime outcome is excluded from replay count`() {
        val expectedIdentity = identity()
        val record = org.apache.kafka.clients.consumer.ConsumerRecord(
            topic.value,
            0,
            0L,
            AppointmentPartitionKeyFactory.create(7, 31, 42).value,
            AppointmentEventEnvelopeCodec().encode(envelope()),
        )
        val runtime = AppointmentConsumerRuntime(
            codec = AppointmentEventEnvelopeCodec(),
            inboxStore = JdbcAppointmentConsumerInboxStore(database),
            allowedTopics = setOf(topic),
        )
        runtime.consume(record, expectedIdentity) { _, _ -> }

        val consumer = MockConsumer<String, String>(OffsetResetStrategy.NONE)
        val partition = TopicPartition(topic.value, 0)
        consumer.updatePartitions(topic.value, listOf(PartitionInfo(topic.value, 0, null, null, null)))
        consumer.updateBeginningOffsets(mapOf(partition to 0L))
        consumer.updateEndOffsets(mapOf(partition to 1L))
        consumer.schedulePollTask { consumer.addRecord(record) }
        val source = KafkaAppointmentReplaySource(
            consumerFactory = FixedConsumerFactory(consumer),
            topic = topic,
            runtime = runtime,
            handler = AppointmentConsumerHandler { _, _ -> error("duplicate must not invoke handler") },
            expectedIdentity = expectedIdentity,
        )

        source.replay(
            request = request(expectedIdentity),
            execution = AppointmentReplayExecution("replay-group", expectedIdentity),
        ) shouldBeEqualTo 0
    }

    private fun identity() = AppointmentConsumerIdentity(
        AppointmentLogicalConsumerId("notification"),
        AppointmentLogicalStreamId("appointment-events"),
    )

    private fun request(identity: AppointmentConsumerIdentity) = AppointmentReplayRequest(
        identity = identity,
        tenantGroupId = 7,
        clinicId = 31,
        approver = "operator-1",
        fromOffset = 0,
        toOffset = 0,
        dryRun = false,
    )

    private fun envelope() = AppointmentEventEnvelope(
        eventId = AppointmentEventId("event-replay-42"),
        eventType = AppointmentEventType.CREATED,
        schemaVersion = AppointmentEventEnvelope.CURRENT_SCHEMA_VERSION,
        occurredAt = java.time.Instant.parse("2026-08-06T00:00:00Z"),
        tenantGroupId = 7,
        clinicId = 31,
        aggregateType = AppointmentEventEnvelope.AGGREGATE_TYPE,
        aggregateId = AppointmentAggregateId(42),
        correlationId = AppointmentCorrelationId("correlation-replay-42"),
        causationId = AppointmentCausationId("causation-replay-42"),
        payload = AppointmentCreatedPayload(
            appointmentId = AppointmentAggregateId(42),
            version = 1,
            status = AppointmentState.CONFIRMED,
        ),
    )

    private class FixedConsumerFactory(
        private val consumer: Consumer<String, String>,
    ) : ConsumerFactory<String, String> {
        override fun createConsumer(
            groupId: String?,
            clientIdPrefix: String?,
            clientIdSuffix: String?,
            properties: Properties?,
        ): Consumer<String, String> = consumer

        override fun isAutoCommit(): Boolean = false
    }

    private class NoopConsumerFactory : ConsumerFactory<String, String> {
        override fun createConsumer(
            groupId: String?,
            clientIdPrefix: String?,
            clientIdSuffix: String?,
            properties: Properties?,
        ): Consumer<String, String> = error("consumer must not be created")

        override fun isAutoCommit(): Boolean = false
    }
}

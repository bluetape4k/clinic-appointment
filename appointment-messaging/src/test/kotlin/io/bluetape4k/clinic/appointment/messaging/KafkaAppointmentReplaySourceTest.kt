package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import org.apache.kafka.clients.consumer.Consumer
import org.springframework.kafka.core.ConsumerFactory
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.util.Properties

class KafkaAppointmentReplaySourceTest {
    @Test
    fun `source rejects caller controlled logical identity before opening a consumer`() {
        val expectedIdentity = AppointmentConsumerIdentity(
            AppointmentLogicalConsumerId("notification"),
            AppointmentLogicalStreamId("appointment-events"),
        )
        val source = KafkaAppointmentReplaySource(
            consumerFactory = NoopConsumerFactory(),
            topic = AppointmentTopic("clinic.appointment.events"),
            runtime = AppointmentConsumerRuntime(
                codec = AppointmentEventEnvelopeCodec(),
                inboxStore = JdbcAppointmentConsumerInboxStore(
                    Database.connect("jdbc:h2:mem:replay-source-test", driver = "org.h2.Driver"),
                ),
                allowedTopics = setOf(AppointmentTopic("clinic.appointment.events")),
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

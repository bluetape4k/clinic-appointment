package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.health.contributor.Status
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.junit.jupiter.api.Test
import java.util.function.Supplier
import java.time.Duration
import javax.sql.DataSource
import org.h2.jdbcx.JdbcDataSource

class AppointmentMessagingPropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AppointmentMessagingAutoConfiguration::class.java))

    @Test
    fun `lease budget requires safety margin`() {
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(
                leaseDuration = Duration.ofSeconds(10),
                sendTimeout = Duration.ofSeconds(5),
            )
        }
    }

    @Test
    fun `sequential claim window must fit inside lease`() {
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(
                leaseDuration = Duration.ofSeconds(60),
                sendTimeout = Duration.ofSeconds(5),
                claimSize = 10,
            )
        }
    }

    @Test
    fun `topic and allow list are validated`() {
        assertFailsWith<IllegalArgumentException> { AppointmentTopic("clinic/appointment") }
        val topic = AppointmentTopic("clinic.appointment.events")
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(topic = topic, allowedTopics = emptySet())
        }
    }

    @Test
    fun `Spring Boot properties bind through typed topic validation`() {
        contextRunner
            .withPropertyValues(
                "appointment.messaging.topic=clinic.appointment.v2",
                "appointment.messaging.allowed-topics[0]=clinic.appointment.v2",
                "appointment.messaging.lease-duration=90s",
                "appointment.messaging.send-timeout=5s",
                "appointment.messaging.claim-size=12",
                "appointment.messaging.max-attempts=4",
                "appointment.messaging.enabled=false",
            )
            .run { context ->
                val properties = context.getBean(AppointmentMessagingProperties::class.java)
                properties.topic shouldBeEqualTo AppointmentTopic("clinic.appointment.v2")
                properties.allowedTopics shouldBeEqualTo setOf(AppointmentTopic("clinic.appointment.v2"))
                properties.leaseDuration shouldBeEqualTo Duration.ofSeconds(90)
                properties.claimSize shouldBeEqualTo 12
                properties.maxAttempts shouldBeEqualTo 4
                properties.enabled shouldBeEqualTo false
        }
    }

    @Test
    fun `V22 schema failure stops writer construction during context startup`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:appointment_auto_config_missing_${System.nanoTime()};DB_CLOSE_DELAY=-1")
        }

        contextRunner
            .withBean("dataSource", DataSource::class.java, Supplier { dataSource })
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `producer contract cannot be weakened`() {
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(producerAcks = "1")
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(producerEnableIdempotence = false)
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(producerAllowAutoCreateTopics = true)
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentMessagingProperties(producerSecurityProtocol = "SSL")
        }
    }

    @Test
    fun `producer configuration applies bounded kafka defaults to the factory`() {
        val properties = AppointmentMessagingProperties()
        val factory = DefaultKafkaProducerFactory<Any, Any>(emptyMap())

        val configuration = AppointmentKafkaProducerConfiguration.apply(properties, factory)

        configuration.kafkaProperties[ProducerConfig.ACKS_CONFIG] shouldBeEqualTo "all"
        configuration.kafkaProperties[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] shouldBeEqualTo true
        configuration.contractProperties[ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG] shouldBeEqualTo false
        configuration.kafkaProperties[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] shouldBeEqualTo "PLAINTEXT"
        configuration.kafkaProperties[ProducerConfig.MAX_BLOCK_MS_CONFIG] shouldBeEqualTo 5_000
        factory.configurationProperties[ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG] shouldBeEqualTo 15_000
    }

    @Test
    fun `secured producer credentials are resolved at the bean boundary`() {
        val properties = AppointmentMessagingProperties(
            producerSecurityProtocol = "SASL_SSL",
            producerCredentialReference = "secret://kafka/appointment-producer",
        )
        val factory = DefaultKafkaProducerFactory<Any, Any>(emptyMap())
        var reference: String? = null

        val configuration = AppointmentKafkaProducerConfiguration.apply(
            properties = properties,
            producerFactory = factory,
            credentialResolver = AppointmentKafkaCredentialResolver {
                reference = it
                mapOf("sasl.mechanism" to "SCRAM-SHA-512", "sasl.jaas.config" to "opaque-secret")
            },
        )

        reference shouldBeEqualTo "secret://kafka/appointment-producer"
        configuration.appliedCredentialKeys shouldBeEqualTo setOf("sasl.mechanism", "sasl.jaas.config")
        configuration.kafkaProperties.containsKey("sasl.jaas.config").shouldBeFalse()
        factory.configurationProperties["sasl.jaas.config"] shouldBeEqualTo "opaque-secret"
    }

    @Test
    fun `secured producer fails without a resolver or with an unsafe key`() {
        val properties = AppointmentMessagingProperties(
            producerSecurityProtocol = "SSL",
            producerCredentialReference = "secret://kafka/appointment-producer",
        )
        assertFailsWith<IllegalArgumentException> {
            AppointmentKafkaProducerConfiguration.apply(
                properties = properties,
                producerFactory = DefaultKafkaProducerFactory<Any, Any>(emptyMap()),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AppointmentKafkaProducerConfiguration.apply(
                properties = properties,
                producerFactory = DefaultKafkaProducerFactory<Any, Any>(emptyMap()),
                credentialResolver = AppointmentKafkaCredentialResolver {
                    mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to "secret")
                },
            )
        }
    }

    @Test
    fun `readiness probe exposes broker and pause transitions`() {
        val probe = AppointmentMessagingReadinessProbe()
        probe.snapshot().ready.shouldBeFalse()

        probe.markBrokerAvailable()
        probe.snapshot().ready.shouldBeTrue()

        probe.markRelayPaused()
        probe.snapshot().ready.shouldBeFalse()
        probe.markRelayResumed()
        probe.markRelayHeld()
        probe.snapshot().relayHeld.shouldBeTrue()
        probe.snapshot().ready.shouldBeFalse()
        probe.releaseRelayHold()
        probe.markBrokerUnavailable()
        probe.snapshot().ready.shouldBeFalse()
    }

    @Test
    fun `schema and serializer readiness gates are fail closed`() {
        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)

        probe.markSchemaInvalid()
        probe.snapshot().ready.shouldBeFalse()
        probe.markSchemaAvailable()
        probe.markSerializerInvalid()
        probe.snapshot().ready.shouldBeFalse()
        probe.markSerializerAvailable()
        probe.snapshot().ready.shouldBeTrue()
    }

    @Test
    fun `disabled relay is visible in readiness`() {
        val probe = AppointmentMessagingReadinessProbe(enabled = false, brokerAvailable = true)

        probe.snapshot().enabled.shouldBeFalse()
        probe.snapshot().ready.shouldBeFalse()
        probe.markEnabled()
        probe.snapshot().ready.shouldBeTrue()
    }

    @Test
    fun `health indicator keeps broker outage out of liveness contract`() {
        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = false)
        val health = AppointmentMessagingHealthIndicator(probe).health()

        health.status shouldBeEqualTo Status.OUT_OF_SERVICE
        health.details["brokerAvailable"] shouldBeEqualTo false
        health.details.keys.none { key ->
            key.contains("tenant", ignoreCase = true) ||
                key.contains("clinic", ignoreCase = true) ||
                key.contains("appointment", ignoreCase = true)
        }.shouldBeTrue()
    }

    @Test
    fun `health indicator marks schema contract failure down`() {
        val probe = AppointmentMessagingReadinessProbe(brokerAvailable = true)
        probe.markSchemaInvalid()

        AppointmentMessagingHealthIndicator(probe).health().status shouldBeEqualTo Status.DOWN
    }

    @Test
    fun `micrometer metrics keep only bounded labels`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAppointmentOutboxMetrics(registry)

        metrics.publishSuccess(AppointmentEventType.CREATED)
        metrics.publishRetry(AppointmentOutboxRelay.FAILURE_BROKER_UNAVAILABLE)
        metrics.contractRejected(AppointmentOutboxRelay.FAILURE_INVALID_PAYLOAD)
        metrics.leaseLost()

        registry.get("appointment_outbox_publish_success")
            .tag("event_type", AppointmentEventType.CREATED.wireName)
            .counter().count() shouldBeEqualTo 1.0
        registry.get("appointment_outbox_retry")
            .tag("failure_code", AppointmentOutboxRelay.FAILURE_BROKER_UNAVAILABLE)
            .counter().count() shouldBeEqualTo 1.0
        registry.get("appointment_outbox_lease_lost").counter().count() shouldBeEqualTo 1.0
    }

    @Test
    fun `micrometer metrics expose bounded backlog gauges`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerAppointmentOutboxMetrics(registry)

        metrics.recordBacklog(
            AppointmentOutboxBacklogSnapshot(
                pending = 7,
                oldestAgeSeconds = 12.5,
                partitionSkew = 2.0,
            ),
        )

        registry.get("appointment_outbox_pending").gauge().value() shouldBeEqualTo 7.0
        registry.get("appointment_outbox_oldest_age_seconds").gauge().value() shouldBeEqualTo 12.5
        registry.get("appointment_outbox_partition_skew").gauge().value() shouldBeEqualTo 2.0
    }

    @Test
    fun `micrometer metrics reject unbounded failure labels`() {
        val metrics = MicrometerAppointmentOutboxMetrics(SimpleMeterRegistry())

        assertFailsWith<IllegalArgumentException> {
            metrics.publishFailed("raw-secret-${System.nanoTime()}")
        }
    }
}

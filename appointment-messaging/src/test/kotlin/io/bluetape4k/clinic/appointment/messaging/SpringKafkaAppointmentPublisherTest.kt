package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.every
import io.mockk.mockk
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Kafka4 suspend helper와 publisher 수명 주기의 취소 경계를 검증한다. */
class SpringKafkaAppointmentPublisherTest {

    @Test
    fun `publishing adapts the Kafka4 send result to a completion stage`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        val kafkaAdmin = mockk<KafkaAdmin>(relaxed = true)
        val brokerResult = mockk<SendResult<String, String>>(relaxed = true)
        val sendFuture = CompletableFuture.completedFuture(brokerResult)
        every {
            kafkaTemplate.send(
                "appointments",
                "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924",
                "payload",
            )
        } returns sendFuture
        val publisher = SpringKafkaAppointmentPublisher(kafkaTemplate, kafkaAdmin)

        try {
            val published = publisher.publish(
                topic = AppointmentTopic("appointments"),
                key = AppointmentPartitionKey("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"),
                value = "payload",
            ).toCompletableFuture()

            (published !== sendFuture).shouldBeTrue()
            published.get(5, TimeUnit.SECONDS).shouldBeEqualTo(brokerResult)
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `broker failure completes the adapted stage exceptionally`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        val kafkaAdmin = mockk<KafkaAdmin>(relaxed = true)
        val failure = IllegalStateException("broker unavailable")
        val sendFuture = CompletableFuture<SendResult<String, String>>().also {
            it.completeExceptionally(failure)
        }
        every {
            kafkaTemplate.send(
                "appointments",
                "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924",
                "payload",
            )
        } returns sendFuture
        val publisher = SpringKafkaAppointmentPublisher(kafkaTemplate, kafkaAdmin)

        try {
            val published = publisher.publish(
                topic = AppointmentTopic("appointments"),
                key = AppointmentPartitionKey("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"),
                value = "payload",
            ).toCompletableFuture()

            assertFailsWith<ExecutionException> { published.get(5, TimeUnit.SECONDS) }
                .cause.shouldBeEqualTo(failure)
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `cancelling the returned stage cancels the Kafka4 send`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        val kafkaAdmin = mockk<KafkaAdmin>(relaxed = true)
        val sendStarted = CountDownLatch(1)
        val sendFuture = CompletableFuture<SendResult<String, String>>()
        every {
            kafkaTemplate.send(
                "appointments",
                "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924",
                "payload",
            )
        } answers {
            sendStarted.countDown()
            sendFuture
        }
        val publisher = SpringKafkaAppointmentPublisher(kafkaTemplate, kafkaAdmin)

        try {
            val published = publisher.publish(
                topic = AppointmentTopic("appointments"),
                key = AppointmentPartitionKey("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"),
                value = "payload",
            ).toCompletableFuture()

            sendStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            published.cancel(false).shouldBeTrue()

            assertFailsWith<CancellationException> { sendFuture.get(5, TimeUnit.SECONDS) }
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `caller timeout leaves the in-flight stage cancellable`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        val kafkaAdmin = mockk<KafkaAdmin>(relaxed = true)
        val sendStarted = CountDownLatch(1)
        val sendFuture = CompletableFuture<SendResult<String, String>>()
        every {
            kafkaTemplate.send(
                "appointments",
                "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924",
                "payload",
            )
        } answers {
            sendStarted.countDown()
            sendFuture
        }
        val publisher = SpringKafkaAppointmentPublisher(kafkaTemplate, kafkaAdmin)

        try {
            val published = publisher.publish(
                topic = AppointmentTopic("appointments"),
                key = AppointmentPartitionKey("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"),
                value = "payload",
            ).toCompletableFuture()

            sendStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            assertFailsWith<TimeoutException> { published.get(50, TimeUnit.MILLISECONDS) }

            publisher.close()
            assertFailsWith<CancellationException> { sendFuture.get(5, TimeUnit.SECONDS) }
        } finally {
            publisher.close()
        }
    }

    @Test
    fun `closing publisher cancels an in-flight Kafka4 send`() {
        val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
        val kafkaAdmin = mockk<KafkaAdmin>(relaxed = true)
        val sendStarted = CountDownLatch(1)
        val sendFuture = CompletableFuture<SendResult<String, String>>()
        every {
            kafkaTemplate.send(
                "appointments",
                "tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924",
                "payload",
            )
        } answers {
            sendStarted.countDown()
            sendFuture
        }
        val publisher = SpringKafkaAppointmentPublisher(kafkaTemplate, kafkaAdmin)

        try {
            val published = publisher.publish(
                topic = AppointmentTopic("appointments"),
                key = AppointmentPartitionKey("tenant-1:CLINIC:clinic-31:APPOINTMENT:apt-924"),
                value = "payload",
            ).toCompletableFuture()

            sendStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
            publisher.close()

            assertFailsWith<CancellationException> { published.get(5, TimeUnit.SECONDS) }
            assertFailsWith<CancellationException> { sendFuture.get(5, TimeUnit.SECONDS) }
        } finally {
            publisher.close()
        }
    }
}

package io.bluetape4k.clinic.appointment.api.profile

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProfileAssessmentResilienceTest {

    @Test
    fun `동시성 permit이 포화되면 외부 호출 없이 retryable backpressure로 넘긴다`() {
        ProfileAssessmentHttpFixture().use { fixture ->
            val entered = CountDownLatch(2)
            val release = CountDownLatch(1)
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            fixture.handle { exchange ->
                val current = active.incrementAndGet()
                maxActive.accumulateAndGet(current, ::maxOf)
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                active.decrementAndGet()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            val registry = SimpleMeterRegistry()
            val client = client(fixture, registry, maxConcurrency = 2)
            val executor = Executors.newFixedThreadPool(3)

            try {
                val running = List(2) {
                    executor.submit<ProfileAssessmentException> {
                        runCatching { client.fetch(request()) }.exceptionOrNull() as ProfileAssessmentException
                    }
                }
                entered.await(5, TimeUnit.SECONDS).shouldBeTrue()

                val saturated = runCatching { client.fetch(request()) }
                    .exceptionOrNull() as ProfileAssessmentException

                saturated.code shouldBeEqualTo ProfileAssessmentFailureCode.CONCURRENCY_SATURATED
                saturated.retryable.shouldBeTrue()
                fixture.requestCount.get() shouldBeEqualTo 2
                maxActive.get() shouldBeEqualTo 2
                registry.find(ProfileAssessmentClientMetrics.IN_FLIGHT).gauge().shouldNotBeNull().value() shouldBeEqualTo 2.0
                registry.find(ProfileAssessmentClientMetrics.REQUESTS)
                    .tag("result", "saturated")
                    .counter().shouldNotBeNull().count() shouldBeEqualTo 1.0

                release.countDown()
                running.forEach { future ->
                    future.get(5, TimeUnit.SECONDS).code shouldBeEqualTo ProfileAssessmentFailureCode.UPSTREAM_UNAVAILABLE
                }
                registry.find(ProfileAssessmentClientMetrics.IN_FLIGHT).gauge().shouldNotBeNull().value() shouldBeEqualTo 0.0
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `timeout metric과 모든 tag는 민감정보 없이 닫힌 값만 사용한다`() {
        ProfileAssessmentHttpFixture().use { fixture ->
            fixture.respond(200, assessmentJson(), delay = Duration.ofMillis(200))
            val registry = SimpleMeterRegistry()
            val client = client(
                fixture,
                registry,
                maxConcurrency = 1,
                readTimeout = Duration.ofMillis(30),
            )

            val timeout = runCatching { client.fetch(request()) }
                .exceptionOrNull() as ProfileAssessmentException

            timeout.code shouldBeEqualTo ProfileAssessmentFailureCode.TIMEOUT
            timeout.retryable.shouldBeTrue()
            registry.find(ProfileAssessmentClientMetrics.REQUESTS)
                .tag("result", "timeout")
                .counter().shouldNotBeNull().count() shouldBeEqualTo 1.0
            val sensitiveValues = setOf(
                request().patientReferenceFingerprint,
                request().assessmentReference,
                request().correlationId,
            )
            registry.meters
                .flatMap { meter -> meter.id.tags.map { it.value } }
                .forEach { tagValue ->
                    (tagValue in sensitiveValues).shouldBeFalse()
                }
        }
    }

    private fun client(
        fixture: ProfileAssessmentHttpFixture,
        registry: SimpleMeterRegistry,
        maxConcurrency: Int,
        readTimeout: Duration = Duration.ofSeconds(1),
    ) = RestClientProfileAssessmentClient(
        baseUrl = fixture.baseUri,
        allowedHosts = setOf("127.0.0.1"),
        connectTimeout = Duration.ofSeconds(1),
        readTimeout = readTimeout,
        maxResponseBytes = 16 * 1024,
        maxConcurrency = maxConcurrency,
        meterRegistry = registry,
        addressResolver = ProfileAssessmentAddressResolver {
            listOf(InetAddress.getLoopbackAddress())
        },
        allowUnsafeTestEndpoint = true,
    )
}

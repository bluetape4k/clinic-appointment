package io.bluetape4k.clinic.appointment.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.clinic.appointment.api.notification.NotificationOutboxScaleFixture
import io.bluetape4k.clinic.appointment.api.notification.NotificationOutboxScaleProfile
import io.bluetape4k.clinic.appointment.api.notification.NotificationOutboxScaleResult
import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 알림 outbox의 대형 병원 편중·공정성·backpressure 기준을 실행 가능한 assertion으로 검증합니다.
 *
 * `notificationOutbox.scale` 시스템 속성으로 profile을 선택합니다. warm-up 뒤 동일한 고정
 * dataset을 세 번 측정하며 fixture의 안전성 또는 SLO 위반은 HTTP 500으로 전환됩니다.
 */
class NotificationOutboxScaleSimulation : Simulation() {
    private val profile = NotificationOutboxScaleProfile.from(System.getProperty("notificationOutbox.scale"))
    private val fixture = NotificationOutboxScaleFixture(profile)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val measurementSequence = AtomicInteger()
    private val measurements = CopyOnWriteArrayList<NotificationOutboxScaleResult>()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/notification-outbox/warmup", ::handleWarmup)
        createContext("/notification-outbox/measure", ::handleMeasurement)
        this.executor = this@NotificationOutboxScaleSimulation.executor
    }
    private val baseUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"
    private val measurementRequestName = "${profile.name.lowercase()} notification outbox"

    init {
        val chain = exec(
            http("${profile.name.lowercase()} warmup")
                .post("/notification-outbox/warmup")
                .check(status().`is`(HTTP_OK)),
        ).repeat(MEASUREMENT_COUNT).on(
            exec(
                http(measurementRequestName)
                    .post("/notification-outbox/measure")
                    .check(status().`is`(HTTP_OK)),
            ),
        )
        setUp(
            scenario("Notification outbox ${profile.name.lowercase()}")
                .exec(chain)
                .injectOpen(atOnceUsers(1)),
        ).protocols(http.baseUrl(baseUrl))
            .assertions(
                global().failedRequests().count().`is`(0),
                details(measurementRequestName).failedRequests().count().`is`(0),
                details(measurementRequestName).responseTime().percentile3().lte(WALL_CLOCK_P95_MILLIS),
            )
    }

    override fun before() {
        server.start()
    }

    override fun after() {
        try {
            fixture.writeReport(measurements.sortedBy(NotificationOutboxScaleResult::measurement))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun handleWarmup(exchange: HttpExchange) {
        respond(exchange, fixture.run(measurement = 0), record = false)
    }

    private fun handleMeasurement(exchange: HttpExchange) {
        respond(exchange, fixture.run(measurementSequence.incrementAndGet()), record = true)
    }

    private fun respond(
        exchange: HttpExchange,
        result: NotificationOutboxScaleResult,
        record: Boolean,
    ) {
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(HTTP_METHOD_NOT_ALLOWED, -1)
            exchange.close()
            return
        }
        if (record) measurements += result
        val body = result.toJson().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(if (result.verified) HTTP_OK else HTTP_INTERNAL_ERROR, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private companion object {
        const val MEASUREMENT_COUNT = 3
        const val WALL_CLOCK_P95_MILLIS = 5_000
        const val HTTP_OK = 200
        const val HTTP_INTERNAL_ERROR = 500
        const val HTTP_METHOD_NOT_ALLOWED = 405
    }
}

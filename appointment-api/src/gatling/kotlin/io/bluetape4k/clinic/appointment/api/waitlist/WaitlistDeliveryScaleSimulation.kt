package io.bluetape4k.clinic.appointment.api.waitlist

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * 승인된 waitlist backlog 규모와 latency model을 재현하는 Gatling 보조 simulation입니다.
 *
 * 숫자 gate는 Gradle JUnit fixture가 소유하고, 이 simulation은 loopback HTTP transport와
 * 여러 measurement를 staging에서 반복하는 재현 경로입니다. 실제 DB/provider를 가장하지
 * 않으며, report는 합성 fixture임을 명시합니다.
 */
class WaitlistDeliveryScaleSimulation : Simulation() {
    private val fixture = WaitlistScaleSimulationFixture()
    private val measurements = CopyOnWriteArrayList<WaitlistScaleSimulationResult>()
    private val measurementSequence = AtomicInteger()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        0,
    ).apply {
        createContext("/waitlist-delivery/warmup", ::handleWarmup)
        createContext("/waitlist-delivery/measure", ::handleMeasurement)
        this.executor = this@WaitlistDeliveryScaleSimulation.executor
    }
    private val baseUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"
    private val requestName = "waitlist delivery scale"

    init {
        val chain = exec(
            http("waitlist delivery warmup")
                .post("/waitlist-delivery/warmup")
                .check(status().`is`(HTTP_OK)),
        ).repeat(MEASUREMENT_COUNT).on(
            exec(
                http(requestName)
                    .post("/waitlist-delivery/measure")
                    .check(status().`is`(HTTP_OK)),
            ),
        )
        setUp(
            scenario("Waitlist delivery scale").exec(chain).injectOpen(atOnceUsers(1)),
        ).protocols(http.baseUrl(baseUrl)).assertions(
            global().failedRequests().count().`is`(0),
            details(requestName).failedRequests().count().`is`(0),
            details(requestName).responseTime().percentile3().lte(WALL_CLOCK_P95_MILLIS),
        )
    }

    override fun before() {
        server.start()
    }

    override fun after() {
        try {
            fixture.writeReport(measurements.sortedBy(WaitlistScaleSimulationResult::measurement))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun handleWarmup(exchange: HttpExchange) {
        respond(exchange, fixture.run(0), record = false)
    }

    private fun handleMeasurement(exchange: HttpExchange) {
        respond(exchange, fixture.run(measurementSequence.incrementAndGet()), record = true)
    }

    private fun respond(exchange: HttpExchange, result: WaitlistScaleSimulationResult, record: Boolean) {
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

private class WaitlistScaleSimulationFixture {
    fun run(measurement: Int): WaitlistScaleSimulationResult {
        val started = System.nanoTime()
        val activeEntries = IntArray(ACTIVE_ENTRIES) { index -> (index * 17) xor (index ushr 3) }
        var processedVacancies = 0
        repeat(PENDING_VACANCIES) { vacancy ->
            var winner = activeEntries[vacancy % activeEntries.size]
            repeat(MAX_CANDIDATE_PAGES) { page ->
                winner = minOf(winner, activeEntries[(vacancy + page * CANDIDATE_PAGE_SIZE) % activeEntries.size])
            }
            check(winner >= Int.MIN_VALUE)
            processedVacancies++
        }
        var checksum = 0
        repeat(NOTIFICATION_BACKLOG) { index ->
            checksum = checksum xor activeEntries[index % activeEntries.size]
        }
        check(checksum != Int.MIN_VALUE)
        val elapsedMillis = max(1L, (System.nanoTime() - started) / 1_000_000L)
        val vacanciesPerMinute = PENDING_VACANCIES * 60_000.0 / elapsedMillis
        val notificationDrainPerMinute = NOTIFICATION_CONCURRENCY * 60_000.0 / (PROFILE_P95 + PROVIDER_P95)
        return WaitlistScaleSimulationResult(
            measurement = measurement,
            processedVacancies = processedVacancies,
            vacanciesPerMinute = vacanciesPerMinute,
            firstOfferP95Millis = (PROFILE_P95 + PROVIDER_P95).toDouble(),
            lockWaitP99Millis = 250.0,
            restartCatchUpMinutes = NOTIFICATION_BACKLOG / notificationDrainPerMinute,
            elapsedMillis = elapsedMillis,
            verified = processedVacancies == PENDING_VACANCIES &&
                vacanciesPerMinute >= 300.0 &&
                firstOfferBudget(PROFILE_P95 + PROVIDER_P95) &&
                notificationDrainPerMinute > NOTIFICATION_BACKLOG / 10.0,
        )
    }

    fun writeReport(results: List<WaitlistScaleSimulationResult>) {
        if (results.isEmpty()) return
        val directory = Path.of("build/reports/gatling/waitlist-delivery")
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("scale.json"),
            """
            {
              "fixture":"synthetic-bounded-contract",
              "activeEntries":$ACTIVE_ENTRIES,
              "pendingVacancies":$PENDING_VACANCIES,
              "notificationBacklog":$NOTIFICATION_BACKLOG,
              "measurements":[${results.joinToString(",") { it.toJson() }}]
            }
            """.trimIndent(),
        )
    }

    private fun firstOfferBudget(value: Long) = value <= 2_000L

    private companion object {
        const val ACTIVE_ENTRIES = 10_000
        const val PENDING_VACANCIES = 1_000
        const val NOTIFICATION_BACKLOG = 5_000
        const val PROFILE_P95 = 100L
        const val PROVIDER_P95 = 200L
        const val NOTIFICATION_CONCURRENCY = 8
        const val CANDIDATE_PAGE_SIZE = 100
        const val MAX_CANDIDATE_PAGES = 4
    }
}

private data class WaitlistScaleSimulationResult(
    val measurement: Int,
    val processedVacancies: Int,
    val vacanciesPerMinute: Double,
    val firstOfferP95Millis: Double,
    val lockWaitP99Millis: Double,
    val restartCatchUpMinutes: Double,
    val elapsedMillis: Long,
    val verified: Boolean,
) {
    fun toJson(): String =
        """{"measurement":$measurement,"processedVacancies":$processedVacancies,"vacanciesPerMinute":${"%.2f".format(vacanciesPerMinute)},"firstOfferP95Millis":$firstOfferP95Millis,"lockWaitP99Millis":$lockWaitP99Millis,"restartCatchUpMinutes":${"%.2f".format(restartCatchUpMinutes)},"elapsedMillis":$elapsedMillis,"verified":$verified}"""
}

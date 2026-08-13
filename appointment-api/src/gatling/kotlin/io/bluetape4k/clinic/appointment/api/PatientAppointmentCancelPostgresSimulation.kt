package io.bluetape4k.clinic.appointment.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.clinic.appointment.api.commitment.CancellationLoadOperation
import io.bluetape4k.clinic.appointment.api.commitment.PatientAppointmentCancelPostgresFixture
import io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers
import io.gatling.javaapi.core.CoreDsl.during
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.percent
import io.gatling.javaapi.core.CoreDsl.randomSwitch
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Issue #34의 실제 PostgreSQL cancel command latency를 측정하는 Type-F Gatling lane입니다.
 *
 * warm-up 30초, 측정 5분, 100개 appointment dataset, 같은 appointment 10명/상이 appointment
 * 20명의 closed concurrency를 기본값으로 사용한다. 로컬 smoke에서는
 * `-Dissue34.warmupSeconds=1 -Dissue34.measureSeconds=2`를 사용할 수 있지만, comparator용
 * 증거에는 기본 30초/300초 실행만 인정한다.
 */
open class PatientAppointmentCancelPostgresSimulation : Simulation() {
    private val fixture = PatientAppointmentCancelPostgresFixture()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val warmupSeconds = systemSeconds("issue34.warmupSeconds", WARMUP_SECONDS)
    private val measureSeconds = systemSeconds("issue34.measureSeconds", MEASURE_SECONDS)
    private val totalSeconds = warmupSeconds + measureSeconds
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/cancel", ::handleCancel)
            this.executor = this@PatientAppointmentCancelPostgresSimulation.executor
        }
    private val baseUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"
    private val sameAppointmentChain =
        randomSwitch().on(
            percent(25.0).then(cancelRequest(CancellationLoadOperation.SUCCESS_PATIENT, "same")),
            percent(25.0).then(cancelRequest(CancellationLoadOperation.SUCCESS_ADMIN, "same")),
            percent(20.0).then(cancelRequest(CancellationLoadOperation.IDEMPOTENT_REPLAY, "same")),
            percent(20.0).then(cancelRequest(CancellationLoadOperation.PRECONDITION_CONFLICT, "same")),
            percent(10.0).then(cancelRequest(CancellationLoadOperation.RETRY_EXHAUSTION, "same")),
        )
    private val differentAppointmentChain =
        randomSwitch().on(
            percent(25.0).then(cancelRequest(CancellationLoadOperation.SUCCESS_PATIENT, "different")),
            percent(25.0).then(cancelRequest(CancellationLoadOperation.SUCCESS_ADMIN, "different")),
            percent(20.0).then(cancelRequest(CancellationLoadOperation.IDEMPOTENT_REPLAY, "different")),
            percent(20.0).then(cancelRequest(CancellationLoadOperation.PRECONDITION_CONFLICT, "different")),
            percent(10.0).then(cancelRequest(CancellationLoadOperation.RETRY_EXHAUSTION, "different")),
        )

    init {
        val total = Duration.ofSeconds(totalSeconds)
        val sameAppointment =
            scenario("Issue 34 cancel same appointment")
                .exec(during(Duration.ofSeconds(warmupSeconds)).on(sameAppointmentChain))
                .exec(during(Duration.ofSeconds(measureSeconds)).on(sameAppointmentChain))
        val differentAppointments =
            scenario("Issue 34 cancel different appointments")
                .exec(during(Duration.ofSeconds(warmupSeconds)).on(differentAppointmentChain))
                .exec(during(Duration.ofSeconds(measureSeconds)).on(differentAppointmentChain))

        val setup = setUp(
            sameAppointment.injectClosed(constantConcurrentUsers(SAME_APPOINTMENT_CONCURRENCY).during(total)),
            differentAppointments.injectClosed(constantConcurrentUsers(DIFFERENT_APPOINTMENT_CONCURRENCY).during(total)),
        ).protocols(http.baseUrl(baseUrl))
        if (!System.getProperty("issue34.smoke").toBoolean()) {
            setup.assertions(
                global().failedRequests().count().`is`(0),
                global().responseTime().percentile3().lte(P95_ABSOLUTE_LIMIT_MILLIS),
                global().responseTime().percentile4().lte(P99_ABSOLUTE_LIMIT_MILLIS),
            )
        }
    }

    override fun before() {
        server.start()
        fixture.startMeasurementWindow(warmupSeconds)
    }

    override fun after() {
        fixture.writeReport(
            path = Path.of(System.getProperty("issue34.candidate", DEFAULT_CANDIDATE_PATH)),
            runNumber = System.getProperty("issue34.run")?.toIntOrNull()?.coerceIn(1, 3) ?: 1,
        )
        fixture.close()
        server.stop(0)
        executor.shutdownNow()
    }

    private fun cancelRequest(operation: CancellationLoadOperation, lane: String) =
        exec(
            http("cancel ${operation.name.lowercase()}")
                .post("/cancel/$lane?operation=${operation.name}")
                .check(status().`in`(HTTP_OK, HTTP_PRECONDITION_FAILED, HTTP_SERVICE_UNAVAILABLE)),
        ).pause(Duration.ofMillis(systemMillis("issue34.pauseMillis", PAUSE_MILLIS)))

    private fun handleCancel(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate { parameter ->
            parameter.substringBefore('=') to parameter.substringAfter('=', "")
        }
        val operation = query["operation"]?.let { runCatching { CancellationLoadOperation.valueOf(it) }.getOrNull() }
        if (exchange.requestMethod != "POST" || operation == null) {
            exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            exchange.close()
            return
        }
        val sameAppointment = exchange.requestURI.path.endsWith("/same")
        val observation = fixture.execute(operation, sameAppointment)
        val body =
            """
            {"operation":"${operation.name}","status":${observation.statusCode},"errorCode":${jsonValue(observation.errorCode)},"scenarioMatched":${observation.scenarioMatched}}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(observation.statusCode, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun jsonValue(value: String?): String = value?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"

    private companion object {
        const val WARMUP_SECONDS = 30L
        const val MEASURE_SECONDS = 300L
        const val SAME_APPOINTMENT_CONCURRENCY = 10
        const val DIFFERENT_APPOINTMENT_CONCURRENCY = 20
        const val P95_ABSOLUTE_LIMIT_MILLIS = 500
        const val P99_ABSOLUTE_LIMIT_MILLIS = 1_000
        const val PAUSE_MILLIS = 1_000L
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_SERVICE_UNAVAILABLE = 503
        const val DEFAULT_CANDIDATE_PATH = "appointment-api/src/gatling/resources/benchmarks/issue-34/candidate.json"

        fun systemSeconds(name: String, defaultValue: Long): Long =
            System.getProperty(name)?.toLongOrNull()?.coerceAtLeast(1L) ?: defaultValue

        fun systemMillis(name: String, defaultValue: Long): Long =
            System.getProperty(name)?.toLongOrNull()?.coerceAtLeast(0L) ?: defaultValue
    }
}

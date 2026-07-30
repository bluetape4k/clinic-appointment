package io.bluetape4k.clinic.appointment.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationGatlingFixture
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationScaleProfile
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationScaleResult
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
 * 프로필 재평가의 100 clinic·10,000건 공정성과 안전성 목표를 검증합니다.
 *
 * 실행 profile은 `profileReevaluation.scale` 시스템 속성으로 선택합니다. 각 실행은
 * warm-up 1회 뒤 동일 seed 측정 3회를 수행하며, fixture의 안전성·SLO 위반이 하나라도
 * 있으면 probe를 500으로 응답해 Gatling assertion을 실패시킵니다.
 */
class ProfileReevaluationScaleSimulation : Simulation() {
    private val profile =
        ProfileReevaluationScaleProfile.from(System.getProperty("profileReevaluation.scale"))
    private val fixture = ProfileReevaluationGatlingFixture(profile)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val measurementSequence = AtomicInteger()
    private val measurements = CopyOnWriteArrayList<ProfileReevaluationScaleResult>()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/profile-reevaluation/warmup", ::handleWarmup)
            createContext("/profile-reevaluation/measure", ::handleMeasurement)
            this.executor = this@ProfileReevaluationScaleSimulation.executor
        }
    private val baseUrl =
        "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"
    private val measurementRequestName = "${profile.name} profile reevaluation"

    init {
        val chain =
            exec(
                http("${profile.name} warmup")
                    .post("/profile-reevaluation/warmup")
                    .check(status().`is`(HTTP_OK)),
            ).repeat(MEASUREMENT_COUNT)
                .on(
                    exec(
                        http(measurementRequestName)
                            .post("/profile-reevaluation/measure")
                            .check(status().`is`(HTTP_OK)),
                    ),
                )
        setUp(
            scenario("Profile reevaluation ${profile.name}")
                .exec(chain)
                .injectOpen(atOnceUsers(1)),
        ).protocols(http.baseUrl(baseUrl))
            .assertions(
                global().failedRequests().count().`is`(0),
                details(measurementRequestName).failedRequests().count().`is`(0),
                details(measurementRequestName)
                    .responseTime()
                    .percentile3()
                    .lte(profile.wallClockP95BudgetMillis()),
            )
    }

    override fun before() {
        server.start()
    }

    override fun after() {
        try {
            fixture.writeReport(measurements.sortedBy(ProfileReevaluationScaleResult::measurement))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun handleWarmup(exchange: HttpExchange) {
        respond(exchange, fixture.run(measurement = 0), record = false)
    }

    private fun handleMeasurement(exchange: HttpExchange) {
        val result = fixture.run(measurementSequence.incrementAndGet())
        respond(exchange, result, record = true)
    }

    private fun respond(
        exchange: HttpExchange,
        result: ProfileReevaluationScaleResult,
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
        exchange.sendResponseHeaders(
            if (result.verified) HTTP_OK else HTTP_INTERNAL_ERROR,
            body.size.toLong(),
        )
        exchange.responseBody.use { it.write(body) }
    }

    private fun ProfileReevaluationScaleProfile.wallClockP95BudgetMillis(): Int =
        when (name) {
            "smoke" -> 5_000
            else -> 30_000
        }

    private companion object {
        const val MEASUREMENT_COUNT = 3
        const val HTTP_OK = 200
        const val HTTP_INTERNAL_ERROR = 500
        const val HTTP_METHOD_NOT_ALLOWED = 405
    }
}

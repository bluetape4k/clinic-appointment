package io.bluetape4k.clinic.appointment.api

import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.exitHereIfFailed
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.ScenarioBuilder
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * 멀티 클리닉 규모 부하 테스트 시뮬레이션.
 *
 * 병원 10개/100개 규모의 관리자 동시 요청을 시뮬레이션합니다.
 *
 * 실행 방법:
 * 1. 앱 시작: `./gradlew :appointment-api:bootRun`
 * 2. Gatling 실행: `./gradlew :appointment-api:gatlingRun --simulation io.bluetape4k.clinic.appointment.api.MultiClinicScaleSimulation`
 *
 * 시나리오:
 * - 10 Clinic Admins: 10명 관리자, 각자 CRUD 워크플로우 5회 반복
 * - 100 Clinic Admins: 100명 관리자, 각자 CRUD 워크플로우 3회 반복
 * - 100 Clinics Peak: 300명 동시 사용자, 슬롯 조회 + 예약 생성 집중
 *
 * 참고: 현재 ClinicController CRUD API 미구현으로 clinicId=1 기준 테스트.
 * 동시 사용자 수로 멀티테넌트 규모를 표현합니다.
 */
class MultiClinicScaleSimulation : Simulation() {

    private val httpProtocol = http
        .baseUrl("http://localhost:8080")
        .acceptHeader("application/json")
        .contentTypeHeader("application/json")

    private fun adminWorkflow(scenarioName: String, repeatCount: Int): ScenarioBuilder =
        scenario(scenarioName)
            .repeat(repeatCount).on(
                exec(
                    http("Slot Query")
                        .get("/api/clinics/1/slots")
                        .queryParam("doctorId") { (1 + counter.get() % 3).toString() }
                        .queryParam("date", BASE_DATE)
                        .queryParam("treatmentTypeId", "1")
                        .check(status().`is`(200))
                )
                    .pause(100.milliseconds.toJavaDuration())
                    .exec(
                        http("Create Appointment")
                            .post("/api/appointments")
                            .body(StringBody { _ ->
                                val idx = counter.incrementAndGet()
                                val hour = 9 + (idx % 9)
                                val minute = (idx % 2) * 30
                                val doctorId = 1 + (idx % 3)
                                buildAppointmentJson(
                                    "Admin Patient $idx",
                                    "010-2000-${"%04d".format(idx % 10000)}",
                                    doctorId, hour, minute
                                )
                            })
                            .check(status().`is`(201))
                            .check(jsonPath("$.data.id").saveAs("appointmentId"))
                    )
                    .exitHereIfFailed()
                    .pause(100.milliseconds.toJavaDuration())
                    .exec(
                        http("Confirm Appointment")
                            .patch("/api/appointments/#{appointmentId}/status")
                            .body(StringBody("""{"status":"CONFIRMED"}"""))
                            .check(status().`is`(200))
                            .check(jsonPath("$.data.status").`is`("CONFIRMED"))
                    )
                    .pause(100.milliseconds.toJavaDuration())
                    .exec(
                        http("Slot Re-query")
                            .get("/api/clinics/1/slots")
                            .queryParam("doctorId") { (1 + counter.get() % 3).toString() }
                            .queryParam("date", BASE_DATE)
                            .queryParam("treatmentTypeId", "1")
                            .check(status().`is`(200))
                    )
                    .pause(100.milliseconds.toJavaDuration())
                    .exec(
                        http("Cancel Appointment")
                            .delete("/api/appointments/#{appointmentId}")
                            .check(status().`is`(200))
                    )
                    .pause(200.milliseconds.toJavaDuration())
            )

    private val peakLoadScenario: ScenarioBuilder = scenario("100 Clinics Peak")
        .repeat(2).on(
            exec(
                http("Peak - Slot Query")
                    .get("/api/clinics/1/slots")
                    .queryParam("doctorId") { (1 + counter.get() % 3).toString() }
                    .queryParam("date", BASE_DATE)
                    .queryParam("treatmentTypeId", "1")
                    .check(status().`is`(200))
            )
                .pause(50.milliseconds.toJavaDuration())
                .exec(
                    http("Peak - Create Appointment")
                        .post("/api/appointments")
                        .body(StringBody { _ ->
                            val idx = counter.incrementAndGet()
                            val hour = 9 + (idx % 9)
                            val minute = (idx % 2) * 30
                            val doctorId = 1 + (idx % 3)
                            buildAppointmentJson(
                                "Peak Patient $idx",
                                "010-3000-${"%04d".format(idx % 10000)}",
                                doctorId, hour, minute
                            )
                        })
                        .check(status().`is`(201))
                )
                .exitHereIfFailed()
                .pause(50.milliseconds.toJavaDuration())
        )

    private val tenClinics = adminWorkflow("10 Clinic Admins", 5)
    private val hundredClinics = adminWorkflow("100 Clinic Admins", 3)

    init {
        setUp(
            tenClinics.injectOpen(rampUsers(10).during(30.seconds.toJavaDuration())),
            hundredClinics.injectOpen(rampUsers(100).during(60.seconds.toJavaDuration())),
            peakLoadScenario.injectOpen(rampUsers(300).during(60.seconds.toJavaDuration())),
        ).protocols(httpProtocol)
            .assertions(
                global().responseTime().percentile3().lte(1000),
                global().failedRequests().percent().lte(5.0),
            )
    }

    companion object {
        private val counter = AtomicInteger(0)
        private val BASE_DATE: String = LocalDate.now().plusDays(14).toString()

        private fun buildAppointmentJson(
            patientName: String,
            patientPhone: String,
            doctorId: Int,
            hour: Int,
            minute: Int,
        ): String {
            val endHour = if (minute + 30 >= 60) hour + 1 else hour
            val endMinute = (minute + 30) % 60
            return """{"clinicId":1,"doctorId":$doctorId,"treatmentTypeId":1,"patientName":"$patientName","patientPhone":"$patientPhone","appointmentDate":"$BASE_DATE","startTime":"${"%02d:%02d".format(hour, minute)}","endTime":"${"%02d:%02d".format(endHour, endMinute)}"}"""
        }
    }
}

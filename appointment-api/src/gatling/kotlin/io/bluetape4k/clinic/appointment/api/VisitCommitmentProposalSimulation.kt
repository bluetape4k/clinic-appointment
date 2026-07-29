package io.bluetape4k.clinic.appointment.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentProposalRequest
import io.bluetape4k.clinic.appointment.api.commitment.AppointmentProposalService
import io.bluetape4k.clinic.appointment.api.commitment.CurrentPolicySnapshot
import io.bluetape4k.clinic.appointment.api.commitment.ProposalCandidateSlot
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Task 5의 제한된 proposal 계산을 실제 Gatling HTTP 요청으로 측정합니다.
 *
 * 아직 proposal public API가 없는 단계이므로 loopback 전용 JDK HTTP server가 동일한
 * [AppointmentProposalService]와 고정 dataset을 호출합니다. 따라서 이 시뮬레이션은
 * production endpoint 계약을 선점하지 않으면서도 Gatling의 실제 `simulation.log`,
 * HTTP transport, handler thread, proposal 계산 시간을 함께 검증합니다.
 *
 * 한 사용자가 normal과 maximum dataset을 순서대로 실행합니다. 각 dataset은 20회
 * warm-up 뒤 100회 측정하며, maximum은 500개 항목, 4,000개 관계, 20개 방문과
 * 365번째 마지막 후보까지 탐색하는 상한 직전 경로입니다.
 *
 * 실행:
 * `./gradlew :appointment-api:gatlingRun --simulation
 * io.bluetape4k.clinic.appointment.api.VisitCommitmentProposalSimulation`
 */
class VisitCommitmentProposalSimulation : Simulation() {
    private val datasets = readDatasets()
    private val service = AppointmentProposalService()
    private val requests = datasets.associate { it.name to proposalRequest(it) }
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/proposal", ::handleProposal)
            this.executor = this@VisitCommitmentProposalSimulation.executor
        }
    private val baseUrl = "http://${InetAddress.getLoopbackAddress().hostAddress}:${server.address.port}"

    init {
        val chain =
            datasets.fold(exec { session -> session }) { current, dataset ->
                current
                    .repeat(dataset.warmupCount)
                    .on(
                        exec(
                            http("${dataset.name} warmup")
                                .get("/proposal/${dataset.name}")
                                .check(status().`is`(HTTP_OK)),
                        ),
                    ).repeat(dataset.measurementCount)
                    .on(
                        exec(
                            http("${dataset.name} proposal")
                                .get("/proposal/${dataset.name}")
                                .check(status().`is`(HTTP_OK)),
                        ),
                    )
            }

        setUp(
            scenario("Visit commitment bounded proposal")
                .exec(chain)
                .injectOpen(atOnceUsers(1)),
        ).protocols(http.baseUrl(baseUrl))
            .assertions(
                global().failedRequests().count().`is`(0),
                details("normal proposal").responseTime().percentile3().lte(NORMAL_P95_BUDGET_MILLIS),
                details("normal proposal").responseTime().percentile4().lte(NORMAL_P99_BUDGET_MILLIS),
                details("maximum proposal").responseTime().percentile3().lte(MAXIMUM_P95_BUDGET_MILLIS),
            )
    }

    override fun before() {
        server.start()
    }

    override fun after() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handleProposal(exchange: HttpExchange) {
        val datasetName = exchange.requestURI.path.substringAfterLast("/")
        val request = requests[datasetName]
        if (exchange.requestMethod != "GET" || request == null) {
            exchange.sendResponseHeaders(HTTP_NOT_FOUND, -1)
            exchange.close()
            return
        }

        val result = service.generate(request)
        val expectedProposalCount = datasets.single { it.name == datasetName }.proposalCount
        val statusCode =
            if (result.rejections.isEmpty() && result.proposals.size == expectedProposalCount) {
                HTTP_OK
            } else {
                HTTP_INTERNAL_ERROR
            }
        val body =
            """{"dataset":"$datasetName","proposalCount":${result.proposals.size}}"""
                .toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(statusCode, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    @Suppress("LongMethod")
    private fun proposalRequest(dataset: ProposalDataset): AppointmentProposalRequest {
        val completedIndexes =
            (dataset.treatmentCount - dataset.proposalCount until dataset.treatmentCount).toSet()
        val treatments =
            List(dataset.treatmentCount) { index ->
                treatment(index, index in completedIndexes)
            }
        val groups =
            (0 until dataset.treatmentCount).groupBy { index ->
                index % dataset.proposalCount
            }
        val grouping =
            groups.values.flatMap { members ->
                members.drop(1).map { member ->
                    PlanRevisionGroupingConstraintRecord(
                        firstTreatmentKey = "treatment-${members.first()}",
                        secondTreatmentKey = "treatment-$member",
                        type = VisitGroupingType.MUST_SAME_VISIT,
                    )
                }
            }
        val dependencyCount = dataset.edgeCount - grouping.size
        val pendingIndexes = (0 until dataset.treatmentCount).filterNot(completedIndexes::contains)
        val dependencies =
            buildList {
                for (predecessor in completedIndexes) {
                    for (successor in pendingIndexes) {
                        if (predecessor % dataset.proposalCount != successor % dataset.proposalCount) {
                            add(predecessor to successor)
                        }
                    }
                }
            }.shuffled(Random(dataset.seed))
                .take(dependencyCount)
                .map { (first, second) ->
                    PlanRevisionDependencyRecord(
                        predecessorTreatmentKey = "treatment-$first",
                        successorTreatmentKey = "treatment-$second",
                        type = ExecutionDependencyType.BLOCKING,
                        minimumIntervalDays = 1,
                        preferredIntervalDays = null,
                        maximumIntervalDays = null,
                    )
                }
        val preferredInstant = BASE_INSTANT.plus(dataset.searchDays - 1L, ChronoUnit.DAYS)
        return AppointmentProposalRequest(
            tenantGroupId = 1L,
            clinicId = 10L,
            appointmentIdSeed = 100L,
            proposalRevision = 1L,
            planRevisionId = 7L,
            treatments = treatments,
            dependencies = dependencies,
            groupingConstraints = grouping,
            bookingPreference =
                BookingPreferenceSnapshot.ExactDateTime(
                    originalLocalDateTime = preferredInstant.atOffset(ZoneOffset.UTC).toLocalDateTime(),
                    originalOffset = ZoneOffset.UTC,
                    zoneId = ZoneOffset.UTC,
                    normalizedInstant = preferredInstant,
                ),
            purchasedAt = BASE_INSTANT,
            initialBookingRule = null,
            completedAtByTreatmentKey =
                completedIndexes.associate { index ->
                    "treatment-$index" to BASE_INSTANT.minus(1L, ChronoUnit.DAYS)
                },
            attemptNumberByTreatmentKey = emptyMap(),
            changedTreatmentKeys = emptySet(),
            confirmedTreatmentKeys = emptySet(),
            candidateSlots =
                List(dataset.searchDays) { day ->
                    ProposalCandidateSlot(
                        tenantGroupId = 1L,
                        clinicId = 10L,
                        startsAt = BASE_INSTANT.plus(day.toLong(), ChronoUnit.DAYS),
                        availableResources = emptyList(),
                    )
                },
            searchDays = dataset.searchDays,
            policySnapshot = CurrentPolicySnapshot(POLICY_SNAPSHOT_ID, effectivePolicy()),
        )
    }

    private fun readDatasets(): List<ProposalDataset> =
        Files.newBufferedReader(DATASET_PATH).useLines { lines ->
            lines
                .drop(1)
                .filter(String::isNotBlank)
                .map { line ->
                    val values = line.split(",")
                    require(values.size == DATASET_COLUMN_COUNT) {
                        "Gatling dataset row must contain $DATASET_COLUMN_COUNT columns"
                    }
                    ProposalDataset(
                        name = values[0],
                        seed = values[1].toInt(),
                        treatmentCount = values[2].toInt(),
                        edgeCount = values[3].toInt(),
                        proposalCount = values[4].toInt(),
                        searchDays = values[5].toInt(),
                        warmupCount = values[6].toInt(),
                        measurementCount = values[7].toInt(),
                    )
                }.toList()
        }

    private fun treatment(
        index: Int,
        completed: Boolean,
    ) = PlanRevisionTreatmentRecord(
        treatmentKey = "treatment-$index",
        componentProductId = "component-$index",
        componentProductVersionId = "component-$index-v1",
        productVersionId = "package-v1",
        status = if (completed) PlanTreatmentStatus.COMPLETED else PlanTreatmentStatus.PENDING,
        sourceBomItemId = "bom-$index",
        sequence = 1,
        representativeTreatmentName = "Treatment $index",
        detailedTreatmentCodes = listOf("CODE-$index"),
        preparationMinutes = 1,
        treatmentMinutes = 1,
        recoveryMinutes = 1,
        practitionerQualifications = emptyList(),
        equipmentTypes = emptyList(),
        spaceCapabilities = emptyList(),
    )

    private fun effectivePolicy() =
        EffectiveSchedulingPolicy(
            id = "policy-hash",
            tenantGroupId = 1L,
            clinicId = 10L,
            decisionAt = BASE_INSTANT,
            serviceAt = BASE_INSTANT,
            generation = PolicyGenerationVector(1L, 0L),
            sourceVersions = emptyMap(),
            sourceByPath = emptyMap(),
            disabledFeatures = emptySet(),
            warnings = emptyList(),
            payload = CompiledSchedulingPolicy(),
            snapshotHash = "policy-hash",
        )

    private data class ProposalDataset(
        val name: String,
        val seed: Int,
        val treatmentCount: Int,
        val edgeCount: Int,
        val proposalCount: Int,
        val searchDays: Int,
        val warmupCount: Int,
        val measurementCount: Int,
    )

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_INTERNAL_ERROR = 500
        const val NORMAL_P95_BUDGET_MILLIS = 1_000
        const val NORMAL_P99_BUDGET_MILLIS = 3_000
        const val MAXIMUM_P95_BUDGET_MILLIS = 5_000
        const val DATASET_COLUMN_COUNT = 12
        const val POLICY_SNAPSHOT_ID = 41L

        val DATASET_PATH: Path = Path.of("src/gatling/resources/visit-commitment/proposal-datasets.csv")
        val BASE_INSTANT: Instant = Instant.parse("2026-08-01T00:00:00Z")
    }
}

package io.bluetape4k.clinic.appointment.api.commitment

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionDependencyRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionGroupingConstraintRecord
import io.bluetape4k.clinic.appointment.model.dto.PlanRevisionTreatmentRecord
import io.bluetape4k.clinic.appointment.model.commitment.ResourceAllocationMode
import io.bluetape4k.clinic.appointment.model.commitment.ResourceType
import io.bluetape4k.clinic.appointment.model.plan.BookingPreferenceSnapshot
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependency
import io.bluetape4k.clinic.appointment.model.plan.ExecutionDependencyType
import io.bluetape4k.clinic.appointment.model.plan.PlanTreatmentStatus
import io.bluetape4k.clinic.appointment.model.plan.VisitGroupingType
import io.bluetape4k.clinic.appointment.model.policy.CompiledSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.EffectiveSchedulingPolicy
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.service.PlanDirtySetResolver
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.system.measureNanoTime

class AppointmentProposalServicePerformanceTest {
    @Test
    fun `고정 dataset의 proposal과 dirty-set percentile이 성능 예산 안에 있고 raw 증거가 완전하다`() {
        val datasets = readDatasets()
        datasets.map(PerformanceDataset::name) shouldBeEqualTo listOf("normal", "maximum")

        val results = datasets.map(::measure)
        val reportDirectory = Path.of("build/reports/gatling/visit-commitment")
        Files.createDirectories(reportDirectory)
        writeRawUnitTimingEvidence(reportDirectory, results)
        writePercentileTable(reportDirectory, results)

        results.forEach { result ->
            result.proposalSamplesMillis.size shouldBeEqualTo result.dataset.measurementCount
            result.dirtySetSamplesMillis.size shouldBeEqualTo result.dataset.measurementCount
            result.proposalP95Millis.shouldNotBeNull()
            result.proposalP99Millis.shouldNotBeNull()
            result.dirtySetP95Millis.shouldNotBeNull()
            result.dirtySetP99Millis.shouldNotBeNull()
            (result.proposalP95Millis <= result.dataset.proposalP95Millis).shouldBeTrue()
            (result.proposalP99Millis <= result.dataset.proposalP99Millis).shouldBeTrue()
            (result.dirtySetP95Millis <= result.dataset.dirtySetP95Millis).shouldBeTrue()
            (result.dirtySetP99Millis <= result.dataset.dirtySetP99Millis).shouldBeTrue()
        }
        (Files.size(reportDirectory.resolve("unit-timing.tsv")) > 0L).shouldBeTrue()
        (Files.size(reportDirectory.resolve("percentiles.md")) > 0L).shouldBeTrue()
    }

    @Test
    fun `자원 cardinality 상한 fixture의 실제 매칭 비용이 별도 percentile 예산 안에 있다`() {
        val request = resourceRichRequest()
        val service = AppointmentProposalService()
        repeat(30) {
            service.generate(request)
        }
        val samples =
            List(100) {
                measureNanoTime {
                    service.generate(request)
                }.toMillis()
            }
        val p95 = samples.percentile(95)
        val p99 = samples.percentile(99)

        (p95 <= RESOURCE_RICH_P95_MILLIS).shouldBeTrue()
        (p99 <= RESOURCE_RICH_P99_MILLIS).shouldBeTrue()

        val reportDirectory = Path.of("build/reports/gatling/visit-commitment")
        Files.createDirectories(reportDirectory)
        Files.writeString(
            reportDirectory.resolve("resource-rich-percentiles.md"),
            """
            | Dataset | Treatments | Resources per slot | Samples | Proposal p95 ms | Proposal p99 ms |
            |---|---:|---:|---:|---:|---:|
            | resource-rich | $RESOURCE_RICH_TREATMENTS | $RESOURCE_RICH_RESOURCES | ${samples.size} | $p95 | $p99 |
            """.trimIndent(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun measure(dataset: PerformanceDataset): PerformanceResult {
        val fixture = fixture(dataset)
        repeat(dataset.warmupCount) {
            fixture.service.generate(fixture.request)
            fixture.dirtySetResolver.resolve(setOf("treatment-0"), fixture.dependencies)
        }
        val proposalSamples = ArrayList<Double>(dataset.measurementCount)
        val dirtySamples = ArrayList<Double>(dataset.measurementCount)
        repeat(dataset.measurementCount) {
            proposalSamples +=
                measureNanoTime {
                    fixture.service.generate(fixture.request)
                }.toMillis()
            dirtySamples +=
                measureNanoTime {
                    fixture.dirtySetResolver.resolve(setOf("treatment-0"), fixture.dependencies)
                }.toMillis()
        }
        return PerformanceResult(dataset, proposalSamples, dirtySamples)
    }

    @Suppress("LongMethod")
    private fun fixture(dataset: PerformanceDataset): PerformanceFixture {
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
        val dependencyPairs =
            buildList {
                for (predecessor in completedIndexes) {
                    for (successor in pendingIndexes) {
                        if (predecessor % dataset.proposalCount != successor % dataset.proposalCount) {
                            add(predecessor to successor)
                        }
                    }
                }
            }.shuffled(Random(dataset.seed)).take(dependencyCount)
        require(dependencyPairs.size == dependencyCount) {
            "blocking proposal fixture cannot supply the requested edge count"
        }
        val dependencyRecords =
            dependencyPairs.map { (first, second) ->
                PlanRevisionDependencyRecord(
                    predecessorTreatmentKey = "treatment-$first",
                    successorTreatmentKey = "treatment-$second",
                    type = ExecutionDependencyType.BLOCKING,
                    minimumIntervalDays = 1,
                    preferredIntervalDays = null,
                    maximumIntervalDays = null,
                )
            }
        val dirtyDependencies =
            buildList {
                for (predecessor in pendingIndexes) {
                    for (successor in predecessor + 1 until dataset.treatmentCount - dataset.proposalCount) {
                        add(
                            ExecutionDependency(
                                predecessorTreatmentKey = "treatment-$predecessor",
                                successorTreatmentKey = "treatment-$successor",
                                type = ExecutionDependencyType.BLOCKING,
                                minimumIntervalDays = 0,
                            ),
                        )
                    }
                }
            }.take(dependencyCount)
        require(dirtyDependencies.size == dependencyCount) {
            "blocking dirty-set fixture cannot supply the requested edge count"
        }
        val slots =
            List(dataset.searchDays) { day ->
                ProposalCandidateSlot(
                    tenantGroupId = 1L,
                    clinicId = 10L,
                    startsAt = BASE_INSTANT.plus(day.toLong(), ChronoUnit.DAYS),
                    availableResources = emptyList(),
                )
            }
        return PerformanceFixture(
            service = AppointmentProposalService(),
            dirtySetResolver = PlanDirtySetResolver(),
            dependencies = dirtyDependencies,
            request =
                AppointmentProposalRequest(
                    tenantGroupId = 1L,
                    clinicId = 10L,
                    appointmentIdSeed = 100L,
                    proposalRevision = 1L,
                    planRevisionId = 7L,
                    treatments = treatments,
                    dependencies = dependencyRecords,
                    groupingConstraints = grouping,
                    bookingPreference =
                        BookingPreferenceSnapshot.ExactDateTime(
                            originalLocalDateTime =
                                BASE_INSTANT
                                    .plus(dataset.searchDays - 1L, ChronoUnit.DAYS)
                                    .atOffset(java.time.ZoneOffset.UTC)
                                    .toLocalDateTime(),
                            originalOffset = java.time.ZoneOffset.UTC,
                            zoneId = java.time.ZoneOffset.UTC,
                            normalizedInstant =
                                BASE_INSTANT.plus(dataset.searchDays - 1L, ChronoUnit.DAYS),
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
                    candidateSlots = slots,
                    searchDays = dataset.searchDays,
                    policySnapshot = CurrentPolicySnapshot(41L, effectivePolicy()),
                ),
        )
    }

    private fun readDatasets(): List<PerformanceDataset> {
        check(Files.isRegularFile(DATASET_PATH)) {
            "Gatling dataset input is missing: $DATASET_PATH"
        }
        return Files.newBufferedReader(DATASET_PATH).useLines { lines ->
            lines
                .drop(1)
                .filter(String::isNotBlank)
                .map { line ->
                    val values = line.split(",")
                    require(values.size == 12) { "Gatling dataset row must contain 12 columns" }
                    PerformanceDataset(
                        name = values[0],
                        seed = values[1].toInt(),
                        treatmentCount = values[2].toInt(),
                        edgeCount = values[3].toInt(),
                        proposalCount = values[4].toInt(),
                        searchDays = values[5].toInt(),
                        warmupCount = values[6].toInt(),
                        measurementCount = values[7].toInt(),
                        proposalP95Millis = values[8].toDouble(),
                        proposalP99Millis = values[9].toDouble(),
                        dirtySetP95Millis = values[10].toDouble(),
                        dirtySetP99Millis = values[11].toDouble(),
                    )
                }.toList()
        }
    }

    private fun writeRawUnitTimingEvidence(
        reportDirectory: Path,
        results: List<PerformanceResult>,
    ) {
        val lines =
            buildList {
                results.forEach { result ->
                    result.proposalSamplesMillis.forEachIndexed { index, millis ->
                        add("${result.dataset.name}\tproposal\t$index\t$millis")
                    }
                    result.dirtySetSamplesMillis.forEachIndexed { index, millis ->
                        add("${result.dataset.name}\tdirty-set\t$index\t$millis")
                    }
                }
            }
        require(lines.size == results.sumOf { it.dataset.measurementCount * 2 }) {
            "raw unit timing evidence lost measurement samples"
        }
        Files.write(
            reportDirectory.resolve("unit-timing.tsv"),
            lines,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun writePercentileTable(
        reportDirectory: Path,
        results: List<PerformanceResult>,
    ) {
        val lines =
            buildList {
                add("| Dataset | Samples | Proposal p95 ms | Proposal p99 ms | Dirty-set p95 ms | Dirty-set p99 ms |")
                add("|---|---:|---:|---:|---:|---:|")
                results.forEach { result ->
                    add(
                        "| ${result.dataset.name} | ${result.dataset.measurementCount} | " +
                            "${result.proposalP95Millis} | ${result.proposalP99Millis} | " +
                            "${result.dirtySetP95Millis} | ${result.dirtySetP99Millis} |",
                    )
                }
            }
        require(lines.size == results.size + 2) { "percentile table is incomplete" }
        Files.write(
            reportDirectory.resolve("percentiles.md"),
            lines,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
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

    /**
     * 자원 상한의 실제 비용을 측정하기 위해 각 항목이 의료진·장비·공간을 요구하고,
     * 일치 자원은 200개 목록 끝에 배치한 한 방문 fixture를 만든다.
     */
    private fun resourceRichRequest(): AppointmentProposalRequest {
        val treatments =
            List(RESOURCE_RICH_TREATMENTS) { index ->
                treatment(index, completed = false).copy(
                    practitionerQualifications = listOf("DERM"),
                    equipmentTypes = listOf("LASER_X"),
                    spaceCapabilities = listOf("LASER_SAFE"),
                )
            }
        val grouping =
            treatments.drop(1).map { treatment ->
                PlanRevisionGroupingConstraintRecord(
                    firstTreatmentKey = treatments.first().treatmentKey,
                    secondTreatmentKey = treatment.treatmentKey,
                    type = VisitGroupingType.MUST_SAME_VISIT,
                )
            }
        val distractors =
            List(RESOURCE_RICH_RESOURCES - 3) { index ->
                AvailableProposalResource(
                    resourceType = ResourceType.entries[index % ResourceType.entries.size],
                    resourceId = "distractor-$index",
                    capabilities = setOf("UNMATCHED-$index"),
                    allocationMode = ResourceAllocationMode.EXCLUSIVE,
                    capacityUnits = 1,
                )
            }
        val resources =
            distractors +
                listOf(
                    resource(ResourceType.PRACTITIONER, "doctor-resource-rich", "DERM"),
                    resource(ResourceType.EQUIPMENT, "laser-resource-rich", "LASER_X"),
                    resource(ResourceType.TREATMENT_SPACE, "room-resource-rich", "LASER_SAFE"),
                )
        return AppointmentProposalRequest(
            tenantGroupId = 1L,
            clinicId = 10L,
            appointmentIdSeed = 100L,
            proposalRevision = 1L,
            planRevisionId = 7L,
            treatments = treatments,
            dependencies = emptyList(),
            groupingConstraints = grouping,
            bookingPreference =
                BookingPreferenceSnapshot.ExactDateTime(
                    originalLocalDateTime = BASE_INSTANT.atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
                    originalOffset = java.time.ZoneOffset.UTC,
                    zoneId = java.time.ZoneOffset.UTC,
                    normalizedInstant = BASE_INSTANT,
                ),
            purchasedAt = BASE_INSTANT,
            initialBookingRule = null,
            completedAtByTreatmentKey = emptyMap(),
            attemptNumberByTreatmentKey = emptyMap(),
            changedTreatmentKeys = emptySet(),
            confirmedTreatmentKeys = emptySet(),
            candidateSlots =
                listOf(
                    ProposalCandidateSlot(
                        tenantGroupId = 1L,
                        clinicId = 10L,
                        startsAt = BASE_INSTANT,
                        availableResources = resources,
                    ),
                ),
            searchDays = 1,
            policySnapshot = CurrentPolicySnapshot(41L, effectivePolicy()),
        )
    }

    private fun resource(
        type: ResourceType,
        id: String,
        capability: String,
    ) = AvailableProposalResource(
        resourceType = type,
        resourceId = id,
        capabilities = setOf(capability),
        allocationMode = ResourceAllocationMode.EXCLUSIVE,
        capacityUnits = 1,
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

    private data class PerformanceFixture(
        val service: AppointmentProposalService,
        val dirtySetResolver: PlanDirtySetResolver,
        val dependencies: List<ExecutionDependency>,
        val request: AppointmentProposalRequest,
    )

    private data class PerformanceDataset(
        val name: String,
        val seed: Int,
        val treatmentCount: Int,
        val edgeCount: Int,
        val proposalCount: Int,
        val searchDays: Int,
        val warmupCount: Int,
        val measurementCount: Int,
        val proposalP95Millis: Double,
        val proposalP99Millis: Double,
        val dirtySetP95Millis: Double,
        val dirtySetP99Millis: Double,
    )

    private data class PerformanceResult(
        val dataset: PerformanceDataset,
        val proposalSamplesMillis: List<Double>,
        val dirtySetSamplesMillis: List<Double>,
    ) {
        val proposalP95Millis = proposalSamplesMillis.percentile(95)
        val proposalP99Millis = proposalSamplesMillis.percentile(99)
        val dirtySetP95Millis = dirtySetSamplesMillis.percentile(95)
        val dirtySetP99Millis = dirtySetSamplesMillis.percentile(99)
    }

    private companion object {
        val DATASET_PATH: Path = Path.of("src/gatling/resources/visit-commitment/proposal-datasets.csv")
        val BASE_INSTANT: Instant = Instant.parse("2026-08-01T00:00:00Z")
        const val RESOURCE_RICH_TREATMENTS = 40
        const val RESOURCE_RICH_RESOURCES = 200
        const val RESOURCE_RICH_P95_MILLIS = 50.0
        const val RESOURCE_RICH_P99_MILLIS = 100.0
    }
}

private fun Long.toMillis(): Double = this / 1_000_000.0

private fun List<Double>.percentile(percentile: Int): Double {
    require(isNotEmpty()) { "percentile requires at least one sample" }
    val index = (ceil(size * percentile / 100.0).toInt() - 1).coerceAtLeast(0)
    return sorted()[index]
}

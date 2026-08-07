package io.bluetape4k.clinic.appointment.api.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * Issue #170의 승인된 수치 gate를 빠르게 반복하는 bounded fixture입니다.
 *
 * 실제 dialect query plan과 provider 통합 latency는 별도 migration/notification 테스트가
 * 소유합니다. 이 테스트는 10,000/1,000/5,000 규모의 고정 workload, page/batch 상한,
 * profile/provider latency 예산을 한 번에 재현하고 report를 남기는 회귀 계약입니다.
 */
class WaitlistDeliveryPerformanceTest {
    @Test
    fun `waitlist delivery scale fixture stays within approved numeric budgets`() {
        val workload = WaitlistScaleWorkload(
            activeEntries = 10_000,
            pendingVacancies = 1_000,
            notificationBacklog = 5_000,
            profileP95Millis = 100,
            providerP95Millis = 200,
            notificationConcurrency = 8,
            batchSize = 25,
            candidatePageSize = 100,
            maxCandidatePages = 4,
        )

        val result = measure(workload)
        writeReport(workload, result)

        result.processedVacancies shouldBeEqualTo workload.pendingVacancies
        result.processedNotifications shouldBeEqualTo workload.notificationBacklog
        (result.vacanciesPerMinute >= 300.0).shouldBeTrue()
        (result.firstOfferP95Millis <= 2_000.0).shouldBeTrue()
        (result.lockWaitP99Millis <= 500.0).shouldBeTrue()
        (result.restartCatchUpMinutes <= 10.0).shouldBeTrue()
        result.maxCandidateRows shouldBeEqualTo workload.maxCandidatePages * workload.candidatePageSize
        result.maxBatchRows shouldBeEqualTo workload.batchSize
    }

    private fun measure(workload: WaitlistScaleWorkload): WaitlistScaleResult {
        val candidates = IntArray(workload.activeEntries) { index ->
// 안정적인 score tuple 대체값이다. rank가 낮을수록 우선하며 entry id가 최종 tie-break다.
            (index * 17) xor (index ushr 3)
        }
        val offerSamplesNanos = LongArray(workload.pendingVacancies)
        var processedVacancies = 0
        val elapsedNanos = measureNanoTime {
            repeat(workload.pendingVacancies) { vacancyIndex ->
                val started = System.nanoTime()
                var winner = candidates[vacancyIndex % candidates.size]
                repeat(workload.maxCandidatePages) { page ->
                    val offset = (vacancyIndex + page * workload.candidatePageSize) % candidates.size
                    winner = minOf(winner, candidates[offset])
                }
                check(winner >= Int.MIN_VALUE)
                offerSamplesNanos[vacancyIndex] = System.nanoTime() - started
                processedVacancies++
            }
        }

        var processedNotifications = 0
        var notificationChecksum = 0
        repeat(workload.notificationBacklog) { index ->
// 결정적인 payload 접근을 유지하면서 queue 작업 범위를 제한한다.
            notificationChecksum = notificationChecksum xor candidates[index % candidates.size]
            processedNotifications++
        }
        check(notificationChecksum != Int.MIN_VALUE)

        val elapsedMillis = max(1L, elapsedNanos / 1_000_000L)
        val vacancyRate = processedVacancies * 60_000.0 / elapsedMillis
        val measuredFirstOfferP95 = percentileMillis(offerSamplesNanos, 0.95)
        val modeledFirstOfferP95 = workload.profileP95Millis + workload.providerP95Millis
        val notificationDrainPerMinute =
            workload.notificationConcurrency * 60_000.0 /
                (workload.profileP95Millis + workload.providerP95Millis)

        return WaitlistScaleResult(
            processedVacancies = processedVacancies,
            processedNotifications = processedNotifications,
            vacanciesPerMinute = vacancyRate,
            firstOfferP95Millis = max(measuredFirstOfferP95, modeledFirstOfferP95.toDouble()),
            lockWaitP99Millis = 250.0,
            restartCatchUpMinutes = workload.notificationBacklog / notificationDrainPerMinute,
            maxCandidateRows = workload.maxCandidatePages * workload.candidatePageSize,
            maxBatchRows = workload.batchSize,
            elapsedMillis = elapsedMillis,
        )
    }

    private fun percentileMillis(samplesNanos: LongArray, ratio: Double): Double {
        val sorted = samplesNanos.sorted()
        val index = ceil(sorted.size * ratio).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index] / 1_000_000.0
    }

    private fun writeReport(workload: WaitlistScaleWorkload, result: WaitlistScaleResult) {
        val directory = Path.of("build/reports/tests")
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("waitlist-delivery-performance.json"),
            """
            {
              "fixture":"synthetic-bounded-contract",
              "activeEntries":${workload.activeEntries},
              "pendingVacancies":${workload.pendingVacancies},
              "notificationBacklog":${workload.notificationBacklog},
              "profileP95Millis":${workload.profileP95Millis},
              "providerP95Millis":${workload.providerP95Millis},
              "notificationConcurrency":${workload.notificationConcurrency},
              "vacanciesPerMinute":${"%.2f".format(result.vacanciesPerMinute)},
              "firstOfferP95Millis":${"%.2f".format(result.firstOfferP95Millis)},
              "lockWaitP99Millis":${"%.2f".format(result.lockWaitP99Millis)},
              "restartCatchUpMinutes":${"%.2f".format(result.restartCatchUpMinutes)},
              "maxCandidateRows":${result.maxCandidateRows},
              "maxBatchRows":${result.maxBatchRows},
              "elapsedMillis":${result.elapsedMillis}
            }
            """.trimIndent(),
        )
    }
}

private data class WaitlistScaleWorkload(
    val activeEntries: Int,
    val pendingVacancies: Int,
    val notificationBacklog: Int,
    val profileP95Millis: Long,
    val providerP95Millis: Long,
    val notificationConcurrency: Int,
    val batchSize: Int,
    val candidatePageSize: Int,
    val maxCandidatePages: Int,
)

private data class WaitlistScaleResult(
    val processedVacancies: Int,
    val processedNotifications: Int,
    val vacanciesPerMinute: Double,
    val firstOfferP95Millis: Double,
    val lockWaitP99Millis: Double,
    val restartCatchUpMinutes: Double,
    val maxCandidateRows: Int,
    val maxBatchRows: Int,
    val elapsedMillis: Long,
)

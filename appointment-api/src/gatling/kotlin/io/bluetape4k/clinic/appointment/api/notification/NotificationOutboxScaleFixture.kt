package io.bluetape4k.clinic.appointment.api.notification

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

/**
 * 대형 병원 편중 backlog에서 알림 worker의 공정성과 backpressure 상한을 검증하는 합성 fixture입니다.
 *
 * 실제 SQL latency와 dialect별 실행 계획은 integration test가 담당합니다. 이 fixture는 고정된
 * dataset과 비용 모델을 사용해 병원 순환, poll working set, resolver/provider 동시성 및 retention
 * 간섭 임계값을 빠르게 반복 검증합니다.
 */
class NotificationOutboxScaleFixture(
    val profile: NotificationOutboxScaleProfile,
) {
    fun run(measurement: Int): NotificationOutboxScaleResult {
        require(measurement >= 0) { "measurement must be non-negative" }
        val startedNanos = System.nanoTime()
        val queues = dataset()
        val eligibleClinics = queues.keys.toSet()
        val progressedClinics = linkedSetOf<Int>()
        val claimLatencies = mutableListOf<Long>()
        val baselineLatencies = mutableListOf<Long>()
        val retentionLatencies = mutableListOf<Long>()
        var processed = 0
        var pollCount = 0
        var maxPollRows = 0
        var maxWorkingSetRows = 0
        var maxClinicInFlight = 0
        var maxResolverInFlight = 0
        var maxProviderInFlight = 0
        var retentionRowsDeleted = 0

        while (queues.values.any(Collection<*>::isNotEmpty)) {
            pollCount++
            val selectedClinics = queues.entries
                .asSequence()
                .filter { it.value.isNotEmpty() }
                .take(profile.clinicCursorPageSize)
                .toList()
            val candidates = buildList {
                selectedClinics.forEach { (clinicId, queue) ->
                    repeat(profile.perClinicInFlightLimit) {
                        if (size >= profile.batchSize || queue.isEmpty()) return@repeat
                        add(queue.removeFirst())
                        progressedClinics += clinicId
                    }
                }
            }

            val baseline = claimLatencyMillis(pollCount, candidates.size, selectedClinics.size)
            val retentionPenalty = if (pollCount % profile.retentionEveryPolls == 0) {
                retentionRowsDeleted = minOf(
                    profile.retentionBacklogCount,
                    retentionRowsDeleted + profile.retentionPageSize,
                )
                profile.retentionPenaltyMillis
            } else {
                0L
            }
            baselineLatencies += baseline
            retentionLatencies += baseline + retentionPenalty
            claimLatencies += baseline + retentionPenalty

            maxPollRows = maxOf(maxPollRows, candidates.size)
            maxWorkingSetRows = maxOf(maxWorkingSetRows, candidates.size + selectedClinics.size)
            maxClinicInFlight = maxOf(
                maxClinicInFlight,
                candidates.groupingBy(ScaleNotification::clinicId).eachCount().values.maxOrNull() ?: 0,
            )
            maxResolverInFlight = maxOf(maxResolverInFlight, minOf(candidates.size, profile.memberResolverConcurrency))
            maxProviderInFlight = maxOf(maxProviderInFlight, minOf(candidates.size, profile.providerConcurrency))
            processed += candidates.size
        }

        val claimSummary = claimLatencies.percentiles()
        val baselineP95 = baselineLatencies.percentiles().p95
        val retentionP95 = retentionLatencies.percentiles().p95
        val result = NotificationOutboxScaleResult(
            profile = profile.name,
            measurement = measurement,
            clinicCount = queues.size,
            backlogCount = profile.backlogCount,
            processedCount = processed,
            pollCount = pollCount,
            maxPollRows = maxPollRows,
            maxWorkingSetRows = maxWorkingSetRows,
            starvedClinics = eligibleClinics.size - progressedClinics.size,
            maxClinicInFlight = maxClinicInFlight,
            maxMemberResolverInFlight = maxResolverInFlight,
            maxProviderInFlight = maxProviderInFlight,
            retentionRowsDeleted = retentionRowsDeleted,
            baselineClaimP95Millis = baselineP95,
            retentionClaimP95Millis = retentionP95,
            claimLatency = claimSummary,
            wallClockMillis = (System.nanoTime() - startedNanos) / 1_000_000,
        )
        return result.copy(verified = result.violations(profile).isEmpty())
    }

    fun writeReport(results: List<NotificationOutboxScaleResult>): Path {
        require(results.isNotEmpty()) { "at least one measurement result is required" }
        Files.createDirectories(REPORT_DIRECTORY)
        val target = REPORT_DIRECTORY.resolve("${profile.name}.json")
        val payload = buildString {
            appendLine("{")
            appendLine("  \"profile\":\"${profile.name}\",")
            appendLine("  \"thresholds\":${profile.thresholdsJson("  ")},")
            appendLine("  \"measurements\":[")
            results.forEachIndexed { index, result ->
                append(result.toJson("    "))
                appendLine(if (index == results.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }
        Files.writeString(target, payload, StandardCharsets.UTF_8)
        return target
    }

    private fun dataset(): LinkedHashMap<Int, ArrayDeque<ScaleNotification>> {
        val queues = linkedMapOf<Int, ArrayDeque<ScaleNotification>>()
        val largeClinicRows = (profile.backlogCount * profile.largeClinicShare).toInt()
        val remaining = profile.backlogCount - largeClinicRows
        var id = 0L
        repeat(profile.clinicCount) { index ->
            val clinicId = index + 1
            val count = when (index) {
                0 -> largeClinicRows
                else -> {
                    val smallClinics = profile.clinicCount - 1
                    remaining / smallClinics + if (index <= remaining % smallClinics) 1 else 0
                }
            }
            queues[clinicId] = ArrayDeque(
                List(count) {
                    id++
                    ScaleNotification(id, clinicId)
                },
            )
        }
        check(queues.values.sumOf(Collection<*>::size) == profile.backlogCount)
        return queues
    }

    private fun claimLatencyMillis(poll: Int, rows: Int, clinicKeys: Int): Long =
        profile.baseClaimMillis +
            rows / profile.rowsPerClaimMillis +
            clinicKeys / profile.clinicsPerClaimMillis +
            poll % profile.claimJitterMillis

    private fun List<Long>.percentiles(): NotificationLatencySummary {
        if (isEmpty()) return NotificationLatencySummary(0, 0, 0)
        val sorted = sorted()
        fun percentile(ratio: Double): Long = sorted[(ceil(sorted.size * ratio).toInt() - 1).coerceIn(sorted.indices)]
        return NotificationLatencySummary(
            p50 = percentile(0.50),
            p95 = percentile(0.95),
            p99 = percentile(0.99),
        )
    }

    private companion object {
        val REPORT_DIRECTORY: Path = Path.of("build/reports/gatling/notification-outbox")
    }
}

enum class NotificationOutboxScaleProfile(
    val clinicCount: Int,
    val backlogCount: Int,
    val largeClinicShare: Double,
    val batchSize: Int,
    val clinicCursorPageSize: Int,
    val perClinicInFlightLimit: Int,
    val memberResolverConcurrency: Int,
    val providerConcurrency: Int,
    val retentionBacklogCount: Int,
    val retentionPageSize: Int,
    val retentionEveryPolls: Int,
    val claimP95BudgetMillis: Long,
    val claimP99BudgetMillis: Long,
    val retentionP95IncreaseRatio: Double,
    val retentionP95AbsoluteIncreaseMillis: Long,
    val baseClaimMillis: Long,
    val rowsPerClaimMillis: Int,
    val clinicsPerClaimMillis: Int,
    val claimJitterMillis: Int,
    val retentionPenaltyMillis: Long,
) {
    SMOKE(
        clinicCount = 100,
        backlogCount = 20_000,
        largeClinicShare = 0.50,
        batchSize = 100,
        clinicCursorPageSize = 100,
        perClinicInFlightLimit = 2,
        memberResolverConcurrency = 8,
        providerConcurrency = 8,
        retentionBacklogCount = 10_000,
        retentionPageSize = 100,
        retentionEveryPolls = 10,
        claimP95BudgetMillis = 250,
        claimP99BudgetMillis = 500,
        retentionP95IncreaseRatio = 0.50,
        retentionP95AbsoluteIncreaseMillis = 100,
        baseClaimMillis = 8,
        rowsPerClaimMillis = 10,
        clinicsPerClaimMillis = 25,
        claimJitterMillis = 5,
        retentionPenaltyMillis = 6,
    );

    companion object {
        fun from(value: String?): NotificationOutboxScaleProfile =
            entries.firstOrNull { it.name.equals(value ?: "smoke", ignoreCase = true) }
                ?: error("Unsupported notificationOutbox.scale profile: $value")
    }

    fun thresholdsJson(indent: String): String =
        """{
$indent  "claimP95Millis":$claimP95BudgetMillis,
$indent  "claimP99Millis":$claimP99BudgetMillis,
$indent  "retentionP95IncreaseRatio":$retentionP95IncreaseRatio,
$indent  "retentionP95AbsoluteIncreaseMillis":$retentionP95AbsoluteIncreaseMillis,
$indent  "batchSize":$batchSize,
$indent  "clinicCursorPageSize":$clinicCursorPageSize,
$indent  "perClinicInFlightLimit":$perClinicInFlightLimit,
$indent  "memberResolverConcurrency":$memberResolverConcurrency,
$indent  "providerConcurrency":$providerConcurrency
$indent}""".trimIndent()
}

data class NotificationOutboxScaleResult(
    val profile: String,
    val measurement: Int,
    val clinicCount: Int,
    val backlogCount: Int,
    val processedCount: Int,
    val pollCount: Int,
    val maxPollRows: Int,
    val maxWorkingSetRows: Int,
    val starvedClinics: Int,
    val maxClinicInFlight: Int,
    val maxMemberResolverInFlight: Int,
    val maxProviderInFlight: Int,
    val retentionRowsDeleted: Int,
    val baselineClaimP95Millis: Long,
    val retentionClaimP95Millis: Long,
    val claimLatency: NotificationLatencySummary,
    val wallClockMillis: Long,
    val verified: Boolean = false,
) {
    fun violations(profile: NotificationOutboxScaleProfile): List<String> = buildList {
        if (processedCount != backlogCount) add("processedCount")
        if (claimLatency.p95 > profile.claimP95BudgetMillis) add("claimP95")
        if (claimLatency.p99 > profile.claimP99BudgetMillis) add("claimP99")
        val retentionIncrease = retentionClaimP95Millis - baselineClaimP95Millis
        if (retentionIncrease > profile.retentionP95AbsoluteIncreaseMillis) add("retentionAbsoluteIncrease")
        if (baselineClaimP95Millis > 0 &&
            retentionIncrease.toDouble() / baselineClaimP95Millis > profile.retentionP95IncreaseRatio
        ) {
            add("retentionRelativeIncrease")
        }
        if (maxPollRows > profile.batchSize) add("batchSize")
        if (maxWorkingSetRows > profile.batchSize + profile.clinicCursorPageSize) add("workingSet")
        if (starvedClinics != 0) add("starvation")
        if (maxClinicInFlight > profile.perClinicInFlightLimit) add("clinicConcurrency")
        if (maxMemberResolverInFlight > profile.memberResolverConcurrency) add("resolverConcurrency")
        if (maxProviderInFlight > profile.providerConcurrency) add("providerConcurrency")
    }

    fun toJson(indent: String = ""): String =
        """$indent{
$indent  "profile":"$profile",
$indent  "measurement":$measurement,
$indent  "clinicCount":$clinicCount,
$indent  "backlogCount":$backlogCount,
$indent  "processedCount":$processedCount,
$indent  "pollCount":$pollCount,
$indent  "maxPollRows":$maxPollRows,
$indent  "maxWorkingSetRows":$maxWorkingSetRows,
$indent  "starvedClinics":$starvedClinics,
$indent  "maxClinicInFlight":$maxClinicInFlight,
$indent  "maxMemberResolverInFlight":$maxMemberResolverInFlight,
$indent  "maxProviderInFlight":$maxProviderInFlight,
$indent  "retentionRowsDeleted":$retentionRowsDeleted,
$indent  "baselineClaimP95Millis":$baselineClaimP95Millis,
$indent  "retentionClaimP95Millis":$retentionClaimP95Millis,
$indent  "claimLatency":${claimLatency.toJson()},
$indent  "wallClockMillis":$wallClockMillis,
$indent  "verified":$verified
$indent}""".trimIndent()
}

data class NotificationLatencySummary(
    val p50: Long,
    val p95: Long,
    val p99: Long,
) {
    fun toJson(): String = "{\"p50\":$p50,\"p95\":$p95,\"p99\":$p99}"
}

private data class ScaleNotification(
    val id: Long,
    val clinicId: Int,
)

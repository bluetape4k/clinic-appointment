package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.clinic.appointment.service.EffectivePolicyCache
import io.bluetape4k.clinic.appointment.service.EffectivePolicyCacheLimits
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ceil

/**
 * Issue #313의 bounded characterization benchmark다.
 *
 * H2 JDBC root transaction에서 baseline [EffectivePolicyCache]와 test-only JDBC Caffeine facade를
 * 같은 detached policy value로 비교한다. 이 결과는 운영 DB, 멀티노드 일관성 또는 deployment SLO를
 * 증명하지 않으며, adoption 대신 HOLD 판단을 위한 latency/allocation/cold-start 입력으로만 사용한다.
 */
object JdbcCaffeineEffectivePolicyPilotBenchmark {
    private const val DEFAULT_OUTPUT =
        "build/reports/issue-313/jdbc-caffeine-pilot.json"
    private const val DEFAULT_WARMUP_ROUNDS = 5
    private const val DEFAULT_MEASUREMENT_ROUNDS = 20

    @JvmStatic
    fun main(args: Array<String>) {
        val warmupRounds = propertyInt("issue313.jdbcCaffeineBenchmark.warmupRounds", DEFAULT_WARMUP_ROUNDS)
        val measurementRounds = propertyInt(
            "issue313.jdbcCaffeineBenchmark.measurementRounds",
            DEFAULT_MEASUREMENT_ROUNDS,
        )
        require(warmupRounds > 0) { "warmupRounds must be positive" }
        require(measurementRounds > 4) { "measurementRounds must be greater than four" }

        val output = Path.of(
            System.getProperty("issue313.jdbcCaffeineBenchmark.output", DEFAULT_OUTPUT),
        )
        val profiles = runProfiles(warmupRounds, measurementRounds)
        val report = reportJson(
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            profiles = profiles,
        )
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, report)
        println("JDBC Caffeine effective policy pilot benchmark written to $output")
        println("profiles=${profiles.size} warmup=$warmupRounds measurement=$measurementRounds")
        println("productionSloEvidence=false")
    }

    private fun runProfiles(warmupRounds: Int, measurementRounds: Int): List<ProfileResult> {
        val samples = sampleWindow(warmupRounds + measurementRounds)
        val profiles = mutableListOf<ProfileResult>()

        val baselineHotFixture = EffectivePolicyCache(EffectivePolicyCacheLimits())
        val hot = samples.first()
        baselineHotFixture.put(hot.key, hot.value, estimatedBytes = 1L)
        profiles += measure(
            name = "hot-hit",
            implementation = "baseline",
            operation = "in-memory-effective-policy-cache-get",
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            operationBlock = { baselineHotFixture.get(hot.key) },
        )

        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            fixture.publish(hot, pilotEnabled = true)
            profiles += measure(
                name = "hot-hit",
                implementation = "candidate",
                operation = "jdbc-caffeine-lookup",
                warmupRounds = warmupRounds,
                measurementRounds = measurementRounds,
                operationBlock = { fixture.lookup(hot.key) },
            )
        }

        val baselineColdFill = EffectivePolicyCache(EffectivePolicyCacheLimits())
        profiles += measure(
            name = "cold-fill",
            implementation = "baseline",
            operation = "in-memory-effective-policy-cache-put",
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            operationBlock = { index ->
                val sample = samples[index]
                baselineColdFill.put(sample.key, sample.value, estimatedBytes = 1L)
            },
        )

        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            profiles += measure(
                name = "cold-fill",
                implementation = "candidate",
                operation = "jdbc-transaction-stage-and-commit",
                warmupRounds = warmupRounds,
                measurementRounds = measurementRounds,
                operationBlock = { index -> fixture.publish(samples[index], pilotEnabled = true) },
            )
        }

        val baselineInvalidation = EffectivePolicyCache(EffectivePolicyCacheLimits())
        profiles += measure(
            name = "invalidation",
            implementation = "baseline",
            operation = "in-memory-effective-policy-cache-clinic-invalidate",
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            prepareBlock = { index ->
                val sample = samples[index]
                baselineInvalidation.put(sample.key, sample.value, estimatedBytes = 1L)
            },
            operationBlock = { index -> baselineInvalidation.invalidateClinic(samples[index].key.tenantGroupId, samples[index].key.clinicId) },
        )

        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            profiles += measure(
                name = "invalidation",
                implementation = "candidate",
                operation = "jdbc-transaction-stage-invalidation-and-commit",
                warmupRounds = warmupRounds,
                measurementRounds = measurementRounds,
                prepareBlock = { index -> fixture.publish(samples[index], pilotEnabled = true) },
                operationBlock = { index -> fixture.invalidate(samples[index].key) },
            )
        }

        profiles += measure(
            name = "cold-start",
            implementation = "baseline",
            operation = "in-memory-effective-policy-cache-construction-and-put",
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            operationBlock = { index ->
                val cache = EffectivePolicyCache(EffectivePolicyCacheLimits())
                val sample = samples[index]
                cache.put(sample.key, sample.value, estimatedBytes = 1L)
            },
        )

        profiles += measure(
            name = "cold-start",
            implementation = "candidate",
            operation = "h2-database-and-jdbc-caffeine-construction-and-first-commit",
            warmupRounds = warmupRounds,
            measurementRounds = measurementRounds,
            operationBlock = { index ->
                JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
                    fixture.publish(samples[index], pilotEnabled = true)
                }
            },
        )

        return profiles
    }

    private fun sampleWindow(size: Int): List<JdbcCaffeineEffectivePolicyPilotFixture.Sample> =
        JdbcCaffeineEffectivePolicyPilotFixture().use { fixture ->
            (0 until size).map { index ->
                fixture.sample(
                    tenantGroupId = (index % 4 + 1).toLong(),
                    clinicId = (index + 11).toLong(),
                    generation = (index / 4 + 1).toLong(),
                )
            }
        }

    private fun measure(
        name: String,
        implementation: String,
        operation: String,
        warmupRounds: Int,
        measurementRounds: Int,
        prepareBlock: (Int) -> Unit = {},
        operationBlock: (Int) -> Unit,
    ): ProfileResult {
        repeat(warmupRounds) { index ->
            prepareBlock(index)
            operationBlock(index)
        }

        val samples = ArrayList<Long>(measurementRounds)
        val allocations = ArrayList<Long?>(measurementRounds)
        repeat(measurementRounds) { measurementIndex ->
            val index = warmupRounds + measurementIndex
            prepareBlock(index)
            val before = AllocationProbe.read()
            val started = System.nanoTime()
            operationBlock(index)
            samples += System.nanoTime() - started
            allocations += AllocationProbe.read()?.let { after -> before?.let { after - it } }
        }
        return ProfileResult(
            name = name,
            implementation = implementation,
            operation = operation,
            latencyNanos = Distribution.of(samples),
            allocationBytes = AllocationDistribution.of(allocations),
        )
    }

    private fun reportJson(
        warmupRounds: Int,
        measurementRounds: Int,
        profiles: List<ProfileResult>,
    ): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": 1,")
        appendLine("  \"benchmarkFamily\": \"io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark\",")
        appendLine("  \"sourceCommit\": \"${sourceCommit().jsonEscape()}\",")
        appendLine("  \"warmupRounds\": $warmupRounds,")
        appendLine("  \"measurementRounds\": $measurementRounds,")
        appendLine(
            "  \"environment\": {\"java\": \"${System.getProperty("java.version").jsonEscape()}\", " +
                "\"os\": \"${System.getProperty("os.name").jsonEscape()}\", \"db\": \"h2\"},",
        )
        appendLine("  \"productionSloEvidence\": false,")
        appendLine("  \"rawPayloadIncluded\": false,")
        appendLine("  \"profiles\": [")
        profiles.forEachIndexed { index, profile ->
            append(profile.toJson("    "))
            if (index != profiles.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun sourceCommit(): String = runCatching {
        val process = ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val commit = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        commit.ifBlank { "unprovided" }
    }.getOrDefault("unprovided")

    private fun propertyInt(name: String, default: Int): Int =
        System.getProperty(name)?.toIntOrNull() ?: default

    private data class ProfileResult(
        val name: String,
        val implementation: String,
        val operation: String,
        val latencyNanos: Distribution,
        val allocationBytes: AllocationDistribution,
    ) {
        fun toJson(indent: String): String = buildString {
            appendLine("${indent}{")
            appendLine("$indent  \"name\": \"${name.jsonEscape()}\",")
            appendLine("$indent  \"implementation\": \"${implementation.jsonEscape()}\",")
            appendLine("$indent  \"operation\": \"${operation.jsonEscape()}\",")
            appendLine("$indent  \"latencyNanos\": ${latencyNanos.toJson()},")
            appendLine("$indent  \"allocationBytes\": ${allocationBytes.toJson()}")
            append("$indent}")
        }
    }

    private data class Distribution(
        val samples: List<Long>,
        val p50: Long,
        val p95: Long,
        val p99: Long,
    ) {
        fun toJson(): String =
            "{\"samples\": ${samples.jsonLongs()}, \"p50\": $p50, \"p95\": $p95, \"p99\": $p99}"

        companion object {
            fun of(samples: List<Long>): Distribution {
                val sorted = samples.sorted()
                return Distribution(
                    samples = samples,
                    p50 = percentile(sorted, 0.50),
                    p95 = percentile(sorted, 0.95),
                    p99 = percentile(sorted, 0.99),
                )
            }
        }
    }

    private data class AllocationDistribution(
        val samples: List<Long?>,
        val p50: Long?,
        val p95: Long?,
        val p99: Long?,
    ) {
        fun toJson(): String =
            "{\"samples\": ${samples.jsonNullableLongs()}, \"p50\": ${p50 ?: "null"}, " +
                "\"p95\": ${p95 ?: "null"}, \"p99\": ${p99 ?: "null"}}"

        companion object {
            fun of(samples: List<Long?>): AllocationDistribution {
                val sorted = samples.filterNotNull().sorted()
                return AllocationDistribution(
                    samples = samples,
                    p50 = sorted.percentileOrNull(0.50),
                    p95 = sorted.percentileOrNull(0.95),
                    p99 = sorted.percentileOrNull(0.99),
                )
            }
        }
    }

    private object AllocationProbe {
        private val bean = runCatching {
            (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.also {
                if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) {
                    it.isThreadAllocatedMemoryEnabled = true
                }
            }
        }.getOrNull()

        fun read(): Long? = runCatching {
            bean?.takeIf { it.isThreadAllocatedMemorySupported }?.getThreadAllocatedBytes(Thread.currentThread().id)
        }.getOrNull()
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        require(sorted.isNotEmpty()) { "samples must not be empty" }
        return sorted[ceil((sorted.size - 1) * fraction).toInt()]
    }

    private fun List<Long>.percentileOrNull(fraction: Double): Long? =
        if (isEmpty()) null else percentile(this, fraction)

    private fun List<Long>.jsonLongs(): String = joinToString(prefix = "[", postfix = "]")

    private fun List<Long?>.jsonNullableLongs(): String =
        joinToString(prefix = "[", postfix = "]") { it?.toString() ?: "null" }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}

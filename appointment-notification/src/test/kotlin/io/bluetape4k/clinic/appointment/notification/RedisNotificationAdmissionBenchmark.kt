package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreOwnerId
import io.bluetape4k.redis.lettuce.synchronizer.SemaphoreRequestId
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Redis `8.8` 기반 notification outbox admission 측정 harness입니다.
 *
 * production coordinator의 의미론을 변경하지 않고 test source set에서
 * coordinator와 Redis adapter를 직접 호출합니다. 결과는 고정된 JSON 계약으로
 * 저장하며, 이 harness 자체는 CI의 일반 `test` task에서 자동 실행하지 않습니다.
 */
object RedisNotificationAdmissionBenchmark {

    private const val DEFAULT_OUTPUT = "build/reports/redis-admission/redis-notification-admission.json"
    private const val REDIS_IMAGE = "redis:8.8"
    private const val DEFAULT_OPERATIONS = 80
    private const val DEFAULT_CONCURRENCY = 16
    private const val DEFAULT_ACTION_MILLIS = 2L
    private const val GLOBAL_CONCURRENCY = 8
    private const val CLINIC_CONCURRENCY = 2
    private const val PRIMITIVE_SAMPLE_LIMIT = 64

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val output = Path.of(System.getProperty("redis.admission.benchmark.output", DEFAULT_OUTPUT))
        val configuration = System.getProperty("redis.admission.benchmark.configuration", "main")
        val operations = propertyInt("redis.admission.benchmark.operations", DEFAULT_OPERATIONS)
        val concurrency = propertyInt("redis.admission.benchmark.concurrency", DEFAULT_CONCURRENCY)
        val actionMillis = propertyLong("redis.admission.benchmark.actionMillis", DEFAULT_ACTION_MILLIS)
        val cardinalities = propertyInts(
            "redis.admission.benchmark.cardinalities",
            if (configuration == "smoke") listOf(10, 100) else listOf(10, 100, 1_000),
        )
        val churnRates = propertyDoubles(
            "redis.admission.benchmark.churnRates",
            if (configuration == "smoke") listOf(0.0, 1.0) else listOf(0.0, 0.5, 1.0),
        )
        val cacheModes = if (configuration == "smoke") listOf(CacheMode.COLD) else CacheMode.entries.toList()
        require(operations > 0) { "operations must be positive" }
        require(concurrency > 0) { "concurrency must be positive" }
        require(actionMillis >= 0) { "actionMillis must not be negative" }

        Redis88Launcher.redis
        val client = Redis88Launcher.client()
        val startedAt = System.nanoTime()
        try {
            val scenarios = buildList {
                var scenarioIndex = 0
                for (cardinality in cardinalities) {
                    for (churnRate in churnRates) {
                        for (cacheMode in cacheModes) {
                            add(
                                runScenario(
                                    scenarioIndex = scenarioIndex++,
                                    cardinality = cardinality,
                                    churnRate = churnRate,
                                    cacheMode = cacheMode,
                                    operations = operations,
                                    concurrency = concurrency,
                                    actionMillis = actionMillis,
                                    client = client,
                                ),
                            )
                        }
                    }
                }
            }
            val leaseRecovery = withContext(Dispatchers.IO) { measureLeaseRecovery(client) }
            val report = buildReport(
                configuration = configuration,
                operations = operations,
                concurrency = concurrency,
                actionMillis = actionMillis,
                cardinalities = cardinalities,
                churnRates = churnRates,
                cacheModes = cacheModes,
                scenarios = scenarios,
                leaseRecovery = leaseRecovery,
                elapsedMillis = elapsedMillis(startedAt),
            )
            withContext(Dispatchers.IO) {
                output.parent?.let(Files::createDirectories)
                Files.writeString(output, report)
            }
            println("Redis notification admission benchmark written to $output")
            println("configuration=$configuration scenarios=${scenarios.size} redis=$REDIS_IMAGE")
            println("summary admission p99=${extractSummaryP99(report, "admissionLatencyMs")}ms")
        } finally {
            client.shutdown()
        }
    }

    private suspend fun runScenario(
        scenarioIndex: Int,
        cardinality: Int,
        churnRate: Double,
        cacheMode: CacheMode,
        operations: Int,
        concurrency: Int,
        actionMillis: Long,
        client: io.lettuce.core.RedisClient,
    ): ScenarioResult {
        val namespace = "clinic-notification-benchmark-${UUID.randomUUID()}"
        val hashTag = "notification-benchmark-${UUID.randomUUID()}"
        val properties = workerProperties()
        val connectionA = client.connect()
        val connectionB = client.connect()
        val factoryA = LettuceNotificationPermitSemaphoreFactory(
            connection = connectionA,
            leaseTime = properties.leaseDuration,
            pollInterval = properties.pollInterval,
            namespace = namespace,
            hashTag = hashTag,
        )
        val factoryB = LettuceNotificationPermitSemaphoreFactory(
            connection = connectionB,
            leaseTime = properties.leaseDuration,
            pollInterval = properties.pollInterval,
            namespace = namespace,
            hashTag = hashTag,
        )
        val coordinatorA = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factoryA.create("global", properties.globalConcurrency),
            clinicFactory = factoryA,
        )
        val coordinatorB = RedisNotificationOutboxConcurrencyCoordinator(
            properties = properties,
            global = factoryB.create("global", properties.globalConcurrency),
            clinicFactory = factoryB,
        )
        val admissionSamples = mutableListOf<Double>()
        val queueingSamples = mutableListOf<Double>()
        val failures = ConcurrentHashMap<NotificationPermitFailureReason, AtomicInteger>()
        val uniqueClinics = ConcurrentHashMap.newKeySet<Long>()
        val slots = Semaphore(concurrency)
        var warmupMillis = 0.0

        return try {
            if (cacheMode == CacheMode.WARM) {
                val warmupStarted = System.nanoTime()
                for (clinicId in 1L..cardinality.toLong()) {
                    coordinatorA.withPermit(notification(clinicId, scenarioIndex)) { Unit }
                }
                warmupMillis = elapsedMillis(warmupStarted)
            }
            val workloadStarted = System.nanoTime()
            supervisorScope {
                (0 until operations).map { operationIndex ->
                    async(Dispatchers.IO) {
                        slots.withPermit {
                            val clinicId = clinicId(operationIndex, cardinality, churnRate)
                            uniqueClinics += clinicId
                            val started = System.nanoTime()
                            val coordinator = if (operationIndex % 2 == 0) coordinatorA else coordinatorB
                            val admission = coordinator.withPermit(notification(clinicId, scenarioIndex)) {
                                if (actionMillis > 0) delay(actionMillis)
                                Unit
                            }
                            val elapsed = elapsedMillis(started)
                            synchronized(admissionSamples) { admissionSamples += elapsed }
                            val queueing = (elapsed - actionMillis).coerceAtLeast(0.0)
                            synchronized(queueingSamples) { queueingSamples += queueing }
                            if (admission is NotificationOutboxAdmission.Backpressured) {
                                failures.computeIfAbsent(admission.reason) { AtomicInteger() }.incrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
            val workloadElapsedMillis = elapsedMillis(workloadStarted)
            val primitive = withContext(Dispatchers.IO) {
                measurePrimitiveLatencies(
                    connection = connectionA,
                    namespace = namespace,
                    hashTag = hashTag,
                    properties = properties,
                    samples = minOf(PRIMITIVE_SAMPLE_LIMIT, operations),
                )
            }
            val redisKeyCount = withContext(Dispatchers.IO) { connectionA.sync().keys("$namespace:*").size }
            ScenarioResult(
                name = "${cacheMode.name.lowercase()}-cardinality-$cardinality-churn-${churnRate.percentLabel()}",
                cardinality = cardinality,
                churnRate = churnRate,
                cacheMode = cacheMode.name.lowercase(),
                operations = operations,
                successfulOperations = operations - failures.values.sumOf { it.get() },
                backpressuredOperations = failures.values.sumOf { it.get() },
                warmupMillis = warmupMillis,
                workloadElapsedMillis = workloadElapsedMillis,
                throughputOpsPerSecond = (operations * 1_000.0) / workloadElapsedMillis.coerceAtLeast(0.001),
                admissionLatencyMs = Percentiles.from(admissionSamples),
                queueingLatencyMs = Percentiles.from(queueingSamples),
                acquireLatencyMs = primitive.acquire,
                reconcileLatencyMs = primitive.reconcile,
                renewLatencyMs = primitive.renew,
                failureReasons = failures.entries.associate { it.key.name to it.value.get() },
                uniqueClinicIds = uniqueClinics.size,
                redisKeyCountAfter = redisKeyCount,
            )
        } finally {
            coordinatorA.close()
            coordinatorB.close()
            connectionA.close()
            connectionB.close()
        }
    }

    private suspend fun measurePrimitiveLatencies(
        connection: io.lettuce.core.api.StatefulRedisConnection<String, String>,
        namespace: String,
        hashTag: String,
        properties: NotificationProperties.WorkerProperties,
        samples: Int,
    ): PrimitiveMetrics {
        val semaphore = LettuceNotificationPermitSemaphoreFactory(
            connection = connection,
            leaseTime = properties.leaseDuration,
            pollInterval = properties.pollInterval,
            namespace = namespace,
            hashTag = hashTag,
        ).create("primitive", 1)
        val acquire = mutableListOf<Double>()
        val reconcile = mutableListOf<Double>()
        val renew = mutableListOf<Double>()
        try {
            semaphore.initialize(1)
            repeat(samples) {
                val owner = SemaphoreOwnerId.random()
                val request = SemaphoreRequestId.random()
                val acquireStarted = System.nanoTime()
                val result = semaphore.acquire(owner, request, Duration.ofMillis(250))
                acquire += elapsedMillis(acquireStarted)
                val acquired = result as? NotificationPermitAcquire.Acquired ?: return@repeat
                val reconcileStarted = System.nanoTime()
                val owned = semaphore.reconcile(owner, request)
                reconcile += elapsedMillis(reconcileStarted)
                val handle = (owned as? NotificationPermitReconcile.Owned)?.handle ?: acquired.handle
                val renewStarted = System.nanoTime()
                val renewed = semaphore.renew(handle, properties.leaseDuration)
                renew += elapsedMillis(renewStarted)
                semaphore.release((renewed as? NotificationPermitRenew.Renewed)?.handle ?: handle)
            }
        } finally {
            semaphore.close()
        }
        return PrimitiveMetrics(
            acquire = Percentiles.from(acquire),
            reconcile = Percentiles.from(reconcile),
            renew = Percentiles.from(renew),
        )
    }

    private suspend fun measureLeaseRecovery(
        client: io.lettuce.core.RedisClient,
    ): LeaseRecoveryResult {
        val lease = Duration.ofSeconds(1)
        val poll = Duration.ofMillis(25)
        val namespace = "clinic-notification-benchmark-lease-${UUID.randomUUID()}"
        val hashTag = "notification-benchmark-lease-${UUID.randomUUID()}"
        val connectionA = client.connect()
        val connectionB = client.connect()
        val factoryA = LettuceNotificationPermitSemaphoreFactory(connectionA, lease, poll, namespace, hashTag)
        val factoryB = LettuceNotificationPermitSemaphoreFactory(connectionB, lease, poll, namespace, hashTag)
        val first = factoryA.create("lease-recovery", 1)
        val second = factoryB.create("lease-recovery", 1)
        try {
            first.initialize(1)
            second.initialize(1)
            val acquired = first.acquire(SemaphoreOwnerId.random(), SemaphoreRequestId.random(), Duration.ofMillis(250))
            check(acquired is NotificationPermitAcquire.Acquired) { "lease recovery setup must acquire a permit" }
            delay(lease.toMillis() + 500)
            val started = System.nanoTime()
            val recovered = second.acquire(SemaphoreOwnerId.random(), SemaphoreRequestId.random(), Duration.ofMillis(500))
            val elapsed = elapsedMillis(started)
            check(recovered is NotificationPermitAcquire.Acquired) { "lease recovery must reacquire after expiry" }
            return LeaseRecoveryResult(
                status = "reacquired",
                leaseMillis = lease.toMillis(),
                reacquireLatencyMs = elapsed,
            )
        } finally {
            first.close()
            second.close()
            connectionA.close()
            connectionB.close()
        }
    }

    private fun buildReport(
        configuration: String,
        operations: Int,
        concurrency: Int,
        actionMillis: Long,
        cardinalities: List<Int>,
        churnRates: List<Double>,
        cacheModes: List<CacheMode>,
        scenarios: List<ScenarioResult>,
        leaseRecovery: LeaseRecoveryResult,
        elapsedMillis: Double,
    ): String {
        val admission = scenarios.map { it.admissionLatencyMs }
        val queueing = scenarios.map { it.queueingLatencyMs }
        val acquire = scenarios.map { it.acquireLatencyMs }
        val reconcile = scenarios.map { it.reconcileLatencyMs }
        val renew = scenarios.map { it.renewLatencyMs }
        val workloadElapsedMillis = scenarios.sumOf { it.workloadElapsedMillis }
        val successfulOperations = scenarios.sumOf { it.successfulOperations }
        val sourceCommit = (System.getenv("GIT_COMMIT") ?: "unprovided").jsonEscape()
        val javaVersion = System.getProperty("java.version").jsonEscape()
        val osName = System.getProperty("os.name").jsonEscape()
        val osArch = System.getProperty("os.arch").jsonEscape()
        val cacheModeJson = cacheModes.joinToString(prefix = "[", postfix = "]") { "\"${it.name.lowercase()}\"" }
        return buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"benchmarkFamily\": \"io.bluetape4k.clinic.appointment.notification.RedisNotificationAdmissionBenchmark\",")
            appendLine("  \"redisImage\": \"$REDIS_IMAGE\",")
            appendLine("  \"configuration\": \"${configuration.jsonEscape()}\",")
            appendLine("""  "sourceCommit": "$sourceCommit",""")
            appendLine("  \"environment\": {")
            appendLine("""    "java": "$javaVersion",""")
            appendLine("""    "os": "$osName",""")
            appendLine("""    "arch": "$osArch"""")
            appendLine("  },")
            appendLine("  \"workload\": {")
            appendLine("    \"operationsPerScenario\": $operations,")
            appendLine("    \"concurrency\": $concurrency,")
            appendLine("    \"actionMillis\": $actionMillis,")
            appendLine("    \"globalConcurrency\": $GLOBAL_CONCURRENCY,")
            appendLine("    \"perClinicConcurrency\": $CLINIC_CONCURRENCY,")
            appendLine("    \"clinicCardinalities\": ${cardinalities.jsonInts()},")
            appendLine("    \"churnRates\": ${churnRates.jsonDoubles()},")
            appendLine("""    "cacheModes": $cacheModeJson""")
            appendLine("  },")
            appendLine("  \"summary\": {")
            appendLine("    \"elapsedMillis\": ${elapsedMillis.rounded()},")
            appendLine("""    "summaryAggregation": "maxScenarioPercentile",""")
            appendLine("    \"workloadElapsedMillis\": ${workloadElapsedMillis.rounded()},")
            appendLine("    \"throughputOpsPerSecond\": ${scenarios.sumOf { it.successfulOperations } * 1_000.0 / elapsedMillis.coerceAtLeast(0.001)} ,")
            appendLine("    \"steadyStateThroughputOpsPerSecond\": ${successfulOperations * 1_000.0 / workloadElapsedMillis.coerceAtLeast(0.001)},")
            appendLine("    \"admissionLatencyMs\": ${admission.aggregateJson()},")
            appendLine("    \"queueingLatencyMs\": ${queueing.aggregateJson()},")
            appendLine("    \"acquireLatencyMs\": ${acquire.aggregateJson()},")
            appendLine("    \"reconcileLatencyMs\": ${reconcile.aggregateJson()},")
            appendLine("    \"renewLatencyMs\": ${renew.aggregateJson()},")
            appendLine("    \"successfulOperations\": ${scenarios.sumOf { it.successfulOperations }},")
            appendLine("    \"backpressuredOperations\": ${scenarios.sumOf { it.backpressuredOperations }}")
            appendLine("  },")
            appendLine("  \"scenarios\": [")
            scenarios.forEachIndexed { index, scenario ->
                append(scenario.toJson("    "))
                if (index != scenarios.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"leaseRecovery\": ${leaseRecovery.toJson()},")
            appendLine("  \"deploymentSloEvidence\": false")
            appendLine("}")
        }
    }

    private fun workerProperties(leaseDuration: Duration = Duration.ofSeconds(2)) =
        NotificationProperties.WorkerProperties(
            concurrencyMode = NotificationConcurrencyMode.REDIS,
            leaseDuration = leaseDuration,
            providerTimeout = Duration.ofMillis(100),
            pollInterval = Duration.ofMillis(100),
            globalConcurrency = GLOBAL_CONCURRENCY,
            perClinicConcurrency = CLINIC_CONCURRENCY,
            dbClaimMaxConcurrency = GLOBAL_CONCURRENCY,
            memberResolverMaxConcurrency = GLOBAL_CONCURRENCY,
            channels = mapOf(
                "dummy" to NotificationProperties.ChannelWorkerProperties(
                    providerMaxConcurrency = GLOBAL_CONCURRENCY,
                    bulkheadMaxConcurrentCalls = GLOBAL_CONCURRENCY,
                    providerTimeout = Duration.ofMillis(100),
                ),
            ),
        )

    private fun notification(clinicId: Long, scenarioIndex: Int) = ClaimedNotification(
        id = scenarioIndex * 1_000_000L + clinicId,
        tenantGroupId = TenantGroupId(1L),
        clinicId = ClinicId(clinicId),
        appointmentId = AppointmentId(clinicId),
        memberId = MemberId("member-$scenarioIndex-$clinicId"),
        idempotencyKey = NotificationIdempotencyKey("idem-$scenarioIndex-$clinicId"),
        owner = "redis-admission-benchmark",
        token = "token-$scenarioIndex-$clinicId",
        attemptNumber = 1,
        leaseUntil = Instant.parse("2026-07-31T00:01:00Z"),
        firstAttemptAt = Instant.parse("2026-07-31T00:00:00Z"),
        claimedAt = Instant.parse("2026-07-31T00:00:00Z"),
        channel = NotificationChannelType.DUMMY,
        eventType = NotificationEventType.CONFIRMED,
        notificationSlot = NotificationSlot.CONFIRMED,
        providerKey = "dummy",
        templateKey = NotificationTemplateKey("appointment.confirmed"),
        templateVersion = NotificationTemplateVersion(1),
        parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
        eventId = NotificationEventId("event-$scenarioIndex-$clinicId"),
        parametersJson = "{}",
    )

    private fun clinicId(operationIndex: Int, cardinality: Int, churnRate: Double): Long {
        val stable = (operationIndex % cardinality) + 1L
        val churnBucket = ((operationIndex * 31) % 100) / 100.0
        return if (churnBucket < churnRate) 1_000_000L + operationIndex else stable
    }

    private fun propertyInt(name: String, default: Int): Int = System.getProperty(name)?.toIntOrNull() ?: default

    private fun propertyLong(name: String, default: Long): Long = System.getProperty(name)?.toLongOrNull() ?: default

    private fun propertyInts(name: String, default: List<Int>): List<Int> =
        System.getProperty(name)?.split(',')?.map(String::trim)?.mapNotNull(String::toIntOrNull)?.takeIf { it.isNotEmpty() }
            ?: default

    private fun propertyDoubles(name: String, default: List<Double>): List<Double> =
        System.getProperty(name)?.split(',')?.map(String::trim)?.mapNotNull(String::toDoubleOrNull)?.takeIf { it.isNotEmpty() }
            ?: default

    private fun elapsedMillis(started: Long): Double = (System.nanoTime() - started) / 1_000_000.0

    private fun extractSummaryP99(report: String, metric: String): String =
        Regex("\\\"$metric\\\"\\s*:\\s*\\{[^}]*\\\"p99\\\"\\s*:\\s*([0-9.]+)")
            .find(report)
            ?.groupValues
            ?.getOrNull(1)
            ?: "unknown"

    private enum class CacheMode { COLD, WARM }

    private data class PrimitiveMetrics(
        val acquire: Percentiles,
        val reconcile: Percentiles,
        val renew: Percentiles,
    )

    private data class ScenarioResult(
        val name: String,
        val cardinality: Int,
        val churnRate: Double,
        val cacheMode: String,
        val operations: Int,
        val successfulOperations: Int,
        val backpressuredOperations: Int,
        val warmupMillis: Double,
        val workloadElapsedMillis: Double,
        val throughputOpsPerSecond: Double,
        val admissionLatencyMs: Percentiles,
        val queueingLatencyMs: Percentiles,
        val acquireLatencyMs: Percentiles,
        val reconcileLatencyMs: Percentiles,
        val renewLatencyMs: Percentiles,
        val failureReasons: Map<String, Int>,
        val uniqueClinicIds: Int,
        val redisKeyCountAfter: Int,
    ) {
        fun toJson(indent: String): String = buildString {
            appendLine("""$indent{""")
            appendLine("""$indent  "name": "${name.jsonEscape()}",""")
            appendLine("""$indent  "clinicCardinality": $cardinality,""")
            appendLine("""$indent  "churnRate": $churnRate,""")
            appendLine("""$indent  "cacheMode": "$cacheMode",""")
            appendLine("""$indent  "operations": $operations,""")
            appendLine("""$indent  "successfulOperations": $successfulOperations,""")
            appendLine("""$indent  "backpressuredOperations": $backpressuredOperations,""")
            appendLine("""$indent  "warmupMillis": ${warmupMillis.rounded()},""")
            appendLine("""$indent  "workloadElapsedMillis": ${workloadElapsedMillis.rounded()},""")
            appendLine("""$indent  "throughputOpsPerSecond": ${throughputOpsPerSecond.rounded()},""")
            appendLine("""$indent  "admissionLatencyMs": ${admissionLatencyMs.toJson()},""")
            appendLine("""$indent  "queueingLatencyMs": ${queueingLatencyMs.toJson()},""")
            appendLine("""$indent  "acquireLatencyMs": ${acquireLatencyMs.toJson()},""")
            appendLine("""$indent  "reconcileLatencyMs": ${reconcileLatencyMs.toJson()},""")
            appendLine("""$indent  "renewLatencyMs": ${renewLatencyMs.toJson()},""")
            appendLine("""$indent  "failureReasons": ${failureReasons.toJson()},""")
            appendLine("""$indent  "uniqueClinicIds": $uniqueClinicIds,""")
            appendLine("""$indent  "redisKeyCountAfter": $redisKeyCountAfter""")
            append("$indent}")
        }
    }

    private data class LeaseRecoveryResult(
        val status: String,
        val leaseMillis: Long,
        val reacquireLatencyMs: Double,
    ) {
        fun toJson(): String =
            """{"status":"${status.jsonEscape()}","leaseMillis":$leaseMillis,"reacquireLatencyMs":${reacquireLatencyMs.rounded()}}"""
    }

    private data class Percentiles(
        val sampleCount: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
    ) {
        fun toJson(): String =
            """{"sampleCount":$sampleCount,"p50":${p50.rounded()},"p95":${p95.rounded()},"p99":${p99.rounded()}}"""

        companion object {
            fun from(samples: List<Double>): Percentiles {
                val sorted = samples.filter { it.isFinite() }.sorted()
                require(sorted.isNotEmpty()) { "benchmark metric must contain a sample" }
                return Percentiles(
                    sampleCount = sorted.size,
                    p50 = percentile(sorted, 0.50),
                    p95 = percentile(sorted, 0.95),
                    p99 = percentile(sorted, 0.99),
                )
            }

            private fun percentile(sorted: List<Double>, quantile: Double): Double {
                val index = (ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)
                return max(sorted[index], 0.001)
            }
        }
    }

    private fun List<Percentiles>.aggregateJson(): String = Percentiles(
        sampleCount = sumOf { it.sampleCount },
        p50 = maxOf { it.p50 },
        p95 = maxOf { it.p95 },
        p99 = maxOf { it.p99 },
    ).toJson()

    private fun Map<String, Int>.toJson(): String =
        entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") { "\"${it.key.jsonEscape()}\":${it.value}" }

    private fun List<Int>.jsonInts(): String = joinToString(prefix = "[", postfix = "]")

    private fun List<Double>.jsonDoubles(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun Double.rounded(): String = "%.3f".format(java.util.Locale.ROOT, this)

    private fun Double.percentLabel(): String = toString().replace('.', '_')
}

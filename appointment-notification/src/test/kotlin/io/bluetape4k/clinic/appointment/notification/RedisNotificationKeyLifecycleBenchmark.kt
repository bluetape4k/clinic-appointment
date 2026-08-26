package io.bluetape4k.clinic.appointment.notification

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
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
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Locale
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
 * Issue #372 전용 Redis key lifecycle characterization harness입니다.
 *
 * 기존 admission benchmark의 생산 의미론이나 Issue #369 sealed harness를
 * 변경하지 않고, workload 종료·반복 장기 실행·coordinator 정상 종료·lease
 * window 이후의 key prefix와 TTL 관측을 추가합니다. 각 실행은 격리된 Redis
 * 8.8 컨테이너와 UUID namespace를 사용하며 결과는 JSON으로만 보존합니다.
 */
object RedisNotificationKeyLifecycleBenchmark {

    private const val DEFAULT_OUTPUT = "build/reports/redis-key-lifecycle/redis-notification-key-lifecycle.json"
    private const val REDIS_IMAGE = "redis:8.8"
    private const val DEFAULT_OPERATIONS = 80
    private const val DEFAULT_CONCURRENCY = 16
    private const val DEFAULT_ACTION_MILLIS = 2L
    private const val DEFAULT_RETENTION_WAIT_MILLIS = 2_500L
    private const val GLOBAL_CONCURRENCY = 8
    private const val CLINIC_CONCURRENCY = 2

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val output = Path.of(System.getProperty("redis.lifecycle.benchmark.output", DEFAULT_OUTPUT))
        val configuration = System.getProperty("redis.lifecycle.benchmark.configuration", "main")
        val operations = propertyInt("redis.lifecycle.benchmark.operations", DEFAULT_OPERATIONS)
        val concurrency = propertyInt("redis.lifecycle.benchmark.concurrency", DEFAULT_CONCURRENCY)
        val actionMillis = propertyLong("redis.lifecycle.benchmark.actionMillis", DEFAULT_ACTION_MILLIS)
        val retentionWaitMillis = propertyLong(
            "redis.lifecycle.benchmark.retentionWaitMillis",
            DEFAULT_RETENTION_WAIT_MILLIS,
        )
        val longRunRounds = propertyInt(
            "redis.lifecycle.benchmark.longRunRounds",
            if (configuration == "smoke") 1 else 2,
        )
        val cardinalities = propertyInts(
            "redis.lifecycle.benchmark.cardinalities",
            if (configuration == "smoke") listOf(10, 100) else listOf(10, 100, 1_000),
        )
        val churnRates = propertyDoubles(
            "redis.lifecycle.benchmark.churnRates",
            if (configuration == "smoke") listOf(0.0, 1.0) else listOf(0.0, 0.5, 1.0),
        )
        val cacheModes = if (configuration == "smoke") listOf(CacheMode.COLD) else CacheMode.entries.toList()
        require(operations > 0) { "operations must be positive" }
        require(concurrency > 0) { "concurrency must be positive" }
        require(actionMillis >= 0) { "actionMillis must not be negative" }
        require(retentionWaitMillis >= 0) { "retentionWaitMillis must not be negative" }
        require(longRunRounds > 0) { "longRunRounds must be positive" }

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
                                    longRunRounds = longRunRounds,
                                    retentionWaitMillis = retentionWaitMillis,
                                    client = client,
                                ),
                            )
                        }
                    }
                }
            }
            val leaseRecovery = measureLeaseRecovery(client)
            val report = buildReport(
                configuration = configuration,
                operations = operations,
                concurrency = concurrency,
                actionMillis = actionMillis,
                longRunRounds = longRunRounds,
                retentionWaitMillis = retentionWaitMillis,
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
            println("Redis notification key lifecycle benchmark written to $output")
            println("configuration=$configuration scenarios=${scenarios.size} redis=$REDIS_IMAGE")
            println("summary admission p99=${extractSummaryP99(report)}ms")
            println("summary lifecycle coverage=${extractCoverage(report)}")
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
        longRunRounds: Int,
        retentionWaitMillis: Long,
        client: RedisClient,
    ): ScenarioResult {
        val namespace = "clinic-notification-lifecycle-${UUID.randomUUID()}"
        val hashTag = "notification-lifecycle-${UUID.randomUUID()}"
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
        var coordinatorsClosed = false
        val admissionSamples = mutableListOf<Double>()
        val failures = ConcurrentHashMap<NotificationPermitFailureReason, AtomicInteger>()
        val uniqueClinics = ConcurrentHashMap.newKeySet<Long>()
        var warmupMillis = 0.0
        var workloadElapsedMillis = 0.0
        var successfulOperations = 0
        var backpressuredOperations = 0
        val longRunSnapshots = mutableListOf<KeySnapshot>()

        return try {
            if (cacheMode == CacheMode.WARM) {
                val warmupStarted = System.nanoTime()
                for (clinicId in 1L..cardinality.toLong()) {
                    coordinatorA.withPermit(notification(clinicId, scenarioIndex)) { Unit }
                }
                warmupMillis = elapsedMillis(warmupStarted)
            }

            val initial = runWorkload(
                scenarioIndex = scenarioIndex,
                cardinality = cardinality,
                churnRate = churnRate,
                operations = operations,
                concurrency = concurrency,
                actionMillis = actionMillis,
                coordinatorA = coordinatorA,
                coordinatorB = coordinatorB,
                admissionSamples = admissionSamples,
                failures = failures,
                uniqueClinics = uniqueClinics,
            )
            workloadElapsedMillis += initial.elapsedMillis
            successfulOperations += initial.successfulOperations
            backpressuredOperations += initial.backpressuredOperations

            val workloadEnd = snapshot(connectionA, namespace, "workload-end")
            repeat(longRunRounds) { round ->
                val roundResult = runWorkload(
                    scenarioIndex = scenarioIndex,
                    cardinality = cardinality,
                    churnRate = churnRate,
                    operations = operations,
                    concurrency = concurrency,
                    actionMillis = actionMillis,
                    coordinatorA = coordinatorA,
                    coordinatorB = coordinatorB,
                    admissionSamples = admissionSamples,
                    failures = failures,
                    uniqueClinics = uniqueClinics,
                )
                workloadElapsedMillis += roundResult.elapsedMillis
                successfulOperations += roundResult.successfulOperations
                backpressuredOperations += roundResult.backpressuredOperations
                longRunSnapshots += snapshot(connectionA, namespace, "long-run-round-${round + 1}")
            }

            coordinatorA.close()
            coordinatorB.close()
            coordinatorsClosed = true
            val afterCoordinatorClose = snapshot(connectionA, namespace, "after-coordinator-close")
            delay(retentionWaitMillis)
            val afterRetentionWindow = snapshot(connectionA, namespace, "after-retention-window")
            val lifecycle = LifecycleObservation(
                workloadEnd = workloadEnd,
                longRun = longRunSnapshots.toList(),
                afterCoordinatorClose = afterCoordinatorClose,
                afterRetentionWindow = afterRetentionWindow,
                retentionWaitMillis = retentionWaitMillis,
            )
            ScenarioResult(
                name = "${cacheMode.name.lowercase()}-cardinality-$cardinality-churn-${churnRate.percentLabel()}",
                cardinality = cardinality,
                churnRate = churnRate,
                cacheMode = cacheMode.name.lowercase(),
                operationsPerRound = operations,
                longRunRounds = longRunRounds,
                successfulOperations = successfulOperations,
                backpressuredOperations = backpressuredOperations,
                warmupMillis = warmupMillis,
                workloadElapsedMillis = workloadElapsedMillis,
                admissionLatencyMs = Percentiles.from(admissionSamples),
                failureReasons = failures.entries.associate { it.key.name to it.value.get() },
                uniqueClinicIds = uniqueClinics.size,
                lifecycle = lifecycle,
            )
        } finally {
            if (!coordinatorsClosed) {
                runCatching { coordinatorA.close() }
                runCatching { coordinatorB.close() }
            }
            connectionA.close()
            connectionB.close()
        }
    }

    private suspend fun runWorkload(
        scenarioIndex: Int,
        cardinality: Int,
        churnRate: Double,
        operations: Int,
        concurrency: Int,
        actionMillis: Long,
        coordinatorA: RedisNotificationOutboxConcurrencyCoordinator,
        coordinatorB: RedisNotificationOutboxConcurrencyCoordinator,
        admissionSamples: MutableList<Double>,
        failures: ConcurrentHashMap<NotificationPermitFailureReason, AtomicInteger>,
        uniqueClinics: MutableSet<Long>,
    ): WorkloadResult {
        val slots = Semaphore(concurrency)
        val startedAt = System.nanoTime()
        val failuresBefore = failures.values.sumOf { it.get() }
        supervisorScope {
            (0 until operations).map { operationIndex ->
                async(Dispatchers.IO) {
                    slots.withPermit {
                        val clinicId = clinicId(operationIndex, cardinality, churnRate)
                        uniqueClinics += clinicId
                        val operationStarted = System.nanoTime()
                        val coordinator = if (operationIndex % 2 == 0) coordinatorA else coordinatorB
                        val admission = coordinator.withPermit(notification(clinicId, scenarioIndex)) {
                            if (actionMillis > 0) delay(actionMillis)
                            Unit
                        }
                        val elapsed = elapsedMillis(operationStarted)
                        synchronized(admissionSamples) { admissionSamples += elapsed }
                        if (admission is NotificationOutboxAdmission.Backpressured) {
                            failures.computeIfAbsent(admission.reason) { AtomicInteger() }.incrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        val backpressured = failures.values.sumOf { it.get() } - failuresBefore
        return WorkloadResult(
            elapsedMillis = elapsedMillis(startedAt),
            successfulOperations = operations - backpressured,
            backpressuredOperations = backpressured,
        )
    }

    private fun snapshot(
        connection: StatefulRedisConnection<String, String>,
        namespace: String,
        stage: String,
    ): KeySnapshot {
        val sync = connection.sync()
        val keys = sync.keys("$namespace:*").sorted()
        val ttlBuckets = linkedMapOf("persistent" to 0, "expiring" to 0, "missing" to 0)
        val keyKinds = linkedMapOf<String, Int>()
        keys.forEach { key ->
            val ttl = sync.pttl(key)
            when {
                ttl == -1L -> ttlBuckets.compute("persistent") { _, value -> value!! + 1 }
                ttl == -2L -> ttlBuckets.compute("missing") { _, value -> value!! + 1 }
                ttl >= 0L -> ttlBuckets.compute("expiring") { _, value -> value!! + 1 }
                else -> error("unexpected Redis PTTL=$ttl for $key")
            }
            val kind = keyKind(key)
            keyKinds.compute(kind) { _, value -> (value ?: 0) + 1 }
        }
        return KeySnapshot(
            stage = stage,
            keyCount = keys.size,
            ttlBuckets = ttlBuckets,
            keyKinds = keyKinds,
        )
    }

    private fun keyKind(key: String): String = when {
        ":capacity-contract:" in key -> "capacity-contract"
        key.endsWith(":available") -> "available"
        key.endsWith(":generation") -> "generation"
        key.endsWith(":capacity") -> "capacity"
        key.endsWith(":allocations") -> "allocations"
        key.endsWith(":allocation-leases") -> "allocation-leases"
        key.endsWith(":leases") -> "leases"
        key.endsWith(":deadlines") -> "deadlines"
        key.endsWith(":requests") -> "requests"
        else -> "other"
    }

    private suspend fun measureLeaseRecovery(client: RedisClient): LeaseRecoveryResult {
        val lease = Duration.ofSeconds(1)
        val poll = Duration.ofMillis(25)
        val namespace = "clinic-notification-lifecycle-lease-${UUID.randomUUID()}"
        val hashTag = "notification-lifecycle-lease-${UUID.randomUUID()}"
        val connectionA = client.connect()
        val connectionB = client.connect()
        val factoryA = LettuceNotificationPermitSemaphoreFactory(connectionA, lease, poll, namespace, hashTag)
        val factoryB = LettuceNotificationPermitSemaphoreFactory(connectionB, lease, poll, namespace, hashTag)
        val first = factoryA.create("lease-recovery", 1)
        val second = factoryB.create("lease-recovery", 1)
        return try {
            first.initialize(1)
            second.initialize(1)
            val acquired = first.acquire(SemaphoreOwnerId.random(), SemaphoreRequestId.random(), Duration.ofMillis(250))
            check(acquired is NotificationPermitAcquire.Acquired) { "lease recovery setup must acquire a permit" }
            delay(lease.toMillis() + 500)
            val started = System.nanoTime()
            val recovered = second.acquire(SemaphoreOwnerId.random(), SemaphoreRequestId.random(), Duration.ofMillis(500))
            val elapsed = elapsedMillis(started)
            check(recovered is NotificationPermitAcquire.Acquired) { "lease recovery must reacquire after expiry" }
            LeaseRecoveryResult("reacquired", lease.toMillis(), elapsed)
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
        longRunRounds: Int,
        retentionWaitMillis: Long,
        cardinalities: List<Int>,
        churnRates: List<Double>,
        cacheModes: List<CacheMode>,
        scenarios: List<ScenarioResult>,
        leaseRecovery: LeaseRecoveryResult,
        elapsedMillis: Double,
    ): String {
        val sourceCommit = (System.getenv("GIT_COMMIT") ?: "unprovided").jsonEscape()
        val javaVersion = System.getProperty("java.version").jsonEscape()
        val osName = System.getProperty("os.name").jsonEscape()
        val osArch = System.getProperty("os.arch").jsonEscape()
        val cacheModeJson = cacheModes.joinToString(prefix = "[", postfix = "]") { "\"${it.name.lowercase()}\"" }
        val lifecycleCoverage = scenarios.count { it.lifecycle.hasAllRequiredStages(longRunRounds) } / scenarios.size.toDouble()
        val admission = Percentiles.aggregate(scenarios.map { it.admissionLatencyMs })
        val successfulOperations = scenarios.sumOf { it.successfulOperations }
        val backpressuredOperations = scenarios.sumOf { it.backpressuredOperations }
        return buildString {
            appendLine("{")
            appendLine("  \"schemaVersion\": 1,")
            appendLine("  \"benchmarkFamily\": \"io.bluetape4k.clinic.appointment.notification.RedisNotificationKeyLifecycleBenchmark\",")
            appendLine("  \"redisImage\": \"$REDIS_IMAGE\",")
            appendLine("  \"configuration\": \"${configuration.jsonEscape()}\",")
            appendLine("  \"sourceCommit\": \"$sourceCommit\",")
            appendLine("  \"environment\": {\"java\": \"$javaVersion\", \"os\": \"$osName\", \"arch\": \"$osArch\"},")
            appendLine("  \"workload\": {")
            appendLine("    \"operationsPerRound\": $operations,")
            appendLine("    \"longRunRounds\": $longRunRounds,")
            appendLine("    \"concurrency\": $concurrency,")
            appendLine("    \"actionMillis\": $actionMillis,")
            appendLine("    \"retentionWaitMillis\": $retentionWaitMillis,")
            appendLine("    \"globalConcurrency\": $GLOBAL_CONCURRENCY,")
            appendLine("    \"perClinicConcurrency\": $CLINIC_CONCURRENCY,")
            appendLine("    \"clinicCardinalities\": ${cardinalities.jsonInts()},")
            appendLine("    \"churnRates\": ${churnRates.jsonDoubles()},")
            appendLine("    \"cacheModes\": $cacheModeJson")
            appendLine("  },")
            appendLine("  \"summary\": {")
            appendLine("    \"elapsedMillis\": ${elapsedMillis.rounded()},")
            appendLine("    \"admissionLatencyMs\": ${admission.toJson()},")
            appendLine("    \"successfulOperations\": $successfulOperations,")
            appendLine("    \"backpressuredOperations\": $backpressuredOperations,")
            appendLine("    \"lifecycleObservationCoverage\": ${format(lifecycleCoverage)},")
            appendLine("    \"requiredLifecycleStages\": [\"workload-end\", \"long-run\", \"after-coordinator-close\", \"after-retention-window\"],")
            appendLine("    \"persistentKeyCountAfterRetentionMax\": ${scenarios.maxOf { it.lifecycle.afterRetentionWindow.keyCount }}")
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
        owner = "redis-key-lifecycle-benchmark",
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

    private fun extractSummaryP99(report: String): String =
        Regex("\\\"admissionLatencyMs\\\"\\s*:\\s*\\{[^}]*\\\"p99\\\"\\s*:\\s*([0-9.]+)")
            .find(report)?.groupValues?.getOrNull(1) ?: "unknown"

    private fun extractCoverage(report: String): String =
        Regex("\\\"lifecycleObservationCoverage\\\"\\s*:\\s*([0-9.]+)")
            .find(report)?.groupValues?.getOrNull(1) ?: "unknown"

    private enum class CacheMode { COLD, WARM }

    private data class WorkloadResult(
        val elapsedMillis: Double,
        val successfulOperations: Int,
        val backpressuredOperations: Int,
    )

    private data class KeySnapshot(
        val stage: String,
        val keyCount: Int,
        val ttlBuckets: Map<String, Int>,
        val keyKinds: Map<String, Int>,
    ) {
        fun toJson(indent: String): String = buildString {
            appendLine("$indent{")
            appendLine("$indent  \"stage\": \"${stage.jsonEscape()}\",")
            appendLine("$indent  \"keyCount\": $keyCount,")
            appendLine("$indent  \"ttlBuckets\": ${ttlBuckets.toJson()},")
            appendLine("$indent  \"keyKinds\": ${keyKinds.toJson()}")
            append("$indent}")
        }
    }

    private data class LifecycleObservation(
        val workloadEnd: KeySnapshot,
        val longRun: List<KeySnapshot>,
        val afterCoordinatorClose: KeySnapshot,
        val afterRetentionWindow: KeySnapshot,
        val retentionWaitMillis: Long,
    ) {
        fun hasAllRequiredStages(rounds: Int): Boolean =
            workloadEnd.stage == "workload-end" &&
                longRun.size == rounds &&
                afterCoordinatorClose.stage == "after-coordinator-close" &&
                afterRetentionWindow.stage == "after-retention-window"

        fun toJson(indent: String): String = buildString {
            val childIndent = "$indent  "
            appendLine("$indent{")
            appendLine("$indent  \"workloadEnd\": ${workloadEnd.toJson(childIndent)},")
            appendLine("$indent  \"longRun\": [")
            longRun.forEachIndexed { index, snapshot ->
                append(snapshot.toJson("$indent    "))
                if (index != longRun.lastIndex) append(',')
                appendLine()
            }
            appendLine("$indent  ],")
            appendLine("$indent  \"afterCoordinatorClose\": ${afterCoordinatorClose.toJson(childIndent)},")
            appendLine("$indent  \"afterRetentionWindow\": ${afterRetentionWindow.toJson(childIndent)},")
            appendLine("$indent  \"retentionWaitMillis\": $retentionWaitMillis")
            append("$indent}")
        }
    }

    private data class ScenarioResult(
        val name: String,
        val cardinality: Int,
        val churnRate: Double,
        val cacheMode: String,
        val operationsPerRound: Int,
        val longRunRounds: Int,
        val successfulOperations: Int,
        val backpressuredOperations: Int,
        val warmupMillis: Double,
        val workloadElapsedMillis: Double,
        val admissionLatencyMs: Percentiles,
        val failureReasons: Map<String, Int>,
        val uniqueClinicIds: Int,
        val lifecycle: LifecycleObservation,
    ) {
        fun toJson(indent: String): String = buildString {
            appendLine("$indent{")
            appendLine("$indent  \"name\": \"${name.jsonEscape()}\",")
            appendLine("$indent  \"clinicCardinality\": $cardinality,")
            appendLine("$indent  \"churnRate\": $churnRate,")
            appendLine("$indent  \"cacheMode\": \"$cacheMode\",")
            appendLine("$indent  \"operationsPerRound\": $operationsPerRound,")
            appendLine("$indent  \"longRunRounds\": $longRunRounds,")
            appendLine("$indent  \"successfulOperations\": $successfulOperations,")
            appendLine("$indent  \"backpressuredOperations\": $backpressuredOperations,")
            appendLine("$indent  \"warmupMillis\": ${warmupMillis.rounded()},")
            appendLine("$indent  \"workloadElapsedMillis\": ${workloadElapsedMillis.rounded()},")
            appendLine("$indent  \"admissionLatencyMs\": ${admissionLatencyMs.toJson()},")
            appendLine("$indent  \"failureReasons\": ${failureReasons.toJson()},")
            appendLine("$indent  \"uniqueClinicIds\": $uniqueClinicIds,")
            appendLine("$indent  \"lifecycle\": ${lifecycle.toJson("$indent  ")}")
            append("$indent}")
        }
    }

    private data class LeaseRecoveryResult(
        val status: String,
        val leaseMillis: Long,
        val reacquireLatencyMs: Double,
    ) {
        fun toJson(): String =
            "{\"status\":\"${status.jsonEscape()}\",\"leaseMillis\":$leaseMillis,\"reacquireLatencyMs\":${reacquireLatencyMs.rounded()}}"
    }

    private data class Percentiles(
        val sampleCount: Int,
        val p50: Double,
        val p95: Double,
        val p99: Double,
    ) {
        fun toJson(): String =
            "{\"sampleCount\":$sampleCount,\"p50\":${p50.rounded()},\"p95\":${p95.rounded()},\"p99\":${p99.rounded()}}"

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

            fun aggregate(metrics: List<Percentiles>): Percentiles = Percentiles(
                sampleCount = metrics.sumOf { it.sampleCount },
                p50 = metrics.maxOf { it.p50 },
                p95 = metrics.maxOf { it.p95 },
                p99 = metrics.maxOf { it.p99 },
            )

            private fun percentile(sorted: List<Double>, quantile: Double): Double {
                val index = (ceil(sorted.size * quantile).toInt() - 1).coerceIn(0, sorted.lastIndex)
                return max(sorted[index], 0.001)
            }
        }
    }

    private fun Map<String, Int>.toJson(): String =
        entries.sortedBy { it.key }.joinToString(prefix = "{", postfix = "}") {
            "\"${it.key.jsonEscape()}\":${it.value}"
        }

    private fun List<Int>.jsonInts(): String = joinToString(prefix = "[", postfix = "]")

    private fun List<Double>.jsonDoubles(): String = joinToString(prefix = "[", postfix = "]") { it.toString() }

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun Double.rounded(): String = format(this)

    private fun format(value: Double): String = "%.3f".format(Locale.ROOT, value)

    private fun Double.percentLabel(): String = toString().replace('.', '_')
}

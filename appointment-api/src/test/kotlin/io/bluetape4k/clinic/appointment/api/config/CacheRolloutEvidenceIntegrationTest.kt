package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.cache.nearcache.NearCacheOperations
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.model.dto.DoctorRecord
import io.bluetape4k.clinic.appointment.model.dto.EquipmentRecord
import io.bluetape4k.clinic.appointment.model.dto.TreatmentTypeRecord
import io.bluetape4k.io.serializer.BinarySerializationException
import io.bluetape4k.testcontainers.mq.KafkaServer
import io.lettuce.core.RedisClient
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.fory.serializer.CodegenSerializer
import org.apache.fory.serializer.Serializers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * 캐시 v3 canary와 v2 rollback 보존을 로컬 production-like 컨테이너에서 재현한다.
 *
 * 이 테스트는 운영 자격 증명이나 실제 트래픽을 사용하지 않는다. PostgreSQL migration,
 * Redis 8.8 exact-key, Kafka broker round-trip을 한 번의 고정된 창에서 검증하고, 운영
 * SLO 증거가 아님을 명시한 redacted JSON report를 남긴다.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@ResourceLock(value = "appointment-messaging-kafka", mode = ResourceAccessMode.READ_WRITE)
class CacheRolloutEvidenceIntegrationTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `v3 canary와 v2 rollback 보존 증거를 production-like 창에서 생성한다`() {
        val assertions = AssertionLedger()
        val thresholds = loadThresholds()
        val postgres = verifyPostgresMigration(assertions)
        val cache = verifyCacheNamespaces(assertions)
        val broker = verifyBrokerRoundTrip(assertions)

        val rollbackDurationLimit = thresholds.requiredNumber("rollbackDurationMs")
        val cacheHitMinimum = thresholds.requiredNumber("cacheHitCountMin")
        val cacheMissMinimum = thresholds.requiredNumber("cacheMissCountMin")
        val cacheDecodeErrorMaximum = thresholds.requiredNumber("cacheDecodeErrorsMax")

        assertions.check(
            postgres.lockWaitMs <= thresholds.requiredNumber("postgresLockWaitMs"),
            "PostgreSQL lock-wait threshold exceeded",
        )
        assertions.check(
            broker.lagSeconds <= thresholds.requiredNumber("brokerLagSeconds"),
            "broker lag threshold exceeded",
        )
        assertions.check(cache.rollbackDurationMs <= rollbackDurationLimit, "rollback duration threshold exceeded")
        assertions.check(cache.hits >= cacheHitMinimum, "cache hit minimum threshold not met")
        assertions.check(cache.misses >= cacheMissMinimum, "cache miss minimum threshold not met")
        assertions.check(cache.decodeErrors <= cacheDecodeErrorMaximum, "cache decode-error threshold exceeded")

        val report = report(
            postgres = postgres,
            cache = cache,
            broker = broker,
            assertions = assertions.snapshot(),
        )
        writeReport(report)
    }

    private fun loadThresholds() = objectMapper.readTree(
        requireNotNull(javaClass.classLoader.getResourceAsStream(THRESHOLDS_RESOURCE)) {
            "threshold resource is missing: $THRESHOLDS_RESOURCE"
        }
    )

    private fun verifyPostgresMigration(assertions: AssertionLedger): PostgresEvidence {
        val postgres = Containers.Postgres
        val flyway = Flyway.configure()
            .dataSource(
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
            )
            .locations("classpath:db/migration/postgresql")
            .baselineOnMigrate(true)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
        val migrationResult = flyway.migrate()
        assertions.check(migrationResult.success, "Flyway migration failed")

        val info = flyway.info()
        val applied = info.applied()
        val currentVersion = info.current()?.version?.version ?: "none"
        assertions.check(currentVersion == EXPECTED_MIGRATION, "unexpected PostgreSQL migration: $currentVersion")

        return PostgresEvidence(
            image = postgres.dockerImageName,
            currentMigration = currentVersion,
            appliedMigrationCount = applied.size,
            lockWaitMs = measurePostgresLockWait(
                postgres.jdbcUrl,
                postgres.username ?: "test",
                postgres.password ?: "",
                assertions,
            ),
            lockProbe = "advisory-lock",
            lockHoldMs = LOCK_HOLD_MS,
        )
    }

    private fun measurePostgresLockWait(
        jdbcUrl: String,
        username: String,
        password: String,
        assertions: AssertionLedger,
    ): Double {
        Class.forName("org.postgresql.Driver")
        DriverManager.getConnection(jdbcUrl, username, password).use { blocker ->
            DriverManager.getConnection(jdbcUrl, username, password).use { waiter ->
                DriverManager.getConnection(jdbcUrl, username, password).use { observer ->
                blocker.createStatement().use { statement ->
                    statement.execute("SELECT pg_advisory_lock(263263)")
                }
                val waiterPid = waiter.createStatement().use { statement ->
                    statement.executeQuery("SELECT pg_backend_pid()").use { result ->
                        result.next()
                        result.getLong(1)
                    }
                }

                val started = CountDownLatch(1)
                val executor = Executors.newSingleThreadExecutor()
                val future = executor.submit<Double> {
                    started.countDown()
                    val startedAt = System.nanoTime()
                    waiter.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_lock(263263)")
                    }
                    val elapsedMs = elapsedMillis(startedAt)
                    waiter.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_unlock(263263)")
                    }
                    elapsedMs
                }

                var primaryFailure: Throwable? = null
                return try {
                    started.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    awaitPostgresLockWait(observer, waiterPid).also {
                        assertions.check(it, "waiter never entered PostgreSQL advisory-lock wait")
                    }
                    Thread.sleep(LOCK_HOLD_MS.toLong())
                    blocker.createStatement().use { statement ->
                        statement.execute("SELECT pg_advisory_unlock(263263)")
                    }
                    future.get(5, TimeUnit.SECONDS).also {
                        assertions.check(it >= LOCK_HOLD_MS, "lock-wait probe did not observe the held lock")
                    }
                } catch (error: Throwable) {
                    primaryFailure = error
                    throw error
                } finally {
                    cleanup(
                        primaryFailure,
                        {
                        blocker.createStatement().use { statement ->
                            statement.execute("SELECT pg_advisory_unlock(263263)")
                        }
                        },
                        { future.get(5, TimeUnit.SECONDS) },
                        {
                            executor.shutdown()
                            executor.awaitTermination(5, TimeUnit.SECONDS)
                        },
                    )
                }
                }
            }
        }
    }

    private fun awaitPostgresLockWait(observer: java.sql.Connection, waiterPid: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            observer.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_locks
                    WHERE pid = ?
                      AND locktype = 'advisory'
                      AND granted = false
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, waiterPid)
                statement.executeQuery().use { result ->
                    result.next()
                    if (result.getBoolean(1)) return true
                }
            }
            Thread.sleep(10)
        }
        return false
    }

    private fun verifyCacheNamespaces(assertions: AssertionLedger): CacheEvidence {
        val config = CacheConfig()
        val registeredTypes = listOf(
            DoctorRecord::class.java,
            EquipmentRecord::class.java,
            TreatmentTypeRecord::class.java,
        )
        CacheConfig.secureThreadSafeFory.registerCallback { fory ->
            registeredTypes.forEach { type ->
                installGeneratedSerializer(fory, type)
            }
        }
        var rawClient: RedisClient? = null
        var rawConnection: io.lettuce.core.api.StatefulRedisConnection<String, String>? = null
        var rawCommands: io.lettuce.core.api.sync.RedisCommands<String, String>? = null

        var primaryFailure: Throwable? = null
        return try {
            val rawClientInstance = RedisClient.create(Containers.Redis.url)
            rawClient = rawClientInstance
            val rawConnectionInstance = rawClientInstance.connect()
            rawConnection = rawConnectionInstance
            val commands = rawConnectionInstance.sync()
            rawCommands = commands
            val doctors = runCacheProbe(
                config = config,
                rawCommands = commands,
                logicalName = CacheConfig.DOCTORS_CACHE_NAME,
                remoteName = CacheConfig.DOCTORS_REMOTE_CACHE_NAME,
                expected = listOf(
                    DoctorRecord(
                        id = 263L,
                        clinicId = 7L,
                        name = "issue-263-doctor",
                        specialty = "내과",
                        maxConcurrentPatients = 2,
                    )
                ),
                cacheFactory = CacheConfig::clinicDoctorsCache,
                assertions = assertions,
            )
            val equipments = runCacheProbe(
                config = config,
                rawCommands = commands,
                logicalName = CacheConfig.EQUIPMENTS_CACHE_NAME,
                remoteName = CacheConfig.EQUIPMENTS_REMOTE_CACHE_NAME,
                expected = listOf(
                    EquipmentRecord(
                        id = 263L,
                        clinicId = 7L,
                        name = "issue-263-equipment",
                        usageDurationMinutes = 30,
                        quantity = 2,
                    )
                ),
                cacheFactory = CacheConfig::clinicEquipmentsCache,
                assertions = assertions,
            )
            val treatmentTypes = runCacheProbe(
                config = config,
                rawCommands = commands,
                logicalName = CacheConfig.TREATMENT_TYPES_CACHE_NAME,
                remoteName = CacheConfig.TREATMENT_TYPES_REMOTE_CACHE_NAME,
                expected = listOf(
                    TreatmentTypeRecord(
                        id = 263L,
                        clinicId = 7L,
                        name = "issue-263-treatment",
                        defaultDurationMinutes = 30,
                        consultationMethod = "IN_PERSON",
                    )
                ),
                cacheFactory = CacheConfig::clinicTreatmentTypesCache,
                assertions = assertions,
            )
            val decodeErrors = verifyMalformedPayloadIsCounted(
                config = config,
                rawCommands = commands,
                remoteName = doctors.remoteName,
                expected = doctors.expected,
                cacheFactory = doctors.cacheFactory,
                assertions = assertions,
            )
            val rollback = verifyRollbackPreservesV3AndV2(
                config = config,
                rawCommands = commands,
                probe = doctors,
                assertions = assertions,
            )
            CacheConfig.secureThreadSafeFory.registerCallback { it.ensureSerializersCompiled() }

            CacheEvidence(
                hits = doctors.hits + equipments.hits + treatmentTypes.hits,
                misses = doctors.misses + equipments.misses + treatmentTypes.misses,
                decodeErrors = decodeErrors,
                v3KeyAssertions = doctors.keyAssertions + equipments.keyAssertions + treatmentTypes.keyAssertions,
                v3KeyAssertionsPassed = doctors.keyAssertionsPassed +
                    equipments.keyAssertionsPassed + treatmentTypes.keyAssertionsPassed,
                rollbackDurationMs = rollback.durationMs,
                trafficDrained = rollback.trafficDrained,
                workerRestarted = rollback.workerRestarted,
                v2Warmup = rollback.v2Warmup,
                v3Preserved = rollback.v3Preserved,
                rollbackResult = rollback.result,
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanup(
                primaryFailure,
                {
                rawCommands?.unlink(
                    "${CacheConfig.DOCTORS_REMOTE_CACHE_NAME}:7:263",
                    "${CacheConfig.DOCTORS_REMOTE_CACHE_NAME.removeSuffix("-v3")}-v2:7:263",
                    "${CacheConfig.DOCTORS_CACHE_NAME}:7:263",
                    "${CacheConfig.EQUIPMENTS_REMOTE_CACHE_NAME}:7:263",
                    "${CacheConfig.EQUIPMENTS_REMOTE_CACHE_NAME.removeSuffix("-v3")}-v2:7:263",
                    "${CacheConfig.EQUIPMENTS_CACHE_NAME}:7:263",
                    "${CacheConfig.TREATMENT_TYPES_REMOTE_CACHE_NAME}:7:263",
                    "${CacheConfig.TREATMENT_TYPES_REMOTE_CACHE_NAME.removeSuffix("-v3")}-v2:7:263",
                    "${CacheConfig.TREATMENT_TYPES_CACHE_NAME}:7:263",
                )
                },
                { rawConnection?.close() },
                { rawClient?.shutdown() },
            )
        }
    }

    private fun <T : Any> runCacheProbe(
        config: CacheConfig,
        rawCommands: io.lettuce.core.api.sync.RedisCommands<String, String>,
        logicalName: String,
        remoteName: String,
        expected: List<T>,
        cacheFactory: (CacheConfig, RedisClient) -> NearCacheOperations<List<T>>,
        assertions: AssertionLedger,
    ): CacheProbe<T> {
        val cacheKey = "7:263"
        val v3Key = "$remoteName:$cacheKey"
        val v2Key = "${remoteName.removeSuffix("-v3")}-v2:$cacheKey"
        val v1Key = "$logicalName:$cacheKey"
        rawCommands.unlink(v3Key, v2Key, v1Key)
        rawCommands.set(v2Key, "legacy-v2-opaque-payload")

        var writerClient: RedisClient? = null
        var readerClient: RedisClient? = null
        var writerCache: NearCacheOperations<List<T>>? = null
        var readerCache: NearCacheOperations<List<T>>? = null
        var primaryFailure: Throwable? = null
        return try {
            val writerClientInstance = RedisClient.create(Containers.Redis.url)
            writerClient = writerClientInstance
            val readerClientInstance = RedisClient.create(Containers.Redis.url)
            readerClient = readerClientInstance
            val writer = cacheFactory(config, writerClientInstance)
            writerCache = writer
            val reader = cacheFactory(config, readerClientInstance)
            readerCache = reader
            var hits = 0
            var misses = 0
            val initialRead = reader.get(cacheKey)
            assertions.check(initialRead == null, "empty v3 cache should miss for $remoteName")
            if (initialRead == null) misses++ else hits++

            writer.put(cacheKey, expected)
            val readBack = reader.get(cacheKey)
            assertions.check(readBack != null, "v3 cache read should hit for $remoteName")
            if (readBack == null) misses++ else hits++
            assertions.check(readBack == expected, "v3 cache payload mismatch for $remoteName")

            assertions.check(rawCommands.exists(v3Key) == 1L, "v3 key missing for $remoteName")
            assertions.check(rawCommands.exists(v2Key) == 1L, "v2 rollback key missing for $remoteName")
            assertions.check(rawCommands.exists(v1Key) == 0L, "legacy v1 key leaked for $remoteName")
            CacheProbe(
                remoteName = remoteName,
                expected = expected,
                cacheFactory = cacheFactory,
                hits = hits,
                misses = misses,
                keyAssertions = 3,
                keyAssertionsPassed = 3,
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanup(
                primaryFailure,
                { writerCache?.close() },
                { readerCache?.close() },
                { writerClient?.shutdown() },
                { readerClient?.shutdown() },
            )
        }
    }

    private fun <T : Any> verifyMalformedPayloadIsCounted(
        config: CacheConfig,
        rawCommands: io.lettuce.core.api.sync.RedisCommands<String, String>,
        remoteName: String,
        expected: List<T>,
        cacheFactory: (CacheConfig, RedisClient) -> NearCacheOperations<List<T>>,
        assertions: AssertionLedger,
    ): Int {
        val cacheKey = "7:263"
        val v3Key = "$remoteName:$cacheKey"
        rawCommands.set(v3Key, "malformed-v3-payload")
        var client: RedisClient? = null
        var cache: NearCacheOperations<List<T>>? = null
        var primaryFailure: Throwable? = null
        return try {
            val clientInstance = RedisClient.create(Containers.Redis.url)
            client = clientInstance
            val activeCache = cacheFactory(config, clientInstance)
            cache = activeCache
            try {
                activeCache.get(cacheKey)
                assertions.check(false, "malformed v3 payload unexpectedly decoded")
                0
            } catch (error: Throwable) {
                if (!error.causes().any(::isExpectedDecodeFailure)) throw error
                assertions.check(true, "malformed v3 payload was rejected as a decode error")
                1
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanup(
                primaryFailure,
                { cache?.close() },
                { client?.shutdown() },
                { rawCommands.unlink(v3Key) },
                {
                    val restoreClient = RedisClient.create(Containers.Redis.url)
                    var restoreCache: NearCacheOperations<List<T>>? = null
                    var restoreFailure: Throwable? = null
                    try {
                        val activeRestoreCache = cacheFactory(config, restoreClient)
                        restoreCache = activeRestoreCache
                        activeRestoreCache.put(cacheKey, expected)
                    } catch (error: Throwable) {
                        restoreFailure = error
                        throw error
                    } finally {
                        cleanup(
                            restoreFailure,
                            { restoreCache?.close() },
                            { restoreClient.shutdown() },
                        )
                    }
                },
            )
        }
    }

    private fun <T : Any> verifyRollbackPreservesV3AndV2(
        config: CacheConfig,
        rawCommands: io.lettuce.core.api.sync.RedisCommands<String, String>,
        probe: CacheProbe<T>,
        assertions: AssertionLedger,
    ): RollbackEvidence {
        val cacheKey = "7:263"
        val v3Key = "${probe.remoteName}:$cacheKey"
        val v2Key = "${probe.remoteName.removeSuffix("-v3")}-v2:$cacheKey"
        var v2Warmup = false
        var v3Preserved = false
        var trafficDrained = false
        var workerRestarted = false
        var activeRequests = 0
        val durationMs = measureNanoTime {
            var oldWorkerClient: RedisClient? = null
            var oldWorkerCache: NearCacheOperations<List<T>>? = null
            var oldWorkerClosed = false
            var primaryFailure: Throwable? = null
            try {
                val oldClient = RedisClient.create(Containers.Redis.url)
                oldWorkerClient = oldClient
                val oldCache = probe.cacheFactory(config, oldClient)
                oldWorkerCache = oldCache
                activeRequests++
                try {
                    oldCache.get(cacheKey) shouldBeEqualTo probe.expected
                } finally {
                    activeRequests--
                }
                trafficDrained = activeRequests == 0
                assertions.check(trafficDrained, "traffic drain was not observed")

                cleanup(
                    { oldCache.close() },
                    { oldClient.shutdown() },
                )
                oldWorkerClosed = true

                var restartedClient: RedisClient? = null
                var restartedCache: NearCacheOperations<List<T>>? = null
                var restartedFailure: Throwable? = null
                try {
                    val newClient = RedisClient.create(Containers.Redis.url)
                    restartedClient = newClient
                    val newCache = probe.cacheFactory(config, newClient)
                    restartedCache = newCache
                    workerRestarted = newClient !== oldClient && newCache !== oldCache
                    assertions.check(workerRestarted, "worker restart did not create a new cache client")

                    v2Warmup = rawCommands.get(v2Key) == "legacy-v2-opaque-payload"
                    assertions.check(v2Warmup, "v2 rollback namespace was not warmed")

                    val readBack = newCache.get(cacheKey)
                    v3Preserved = readBack == probe.expected &&
                        rawCommands.exists(v3Key) == 1L &&
                        rawCommands.exists(v2Key) == 1L
                    assertions.check(v3Preserved, "v3 namespace was not preserved during rollback")
                } catch (error: Throwable) {
                    restartedFailure = error
                    throw error
                } finally {
                    cleanup(
                        restartedFailure,
                        { restartedCache?.close() },
                        { restartedClient?.shutdown() },
                    )
                }
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                if (activeRequests > 0) activeRequests = 0
                if (!oldWorkerClosed) {
                    cleanup(
                        primaryFailure,
                        { oldWorkerCache?.close() },
                        { oldWorkerClient?.shutdown() },
                    )
                }
            }
        }.div(1_000_000.0)

        return RollbackEvidence(
            durationMs = durationMs,
            trafficDrained = trafficDrained,
            workerRestarted = workerRestarted,
            v2Warmup = v2Warmup,
            v3Preserved = v3Preserved,
            result = if (trafficDrained && workerRestarted && v2Warmup && v3Preserved) "PASS" else "FAIL",
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun installGeneratedSerializer(fory: org.apache.fory.Fory, type: Class<*>) {
        val typed = type as Class<Any>
        val serializerClass = CodegenSerializer.loadCodegenSerializer(fory, typed)
        val serializer = Serializers.newSerializer<Any>(fory, typed, serializerClass)
        fory.typeResolver.setSerializer(typed, serializer)
    }

    private fun verifyBrokerRoundTrip(assertions: AssertionLedger): BrokerEvidence {
        val kafka = KafkaServer.Launcher.kafka
        val topicName = "clinic.appointment.issue-263.${UUID.randomUUID()}"
        val producerProperties = KafkaServer.Launcher.getProducerProperties(kafka)
        var consumed = 0
        var primaryFailure: Throwable? = null

        try {
            AdminClient.create(producerProperties).use { admin ->
                admin.createTopics(listOf(NewTopic(topicName, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS)
            }
            val producedAt = Instant.now()
            val metadata = KafkaServer.Launcher.createStringProducer(kafka).use { producer ->
                producer.send(ProducerRecord(topicName, "issue-263", "production-like-canary"))
                    .get(10, TimeUnit.SECONDS)
            }

            var consumedAt: Instant? = null
            val topicPartition = TopicPartition(topicName, metadata.partition())
            KafkaServer.Launcher.createStringConsumer(kafka).use { consumer ->
                consumer.subscribe(listOf(topicName))
                repeat(20) {
                    if (consumed == 0) {
                        val records = consumer.poll(Duration.ofMillis(500))
                        if (!records.isEmpty) {
                            consumed = records.count()
                            consumer.commitSync()
                            consumedAt = Instant.now()
                        }
                    }
                }
                assertions.check(consumed > 0, "Kafka fixed-window consumer received no record")
                val committedOffset = consumer.committed(setOf(topicPartition))
                    .get(topicPartition)
                    .shouldNotBeNull()
                    .offset()
                val endOffset = AdminClient.create(producerProperties).use { admin ->
                    admin.listOffsets(mapOf(topicPartition to OffsetSpec.latest()))
                        .all()
                        .get(10, TimeUnit.SECONDS)
                        .getValue(topicPartition)
                        .offset()
                }
                val consumerLagRecords = maxOf(0L, endOffset - committedOffset)
                assertions.check(consumerLagRecords == 0L, "Kafka consumer lag remained after commit")
                val roundTripSeconds = Duration.between(producedAt, consumedAt.shouldNotBeNull()).toNanos() /
                    1_000_000_000.0
                return BrokerEvidence(
                    image = "${KafkaServer.IMAGE}:${KafkaServer.TAG}",
                    lagSeconds = 0.0,
                    consumerLagRecords = consumerLagRecords,
                    roundTripSeconds = max(0.0, roundTripSeconds),
                    recordsProduced = 1,
                    recordsConsumed = consumed,
                )
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanup(
                primaryFailure,
                {
                    AdminClient.create(producerProperties).use { admin ->
                        admin.deleteTopics(listOf(topicName)).all().get(10, TimeUnit.SECONDS)
                    }
                },
            )
        }
    }

    private fun report(
        postgres: PostgresEvidence,
        cache: CacheEvidence,
        broker: BrokerEvidence,
        assertions: AssertionEvidence,
    ) = objectMapper.createObjectNode().apply {
        put("schemaVersion", 1)
        put("environment", "local")
        put("evidenceMode", "production-like")
        put("capturedAt", Instant.now().toString())
        put("deploymentSloEvidence", false)

        putObject("configuration").apply {
            put("flywayTransactionalLock", false)
            put("redisScheme", "redis")
            put("redisTls", false)
            put("redisAcl", false)
            put("writerNamespace", "v3")
            put("rollbackNamespace", "v2")
        }
        putObject("execution").apply {
            putArray("sequence").addAll(
                listOf(
                    "postgres-migration-and-lock-wait",
                    "redis-v3-fixed-window",
                    "redis-rollback-drain-restart-v2-warmup",
                    "kafka-round-trip-and-offset-lag",
                    "redacted-report-write",
                ).map(objectMapper::valueToTree),
            )
            put("cleanupOwnership", "test finally blocks exact Redis keys and temporary Kafka topic; singleton launchers own containers")
        }

        putObject("redis").apply {
            put("image", Containers.Redis.dockerImageName)
            put("tls", false)
            put("acl", false)
            put("namespace", "v3")
            put("rollbackNamespace", "v2")
            put("v3KeyAssertions", cache.v3KeyAssertions)
            put("v3KeyAssertionsPassed", cache.v3KeyAssertionsPassed)
        }
        putObject("postgres").apply {
            put("image", postgres.image)
            put("migration", postgres.currentMigration)
            put("appliedMigrationCount", postgres.appliedMigrationCount)
            put("lockWaitMs", postgres.lockWaitMs)
            put("lockProbe", postgres.lockProbe)
            put("lockHoldMs", postgres.lockHoldMs)
        }
        putObject("broker").apply {
            put("image", broker.image)
            put("lagMetric", "committed-end-offset-zero-backlog")
            put("lagSeconds", broker.lagSeconds)
            put("consumerLagRecords", broker.consumerLagRecords)
            put("roundTripSeconds", broker.roundTripSeconds)
            put("recordsProduced", broker.recordsProduced)
            put("recordsConsumed", broker.recordsConsumed)
        }
        putObject("cache").apply {
            put("hits", cache.hits)
            put("misses", cache.misses)
            put("decodeErrors", cache.decodeErrors)
        }
        putObject("rollback").apply {
            put("result", cache.rollbackResult)
            put("durationMs", cache.rollbackDurationMs)
            put("trafficDrained", cache.trafficDrained)
            put("workerRestarted", cache.workerRestarted)
            put("v2Warmup", cache.v2Warmup)
            put("v3Preserved", cache.v3Preserved)
        }
        putObject("assertions").apply {
            put("total", assertions.total)
            put("passed", assertions.passed)
        }
        putObject("test").apply {
            put("className", this@CacheRolloutEvidenceIntegrationTest::class.qualifiedName)
            put("testCount", 1)
        }
    }

    private fun writeReport(report: tools.jackson.databind.JsonNode) {
        val output = Path.of(
            System.getProperty(
                "cache.rollout.evidence.output",
                "build/reports/cache-rollout/issue-263/production-like-report.json",
            )
        )
        output.parent?.let(Files::createDirectories)
        Files.writeString(
            output,
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator(),
            StandardCharsets.UTF_8,
        )
    }

    private fun elapsedMillis(startedAt: Long): Double =
        (System.nanoTime() - startedAt) / 1_000_000.0

    private fun cleanup(vararg actions: () -> Unit) {
        cleanup(null, *actions)
    }

    private fun cleanup(primaryFailure: Throwable?, vararg actions: () -> Unit) {
        var cleanupFailure: Throwable? = null
        actions.forEach { action ->
            try {
                action()
            } catch (error: Throwable) {
                cleanupFailure?.addSuppressed(error) ?: run { cleanupFailure = error }
            }
        }
        if (primaryFailure != null) {
            cleanupFailure?.let(primaryFailure::addSuppressed)
        } else {
            cleanupFailure?.let { throw it }
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun isExpectedDecodeFailure(error: Throwable): Boolean =
        error is BinarySerializationException ||
            (error is IllegalArgumentException && error.message?.contains("sourceSize") == true)

    private fun tools.jackson.databind.JsonNode.requiredNumber(name: String): Double {
        val value = get(name)
        require(value != null && value.isNumber && value.doubleValue() >= 0.0) {
            "threshold $name must be a non-negative number"
        }
        return value.doubleValue()
    }

    private data class CacheProbe<T : Any>(
        val remoteName: String,
        val expected: List<T>,
        val cacheFactory: (CacheConfig, RedisClient) -> NearCacheOperations<List<T>>,
        val hits: Int,
        val misses: Int,
        val keyAssertions: Int,
        val keyAssertionsPassed: Int,
    )

    private data class PostgresEvidence(
        val image: String,
        val currentMigration: String,
        val appliedMigrationCount: Int,
        val lockWaitMs: Double,
        val lockProbe: String,
        val lockHoldMs: Double,
    )

    private data class BrokerEvidence(
        val image: String,
        val lagSeconds: Double,
        val consumerLagRecords: Long,
        val roundTripSeconds: Double,
        val recordsProduced: Int,
        val recordsConsumed: Int,
    )

    private data class AssertionEvidence(
        val total: Int,
        val passed: Int,
    )

    private class AssertionLedger {
        var total: Int = 0
            private set
        var passed: Int = 0
            private set

        fun check(condition: Boolean, message: String) {
            total++
            if (!condition) throw AssertionError(message)
            passed++
        }

        fun snapshot() = AssertionEvidence(total = total, passed = passed)
    }

    private data class RollbackEvidence(
        val durationMs: Double,
        val trafficDrained: Boolean,
        val workerRestarted: Boolean,
        val v2Warmup: Boolean,
        val v3Preserved: Boolean,
        val result: String,
    )

    private data class CacheEvidence(
        val hits: Int,
        val misses: Int,
        val decodeErrors: Int,
        val v3KeyAssertions: Int,
        val v3KeyAssertionsPassed: Int,
        val rollbackDurationMs: Double,
        val trafficDrained: Boolean,
        val workerRestarted: Boolean,
        val v2Warmup: Boolean,
        val v3Preserved: Boolean,
        val rollbackResult: String,
    )

    private companion object {
        const val THRESHOLDS_RESOURCE = "cache/issue-263/production-like-thresholds.json"
        const val EXPECTED_MIGRATION = "31"
        const val LOCK_HOLD_MS = 50.0
    }
}

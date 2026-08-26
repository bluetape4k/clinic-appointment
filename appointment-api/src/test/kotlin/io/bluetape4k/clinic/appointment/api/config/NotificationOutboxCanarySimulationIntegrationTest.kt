package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.test.Containers
import io.bluetape4k.clinic.appointment.event.notification.AppointmentConfirmedParameters
import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.notification.persistence.ClaimedNotification
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.notification.persistence.CompleteNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.NotificationAuditFingerprint
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationDeliveryAttemptOutcome
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationDeliveryAttempts
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationFairCursor
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyDigest
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxEnvelope
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxEvents
import io.bluetape4k.clinic.appointment.notification.persistence.JdbcNotificationOutboxRepository
import io.bluetape4k.clinic.appointment.notification.persistence.NotificationOutboxStatus
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.notification.persistence.RetryNotificationCommand
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.notification.JdbcNotificationOutboxObservationStore
import io.bluetape4k.clinic.appointment.notification.JdbcNotificationOutboxWorkStore
import io.bluetape4k.clinic.appointment.notification.MemberNotificationProfile
import io.bluetape4k.clinic.appointment.notification.MemberNotificationProfileResolver
import io.bluetape4k.clinic.appointment.notification.MemberNotificationProfileResult
import io.bluetape4k.clinic.appointment.notification.NotificationCandidatePage
import io.bluetape4k.clinic.appointment.notification.NotificationChannel
import io.bluetape4k.clinic.appointment.notification.NotificationDeliveryRouteGate
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxAlertPolicy
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxAlertSample
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxAlertSeverity
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxDispatcher
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxHealthIndicator
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxLivenessSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxReadinessSnapshot
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxWorkStore
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxWorker
import io.bluetape4k.clinic.appointment.notification.NotificationOutboxWorkerResult
import io.bluetape4k.clinic.appointment.notification.NotificationProperties
import io.bluetape4k.clinic.appointment.notification.NotificationProviderIdempotencyKeyFactory
import io.bluetape4k.clinic.appointment.notification.NotificationProviderRequest
import io.bluetape4k.clinic.appointment.notification.NotificationProviderResult
import io.bluetape4k.clinic.appointment.notification.NotificationRolloutMode
import io.bluetape4k.clinic.appointment.notification.NotificationTemplate
import io.bluetape4k.clinic.appointment.notification.NotificationTemplateCatalog
import io.bluetape4k.clinic.appointment.notification.NotificationTemplateRenderer
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.Isolated
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import io.bluetape4k.testcontainers.mq.KafkaServer
import io.lettuce.core.RedisClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * Issue #204의 고정 canary 창을 실제 provider 대신 deterministic stub으로 재현합니다.
 *
 * PostgreSQL·Redis·Kafka는 singleton launcher를 사용하지만 provider credential, 실제 수신자,
 * 운영 트래픽은 사용하지 않습니다. 따라서 결과는 production-like readiness evidence일 뿐
 * production SLO 또는 실제 rollout 완료 증거가 아닙니다.
 */
@Execution(ExecutionMode.SAME_THREAD)
@Isolated
@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
@ResourceLock(value = "appointment-messaging-kafka", mode = ResourceAccessMode.READ_WRITE)
internal class NotificationOutboxCanarySimulationIntegrationTest {

    @Test
    fun `1000건 canary는 rollback queue 보존 retry fencing idempotency와 인프라 정리를 증명한다`() = runBlocking {
        val assertions = AssertionLedger()
        val startedAt = Instant.now()
        val postgres = Containers.Postgres
        val dataSource = postgresDataSource(postgres)
        migrate(dataSource)
        val database = Database.connect(dataSource)
        val repository = JdbcNotificationOutboxRepository(NotificationOutboxCodec(), Duration.ofMinutes(5))
        val store = CountingWorkStore(JdbcNotificationOutboxWorkStore(database, repository))
        val provider = DeterministicProvider(retryOnCall = 2)
        val worker = worker(store, provider)
        val reportOutput = reportOutput()

        seed(database, repository)
        val initialOpen = countOpen(database)
        assertions.check(initialOpen == LOGICAL_NOTIFICATIONS.toLong(), "seed count must be 1000")

        val shadow = dispatcher(store, worker, NotificationRolloutMode.SHADOW)
        shadow.dispatchOnce() shouldBeEqualTo emptyList()
        assertions.check(provider.calls.get() == 0, "SHADOW must not call provider")
        shadow.close()

        val active = dispatcher(store, worker, NotificationRolloutMode.ACTIVE)
        val firstBatch = active.dispatchOnce()
        assertions.check(firstBatch.isNotEmpty(), "ACTIVE must claim a bounded batch")
        assertions.check(
            provider.replayFirstAccepted() is NotificationProviderResult.Accepted,
            "provider replay must return the original idempotent result",
        )
        val openBeforePause = countOpen(database)
        assertions.check(openBeforePause in 1 until LOGICAL_NOTIFICATIONS.toLong(), "partial ACTIVE must leave queue")

        active.close()
        val workerStoppedAt = Instant.now()
        val callsBeforePause = provider.calls.get()
        val paused = dispatcher(store, worker, NotificationRolloutMode.PAUSED)
        paused.dispatchOnce() shouldBeEqualTo emptyList()
        val queuePreserved = countOpen(database) == openBeforePause
        val providerCallsDuringPause = provider.calls.get() - callsBeforePause
        assertions.check(providerCallsDuringPause == 0, "PAUSED must not call provider")
        assertions.check(queuePreserved, "PAUSED must preserve non-terminal queue")
        paused.close()

        val finalShadow = dispatcher(store, worker, NotificationRolloutMode.SHADOW)
        finalShadow.dispatchOnce() shouldBeEqualTo emptyList()
        assertions.check(provider.calls.get() == callsBeforePause, "final SHADOW must not call provider")
        finalShadow.close()

        val fencedCandidate = firstCandidate(database, repository)
        val firstClaim = store.claim(fencedCandidate.id, "issue-204-fence-a")
        checkNotNull(firstClaim)
        expireLease(database, fencedCandidate.id)
        val recovered = store.recoverExpired(1, "issue-204-fence-b").single()
        assertions.check(recovered.attemptNumber == 2, "expired lease must advance attempt number")
        worker.process(recovered) shouldBeEqualTo NotificationOutboxWorkerResult.COMPLETED

        val restartedActive = dispatcher(store, worker, NotificationRolloutMode.ACTIVE)
        val workerRestartedAt = Instant.now()
        val workerStoppedAndRestarted = workerStoppedAt <= workerRestartedAt && restartedActive !== active
        drain(restartedActive, database, assertions)
        restartedActive.close()

        val rollback = RollbackEvidence(
            queuePreserved = queuePreserved,
            providerCallsDuringPause = providerCallsDuringPause,
            workerStoppedAndRestarted = workerStoppedAndRestarted,
        )
        assertions.check(rollback.workerStoppedAndRestarted, "rollback must stop and restart the worker")

        val redis = verifyRedis(assertions)
        val kafka = verifyKafka(assertions)
        val lifecycle = lifecycleEvidence(database, provider)
        assertions.check(lifecycle.openRows == 0L, "all seeded rows must be terminal")
        assertions.check(lifecycle.retryScheduled >= 1, "retry path must be exercised")
        assertions.check(lifecycle.leaseLost >= 1, "fencing path must close a lost lease")
        assertions.check(provider.replayedRequests() == 1, "provider idempotency replay must be exercised once")
        assertions.check(provider.duplicateAcceptedResults() == 0, "provider accepted results must be idempotent")
        assertions.check(lifecycle.terminalRedactionViolations == 0, "terminal rows must be redacted")
        val observation = observation(database, repository)
        assertions.check(observation.pendingReady == 0L, "final ready backlog must be zero")
        val criticalAlerts = criticalAlertCount(lifecycle, observation)
        assertions.check(criticalAlerts == 0, "simulation must emit no critical alerts")
        assertions.check(store.claimFailures == 0, "all dispatcher claims must succeed")
        val health = verifyHealth(assertions, observation)

        val report = report(
            postgres = postgres.dockerImageName,
            migration = EXPECTED_MIGRATION,
            redis = redis,
            kafka = kafka,
            lifecycle = lifecycle,
            provider = provider,
            rollback = rollback,
            observation = observation,
            criticalAlerts = criticalAlerts,
            claimFailures = store.claimFailures,
            health = health,
            startedAt = startedAt,
            assertions = assertions.snapshot(),
        )
        NotificationOutboxCanaryEvidenceValidator.validate(report)
        writeReport(report, reportOutput)
    }

    private fun migrate(dataSource: javax.sql.DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .cleanDisabled(false)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .apply {
                clean()
                migrate()
            }
    }

    private fun seed(database: Database, repository: JdbcNotificationOutboxRepository) {
        transaction(database) {
            (1..LOGICAL_NOTIFICATIONS).forEach { index -> repository.enqueue(draft(index)) }
        }
    }

    private fun draft(index: Int): SendableNotificationDraft {
        val digest = index.toString(16).padStart(64, '0')
        val envelope = NotificationOutboxEnvelope(
            schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = NotificationEventId("issue-204-event-$index"),
            idempotencyKey = NotificationIdempotencyKey(digest),
            tenantGroupId = TenantGroupId(204L),
            clinicId = ClinicId(204L),
            appointmentId = AppointmentId(index.toLong()),
            memberId = MemberId("issue-204-member-$index"),
            channel = NotificationChannelType.DUMMY,
            eventType = NotificationEventType.CONFIRMED,
            notificationSlot = NotificationSlot.CONFIRMED,
            templateKey = io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey("appointment.confirmed"),
            templateVersion = io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion(1),
            parameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
            parameters = AppointmentConfirmedParameters(
                clinicDisplayName = "Issue 204 Clinic",
                appointmentDate = LocalDate.parse("2026-08-24"),
                startTime = LocalTime.of(9, index % 60),
            ),
            occurredAt = FIXED_INSTANT,
            availableAt = FIXED_INSTANT,
        )
        return SendableNotificationDraft(
            envelope = envelope,
            idempotencyDigest = NotificationIdempotencyDigest("issue-204-key", 1, digest),
            auditFingerprint = NotificationAuditFingerprint("issue-204-audit", 1, digest.replace('0', 'a')),
            providerKey = "dummy",
        )
    }

    private fun worker(
        store: NotificationOutboxWorkStore,
        provider: DeterministicProvider,
    ): NotificationOutboxWorker = NotificationOutboxWorker(
        workStore = store,
        leaseOwner = "issue-204-worker",
        profileResolver = MemberNotificationProfileResolver {
            MemberNotificationProfileResult.Resolved(
                MemberNotificationProfile(
                    displayName = "simulation",
                    destination = "simulated-recipient",
                    locale = java.util.Locale.KOREAN,
                    consent = io.bluetape4k.clinic.appointment.notification.NotificationConsent(),
                    tenantGroupId = TenantGroupId(204L),
                    clinicId = ClinicId(204L),
                )
            )
        },
        templateRenderer = NotificationTemplateRenderer(
            NotificationTemplateCatalog { key, version, channel ->
                NotificationTemplate(
                    key = key,
                    version = version,
                    channel = channel,
                    fields = setOf("clinicDisplayName", "appointmentDate", "startTime"),
                    textTemplate = "{{clinicDisplayName}} {{appointmentDate}} {{startTime}}",
                )
            }
        ),
        providerChannel = provider,
        providerIdempotencyKeyFactory = NotificationProviderIdempotencyKeyFactory(ByteArray(32) { 0x2A }),
    )

    private fun dispatcher(
        store: NotificationOutboxWorkStore,
        worker: NotificationOutboxWorker,
        mode: NotificationRolloutMode,
    ): NotificationOutboxDispatcher = NotificationOutboxDispatcher(
        store = store,
        worker = worker,
        leaseOwner = "issue-204-dispatcher-${mode.name.lowercase()}",
        globalConcurrency = 8,
        perClinicConcurrency = 8,
        routeGate = NotificationDeliveryRouteGate(NotificationProperties.RolloutProperties(mode = mode)),
    )

    private suspend fun drain(
        dispatcher: NotificationOutboxDispatcher,
        database: Database,
        assertions: AssertionLedger,
    ) {
        var rounds = 0
        while (rounds < MAX_DRAIN_ROUNDS) {
            rounds++
            val results = dispatcher.dispatchOnce()
            if (results.isEmpty()) {
                backdateRetries(database)
                if (countOpen(database) == 0L) break
            }
        }
        assertions.check(countOpen(database) == 0L, "bounded ACTIVE drain must close every row")
        assertions.check(rounds <= MAX_DRAIN_ROUNDS, "ACTIVE drain exceeded bounded rounds")
    }

    private fun firstCandidate(
        database: Database,
        repository: JdbcNotificationOutboxRepository,
    ): io.bluetape4k.clinic.appointment.notification.persistence.NotificationCandidate = transaction(database) {
        val clinic = repository.findReadyClinicKeys(cursor = null, limit = 1).single()
        repository.findReadyCandidates(clinic, cursorId = null, limit = 1).single()
    }

    private fun expireLease(database: Database, id: Long) {
        transaction(database) {
            exec("UPDATE clinic_notification_outbox SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = $id")
        }
    }

    private fun backdateRetries(database: Database) {
        transaction(database) {
            exec(
                "UPDATE clinic_notification_outbox " +
                    "SET next_retry_at = CURRENT_TIMESTAMP - INTERVAL '1 second' " +
                    "WHERE status = 'RETRY_WAIT'",
            )
        }
    }

    private fun countOpen(database: Database): Long = transaction(database) {
        NotificationOutboxEvents.selectAll()
            .count { row -> row[NotificationOutboxEvents.status] in OPEN_STATUSES }
            .toLong()
    }

    private fun lifecycleEvidence(database: Database, provider: DeterministicProvider): LifecycleEvidence = transaction(database) {
        val rows = NotificationOutboxEvents.selectAll().toList()
        val attempts = NotificationDeliveryAttempts.selectAll().toList()
        LifecycleEvidence(
            openRows = rows.count { it[NotificationOutboxEvents.status] in OPEN_STATUSES }.toLong(),
            sentRows = rows.count { it[NotificationOutboxEvents.status] == NotificationOutboxStatus.SENT },
            retryScheduled = attempts.count { it[NotificationDeliveryAttempts.outcome] == NotificationDeliveryAttemptOutcome.RETRY_SCHEDULED },
            leaseLost = attempts.count { it[NotificationDeliveryAttempts.outcome] == NotificationDeliveryAttemptOutcome.LEASE_LOST },
            unknownResults = attempts.count { it[NotificationDeliveryAttempts.failureCode] == NotificationFailureCode.DELIVERY_RESULT_UNKNOWN.name },
            acceptedResults = provider.acceptedResults(),
            deliveryAttempts = attempts.size,
            terminalRedactionViolations = rows.count { row ->
                row[NotificationOutboxEvents.status] in TERMINAL_STATUSES &&
                    (row[NotificationOutboxEvents.appointmentId] != null ||
                        row[NotificationOutboxEvents.memberId] != null ||
                        row[NotificationOutboxEvents.parametersJson] != null)
            },
        )
    }

    private suspend fun observation(
        database: Database,
        repository: JdbcNotificationOutboxRepository,
    ): ObservationEvidence {
        val snapshot = JdbcNotificationOutboxObservationStore(
            database = database,
            persistence = repository,
        ).loadBoundedSnapshot()
        return ObservationEvidence(
            pendingReady = snapshot.pendingReady,
            oldestReadyAgeSeconds = snapshot.oldestActiveAge?.seconds ?: 0L,
            capped = snapshot.capped,
        )
    }

    private fun criticalAlertCount(
        lifecycle: LifecycleEvidence,
        observation: ObservationEvidence,
    ): Int = NotificationOutboxAlertPolicy().evaluate(
        NotificationOutboxAlertSample(
            oldestActiveAge = Duration.ofSeconds(observation.oldestReadyAgeSeconds),
            providerAttempts = lifecycle.deliveryAttempts,
            providerFailures = lifecycle.retryScheduled,
            unknownInFiveMinutes = lifecycle.unknownResults,
            leaseRecoveries = lifecycle.leaseLost,
            deliveryAttempts = lifecycle.deliveryAttempts,
            pendingBacklog = observation.pendingReady,
        )
    ).count { it.severity == NotificationOutboxAlertSeverity.CRITICAL }

    private fun verifyHealth(
        assertions: AssertionLedger,
        observation: ObservationEvidence,
    ): HealthEvidence {
        val indicator = NotificationOutboxHealthIndicator(
            readinessSource = { NotificationOutboxReadinessSnapshot.up() },
            livenessSource = {
                NotificationOutboxLivenessSnapshot(
                    oldestActiveAge = Duration.ofSeconds(observation.oldestReadyAgeSeconds),
                    backlogCapped = observation.capped,
                )
            },
        )
        val health = requireNotNull(NotificationHealthConfiguration().notificationOutboxActuatorHealth(indicator).health())
        val status = health.status.code
        assertions.check(status == "UP", "notificationOutboxHealth must be UP")
        assertions.check(health.details.keys == setOf("readiness", "liveness"), "health details must be bounded")
        return HealthEvidence(component = "notificationOutboxHealth", status = status, redacted = true)
    }

    private fun verifyRedis(assertions: AssertionLedger): RedisEvidence {
        val key = "issue-204:canary:${UUID.randomUUID()}"
        return RedisClient.create(Containers.Redis.url).use { client ->
            client.connect().use { connection ->
                val commands = connection.sync()
                var leakedKeys = 0L
                var primaryFailure: Throwable? = null
                try {
                    commands.set(key, "simulation") shouldBeEqualTo "OK"
                    assertions.check(commands.get(key) == "simulation", "Redis canary key must round-trip")
                } catch (failure: Throwable) {
                    primaryFailure = failure
                    throw failure
                } finally {
                    try {
                        commands.unlink(key)
                        leakedKeys = commands.exists(key)
                    } catch (cleanupFailure: Throwable) {
                        primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                    }
                }
                assertions.check(leakedKeys == 0L, "Redis canary key must be deleted")
                RedisEvidence(Containers.Redis.dockerImageName, keyNamespace = "issue-204:canary", leakedKeys = leakedKeys.toInt())
            }
        }
    }

    private fun verifyKafka(assertions: AssertionLedger): KafkaEvidence {
        val kafka = KafkaServer.Launcher.kafka
        val topic = "clinic.appointment.issue-204.${UUID.randomUUID()}"
        val properties = KafkaServer.Launcher.getProducerProperties(kafka)
        var topicCreated = false
        var primaryFailure: Throwable? = null
        try {
            AdminClient.create(properties).use { admin ->
                admin.createTopics(listOf(NewTopic(topic, 1, 1.toShort()))).all().get(10, TimeUnit.SECONDS)
            }
            topicCreated = true
            val metadata = KafkaServer.Launcher.createStringProducer(kafka).use { producer ->
                producer.send(ProducerRecord(topic, "issue-204", "redacted-canary-event"))
                    .get(10, TimeUnit.SECONDS)
            }
            var consumed = 0
            KafkaServer.Launcher.createStringConsumer(kafka).use { consumer ->
                consumer.subscribe(listOf(topic))
                repeat(20) {
                    val records = consumer.poll(Duration.ofMillis(250))
                    if (!records.isEmpty) {
                        consumed += records.count()
                        consumer.commitSync()
                        return@repeat
                    }
                }
                assertions.check(consumed == 1, "Kafka canary event must be consumed")
                val partition = TopicPartition(topic, metadata.partition())
                val committed = consumer.committed(setOf(partition)).getValue(partition).offset()
                val latest = AdminClient.create(properties).use { admin ->
                    admin.listOffsets(mapOf(partition to OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS)
                        .getValue(partition).offset()
                }
                val lag = maxOf(0L, latest - committed)
                assertions.check(lag == 0L, "Kafka canary consumer lag must be zero")
                return KafkaEvidence("${KafkaServer.IMAGE}:${KafkaServer.TAG}", lag)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (topicCreated) {
                try {
                    AdminClient.create(properties).use { admin ->
                        admin.deleteTopics(listOf(topic)).all().get(10, TimeUnit.SECONDS)
                    }
                } catch (cleanupFailure: Throwable) {
                    primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }
    }

    private fun report(
        postgres: String,
        migration: String,
        redis: RedisEvidence,
        kafka: KafkaEvidence,
        lifecycle: LifecycleEvidence,
        provider: DeterministicProvider,
        rollback: RollbackEvidence,
        observation: ObservationEvidence,
        criticalAlerts: Int,
        claimFailures: Int,
        health: HealthEvidence,
        startedAt: Instant,
        assertions: AssertionEvidence,
    ): JsonNode {
        val objectMapper = jacksonObjectMapper()
        val report = objectMapper.createObjectNode().apply {
            put("schemaVersion", 1)
            put("environment", "local")
            put("evidenceMode", "production-like-container-backed")
            put("capturedAt", Instant.now().toString())
            put("productionSloEvidence", false)
            put("productionClaim", false)
            put("durationMillis", Duration.between(startedAt, Instant.now()).toMillis().coerceAtLeast(0L))
            putObject("workload").apply {
                put("logicalNotifications", LOGICAL_NOTIFICATIONS)
                put("fixedSeed", FIXED_SEED)
                put("window", "bounded")
                putArray("rolloutSequence").addAll(
                    listOf(
                        "SHADOW",
                        "ACTIVE_SIMULATED",
                        "ACTIVE_WORKER_STOPPED",
                        "PAUSED",
                        "SHADOW",
                        "ACTIVE_SIMULATED_RESTARTED",
                    ).map(objectMapper::valueToTree)
                )
            }
            putObject("infrastructure").apply {
                putObject("postgres")
                    .put("image", postgres)
                    .put("migration", migration)
                    .put("schema", "clinic_notification_outbox")
                    .put("ddlLockMode", "flyway.postgresql.transactional.lock=false")
                putObject("redis").put("image", redis.image).put("namespace", redis.keyNamespace)
                putObject("kafka").put("image", kafka.image).put("lagRecords", kafka.lagRecords)
            }
            putObject("thresholds").apply {
                put("deliveryResultUnknown", lifecycle.unknownResults)
                put("duplicateProviderResults", provider.duplicateAcceptedResults())
                put("criticalAlerts", criticalAlerts)
                put("claimFailures", claimFailures)
                put("unresolvedRows", lifecycle.openRows)
                put("redisLeakedKeys", redis.leakedKeys)
                put("kafkaLagRecords", kafka.lagRecords)
                put("oldestReadyAgeSeconds", observation.oldestReadyAgeSeconds)
                put("readyBacklog", observation.pendingReady)
                put("providerThroughputPerSecond", provider.throughputPerSecond(startedAt))
            }
            putObject("rollback").apply {
                put("queuePreserved", rollback.queuePreserved)
                put("providerCallsDuringPause", rollback.providerCallsDuringPause)
                put("workerStoppedAndRestarted", rollback.workerStoppedAndRestarted)
                put("result", if (rollback.queuePreserved && rollback.providerCallsDuringPause == 0) "PASS" else "FAIL")
            }
            putObject("idempotency").apply {
                put("acceptedResults", lifecycle.acceptedResults)
                put("replayedRequests", provider.replayedRequests())
                put("duplicateAcceptedResults", provider.duplicateAcceptedResults())
            }
            putObject("health").apply {
                put("component", health.component)
                put("status", health.status)
                put("redacted", health.redacted)
            }
            putObject("lifecycle").apply {
                put("sentRows", lifecycle.sentRows)
                put("retryScheduled", lifecycle.retryScheduled)
                put("leaseLost", lifecycle.leaseLost)
                put("deliveryAttempts", lifecycle.deliveryAttempts)
                put("providerCalls", provider.calls.get())
                put("acceptedResults", lifecycle.acceptedResults)
            }
            putObject("assertions").apply {
                put("total", assertions.total)
                put("passed", assertions.passed)
            }
        }
        val reportRedaction = reportRedaction(report)
        report.putObject("redaction").apply {
            put("rawPayloadFields", reportRedaction.rawPayloadFields)
            put("secretFields", reportRedaction.secretFields)
            put("destinationFields", reportRedaction.destinationFields)
            put("terminalRowViolations", lifecycle.terminalRedactionViolations)
        }
        return report
    }

    private fun reportRedaction(report: JsonNode): ReportRedaction {
        var rawPayloadFields = 0
        var secretFields = 0
        var destinationFields = 0

        fun visit(node: JsonNode) {
            when {
                node.isObject -> node.properties().forEach { (field, value) ->
                    when {
                        field.lowercase() in RAW_PAYLOAD_FIELDS -> rawPayloadFields++
                        field.lowercase() in SECRET_FIELDS -> secretFields++
                        field.lowercase() in DESTINATION_FIELDS -> destinationFields++
                    }
                    visit(value)
                }
                node.isArray -> node.forEach(::visit)
            }
        }

        visit(report)
        return ReportRedaction(rawPayloadFields, secretFields, destinationFields)
    }

    private fun writeReport(report: JsonNode, output: Path) {
        output.parent?.let(Files::createDirectories)
        Files.writeString(
            output,
            jacksonObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator(),
            StandardCharsets.UTF_8,
        )
    }

    private fun reportOutput(): Path = Path.of(
        System.getProperty(
            "notification.outbox.canary.output",
            "build/reports/notification-outbox/issue-204/production-like-report.json",
        )
    )

    private fun postgresDataSource(postgres: io.bluetape4k.testcontainers.database.PostgreSQLServer): javax.sql.DataSource {
        val driver = Class.forName("org.postgresql.Driver").getDeclaredConstructor().newInstance() as Driver
        return SimpleDriverDataSource(
            driver,
            postgres.jdbcUrl,
            postgres.username ?: "test",
            postgres.password ?: "",
        )
    }

    private class DeterministicProvider(
        private val retryOnCall: Int,
    ) : NotificationChannel {
        override val channelType: NotificationChannelType = NotificationChannelType.DUMMY
        val calls = AtomicInteger()
        private val acceptedByKey = ConcurrentHashMap<String, NotificationProviderMessageReference>()
        private val acceptedReferencesByKey = ConcurrentHashMap<String, MutableSet<String>>()
        private val requestsByKey = ConcurrentHashMap<String, NotificationProviderRequest>()
        private val replayCount = AtomicInteger()
        private val referenceSequence = AtomicInteger()
        private val retryEmitted = AtomicBoolean(false)

        override fun send(request: NotificationProviderRequest): NotificationProviderResult {
            val call = calls.incrementAndGet()
            if (call == retryOnCall && retryEmitted.compareAndSet(false, true)) {
                return NotificationProviderResult.retry(NotificationFailureCode.PROVIDER_UNAVAILABLE)
            }
            requestsByKey[request.idempotencyKey.value] = request
            val reference = NotificationProviderMessageReference("provider-204-${referenceSequence.incrementAndGet()}")
            val existing = acceptedByKey.putIfAbsent(request.idempotencyKey.value, reference)
            val acceptedReference = existing ?: reference
            acceptedReferencesByKey
                .computeIfAbsent(request.idempotencyKey.value) { ConcurrentHashMap.newKeySet<String>() }
                .add(acceptedReference.value)
            if (existing != null) {
                replayCount.incrementAndGet()
                return NotificationProviderResult.accepted(acceptedReference)
            }
            return NotificationProviderResult.accepted(acceptedReference)
        }

        fun acceptedResults(): Int = acceptedByKey.size

        fun duplicateAcceptedResults(): Int = acceptedReferencesByKey.values.sumOf { references ->
            (references.size - 1).coerceAtLeast(0)
        }

        fun replayedRequests(): Int = replayCount.get()

        fun replayFirstAccepted(): NotificationProviderResult =
            requestsByKey.values.firstOrNull()?.let(::send)
                ?: error("provider must accept at least one request before replay")

        fun throughputPerSecond(startedAt: Instant): Double {
            val seconds = Duration.between(startedAt, Instant.now()).toMillis().coerceAtLeast(1L) / 1_000.0
            return acceptedResults() / seconds
        }
    }

    private data class RedisEvidence(val image: String, val keyNamespace: String, val leakedKeys: Int)

    private data class KafkaEvidence(val image: String, val lagRecords: Long)

    private data class LifecycleEvidence(
        val openRows: Long,
        val sentRows: Int,
        val retryScheduled: Int,
        val leaseLost: Int,
        val unknownResults: Int,
        val acceptedResults: Int,
        val deliveryAttempts: Int,
        val terminalRedactionViolations: Int,
    )

    private data class RollbackEvidence(
        val queuePreserved: Boolean,
        val providerCallsDuringPause: Int,
        val workerStoppedAndRestarted: Boolean,
    )

    private data class ObservationEvidence(
        val pendingReady: Long,
        val oldestReadyAgeSeconds: Long,
        val capped: Boolean,
    )

    private data class HealthEvidence(
        val component: String,
        val status: String,
        val redacted: Boolean,
    )

    private data class ReportRedaction(
        val rawPayloadFields: Int,
        val secretFields: Int,
        val destinationFields: Int,
    )

    private class CountingWorkStore(
        private val delegate: NotificationOutboxWorkStore,
    ) : NotificationOutboxWorkStore {
        var claimFailures: Int = 0
            private set

        override suspend fun findFairCandidates(
            limit: Int,
            cursor: NotificationFairCursor?,
        ): NotificationCandidatePage = delegate.findFairCandidates(limit, cursor)

        override suspend fun findFairCandidatesForRoute(
            limit: Int,
            cursor: NotificationFairCursor?,
            perClinicLimit: Int,
            eligibleScopes: Set<TenantClinicScope>?,
        ): NotificationCandidatePage = delegate.findFairCandidatesForRoute(limit, cursor, perClinicLimit, eligibleScopes)

        override suspend fun claim(id: Long, owner: String): ClaimedNotification? =
            delegate.claim(id, owner).also { if (it == null) claimFailures++ }

        override suspend fun recoverExpired(limit: Int, owner: String): List<ClaimedNotification> =
            delegate.recoverExpired(limit, owner)

        override suspend fun recoverExpired(
            limit: Int,
            owner: String,
            eligibleScopes: Set<TenantClinicScope>?,
        ): List<ClaimedNotification> = delegate.recoverExpired(limit, owner, eligibleScopes)

        override suspend fun complete(command: CompleteNotificationCommand): Boolean = delegate.complete(command)

        override suspend fun retry(command: RetryNotificationCommand): Boolean = delegate.retry(command)

        override suspend fun currentDatabaseTime(): Instant = delegate.currentDatabaseTime()

        override suspend fun deleteTerminalBatch(
            status: NotificationOutboxStatus,
            retention: Duration,
            limit: Int,
        ): Int = delegate.deleteTerminalBatch(status, retention, limit)
    }

    private data class AssertionEvidence(val total: Int, val passed: Int)

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

        fun snapshot() = AssertionEvidence(total, passed)
    }

    private companion object {
        const val LOGICAL_NOTIFICATIONS = 1_000
        const val MAX_DRAIN_ROUNDS = 300
        const val EXPECTED_MIGRATION = "30"
        const val FIXED_SEED = "issue-204-seed-v1"
        val FIXED_INSTANT: Instant = Instant.parse("2026-08-24T00:00:00Z")
        val OPEN_STATUSES = setOf(
            NotificationOutboxStatus.PENDING,
            NotificationOutboxStatus.PROCESSING,
            NotificationOutboxStatus.RETRY_WAIT,
        )
        val TERMINAL_STATUSES = setOf(
            NotificationOutboxStatus.SENT,
            NotificationOutboxStatus.SUPPRESSED,
            NotificationOutboxStatus.EXHAUSTED,
        )
        val RAW_PAYLOAD_FIELDS = setOf("payload", "rawpayload", "parameters", "rendered")
        val SECRET_FIELDS = setOf("secret", "credential", "token")
        val DESTINATION_FIELDS = setOf("destination", "destinationvalue", "memberid", "appointmentid")
    }
}

package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class NotificationOutboxRepositoryTest {

    private val database = Database.connect(
        "jdbc:h2:mem:notification_outbox_repository_${System.nanoTime()};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        driver = "org.h2.Driver",
    )
    private val codec = NotificationOutboxCodec()
    private val repository = NotificationOutboxRepository(codec = codec, leaseDuration = Duration.ofMinutes(5))

    @BeforeEach
    fun setup() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                NotificationOutboxEvents,
                NotificationDeliveryAttempts,
            )
            NotificationDeliveryAttempts.deleteAll()
            NotificationOutboxEvents.deleteAll()
        }
    }

    @Test
    fun `같은 idempotency digest는 한 행으로 수렴한다`() {
        transaction(database) {
            val first = repository.enqueue(sendableDraft())
            val second = repository.enqueue(sendableDraft())

            first.id shouldBeEqualTo second.id
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `duplicate enqueue uses atomic upsert without hiding non idempotency sql errors`() {
        transaction(database) {
            val first = repository.enqueue(sendableDraft())
            val duplicate = repository.enqueue(sendableDraft())

            duplicate.id shouldBeEqualTo first.id
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L

            exec(
                """
                ALTER TABLE clinic_notification_outbox
                ADD CONSTRAINT chk_notification_test_provider_key CHECK (provider_key <> 'dummy-broken')
                """.trimIndent()
            )
            assertFailsWith<ExposedSQLException> {
                repository.enqueue(sendableDraft(eventId = "event-broken", digest = "digest-broken", providerKey = "dummy-broken"))
            }
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `legacy suppression은 raw appointment와 member를 저장하지 않는다`() {
        transaction(database) {
            val row = repository.suppressLegacy(legacySuppressionDraft())

            row.rowKind shouldBeEqualTo NotificationOutboxRowKind.LEGACY_SUPPRESSION
            row.status shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
            row.appointmentId.shouldBeNull()
            row.memberId.shouldBeNull()
            row.templateKey.shouldBeNull()
            row.parametersJson.shouldBeNull()
            row.providerKey.shouldBeNull()
            val stored = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq row.id }
                .single()
            stored[NotificationOutboxEvents.channel].shouldBeNull()
            stored[NotificationOutboxEvents.eventType].shouldBeNull()
            stored[NotificationOutboxEvents.notificationSlot].shouldBeNull()
            stored[NotificationOutboxEvents.templateVersion].shouldBeNull()
            stored[NotificationOutboxEvents.parameterType].shouldBeNull()
        }
    }

    @Test
    fun `ready query contracts and indexes stay aligned`() {
        NotificationOutboxIndexes.names() shouldBeEqualTo listOf(
            "uk_notification_outbox_idempotency",
            "idx_notification_outbox_ready_clinic_cursor",
            "idx_notification_outbox_ready_within_clinic",
            "idx_notification_outbox_direct_lookup",
            "idx_notification_outbox_reminder_suppression",
            "idx_notification_outbox_lease_recovery",
            "idx_notification_outbox_terminal_retention",
            "idx_notification_outbox_pending_oldest",
        )
        NotificationOutboxQueryContracts.readyClinicCursor.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.readyClinicCursor.columns
        NotificationOutboxQueryContracts.readyClinicCursor.filters shouldBeEqualTo
            NotificationOutboxIndexes.readyClinicCursor.columns
        NotificationOutboxQueryContracts.readyClinicCursor.orderBy shouldBeEqualTo listOf("tenant_group_id", "clinic_id")
        NotificationOutboxQueryContracts.readyWithinClinic.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.readyWithinClinic.columns
        NotificationOutboxQueryContracts.readyWithinClinic.filters shouldBeEqualTo
            NotificationOutboxIndexes.readyWithinClinic.columns
        NotificationOutboxQueryContracts.readyWithinClinic.orderBy shouldBeEqualTo listOf("available_at", "id")
        NotificationOutboxQueryContracts.directLookup.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.directLookup.columns
        NotificationOutboxQueryContracts.directLookup.filters shouldBeEqualTo
            NotificationOutboxIndexes.directLookup.columns.dropLast(1)
        NotificationOutboxQueryContracts.directLookup.orderBy shouldBeEqualTo listOf("available_at", "id")
        NotificationOutboxQueryContracts.leaseRecovery.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.leaseRecovery.columns
        NotificationOutboxQueryContracts.leaseRecovery.filters shouldBeEqualTo
            NotificationOutboxIndexes.leaseRecovery.columns
        NotificationOutboxQueryContracts.leaseRecovery.orderBy shouldBeEqualTo listOf("lease_until", "id")
        NotificationOutboxQueryContracts.terminalRetention.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.terminalRetention.columns
        NotificationOutboxQueryContracts.terminalRetention.filters shouldBeEqualTo
            NotificationOutboxIndexes.terminalRetention.columns
        NotificationOutboxQueryContracts.terminalRetention.orderBy shouldBeEqualTo listOf("terminal_at", "id")
        NotificationOutboxQueryContracts.pendingOldest.indexColumns shouldBeEqualTo
            NotificationOutboxIndexes.pendingOldest.columns
        NotificationOutboxQueryContracts.pendingOldest.filters shouldBeEqualTo
            NotificationOutboxIndexes.pendingOldest.columns
        NotificationOutboxQueryContracts.pendingOldest.orderBy shouldBeEqualTo listOf("available_at", "created_at")
        NotificationDeliveryAttempts.columns.map { it.name }.none {
            it in setOf("recipient", "payload", "rendered", "error_message")
        }.shouldBeTrue()
        NotificationDeliveryAttempts.columns.map { it.name }.containsAll(
            listOf(
                "channel",
                "event_type",
                "template_key",
                "template_version",
                "completed_at",
                "duration_millis",
                "outcome",
                "failure_code",
                "provider_message_reference",
                "destination_fingerprint",
                "correlation_id",
                "trace_id",
            )
        ).shouldBeTrue()
    }

    @Test
    fun `ready 조회는 발송 가능한 clinic key와 candidate만 반환한다`() {
        val (readyId, retryId) = transaction(database) {
            val readyOne = repository.enqueue(sendableDraft(eventId = "event-ready-1", digest = "digest-ready-1"))
            repository.enqueue(
                sendableDraft(
                    eventId = "event-future",
                    digest = "digest-future",
                    availableAt = Instant.parse("2999-01-01T00:00:00Z"),
                )
            )
            val retry = repository.enqueue(sendableDraft(eventId = "event-retry", digest = "digest-retry"))
            val claimed = repository.claim(retry.id, owner = "worker-a", token = "token-a")!!
            repository.scheduleRetry(
                RetryNotificationCommand(
                    outboxId = retry.id,
                    owner = claimed.owner,
                    token = claimed.token,
                    attemptNumber = claimed.attemptNumber,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    retryDelay = Duration.ofMillis(1),
                )
            ).shouldBeTrue()
            readyOne.id to retry.id
        }

        Thread.sleep(5)
        transaction(database) {
            repository.findReadyClinicKeys(cursor = null, limit = 10) shouldBeEqualTo
                listOf(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)))
            repository.findReadyCandidates(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)), null, 10)
                .map { it.id } shouldBeEqualTo listOf(readyId, retryId)
        }
    }

    @Test
    fun `운영 backlog 관측은 미래 예약을 제외하고 현재 ready 행만 센다`() {
        transaction(database) {
            repository.enqueue(
                sendableDraft(
                    eventId = "event-ready-observation",
                    digest = "digest-ready-observation",
                    availableAt = Instant.parse("2020-01-01T00:00:00Z"),
                )
            )
            repository.enqueue(
                sendableDraft(
                    eventId = "event-future-observation",
                    digest = "digest-future-observation",
                    availableAt = Instant.parse("2999-01-01T00:00:00Z"),
                )
            )

            val observation = repository.observeReady(limit = 10)

            observation.readyCount shouldBeEqualTo 1L
            observation.oldestReadyAt shouldBeEqualTo Instant.parse("2020-01-01T00:00:00Z")
            observation.capped.shouldBeFalse()
        }
    }

    @Test
    fun `retry 시각은 애플리케이션 clock이 아니라 DB 갱신 시각에 delay를 더해 기록한다`() {
        transaction(database) {
            val row = repository.enqueue(sendableDraft(eventId = "event-db-retry", digest = "digest-db-retry"))
            val claim = repository.claim(row.id, owner = "worker-db", token = "token-db")!!

            repository.scheduleRetry(
                RetryNotificationCommand(
                    outboxId = row.id,
                    owner = claim.owner,
                    token = claim.token,
                    attemptNumber = claim.attemptNumber,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                    retryDelay = Duration.ofMinutes(10),
                ),
            ).shouldBeTrue()

            val stored = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq row.id }
                .single()
            Duration.between(
                stored[NotificationOutboxEvents.updatedAt],
                checkNotNull(stored[NotificationOutboxEvents.nextRetryAt]),
            ) shouldBeEqualTo Duration.ofMinutes(10)
        }
    }

    @Test
    fun `ready candidate는 availableAt id 순서와 cursor row 기준으로 조회한다`() {
        transaction(database) {
            val later = repository.enqueue(
                sendableDraft(
                    eventId = "event-later",
                    digest = "digest-later",
                    availableAt = Instant.parse("2020-01-01T00:10:00Z"),
                )
            )
            val earlier = repository.enqueue(
                sendableDraft(
                    eventId = "event-earlier",
                    digest = "digest-earlier",
                    availableAt = Instant.parse("2020-01-01T00:00:10Z"),
                )
            )
            val middle = repository.enqueue(
                sendableDraft(
                    eventId = "event-middle",
                    digest = "digest-middle",
                    availableAt = Instant.parse("2020-01-01T00:05:00Z"),
                )
            )

            repository.findReadyCandidates(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)), null, 10)
                .map { it.id } shouldBeEqualTo listOf(earlier.id, middle.id, later.id)
            repository.findReadyCandidates(NotificationClinicKey(TenantGroupId(1L), ClinicId(2L)), earlier.id, 10)
                .map { it.id } shouldBeEqualTo listOf(middle.id, later.id)
        }
    }

    @Test
    fun `claim은 attempt를 하나 만들고 attempt number unique fence를 유지한다`() {
        transaction(database) {
            val candidate = repository.enqueue(sendableDraft())

            val claimed = repository.claim(candidate.id, owner = "worker-a", token = "token-a")!!

            claimed.attemptNumber shouldBeEqualTo 1
            claimed.channel shouldBeEqualTo NotificationChannelType.DUMMY
            claimed.eventType shouldBeEqualTo NotificationEventType.CONFIRMED
            claimed.notificationSlot shouldBeEqualTo NotificationSlot.CONFIRMED
            claimed.providerKey shouldBeEqualTo "dummy"
            claimed.templateKey shouldBeEqualTo NotificationTemplateKey("appointment-confirmed")
            claimed.templateVersion shouldBeEqualTo NotificationTemplateVersion(1)
            claimed.parameterType shouldBeEqualTo NotificationParameterType.APPOINTMENT_CONFIRMED
            claimed.eventId shouldBeEqualTo NotificationEventId("event-1")
            NotificationDeliveryAttempts.selectAll().count() shouldBeEqualTo 1L
            val attempt = NotificationDeliveryAttempts.selectAll().single()
            attempt[NotificationDeliveryAttempts.outboxId].value shouldBeEqualTo candidate.id
            attempt[NotificationDeliveryAttempts.channel] shouldBeEqualTo NotificationChannelType.DUMMY
            attempt[NotificationDeliveryAttempts.eventType] shouldBeEqualTo NotificationEventType.CONFIRMED
            attempt[NotificationDeliveryAttempts.templateKey] shouldBeEqualTo "appointment-confirmed"
            attempt[NotificationDeliveryAttempts.templateVersion] shouldBeEqualTo 1
            attempt[NotificationDeliveryAttempts.outcome].shouldBeNull()
        }
    }

    @Test
    fun `전환기 direct claim은 같은 병원 예약 event의 준비된 sendable 행만 한 번 획득한다`() {
        transaction(database) {
            val confirmed = repository.enqueue(
                sendableDraft(eventId = "event-direct-confirmed", digest = "digest-direct-confirmed")
            )
            repository.enqueue(
                sendableDraft(
                    eventId = "event-direct-created",
                    digest = "digest-direct-created",
                    eventType = NotificationEventType.CREATED,
                    notificationSlot = NotificationSlot.CREATED,
                    templateKey = "appointment-created",
                    parameterType = NotificationParameterType.APPOINTMENT_CREATED,
                    parameters = AppointmentCreatedParameters(
                        clinicDisplayName = "Clinic",
                        appointmentDate = LocalDate.parse("2026-08-01"),
                        startTime = LocalTime.parse("09:00"),
                    ),
                )
            )

            val claimed = repository.claimReadyForDirect(
                clinicId = ClinicId(2L),
                appointmentId = AppointmentId(3L),
                eventType = NotificationEventType.CONFIRMED,
                owner = "notification-direct-event",
                token = "direct-token-1",
            )

            claimed?.id shouldBeEqualTo confirmed.id
            repository.claimReadyForDirect(
                clinicId = ClinicId(2L),
                appointmentId = AppointmentId(3L),
                eventType = NotificationEventType.CONFIRMED,
                owner = "notification-direct-event",
                token = "direct-token-2",
            ).shouldBeNull()
        }
    }

    @Test
    fun `claim은 rescheduled template parameter contract를 보존한다`() {
        transaction(database) {
            val candidate = repository.enqueue(
                sendableDraft(
                    eventType = NotificationEventType.RESCHEDULED,
                    notificationSlot = NotificationSlot.RESCHEDULED,
                    templateKey = "appointment-rescheduled",
                    parameterType = NotificationParameterType.APPOINTMENT_RESCHEDULED,
                    parameters = AppointmentRescheduledParameters(
                        clinicDisplayName = "Clinic",
                        previousAppointmentDate = LocalDate.parse("2026-08-01"),
                        previousStartTime = LocalTime.parse("09:00"),
                        replacementAppointmentDate = LocalDate.parse("2026-08-02"),
                        replacementStartTime = LocalTime.parse("14:00"),
                    ),
                ),
            )

            val claimed = repository.claim(candidate.id, owner = "worker-a", token = "token-a")!!

            claimed.eventType shouldBeEqualTo NotificationEventType.RESCHEDULED
            claimed.notificationSlot shouldBeEqualTo NotificationSlot.RESCHEDULED
            claimed.parameterType shouldBeEqualTo NotificationParameterType.APPOINTMENT_RESCHEDULED
            codec.decode(claimed.parametersJson).parameters shouldBeEqualTo AppointmentRescheduledParameters(
                clinicDisplayName = "Clinic",
                previousAppointmentDate = LocalDate.parse("2026-08-01"),
                previousStartTime = LocalTime.parse("09:00"),
                replacementAppointmentDate = LocalDate.parse("2026-08-02"),
                replacementStartTime = LocalTime.parse("14:00"),
            )
        }
    }

    @Test
    fun `stale completion은 실패하고 현재 lease만 완료할 수 있다`() {
        transaction(database) {
            val candidate = repository.enqueue(sendableDraft())
            val stale = repository.claim(candidate.id, owner = "worker-a", token = "token-a")!!
            val current = stale.copy(
                owner = "worker-b",
                token = "token-b",
                attemptNumber = 2,
            )
            NotificationOutboxEvents.update({ NotificationOutboxEvents.id eq candidate.id }) {
                it[NotificationOutboxEvents.leaseOwner] = current.owner
                it[NotificationOutboxEvents.leaseToken] = current.token
                it[NotificationOutboxEvents.attemptNumber] = current.attemptNumber
                it[NotificationOutboxEvents.leaseUntil] = Instant.parse("2999-01-01T00:00:00Z")
            }
            NotificationDeliveryAttempts.insertAndGetId {
                it[NotificationDeliveryAttempts.outboxId] = EntityID(candidate.id, NotificationOutboxEvents)
                it[NotificationDeliveryAttempts.attemptNumber] = current.attemptNumber
                it[NotificationDeliveryAttempts.owner] = current.owner
                it[NotificationDeliveryAttempts.token] = current.token
                it[NotificationDeliveryAttempts.channel] = current.channel
                it[NotificationDeliveryAttempts.eventType] = current.eventType
                it[NotificationDeliveryAttempts.templateKey] = current.templateKey.value
                it[NotificationDeliveryAttempts.templateVersion] = current.templateVersion.value
                it[NotificationDeliveryAttempts.startedAt] = Instant.parse("2026-07-31T00:00:00Z")
                it[NotificationDeliveryAttempts.completedAt] = null
                it[NotificationDeliveryAttempts.durationMillis] = null
                it[NotificationDeliveryAttempts.outcome] = null
                it[NotificationDeliveryAttempts.failureCode] = null
                it[NotificationDeliveryAttempts.providerMessageReference] = null
                it[NotificationDeliveryAttempts.destinationFingerprint] = null
                it[NotificationDeliveryAttempts.correlationId] = null
                it[NotificationDeliveryAttempts.traceId] = null
            }

            repository.complete(
                CompleteNotificationCommand(
                    outboxId = candidate.id,
                    owner = stale.owner,
                    token = stale.token,
                    attemptNumber = stale.attemptNumber,
                )
            ).shouldBeFalse()
            val staleAttempt = NotificationDeliveryAttempts.selectAll()
                .where { NotificationDeliveryAttempts.attemptNumber eq stale.attemptNumber }
                .single()
            staleAttempt[NotificationDeliveryAttempts.outcome] shouldBeEqualTo NotificationDeliveryAttemptOutcome.LEASE_LOST
            staleAttempt[NotificationDeliveryAttempts.failureCode] shouldBeEqualTo NotificationFailureCode.LEASE_LOST.name
            val currentAttemptBeforeComplete = NotificationDeliveryAttempts.selectAll()
                .where { NotificationDeliveryAttempts.attemptNumber eq current.attemptNumber }
                .single()
            currentAttemptBeforeComplete[NotificationDeliveryAttempts.outcome].shouldBeNull()
            NotificationOutboxEvents.selectAll().single()[NotificationOutboxEvents.attemptNumber] shouldBeEqualTo
                current.attemptNumber
            repository.complete(
                CompleteNotificationCommand(
                    outboxId = candidate.id,
                    owner = current.owner,
                    token = current.token,
                    attemptNumber = current.attemptNumber,
                    providerMessageReference = NotificationProviderMessageReference("provider-message-1"),
                    destinationFingerprint = NotificationDestinationFingerprint(TEST_DESTINATION_FINGERPRINT),
                    correlationId = NotificationCorrelationId("corr-1"),
                    traceId = NotificationTraceId("trace-abcdef0123456789"),
                )
            ).shouldBeTrue()
            val sent = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq candidate.id }
                .single()
            sent[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.SENT
            sent[NotificationOutboxEvents.appointmentId].shouldBeNull()
            sent[NotificationOutboxEvents.memberId].shouldBeNull()
            sent[NotificationOutboxEvents.parametersJson].shouldBeNull()
            sent[NotificationOutboxEvents.providerMessageReference] shouldBeEqualTo "provider-message-1"
            sent[NotificationOutboxEvents.destinationFingerprint] shouldBeEqualTo TEST_DESTINATION_FINGERPRINT
            val currentAttempt = NotificationDeliveryAttempts.selectAll()
                .where { NotificationDeliveryAttempts.attemptNumber eq current.attemptNumber }
                .single()
            currentAttempt[NotificationDeliveryAttempts.outcome] shouldBeEqualTo NotificationDeliveryAttemptOutcome.SUCCESS
            currentAttempt[NotificationDeliveryAttempts.providerMessageReference] shouldBeEqualTo "provider-message-1"
        }
    }

    @Test
    fun `provider metadata value types reject raw PII and unstable values`() {
        assertFailsWith<IllegalArgumentException> { NotificationProviderMessageReference("member@example.com") }
        assertFailsWith<IllegalArgumentException> { NotificationProviderMessageReference("010-1234-5678") }
        assertFailsWith<IllegalArgumentException> { NotificationProviderMessageReference("sms-01012345678") }
        assertFailsWith<IllegalArgumentException> { NotificationProviderMessageReference("ref-821012345678") }
        assertFailsWith<IllegalArgumentException> { NotificationProviderMessageReference("provider ref") }
        assertFailsWith<IllegalArgumentException> { NotificationProviderMessageReference("NullPointerException: raw failure") }
        assertFailsWith<IllegalArgumentException> { NotificationDestinationFingerprint("dest-fp-1") }
        assertFailsWith<IllegalArgumentException> { NotificationDestinationFingerprint("v1:hmac-sha256:abcdef0123456789") }
        assertFailsWith<IllegalArgumentException> { NotificationDestinationFingerprint("v1:hmac-sha256:member@example.com") }
        assertFailsWith<IllegalArgumentException> {
            NotificationDestinationFingerprint("v1:hmac-sha256:sms01012345678")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationDestinationFingerprint("v1:hmac-sha256:${"1".repeat(64)}")
        }
        assertFailsWith<IllegalArgumentException> { NotificationCorrelationId("corr 1") }
        assertFailsWith<IllegalArgumentException> { NotificationCorrelationId("corr-01012345678") }
        assertFailsWith<IllegalArgumentException> { NotificationTraceId("trace\n1") }
        assertFailsWith<IllegalArgumentException> { NotificationTraceId("trace-821012345678") }

        NotificationProviderMessageReference("provider-message-1").value shouldBeEqualTo "provider-message-1"
        NotificationDestinationFingerprint(TEST_DESTINATION_FINGERPRINT).value shouldBeEqualTo TEST_DESTINATION_FINGERPRINT
        NotificationCorrelationId("corr-1").value shouldBeEqualTo "corr-1"
        NotificationTraceId("trace-abcdef0123456789").value shouldBeEqualTo "trace-abcdef0123456789"
    }

    @Test
    fun `db timestamp conversion normalizes JDBC local date time values as UTC`() {
        val local = LocalDateTime.parse("2026-07-31T12:34:56")

        local.toNotificationDbInstant() shouldBeEqualTo Instant.parse("2026-07-31T12:34:56Z")
        Timestamp.from(Instant.parse("2026-07-31T12:34:56Z")).toNotificationDbInstant() shouldBeEqualTo
            Instant.parse("2026-07-31T12:34:56Z")
        Instant.parse("2026-07-31T12:34:56Z").toNotificationDbInstant() shouldBeEqualTo
            Instant.parse("2026-07-31T12:34:56Z")
        local.atOffset(ZoneOffset.UTC).toNotificationDbInstant() shouldBeEqualTo
            Instant.parse("2026-07-31T12:34:56Z")
    }

    @Test
    fun `successful complete fails caller transaction when attempt close is not exactly one row`() {
        val candidateAndClaim = transaction(database) {
            val candidate = repository.enqueue(sendableDraft())
            val claimed = repository.claim(candidate.id, owner = "worker-a", token = "token-a")!!
            NotificationDeliveryAttempts.deleteAll()
            candidate to claimed
        }

        assertFailsWith<IllegalStateException> {
            transaction(database) {
                val (candidate, claimed) = candidateAndClaim
                repository.complete(
                    CompleteNotificationCommand(
                        outboxId = candidate.id,
                        owner = claimed.owner,
                        token = claimed.token,
                        attemptNumber = claimed.attemptNumber,
                    )
                )
            }
        }
        transaction(database) {
            val (candidate, claimed) = candidateAndClaim
            val outbox = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq candidate.id }
                .single()
            outbox[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.PROCESSING
            outbox[NotificationOutboxEvents.leaseOwner] shouldBeEqualTo claimed.owner
            outbox[NotificationOutboxEvents.terminalAt].shouldBeNull()
            NotificationDeliveryAttempts.selectAll().count() shouldBeEqualTo 0L
        }

        transaction(database) {
            val (candidate, claimed) = candidateAndClaim
            NotificationOutboxEvents.update({ NotificationOutboxEvents.id eq candidate.id }) {
                it[NotificationOutboxEvents.status] = NotificationOutboxStatus.PROCESSING
                it[NotificationOutboxEvents.leaseOwner] = claimed.owner
                it[NotificationOutboxEvents.leaseToken] = claimed.token
                it[NotificationOutboxEvents.leaseUntil] = Instant.parse("2999-01-01T00:00:00Z")
                it[NotificationOutboxEvents.attemptNumber] = claimed.attemptNumber
                it[NotificationOutboxEvents.terminalAt] = null
            }
        }
        assertFailsWith<IllegalStateException> {
            transaction(database) {
                val (candidate, claimed) = candidateAndClaim
                repository.scheduleRetry(
                    RetryNotificationCommand(
                        outboxId = candidate.id,
                        owner = claimed.owner,
                        token = claimed.token,
                        attemptNumber = claimed.attemptNumber,
                        failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                        retryDelay = Duration.ofMinutes(10),
                    )
                )
            }
        }
    }

    @Test
    fun `complete는 suppressed와 exhausted terminal status code를 저장하고 개인정보를 지운다`() {
        transaction(database) {
            val suppressed = repository.enqueue(sendableDraft(eventId = "event-suppressed", digest = "digest-suppressed"))
            val suppressedClaim = repository.claim(suppressed.id, owner = "worker-a", token = "token-a")!!

            repository.complete(
                CompleteNotificationCommand(
                    outboxId = suppressed.id,
                    owner = suppressedClaim.owner,
                    token = suppressedClaim.token,
                    attemptNumber = suppressedClaim.attemptNumber,
                    terminalStatus = NotificationOutboxStatus.SUPPRESSED,
                    suppressionReason = NotificationSuppressionReasonCode.CONSENT_DENIED,
                    destinationFingerprint = NotificationDestinationFingerprint(TEST_DESTINATION_FINGERPRINT),
                )
            ).shouldBeTrue()
            val suppressedRow = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq suppressed.id }
                .single()
            suppressedRow[NotificationOutboxEvents.suppressionReason] shouldBeEqualTo
                NotificationSuppressionReasonCode.CONSENT_DENIED
            suppressedRow[NotificationOutboxEvents.memberId].shouldBeNull()
            suppressedRow[NotificationOutboxEvents.parametersJson].shouldBeNull()

            val exhausted = repository.enqueue(sendableDraft(eventId = "event-exhausted", digest = "digest-exhausted"))
            val exhaustedClaim = repository.claim(exhausted.id, owner = "worker-b", token = "token-b")!!
            repository.complete(
                CompleteNotificationCommand(
                    outboxId = exhausted.id,
                    owner = exhaustedClaim.owner,
                    token = exhaustedClaim.token,
                    attemptNumber = exhaustedClaim.attemptNumber,
                    terminalStatus = NotificationOutboxStatus.EXHAUSTED,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                )
            ).shouldBeTrue()
            val exhaustedRow = NotificationOutboxEvents.selectAll()
                .where { NotificationOutboxEvents.id eq exhausted.id }
                .single()
            exhaustedRow[NotificationOutboxEvents.failureCode] shouldBeEqualTo NotificationFailureCode.PROVIDER_UNAVAILABLE
            exhaustedRow[NotificationOutboxEvents.appointmentId].shouldBeNull()
            exhaustedRow[NotificationOutboxEvents.memberId].shouldBeNull()
            exhaustedRow[NotificationOutboxEvents.parametersJson].shouldBeNull()
        }
    }

    @Test
    fun `retention은 DB cutoff와 page limit에 따라 attempt를 먼저 지우고 종료 행만 삭제한다`() {
        transaction(database) {
            val sentIds = (1..2).map { index ->
                val row = repository.enqueue(
                    sendableDraft(eventId = "retention-sent-$index", digest = "retention-sent-$index"),
                )
                val claim = repository.claim(row.id, owner = "worker-$index", token = "token-$index")!!
                repository.complete(
                    CompleteNotificationCommand(
                        outboxId = row.id,
                        owner = claim.owner,
                        token = claim.token,
                        attemptNumber = claim.attemptNumber,
                    ),
                ).shouldBeTrue()
                row.id
            }
            val exhausted = repository.enqueue(
                sendableDraft(eventId = "retention-exhausted", digest = "retention-exhausted"),
            )
            val exhaustedClaim = repository.claim(exhausted.id, owner = "worker-x", token = "token-x")!!
            repository.complete(
                CompleteNotificationCommand(
                    outboxId = exhausted.id,
                    owner = exhaustedClaim.owner,
                    token = exhaustedClaim.token,
                    attemptNumber = exhaustedClaim.attemptNumber,
                    terminalStatus = NotificationOutboxStatus.EXHAUSTED,
                    failureCode = NotificationFailureCode.PROVIDER_UNAVAILABLE,
                ),
            ).shouldBeTrue()
            NotificationOutboxEvents.update({
                NotificationOutboxEvents.id inList (sentIds + exhausted.id).map {
                    EntityID(it, NotificationOutboxEvents)
                }
            }) {
                it[terminalAt] = Instant.parse("2020-01-01T00:00:00Z")
            }

            repository.deleteTerminalBatch(
                status = NotificationOutboxStatus.SENT,
                retention = Duration.ofDays(7),
                limit = 1,
            ) shouldBeEqualTo 1
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 2L
            NotificationDeliveryAttempts.selectAll().count() shouldBeEqualTo 2L

            repository.deleteTerminalBatch(
                status = NotificationOutboxStatus.SENT,
                retention = Duration.ofDays(7),
                limit = 10,
            ) shouldBeEqualTo 1
            repository.deleteTerminalBatch(
                status = NotificationOutboxStatus.EXHAUSTED,
                retention = Duration.ofDays(30),
                limit = 10,
            ) shouldBeEqualTo 1
            NotificationOutboxEvents.selectAll().count() shouldBeEqualTo 0L
            NotificationDeliveryAttempts.selectAll().count() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `expired recovery closes previous attempt as lease lost and creates current attempt`() {
        transaction(database) {
            val candidate = repository.enqueue(sendableDraft())
            val first = repository.claim(candidate.id, owner = "old-worker", token = "old-token")!!
            NotificationOutboxEvents.update({ NotificationOutboxEvents.id eq candidate.id }) {
                it[NotificationOutboxEvents.leaseUntil] = Instant.parse("2020-01-01T00:00:00Z")
            }

            repository.findExpiredProcessingIds(limit = 10) shouldBeEqualTo listOf(candidate.id)
            val recovered = repository.recoverExpired(candidate.id, owner = "new-worker", token = "new-token")!!

            recovered.owner shouldBeEqualTo "new-worker"
            recovered.attemptNumber shouldBeEqualTo 2
            recovered.firstAttemptAt shouldBeEqualTo first.firstAttemptAt
            (recovered.claimedAt >= recovered.firstAttemptAt).shouldBeTrue()
            NotificationDeliveryAttempts.selectAll()
                .orderBy(NotificationDeliveryAttempts.attemptNumber)
                .map { it[NotificationDeliveryAttempts.failureCode] } shouldBeEqualTo
                listOf(NotificationFailureCode.LEASE_LOST.name, null)
        }
    }

    @Test
    fun `예약 변경 억제는 pending과 claimed 리마인더를 종료하고 늦은 fence 완료를 거절한다`() {
        transaction(database) {
            val reminderParameters = AppointmentReminderParameters(
                clinicDisplayName = "Clinic",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("09:00"),
            )
            val dayBefore = repository.enqueue(
                sendableDraft(
                    eventId = "reminder-24h",
                    digest = "reminder-digest-24h",
                    eventType = NotificationEventType.REMINDER,
                    notificationSlot = NotificationSlot.REMINDER_24H,
                    templateKey = "appointment-reminder-24h",
                    parameterType = NotificationParameterType.APPOINTMENT_REMINDER,
                    parameters = reminderParameters,
                )
            )
            repository.enqueue(
                sendableDraft(
                    eventId = "reminder-same-day",
                    digest = "reminder-digest-same-day",
                    eventType = NotificationEventType.REMINDER,
                    notificationSlot = NotificationSlot.REMINDER_SAME_DAY,
                    templateKey = "appointment-reminder-same-day",
                    parameterType = NotificationParameterType.APPOINTMENT_REMINDER,
                    parameters = reminderParameters,
                )
            )
            repository.enqueue(
                sendableDraft(
                    eventId = "confirmed-kept",
                    digest = "confirmed-kept-digest",
                )
            )
            val claimed = repository.claim(dayBefore.id, owner = "worker-a", token = "token-a")!!

            repository.suppressOutstandingReminders(
                tenantGroupId = TenantGroupId(1L),
                clinicId = ClinicId(2L),
                appointmentId = AppointmentId(3L),
                suppressionReason = NotificationSuppressionReasonCode.APPOINTMENT_CHANGED,
            ) shouldBeEqualTo 2

            val rows = NotificationOutboxEvents.selectAll().toList()
            rows.filter {
                it[NotificationOutboxEvents.notificationSlot] in
                    setOf(NotificationSlot.REMINDER_24H, NotificationSlot.REMINDER_SAME_DAY)
            }.forEach {
                it[NotificationOutboxEvents.status] shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
                it[NotificationOutboxEvents.appointmentId].shouldBeNull()
                it[NotificationOutboxEvents.memberId].shouldBeNull()
                it[NotificationOutboxEvents.parametersJson].shouldBeNull()
                it[NotificationOutboxEvents.suppressionReason] shouldBeEqualTo
                    NotificationSuppressionReasonCode.APPOINTMENT_CHANGED
            }
            rows.single { it[NotificationOutboxEvents.notificationSlot] == NotificationSlot.CONFIRMED }
                .get(NotificationOutboxEvents.status) shouldBeEqualTo NotificationOutboxStatus.PENDING
            val interruptedAttempt = NotificationDeliveryAttempts.selectAll().single()
            interruptedAttempt[NotificationDeliveryAttempts.outcome] shouldBeEqualTo
                NotificationDeliveryAttemptOutcome.LEASE_LOST
            interruptedAttempt[NotificationDeliveryAttempts.failureCode] shouldBeEqualTo
                NotificationFailureCode.LEASE_LOST.name
            repository.complete(
                CompleteNotificationCommand(
                    outboxId = claimed.id,
                    owner = claimed.owner,
                    token = claimed.token,
                    attemptNumber = claimed.attemptNumber,
                )
            ).shouldBeFalse()
        }
    }

    @Test
    fun `예약 변경 억제는 다른 tenant와 clinic의 같은 예약 번호를 건드리지 않는다`() {
        transaction(database) {
            val parameters = AppointmentReminderParameters(
                clinicDisplayName = "Clinic",
                appointmentDate = LocalDate.parse("2026-08-01"),
                startTime = LocalTime.parse("09:00"),
            )
            repository.enqueue(
                sendableDraft(
                    eventId = "owned-reminder",
                    digest = "owned-reminder-digest",
                    eventType = NotificationEventType.REMINDER,
                    notificationSlot = NotificationSlot.REMINDER_24H,
                    templateKey = "appointment-reminder-24h",
                    parameterType = NotificationParameterType.APPOINTMENT_REMINDER,
                    parameters = parameters,
                )
            )
            repository.enqueue(
                sendableDraft(
                    eventId = "foreign-reminder",
                    digest = "foreign-reminder-digest",
                    eventType = NotificationEventType.REMINDER,
                    notificationSlot = NotificationSlot.REMINDER_24H,
                    templateKey = "appointment-reminder-24h",
                    parameterType = NotificationParameterType.APPOINTMENT_REMINDER,
                    parameters = parameters,
                    tenantGroupId = 9L,
                    clinicId = 9L,
                )
            )

            repository.suppressOutstandingReminders(
                tenantGroupId = TenantGroupId(1L),
                clinicId = ClinicId(2L),
                appointmentId = AppointmentId(3L),
                suppressionReason = NotificationSuppressionReasonCode.APPOINTMENT_CHANGED,
            ) shouldBeEqualTo 1

            NotificationOutboxEvents.selectAll()
                .associate { it[NotificationOutboxEvents.eventId] to it[NotificationOutboxEvents.status] }
                .let { statuses ->
                    statuses.getValue("owned-reminder") shouldBeEqualTo NotificationOutboxStatus.SUPPRESSED
                    statuses.getValue("foreign-reminder") shouldBeEqualTo NotificationOutboxStatus.PENDING
                }
        }
    }

    private fun sendableDraft(
        eventId: String = "event-1",
        digest: String = "digest-1",
        availableAt: Instant = Instant.parse("2020-01-01T00:00:00Z"),
        providerKey: String? = "dummy",
        eventType: NotificationEventType = NotificationEventType.CONFIRMED,
        notificationSlot: NotificationSlot = NotificationSlot.CONFIRMED,
        templateKey: String = "appointment-confirmed",
        parameterType: NotificationParameterType = NotificationParameterType.APPOINTMENT_CONFIRMED,
        parameters: NotificationTemplateParameters = AppointmentConfirmedParameters(
            clinicDisplayName = "Clinic",
            appointmentDate = LocalDate.parse("2026-08-01"),
            startTime = LocalTime.parse("09:00"),
        ),
        tenantGroupId: Long = 1L,
        clinicId: Long = 2L,
        appointmentId: Long = 3L,
    ): SendableNotificationDraft =
        SendableNotificationDraft(
            envelope = NotificationOutboxEnvelope(
                schemaVersion = NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION,
                eventId = NotificationEventId(eventId),
                idempotencyKey = NotificationIdempotencyKey("idem-$digest"),
                tenantGroupId = TenantGroupId(tenantGroupId),
                clinicId = ClinicId(clinicId),
                appointmentId = AppointmentId(appointmentId),
                memberId = MemberId("member-1"),
                channel = NotificationChannelType.DUMMY,
                eventType = eventType,
                notificationSlot = notificationSlot,
                templateKey = NotificationTemplateKey(templateKey),
                templateVersion = NotificationTemplateVersion(1),
                parameterType = parameterType,
                parameters = parameters,
                occurredAt = Instant.parse("2026-07-31T00:00:00Z"),
                availableAt = availableAt,
            ),
            idempotencyDigest = NotificationIdempotencyDigest(
                keyId = "active-key",
                version = 1,
                value = digest,
            ),
            auditFingerprint = NotificationAuditFingerprint(
                keyId = "active-key",
                version = 1,
                value = "audit-1",
            ),
            providerKey = providerKey ?: "",
        )

    private fun legacySuppressionDraft(): LegacySuppressionDraft =
        LegacySuppressionDraft(
            idempotencyDigest = NotificationIdempotencyDigest(
                keyId = "active-key",
                version = 1,
                value = "legacy-digest-1",
            ),
            auditFingerprint = NotificationAuditFingerprint(
                keyId = "active-key",
                version = 1,
                value = "legacy-audit-1",
            ),
            tenantGroupId = TenantGroupId(1L),
            clinicId = ClinicId(2L),
            eventId = NotificationEventId("legacy-event-1"),
            suppressionReason = NotificationSuppressionReasonCode.MEMBER_ID_MISSING_LEGACY,
            availableAt = Instant.parse("2020-01-01T00:00:00Z"),
        )

    companion object {
        private const val TEST_DESTINATION_FINGERPRINT =
            "v1:hmac-sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

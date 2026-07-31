package io.bluetape4k.clinic.appointment.event.notification

import io.bluetape4k.clinic.appointment.model.identity.MemberId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/**
 * caller transaction 안에서만 동작하는 알림 outbox repository다.
 *
 * 모든 public 메서드는 자체 `transaction {}`을 열지 않는다. caller는 예약 변경과
 * enqueue를 같은 Exposed transaction에 넣고, worker는 claim transaction을 닫은 뒤
 * provider I/O를 수행해야 한다.
 */
class NotificationOutboxRepository(
    private val codec: NotificationOutboxCodec,
    private val leaseDuration: Duration,
) {

    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
    }

    fun enqueue(draft: SendableNotificationDraft): NotificationOutboxRecord {
        findByIdempotency(draft.idempotencyDigest)?.let { return it }

        return try {
            insertSendable(draft)
        } catch (e: ExposedSQLException) {
            findByIdempotency(draft.idempotencyDigest) ?: throw e
        }
    }

    fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxRecord {
        findByIdempotency(draft.idempotencyDigest)?.let { return it }

        return try {
            insertSuppression(draft)
        } catch (e: ExposedSQLException) {
            findByIdempotency(draft.idempotencyDigest) ?: throw e
        }
    }

    fun findReadyClinicKeys(
        cursor: NotificationFairCursor?,
        limit: Int,
    ): List<NotificationClinicKey> {
        require(limit > 0) { "limit must be positive" }
        val dbNow = dbCurrentTimestamp()
        val ready = readyPredicate(dbNow)
        val cursorPredicate = cursor?.let {
            (NotificationOutboxEvents.tenantGroupId greater it.tenantGroupId.value) or
                ((NotificationOutboxEvents.tenantGroupId eq it.tenantGroupId.value) and
                    (NotificationOutboxEvents.clinicId greater it.clinicId.value))
        }

        return NotificationOutboxEvents
            .select(
                NotificationOutboxEvents.tenantGroupId,
                NotificationOutboxEvents.clinicId,
            )
            .where(if (cursorPredicate == null) ready else ready and cursorPredicate)
            .withDistinct()
            .orderBy(NotificationOutboxEvents.tenantGroupId to SortOrder.ASC, NotificationOutboxEvents.clinicId to SortOrder.ASC)
            .limit(limit)
            .map {
                NotificationClinicKey(
                    tenantGroupId = TenantGroupId(it[NotificationOutboxEvents.tenantGroupId]),
                    clinicId = ClinicId(it[NotificationOutboxEvents.clinicId]),
                )
            }
    }

    fun findReadyCandidates(
        key: NotificationClinicKey,
        cursorId: Long?,
        limit: Int,
    ): List<NotificationCandidate> {
        require(limit > 0) { "limit must be positive" }
        cursorId?.let { require(it > 0) { "cursorId must be positive" } }
        val dbNow = dbCurrentTimestamp()
        val cursorPredicate = cursorId?.let { NotificationOutboxEvents.id greater it }

        return NotificationOutboxEvents
            .selectAll()
            .where(
                readyPredicate(dbNow) and
                    (NotificationOutboxEvents.tenantGroupId eq key.tenantGroupId.value) and
                    (NotificationOutboxEvents.clinicId eq key.clinicId.value) and
                    (cursorPredicate ?: org.jetbrains.exposed.v1.core.Op.TRUE),
            )
            .orderBy(NotificationOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toCandidate() }
    }

    fun claim(candidateId: Long, owner: String, token: String): ClaimedNotification? {
        require(candidateId > 0) { "candidateId must be positive" }
        val validOwner = owner.validFence("owner")
        val validToken = token.validFence("token")
        val row = findOutboxForUpdate(candidateId) ?: return null
        val dbNow = dbCurrentTimestamp()
        if (!row.isReady(dbNow)) return null

        return openAttempt(row, validOwner, validToken, dbNow)
    }

    fun recoverExpired(candidateId: Long, owner: String, token: String): ClaimedNotification? {
        require(candidateId > 0) { "candidateId must be positive" }
        val validOwner = owner.validFence("owner")
        val validToken = token.validFence("token")
        val row = findOutboxForUpdate(candidateId) ?: return null
        val dbNow = dbCurrentTimestamp()
        if (!row.isExpiredProcessing(dbNow)) return null

        closeAttempt(
            outboxId = candidateId,
            attemptNumber = row[NotificationOutboxEvents.attemptNumber],
            succeeded = false,
            failureCode = NotificationFailureCode.LEASE_LOST,
            finishedAt = dbNow,
        )
        return openAttempt(row, validOwner, validToken, dbNow)
    }

    fun complete(command: CompleteNotificationCommand): Boolean {
        command.validate()
        val dbNow = dbCurrentTimestamp()
        val updated = NotificationOutboxEvents.update({ fencedProcessingPredicate(command, dbNow) }) {
            it[status] = NotificationOutboxStatus.SENT
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[terminalAt] = dbNow
            it[updatedAt] = dbNow
        }
        if (updated != 1) return false

        closeAttempt(
            outboxId = command.outboxId,
            attemptNumber = command.attemptNumber,
            succeeded = true,
            failureCode = null,
            finishedAt = dbNow,
        )
        return true
    }

    fun scheduleRetry(command: RetryNotificationCommand): Boolean {
        command.validate()
        val dbNow = dbCurrentTimestamp()
        val updated = NotificationOutboxEvents.update({ fencedProcessingPredicate(command, dbNow) }) {
            it[status] = NotificationOutboxStatus.RETRY_WAIT
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[nextRetryAt] = command.nextAttemptAt
            it[updatedAt] = dbNow
        }
        if (updated != 1) return false

        closeAttempt(
            outboxId = command.outboxId,
            attemptNumber = command.attemptNumber,
            succeeded = false,
            failureCode = command.failureCode,
            finishedAt = dbNow,
        )
        return true
    }

    private fun insertSendable(draft: SendableNotificationDraft): NotificationOutboxRecord {
        val envelope = draft.envelope
        val dbNow = dbCurrentTimestamp()
        val id = NotificationOutboxEvents.insertAndGetId {
            it[rowKind] = NotificationOutboxRowKind.SENDABLE
            it[status] = NotificationOutboxStatus.PENDING
            it[idempotencyKeyVersion] = draft.idempotencyDigest.version
            it[idempotencyKey] = draft.idempotencyDigest.value
            it[idempotencyKeyId] = draft.idempotencyDigest.keyId
            it[auditFingerprintVersion] = draft.auditFingerprint.version
            it[auditFingerprint] = draft.auditFingerprint.value
            it[auditFingerprintKeyId] = draft.auditFingerprint.keyId
            it[tenantGroupId] = envelope.tenantGroupId.value
            it[clinicId] = envelope.clinicId.value
            it[eventId] = envelope.eventId.value
            it[appointmentId] = envelope.appointmentId.value
            it[memberId] = envelope.memberId.value
            it[channel] = envelope.channel
            it[eventType] = envelope.eventType
            it[notificationSlot] = envelope.notificationSlot
            it[providerKey] = draft.providerKey
            it[templateKey] = envelope.templateKey.value
            it[templateVersion] = envelope.templateVersion.value
            it[parameterType] = envelope.parameterType
            it[parametersJson] = codec.encode(envelope)
            it[suppressionReason] = null
            it[availableAt] = envelope.availableAt
            it[nextRetryAt] = null
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[attemptNumber] = 0
            it[createdAt] = dbNow
            it[updatedAt] = dbNow
            it[terminalAt] = null
        }
        return findById(id.value) ?: error("notification outbox insert did not return a readable row")
    }

    private fun insertSuppression(draft: LegacySuppressionDraft): NotificationOutboxRecord {
        val dbNow = dbCurrentTimestamp()
        val id = NotificationOutboxEvents.insertAndGetId {
            it[rowKind] = NotificationOutboxRowKind.LEGACY_SUPPRESSION
            it[status] = NotificationOutboxStatus.SUPPRESSED
            it[idempotencyKeyVersion] = draft.idempotencyDigest.version
            it[idempotencyKey] = draft.idempotencyDigest.value
            it[idempotencyKeyId] = draft.idempotencyDigest.keyId
            it[auditFingerprintVersion] = draft.auditFingerprint.version
            it[auditFingerprint] = draft.auditFingerprint.value
            it[auditFingerprintKeyId] = draft.auditFingerprint.keyId
            it[tenantGroupId] = draft.tenantGroupId.value
            it[clinicId] = draft.clinicId.value
            it[eventId] = draft.eventId.value
            it[appointmentId] = null
            it[memberId] = null
            it[channel] = null
            it[eventType] = null
            it[notificationSlot] = null
            it[providerKey] = null
            it[templateKey] = null
            it[templateVersion] = null
            it[parameterType] = null
            it[parametersJson] = null
            it[suppressionReason] = draft.suppressionReason
            it[availableAt] = draft.availableAt
            it[nextRetryAt] = null
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[attemptNumber] = 0
            it[createdAt] = dbNow
            it[updatedAt] = dbNow
            it[terminalAt] = dbNow
        }
        return findById(id.value) ?: error("notification outbox suppression insert did not return a readable row")
    }

    private fun openAttempt(
        row: ResultRow,
        owner: String,
        token: String,
        dbNow: Instant,
    ): ClaimedNotification {
        val outboxId = row[NotificationOutboxEvents.id].value
        val nextAttempt = row[NotificationOutboxEvents.attemptNumber] + 1
        val leaseUntil = dbNow.plus(leaseDuration)
        NotificationOutboxEvents.update({ NotificationOutboxEvents.id eq outboxId }) {
            it[status] = NotificationOutboxStatus.PROCESSING
            it[leaseOwner] = owner
            it[leaseToken] = token
            it[NotificationOutboxEvents.leaseUntil] = leaseUntil
            it[attemptNumber] = nextAttempt
            it[updatedAt] = dbNow
        }
        NotificationDeliveryAttempts.insertAndGetId {
            it[NotificationDeliveryAttempts.outboxId] = EntityID(outboxId, NotificationOutboxEvents)
            it[attemptNumber] = nextAttempt
            it[NotificationDeliveryAttempts.owner] = owner
            it[NotificationDeliveryAttempts.token] = token
            it[startedAt] = dbNow
            it[finishedAt] = null
            it[succeeded] = null
            it[failureCode] = null
        }
        return row.toClaimed(nextAttempt, owner, token, leaseUntil)
    }

    private fun closeAttempt(
        outboxId: Long,
        attemptNumber: Int,
        succeeded: Boolean,
        failureCode: NotificationFailureCode?,
        finishedAt: Instant,
    ) {
        NotificationDeliveryAttempts.update({
            (NotificationDeliveryAttempts.outboxId eq EntityID(outboxId, NotificationOutboxEvents)) and
                (NotificationDeliveryAttempts.attemptNumber eq attemptNumber) and
                NotificationDeliveryAttempts.finishedAt.isNull()
        }) {
            it[NotificationDeliveryAttempts.finishedAt] = finishedAt
            it[NotificationDeliveryAttempts.succeeded] = succeeded
            it[NotificationDeliveryAttempts.failureCode] = failureCode?.name
        }
    }

    private fun findById(id: Long): NotificationOutboxRecord? =
        NotificationOutboxEvents
            .selectAll()
            .where { NotificationOutboxEvents.id eq id }
            .singleOrNull()
            ?.toRecord()

    private fun findByIdempotency(digest: NotificationIdempotencyDigest): NotificationOutboxRecord? =
        NotificationOutboxEvents
            .selectAll()
            .where {
                (NotificationOutboxEvents.idempotencyKeyVersion eq digest.version) and
                    (NotificationOutboxEvents.idempotencyKey eq digest.value)
            }
            .singleOrNull()
            ?.toRecord()

    private fun findOutboxForUpdate(id: Long): ResultRow? =
        NotificationOutboxEvents
            .selectAll()
            .where { NotificationOutboxEvents.id eq id }
            .forUpdate()
            .singleOrNull()

    private fun readyPredicate(dbNow: Instant) =
        (NotificationOutboxEvents.rowKind eq NotificationOutboxRowKind.SENDABLE) and
            (NotificationOutboxEvents.status inList READY_STATUSES) and
            (NotificationOutboxEvents.availableAt lessEq dbNow) and
            (
                NotificationOutboxEvents.nextRetryAt.isNull() or
                    (NotificationOutboxEvents.nextRetryAt lessEq dbNow)
                )

    private fun fencedProcessingPredicate(command: NotificationFenceCommand, dbNow: Instant) =
        (NotificationOutboxEvents.id eq command.outboxId) and
            (NotificationOutboxEvents.rowKind eq NotificationOutboxRowKind.SENDABLE) and
            (NotificationOutboxEvents.status eq NotificationOutboxStatus.PROCESSING) and
            (NotificationOutboxEvents.leaseOwner eq command.owner) and
            (NotificationOutboxEvents.leaseToken eq command.token) and
            (NotificationOutboxEvents.attemptNumber eq command.attemptNumber) and
            (NotificationOutboxEvents.leaseUntil greaterEq dbNow)

    private fun ResultRow.isReady(dbNow: Instant): Boolean =
        this[NotificationOutboxEvents.rowKind] == NotificationOutboxRowKind.SENDABLE &&
            this[NotificationOutboxEvents.status] in READY_STATUSES &&
            this[NotificationOutboxEvents.availableAt] <= dbNow &&
            (this[NotificationOutboxEvents.nextRetryAt]?.let { it <= dbNow } ?: true)

    private fun ResultRow.isExpiredProcessing(dbNow: Instant): Boolean =
        this[NotificationOutboxEvents.rowKind] == NotificationOutboxRowKind.SENDABLE &&
            this[NotificationOutboxEvents.status] == NotificationOutboxStatus.PROCESSING &&
            this[NotificationOutboxEvents.leaseUntil]?.let { it < dbNow } == true

    private fun ResultRow.toRecord(): NotificationOutboxRecord =
        NotificationOutboxRecord(
            id = this[NotificationOutboxEvents.id].value,
            rowKind = this[NotificationOutboxEvents.rowKind],
            status = this[NotificationOutboxEvents.status],
            tenantGroupId = TenantGroupId(this[NotificationOutboxEvents.tenantGroupId]),
            clinicId = ClinicId(this[NotificationOutboxEvents.clinicId]),
            eventId = NotificationEventId(this[NotificationOutboxEvents.eventId]),
            appointmentId = this[NotificationOutboxEvents.appointmentId]?.let(::AppointmentId),
            memberId = this[NotificationOutboxEvents.memberId]?.let(::MemberId),
            templateKey = this[NotificationOutboxEvents.templateKey]?.let(::NotificationTemplateKey),
            parametersJson = this[NotificationOutboxEvents.parametersJson],
            providerKey = this[NotificationOutboxEvents.providerKey],
            attemptNumber = this[NotificationOutboxEvents.attemptNumber],
        )

    private fun ResultRow.toCandidate(): NotificationCandidate =
        NotificationCandidate(
            id = this[NotificationOutboxEvents.id].value,
            tenantGroupId = TenantGroupId(this[NotificationOutboxEvents.tenantGroupId]),
            clinicId = ClinicId(this[NotificationOutboxEvents.clinicId]),
            availableAt = this[NotificationOutboxEvents.availableAt],
        )

    private fun ResultRow.toClaimed(
        attemptNumber: Int,
        owner: String,
        token: String,
        leaseUntil: Instant,
    ): ClaimedNotification {
        val appointmentId = checkNotNull(this[NotificationOutboxEvents.appointmentId]) {
            "sendable notification outbox row must have appointmentId"
        }
        val memberId = checkNotNull(this[NotificationOutboxEvents.memberId]) {
            "sendable notification outbox row must have memberId"
        }
        val parametersJson = checkNotNull(this[NotificationOutboxEvents.parametersJson]) {
            "sendable notification outbox row must have parametersJson"
        }
        return ClaimedNotification(
            id = this[NotificationOutboxEvents.id].value,
            tenantGroupId = TenantGroupId(this[NotificationOutboxEvents.tenantGroupId]),
            clinicId = ClinicId(this[NotificationOutboxEvents.clinicId]),
            appointmentId = AppointmentId(appointmentId),
            memberId = MemberId(memberId),
            owner = owner,
            token = token,
            attemptNumber = attemptNumber,
            leaseUntil = leaseUntil,
            parametersJson = parametersJson,
        )
    }

    private fun dbCurrentTimestamp(): Instant =
        TransactionManager.current().dbCurrentTimestamp()

    companion object {
        private val READY_STATUSES = listOf(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.RETRY_WAIT)
    }
}

/**
 * 새로 발송 가능한 알림을 기록하기 위한 draft다.
 *
 * [idempotencyDigest]는 HMAC digest이며 원문 idempotency key를 직렬화하거나 저장하지
 * 않는다.
 */
data class SendableNotificationDraft(
    val envelope: NotificationOutboxEnvelope,
    val idempotencyDigest: NotificationIdempotencyDigest,
    val auditFingerprint: NotificationAuditFingerprint,
    val providerKey: String,
) : Serializable {
    init {
        validateDurableOpaqueString(providerKey, "providerKey", 128)
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * legacy 예약의 회원 ID 누락을 발송하지 않는 terminal row로 기록하는 draft다.
 */
data class LegacySuppressionDraft(
    val idempotencyDigest: NotificationIdempotencyDigest,
    val auditFingerprint: NotificationAuditFingerprint,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val eventId: NotificationEventId,
    val suppressionReason: NotificationSuppressionReasonCode,
    val availableAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** clinic별 공정 polling key다. */
data class NotificationClinicKey(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** clinic keyset polling cursor다. */
data class NotificationFairCursor(
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** worker가 claim하기 전 조회하는 후보 row다. */
data class NotificationCandidate(
    val id: Long,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val availableAt: Instant,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** claim에 성공한 worker만 provider I/O를 수행할 수 있는 fenced 계약이다. */
data class ClaimedNotification(
    val id: Long,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val appointmentId: AppointmentId,
    val memberId: MemberId,
    val owner: String,
    val token: String,
    val attemptNumber: Int,
    val leaseUntil: Instant,
    val parametersJson: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 저장된 outbox row의 privacy-safe projection이다. */
data class NotificationOutboxRecord(
    val id: Long,
    val rowKind: NotificationOutboxRowKind,
    val status: NotificationOutboxStatus,
    val tenantGroupId: TenantGroupId,
    val clinicId: ClinicId,
    val eventId: NotificationEventId,
    val appointmentId: AppointmentId?,
    val memberId: MemberId?,
    val templateKey: NotificationTemplateKey?,
    val parametersJson: String?,
    val providerKey: String?,
    val attemptNumber: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private interface NotificationFenceCommand {
    val outboxId: Long
    val owner: String
    val token: String
    val attemptNumber: Int
}

/** 성공 완료를 fenced update로 반영하는 command다. */
data class CompleteNotificationCommand(
    override val outboxId: Long,
    override val owner: String,
    override val token: String,
    override val attemptNumber: Int,
) : NotificationFenceCommand,
    Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** retry 대기를 fenced update로 반영하는 command다. */
data class RetryNotificationCommand(
    override val outboxId: Long,
    override val owner: String,
    override val token: String,
    override val attemptNumber: Int,
    val failureCode: NotificationFailureCode,
    val nextAttemptAt: Instant,
) : NotificationFenceCommand,
    Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

private fun NotificationFenceCommand.validate() {
    require(outboxId > 0) { "outboxId must be positive" }
    owner.validFence("owner")
    token.validFence("token")
    require(attemptNumber > 0) { "attemptNumber must be positive" }
}

private fun String.validFence(fieldName: String): String =
    validateDurableOpaqueString(this, fieldName, 128)

private fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) error("SELECT CURRENT_TIMESTAMP returned no rows")
        resultSet.getObject(1).toInstant()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")

private fun Any?.toInstant(): Instant =
    when (this) {
        is Instant -> this
        is Timestamp -> toInstant()
        is OffsetDateTime -> toInstant()
        is ZonedDateTime -> toInstant()
        else -> error("Unsupported CURRENT_TIMESTAMP type: ${this?.javaClass?.name}")
    }

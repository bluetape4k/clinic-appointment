package io.bluetape4k.clinic.appointment.notification.persistence

import io.bluetape4k.clinic.appointment.event.notification.AppointmentId
import io.bluetape4k.clinic.appointment.event.notification.ClinicId
import io.bluetape4k.clinic.appointment.event.notification.LegacySuppressionDraft
import io.bluetape4k.clinic.appointment.event.notification.NotificationChannelType
import io.bluetape4k.clinic.appointment.event.notification.NotificationCorrelationId
import io.bluetape4k.clinic.appointment.event.notification.NotificationDestinationFingerprint
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventId
import io.bluetape4k.clinic.appointment.event.notification.NotificationEventType
import io.bluetape4k.clinic.appointment.event.notification.NotificationFailureCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyDigest
import io.bluetape4k.clinic.appointment.event.notification.NotificationIdempotencyKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxCodec
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriteReceipt
import io.bluetape4k.clinic.appointment.event.notification.NotificationOutboxWriter
import io.bluetape4k.clinic.appointment.event.notification.NotificationParameterType
import io.bluetape4k.clinic.appointment.event.notification.NotificationProviderMessageReference
import io.bluetape4k.clinic.appointment.event.notification.NotificationSlot
import io.bluetape4k.clinic.appointment.event.notification.NotificationSuppressionReasonCode
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateKey
import io.bluetape4k.clinic.appointment.event.notification.NotificationTemplateVersion
import io.bluetape4k.clinic.appointment.event.notification.NotificationTraceId
import io.bluetape4k.clinic.appointment.event.notification.SendableNotificationDraft
import io.bluetape4k.clinic.appointment.event.notification.TenantGroupId
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * caller transaction 안에서만 동작하는 알림 outbox repository다.
 *
 * 모든 public 메서드는 자체 `transaction {}`을 열지 않는다. caller는 예약 변경과
 * enqueue를 같은 Exposed transaction에 넣고, worker는 claim transaction을 닫은 뒤
 * provider I/O를 수행해야 한다.
 */
class JdbcNotificationOutboxRepository(
    private val codec: NotificationOutboxCodec,
    private val leaseDuration: Duration,
) : NotificationOutboxWriter,
    NotificationOutboxWorkPersistence,
    NotificationOutboxObservationPersistence {
    /**
     * 현재 transaction이 연결된 DB의 현재 시각을 반환한다.
     *
     * 외부 I/O가 끝난 뒤 elapsed retry 상한을 판단할 때 애플리케이션 clock 대신 사용한다.
     */
    override fun currentDatabaseTime(): Instant = dbCurrentTimestamp()


    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
    }

    override fun enqueue(draft: SendableNotificationDraft): NotificationOutboxRecord {
        upsertSendable(draft)
        return findByIdempotency(draft.idempotencyDigest)
            ?: error("notification outbox insert was ignored without an idempotency row")
    }

    /** 현재 caller transaction에서 같은 멱등성 digest의 outbox 행이 보이는지 확인합니다. */
    override fun containsIdempotency(digest: NotificationIdempotencyDigest): Boolean =
        findByIdempotency(digest) != null

    override fun suppressLegacy(draft: LegacySuppressionDraft): NotificationOutboxRecord {
        upsertSuppression(draft)
        return findByIdempotency(draft.idempotencyDigest)
            ?: error("notification outbox suppression insert was ignored without an idempotency row")
    }

    /**
     * 예약 변경으로 더 이상 유효하지 않은 미래 리마인더를 종료한다.
     *
     * 이미 claim된 행도 lease fence를 제거하고 open attempt를 `LEASE_LOST`로 닫아
     * provider 결과 불명 가능성을 보존한다. 늦게 도착한 worker 완료 update는 fence
     * 검증에 실패한다. 호출자는 예약 변경과 이 작업을 같은 transaction에서 실행해야 한다.
     */
    override fun suppressOutstandingReminders(
        tenantGroupId: TenantGroupId,
        clinicId: ClinicId,
        appointmentId: AppointmentId,
        suppressionReason: NotificationSuppressionReasonCode,
    ): Int {
        val dbNow = dbCurrentTimestamp()
        val candidates = NotificationOutboxEvents
            .selectAll()
            .where {
                (NotificationOutboxEvents.rowKind eq NotificationOutboxRowKind.SENDABLE) and
                    (NotificationOutboxEvents.tenantGroupId eq tenantGroupId.value) and
                    (NotificationOutboxEvents.clinicId eq clinicId.value) and
                    (NotificationOutboxEvents.appointmentId eq appointmentId.value) and
                    (NotificationOutboxEvents.notificationSlot inList REMINDER_SLOTS) and
                    (NotificationOutboxEvents.status inList SUPPRESSIBLE_STATUSES)
            }
            .forUpdate()
            .toList()

        return candidates.count { row ->
            val outboxId = row[NotificationOutboxEvents.id].value
            val attemptNumber = row[NotificationOutboxEvents.attemptNumber]
            val owner = row[NotificationOutboxEvents.leaseOwner]
            val token = row[NotificationOutboxEvents.leaseToken]
            val updated = NotificationOutboxEvents.update({
                (NotificationOutboxEvents.id eq outboxId) and
                    (NotificationOutboxEvents.status inList SUPPRESSIBLE_STATUSES)
            }) {
                it[status] = NotificationOutboxStatus.SUPPRESSED
                it[NotificationOutboxEvents.appointmentId] = null
                it[memberId] = null
                it[parametersJson] = null
                it[NotificationOutboxEvents.suppressionReason] = suppressionReason
                it[leaseOwner] = null
                it[leaseToken] = null
                it[leaseUntil] = null
                it[terminalAt] = dbNow
                it[updatedAt] = dbNow
            } == 1
            if (updated && owner != null && token != null && attemptNumber > 0) {
                closeAttempt(
                    outboxId = outboxId,
                    attemptNumber = attemptNumber,
                    owner = owner,
                    token = token,
                    outcome = NotificationDeliveryAttemptOutcome.LEASE_LOST,
                    failureCode = NotificationFailureCode.LEASE_LOST,
                    completedAt = dbNow,
                )
            }
            updated
        }
    }

    override fun findReadyClinicKeys(
        cursor: NotificationFairCursor?,
        limit: Int,
        eligibleScopes: Set<TenantClinicScope>?,
    ): List<NotificationClinicKey> {
        require(limit > 0) { "limit must be positive" }
        eligibleScopes?.let { scopes ->
            require(scopes.all { it.tenantGroupId > 0L && it.clinicId > 0L }) {
                "eligibleScopes must contain only positive IDs"
            }
            if (scopes.isEmpty()) return emptyList()
        }
        val dbNow = dbCurrentTimestamp()
        val ready = readyPredicate(dbNow)
        val cursorPredicate = cursor?.let {
            (NotificationOutboxEvents.tenantGroupId greater it.tenantGroupId.value) or
                ((NotificationOutboxEvents.tenantGroupId eq it.tenantGroupId.value) and
                    (NotificationOutboxEvents.clinicId greater it.clinicId.value))
        }
        val eligibilityPredicate = when {
            eligibleScopes != null -> eligibleScopes
                .map { scope ->
                    (NotificationOutboxEvents.tenantGroupId eq scope.tenantGroupId) and
                        (NotificationOutboxEvents.clinicId eq scope.clinicId)
                }
                .reduce { left, right -> left or right }
            else -> null
        }
        val filteredReady = if (eligibilityPredicate == null) ready else ready and eligibilityPredicate

        return NotificationOutboxEvents
            .select(
                NotificationOutboxEvents.tenantGroupId,
                NotificationOutboxEvents.clinicId,
            )
            .where(if (cursorPredicate == null) filteredReady else filteredReady and cursorPredicate)
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

    /** 기존 concrete repository 호출자의 기본 allowlist 동작을 유지합니다. */
    fun findReadyClinicKeys(
        cursor: NotificationFairCursor?,
        limit: Int,
    ): List<NotificationClinicKey> = findReadyClinicKeys(cursor, limit, null)

    override fun findReadyCandidates(
        key: NotificationClinicKey,
        cursorId: Long?,
        limit: Int,
    ): List<NotificationCandidate> {
        require(limit > 0) { "limit must be positive" }
        cursorId?.let { require(it > 0) { "cursorId must be positive" } }
        val dbNow = dbCurrentTimestamp()
        val cursorPredicate = cursorId?.let { readyCursorPredicate(it) }

        return NotificationOutboxEvents
            .selectAll()
            .where(
                readyPredicate(dbNow) and
                    (NotificationOutboxEvents.tenantGroupId eq key.tenantGroupId.value) and
                    (NotificationOutboxEvents.clinicId eq key.clinicId.value) and
                    (cursorPredicate ?: org.jetbrains.exposed.v1.core.Op.TRUE),
            )
            .orderBy(NotificationOutboxEvents.availableAt to SortOrder.ASC, NotificationOutboxEvents.id to SortOrder.ASC)
            .limit(limit)
            .map { it.toCandidate() }
    }

    /** metric refresh가 읽는 상한 있는 ready backlog snapshot입니다. */
    override fun observeReady(limit: Int): NotificationOutboxObservation {
        require(limit > 0) { "limit must be positive" }
        val dbNow = dbCurrentTimestamp()
        val rows = NotificationOutboxEvents
            .select(NotificationOutboxEvents.availableAt)
            .where { readyPredicate(dbNow) }
            .orderBy(
                NotificationOutboxEvents.availableAt to SortOrder.ASC,
                NotificationOutboxEvents.createdAt to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[NotificationOutboxEvents.availableAt] }
        return NotificationOutboxObservation(
            readyCount = rows.size.toLong(),
            oldestReadyAt = rows.firstOrNull(),
            observedAt = dbNow,
            capped = rows.size == limit,
        )
    }

    /**
     * DB 시각을 기준으로 lease가 만료된 처리 중 행의 식별자를 오래된 순서로 찾는다.
     *
     * 이 조회는 행 상태를 바꾸지 않는다. caller는 같은 짧은 transaction에서 각 식별자를
     * [recoverExpired]로 조건부 복구해야 한다.
     */
    override fun findExpiredProcessingIds(
        limit: Int,
        eligibleScopes: Set<TenantClinicScope>?,
    ): List<Long> {
        require(limit > 0) { "limit must be positive" }
        eligibleScopes?.let { scopes ->
            require(scopes.all { it.tenantGroupId > 0L && it.clinicId > 0L }) {
                "eligibleScopes must contain only positive IDs"
            }
            if (scopes.isEmpty()) return emptyList()
        }
        val dbNow = dbCurrentTimestamp()
        val eligibilityPredicate = eligibleScopes?.map { scope ->
            (NotificationOutboxEvents.tenantGroupId eq scope.tenantGroupId) and
                (NotificationOutboxEvents.clinicId eq scope.clinicId)
        }?.reduce { left, right -> left or right }
        return NotificationOutboxEvents
            .select(NotificationOutboxEvents.id)
            .where {
                (NotificationOutboxEvents.rowKind eq NotificationOutboxRowKind.SENDABLE) and
                    (NotificationOutboxEvents.status eq NotificationOutboxStatus.PROCESSING) and
                    (NotificationOutboxEvents.leaseUntil less dbNow) and
                    (eligibilityPredicate ?: org.jetbrains.exposed.v1.core.Op.TRUE)
            }
            .orderBy(
                NotificationOutboxEvents.leaseUntil to SortOrder.ASC,
                NotificationOutboxEvents.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[NotificationOutboxEvents.id].value }
    }

    /** 기존 concrete repository 호출자의 전체 scope 기본 동작을 유지합니다. */
    fun findExpiredProcessingIds(limit: Int): List<Long> = findExpiredProcessingIds(limit, null)

    override fun claim(candidateId: Long, owner: String, token: String): ClaimedNotification? {
        require(candidateId > 0) { "candidateId must be positive" }
        val validOwner = owner.validFence("owner")
        val validToken = token.validFence("token")
        val dbNow = dbCurrentTimestamp()
        val snapshot = findOutbox(candidateId) ?: return null
        if (!snapshot.isReady(dbNow)) return null
        val oldAttempt = snapshot[NotificationOutboxEvents.attemptNumber]
        val nextAttempt = oldAttempt + 1
        val firstAttemptAt = findFirstAttemptAt(candidateId) ?: dbNow
        val leaseUntil = dbNow.plus(leaseDuration)
        val updated = NotificationOutboxEvents.update({
            readyPredicate(dbNow) and
                (NotificationOutboxEvents.id eq candidateId) and
                (NotificationOutboxEvents.attemptNumber eq oldAttempt)
        }) {
            it[status] = NotificationOutboxStatus.PROCESSING
            it[leaseOwner] = validOwner
            it[leaseToken] = validToken
            it[NotificationOutboxEvents.leaseUntil] = leaseUntil
            it[attemptNumber] = nextAttempt
            it[updatedAt] = dbNow
        }
        if (updated != 1) return null

        insertAttempt(snapshot, nextAttempt, validOwner, validToken, dbNow)
        return snapshot.toClaimed(nextAttempt, validOwner, validToken, leaseUntil, firstAttemptAt, dbNow)
    }

    /**
     * 전환기 event route가 같은 병원·예약·event의 준비된 sendable 행을 조건부 claim합니다.
     *
     * caller는 짧은 transaction 안에서 호출해야 합니다. background worker와 동시에
     * 실행돼도 [claim]의 상태·attempt 조건을 통과한 한 호출자만 행을 획득합니다.
     */
    override fun claimReadyForDirect(
        scope: TenantClinicScope,
        appointmentId: AppointmentId,
        eventType: NotificationEventType,
        owner: String,
        token: String,
    ): ClaimedNotification? {
        val dbNow = dbCurrentTimestamp()
        val candidateId = NotificationOutboxEvents
            .select(NotificationOutboxEvents.id)
            .where {
                readyPredicate(dbNow) and
                    (NotificationOutboxEvents.rowKind eq NotificationOutboxRowKind.SENDABLE) and
                    (NotificationOutboxEvents.tenantGroupId eq scope.tenantGroupId) and
                    (NotificationOutboxEvents.clinicId eq scope.clinicId) and
                    (NotificationOutboxEvents.appointmentId eq appointmentId.value) and
                    (NotificationOutboxEvents.eventType eq eventType)
            }
            .orderBy(
                NotificationOutboxEvents.availableAt to SortOrder.ASC,
                NotificationOutboxEvents.id to SortOrder.ASC,
            )
            .limit(1)
            .singleOrNull()
            ?.get(NotificationOutboxEvents.id)
            ?.value
            ?: return null
        return claim(candidateId, owner, token)
    }

    override fun recoverExpired(candidateId: Long, owner: String, token: String): ClaimedNotification? {
        require(candidateId > 0) { "candidateId must be positive" }
        val validOwner = owner.validFence("owner")
        val validToken = token.validFence("token")
        val dbNow = dbCurrentTimestamp()
        val snapshot = findOutbox(candidateId) ?: return null
        if (!snapshot.isExpiredProcessing(dbNow)) return null
        val oldAttempt = snapshot[NotificationOutboxEvents.attemptNumber]
        val oldOwner = snapshot[NotificationOutboxEvents.leaseOwner] ?: return null
        val oldToken = snapshot[NotificationOutboxEvents.leaseToken] ?: return null
        val nextAttempt = oldAttempt + 1
        val firstAttemptAt = findFirstAttemptAt(candidateId) ?: dbNow
        val leaseUntil = dbNow.plus(leaseDuration)
        val updated = NotificationOutboxEvents.update({
            (NotificationOutboxEvents.id eq candidateId) and
                (NotificationOutboxEvents.rowKind eq NotificationOutboxRowKind.SENDABLE) and
                (NotificationOutboxEvents.status eq NotificationOutboxStatus.PROCESSING) and
                (NotificationOutboxEvents.attemptNumber eq oldAttempt) and
                (NotificationOutboxEvents.leaseOwner eq oldOwner) and
                (NotificationOutboxEvents.leaseToken eq oldToken) and
                (NotificationOutboxEvents.leaseUntil less dbNow)
        }) {
            it[leaseOwner] = validOwner
            it[leaseToken] = validToken
            it[NotificationOutboxEvents.leaseUntil] = leaseUntil
            it[attemptNumber] = nextAttempt
            it[updatedAt] = dbNow
        }
        if (updated != 1) return null

        closeAttempt(
            outboxId = candidateId,
            attemptNumber = oldAttempt,
            owner = oldOwner,
            token = oldToken,
            outcome = NotificationDeliveryAttemptOutcome.LEASE_LOST,
            failureCode = NotificationFailureCode.LEASE_LOST,
            completedAt = dbNow,
        )
        insertAttempt(snapshot, nextAttempt, validOwner, validToken, dbNow)
        return snapshot.toClaimed(nextAttempt, validOwner, validToken, leaseUntil, firstAttemptAt, dbNow)
    }

    override fun complete(command: CompleteNotificationCommand): Boolean {
        command.validate()
        val dbNow = dbCurrentTimestamp()
        val updated = NotificationOutboxEvents.update({ fencedProcessingPredicate(command, dbNow) }) {
            it[status] = command.terminalStatus
            it[appointmentId] = null
            it[memberId] = null
            it[parametersJson] = null
            it[failureCode] = command.failureCode
            it[suppressionReason] = command.suppressionReason
            it[providerMessageReference] = command.providerMessageReference?.value
            it[destinationFingerprint] = command.destinationFingerprint?.value
            it[correlationId] = command.correlationId?.value
            it[traceId] = command.traceId?.value
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[terminalAt] = dbNow
            it[updatedAt] = dbNow
        }
        if (updated != 1) {
            closeLostFence(command, dbNow)
            return false
        }

        closeAttempt(
            outboxId = command.outboxId,
            attemptNumber = command.attemptNumber,
            owner = command.owner,
            token = command.token,
            outcome = command.terminalStatus.toAttemptOutcome(),
            failureCode = command.failureCode,
            completedAt = dbNow,
            providerMessageReference = command.providerMessageReference?.value,
            destinationFingerprint = command.destinationFingerprint?.value,
            correlationId = command.correlationId?.value,
            traceId = command.traceId?.value,
            requireExactlyOne = true,
        )
        return true
    }

    override fun scheduleRetry(command: RetryNotificationCommand): Boolean {
        command.validate()
        val dbNow = dbCurrentTimestamp()
        val updated = NotificationOutboxEvents.update({ fencedProcessingPredicate(command, dbNow) }) {
            it[status] = NotificationOutboxStatus.RETRY_WAIT
            it[leaseOwner] = null
            it[leaseToken] = null
            it[leaseUntil] = null
            it[nextRetryAt] = dbNow.plus(command.retryDelay)
            it[updatedAt] = dbNow
        }
        if (updated != 1) {
            closeLostFence(command, dbNow)
            return false
        }

        closeAttempt(
            outboxId = command.outboxId,
            attemptNumber = command.attemptNumber,
            owner = command.owner,
            token = command.token,
            outcome = NotificationDeliveryAttemptOutcome.RETRY_SCHEDULED,
            failureCode = command.failureCode,
            completedAt = dbNow,
            providerMessageReference = command.providerMessageReference?.value,
            destinationFingerprint = command.destinationFingerprint?.value,
            correlationId = command.correlationId?.value,
            traceId = command.traceId?.value,
            requireExactlyOne = true,
        )
        return true
    }

    /**
     * 보존 기간이 지난 종료 행을 오래된 순서로 제한된 개수만 삭제한다.
     *
     * attempt 외래 키가 outbox 삭제를 제한하므로 같은 caller transaction에서 attempt를
     * 먼저 삭제한다. cutoff는 애플리케이션 시각이 아니라 DB 현재 시각으로 계산한다.
     */
    override fun deleteTerminalBatch(
        status: NotificationOutboxStatus,
        retention: Duration,
        limit: Int,
    ): Int {
        require(status in TERMINAL_STATUSES) { "status must be SENT, SUPPRESSED, or EXHAUSTED" }
        require(!retention.isNegative && !retention.isZero) { "retention must be positive" }
        require(limit > 0) { "limit must be positive" }
        val cutoff = dbCurrentTimestamp().minus(retention)
        val ids = NotificationOutboxEvents
            .select(NotificationOutboxEvents.id)
            .where {
                (NotificationOutboxEvents.rowKind inList TERMINAL_ROW_KINDS) and
                    (NotificationOutboxEvents.status eq status) and
                    (NotificationOutboxEvents.terminalAt lessEq cutoff)
            }
            .orderBy(
                NotificationOutboxEvents.terminalAt to SortOrder.ASC,
                NotificationOutboxEvents.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[NotificationOutboxEvents.id] }
        if (ids.isEmpty()) return 0

        NotificationDeliveryAttempts.deleteWhere {
            NotificationDeliveryAttempts.outboxId inList ids
        }
        return NotificationOutboxEvents.deleteWhere {
            NotificationOutboxEvents.id inList ids
        }
    }

    private fun upsertSendable(draft: SendableNotificationDraft) {
        val envelope = draft.envelope
        val dbNow = dbCurrentTimestamp()
        NotificationOutboxEvents.upsert(
            *idempotencyUpsertKeys(),
            onUpdate = { it[NotificationOutboxEvents.idempotencyKey] = draft.idempotencyDigest.value },
        ) {
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
            it[failureCode] = null
            it[providerMessageReference] = null
            it[destinationFingerprint] = null
            it[correlationId] = null
            it[traceId] = null
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
    }

    private fun upsertSuppression(draft: LegacySuppressionDraft) {
        val dbNow = dbCurrentTimestamp()
        NotificationOutboxEvents.upsert(
            *idempotencyUpsertKeys(),
            onUpdate = { it[NotificationOutboxEvents.idempotencyKey] = draft.idempotencyDigest.value },
        ) {
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
            it[failureCode] = null
            it[providerMessageReference] = null
            it[destinationFingerprint] = null
            it[correlationId] = null
            it[traceId] = null
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
    }

    private fun idempotencyUpsertKeys(): Array<Column<*>> =
        arrayOf(NotificationOutboxEvents.idempotencyKeyVersion, NotificationOutboxEvents.idempotencyKey)

    private fun insertAttempt(
        row: ResultRow,
        attemptNumber: Int,
        owner: String,
        token: String,
        dbNow: Instant,
    ) {
        val outboxId = row[NotificationOutboxEvents.id].value
        NotificationDeliveryAttempts.insertAndGetId {
            it[NotificationDeliveryAttempts.outboxId] = EntityID(outboxId, NotificationOutboxEvents)
            it[NotificationDeliveryAttempts.attemptNumber] = attemptNumber
            it[NotificationDeliveryAttempts.owner] = owner
            it[NotificationDeliveryAttempts.token] = token
            it[channel] = row[NotificationOutboxEvents.channel] ?: error("sendable notification outbox row must have channel")
            it[eventType] = row[NotificationOutboxEvents.eventType] ?: error("sendable notification outbox row must have eventType")
            it[templateKey] = row[NotificationOutboxEvents.templateKey]
                ?: error("sendable notification outbox row must have templateKey")
            it[templateVersion] = row[NotificationOutboxEvents.templateVersion]
                ?: error("sendable notification outbox row must have templateVersion")
            it[startedAt] = dbNow
            it[completedAt] = null
            it[durationMillis] = null
            it[outcome] = null
            it[failureCode] = null
            it[providerMessageReference] = null
            it[destinationFingerprint] = null
            it[correlationId] = null
            it[traceId] = null
        }
    }

    private fun closeAttempt(
        outboxId: Long,
        attemptNumber: Int,
        owner: String,
        token: String,
        outcome: NotificationDeliveryAttemptOutcome,
        failureCode: NotificationFailureCode?,
        completedAt: Instant,
        providerMessageReference: String? = null,
        destinationFingerprint: String? = null,
        correlationId: String? = null,
        traceId: String? = null,
        requireExactlyOne: Boolean = false,
    ) {
        val attempt = NotificationDeliveryAttempts.selectAll()
            .where {
                (NotificationDeliveryAttempts.outboxId eq EntityID(outboxId, NotificationOutboxEvents)) and
                    (NotificationDeliveryAttempts.attemptNumber eq attemptNumber) and
                    (NotificationDeliveryAttempts.owner eq owner) and
                    (NotificationDeliveryAttempts.token eq token) and
                    NotificationDeliveryAttempts.completedAt.isNull()
            }
            .singleOrNull()
        val startedAt = attempt?.get(NotificationDeliveryAttempts.startedAt)
        if (startedAt == null) {
            if (requireExactlyOne) {
                error("notification delivery attempt close must affect exactly one row")
            }
            return
        }
        val updated = NotificationDeliveryAttempts.update({
            (NotificationDeliveryAttempts.outboxId eq EntityID(outboxId, NotificationOutboxEvents)) and
                (NotificationDeliveryAttempts.attemptNumber eq attemptNumber) and
                (NotificationDeliveryAttempts.owner eq owner) and
                (NotificationDeliveryAttempts.token eq token) and
                NotificationDeliveryAttempts.completedAt.isNull()
        }) {
            it[NotificationDeliveryAttempts.completedAt] = completedAt
            it[NotificationDeliveryAttempts.durationMillis] = Duration.between(startedAt, completedAt).toMillis().coerceAtLeast(0L)
            it[NotificationDeliveryAttempts.outcome] = outcome
            it[NotificationDeliveryAttempts.failureCode] = failureCode?.name
            it[NotificationDeliveryAttempts.providerMessageReference] = providerMessageReference
            it[NotificationDeliveryAttempts.destinationFingerprint] = destinationFingerprint
            it[NotificationDeliveryAttempts.correlationId] = correlationId
            it[NotificationDeliveryAttempts.traceId] = traceId
        }
        if (requireExactlyOne && updated != 1) {
            error("notification delivery attempt close must affect exactly one row")
        }
    }

    private fun closeLostFence(command: NotificationFenceCommand, dbNow: Instant) {
        closeAttempt(
            outboxId = command.outboxId,
            attemptNumber = command.attemptNumber,
            owner = command.owner,
            token = command.token,
            outcome = NotificationDeliveryAttemptOutcome.LEASE_LOST,
            failureCode = NotificationFailureCode.LEASE_LOST,
            completedAt = dbNow,
        )
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

    private fun findOutbox(id: Long): ResultRow? =
        NotificationOutboxEvents
            .selectAll()
            .where { NotificationOutboxEvents.id eq id }
            .singleOrNull()

    private fun findFirstAttemptAt(outboxId: Long): Instant? =
        NotificationDeliveryAttempts
            .select(NotificationDeliveryAttempts.startedAt)
            .where {
                NotificationDeliveryAttempts.outboxId eq EntityID(outboxId, NotificationOutboxEvents)
            }
            .orderBy(NotificationDeliveryAttempts.startedAt to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.get(NotificationDeliveryAttempts.startedAt)

    private fun readyCursorPredicate(cursorId: Long) =
        findOutbox(cursorId)?.let { cursor ->
            val cursorAvailableAt = cursor[NotificationOutboxEvents.availableAt]
            (NotificationOutboxEvents.availableAt greater cursorAvailableAt) or
                ((NotificationOutboxEvents.availableAt eq cursorAvailableAt) and (NotificationOutboxEvents.id greater cursorId))
        } ?: org.jetbrains.exposed.v1.core.Op.TRUE

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
        firstAttemptAt: Instant,
        claimedAt: Instant,
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
            idempotencyKey = NotificationIdempotencyKey(this[NotificationOutboxEvents.idempotencyKey]),
            owner = owner,
            token = token,
            attemptNumber = attemptNumber,
            leaseUntil = leaseUntil,
            firstAttemptAt = firstAttemptAt,
            claimedAt = claimedAt,
            channel = checkNotNull(this[NotificationOutboxEvents.channel]) {
                "sendable notification outbox row must have channel"
            },
            eventType = checkNotNull(this[NotificationOutboxEvents.eventType]) {
                "sendable notification outbox row must have eventType"
            },
            notificationSlot = checkNotNull(this[NotificationOutboxEvents.notificationSlot]) {
                "sendable notification outbox row must have notificationSlot"
            },
            providerKey = checkNotNull(this[NotificationOutboxEvents.providerKey]) {
                "sendable notification outbox row must have providerKey"
            },
            templateKey = NotificationTemplateKey(
                checkNotNull(this[NotificationOutboxEvents.templateKey]) {
                    "sendable notification outbox row must have templateKey"
                }
            ),
            templateVersion = NotificationTemplateVersion(
                checkNotNull(this[NotificationOutboxEvents.templateVersion]) {
                    "sendable notification outbox row must have templateVersion"
                }
            ),
            parameterType = checkNotNull(this[NotificationOutboxEvents.parameterType]) {
                "sendable notification outbox row must have parameterType"
            },
            eventId = NotificationEventId(this[NotificationOutboxEvents.eventId]),
            parametersJson = parametersJson,
        )
    }

    private fun dbCurrentTimestamp(): Instant =
        TransactionManager.current().dbCurrentTimestamp()

    companion object {
        private val READY_STATUSES = listOf(NotificationOutboxStatus.PENDING, NotificationOutboxStatus.RETRY_WAIT)
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

/** 운영 gauge가 사용하는 상한 있는 ready outbox 관측값입니다. */
data class NotificationOutboxObservation(
    val readyCount: Long,
    val oldestReadyAt: Instant?,
    val observedAt: Instant,
    val capped: Boolean,
) : Serializable {
    init {
        require(readyCount >= 0L) { "readyCount must be non-negative" }
    }

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
    val idempotencyKey: NotificationIdempotencyKey,
    val owner: String,
    val token: String,
    val attemptNumber: Int,
    val leaseUntil: Instant,
    val firstAttemptAt: Instant,
    val claimedAt: Instant,
    val channel: NotificationChannelType,
    val eventType: NotificationEventType,
    val notificationSlot: NotificationSlot,
    val providerKey: String,
    val templateKey: NotificationTemplateKey,
    val templateVersion: NotificationTemplateVersion,
    val parameterType: NotificationParameterType,
    val eventId: NotificationEventId,
    val parametersJson: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/** 저장된 outbox row의 privacy-safe projection이다. */
data class NotificationOutboxRecord(
    override val id: Long,
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
) : NotificationOutboxWriteReceipt(id), Serializable {
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
    val terminalStatus: NotificationOutboxStatus = NotificationOutboxStatus.SENT,
    val failureCode: NotificationFailureCode? = null,
    val suppressionReason: NotificationSuppressionReasonCode? = null,
    val providerMessageReference: NotificationProviderMessageReference? = null,
    val destinationFingerprint: NotificationDestinationFingerprint? = null,
    val correlationId: NotificationCorrelationId? = null,
    val traceId: NotificationTraceId? = null,
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
    val retryDelay: Duration,
    val providerMessageReference: NotificationProviderMessageReference? = null,
    val destinationFingerprint: NotificationDestinationFingerprint? = null,
    val correlationId: NotificationCorrelationId? = null,
    val traceId: NotificationTraceId? = null,
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
    when (this) {
        is CompleteNotificationCommand -> {
            require(terminalStatus in TERMINAL_STATUSES) { "terminalStatus must be SENT, SUPPRESSED, or EXHAUSTED" }
            if (terminalStatus == NotificationOutboxStatus.SENT) {
                require(failureCode == null) { "failureCode must be null for SENT" }
                require(suppressionReason == null) { "suppressionReason must be null for SENT" }
            }
            if (terminalStatus == NotificationOutboxStatus.SUPPRESSED) {
                require(suppressionReason != null) { "suppressionReason is required for SUPPRESSED" }
            }
            if (terminalStatus == NotificationOutboxStatus.EXHAUSTED) {
                require(failureCode != null) { "failureCode is required for EXHAUSTED" }
            }
            validateOptionalMetadata()
        }

        is RetryNotificationCommand -> validateOptionalMetadata()
    }
}

private fun CompleteNotificationCommand.validateOptionalMetadata() {
    providerMessageReference?.value?.validFence("providerMessageReference")
    destinationFingerprint?.value?.validFence("destinationFingerprint")
    correlationId?.value?.validFence("correlationId")
    traceId?.value?.validFence("traceId")
}

private fun RetryNotificationCommand.validateOptionalMetadata() {
    require(!retryDelay.isNegative && !retryDelay.isZero) { "retryDelay must be positive" }
    require(retryDelay <= Duration.ofHours(72)) { "retryDelay must not exceed 72 hours" }
    providerMessageReference?.value?.validFence("providerMessageReference")
    destinationFingerprint?.value?.validFence("destinationFingerprint")
    correlationId?.value?.validFence("correlationId")
    traceId?.value?.validFence("traceId")
}

private fun NotificationOutboxStatus.toAttemptOutcome(): NotificationDeliveryAttemptOutcome =
    when (this) {
        NotificationOutboxStatus.SENT -> NotificationDeliveryAttemptOutcome.SUCCESS
        NotificationOutboxStatus.SUPPRESSED -> NotificationDeliveryAttemptOutcome.SUPPRESSED
        NotificationOutboxStatus.EXHAUSTED -> NotificationDeliveryAttemptOutcome.EXHAUSTED
        NotificationOutboxStatus.PENDING,
        NotificationOutboxStatus.PROCESSING,
        NotificationOutboxStatus.RETRY_WAIT,
        -> error("status $this is not terminal")
    }

private val TERMINAL_STATUSES = setOf(
    NotificationOutboxStatus.SENT,
    NotificationOutboxStatus.SUPPRESSED,
    NotificationOutboxStatus.EXHAUSTED,
)

private val TERMINAL_ROW_KINDS = listOf(
    NotificationOutboxRowKind.SENDABLE,
    NotificationOutboxRowKind.LEGACY_SUPPRESSION,
)

private val REMINDER_SLOTS = listOf(
    NotificationSlot.REMINDER_24H,
    NotificationSlot.REMINDER_SAME_DAY,
)

private val SUPPRESSIBLE_STATUSES = listOf(
    NotificationOutboxStatus.PENDING,
    NotificationOutboxStatus.PROCESSING,
    NotificationOutboxStatus.RETRY_WAIT,
)

private fun String.validFence(fieldName: String): String =
    validateDurableOpaqueString(this, fieldName, 128)

private fun validateDurableOpaqueString(
    value: String,
    fieldName: String,
    maxLength: Int,
): String {
    require(value.isNotBlank()) { "$fieldName must not be blank" }
    require(value.length <= maxLength) { "$fieldName must not exceed $maxLength characters" }
    require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
    return value
}

private fun JdbcTransaction.dbCurrentTimestamp(): Instant =
    exec("SELECT CURRENT_TIMESTAMP") { resultSet ->
        if (!resultSet.next()) error("SELECT CURRENT_TIMESTAMP returned no rows")
        resultSet.getObject(1).toNotificationDbInstant()
    } ?: error("SELECT CURRENT_TIMESTAMP returned no result set")

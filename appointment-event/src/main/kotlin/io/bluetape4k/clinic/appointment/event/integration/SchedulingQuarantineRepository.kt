package io.bluetape4k.clinic.appointment.event.integration

import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import java.util.Base64

/**
 * 거부된 inbound 일정 event의 격리 metadata와 암호화 원문을 저장합니다.
 *
 * [resolvedAt]은 release 승인·거절 또는 payload 만료처럼 운영상 해결된 시각만 기록합니다.
 * 기존 row를 추정 backfill하지 않으므로 `null`은 아직 해결 시각이 없다는 뜻입니다.
 */
object SchedulingQuarantineEvents : LongIdTable("scheduling_quarantine_events") {
    val eventId = varchar("event_id", 128).uniqueIndex("uq_quarantine_event_id")
    val eventType = varchar("event_type", 128)
    val envelopeHash = varchar("envelope_hash", 64)
    val encryptedOriginalEnvelope = text("encrypted_original_envelope").nullable()
    val encryptionKeyId = varchar("encryption_key_id", 128)
    val producer = varchar("producer", 128)
    val sourceAuthority = varchar("source_authority", 128)
    val schemaVersion = integer("schema_version")
    val sourceAggregateId = varchar("source_aggregate_id", 128)
    val sourceAggregateVersion = long("source_aggregate_version")
    val tenantGroupId = reference("tenant_group_id", TenantGroups, onDelete = ReferenceOption.RESTRICT)
    val clinicId = reference("clinic_id", Clinics, onDelete = ReferenceOption.RESTRICT)
    val reasonCode = varchar("reason_code", 128)
    val detectedAt = timestamp("detected_at")
    val resolvedAt = timestamp("resolved_at").nullable()
    val correlationId = varchar("correlation_id", 128)
    val retentionClass = enumerationByName<QuarantineRetentionClass>("retention_class", 32)
    val payloadExpiresAt = timestamp("payload_expires_at")
    val legalHold = bool("legal_hold").default(false)
    val status = enumerationByName<QuarantineStatus>("status", 32)

    init {
        index("idx_quarantine_status_expiry", false, legalHold, status, payloadExpiresAt, id)
        index("idx_quarantine_resolved_retention", false, tenantGroupId, clinicId, legalHold, resolvedAt, id, status)
        index("idx_quarantine_scope_reason", false, tenantGroupId, clinicId, reasonCode, detectedAt)
    }
}

/**
 * Append-only privileged activity log for quarantine inspection, redrive, release, and retention actions.
 */
object SchedulingQuarantineAuditEvents : LongIdTable("scheduling_quarantine_audit_events") {
    val quarantineId = reference("quarantine_id", SchedulingQuarantineEvents, onDelete = ReferenceOption.RESTRICT)
    val action = enumerationByName<QuarantineAuditAction>("action", 32)
    val actor = varchar("actor", 128)
    val reason = varchar("reason", 256)
    val dryRunDiffHash = varchar("dry_run_diff_hash", 128).nullable()
    val beforeStatus = enumerationByName<QuarantineStatus>("before_status", 32).nullable()
    val afterStatus = enumerationByName<QuarantineStatus>("after_status", 32).nullable()
    val approvalReferences = varchar("approval_references", 512).nullable()
    val createdAt = timestamp("created_at")

    init {
        index("idx_quarantine_audit_quarantine_created", false, quarantineId, createdAt)
    }
}

enum class QuarantineRetentionClass {
    STANDARD,
    EXTENDED,
}

enum class QuarantineStatus {
    OPEN,
    RELEASE_DENIED,
    RELEASE_APPROVED,
    PAYLOAD_EXPIRED,
}

/**
 * 격리 row에 append-only로 남기는 운영 행위입니다.
 *
 * [REDRIVE_ATTEMPT]는 handler 실행 전 의도이고 [REDRIVE]는 handler와 성공 audit이 모두
 * 끝난 상태입니다. [REDRIVE_FAILED]는 handler 자체 실패에만 사용하며, 성공 audit 저장
 * 실패는 `REDRIVE_ATTEMPT`로 남겨 reconciliation 대상으로 구분합니다.
 */
enum class QuarantineAuditAction {
    DETECTED,
    INSPECTED,
    DRY_RUN,
    REDRIVE_ATTEMPT,
    REDRIVE,
    REDRIVE_FAILED,
    RELEASE_DENIED,
    RELEASE_APPROVED,
    PAYLOAD_EXPIRED,
    LEGAL_HOLD_ENABLED,
    LEGAL_HOLD_DISABLED,
}

data class QuarantineDetection(
    val eventId: String,
    val eventType: String,
    val protectedEnvelope: ProtectedQuarantineEnvelope,
    val producer: String,
    val sourceAuthority: String,
    val schemaVersion: Int,
    val sourceAggregateId: String,
    val sourceAggregateVersion: Long,
    val tenantGroupId: Long,
    val clinicId: Long,
    val reasonCode: String,
    val detectedAt: Instant,
    val correlationId: String,
    val retentionClass: QuarantineRetentionClass,
    val payloadExpiresAt: Instant,
) : Serializable

/**
 * 격리 상세 조회에 반환하는 최소 운영 projection입니다.
 *
 * @property resolvedAt release/retention lifecycle이 해결된 UTC 시각입니다. 아직 open이거나
 * V11 이전 미해결 row이면 `null`입니다.
 */
data class QuarantineRecord(
    val id: Long,
    val eventId: String,
    val envelopeHash: String,
    val encryptedOriginalEnvelope: String?,
    val encryptionKeyId: String,
    val reasonCode: String,
    val resolvedAt: Instant?,
    val payloadExpiresAt: Instant,
    val legalHold: Boolean,
    val status: QuarantineStatus,
) : Serializable

data class QuarantineAuditRecord(
    val id: Long,
    val quarantineId: Long,
    val action: QuarantineAuditAction,
    val actor: String,
    val reason: String,
    val dryRunDiffHash: String?,
    val beforeStatus: QuarantineStatus?,
    val afterStatus: QuarantineStatus?,
    val approvalReferences: String?,
    val createdAt: Instant,
) : Serializable

data class QuarantineReleaseEvidence(
    val approvalReferences: List<String>,
    val sourceCorrectionReference: String? = null,
    val trustRevalidated: Boolean = false,
) : Serializable

fun interface QuarantineExpiryObserver {
    /**
     * 만료 후보 조회 뒤 조건부 payload 만료 update 전에 실행되는 transaction-local
     * 테스트·진단 hook입니다.
     */
    fun beforeExpireCandidate(quarantineId: Long)

    companion object {
        val NOOP = QuarantineExpiryObserver { }
    }
}

fun interface QuarantineTransitionObserver {
    /**
     * 전이 snapshot 조회 뒤 compare-and-set update 전에 실행되는 transaction-local
     * 테스트·진단 hook입니다.
     */
    fun beforeTransition(quarantineId: Long, nextStatus: QuarantineStatus)

    companion object {
        val NOOP = QuarantineTransitionObserver { _, _ -> }
    }
}

/**
 * 격리 row와 append-only audit row를 caller transaction 안에서 조작합니다.
 *
 * 이 저장소는 transaction을 열지 않습니다. 상태 전이는 읽은 snapshot과 현재 상태를
 * compare-and-set으로 비교하며, redrive는 정확한 event ID·envelope hash·승인 참조를
 * 다시 확인합니다.
 */
class SchedulingQuarantineRepository(
    private val clock: Clock = Clock.systemUTC(),
    private val expiryObserver: QuarantineExpiryObserver = QuarantineExpiryObserver.NOOP,
    private val transitionObserver: QuarantineTransitionObserver = QuarantineTransitionObserver.NOOP,
) {

    fun recordDetected(detection: QuarantineDetection): QuarantineRecord {
        validateDetection(detection)
        val quarantineId = SchedulingQuarantineEvents.insertAndGetId {
            it[eventId] = detection.eventId
            it[eventType] = detection.eventType
            it[envelopeHash] = detection.protectedEnvelope.envelopeHash
            it[encryptedOriginalEnvelope] = detection.protectedEnvelope.ciphertext
            it[encryptionKeyId] = detection.protectedEnvelope.keyId
            it[producer] = detection.producer
            it[sourceAuthority] = detection.sourceAuthority
            it[schemaVersion] = detection.schemaVersion
            it[sourceAggregateId] = detection.sourceAggregateId
            it[sourceAggregateVersion] = detection.sourceAggregateVersion
            it[tenantGroupId] = detection.tenantGroupId
            it[clinicId] = detection.clinicId
            it[reasonCode] = detection.reasonCode
            it[detectedAt] = detection.detectedAt
            it[resolvedAt] = null
            it[correlationId] = detection.correlationId
            it[retentionClass] = detection.retentionClass
            it[payloadExpiresAt] = detection.payloadExpiresAt
            it[legalHold] = false
            it[status] = QuarantineStatus.OPEN
        }.value
        appendAudit(
            quarantineId = quarantineId,
            action = QuarantineAuditAction.DETECTED,
            actor = "system",
            reason = detection.reasonCode,
            beforeStatus = null,
            afterStatus = QuarantineStatus.OPEN,
            createdAt = detection.detectedAt,
        )
        return requireNotNull(findById(quarantineId)) {
            "inserted quarantine record could not be read back: $quarantineId"
        }
    }

    fun findById(id: Long): QuarantineRecord? =
        SchedulingQuarantineEvents
            .selectAll()
            .where { SchedulingQuarantineEvents.id eq id }
            .singleOrNull()
            ?.let {
                QuarantineRecord(
                    id = it[SchedulingQuarantineEvents.id].value,
                    eventId = it[SchedulingQuarantineEvents.eventId],
                    envelopeHash = it[SchedulingQuarantineEvents.envelopeHash],
                    encryptedOriginalEnvelope = it[SchedulingQuarantineEvents.encryptedOriginalEnvelope],
                    encryptionKeyId = it[SchedulingQuarantineEvents.encryptionKeyId],
                    reasonCode = it[SchedulingQuarantineEvents.reasonCode],
                    resolvedAt = it[SchedulingQuarantineEvents.resolvedAt],
                    payloadExpiresAt = it[SchedulingQuarantineEvents.payloadExpiresAt],
                    legalHold = it[SchedulingQuarantineEvents.legalHold],
                    status = it[SchedulingQuarantineEvents.status],
                )
            }

    /**
     * 같은 broker event가 이미 종결 격리되었는지 확인합니다.
     *
     * 외부 fact consumer는 inbox를 만들기 전 trust/decode 단계에서도 실패할 수 있으므로
     * quarantine 자체의 unique event id를 idempotency 기준으로 사용합니다.
     */
    fun findByEventId(eventId: String): QuarantineRecord? {
        validateIdentifier(eventId, "eventId")
        return SchedulingQuarantineEvents
            .selectAll()
            .where { SchedulingQuarantineEvents.eventId eq eventId }
            .singleOrNull()
            ?.let {
                QuarantineRecord(
                    id = it[SchedulingQuarantineEvents.id].value,
                    eventId = it[SchedulingQuarantineEvents.eventId],
                    envelopeHash = it[SchedulingQuarantineEvents.envelopeHash],
                    encryptedOriginalEnvelope = it[SchedulingQuarantineEvents.encryptedOriginalEnvelope],
                    encryptionKeyId = it[SchedulingQuarantineEvents.encryptionKeyId],
                    reasonCode = it[SchedulingQuarantineEvents.reasonCode],
                    resolvedAt = it[SchedulingQuarantineEvents.resolvedAt],
                    payloadExpiresAt = it[SchedulingQuarantineEvents.payloadExpiresAt],
                    legalHold = it[SchedulingQuarantineEvents.legalHold],
                    status = it[SchedulingQuarantineEvents.status],
                )
            }
    }

    fun auditTrail(quarantineId: Long): List<QuarantineAuditRecord> =
        SchedulingQuarantineAuditEvents
            .selectAll()
            .where { SchedulingQuarantineAuditEvents.quarantineId eq quarantineId }
            .orderBy(SchedulingQuarantineAuditEvents.id)
            .map {
                QuarantineAuditRecord(
                    id = it[SchedulingQuarantineAuditEvents.id].value,
                    quarantineId = it[SchedulingQuarantineAuditEvents.quarantineId].value,
                    action = it[SchedulingQuarantineAuditEvents.action],
                    actor = it[SchedulingQuarantineAuditEvents.actor],
                    reason = it[SchedulingQuarantineAuditEvents.reason],
                    dryRunDiffHash = it[SchedulingQuarantineAuditEvents.dryRunDiffHash],
                    beforeStatus = it[SchedulingQuarantineAuditEvents.beforeStatus],
                    afterStatus = it[SchedulingQuarantineAuditEvents.afterStatus],
                    approvalReferences = it[SchedulingQuarantineAuditEvents.approvalReferences],
                    createdAt = it[SchedulingQuarantineAuditEvents.createdAt],
                )
            }

    fun recordInspection(quarantineId: Long, actor: String, reason: String) {
        val current = requireRecord(quarantineId)
        appendAudit(quarantineId, QuarantineAuditAction.INSPECTED, actor, reason, current.status, current.status)
    }

    fun recordDryRun(
        quarantineId: Long,
        expectedEventId: String,
        actor: String,
        reason: String,
        dryRunDiffHash: String,
    ) {
        val current = requireRecord(quarantineId)
        require(current.eventId == expectedEventId) { "quarantine eventId does not match dry-run confirmation" }
        require(current.status in payloadRetainedStatuses && current.encryptedOriginalEnvelope != null) {
            "dry-run requires a retained quarantine payload"
        }
        validateIdentifier(dryRunDiffHash, "dryRunDiffHash")
        appendAudit(
            quarantineId = quarantineId,
            action = QuarantineAuditAction.DRY_RUN,
            actor = actor,
            reason = reason,
            beforeStatus = current.status,
            afterStatus = current.status,
            dryRunDiffHash = dryRunDiffHash,
        )
    }

    /**
     * 승인된 한 격리 envelope에 대한 redrive 시도를 실행 전에 기록합니다.
     *
     * [expectedEnvelopeHash]까지 영속 격리 row와 일치해야 하므로 event ID와 상품 식별자가
     * 같더라도 다른 환자·선호 일정 payload를 재처리할 수 없습니다. 이 audit은 실행
     * 성공을 뜻하지 않으며, 호출자는 handler 종료 뒤 반드시 [recordRedriveSucceeded]
     * 또는 [recordRedriveFailed]를 별도 transaction에서 기록해야 합니다.
     */
    fun recordRedriveAttempt(
        quarantineId: Long,
        expectedEventId: String,
        expectedEnvelopeHash: String,
        actor: String,
        reason: String,
        approvalReferences: List<String>,
    ) {
        val current = requireRecord(quarantineId)
        require(current.eventId == expectedEventId) { "quarantine eventId does not match redrive confirmation" }
        require(expectedEnvelopeHash.matches(sha256)) { "expectedEnvelopeHash must be lowercase SHA-256" }
        require(current.envelopeHash == expectedEnvelopeHash) {
            "quarantine envelopeHash does not match redrive confirmation"
        }
        require(current.status == QuarantineStatus.RELEASE_APPROVED) {
            "redrive requires RELEASE_APPROVED quarantine status"
        }
        require(current.encryptedOriginalEnvelope != null) { "redrive payload is no longer available" }
        require(approvalReferences.isNotEmpty()) { "redrive requires at least one approval reference" }
        approvalReferences.forEach { validateIdentifier(it, "approvalReference") }
        val approvedReferences = auditTrail(quarantineId)
            .lastOrNull { it.action == QuarantineAuditAction.RELEASE_APPROVED }
            ?.approvalReferences
            ?.split(",")
            ?.toSet()
            .orEmpty()
        require(approvalReferences.toSet().all { it in approvedReferences }) {
            "redrive approval references do not match the recorded release approval"
        }
        appendAudit(
            quarantineId = quarantineId,
            action = QuarantineAuditAction.REDRIVE_ATTEMPT,
            actor = actor,
            reason = reason,
            beforeStatus = current.status,
            afterStatus = current.status,
            approvalReferences = approvalReferences.distinct().joinToString(","),
        )
    }

    /**
     * handler가 정상 종료한 뒤에만 redrive 성공을 append-only audit에 기록합니다.
     */
    fun recordRedriveSucceeded(
        quarantineId: Long,
        expectedEventId: String,
        expectedEnvelopeHash: String,
        actor: String,
        reason: String,
        approvalReferences: List<String>,
    ) {
        appendRedriveOutcome(
            quarantineId = quarantineId,
            expectedEventId = expectedEventId,
            expectedEnvelopeHash = expectedEnvelopeHash,
            actor = actor,
            reason = reason,
            approvalReferences = approvalReferences,
            action = QuarantineAuditAction.REDRIVE,
        )
    }

    /**
     * handler 예외 뒤 실패 사실만 기록합니다. 원 예외 message나 payload는 audit에
     * 복제하지 않아 개인정보·운영 내부정보가 reason column으로 유출되지 않습니다.
     */
    fun recordRedriveFailed(
        quarantineId: Long,
        expectedEventId: String,
        expectedEnvelopeHash: String,
        actor: String,
        approvalReferences: List<String>,
    ) {
        appendRedriveOutcome(
            quarantineId = quarantineId,
            expectedEventId = expectedEventId,
            expectedEnvelopeHash = expectedEnvelopeHash,
            actor = actor,
            reason = "redrive handler failed",
            approvalReferences = approvalReferences,
            action = QuarantineAuditAction.REDRIVE_FAILED,
        )
    }

    fun denyRelease(quarantineId: Long, actor: String, reason: String) {
        val current = requireRecord(quarantineId)
        require(current.status != QuarantineStatus.PAYLOAD_EXPIRED) {
            "payload-expired quarantine cannot change release status"
        }
        transition(quarantineId, QuarantineStatus.RELEASE_DENIED, QuarantineAuditAction.RELEASE_DENIED, actor, reason)
    }

    fun approveRelease(
        quarantineId: Long,
        actor: String,
        reason: String,
        evidence: QuarantineReleaseEvidence,
    ) {
        val current = requireRecord(quarantineId)
        require(current.status in setOf(QuarantineStatus.OPEN, QuarantineStatus.RELEASE_DENIED)) {
            "release approval requires OPEN or RELEASE_DENIED quarantine status"
        }
        require(current.encryptedOriginalEnvelope != null) {
            "release approval requires a retained quarantine payload"
        }
        val requiredApprovals = if (current.reasonCode in dualApprovalReasonCodes) 2 else 1
        require(evidence.approvalReferences.distinct().size >= requiredApprovals) {
            "release requires at least $requiredApprovals distinct approval reference(s)"
        }
        if (current.reasonCode in trustFailureReasonCodes) {
            require(evidence.trustRevalidated) {
                "trust-failed quarantine release requires trust revalidation"
            }
            val sourceCorrectionReference = requireNotNull(evidence.sourceCorrectionReference) {
                "trust-failed quarantine release requires a source correction reference"
            }
            validateIdentifier(sourceCorrectionReference, "sourceCorrectionReference")
        }
        evidence.approvalReferences.forEach { validateIdentifier(it, "approvalReference") }
        transition(
            quarantineId = quarantineId,
            nextStatus = QuarantineStatus.RELEASE_APPROVED,
            action = QuarantineAuditAction.RELEASE_APPROVED,
            actor = actor,
            reason = reason,
            approvalReferences = buildList {
                addAll(evidence.approvalReferences)
                evidence.sourceCorrectionReference?.let(::add)
            }.joinToString(","),
        )
    }

    fun setLegalHold(quarantineId: Long, enabled: Boolean, actor: String, reason: String) {
        val current = requireRecord(quarantineId)
        if (enabled) {
            require(current.status != QuarantineStatus.PAYLOAD_EXPIRED && current.encryptedOriginalEnvelope != null) {
                "payload-expired quarantine cannot be placed on legal hold"
            }
        }
        val updated = SchedulingQuarantineEvents.update({
            (SchedulingQuarantineEvents.id eq quarantineId) and
                (SchedulingQuarantineEvents.status eq current.status) and
                (SchedulingQuarantineEvents.legalHold eq current.legalHold) and
                if (enabled) SchedulingQuarantineEvents.encryptedOriginalEnvelope.isNotNull()
                else SchedulingQuarantineEvents.id eq quarantineId
        }) {
            it[legalHold] = enabled
        }
        check(updated == 1) { "quarantine changed concurrently before legal-hold update" }
        appendAudit(
            quarantineId = quarantineId,
            action = if (enabled) QuarantineAuditAction.LEGAL_HOLD_ENABLED else QuarantineAuditAction.LEGAL_HOLD_DISABLED,
            actor = actor,
            reason = reason,
            beforeStatus = current.status,
            afterStatus = current.status,
        )
    }

    fun expireEligiblePayloads(
        expiresAtOrBefore: Instant,
        actor: String,
        reason: String,
        batchSize: Int,
    ): Int {
        require(batchSize in 1..1_000) { "batchSize must be between 1 and 1000" }
        val records = SchedulingQuarantineEvents
            .select(SchedulingQuarantineEvents.id, SchedulingQuarantineEvents.status)
            .where {
                (SchedulingQuarantineEvents.payloadExpiresAt lessEq expiresAtOrBefore) and
                    (SchedulingQuarantineEvents.legalHold eq false) and
                    (SchedulingQuarantineEvents.status inList payloadRetainedStatuses) and
                    SchedulingQuarantineEvents.encryptedOriginalEnvelope.isNotNull()
            }
            .orderBy(
                SchedulingQuarantineEvents.payloadExpiresAt to SortOrder.ASC,
                SchedulingQuarantineEvents.id to SortOrder.ASC,
            )
            .limit(batchSize)
            .map { it[SchedulingQuarantineEvents.id].value to it[SchedulingQuarantineEvents.status] }

        var expiredCount = 0
        records.forEach { (quarantineId, previousStatus) ->
            expiryObserver.beforeExpireCandidate(quarantineId)
            val updated = SchedulingQuarantineEvents.update({
                (SchedulingQuarantineEvents.id eq quarantineId) and
                    (SchedulingQuarantineEvents.payloadExpiresAt lessEq expiresAtOrBefore) and
                    (SchedulingQuarantineEvents.legalHold eq false) and
                    (SchedulingQuarantineEvents.status inList payloadRetainedStatuses) and
                    SchedulingQuarantineEvents.encryptedOriginalEnvelope.isNotNull()
            }) {
                it[encryptedOriginalEnvelope] = null
                it[status] = QuarantineStatus.PAYLOAD_EXPIRED
            }
            if (updated == 1) {
                appendAudit(
                    quarantineId = quarantineId,
                    action = QuarantineAuditAction.PAYLOAD_EXPIRED,
                    actor = actor,
                    reason = reason,
                    beforeStatus = previousStatus,
                    afterStatus = QuarantineStatus.PAYLOAD_EXPIRED,
                )
                expiredCount += 1
            }
        }
        return expiredCount
    }

    private fun transition(
        quarantineId: Long,
        nextStatus: QuarantineStatus,
        action: QuarantineAuditAction,
        actor: String,
        reason: String,
        approvalReferences: String? = null,
    ) {
        val current = requireRecord(quarantineId)
        transitionObserver.beforeTransition(quarantineId, nextStatus)
        val updated = SchedulingQuarantineEvents.update({
            (SchedulingQuarantineEvents.id eq quarantineId) and
                (SchedulingQuarantineEvents.status eq current.status) and
                SchedulingQuarantineEvents.encryptedOriginalEnvelope.isNotNull()
        }) {
            it[status] = nextStatus
            it[resolvedAt] = clock.instant()
        }
        check(updated == 1) { "quarantine changed concurrently before status transition" }
        appendAudit(quarantineId, action, actor, reason, current.status, nextStatus, approvalReferences = approvalReferences)
    }

    private fun appendRedriveOutcome(
        quarantineId: Long,
        expectedEventId: String,
        expectedEnvelopeHash: String,
        actor: String,
        reason: String,
        approvalReferences: List<String>,
        action: QuarantineAuditAction,
    ) {
        val current = requireRecord(quarantineId)
        require(current.eventId == expectedEventId) { "quarantine eventId does not match redrive outcome" }
        require(expectedEnvelopeHash.matches(sha256)) { "expectedEnvelopeHash must be lowercase SHA-256" }
        require(current.envelopeHash == expectedEnvelopeHash) {
            "quarantine envelopeHash does not match redrive outcome"
        }
        require(current.status == QuarantineStatus.RELEASE_APPROVED) {
            "redrive outcome requires RELEASE_APPROVED quarantine status"
        }
        val lastRedriveAudit = auditTrail(quarantineId)
            .lastOrNull {
                it.action in setOf(
                    QuarantineAuditAction.REDRIVE_ATTEMPT,
                    QuarantineAuditAction.REDRIVE,
                    QuarantineAuditAction.REDRIVE_FAILED,
                )
            }
        require(lastRedriveAudit?.action == QuarantineAuditAction.REDRIVE_ATTEMPT) {
            "redrive outcome requires a pending redrive attempt"
        }
        appendAudit(
            quarantineId = quarantineId,
            action = action,
            actor = actor,
            reason = reason,
            beforeStatus = current.status,
            afterStatus = current.status,
            approvalReferences = approvalReferences.distinct().joinToString(","),
        )
    }

    private fun requireRecord(quarantineId: Long): QuarantineRecord =
        requireNotNull(findById(quarantineId)) { "quarantine record not found: $quarantineId" }

    private fun appendAudit(
        quarantineId: Long,
        action: QuarantineAuditAction,
        actor: String,
        reason: String,
        beforeStatus: QuarantineStatus?,
        afterStatus: QuarantineStatus?,
        dryRunDiffHash: String? = null,
        approvalReferences: String? = null,
        createdAt: Instant = clock.instant(),
    ) {
        validateIdentifier(actor, "actor")
        validateShortText(reason, "reason")
        SchedulingQuarantineAuditEvents.insertAndGetId {
            it[SchedulingQuarantineAuditEvents.quarantineId] = quarantineId
            it[SchedulingQuarantineAuditEvents.action] = action
            it[SchedulingQuarantineAuditEvents.actor] = actor
            it[SchedulingQuarantineAuditEvents.reason] = reason
            it[SchedulingQuarantineAuditEvents.dryRunDiffHash] = dryRunDiffHash
            it[SchedulingQuarantineAuditEvents.beforeStatus] = beforeStatus
            it[SchedulingQuarantineAuditEvents.afterStatus] = afterStatus
            it[SchedulingQuarantineAuditEvents.approvalReferences] = approvalReferences
            it[SchedulingQuarantineAuditEvents.createdAt] = createdAt
        }
    }

    private fun validateDetection(detection: QuarantineDetection) {
        listOf(
            detection.eventId to "eventId",
            detection.eventType to "eventType",
            detection.protectedEnvelope.keyId to "encryptionKeyId",
            detection.producer to "producer",
            detection.sourceAuthority to "sourceAuthority",
            detection.sourceAggregateId to "sourceAggregateId",
            detection.correlationId to "correlationId",
        ).forEach { (value, name) -> validateIdentifier(value, name) }
        require(detection.protectedEnvelope.envelopeHash.matches(sha256)) {
            "envelopeHash must be lowercase SHA-256"
        }
        require(detection.schemaVersion > 0) { "schemaVersion must be positive" }
        require(detection.sourceAggregateVersion > 0) { "sourceAggregateVersion must be positive" }
        require(detection.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(detection.clinicId > 0) { "clinicId must be positive" }
        validateReasonCode(detection.reasonCode)
        detection.protectedEnvelope.ciphertext?.let(::validateEncryptedEnvelope)
        require(detection.payloadExpiresAt >= detection.detectedAt) { "payload expiry must not precede detection" }
    }

    private fun validateReasonCode(reasonCode: String) {
        require(reasonCode in allowedReasonCodes) { "reasonCode is not allowlisted" }
    }

    private fun validateEncryptedEnvelope(encryptedOriginalEnvelope: String) {
        require(encryptedOriginalEnvelope.length in MIN_ENCODED_ENVELOPE_LENGTH..MAX_ENCRYPTED_ENVELOPE_LENGTH) {
            "encrypted original envelope length is invalid"
        }
        val encryptedBytes = runCatching {
            Base64.getDecoder().decode(encryptedOriginalEnvelope)
        }.getOrElse {
            throw IllegalArgumentException("encrypted original envelope must be valid Base64", it)
        }
        require(encryptedBytes.size >= MIN_ENCRYPTED_ENVELOPE_BYTES) {
            "encrypted original envelope is too short for AES-GCM"
        }
    }

    private fun validateIdentifier(value: String, fieldName: String) {
        require(value.length in 1..128) { "$fieldName length is invalid" }
        require(identifier.matches(value)) { "$fieldName contains unsafe characters" }
    }

    private fun validateShortText(value: String, fieldName: String) {
        require(value.isNotBlank()) { "$fieldName must not be blank" }
        require(value.length <= 256) { "$fieldName is too long" }
        require(value.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
    }

    private companion object {
        // 1 MiB raw JSON, canonical metadata, GCM tag/IV를 Base64로 보관할 수 있는 상한입니다.
        const val MAX_ENCRYPTED_ENVELOPE_LENGTH = 1_500_000
        const val MIN_ENCODED_ENVELOPE_LENGTH = 40
        const val MIN_ENCRYPTED_ENVELOPE_BYTES = 29
        val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val sha256 = Regex("[0-9a-f]{64}")
        val payloadRetainedStatuses = listOf(
            QuarantineStatus.OPEN,
            QuarantineStatus.RELEASE_DENIED,
            QuarantineStatus.RELEASE_APPROVED,
        )
        val dualApprovalReasonCodes = setOf("REFUND_REVIEW", "CONSENT_REQUIRED", "SAFETY_REVIEW")
        val trustFailureReasonCodes = setOf(
            "TRUST_FAILED",
            "EVENT_TYPE_NOT_ALLOWED",
            "PRODUCER_NOT_ALLOWED",
            "KEY_NOT_ALLOWED",
            "ALGORITHM_NOT_ALLOWED",
            "ISSUER_NOT_ALLOWED",
            "AUDIENCE_NOT_ALLOWED",
            "REPLAY_WINDOW_EXCEEDED",
            "EVENT_FROM_FUTURE",
            "FACT_REPLAY_WINDOW_EXCEEDED",
            "FACT_FROM_FUTURE",
            "PAYLOAD_HASH_MISMATCH",
            "SIGNATURE_INVALID",
            "SCHEMA_VERSION_NOT_ALLOWED",
            "ENVELOPE_METADATA_INVALID",
            "PAYLOAD_TOO_LARGE",
            "PAYLOAD_DEPTH_EXCEEDED",
            "PAYLOAD_STRUCTURE_INVALID",
            "PRODUCT_MIGRATION_MAPPING_FAILED",
            "PRODUCT_MIGRATION_DECLINE_MAPPING_FAILED",
            "TREATMENT_FULFILLMENT_MAPPING_FAILED",
            "PAYLOAD_CONTRACT_INVALID",
            "ROUTING_METADATA_MISMATCH",
        )
        val allowedReasonCodes = setOf(
            "TRUST_FAILED",
            "CATALOG_RETIRED",
            "UNKNOWN_CATALOG",
            "CATALOG_VERSION_UNAVAILABLE",
            "SCOPE_MISMATCH",
            "TENANT_CLINIC_MISMATCH",
            "PURCHASE_OWNERSHIP_CONFLICT",
            "APPOINTMENT_PLAN_NOT_FOUND",
            "PACKAGE_EXECUTION_INVALID",
            "PACKAGE_EXECUTION_PRODUCT_MISMATCH",
            "ACTIVE_PLAN_REVISION_NOT_FOUND",
            "FULFILLMENT_FACT_INVALID",
            "PRODUCT_VERSION_MISMATCH",
            "CONSENT_SUBJECT_MISMATCH",
            "BOM_MAPPING_INVALID",
            "SOURCE_VERSION_HASH_CONFLICT",
            "STALE_SOURCE_VERSION",
            "SOURCE_VERSION_GAP_EXHAUSTED",
            "SOURCE_AUTHORITY_TIMEOUT_EXHAUSTED",
            "SOURCE_AUTHORITY_CIRCUIT_OPEN_EXHAUSTED",
            "EVENT_TYPE_NOT_ALLOWED",
            "SCHEMA_VERSION_NOT_ALLOWED",
            "ENVELOPE_METADATA_INVALID",
            "PAYLOAD_TOO_LARGE",
            "PAYLOAD_DEPTH_EXCEEDED",
            "PAYLOAD_STRUCTURE_INVALID",
            "PRODUCT_MIGRATION_MAPPING_FAILED",
            "PRODUCT_MIGRATION_DECLINE_MAPPING_FAILED",
            "TREATMENT_FULFILLMENT_MAPPING_FAILED",
            "PAYLOAD_CONTRACT_INVALID",
            "ROUTING_METADATA_MISMATCH",
            "PRODUCER_NOT_ALLOWED",
            "KEY_NOT_ALLOWED",
            "ALGORITHM_NOT_ALLOWED",
            "ISSUER_NOT_ALLOWED",
            "AUDIENCE_NOT_ALLOWED",
            "REPLAY_WINDOW_EXCEEDED",
            "EVENT_FROM_FUTURE",
            "FACT_REPLAY_WINDOW_EXCEEDED",
            "FACT_FROM_FUTURE",
            "PAYLOAD_HASH_MISMATCH",
            "SIGNATURE_INVALID",
            "PROFILE_REFERENCE_FINGERPRINT_INVALID",
            "REFUND_REVIEW",
            "CONSENT_REQUIRED",
            "SAFETY_REVIEW",
        )
    }
}

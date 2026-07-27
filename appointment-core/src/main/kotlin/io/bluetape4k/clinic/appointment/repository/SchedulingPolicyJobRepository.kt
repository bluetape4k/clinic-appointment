package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewCursor
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewProgress
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Caller-transaction persistence primitives for activation and preview workers.
 *
 * Every method must execute inside a caller-owned Exposed `transaction {}`.
 * Claims are conditional updates and terminal/checkpoint writes are fenced by
 * the live lease owner. The constructor secret is used only to derive an
 * HMAC-SHA-256 idempotency hash; callers must inject a rotated secret from
 * protected configuration and must never log it.
 *
 * @param idempotencyHashSecret secret HMAC key with at least 16 bytes. The byte
 * array is defensively copied and is never exposed by this repository.
 */
class SchedulingPolicyJobRepository(
    idempotencyHashSecret: ByteArray,
) {
    private val hashSecret = idempotencyHashSecret.copyOf().also {
        require(it.size >= MIN_HASH_SECRET_BYTES) {
            "idempotencyHashSecret must contain at least $MIN_HASH_SECRET_BYTES bytes"
        }
    }

    /**
     * Validates and HMAC-hashes a raw idempotency key.
     *
     * Accepted keys contain 1..128 ASCII letters, digits, `.`, `_`, `:`, `/`,
     * or `-`. The returned lowercase hash is the only value suitable for
     * persistence, logs, and responses.
     */
    fun hashIdempotencyKey(rawKey: String): String {
        require(IDEMPOTENCY_KEY_REGEX.matches(rawKey)) {
            "Idempotency key must match ${IDEMPOTENCY_KEY_REGEX.pattern}"
        }
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(hashSecret, HMAC_SHA256))
        return mac.doFinal(rawKey.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Inserts a pending activation command.
     *
     * The unique boundary is tenant, scope, non-null clinic sentinel, and
     * [SchedulingPolicyActivationCommandRecord.idempotencyKeyHash]. A duplicate
     * hash therefore replays only inside the same authorized policy scope.
     */
    fun createActivation(
        record: SchedulingPolicyActivationCommandRecord,
    ): SchedulingPolicyActivationCommandRecord {
        validateActivation(record)
        val commandId = SchedulingPolicyActivationCommands.insertAndGetId {
            it[tenantGroupId] = record.tenantGroupId
            it[scope] = record.scope
            it[clinicId] = record.clinicId
            it[clinicScopeKey] = record.clinicScopeKey
            it[definitionId] = record.definitionId
            it[replayOfCommandId] = record.replayOfCommandId
            it[expectedDraftRevision] = record.expectedDraftRevision
            it[expectedActiveRevision] = record.expectedActiveRevision
            it[idempotencyKeyHash] = record.idempotencyKeyHash
            it[requestFingerprint] = record.requestFingerprint
            it[status] = record.status
            it[effectiveFrom] = record.effectiveFrom
            it[nextAttemptAt] = record.nextAttemptAt
            it[leaseOwner] = record.leaseOwner
            it[leaseUntil] = record.leaseUntil
            it[attempt] = record.attempt
            it[resultTenantGeneration] = record.resultTenantGeneration
            it[resultClinicGeneration] = record.resultClinicGeneration
            it[eventId] = record.eventId
            it[lastErrorCode] = record.lastErrorCode
        }.value
        return requireNotNull(findActivation(commandId))
    }

    /**
     * Returns one activation command visible in the caller-owned transaction.
     *
     * @param commandId Positive database identity.
     * @return The stored command, or `null` when no row is visible. `null`
     * conveys absence only and must not be interpreted as authorization denial.
     */
    fun findActivation(commandId: Long): SchedulingPolicyActivationCommandRecord? =
        SchedulingPolicyActivationCommands
            .selectAll()
            .where { SchedulingPolicyActivationCommands.id eq commandId }
            .singleOrNull()
            ?.toSchedulingPolicyActivationCommandRecord()

    /**
     * Finds the command occupying one scoped keyed-idempotency boundary.
     *
     * Lookup uses only the HMAC digest; the raw idempotency header is never
     * accepted by this method, persisted, logged, or returned. The tenant,
     * scope, and non-null clinic sentinel prevent a digest used in one clinic
     * from disclosing or replaying a command in another scope.
     *
     * @param scope Exact authorized policy scope.
     * @param idempotencyKeyHash Lowercase 64-character HMAC-SHA-256 digest.
     * @return Existing command, or `null` when the scoped key is unused.
     */
    fun findActivation(
        scope: PolicyScopeRef,
        idempotencyKeyHash: String,
    ): SchedulingPolicyActivationCommandRecord? {
        require(SHA256_REGEX.matches(idempotencyKeyHash)) {
            "idempotencyKeyHash must be lowercase SHA-256"
        }
        return SchedulingPolicyActivationCommands
            .selectAll()
            .where {
                (SchedulingPolicyActivationCommands.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyActivationCommands.scope eq scope.scope) and
                    (SchedulingPolicyActivationCommands.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyActivationCommands.idempotencyKeyHash eq idempotencyKeyHash)
            }
            .singleOrNull()
            ?.toSchedulingPolicyActivationCommandRecord()
    }

    /**
     * Claims an eligible activation command or reclaims an expired lease.
     *
     * [leaseUntil] must be later than [now]. The conditional update permits
     * pending/retry rows whose `nextAttemptAt <= now`, plus claimed rows whose
     * previous lease has expired. A non-expired owner cannot be displaced.
     */
    fun claimDueActivation(
        commandId: Long,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
    ): Boolean {
        validateLease(owner, now, leaseUntil)
        val previousAttempt = findActivation(commandId)?.attempt ?: return false
        val eligible =
            (
                (SchedulingPolicyActivationCommands.status inList ACTIVATION_READY_STATES) and
                    (SchedulingPolicyActivationCommands.nextAttemptAt lessEq now)
                ) or
                (
                    (SchedulingPolicyActivationCommands.status eq PolicyActivationCommandStatus.CLAIMED) and
                        (SchedulingPolicyActivationCommands.leaseUntil lessEq now)
                    )
        val affected = SchedulingPolicyActivationCommands.update({
            (SchedulingPolicyActivationCommands.id eq commandId) and eligible
        }) {
            it[status] = PolicyActivationCommandStatus.CLAIMED
            it[leaseOwner] = owner
            it[SchedulingPolicyActivationCommands.leaseUntil] = leaseUntil
            it[attempt] = previousAttempt + 1
            it[updatedAt] = now
        }
        return affected == 1
    }

    /**
     * Completes a claimed activation only for the live lease owner.
     *
     * A stale worker receives `false` and cannot overwrite generations or event
     * identity produced by the current owner.
     */
    fun completeActivation(
        commandId: Long,
        owner: String,
        generation: PolicyGenerationVector,
        eventId: String,
        completedAt: Instant,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(generation.tenantGeneration > 0) { "tenantGeneration must be positive" }
        require(generation.clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(eventId.isNotBlank() && eventId.length <= MAX_EVENT_ID_LENGTH) {
            "eventId must contain 1..$MAX_EVENT_ID_LENGTH characters"
        }
        return SchedulingPolicyActivationCommands.update({
            (SchedulingPolicyActivationCommands.id eq commandId) and
                (SchedulingPolicyActivationCommands.status eq PolicyActivationCommandStatus.CLAIMED) and
                (SchedulingPolicyActivationCommands.leaseOwner eq owner) and
                (SchedulingPolicyActivationCommands.leaseUntil greater completedAt)
        }) {
            it[status] = PolicyActivationCommandStatus.COMPLETED
            it[resultTenantGeneration] = generation.tenantGeneration
            it[resultClinicGeneration] = generation.clinicGeneration
            it[SchedulingPolicyActivationCommands.eventId] = eventId
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = null
            it[updatedAt] = completedAt
        } == 1
    }

    /**
     * Marks a claimed activation terminally missed for the live lease owner.
     *
     * A stale or wrong owner receives `false` and cannot erase a completed
     * result. [errorCode] is a stable sanitized operator code, never raw
     * exception text, request JSON, actor data, claims, or an idempotency key.
     * The source row remains immutable afterward; a human recovery creates a
     * new command whose `replayOfCommandId` references this row.
     *
     * @param missedAt UTC transition instant that must precede the live lease
     * expiry; an expired worker must reacquire a lease before deciding MISSED.
     */
    fun markActivationMissed(
        commandId: Long,
        owner: String,
        errorCode: String,
        missedAt: Instant,
    ): Boolean {
        require(commandId > 0) { "commandId must be positive" }
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(STABLE_ERROR_CODE_REGEX.matches(errorCode)) {
            "errorCode must contain 1..$MAX_ERROR_CODE_LENGTH uppercase safe characters"
        }
        return SchedulingPolicyActivationCommands.update({
            (SchedulingPolicyActivationCommands.id eq commandId) and
                (SchedulingPolicyActivationCommands.status eq PolicyActivationCommandStatus.CLAIMED) and
                (SchedulingPolicyActivationCommands.leaseOwner eq owner) and
                (SchedulingPolicyActivationCommands.leaseUntil greater missedAt)
        }) {
            it[status] = PolicyActivationCommandStatus.MISSED
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = errorCode
            it[updatedAt] = missedAt
        } == 1
    }

    /**
     * Inserts an asynchronous impact-preview job.
     *
     * The draft revision and generation pair are immutable inputs. Workers must
     * compare them with authoritative state whenever a partition resumes.
     */
    fun createPreviewJob(record: SchedulingPolicyPreviewJobRecord): SchedulingPolicyPreviewJobRecord {
        validatePreview(record)
        val jobId = SchedulingPolicyPreviewJobs.insertAndGetId {
            it[tenantGroupId] = record.tenantGroupId
            it[clinicId] = record.clinicId
            it[definitionId] = record.definitionId
            it[draftRevision] = record.draftRevision
            it[tenantGeneration] = record.tenantGeneration
            it[clinicGeneration] = record.clinicGeneration
            it[partitionCount] = record.partitionCount
            it[cursorPartition] = record.cursorPartition
            it[cursorLastAppointmentId] = record.cursorLastAppointmentId
            it[scannedCount] = record.scannedCount
            it[affectedCount] = record.affectedCount
            it[status] = record.status
            it[deadlineAt] = record.deadlineAt
            it[nextAttemptAt] = record.nextAttemptAt
            it[leaseOwner] = record.leaseOwner
            it[leaseUntil] = record.leaseUntil
            it[lastErrorCode] = record.lastErrorCode
        }.value
        return requireNotNull(findPreviewJob(jobId))
    }

    /**
     * Returns one preview job visible in the caller-owned transaction.
     *
     * @param jobId Positive database identity.
     * @return The stored job, or `null` when no row is visible. `null` conveys
     * absence only and must not be interpreted as authorization denial.
     */
    fun findPreviewJob(jobId: Long): SchedulingPolicyPreviewJobRecord? =
        SchedulingPolicyPreviewJobs
            .selectAll()
            .where { SchedulingPolicyPreviewJobs.id eq jobId }
            .singleOrNull()
            ?.toSchedulingPolicyPreviewJobRecord()

    /**
     * Claims an eligible preview or reclaims an expired running lease.
     *
     * [leaseUntil] must be strictly after [now]. Eligible rows are either
     * `PENDING` with `nextAttemptAt <= now`, or `RUNNING` with an existing
     * `leaseUntil <= now`; both cases also require `deadlineAt > now`.
     * A successful conditional update writes `RUNNING`, [owner], [leaseUntil],
     * and the transition time in the caller-owned transaction.
     *
     * @return `true` only when this caller won the conditional update. `false`
     * means the row is missing, not yet due, still leased, past its deadline,
     * in a terminal state, or was won concurrently.
     */
    fun claimDuePreview(
        jobId: Long,
        owner: String,
        now: Instant,
        leaseUntil: Instant,
    ): Boolean {
        validateLease(owner, now, leaseUntil)
        val eligible =
            (
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.PENDING) and
                    (SchedulingPolicyPreviewJobs.nextAttemptAt lessEq now) and
                    (SchedulingPolicyPreviewJobs.deadlineAt greater now)
                ) or
                (
                    (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                        (SchedulingPolicyPreviewJobs.leaseUntil lessEq now) and
                        (SchedulingPolicyPreviewJobs.deadlineAt greater now)
                    )
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and eligible
        }) {
            it[status] = PolicyPreviewJobStatus.RUNNING
            it[leaseOwner] = owner
            it[SchedulingPolicyPreviewJobs.leaseUntil] = leaseUntil
            it[updatedAt] = now
        } == 1
    }

    /**
     * Persists a monotonic preview checkpoint for the live lease owner.
     *
     * [PolicyPreviewCursor.partition] is zero-based, remains below the job's
     * fixed partition count, and never moves backward. Its appointment ID is
     * `null` only before the first row of a partition; otherwise it is positive
     * and non-decreasing within that partition. Progress counters are
     * non-negative and monotonic, with `affectedCount <= scannedCount`.
     * Unchanged values are permitted for a heartbeat.
     *
     * @return `false` when the job is missing, is not `RUNNING`, the [owner] is
     * stale, or a concurrent transition wins. Invalid or regressing cursor and
     * progress supplied by the current owner throw [IllegalArgumentException].
     */
    fun checkpointPreview(
        jobId: Long,
        owner: String,
        cursor: PolicyPreviewCursor,
        progress: PolicyPreviewProgress,
    ): Boolean {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(cursor.partition >= 0) { "cursor partition must be non-negative" }
        require(cursor.lastAppointmentId == null || cursor.lastAppointmentId > 0) {
            "lastAppointmentId must be positive when present"
        }
        require(progress.scannedCount >= 0) { "scannedCount must be non-negative" }
        require(progress.affectedCount in 0..progress.scannedCount) {
            "affectedCount must be between zero and scannedCount"
        }
        val current = findPreviewJob(jobId) ?: return false
        if (current.status != PolicyPreviewJobStatus.RUNNING || current.leaseOwner != owner) {
            return false
        }
        require(cursor.partition < current.partitionCount) {
            "cursor partition must be inside partitionCount"
        }
        require(cursor.partition >= current.cursorPartition) { "cursor partition cannot move backward" }
        if (cursor.partition == current.cursorPartition &&
            current.cursorLastAppointmentId != null &&
            cursor.lastAppointmentId != null
        ) {
            require(cursor.lastAppointmentId >= current.cursorLastAppointmentId) {
                "appointment cursor cannot move backward"
            }
        }
        require(progress.scannedCount >= current.scannedCount) { "scannedCount cannot decrease" }
        require(progress.affectedCount >= current.affectedCount) { "affectedCount cannot decrease" }
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                (SchedulingPolicyPreviewJobs.leaseOwner eq owner)
        }) {
            it[cursorPartition] = cursor.partition
            it[cursorLastAppointmentId] = cursor.lastAppointmentId
            it[scannedCount] = progress.scannedCount
            it[affectedCount] = progress.affectedCount
            it.update(updatedAt, CurrentTimestamp)
        } == 1
    }

    private fun validateActivation(record: SchedulingPolicyActivationCommandRecord) {
        val scope = PolicyScopeRef(record.tenantGroupId, record.scope, record.clinicId)
        require(record.clinicScopeKey == scope.clinicScopeKey) {
            "clinicScopeKey must match scope and clinicId"
        }
        require(record.definitionId > 0) { "definitionId must be positive" }
        record.replayOfCommandId?.let { sourceCommandId ->
            require(sourceCommandId > 0) { "replayOfCommandId must be positive" }
            val source = requireNotNull(findActivation(sourceCommandId)) {
                "replayOfCommandId must identify an existing activation command"
            }
            require(source.status == PolicyActivationCommandStatus.MISSED) {
                "replay source command must be MISSED"
            }
            require(
                source.tenantGroupId == record.tenantGroupId &&
                    source.scope == record.scope &&
                    source.clinicScopeKey == record.clinicScopeKey
            ) {
                "replay source command must belong to the same policy scope"
            }
            require(source.definitionId == record.definitionId) {
                "replay source command must select the same definition"
            }
        }
        require(record.expectedDraftRevision > 0) { "expectedDraftRevision must be positive" }
        require(record.expectedActiveRevision >= 0) { "expectedActiveRevision must be non-negative" }
        require(SHA256_REGEX.matches(record.idempotencyKeyHash)) {
            "idempotencyKeyHash must be lowercase SHA-256"
        }
        require(SHA256_REGEX.matches(record.requestFingerprint)) {
            "requestFingerprint must be lowercase SHA-256"
        }
        require(record.nextAttemptAt >= record.effectiveFrom) {
            "nextAttemptAt must not precede effectiveFrom"
        }
        require(record.status == PolicyActivationCommandStatus.PENDING) {
            "new activation command must start in PENDING"
        }
        require(record.leaseOwner == null && record.leaseUntil == null) {
            "new activation command cannot start with a lease"
        }
        require(record.attempt == 0) { "new activation command attempt must be zero" }
        require(
            record.resultTenantGeneration == null &&
                record.resultClinicGeneration == null &&
                record.eventId == null &&
                record.lastErrorCode == null
        ) {
            "new activation command cannot contain terminal result fields"
        }
    }

    private fun validatePreview(record: SchedulingPolicyPreviewJobRecord) {
        require(record.tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(record.clinicId > 0) { "clinicId must be positive" }
        require(record.definitionId > 0) { "definitionId must be positive" }
        require(record.draftRevision > 0) { "draftRevision must be positive" }
        require(record.tenantGeneration > 0) { "tenantGeneration must be positive" }
        require(record.clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(record.partitionCount > 0) { "partitionCount must be positive" }
        require(record.cursorPartition in 0 until record.partitionCount) {
            "cursorPartition must be inside partitionCount"
        }
        require(record.scannedCount >= 0) { "scannedCount must be non-negative" }
        require(record.affectedCount in 0..record.scannedCount) {
            "affectedCount must be between zero and scannedCount"
        }
        require(record.deadlineAt > record.nextAttemptAt) {
            "deadlineAt must be later than nextAttemptAt"
        }
        require(record.status == PolicyPreviewJobStatus.PENDING) {
            "new preview job must start in PENDING"
        }
        require(record.leaseOwner == null && record.leaseUntil == null) {
            "new preview job cannot start with a lease"
        }
        require(
            record.cursorPartition == 0 &&
                record.cursorLastAppointmentId == null &&
                record.scannedCount == 0L &&
                record.affectedCount == 0L &&
                record.lastErrorCode == null
        ) {
            "new preview job cannot contain checkpoint or terminal fields"
        }
    }

    private fun validateLease(owner: String, now: Instant, leaseUntil: Instant) {
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(leaseUntil > now) { "leaseUntil must be later than now" }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private companion object {
        const val HMAC_SHA256 = "HmacSHA256"
        const val MIN_HASH_SECRET_BYTES = 16
        const val MAX_OWNER_LENGTH = 160
        const val MAX_EVENT_ID_LENGTH = 160
        const val MAX_ERROR_CODE_LENGTH = 96
        val IDEMPOTENCY_KEY_REGEX = Regex("[A-Za-z0-9._:/-]{1,128}")
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
        val STABLE_ERROR_CODE_REGEX = Regex("[A-Z][A-Z0-9_]{0,${MAX_ERROR_CODE_LENGTH - 1}}")
        val ACTIVATION_READY_STATES = listOf(
            PolicyActivationCommandStatus.PENDING,
            PolicyActivationCommandStatus.RETRY_WAIT,
        )
    }
}

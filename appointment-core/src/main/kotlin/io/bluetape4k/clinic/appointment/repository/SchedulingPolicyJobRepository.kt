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
 * 정책 활성화 작업자와 영향도 미리보기 작업자가 사용하는 영속화 저장소입니다.
 *
 * 모든 메서드는 호출자가 소유한 Exposed `transaction {}` 안에서 실행되어야 합니다.
 * 작업 선점은 조건부 `UPDATE`로만 수행하며, 완료/누락/체크포인트 기록은 현재 lease
 * 소유자로 한 번 더 fencing 됩니다. 생성자에 전달하는 비밀값은 원본 idempotency key를
 * HMAC-SHA-256 digest로 바꾸는 용도로만 사용합니다. 호출자는 보호된 설정에서 주기적으로
 * 교체되는 비밀값을 주입해야 하며, 이 값이나 원본 key를 로그에 남기면 안 됩니다.
 *
 * @param idempotencyHashSecret 최소 16바이트의 HMAC 비밀 키입니다. 저장소는 byte array를
 * 방어적으로 복사하며 외부로 다시 노출하지 않습니다.
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
     * 원본 idempotency key를 검증한 뒤 HMAC digest로 변환합니다.
     *
     * 허용되는 key는 1..128자의 ASCII 영문자, 숫자, `.`, `_`, `:`, `/`, `-`로만
     * 구성됩니다. 반환되는 소문자 64자 hash만 저장, 로그, 응답에 사용할 수 있으며,
     * 원본 key는 저장소 경계를 넘겨 보관하지 않습니다.
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
     * 대기 상태의 정책 활성화 명령을 생성합니다.
     *
     * 중복 판단 경계는 테넌트, 정책 범위, null이 아닌 병원 sentinel,
     * [SchedulingPolicyActivationCommandRecord.idempotencyKeyHash]입니다. 따라서 같은
     * digest가 재사용되더라도 동일하게 인가된 정책 범위 안에서만 재실행으로 취급됩니다.
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
     * 호출자 트랜잭션에서 보이는 활성화 명령 하나를 조회합니다.
     *
     * @param commandId 양수 데이터베이스 식별자입니다.
     * @return 저장된 명령입니다. 보이는 행이 없으면 `null`을 반환합니다. `null`은 부재만
     * 의미하며 인가 거부로 해석하면 안 됩니다.
     */
    fun findActivation(commandId: Long): SchedulingPolicyActivationCommandRecord? =
        SchedulingPolicyActivationCommands
            .selectAll()
            .where { SchedulingPolicyActivationCommands.id eq commandId }
            .singleOrNull()
            ?.toSchedulingPolicyActivationCommandRecord()

    /**
     * 특정 범위의 keyed-idempotency 경계를 점유한 명령을 조회합니다.
     *
     * 조회에는 HMAC digest만 사용합니다. 이 메서드는 원본 idempotency header를 받지
     * 않으며, 저장하거나 로그로 남기거나 반환하지도 않습니다. 테넌트, 정책 범위,
     * null이 아닌 병원 sentinel을 함께 사용하므로 한 병원에서 사용된 digest가 다른 범위의
     * 명령을 노출하거나 재실행하지 못합니다.
     *
     * @param scope 정확히 인가된 정책 범위입니다.
     * @param idempotencyKeyHash 소문자 64자 HMAC-SHA-256 digest입니다.
     * @return 기존 명령입니다. 해당 범위의 key가 사용되지 않았으면 `null`을 반환합니다.
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
     * 실행 가능한 활성화 명령을 선점하거나 만료된 lease를 재선점합니다.
     *
     * [leaseUntil]은 [now]보다 이후여야 합니다. 조건부 갱신은 `nextAttemptAt <= now`인
     * 대기/재시도 행과 기존 lease가 만료된 `CLAIMED` 행만 허용합니다. 아직 만료되지 않은
     * 소유자는 다른 작업자가 밀어낼 수 없습니다.
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
     * 현재 lease 소유자에게만 선점된 활성화 명령의 완료 기록을 허용합니다.
     *
     * 오래된 작업자는 `false`를 받으며, 현재 소유자가 만든 generation 또는 event 식별자를
     * 덮어쓸 수 없습니다.
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
     * 현재 lease 소유자에게만 선점된 활성화 명령을 최종 누락 상태로 표시합니다.
     *
     * 오래되었거나 잘못된 소유자는 `false`를 받으며, 이미 완료된 결과를 지울 수 없습니다.
     * [errorCode]는 운영자가 식별할 수 있는 안정적이고 정제된 코드여야 하며, 원본 예외
     * 메시지, 요청 JSON, 행위자 정보, claim, idempotency key를 포함하면 안 됩니다.
     * 이후 원본 행은 불변으로 남기고, 사람이 복구할 때는 이 행을 `replayOfCommandId`로
     * 참조하는 새 명령을 생성합니다.
     *
     * @param missedAt UTC 기준 상태 전이 시각입니다. 현재 lease 만료보다 앞서야 하며,
     * lease가 만료된 작업자는 MISSED 판단 전에 lease를 다시 획득해야 합니다.
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
     * 비동기 영향도 미리보기 작업을 생성합니다.
     *
     * draft revision과 generation 쌍은 작업의 불변 입력입니다. 작업자는 partition을 재개할
     * 때마다 이 값을 권위 있는 현재 상태와 비교해야 합니다.
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
     * 호출자 트랜잭션에서 보이는 미리보기 작업 하나를 조회합니다.
     *
     * @param jobId 양수 데이터베이스 식별자입니다.
     * @return 저장된 작업입니다. 보이는 행이 없으면 `null`을 반환합니다. `null`은 부재만
     * 의미하며 인가 거부로 해석하면 안 됩니다.
     */
    fun findPreviewJob(jobId: Long): SchedulingPolicyPreviewJobRecord? =
        SchedulingPolicyPreviewJobs
            .selectAll()
            .where { SchedulingPolicyPreviewJobs.id eq jobId }
            .singleOrNull()
            ?.toSchedulingPolicyPreviewJobRecord()

    /**
     * 실행 가능한 미리보기 작업을 선점하거나 만료된 실행 lease를 재선점합니다.
     *
     * [leaseUntil]은 반드시 [now]보다 이후여야 합니다. 실행 가능한 행은
     * `nextAttemptAt <= now`인 `PENDING` 행 또는 기존 `leaseUntil <= now`인 `RUNNING`
     * 행이며, 두 경우 모두 `deadlineAt > now`여야 합니다. 조건부 갱신에 성공하면 호출자
     * 트랜잭션 안에서 `RUNNING`, [owner], [leaseUntil], 전이 시각을 기록합니다.
     *
     * @return 이 호출자가 조건부 갱신에서 이긴 경우에만 `true`입니다. `false`는 행 부재,
     * 아직 실행 시각 전, 유효한 lease 존재, deadline 경과, 종결 상태, 또는 동시 선점 패배를
     * 의미합니다.
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
     * 현재 lease 소유자의 단조 증가 미리보기 체크포인트를 저장합니다.
     *
     * [PolicyPreviewCursor.partition]은 0부터 시작하며 작업 생성 시 고정된 partition 수보다
     * 작아야 하고 뒤로 이동할 수 없습니다. appointment ID는 partition의 첫 행을 읽기
     * 전일 때만 `null`이며, 값이 있으면 양수이고 같은 partition 안에서 감소하지 않아야
     * 합니다. 진행 카운터는 음수가 아니고 단조 증가해야 하며
     * `affectedCount <= scannedCount`를 만족해야 합니다. heartbeat 목적의 동일 값 저장은
     * 허용합니다.
     *
     * @return 작업이 없거나, `RUNNING` 상태가 아니거나, [owner]가 오래되었거나, 동시 전이가
     * 먼저 성공하면 `false`입니다. 현재 소유자가 유효하지 않거나 후퇴하는 cursor/progress를
     * 제공하면 [IllegalArgumentException]을 던집니다.
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

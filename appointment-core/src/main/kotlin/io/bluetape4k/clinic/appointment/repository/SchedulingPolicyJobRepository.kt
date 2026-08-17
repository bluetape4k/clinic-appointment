package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.PolicyActivationCommandStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewCursor
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewJobStatus
import io.bluetape4k.clinic.appointment.model.dto.PolicyPreviewProgress
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyActivationCommandRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyPreviewJobRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyGenerationVector
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyActivationCommands
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyPreviewJobs
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
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
            it[expectedTenantGeneration] = record.expectedTenantGeneration
            it[expectedClinicGeneration] = record.expectedClinicGeneration
            it[previewEvidenceToken] = record.previewEvidenceToken
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
     * 현재 lease 소유자의 일시 실패를 재시도 대기 상태로 기록합니다.
     *
     * claim transaction과 실제 activation transaction이 분리되어 있으므로 activation
     * rollback 이후에도 이 전이는 durable claim을 owner-fence할 수 있습니다. [errorCode]에는
     * 예외 메시지나 SQL, actor, token을 넣지 않고 안정적인 분류 코드만 저장해야 합니다.
     * [nextAttemptAt]은 [retryAt]보다 뒤여야 하며, 성공하면 lease를 해제해 다른 worker가
     * 지정 시각 이후 다시 선점할 수 있게 합니다.
     *
     * @return 현재 유효한 lease owner가 정확히 한 행을 전이했으면 `true`.
     */
    fun markActivationRetry(
        commandId: Long,
        owner: String,
        errorCode: String,
        nextAttemptAt: Instant,
        retryAt: Instant,
    ): Boolean {
        require(commandId > 0) { "commandId must be positive" }
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(STABLE_ERROR_CODE_REGEX.matches(errorCode)) {
            "errorCode must contain 1..$MAX_ERROR_CODE_LENGTH uppercase safe characters"
        }
        require(nextAttemptAt > retryAt) { "nextAttemptAt must be later than retryAt" }
        return SchedulingPolicyActivationCommands.update({
            (SchedulingPolicyActivationCommands.id eq commandId) and
                (SchedulingPolicyActivationCommands.status eq PolicyActivationCommandStatus.CLAIMED) and
                (SchedulingPolicyActivationCommands.leaseOwner eq owner) and
                (SchedulingPolicyActivationCommands.leaseUntil greater retryAt)
        }) {
            it[status] = PolicyActivationCommandStatus.RETRY_WAIT
            it[SchedulingPolicyActivationCommands.nextAttemptAt] = nextAttemptAt
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = errorCode
            it[updatedAt] = retryAt
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
            it[scope] = record.scope
            it[clinicId] = record.clinicId
            it[clinicScopeKey] = record.clinicScopeKey
            it[definitionId] = record.definitionId
            it[draftRevision] = record.draftRevision
            it[tenantGeneration] = record.tenantGeneration
            it[clinicGeneration] = record.clinicGeneration
            it[clinicGenerationDigest] = record.clinicGenerationDigest
            it[partitionCount] = record.partitionCount
            it[cursorPartition] = record.cursorPartition
            it[cursorLastAppointmentId] = record.cursorLastAppointmentId
            it[cursorClinicId] = record.cursorClinicId
            it[cursorScheduledAt] = record.cursorScheduledAt
            it[cursorAggregateType] = record.cursorAggregateType
            it[cursorAggregateId] = record.cursorAggregateId
            it[scannedCount] = record.scannedCount
            it[affectedCount] = record.affectedCount
            it[status] = record.status
            it[deadlineAt] = record.deadlineAt
            it[nextAttemptAt] = record.nextAttemptAt
            it[horizonFrom] = record.horizonFrom
            it[horizonUntil] = record.horizonUntil
            it[leaseOwner] = record.leaseOwner
            it[leaseUntil] = record.leaseUntil
            it[resultHash] = record.resultHash
            it[activationEvidenceToken] = record.activationEvidenceToken
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
     * HTTP/API 경계에서 미리보기 작업 하나를 scope와 primary key로 함께 조회합니다.
     *
     * [jobId]는 전역 증가 database identity라서 그 값만으로 조회하면 같은 SaaS 인스턴스의
     * 다른 tenant 또는 clinic preview row를 관찰할 수 있습니다. 이 overload는 routing,
     * Gateway 인증정보, 병원 접근 검증을 거쳐 만든 [scope]와 row의
     * `tenant_group_id`, `scope`, `clinic_scope_key`, `id`가 모두 일치할 때만 반환합니다.
     * tenant baseline은 `clinic_scope_key = 0`, clinic override는 양수 clinic ID를 사용하므로
     * non-null scope key를 사용해 PostgreSQL에서 null 비교 없이 같은 fence를 적용합니다.
     *
     * 반환값 `null`은 해당 row가 없거나 호출자가 제공한 scope boundary와 일치하지 않는다는
     * 뜻입니다. API 계층은 이를 동일하게 404로 처리해야 하며, 다른 tenant/scope에 존재하는지
     * 여부를 응답, 로그, metric tag로 구분해서 노출하면 안 됩니다.
     *
     * @param scope 인증된 tenant baseline 또는 clinic override boundary입니다.
     * @param jobId 양수 preview job database 식별자입니다.
     * @return 같은 scope에 속한 preview job입니다. 없거나 scope가 다르면 `null`입니다.
     */
    fun findPreviewJob(
        scope: PolicyScopeRef,
        jobId: Long,
    ): SchedulingPolicyPreviewJobRecord? {
        require(jobId > 0) { "jobId must be positive" }
        return SchedulingPolicyPreviewJobs
            .selectAll()
            .where {
                (SchedulingPolicyPreviewJobs.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyPreviewJobs.scope eq scope.scope) and
                    (SchedulingPolicyPreviewJobs.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyPreviewJobs.id eq jobId)
            }
            .singleOrNull()
            ?.toSchedulingPolicyPreviewJobRecord()
    }

    /**
     * 한 정책 scope의 runnable preview queue가 설정 상한에 도달했는지 판단합니다.
     *
     * tenant baseline과 각 clinic override는 서로 다른 queue key를 사용합니다. 따라서
     * SaaS의 다른 병원 또는 tenant가 만든 작업은 이 capacity를 소비하지 않습니다. 전체
     * `COUNT(*)` 대신 [capacity]개 ID까지만 materialize하며 `PENDING`과 `RUNNING`만 queue
     * 자원을 점유합니다. 호출자는 같은 policy scope의 admission을 직렬화하는 row lock을
     * 보유한 트랜잭션 안에서 이 메서드와 [createPreviewJob]을 연속 실행해야 합니다.
     *
     * @param scope queue를 소유하는 tenant baseline 또는 clinic override 경계.
     * @param capacity 한 정책 scope가 보유할 수 있는 runnable preview 상한.
     */
    fun isPreviewQueueSaturated(
        scope: PolicyScopeRef,
        capacity: Int,
    ): Boolean {
        require(capacity in 1..MAX_PREVIEW_QUEUE_CAPACITY) {
            "capacity must be in 1..$MAX_PREVIEW_QUEUE_CAPACITY"
        }
        return SchedulingPolicyPreviewJobs
            .select(SchedulingPolicyPreviewJobs.id)
            .where {
                (SchedulingPolicyPreviewJobs.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyPreviewJobs.scope eq scope.scope) and
                    (SchedulingPolicyPreviewJobs.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyPreviewJobs.status inList listOf(
                        PolicyPreviewJobStatus.PENDING,
                        PolicyPreviewJobStatus.RUNNING,
                    ))
            }
            .limit(capacity)
            .map { it[SchedulingPolicyPreviewJobs.id].value }
            .size >= capacity
    }

    /**
     * 완료 preview의 opaque activation token을 권위 policy scope 안에서 조회합니다.
     *
     * token만 먼저 조회한 뒤 tenant를 비교하면 애플리케이션 계층에 다른 tenant의 행이
     * materialize됩니다. 이 메서드는 [scope]의 `tenant_group_id`, `scope`,
     * `clinic_scope_key`를 token과 같은 SQL predicate에 넣어 그 경계를 저장소에서
     * fail-closed로 적용합니다. tenant baseline은 `clinic_scope_key = 0`, clinic
     * override는 양수 clinic ID를 사용하므로 지원 dialect에서 같은 equality 계약을
     * 유지합니다.
     *
     * token은 로그에 남기지 않으며 `COMPLETED` 행만 반환합니다. 호출자는 반환된 revision,
     * generation, definition도 현재 activation 입력과 다시 비교해야 합니다.
     *
     * @param scope Gateway 경로와 tenant/clinic 소유권 검증으로 확정한 policy scope입니다.
     * @param token 길이와 문자 집합이 제한된 opaque activation evidence token입니다.
     * @return 같은 scope에 속한 완료 preview입니다. 없거나 다른 scope이면 `null`입니다.
     */
    fun findCompletedPreviewByToken(
        scope: PolicyScopeRef,
        token: String,
    ): SchedulingPolicyPreviewJobRecord? {
        require(token.isNotBlank() && token.length <= MAX_EVIDENCE_TOKEN_LENGTH && OPAQUE_TOKEN_REGEX.matches(token)) {
            "token must contain bounded opaque safe characters"
        }
        return SchedulingPolicyPreviewJobs
            .selectAll()
            .where {
                (SchedulingPolicyPreviewJobs.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyPreviewJobs.scope eq scope.scope) and
                    (SchedulingPolicyPreviewJobs.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.COMPLETED) and
                    (SchedulingPolicyPreviewJobs.activationEvidenceToken eq token)
            }
            .singleOrNull()
            ?.toSchedulingPolicyPreviewJobRecord()
    }

    /**
     * 실행 가능한 미리보기 작업을 선점하거나 만료된 실행 lease를 재선점합니다.
     *
     * [leaseUntil]은 반드시 [now]보다 이후여야 합니다. 실행 가능한 행은
     * `nextAttemptAt <= now`인 `PENDING` 행 또는 기존 `leaseUntil <= now`인 `RUNNING`
     * 행입니다. hard deadline이 지난 행도 의도적으로 claim할 수 있습니다. worker가 현재
     * owner 권한으로 `FAILED(PREVIEW_DEADLINE_EXCEEDED)`를 기록해야 runnable queue에서
     * 제거되기 때문입니다. 조건부 갱신에 성공하면 호출자 트랜잭션 안에서 `RUNNING`,
     * [owner], [leaseUntil], 전이 시각을 기록합니다.
     *
     * @return 이 호출자가 조건부 갱신에서 이긴 경우에만 `true`입니다. `false`는 행 부재,
     * 아직 실행 시각 전, 유효한 lease 존재, 종결 상태, 또는 동시 선점 패배를
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
                    (SchedulingPolicyPreviewJobs.nextAttemptAt lessEq now)
                ) or
                (
                    (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                        (SchedulingPolicyPreviewJobs.leaseUntil lessEq now)
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
     * 현재 데이터베이스 시각까지 실행 가능한 activation command ID를 제한된 순서로 조회합니다.
     *
     * 조회 결과는 `nextAttemptAt`, `id` 오름차순이며 payload나 actor metadata를 읽지 않습니다.
     * 아직 유효한 lease는 제외하고 만료된 `CLAIMED` 행만 회수 후보에 포함합니다. 반환된 ID는
     * 실행 권한이 아니므로 worker는 각 ID를 별도 트랜잭션에서 [claimDueActivation]해야 합니다.
     *
     * @param now 동일 트랜잭션에서 얻은 데이터베이스 현재 시각입니다.
     * @param limit 한 tick이 관찰할 수 있는 최대 ID 수입니다. `1..100`만 허용합니다.
     */
    fun findDueActivationCommandIds(now: Instant, limit: Int): List<Long> {
        require(limit in 1..MAX_DUE_SELECTION_LIMIT) {
            "limit must be in 1..$MAX_DUE_SELECTION_LIMIT"
        }
        val eligible =
            (
                (SchedulingPolicyActivationCommands.status inList ACTIVATION_READY_STATES) and
                    (SchedulingPolicyActivationCommands.nextAttemptAt lessEq now)
                ) or
                (
                    (SchedulingPolicyActivationCommands.status eq PolicyActivationCommandStatus.CLAIMED) and
                        (SchedulingPolicyActivationCommands.leaseUntil lessEq now)
                    )
        return SchedulingPolicyActivationCommands
            .select(SchedulingPolicyActivationCommands.id)
            .where { eligible }
            .orderBy(
                SchedulingPolicyActivationCommands.nextAttemptAt to SortOrder.ASC,
                SchedulingPolicyActivationCommands.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[SchedulingPolicyActivationCommands.id].value }
    }

    /**
     * 현재 데이터베이스 시각까지 실행 가능한 preview job ID를 제한된 순서로 조회합니다.
     *
     * hard deadline이 지난 행도 선택합니다. 선택과 claim 자체는 activation evidence가
     * 아니며, worker가 owner-fenced claim 후 서비스의 첫 page boundary에서
     * `FAILED(PREVIEW_DEADLINE_EXCEEDED)`로 정리해야 queue capacity가 누수되지 않습니다.
     *
     * @param now 동일 트랜잭션에서 얻은 데이터베이스 현재 시각입니다.
     * @param limit 한 tick이 관찰할 수 있는 최대 ID 수입니다. `1..100`만 허용합니다.
     */
    fun findDuePreviewJobIds(now: Instant, limit: Int): List<Long> {
        require(limit in 1..MAX_DUE_SELECTION_LIMIT) {
            "limit must be in 1..$MAX_DUE_SELECTION_LIMIT"
        }
        val eligible =
            (
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.PENDING) and
                    (SchedulingPolicyPreviewJobs.nextAttemptAt lessEq now)
                ) or
                (
                    (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                        (SchedulingPolicyPreviewJobs.leaseUntil lessEq now)
                    )
        return SchedulingPolicyPreviewJobs
            .select(SchedulingPolicyPreviewJobs.id)
            .where { eligible }
            .orderBy(
                SchedulingPolicyPreviewJobs.nextAttemptAt to SortOrder.ASC,
                SchedulingPolicyPreviewJobs.id to SortOrder.ASC,
            )
            .limit(limit)
            .map { it[SchedulingPolicyPreviewJobs.id].value }
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

    /**
     * 복합 impact keyset cursor와 progress를 현재 lease에 fencing해 저장합니다.
     *
     * [checkpointedAt]이 lease 만료보다 앞선 경우에만 갱신합니다. aggregate partition,
     * scheduled instant, aggregate ID 순서는 뒤로 이동할 수 없고, 모든 cursor 구성요소를
     * 한 UPDATE에서 함께 저장해 재시작 시 혼합 cursor를 만들지 않습니다.
     */
    fun checkpointImpactPreview(
        jobId: Long,
        owner: String,
        cursor: PolicyImpactCursor,
        progress: PolicyPreviewProgress,
        checkpointedAt: Instant,
    ): Boolean {
        val current = requireCurrentPreviewOwner(jobId, owner, progress, checkpointedAt) ?: return false
        validateImpactCursorForward(current, cursor)
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                (SchedulingPolicyPreviewJobs.leaseOwner eq owner) and
                (SchedulingPolicyPreviewJobs.leaseUntil greater checkpointedAt)
        }) {
            it[cursorPartition] = cursor.aggregateType.ordinal
            it[cursorLastAppointmentId] = cursor.aggregateId.toLong()
            it[cursorClinicId] = cursor.clinicId
            it[cursorScheduledAt] = cursor.scheduledAt
            it[cursorAggregateType] = cursor.aggregateType.name
            it[cursorAggregateId] = cursor.aggregateId
            it[scannedCount] = progress.scannedCount
            it[affectedCount] = progress.affectedCount
            it[updatedAt] = checkpointedAt
        } == 1
    }

    /**
     * 동기 예산을 소진한 preview의 cursor를 저장하고 lease를 해제해 PENDING으로 되돌립니다.
     *
     * partial progress는 재개용일 뿐 activation evidence가 아니므로 result hash와 token은
     * 반드시 `null`로 유지합니다. 만료된 owner는 defer할 수 없습니다.
     */
    fun deferPreview(
        jobId: Long,
        owner: String,
        cursor: PolicyImpactCursor,
        progress: PolicyPreviewProgress,
        nextAttemptAt: Instant,
        deferredAt: Instant,
    ): Boolean {
        val current = requireCurrentPreviewOwner(jobId, owner, progress, deferredAt) ?: return false
        validateImpactCursorForward(current, cursor)
        require(nextAttemptAt >= deferredAt) { "nextAttemptAt must not precede deferredAt" }
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                (SchedulingPolicyPreviewJobs.leaseOwner eq owner) and
                (SchedulingPolicyPreviewJobs.leaseUntil greater deferredAt)
        }) {
            it[status] = PolicyPreviewJobStatus.PENDING
            it[cursorPartition] = cursor.aggregateType.ordinal
            it[cursorLastAppointmentId] = cursor.aggregateId.toLong()
            it[cursorClinicId] = cursor.clinicId
            it[cursorScheduledAt] = cursor.scheduledAt
            it[cursorAggregateType] = cursor.aggregateType.name
            it[cursorAggregateId] = cursor.aggregateId
            it[scannedCount] = progress.scannedCount
            it[affectedCount] = progress.affectedCount
            it[SchedulingPolicyPreviewJobs.nextAttemptAt] = nextAttemptAt
            it[resultHash] = null
            it[activationEvidenceToken] = null
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = null
            it[updatedAt] = deferredAt
        } == 1
    }

    /**
     * 현재 lease 소유자의 전체 preview 결과와 activation 증적을 원자적으로 확정합니다.
     *
     * 결과 hash와 token은 같은 revision·generation에 대한 전체 scan이 끝난 경우에만
     * 기록합니다. lease가 만료됐거나 다른 owner가 먼저 종결한 경우 `false`를 반환하며,
     * 기존 terminal evidence를 덮어쓰지 않습니다.
     */
    fun completePreview(
        jobId: Long,
        owner: String,
        resultHash: String,
        activationEvidenceToken: String,
        progress: PolicyPreviewProgress? = null,
        completedAt: Instant,
    ): Boolean {
        require(jobId > 0) { "jobId must be positive" }
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(SHA256_REGEX.matches(resultHash)) { "resultHash must be lowercase SHA-256" }
        require(
            activationEvidenceToken.isNotBlank() &&
                activationEvidenceToken.length <= MAX_EVIDENCE_TOKEN_LENGTH &&
                OPAQUE_TOKEN_REGEX.matches(activationEvidenceToken)
        ) {
            "activationEvidenceToken must contain bounded opaque safe characters"
        }
        progress?.let(::validateProgress)
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                (SchedulingPolicyPreviewJobs.leaseOwner eq owner) and
                (SchedulingPolicyPreviewJobs.leaseUntil greater completedAt)
        }) {
            it[status] = PolicyPreviewJobStatus.COMPLETED
            progress?.let { completedProgress ->
                it[scannedCount] = completedProgress.scannedCount
                it[affectedCount] = completedProgress.affectedCount
            }
            it[SchedulingPolicyPreviewJobs.resultHash] = resultHash
            it[SchedulingPolicyPreviewJobs.activationEvidenceToken] = activationEvidenceToken
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = null
            it[updatedAt] = completedAt
        } == 1
    }

    private fun requireCurrentPreviewOwner(
        jobId: Long,
        owner: String,
        progress: PolicyPreviewProgress,
        at: Instant,
    ): SchedulingPolicyPreviewJobRecord? {
        require(jobId > 0) { "jobId must be positive" }
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        validateProgress(progress)
        val current = findPreviewJob(jobId) ?: return null
        if (current.status != PolicyPreviewJobStatus.RUNNING ||
            current.leaseOwner != owner ||
            current.leaseUntil?.let { it > at } != true
        ) {
            return null
        }
        require(progress.scannedCount >= current.scannedCount) { "scannedCount cannot decrease" }
        require(progress.affectedCount >= current.affectedCount) { "affectedCount cannot decrease" }
        return current
    }

    private fun validateProgress(progress: PolicyPreviewProgress) {
        require(progress.scannedCount >= 0) { "scannedCount must be non-negative" }
        require(progress.affectedCount in 0..progress.scannedCount) {
            "affectedCount must be between zero and scannedCount"
        }
    }

    private fun validateImpactCursorForward(
        current: SchedulingPolicyPreviewJobRecord,
        cursor: PolicyImpactCursor,
    ) {
        val cursorId = cursor.aggregateId.toLongOrNull()
        require(cursorId != null && cursorId > 0) { "aggregateId must be a positive database identifier" }
        val currentType = current.cursorAggregateType?.let(PolicyImpactAggregateType::valueOf)
        if (currentType != null) {
            val currentClinicId = requireNotNull(current.cursorClinicId)
            val currentScheduledAt = requireNotNull(current.cursorScheduledAt)
            val currentId = requireNotNull(current.cursorAggregateId).toLong()
            val forward = when {
                cursor.clinicId > currentClinicId -> true
                cursor.clinicId < currentClinicId -> false
                cursor.aggregateType.ordinal > currentType.ordinal -> true
                cursor.aggregateType.ordinal < currentType.ordinal -> false
                cursor.scheduledAt > currentScheduledAt -> true
                cursor.scheduledAt < currentScheduledAt -> false
                else -> cursorId >= currentId
            }
            require(forward) { "impact cursor cannot move backward" }
        }
    }

    /**
     * 현재 lease 소유자의 미완료 preview를 증적 없는 terminal 상태로 전이합니다.
     *
     * `STALE`, `FAILED`, `CANCELLED`만 허용하며 result hash와 activation token을 항상
     * 지웁니다. 따라서 이전 checkpoint의 부분 count는 운영 진단에 남더라도 활성화 근거로
     * 사용할 수 없습니다. 이미 terminal인 행과 만료된 owner는 변경하지 않습니다.
     */
    fun markPreviewTerminal(
        jobId: Long,
        owner: String,
        status: PolicyPreviewJobStatus,
        errorCode: String,
        completedAt: Instant,
    ): Boolean {
        require(jobId > 0) { "jobId must be positive" }
        require(owner.isNotBlank() && owner.length <= MAX_OWNER_LENGTH) {
            "owner must contain 1..$MAX_OWNER_LENGTH characters"
        }
        require(status in PREVIEW_FAILURE_TERMINAL_STATES) {
            "status must be STALE, FAILED, or CANCELLED"
        }
        require(STABLE_ERROR_CODE_REGEX.matches(errorCode)) {
            "errorCode must contain bounded uppercase safe characters"
        }
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and
                (SchedulingPolicyPreviewJobs.status eq PolicyPreviewJobStatus.RUNNING) and
                (SchedulingPolicyPreviewJobs.leaseOwner eq owner) and
                (SchedulingPolicyPreviewJobs.leaseUntil greater completedAt)
        }) {
            it[SchedulingPolicyPreviewJobs.status] = status
            it[resultHash] = null
            it[activationEvidenceToken] = null
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = errorCode
            it[updatedAt] = completedAt
        } == 1
    }

    /**
     * 아직 완료되지 않은 preview를 명시적으로 취소하고 모든 실행 권한을 회수합니다.
     *
     * `PENDING`과 `RUNNING`에서만 전이되며, 현재 lease owner와 무관하게 병원 운영자의
     * 취소 결정을 우선합니다. 실행 중 runnable은 다음 page boundary의 current-row 확인에서
     * `CANCELLED`를 관측하고 더 이상 checkpoint 또는 완료를 기록할 수 없습니다. partial
     * hash와 activation token은 항상 제거합니다.
     *
     * @return 취소 가능한 행 하나를 전이했으면 `true`; terminal 또는 없는 행이면 `false`.
     */
    fun cancelPreview(
        jobId: Long,
        cancelledAt: Instant,
    ): Boolean {
        require(jobId > 0) { "jobId must be positive" }
        return SchedulingPolicyPreviewJobs.update({
            (SchedulingPolicyPreviewJobs.id eq jobId) and
                (SchedulingPolicyPreviewJobs.status inList listOf(
                    PolicyPreviewJobStatus.PENDING,
                    PolicyPreviewJobStatus.RUNNING,
                ))
        }) {
            it[status] = PolicyPreviewJobStatus.CANCELLED
            it[resultHash] = null
            it[activationEvidenceToken] = null
            it[leaseOwner] = null
            it[leaseUntil] = null
            it[lastErrorCode] = PREVIEW_CANCELLED_CODE
            it[updatedAt] = cancelledAt
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
        require(record.expectedTenantGeneration >= 0) { "expectedTenantGeneration must be non-negative" }
        require(record.expectedClinicGeneration >= 0) { "expectedClinicGeneration must be non-negative" }
        require(
            record.previewEvidenceToken.isNotBlank() &&
                record.previewEvidenceToken.length <= MAX_EVENT_ID_LENGTH
        ) {
            "previewEvidenceToken must contain 1..$MAX_EVENT_ID_LENGTH characters"
        }
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
        when (record.scope) {
            PolicyScope.TENANT_DEFAULT -> {
                require(record.clinicId == null && record.clinicScopeKey == 0L) {
                    "tenant preview requires null clinicId and clinicScopeKey zero"
                }
                require(record.clinicGeneration == 0L) {
                    "tenant preview clinicGeneration must be zero"
                }
                require(record.clinicGenerationDigest?.let(SHA256_REGEX::matches) == true) {
                    "tenant preview requires a lowercase clinic generation SHA-256"
                }
            }
            PolicyScope.CLINIC_OVERRIDE -> {
                require(record.clinicId != null && record.clinicId > 0L) {
                    "clinic preview requires positive clinicId"
                }
                require(record.clinicScopeKey == record.clinicId) {
                    "clinicScopeKey must match clinicId"
                }
                require(record.clinicGenerationDigest == null) {
                    "clinic preview must not contain a tenant clinic generation digest"
                }
            }
        }
        require(record.definitionId > 0) { "definitionId must be positive" }
        require(record.draftRevision > 0) { "draftRevision must be positive" }
        require(record.tenantGeneration >= 0) { "tenantGeneration must be non-negative" }
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
        require(record.horizonUntil > record.horizonFrom) {
            "horizonUntil must be later than horizonFrom"
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
                record.cursorClinicId == null &&
                record.cursorScheduledAt == null &&
                record.cursorAggregateType == null &&
                record.cursorAggregateId == null &&
                record.scannedCount == 0L &&
                record.affectedCount == 0L &&
                record.resultHash == null &&
                record.activationEvidenceToken == null &&
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
        const val MAX_EVIDENCE_TOKEN_LENGTH = 192
        const val MAX_DUE_SELECTION_LIMIT = 100
        const val MAX_PREVIEW_QUEUE_CAPACITY = 100
        const val MAX_ERROR_CODE_LENGTH = 96
        const val PREVIEW_CANCELLED_CODE = "PREVIEW_CANCELLED"
        val IDEMPOTENCY_KEY_REGEX = Regex("[A-Za-z0-9._:/-]{1,128}")
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
        val STABLE_ERROR_CODE_REGEX = Regex("[A-Z][A-Z0-9_]{0,${MAX_ERROR_CODE_LENGTH - 1}}")
        val OPAQUE_TOKEN_REGEX = Regex("[A-Za-z0-9._~:/+=-]{1,$MAX_EVIDENCE_TOKEN_LENGTH}")
        val ACTIVATION_READY_STATES = listOf(
            PolicyActivationCommandStatus.PENDING,
            PolicyActivationCommandStatus.RETRY_WAIT,
        )
        val PREVIEW_FAILURE_TERMINAL_STATES = setOf(
            PolicyPreviewJobStatus.STALE,
            PolicyPreviewJobStatus.FAILED,
            PolicyPreviewJobStatus.CANCELLED,
        )
    }
}

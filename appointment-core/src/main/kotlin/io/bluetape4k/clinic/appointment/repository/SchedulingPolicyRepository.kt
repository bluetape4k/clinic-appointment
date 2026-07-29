package io.bluetape4k.clinic.appointment.repository

import io.bluetape4k.clinic.appointment.model.dto.EffectiveSchedulingPolicySnapshotRecord
import io.bluetape4k.clinic.appointment.model.dto.PolicyScopeRef
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyApprovalRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyDefinitionRecord
import io.bluetape4k.clinic.appointment.model.dto.SchedulingPolicyScopeHeadRecord
import io.bluetape4k.clinic.appointment.model.policy.PolicyLifecycle
import io.bluetape4k.clinic.appointment.model.policy.PolicyScope
import io.bluetape4k.clinic.appointment.model.policy.SchedulingPolicyKind
import io.bluetape4k.clinic.appointment.model.tables.EffectiveSchedulingPolicySnapshots
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyApprovals
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyDefinitions
import io.bluetape4k.clinic.appointment.model.tables.SchedulingPolicyScopeHeads
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import java.security.MessageDigest
import java.time.Instant

/**
 * 예약 정책을 저장하기 위한 호출자 트랜잭션 기반 영속성 프리미티브다.
 *
 * 모든 메서드는 호출자가 소유한 Exposed `transaction {}` 안에서 실행해야 한다. 활성화는
 * 정의 검증, 스코프 헤드 CAS, 스냅샷·outbox 기록, 명령 완료를 하나의 원자적 작업으로
 * 묶어야 하므로 이 클래스가 트랜잭션을 열지 않는다. 두 스코프를 함께 잠그는 호출자는 잠금
 * 순서 역전을 막기 위해 항상 테넌트 다음 병원 순서로 잠그는 [lockScopeHeads]를 사용해야 한다.
 */
class SchedulingPolicyRepository {

    /**
     * 데이터베이스 방언에 공통으로 적용되는 스코프 키를 검증한 뒤 정책 정의 하나를 삽입한다.
     *
     * 정의의 유일성은 테넌트, 스코프, non-null 병원 sentinel, 정책 종류, 버전의 조합이다.
     * 공개한 payload 바이트는 제자리 수정하지 않고 반드시 새 버전으로 표현해야 한다.
     */
    fun createDefinition(record: SchedulingPolicyDefinitionRecord): SchedulingPolicyDefinitionRecord {
        validateDefinitionRecord(record)
        val definitionId = SchedulingPolicyDefinitions.insertAndGetId {
            it[tenantGroupId] = record.tenantGroupId
            it[scope] = record.scope
            it[clinicId] = record.clinicId
            it[clinicScopeKey] = record.clinicScopeKey
            it[policyKind] = record.kind
            it[version] = record.version
            it[schemaVersion] = record.schemaVersion
            it[lifecycle] = record.lifecycle
            it[effectiveFrom] = record.effectiveFrom
            it[effectiveUntil] = record.effectiveUntil
            it[revision] = record.revision
            it[payloadHash] = record.payloadHash
            it[payloadJson] = record.payloadJson
            it[createdByActorId] = record.createdByActorId
            it[createdByActorRole] = record.createdByActorRole
            it[changeReason] = record.changeReason
        }.value
        return findDefinition(definitionId)
            ?: error("Inserted policy definition $definitionId was not readable")
    }

    /**
     * 호출자 소유 트랜잭션에서 보이는 정의를 반환한다.
     *
     * @param definitionId 양수 데이터베이스 식별자.
     * @return 불변 정의. 현재 트랜잭션에서 행이 보이지 않으면 `null`이다. 조회 결과가 없다는
     * 사실만으로 현재 행위자에게 권한이 없다고 판단하면 안 된다.
     */
    fun findDefinition(definitionId: Long): SchedulingPolicyDefinitionRecord? =
        SchedulingPolicyDefinitions
            .selectAll()
            .where { SchedulingPolicyDefinitions.id eq definitionId }
            .singleOrNull()
            ?.toSchedulingPolicyDefinitionRecord()

    /**
     * 하나의 스코프와 정책 종류 안에서 다음 양수 정의 버전을 반환한다.
     *
     * 전역적으로 안전한 sequence가 아니라 스코프 잠금을 전제로 한 할당 도우미다. 호출자는 새
     * 정의와 대응하는 스코프 revision이 커밋될 때까지 [scope]의 [lockScopeHead] 잠금을
     * 유지해야 한다. 잠금이 없으면 두 생성자가 같은 값을 읽고 한쪽이 유일성 제약에서 실패한다.
     *
     * @return 기존 정의가 없으면 `1`, 있으면 영속화된 최대 버전에 1을 더한 값. 퇴역한 정의도
     * 버전 계보에 계속 포함한다.
     */
    fun nextDefinitionVersion(
        scope: PolicyScopeRef,
        kind: SchedulingPolicyKind,
    ): Long =
        SchedulingPolicyDefinitions
            .selectAll()
            .where {
                (SchedulingPolicyDefinitions.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyDefinitions.scope eq scope.scope) and
                    (SchedulingPolicyDefinitions.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyDefinitions.policyKind eq kind)
            }
            .orderBy(SchedulingPolicyDefinitions.version, SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(SchedulingPolicyDefinitions.version)
            ?.plus(1L)
            ?: 1L

    /**
     * 호출자가 [expectedRevision]을 계속 소유할 때만 편집 가능한 초안 내용을 교체한다.
     *
     * 정의 식별자, 버전, 스코프, 종류, 생성자 감사정보는 불변이다. CAS 성공 시 revision을
     * 정확히 한 번 증가시켜 이전 revision에 묶인 승인과 미리보기 증거를 무효화하되 감사 행은
     * 삭제하지 않는다. 이 메서드는 트랜잭션을 열거나 커밋하지 않으며 호출자가 주변 Exposed
     * `transaction {}`과 관련 검증·outbox 작업을 소유한다.
     *
     * @param definitionId 양수 초안 식별자.
     * @param expectedRevision 호출자가 읽은 양수 revision.
     * @param schemaVersion 폐쇄형 payload의 양수 schema 버전.
     * @param effectiveFrom 정책 적용 구간의 포함 UTC 시작 시각.
     * @param effectiveUntil 정책 적용 구간의 제외 UTC 종료 시각. 상한이 없으면 `null`.
     * @param payloadHash 정규 payload 바이트의 소문자 SHA-256.
     * @param payloadJson 최대 256 KiB UTF-8인 정규 payload JSON.
     * @param changeReason 비밀정보를 포함하지 않는 1..1000자 운영자 변경 사유.
     * @return 수정된 새 초안 revision. 정의가 없거나 더 이상 `DRAFT`가 아니거나 revision이
     * 오래되었으면 `null`.
     */
    @Suppress("LongParameterList")
    fun compareAndReviseDraft(
        definitionId: Long,
        expectedRevision: Long,
        schemaVersion: Int,
        effectiveFrom: Instant,
        effectiveUntil: Instant?,
        payloadHash: String,
        payloadJson: String,
        changeReason: String,
    ): SchedulingPolicyDefinitionRecord? {
        require(definitionId > 0) { "definitionId must be positive" }
        require(expectedRevision > 0) { "expectedRevision must be positive" }
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(effectiveUntil == null || effectiveUntil > effectiveFrom) {
            "effectiveUntil must be later than effectiveFrom"
        }
        require(SHA256_REGEX.matches(payloadHash)) { "payloadHash must be lowercase SHA-256" }
        require(payloadJson.toByteArray().size <= MAX_JSON_BYTES) {
            "payloadJson exceeds $MAX_JSON_BYTES bytes"
        }
        require(changeReason.isNotBlank() && changeReason.length <= 1000) {
            "changeReason must contain 1..1000 characters"
        }
        val affected = SchedulingPolicyDefinitions.update({
            (SchedulingPolicyDefinitions.id eq definitionId) and
                (SchedulingPolicyDefinitions.lifecycle eq PolicyLifecycle.DRAFT) and
                (SchedulingPolicyDefinitions.revision eq expectedRevision)
        }) {
            it[SchedulingPolicyDefinitions.schemaVersion] = schemaVersion
            it[SchedulingPolicyDefinitions.effectiveFrom] = effectiveFrom
            it[SchedulingPolicyDefinitions.effectiveUntil] = effectiveUntil
            it[SchedulingPolicyDefinitions.revision] = expectedRevision + 1
            it[SchedulingPolicyDefinitions.payloadHash] = payloadHash
            it[SchedulingPolicyDefinitions.payloadJson] = payloadJson
            it[SchedulingPolicyDefinitions.changeReason] = changeReason
        }
        return if (affected == 1) findDefinition(definitionId) else null
    }

    /**
     * revision과 현재 상태가 모두 일치할 때만 수명 주기를 변경한다.
     *
     * 허용 전이는 `DRAFT -> SCHEDULED|ACTIVE|RETIRED`,
     * `SCHEDULED -> ACTIVE|RETIRED`, `ACTIVE -> RETIRED`다. 퇴역 경로에서도 정의,
     * 승인, 명령, 스냅샷, outbox 행을 보존한다. 활성 정의를 공개하거나 교체하는 호출자는
     * 일치하는 스코프 헤드 잠금을 유지해야 하며, 이 프리미티브는 자체적으로 잠금을 획득하거나
     * 세대를 증가시키지 않는다.
     *
     * @return 전이된 정의. 행이 없거나 revision이 오래되었거나 현재 상태가 다르면 `null`.
     * 지원하지 않는 전이 조합은 프로그래밍 오류로 SQL 실행 전에 실패한다.
     */
    fun compareAndTransitionLifecycle(
        definitionId: Long,
        expectedRevision: Long,
        expectedLifecycle: PolicyLifecycle,
        targetLifecycle: PolicyLifecycle,
    ): SchedulingPolicyDefinitionRecord? {
        require(definitionId > 0) { "definitionId must be positive" }
        require(expectedRevision > 0) { "expectedRevision must be positive" }
        require((expectedLifecycle to targetLifecycle) in ALLOWED_LIFECYCLE_TRANSITIONS) {
            "Unsupported policy lifecycle transition: $expectedLifecycle -> $targetLifecycle"
        }
        val affected = SchedulingPolicyDefinitions.update({
            (SchedulingPolicyDefinitions.id eq definitionId) and
                (SchedulingPolicyDefinitions.revision eq expectedRevision) and
                (SchedulingPolicyDefinitions.lifecycle eq expectedLifecycle)
        }) {
            it[lifecycle] = targetLifecycle
        }
        return if (affected == 1) findDefinition(definitionId) else null
    }

    /**
     * 정확한 초안 revision과 행위자 한 명에 대한 승인 증거를 추가한다.
     *
     * 같은 revision에 같은 행위자가 중복 승인하면 데이터베이스 유일성 제약으로 거부한다.
     * 이전 revision의 승인은 감사 목적으로 조회할 수 있지만 현재 승인으로 사용할 수 없다.
     */
    fun addApproval(record: SchedulingPolicyApprovalRecord): SchedulingPolicyApprovalRecord {
        require(record.definitionId > 0) { "definitionId must be positive" }
        require(record.draftRevision > 0) { "draftRevision must be positive" }
        require(record.actorId.isNotBlank() && record.actorId.length <= 160) {
            "actorId must contain 1..160 characters"
        }
        require(record.assuranceLevel.isNotBlank() && record.assuranceLevel.length <= 64) {
            "assuranceLevel must contain 1..64 characters"
        }
        val approvalId = SchedulingPolicyApprovals.insertAndGetId {
            it[definitionId] = record.definitionId
            it[draftRevision] = record.draftRevision
            it[actorId] = record.actorId
            it[actorRole] = record.actorRole
            it[assuranceLevel] = record.assuranceLevel
            it[approvedAt] = record.approvedAt
        }.value
        return SchedulingPolicyApprovals
            .selectAll()
            .where { SchedulingPolicyApprovals.id eq approvalId }
            .single()
            .toSchedulingPolicyApprovalRecord()
    }

    /**
     * 정확한 초안 revision 하나에 대해 현재 트랜잭션에서 보이는 승인 증거를 반환한다.
     *
     * @param definitionId 양수 정의 식별자.
     * @param draftRevision 정의가 동일 revision에 머무는 동안에만 유효한 양수 증거 revision.
     * @return 안정된 삽입 순서의 증거 목록. 보이는 증거가 없으면 빈 목록이다. 빈 목록만으로
     * 행 부재와 권한 부족을 구분하지 않으며 그 판단은 명령 계층이 소유한다.
     */
    fun findApprovals(
        definitionId: Long,
        draftRevision: Long,
    ): List<SchedulingPolicyApprovalRecord> =
        SchedulingPolicyApprovals
            .selectAll()
            .where {
                (SchedulingPolicyApprovals.definitionId eq definitionId) and
                    (SchedulingPolicyApprovals.draftRevision eq draftRevision)
            }
            .orderBy(SchedulingPolicyApprovals.id, SortOrder.ASC)
            .map { it.toSchedulingPolicyApprovalRecord() }

    /**
     * 스코프 직렬화 행을 초기화하고 잠근다.
     *
     * `insertIgnore`로 최초 접근 경쟁을 안전하게 처리한다. 이어서 획득한 `FOR UPDATE`
     * 잠금은 중첩 구간, 세대, 스냅샷, 명령 결과, outbox 쓰기가 모두 끝날 때까지 호출자
     * 트랜잭션이 유지해야 한다.
     */
    fun lockScopeHead(scope: PolicyScopeRef): SchedulingPolicyScopeHeadRecord {
        bootstrapScopeHead(scope)
        return SchedulingPolicyScopeHeads
            .selectAll()
            .where { scopeHeadPredicate(scope) }
            .forUpdate()
            .single()
            .toSchedulingPolicyScopeHeadRecord()
    }

    /**
     * 서로 다른 스코프를 항상 테넌트 다음 병원 순서로 잠근다.
     *
     * 병원 스코프를 먼저 전달해도 실제 잠금 순서는 바뀌지 않는다. 중복 참조는 제거하여 같은
     * 행을 두 번 획득하지 않는다.
     */
    fun lockScopeHeads(vararg scopes: PolicyScopeRef): List<SchedulingPolicyScopeHeadRecord> =
        scopes
            .distinct()
            .sortedWith(compareBy<PolicyScopeRef>({ it.scope != PolicyScope.TENANT_DEFAULT }, { it.clinicScopeKey }))
            .map(::lockScopeHead)

    /**
     * 스코프 헤드를 새로 만들거나 잠그지 않고 읽는다.
     *
     * 캐시 조회 전에 수행하는 권위 최신성 조회다. 테넌트 헤드가 없으면 완전한 유효 테넌트
     * 기본 정책이 아직 존재하지 않는다는 뜻이다. 병원별 재정의는 선택 사항이므로 병원 헤드가
     * 없으면 호출자가 세대 `0`으로 해석할 수 있다.
     *
     * @param scope 정확한 테넌트 또는 병원 정책 스코프.
     * @return 현재 스코프 헤드 레코드. 한 번도 초기화하지 않은 스코프면 `null`이다. 반환값은
     * 특정 시점의 관측값이며 이후 트랜잭션까지 동일함을 보장하지 않는다.
     */
    fun findScopeHead(scope: PolicyScopeRef): SchedulingPolicyScopeHeadRecord? =
        SchedulingPolicyScopeHeads
            .selectAll()
            .where { scopeHeadPredicate(scope) }
            .singleOrNull()
            ?.toSchedulingPolicyScopeHeadRecord()

    /**
     * 한 tenant의 clinic override 정책 세대를 단일 권위 행 조회로 요약한다.
     *
     * clinic override generation이 증가하는 트랜잭션은 tenant head의
     * [SchedulingPolicyScopeHeadRecord.clinicGenerationEpoch]도 함께 증가시킨다. 따라서
     * tenant preview의 매 page freshness 확인은 병원 수와 무관하게 tenant head 한 행만
     * 조회하면 된다. 병원·appointment inventory는 impact scan의 입력이지 정책 세대가
     * 아니므로 이 digest에 포함하지 않는다.
     *
     * tenant head가 아직 없으면 epoch `0`으로 계산하며 행을 생성하지 않는다. 모든 메서드와
     * 마찬가지로 호출자가 소유한 Exposed transaction 안에서 사용해야 한다.
     *
     * @param tenantGroupId 요약할 양수 tenant 경계.
     * @return 64자 lowercase SHA-256.
     */
    fun clinicGenerationDigest(tenantGroupId: Long): String {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        val tenantHead = findScopeHead(PolicyScopeRef(tenantGroupId, PolicyScope.TENANT_DEFAULT))
        return clinicGenerationDigest(tenantGroupId, tenantHead?.clinicGenerationEpoch ?: 0L)
    }

    /**
     * 이미 잠그거나 읽은 tenant head로 추가 SQL 없이 clinic generation digest를 계산한다.
     *
     * @throws IllegalArgumentException tenant head가 아니거나 tenant 경계가 유효하지 않을 때.
     */
    fun clinicGenerationDigest(tenantHead: SchedulingPolicyScopeHeadRecord): String {
        require(tenantHead.scope == PolicyScope.TENANT_DEFAULT) {
            "clinic generation digest requires a tenant scope head"
        }
        require(tenantHead.clinicScopeKey == 0L) {
            "tenant scope head clinicScopeKey must be zero"
        }
        return clinicGenerationDigest(tenantHead.tenantGroupId, tenantHead.clinicGenerationEpoch)
    }

    /**
     * 유효 정책 세대는 유지하면서 낙관적 스코프 revision만 증가시킨다.
     *
     * 초안 생성과 활성화 예약은 관리 상태를 바꾸지만 스케줄러가 선택하는 유효 정책은 바꾸지
     * 않는다. 따라서 [SchedulingPolicyScopeHeadRecord.generation]은 유지하고
     * [SchedulingPolicyScopeHeadRecord.revision]만 정확히 한 번 증가시킨다. 호출자는 관련
     * 쓰기가 끝날 때까지 일치하는 스코프 헤드 잠금을 이미 보유해야 한다.
     *
     * @throws PolicyScopeHeadConflictException [expectedRevision]이 오래되었을 때.
     */
    fun compareAndIncrementRevision(
        scope: PolicyScopeRef,
        expectedRevision: Long,
    ): SchedulingPolicyScopeHeadRecord {
        require(expectedRevision >= 0) { "expectedRevision must be non-negative" }
        val current = lockScopeHead(scope)
        if (current.revision != expectedRevision) {
            throw PolicyScopeHeadConflictException(scope, expectedRevision, current.revision)
        }
        val affected = SchedulingPolicyScopeHeads.update({
            scopeHeadPredicate(scope) and
                (SchedulingPolicyScopeHeads.revision eq expectedRevision)
        }) {
            it[revision] = current.revision + 1
            it.update(updatedAt, CurrentTimestamp)
        }
        if (affected != 1) {
            throw PolicyScopeHeadConflictException(scope, expectedRevision, current.revision)
        }
        return lockScopeHead(scope)
    }

    /**
     * [expectedRevision]이 현재 값일 때만 revision과 generation을 함께 증가시킨다.
     *
     * 먼저 스코프 행을 잠근다. revision이 다르면 [PolicyScopeHeadConflictException]을
     * 던지고 어떤 카운터도 변경하지 않는다.
     */
    fun compareAndIncrementGeneration(
        scope: PolicyScopeRef,
        expectedRevision: Long,
    ): SchedulingPolicyScopeHeadRecord {
        require(expectedRevision >= 0) { "expectedRevision must be non-negative" }
        val tenantScope = PolicyScopeRef(scope.tenantGroupId, PolicyScope.TENANT_DEFAULT)
        val lockedHeads = if (scope.scope == PolicyScope.CLINIC_OVERRIDE) {
            lockScopeHeads(tenantScope, scope)
        } else {
            listOf(lockScopeHead(scope))
        }
        val tenantHead = lockedHeads.first { it.scope == PolicyScope.TENANT_DEFAULT }
        val current = lockedHeads.first {
            it.scope == scope.scope && it.clinicScopeKey == scope.clinicScopeKey
        }
        if (current.revision != expectedRevision) {
            throw PolicyScopeHeadConflictException(scope, expectedRevision, current.revision)
        }
        val affected = SchedulingPolicyScopeHeads.update({
            scopeHeadPredicate(scope) and
                (SchedulingPolicyScopeHeads.revision eq expectedRevision)
        }) {
            it[revision] = current.revision + 1
            it[generation] = current.generation + 1
            it.update(updatedAt, CurrentTimestamp)
        }
        if (affected != 1) {
            throw PolicyScopeHeadConflictException(scope, expectedRevision, current.revision)
        }
        if (scope.scope == PolicyScope.CLINIC_OVERRIDE) {
            val tenantAffected = SchedulingPolicyScopeHeads.update({
                scopeHeadPredicate(tenantScope) and
                    (SchedulingPolicyScopeHeads.clinicGenerationEpoch eq tenantHead.clinicGenerationEpoch)
            }) {
                it[clinicGenerationEpoch] = tenantHead.clinicGenerationEpoch + 1
                it.update(updatedAt, CurrentTimestamp)
            }
            check(tenantAffected == 1) {
                "tenant clinic generation epoch update must affect exactly one scope head"
            }
        }
        return lockScopeHead(scope)
    }

    /**
     * 반개구간 유효 범위가 `[from, until)`과 겹치는 활성 또는 활성화 예정 정의를 찾는다.
     *
     * [until]이 `null`이면 조회 종료 상한이 없다. 호출자는 이 결과로 활성화 승자를 결정하기
     * 전에 일치하는 스코프 헤드 잠금을 보유해야 한다.
     */
    fun findOverlappingPublishedDefinitions(
        scope: PolicyScopeRef,
        kind: SchedulingPolicyKind,
        from: Instant,
        until: Instant?,
    ): List<SchedulingPolicyDefinitionRecord> {
        require(until == null || until > from) { "until must be later than from" }
        val startsBeforeQueryEnd = until?.let {
            SchedulingPolicyDefinitions.effectiveFrom less it
        } ?: Op.TRUE
        val endsAfterQueryStart =
            SchedulingPolicyDefinitions.effectiveUntil.isNull() or
                (SchedulingPolicyDefinitions.effectiveUntil greater from)
        return SchedulingPolicyDefinitions
            .selectAll()
            .where {
                (SchedulingPolicyDefinitions.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyDefinitions.scope eq scope.scope) and
                    (SchedulingPolicyDefinitions.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyDefinitions.policyKind eq kind) and
                    (SchedulingPolicyDefinitions.lifecycle inList listOf(
                        PolicyLifecycle.SCHEDULED,
                        PolicyLifecycle.ACTIVE,
                    )) and
                    startsBeforeQueryEnd and
                    endsAfterQueryStart
            }
            .orderBy(SchedulingPolicyDefinitions.effectiveFrom, SortOrder.ASC)
            .map { it.toSchedulingPolicyDefinitionRecord() }
    }

    /**
     * 하나의 UTC 평가 시각에 적용되는 활성 정의 하나를 선택한다.
     *
     * `effectiveFrom <= evaluationAt < effectiveUntil`인 반개구간으로 적용 가능 여부를
     * 판단하며 종료 시각이 `null`이면 끝이 없는 구간이다. 활성화 트랜잭션이 스코프 세대를
     * 증가시키기 전까지 `SCHEDULED` 정의는 의도적으로 제외한다. 이 규칙은 미래 정의가
     * 승인·활성화를 우회하거나 아직 그 정의를 대표하지 않는 세대로 캐시되는 것을 막는다.
     *
     * @param scope 정확한 테넌트 또는 병원 정책 스코프.
     * @param kind 선택할 폐쇄형 정책 영역.
     * @param evaluationAt 정책 종류가 지정한 UTC 평가 시각.
     * @return 적용 가능한 활성 정의. 없으면 `null`이다.
     * @throws IllegalArgumentException 활성 정의가 둘 이상 적용되어 활성화 불변식이 깨졌을 때.
     */
    fun findActiveDefinitionAt(
        scope: PolicyScopeRef,
        kind: SchedulingPolicyKind,
        evaluationAt: Instant,
    ): SchedulingPolicyDefinitionRecord? =
        findActiveDefinitionsAt(scope, mapOf(kind to evaluationAt))[kind]

    /**
     * 여러 정책 종류의 활성 정의를 하나의 스코프 쿼리로 선택한다.
     *
     * 쿼리는 정확한 테넌트/스코프 키와 `ACTIVE` 수명 주기에 더해, 각 정책 종류를 그 종류가
     * 사용하는 평가 시각의 반개구간 predicate와 직접 결합한다. 예를 들어 의사결정 시각과
     * 시술 시각이 수개월 떨어져 있어도 그 사이의 관계없는 활성 이력을 JVM으로 가져오지
     * 않는다. 요청 종류 수는 폐쇄형 [SchedulingPolicyKind] 개수로 제한되므로 OR predicate
     * 크기도 고정 상한을 가진다. 메모리 재검사는 데이터베이스 이상으로 같은 종류·시각에
     * 둘 이상의 활성 행이 반환된 경우를 닫힌 실패로 검출하기 위한 방어선이다.
     *
     * @param scope 정확한 테넌트 또는 병원 정책 스코프.
     * @param evaluationAtByKind 요청한 정책 종류와 해당 종류의 의사결정 시점 또는 시술 시점
     * UTC 시각을 연결한 비어 있지 않은 맵.
     * @return 종류별 적용 가능한 활성 정의. 키가 없으면 그 종류의 평가 시각에 적용 가능한 활성
     * 정의가 없다는 뜻이다.
     * @throws IllegalArgumentException 요청 종류가 없거나 같은 종류에 적용 가능한 활성 행이 둘
     * 이상일 때.
     */
    fun findActiveDefinitionsAt(
        scope: PolicyScopeRef,
        evaluationAtByKind: Map<SchedulingPolicyKind, Instant>,
    ): Map<SchedulingPolicyKind, SchedulingPolicyDefinitionRecord> {
        require(evaluationAtByKind.isNotEmpty()) { "evaluationAtByKind must not be empty" }
        val exactEvaluationPredicate = evaluationAtByKind
            .map { (kind, evaluationAt) ->
                (SchedulingPolicyDefinitions.policyKind eq kind) and
                    (SchedulingPolicyDefinitions.effectiveFrom lessEq evaluationAt) and
                    (
                        SchedulingPolicyDefinitions.effectiveUntil.isNull() or
                            (SchedulingPolicyDefinitions.effectiveUntil greater evaluationAt)
                    )
            }
            .reduce { aggregate, predicate -> aggregate or predicate }
        val activeDefinitions = SchedulingPolicyDefinitions
            .selectAll()
            .where {
                (SchedulingPolicyDefinitions.tenantGroupId eq scope.tenantGroupId) and
                    (SchedulingPolicyDefinitions.scope eq scope.scope) and
                    (SchedulingPolicyDefinitions.clinicScopeKey eq scope.clinicScopeKey) and
                    (SchedulingPolicyDefinitions.lifecycle eq PolicyLifecycle.ACTIVE) and
                    exactEvaluationPredicate
            }
            .map { it.toSchedulingPolicyDefinitionRecord() }
        return evaluationAtByKind.mapNotNull { (kind, evaluationAt) ->
            val matches = activeDefinitions.filter { definition ->
                definition.kind == kind &&
                    !evaluationAt.isBefore(definition.effectiveFrom) &&
                    (definition.effectiveUntil == null || evaluationAt < definition.effectiveUntil)
            }
            require(matches.size <= 1) {
                "multiple active definitions exist for one policy scope, kind, and instant"
            }
            matches.singleOrNull()?.let { kind to it }
        }.toMap()
    }

    /**
     * 스코프 범위의 정규 해시를 기준으로 불변 스냅샷을 삽입하거나 기존 행을 재사용한다.
     *
     * 같은 해시가 이미 있으면 최초로 저장한 바이트와 세대 메타데이터를 유지한다. 이 규칙은
     * 재시도를 멱등하게 만들고 갱신 경로가 과거 예약 결정 증거를 다시 쓰는 것을 막는다.
     *
     * @param tenantGroupId 모든 원본 정의가 속한 양수 테넌트 경계.
     * @param clinicId 정책 결정을 컴파일한 양수 병원 식별자.
     * @param decisionAt 의사결정 정책을 평가한 UTC 시각.
     * @param serviceAt 시술 시점 정책을 평가한 UTC 시각. [decisionAt]보다 빠를 수 없다.
     * @param tenantGeneration 컴파일러가 재확인한 양수 테넌트 스코프 세대.
     * @param clinicGeneration 0 이상의 병원 스코프 세대. `0`은 병원 재정의 세대가 아직 한
     * 번도 활성화되지 않았다는 뜻이다.
     * @param sourceVersionsJson 정책 종류별 기여 정의 버전의 정규 JSON 맵.
     * @param sourceByPathJson 컴파일된 leaf 경로와 platform·tenant·clinic 원본을 연결한
     * 정규 JSON 맵.
     * @param disabledFeaturesJson 재정의가 명시적으로 비활성화한 경로의 정렬된 정규 JSON 배열.
     * @param warningsJson 고객에게 안전한 컴파일러 경고의 순서 있는 정규 JSON 배열.
     * @param payloadJson 예약 판단에 사용하는 정규 컴파일 정책 JSON. 행위자 인증정보나
     * 멱등 키를 포함하지 않는다.
     * @param snapshotHash 스코프, 시각, 세대, 원본 증거, 경고, 비활성 경로, payload를
     * 포함해 계산한 64자 소문자 SHA-256.
     * @return 같은 스코프 해시로 이미 존재하거나 새로 삽입한 불변 스냅샷.
     */
    @Suppress("LongParameterList")
    fun saveSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        tenantGeneration: Long,
        clinicGeneration: Long,
        sourceVersionsJson: String,
        sourceByPathJson: String,
        disabledFeaturesJson: String,
        warningsJson: String,
        payloadJson: String,
        snapshotHash: String,
    ): EffectiveSchedulingPolicySnapshotRecord {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(serviceAt >= decisionAt) { "serviceAt must not precede decisionAt" }
        require(tenantGeneration > 0) { "tenantGeneration must be positive" }
        require(clinicGeneration >= 0) { "clinicGeneration must be non-negative" }
        require(SHA256_REGEX.matches(snapshotHash)) { "snapshotHash must be lowercase SHA-256" }
        findSnapshot(tenantGroupId, clinicId, snapshotHash)?.let { return it }
        insertSnapshot(
            tenantGroupId,
            clinicId,
            decisionAt,
            serviceAt,
            tenantGeneration,
            clinicGeneration,
            sourceVersionsJson,
            sourceByPathJson,
            disabledFeaturesJson,
            warningsJson,
            payloadJson,
            snapshotHash,
        )
        return requireNotNull(findSnapshot(tenantGroupId, clinicId, snapshotHash)) {
            "Snapshot insert did not produce a readable row"
        }
    }

    /**
     * 정확한 스코프 해시로 현재 트랜잭션에서 보이는 불변 스냅샷 하나를 반환한다.
     *
     * @param tenantGroupId 양수 테넌트 경계.
     * @param clinicId 양수 병원 경계.
     * @param snapshotHash 64자 소문자 정규 스냅샷 SHA-256.
     * @return 스냅샷. 호출자 소유 트랜잭션에서 행이 보이지 않으면 `null`이다. `null`은
     * 권한 판정 결과가 아니다.
     */
    fun findSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        snapshotHash: String,
    ): EffectiveSchedulingPolicySnapshotRecord? =
        EffectiveSchedulingPolicySnapshots
            .selectAll()
            .where {
                (EffectiveSchedulingPolicySnapshots.tenantGroupId eq tenantGroupId) and
                    (EffectiveSchedulingPolicySnapshots.clinicId eq clinicId) and
                    (EffectiveSchedulingPolicySnapshots.snapshotHash eq snapshotHash)
            }
            .singleOrNull()
            ?.toEffectiveSchedulingPolicySnapshotRecord()

    /**
     * proposal에 고정된 양수 snapshot ID를 정확한 tenant·clinic 범위에서 조회한다.
     *
     * 현재 유효 정책을 다시 계산하지 않고 과거 proposal이 참조한 불변 정책 hash를 검증할
     * 때 사용한다. 다른 tenant 또는 clinic의 같은 ID는 보이지 않아야 하므로 식별자만으로
     * 조회하지 않는다.
     *
     * @param tenantGroupId proposal commitment가 속한 양수 tenant 경계.
     * @param clinicId proposal commitment가 속한 양수 clinic 경계.
     * @param snapshotId proposal에 영속화된 양수 정책 snapshot 식별자.
     * @return 정확한 세 필드가 모두 일치하는 불변 snapshot 또는 `null`.
     */
    fun findSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        snapshotId: Long,
    ): EffectiveSchedulingPolicySnapshotRecord? {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicId > 0) { "clinicId must be positive" }
        require(snapshotId > 0) { "snapshotId must be positive" }
        return EffectiveSchedulingPolicySnapshots
            .selectAll()
            .where {
                (EffectiveSchedulingPolicySnapshots.id eq snapshotId) and
                    (EffectiveSchedulingPolicySnapshots.tenantGroupId eq tenantGroupId) and
                    (EffectiveSchedulingPolicySnapshots.clinicId eq clinicId)
            }
            .singleOrNull()
            ?.toEffectiveSchedulingPolicySnapshotRecord()
    }

    private fun scopeHeadPredicate(scope: PolicyScopeRef): Op<Boolean> =
        (SchedulingPolicyScopeHeads.tenantGroupId eq scope.tenantGroupId) and
            (SchedulingPolicyScopeHeads.scope eq scope.scope) and
            (SchedulingPolicyScopeHeads.clinicScopeKey eq scope.clinicScopeKey)

    private fun bootstrapScopeHead(scope: PolicyScopeRef) {
        val insertBody: SchedulingPolicyScopeHeads.(UpdateBuilder<*>) -> Unit = {
            it[tenantGroupId] = scope.tenantGroupId
            it[SchedulingPolicyScopeHeads.scope] = scope.scope
            it[clinicScopeKey] = scope.clinicScopeKey
            it[revision] = 0L
            it[generation] = 0L
            it[clinicGenerationEpoch] = 0L
        }
        if (isH2Dialect()) {
            val exists = SchedulingPolicyScopeHeads.selectAll()
                .where { scopeHeadPredicate(scope) }
                .limit(1)
                .any()
            if (!exists) {
                try {
                    SchedulingPolicyScopeHeads.insert(insertBody)
                } catch (error: ExposedSQLException) {
                    val competingInsertWon = SchedulingPolicyScopeHeads.selectAll()
                        .where { scopeHeadPredicate(scope) }
                        .limit(1)
                        .any()
                    if (!competingInsertWon) throw error
                }
            }
        } else {
            SchedulingPolicyScopeHeads.insertIgnore(insertBody)
        }
    }

    private fun clinicGenerationDigest(
        tenantGroupId: Long,
        clinicGenerationEpoch: Long,
    ): String {
        require(tenantGroupId > 0) { "tenantGroupId must be positive" }
        require(clinicGenerationEpoch >= 0) { "clinicGenerationEpoch must be non-negative" }
        val canonical = "$tenantGroupId:$clinicGenerationEpoch"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    @Suppress("LongParameterList")
    private fun insertSnapshot(
        tenantGroupId: Long,
        clinicId: Long,
        decisionAt: Instant,
        serviceAt: Instant,
        tenantGeneration: Long,
        clinicGeneration: Long,
        sourceVersionsJson: String,
        sourceByPathJson: String,
        disabledFeaturesJson: String,
        warningsJson: String,
        payloadJson: String,
        snapshotHash: String,
    ) {
        val insertBody:
            EffectiveSchedulingPolicySnapshots.(UpdateBuilder<*>) -> Unit = {
                it[EffectiveSchedulingPolicySnapshots.tenantGroupId] = tenantGroupId
                it[EffectiveSchedulingPolicySnapshots.clinicId] = clinicId
                it[EffectiveSchedulingPolicySnapshots.decisionAt] = decisionAt
                it[EffectiveSchedulingPolicySnapshots.serviceAt] = serviceAt
                it[EffectiveSchedulingPolicySnapshots.tenantGeneration] = tenantGeneration
                it[EffectiveSchedulingPolicySnapshots.clinicGeneration] = clinicGeneration
                it[EffectiveSchedulingPolicySnapshots.sourceVersionsJson] = sourceVersionsJson
                it[EffectiveSchedulingPolicySnapshots.sourceByPathJson] = sourceByPathJson
                it[EffectiveSchedulingPolicySnapshots.disabledFeaturesJson] = disabledFeaturesJson
                it[EffectiveSchedulingPolicySnapshots.warningsJson] = warningsJson
                it[EffectiveSchedulingPolicySnapshots.payloadJson] = payloadJson
                it[EffectiveSchedulingPolicySnapshots.snapshotHash] = snapshotHash
            }
        if (isH2Dialect()) {
            try {
                EffectiveSchedulingPolicySnapshots.insert(insertBody)
            } catch (error: ExposedSQLException) {
                val competingInsertWon = findSnapshot(tenantGroupId, clinicId, snapshotHash) != null
                if (!competingInsertWon) throw error
            }
        } else {
            EffectiveSchedulingPolicySnapshots.insertIgnore(insertBody)
        }
    }

    private fun isH2Dialect(): Boolean =
        TransactionManager.current().db.dialect.name.equals("h2", ignoreCase = true)

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun validateDefinitionRecord(record: SchedulingPolicyDefinitionRecord) {
        val scope = PolicyScopeRef(record.tenantGroupId, record.scope, record.clinicId)
        require(record.clinicScopeKey == scope.clinicScopeKey) {
            "clinicScopeKey must match scope and clinicId"
        }
        require(record.version > 0) { "version must be positive" }
        require(record.schemaVersion > 0) { "schemaVersion must be positive" }
        require(record.revision > 0) { "revision must be positive" }
        require(record.effectiveUntil == null || record.effectiveUntil > record.effectiveFrom) {
            "effectiveUntil must be later than effectiveFrom"
        }
        require(SHA256_REGEX.matches(record.payloadHash)) { "payloadHash must be lowercase SHA-256" }
        require(record.payloadJson.toByteArray().size <= MAX_JSON_BYTES) {
            "payloadJson exceeds $MAX_JSON_BYTES bytes"
        }
        require(record.createdByActorId.isNotBlank() && record.createdByActorId.length <= 160) {
            "createdByActorId must contain 1..160 characters"
        }
        require(record.changeReason.isNotBlank() && record.changeReason.length <= 1000) {
            "changeReason must contain 1..1000 characters"
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 256 * 1024
        val SHA256_REGEX = Regex("[0-9a-f]{64}")
        val ALLOWED_LIFECYCLE_TRANSITIONS = setOf(
            PolicyLifecycle.DRAFT to PolicyLifecycle.SCHEDULED,
            PolicyLifecycle.DRAFT to PolicyLifecycle.ACTIVE,
            PolicyLifecycle.DRAFT to PolicyLifecycle.RETIRED,
            PolicyLifecycle.SCHEDULED to PolicyLifecycle.ACTIVE,
            PolicyLifecycle.SCHEDULED to PolicyLifecycle.RETIRED,
            PolicyLifecycle.ACTIVE to PolicyLifecycle.RETIRED,
        )
    }
}

/**
 * 스코프 헤드의 낙관적 revision 불일치를 나타내는 예외다.
 *
 * @property scope 직렬화 revision이 변경된 신뢰된 스코프.
 * @property expectedRevision 호출자가 제공한 예상 revision.
 * @property actualRevision 행 잠금을 보유한 상태에서 실제로 관측한 revision.
 */
class PolicyScopeHeadConflictException(
    val scope: PolicyScopeRef,
    val expectedRevision: Long,
    val actualRevision: Long,
) : IllegalStateException(
    "Policy scope head revision conflict: expected=$expectedRevision, actual=$actualRevision"
)

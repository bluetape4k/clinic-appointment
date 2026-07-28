# Scheduling Policy Foundation 설계

> Issue: [#182](https://github.com/bluetape4k/clinic-appointment/issues/182)
>
> 상태: Step 2/2-R 승인 완료, Step 3-R P0=0/P1=0, 최종 구현 계획 승인 및 Step 3-P PASS. 생산 코드 미착수.
> 기준일: 2026-07-27

## 1. 문제

병원별 예약 운영 방식은 다르다. 같은 SaaS에서도 어떤 병원은 관리자가 바로
확정 예약을 만들고, 어떤 병원은 고객 요청을 가예약으로 받은 뒤 관리자가
승인한다. 선점, 제안, 고객 동의, 초과예약, 재확인, 신뢰도, 장비 장애 대응도
병원 정책에 따라 달라진다.

이 차이를 controller 조건문, 환경변수, nullable 설정으로 구현하면 다음 문제가
생긴다.

- 정책이 바뀐 뒤 과거 예약 결정을 재현할 수 없다.
- tenant 기본값과 clinic override의 우선순위를 설명하기 어렵다.
- 고객과 관리자 예약의 권한·승인 흐름이 request body 값에 의존할 수 있다.
- 정책 활성화가 진행 중인 hold나 확정 예약을 조용히 변경할 수 있다.
- 중복 scheduler 실행과 동시 관리자 요청이 서로 다른 active version을 만들 수 있다.
- 새로운 정책 종류가 추가될 때 기존 정책 저장·감사·캐시 규칙이 반복 구현된다.

따라서 예약서비스는 정책을 typed·versioned aggregate로 관리하고, 의사결정 시점의
결과를 불변 `EffectiveSchedulingPolicy` snapshot으로 보존해야 한다.

## 2. 목표

1. `TENANT_DEFAULT`와 `CLINIC_OVERRIDE`를 결정적으로 합성한다.
2. 정책 lifecycle, 승인, 예약 활성화, generation, idempotency를 감사 가능하게 만든다.
3. Gateway가 전달한 인증 정보를 `ActorContext`로 정규화하고 모든 명령에 사용한다.
4. 관리자 직접 확정과 고객 가예약·관리자 승인 흐름을
   `BOOKING_COMMITMENT` 정책 계약으로 표현한다.
5. 정책 변경은 기본적으로 `FUTURE_ONLY`이며 기존 사실과 약속을 보존한다.
6. H2, PostgreSQL, MySQL에서 같은 의미를 유지한다.
7. 후속 visit/commitment, waitlist, disruption 기능이 임시 정책을 만들지 않게 한다.

## 3. 비목표

- 이번 작업에서 실제 `PROVISIONAL → CONFIRMED` 예약 aggregate를 구현하지 않는다.
- 현재 legacy appointment 생성 경로에 새 정책을 강제하지 않는다.
- Gateway 로그인, MFA, 사용자 신원 확인을 예약서비스가 구현하지 않는다.
- waitlist matching, 신뢰도 점수, 초과예약 실행, 재확인 발송을 구현하지 않는다.
- 상품, 구매, 시술 완료, 환불, 결제 규칙을 소유하지 않는다.
- frontend 정책 관리 화면을 구현하지 않는다.

위 기능은 정책 foundation을 소비하는 후속 업무다. 이번 작업은 그 기능들이
참조할 타입, version, snapshot, 권한, 활성화 계약을 완성한다.

## 4. 현재 근거

- `appointment-core`는 Exposed table과 caller-owned transaction repository 패턴을 사용한다.
- `CatalogSyncApplicationService`는 validation/hash를 transaction 전에 수행하고,
  transaction 안에서 repository 판정과 insert를 원자화한다.
- `AppointmentPlan`은 구매 당시 catalog snapshot을 불변으로 보존한다.
- `appointment-api`는 외부 인증서비스가 발급한 JWT의 HMAC 서명과 issuer를
  검증하고 `SchedulingUserPrincipal`을 만든다. 현재 구현에는 audience,
  assurance, actor type, patient subject와 복수 clinic scope가 없으므로 이번
  foundation에서 명시적으로 확장한다.
- `TenantClinicAccessChecker`는 tenant와 clinic의 실제 소유 관계를 DB에서 검증한다.
- `SchedulingOutboxEvents`는 현재 `AppointmentPlan`에 강하게 결합되어 있어
  정책 aggregate 이벤트를 담기 어렵다.
- 최신 Flyway version은 H2, PostgreSQL, MySQL 모두 V8이다.

## 5. 대안과 결정

### 5.1 정책 종류별 정규화 테이블

각 policy kind마다 별도 table과 repository를 만든다.

- 장점: SQL column과 constraint가 정책 의미를 직접 표현한다.
- 단점: kind 추가마다 migration, repository, API가 반복되고 tenant/clinic 합성기가
  여러 저장 모델을 알아야 한다.
- 판단: 핵심 envelope까지 분산되므로 채택하지 않는다.

### 5.2 단일 JSON document

scope, kind, lifecycle, payload 전체를 한 document로 저장한다.

- 장점: 확장과 versioning이 쉽다.
- 단점: lifecycle, effective interval, optimistic revision, 활성 version 유일성을
  DB가 보장하기 어렵고 payload 타입 오류가 늦게 발견된다.
- 판단: audit와 동시성 경계가 약하므로 채택하지 않는다.

### 5.3 공통 envelope + typed payload + compiled snapshot

scope, lifecycle, version, effective interval, revision, hash, actor 정보는 정규화한다.
정책 본문은 Kotlin sealed type으로 표현하고 canonical JSON으로 저장한다.
compiler는 typed payload만 받아 불변 snapshot을 만든다.

- 장점: 공통 동시성·감사 규칙을 한 곳에서 보장하면서 policy kind를 확장할 수 있다.
- 단점: canonical serialization과 schema migration 규율이 필요하다.
- 결정: 이 방식을 채택한다.

## 6. 신뢰 경계와 `ActorContext`

```text
Client
  → API Gateway / Identity Service
    → signed JWT
      → Appointment API verification
        → immutable ActorContext
          → authorization + audit + domain command
```

Gateway는 인증을 담당하고 예약서비스는 업무 권한을 담당한다. 예약서비스는
설정된 algorithm allowlist, 서명키, issuer, audience, expiration/not-before와
제한된 clock skew를 검증한 JWT claim으로만 `ActorContext`를 만든다. claim의
actor type, role, tenant, clinic, patient가 서로 모순되거나 필수 claim이 없으면
인증에 실패한다. 일반 `X-User-*` header와 request body의 actor 정보는 신뢰하지
않는다.

```kotlin
data class ActorContext(
    val actorId: String,
    val actorType: ActorType,
    val roles: Set<ActorRole>,
    val scopes: Set<String>,
    val allowedTenantCodes: Set<String>,
    val allowedClinicIds: Set<Long>,
    val patientSubjectId: String?,
    val assurance: AuthenticationAssurance,
    val issuer: String,
    val tokenId: String,
    val authenticatedAt: Instant,
    val correlationId: String,
)
```

`ActorType`은 `ADMIN`, `STAFF`, `DOCTOR`, `PATIENT`, `SYSTEM`이다. 역할 조합이
모호하거나 서로 충돌하면 fail closed한다. `actorType`, tenant, clinic, patient
scope와 booking origin은 request body로 덮어쓸 수 없다.

`jti`가 없거나 빈 token은 인증 단계에서 거절한다. JWT 원문, bearer token,
개별 claim 원문, parser detail, 불필요한 개인정보는 응답·DB·log에 저장하지
않는다.
감사 기록에는 actor ID, actor type, issuer, token ID, authentication time,
correlation ID와 명령 당시 tenant/clinic scope만 남긴다.

## 7. 정책 도메인 모델

### 7.1 공통 envelope

```kotlin
data class SchedulingPolicyDefinition(
    val id: Long,
    val tenantGroupId: Long,
    val scope: PolicyScope,
    val clinicId: Long?,
    val kind: SchedulingPolicyKind,
    val version: Long,
    val schemaVersion: Int,
    val lifecycle: PolicyLifecycle,
    val effectiveFrom: Instant,
    val effectiveUntil: Instant?,
    val revision: Long,
    val payloadHash: String,
    val payload: SchedulingPolicyPayload,
    val createdBy: ActorAuditRef,
    val changeReason: String,
)
```

불변조건:

- `TENANT_DEFAULT`이면 `clinicId == null`이다.
- `CLINIC_OVERRIDE`이면 `clinicId`가 tenant 소속 clinic이어야 한다.
- `(tenant, scope, clinic, kind, version)`은 유일하다.
- active/scheduled effective interval은 같은 scope와 kind 안에서 겹치지 않는다.
- `effectiveUntil`은 `effectiveFrom`보다 늦다.
- `payloadHash`는 schema-version-aware canonical JSON의 SHA-256이다.
- published payload는 수정하지 않고 새 version을 만든다.

### 7.2 lifecycle과 승인

정책 definition의 lifecycle은 `DRAFT`, `SCHEDULED`, `ACTIVE`, `RETIRED`다.
승인은 lifecycle 값이 아니라 별도 `PolicyApproval` 기록이다.

```text
DRAFT
  ├─ validate/preview/approve ─→ SCHEDULED ─→ ACTIVE ─→ RETIRED
  └─ validate/preview/approve ──────────────→ ACTIVE ─→ RETIRED
```

- draft 수정은 revision을 증가시키고 기존 preview와 approval을 stale로 만든다.
- 승인자는 draft 작성자와 같을 수 있는지 policy sensitivity가 결정한다.
- 민감 정책은 두 명의 서로 다른 승인자 또는 높은 assurance claim을 요구할 수 있다.
- 예약서비스는 MFA를 수행하지 않고 Gateway claim의 assurance evidence만 검증한다.
- `APPROVE_POLICY`와 `ACTIVATE_POLICY`는 별도 authority다. 민감 정책에서는
  activator를 draft 작성자나 approval actor 수에 포함하지 않는다. 예외를
  허용하려면 schema가 더 높은 assurance와 사유를 명시해야 한다.
- activation은 `activatedBy`, approval actor 집합, assurance, tenant/clinic scope,
  expected revision/generation을 함께 검증하고 감사 기록으로 남긴다.
- 활성화 실패 시 이전 active policy를 유지한다.

### 7.3 초기 policy kind

- `BOOKING_COMMITMENT`
- `HOLD_AND_CONSENT`
- `CAPACITY_AND_OVERBOOKING`
- `PRIORITY_AND_RELIABILITY`
- `RECONFIRMATION`
- `DISRUPTION_RECOVERY`
- `OPERATING_EXTENSION`
- `NOTIFICATION_AND_SLA`

모든 kind는 sealed payload type, validator, compiler contribution을 가진다.
알 수 없는 schema version은 저장·활성화하지 않고 stable error로 거절한다.

### 7.4 override

clinic override의 각 값은 다음 중 하나다.

```kotlin
sealed interface OverrideValue<out T> {
    data object Inherit : OverrideValue<Nothing>
    data class Set<T>(val value: T) : OverrideValue<T>
    data object Disable : OverrideValue<Nothing>
}
```

- `INHERIT`: tenant 값을 사용한다.
- `SET`: clinic 값을 사용하되 platform/tenant hard ceiling을 완화할 수 없다.
- `DISABLE`: schema가 optional로 선언한 기능만 끈다.
- 필수값이 없거나 제한을 완화하면 compile 실패다.
- nullable 값과 override 의미를 혼용하지 않는다.

## 8. 관리자 예약과 고객 예약 정책

`BOOKING_COMMITMENT`는 요청 주체에 따라 생성 가능한 commitment를 구분한다.

```kotlin
data class BookingCommitmentPolicy(
    val adminBookingMode: AdminBookingMode,
    val patientBookingMode: PatientBookingMode,
    val provisionalCapacityMode: ProvisionalCapacityMode,
    val provisionalRequestTtl: Duration,
    val resourceHoldTtl: Duration?,
    val approvalRoles: Set<ActorRole>,
    val adminConsentEvidence: ConsentEvidenceRequirement,
    val confirmedChangeMode: ConfirmedChangeMode,
)
```

`ConsentEvidenceRequirement`는 허용 evidence type, 최대 경과 시간, 동의한 조건의
canonical hash 필수 여부를 가진다. 예약서비스는 동의 자체를 새로 만들어내지 않고
검증 가능한 evidence reference와 조건 hash만 후속 commitment에 보관한다.
`ConfirmedChangeMode`의 안전 기본값은
`NEW_PROPOSAL_AND_CUSTOMER_CONSENT`이며 disable할 수 없다.

권고 기본값:

- `adminBookingMode = DIRECT_CONFIRM_WITH_CONSENT_EVIDENCE`
- `patientBookingMode = PROVISIONAL_APPROVAL_REQUIRED`
- `provisionalCapacityMode = NO_HOLD`
- `provisionalRequestTtl = 24h`
- `resourceHoldTtl = null`
- `approvalRoles = {ADMIN, STAFF}`
- `confirmedChangeMode = NEW_PROPOSAL_AND_CUSTOMER_CONSENT`

TTL invariant:

- `provisionalRequestTtl`은 가예약 요청 자체의 만료이며 platform hard range
  `5m..7d` 안에 있어야 한다.
- `NO_HOLD`와 `SOFT_HOLD`는 `resourceHoldTtl == null`이어야 한다.
- `HARD_HOLD`는 `resourceHoldTtl`이 필수이고 platform hard range `1m..30m`
  안이며 `resourceHoldTtl <= provisionalRequestTtl`이어야 한다.
- 두 TTL은 server `Instant`에서 계산한다. 만료 연장과 단축은 새 proposal이며
  기존 hold를 조용히 연장하지 않는다.

### 8.1 관리자 등록

```text
ActorContext(ADMIN|STAFF)
  → tenant/clinic authority
  → capacity and policy validation
  → captured consent evidence validation
  → CONFIRMED
```

관리자가 등록한다는 사실만으로 임의 확정할 수 있는 것은 아니다. 현재 정책이 직접
확정을 허용하고, 자원 배정과 동의 증빙이 유효해야 한다.

### 8.2 고객 등록

```text
ActorContext(PATIENT)
  → own patient scope validation
  → PROVISIONAL
  → manager review
    ├─ same terms approved → original patient request as consent → CONFIRMED
    ├─ changed terms → new proposal → customer consent → CONFIRMED
    ├─ rejected → REJECTED
    └─ TTL expired → EXPIRED
```

`PROVISIONAL`은 확정 예약이 아니다. 자원 보장 수준은 정책에 따라 다르다.

- `NO_HOLD`: 요청만 기록하고 자원을 점유하지 않는다.
- `SOFT_HOLD`: 운영상 후보로 표시하지만 충돌을 막는 점유는 아니다.
- `HARD_HOLD`: 제한된 TTL 동안만 실제 자원을 점유한다.

이번 foundation은 위 값을 compile한다. 실제 상태 전이와 resource allocation은
후속 visit/commitment 작업이 구현한다.

확정 예약의 시간, 진료 항목, 담당자, 자원 또는 비용 영향 조건을 바꾸는 요청은
`confirmedChangeMode`에 따라 새 proposal을 만든다. 고객이 새 조건에 동의하기
전에는 기존 확정 예약이 유효하며, 관리자 권한만으로 새 조건을 확정할 수 없다.

## 9. `EffectiveSchedulingPolicy`

compiler 입력:

- `tenantGroupId`
- `clinicId`
- `decisionAt`
- `serviceAt`
- 활성 tenant definition 집합
- 활성 clinic override 집합
- platform safety guardrail

compiler 출력:

```kotlin
data class EffectiveSchedulingPolicy(
    val id: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val decisionAt: Instant,
    val serviceAt: Instant,
    val generation: PolicyGenerationVector,
    val sourceVersions: Map<SchedulingPolicyKind, SourceVersion>,
    val sourceByPath: Map<String, PolicyValueSource>,
    val disabledFeatures: Set<String>,
    val warnings: List<PolicyWarning>,
    val payload: CompiledSchedulingPolicy,
    val snapshotHash: String,
)
```

동일한 logical input은 map 입력 순서와 무관하게 같은 `snapshotHash`를 반환한다.
snapshot 본문은 불변 저장하며 후속 aggregate는 ID, source version map, hash를
함께 보관한다.

### 9.1 시간 기준

policy schema가 평가 기준을 선언한다.

- `DECISION_TIME`: hold, consent, proposal, disruption workflow
- `SERVICE_TIME`: capacity, reconfirmation, SLA, operating extension

입력은 `Instant`로 저장한다. clinic local time 입력은 clinic timezone으로 변환하며
DST gap/overlap을 명시적으로 검증한다.

### 9.2 generation

하나의 scalar generation으로 tenant 변경과 clinic 변경을 동시에 표현하면
불필요한 전체 tenant cache invalidation이 생긴다. 따라서 다음 vector를 사용한다.

```kotlin
data class PolicyGenerationVector(
    val tenantGeneration: Long,
    val clinicGeneration: Long,
)
```

각 값은 해당 scope에서 policy kind 하나가 활성화될 때 단조 증가한다. 외부
caller에는 vector와 canonical opaque token을 함께 제공한다. optimistic command는
token을 `expectedPolicyGeneration`으로 돌려보낸다.

tenant 전체 preview는 별도의 `clinicGenerationEpoch`를 tenant scope head에 둔다.
어느 clinic override generation이라도 증가하면 같은 transaction에서 tenant head를
먼저 잠근 뒤 이 epoch도 증가시킨다. preview job은
`SHA-256(tenantId:clinicGenerationEpoch)`를 고정하므로 매 page마다 병원 목록과 모든
clinic head를 다시 읽지 않고 unique scope-head 한 행으로 freshness를 확인한다.
병원·appointment inventory는 bounded impact scan의 시점별 입력이지 policy generation이
아니므로 병원 추가·삭제만으로 preview를 stale 처리하지 않는다.

compiler는 tenant head와 clinic head를 읽고 definition을 합성한 뒤 두 head를 다시
읽는다. generation vector가 바뀌었으면 결과를 버리고 bounded retry한다. snapshot
저장은 expected vector CAS를 다시 확인하므로 서로 다른 DB isolation level에서도
혼합 세대 snapshot을 영속화하지 않는다.

## 10. 영속 모델

V9 migration은 세 DB에 같은 논리 구조를 추가한다.

### 10.1 `scheduling_policy_definitions`

공통 envelope, canonical payload, lifecycle, interval, revision, actor audit를 저장한다.

### 10.2 `scheduling_policy_approvals`

draft revision별 승인자와 assurance evidence를 저장한다. draft revision이 바뀌면
기존 approval은 남지만 활성화 근거로 사용할 수 없다. activation 시 distinct actor,
작성자/승인자/활성자 분리와 authority를 다시 검증한다.

### 10.3 `scheduling_policy_scope_heads`

tenant default 또는 clinic override scope별 optimistic revision과 generation을
보관한다. policy kind 하나가 활성화돼도 해당 scope generation을 증가시킨다.
따라서 `PolicyGenerationVector`의 두 scalar가 전체 compiled policy의 freshness를
표현할 수 있다. 이 row가 scope 안의 모든 policy kind 활성화 CAS serialization
point다. policy kind별 active/scheduled version과 interval은
`scheduling_policy_definitions`에서 관리한다.

tenant head의 `clinic_generation_epoch`는 하위 clinic override generation 증가를
집계하며 clinic head에서는 항상 `0`이다. clinic generation 변경 transaction은
tenant→clinic 순서로 두 head를 잠그고 clinic generation과 tenant epoch를 원자적으로
증가시킨다.

### 10.4 `effective_scheduling_policy_snapshots`

compiled payload, source version map, generation vector, hash, effective boundary를
불변 저장한다. hash 중복은 재사용할 수 있지만 snapshot identity는 tenant/clinic
scope를 벗어나 공유하지 않는다.

### 10.5 `scheduling_policy_activation_commands`

activation idempotency key의 keyed hash, expected draft revision, expected active
revision, 결과 generation과 event ID를 기록한다. 원문 key는 길이·문자 집합을
검증한 뒤 저장·로그·응답하지 않는다. 예약 활성화 명령은 `PENDING`, `CLAIMED`,
`RETRY_WAIT`, `COMPLETED`, `MISSED` 상태와 `nextAttemptAt`, `leaseOwner`,
`leaseUntil`, attempt, last error를 함께 가진다.

### 10.6 `scheduling_policy_preview_jobs`

동기 한도를 넘는 preview의 cursor, draft revision, generation, partition,
deadline과 `PENDING`, `RUNNING`, `COMPLETED`, `STALE`, `FAILED`, `CANCELLED`
상태를 기록한다. stale/partial 결과로 승인하거나 활성화할 수 없다.

### 10.7 generic scheduling outbox

기존 `SchedulingOutboxEvents.planId` 결합을 일반화한다.

- `aggregateType`
- `aggregateId`
- 선택적 legacy `planId`
- event envelope와 payload

기존 plan event는 `aggregateType=APPOINTMENT_PLAN`으로 backfill한다.
정책 활성화는 `aggregateType=SCHEDULING_POLICY`와
`SchedulingPolicyActivated`를 같은 transaction에서 기록한다.

rolling deployment에서 구버전 writer와 신버전 writer가 동시에 존재할 수 있으므로
expand/backfill과 constraint cutover를 같은 배포 migration에 넣지 않는다.

1. V9 expand migration은 nullable `aggregate_type`, `aggregate_id`를 추가하고
   legacy `plan_id`를 nullable로 전환하며 기존 polling index를 보존한다.
2. V9는 기존 row를 `APPOINTMENT_PLAN`과 plan ID로 idempotent backfill한다.
3. V9 application writer는 legacy/new column을 dual-write하고 reader parity
   metric과 검증 query를 제공한다.
4. 별도 배포의 V10 cutover는 모든 writer가 dual-write함과 null row 0건을 운영
   증거로 확인한 뒤 새 aggregate column만 필수화한다. V9에서 이미 nullable로
   전환한 `plan_id`는 legacy 호환 column으로 유지한다. 이 foundation PR에는
   V10을 포함하지 않는다.
5. V9에서 `status,next_attempt_at`, `status,created_at`, aggregate 조회 index와 FK
   의미를 H2, PostgreSQL, MySQL에서 검증한다.

## 11. 활성화 transaction

```text
1. bounded idempotency key를 keyed hash로 변환하고 hash 조회
2. draft revision 확인
3. preview revision/generation 확인
4. 필요한 approval, activation authority와 직무분리 확인
5. current scope head CAS
6. scope head lock 아래 interval overlap 검사
7. 이전 active retire
8. 새 definition active
9. scope generation 증가
10. activation command 결과 기록
11. SchedulingPolicyActivated outbox insert
12. commit
```

모든 단계는 하나의 Exposed `transaction {}` 안에서 수행한다. canonicalization과
순수 validation은 transaction 전에 수행해 lock 보유 시간을 줄인다.

성공한 transaction만 generation과 outbox를 증가시킨다. 동일 idempotency key와
동일 fingerprint 재시도는 hash+fingerprint 비교로 기존 결과를 반환한다. 같은
hash와 다른 fingerprint는 conflict다. 원문 key는 transaction evidence에도
남기지 않는다.

interval 겹침은 DB 전용 exclusion constraint에 의존하지 않는다. 세 dialect 모두
scope head row를 먼저 CAS/lock하고, 같은 `(tenant, scope, clinic, kind)`의
`ACTIVE|SCHEDULED` interval을 lock 안에서 조회한다. 겹침은 공통
`POLICY_ACTIVATION_CONFLICT`로 매핑한다. lock 순서는 tenant head, clinic head
순이며 같은 scope 안에서는 policy kind와 무관하게 직렬화한다.

### 11.1 scheduled activation runner

- DB time 기준 `effectiveFrom <= now`인 activation command ID를 짧게 조회한다.
- runner는 `(definitionId, version, effectiveFrom)`에서 deterministic idempotency
  key를 만들고 revision/lifecycle/만료 lease CAS로 한 명만 claim한다.
- 기본 scan cadence는 10초, lease는 30초다. 실패는 동일 key로 exponential
  backoff와 jitter를 적용하되 `effectiveUntil`과 configured catch-up deadline을
  넘지 않는다.
- 새 instance는 startup catch-up을 수행한다. lease가 만료된 `CLAIMED` command는
  재claim할 수 있고 idempotent activation 결과를 재사용한다.
- 기본 lateness SLO는 60초다. 5분 또는 configured deadline을 넘으면 `MISSED`로
  전이하고 이전 active policy를 유지하며 critical alert를 발생시킨다.
- operator는 원인과 current head를 확인한 뒤 `MISSED` command를 참조하는 새
  manual replay command를 만들거나 incompatible draft를 retire한다. 강제 DB
  상태 변경과 terminal command 재작성은 금지한다.
- scope head가 cold-path serialization point라는 점을 수용한다. 같은 scope의
  multi-kind due burst는 bounded retry하며 deadline 안에 끝나지 않으면 위
  `MISSED` 절차를 따른다.

## 12. preview와 cache

### 12.1 impact preview

- 동기 preview는 최대 10,000 row와 monotonic 2초 runtime deadline 중 먼저
  도달하는 지점까지만 수행한다. 각 chunk 전후에 deadline을 검사한다.
- 한도에 도달하면 partial 결과를 폐기하고 partition당 최대 5,000 row의 async
  job으로 전환해 `202 Accepted`, `jobId`, `Location`, `Retry-After`를 반환한다.
- tenant당 기본 worker concurrency 2, queued job 100의 bounded limit를 두며
  포화 시에만 `429 POLICY_PREVIEW_LIMITED`를 반환한다.
- cursor, draft revision, generation을 checkpoint한다.
- partition resume마다 draft와 관련 generation을 확인하고 변경 시 `STALE`로
  종료한다. cancellation/deadline도 chunk 경계에서 확인한다.
- partial/stale 결과는 approval이나 activation 근거가 될 수 없다.
- preview는 policy와 appointment 상태를 변경하지 않는다.

### 12.2 compiler cache

key:

```text
(tenantId, clinicId, tenantGeneration, clinicGeneration, decision/service boundary)
```

- bounded LRU와 tenant별 entry/memory quota를 둔다.
- effective read는 cache lookup 전에 authoritative DB scope head의 current
  generation vector를 읽고 key와 일치하는 snapshot만 반환한다. DB를 확인할 수
  없으면 stale cache를 반환하지 않고 fail closed한다.
- activation event는 해당 scope generation의 cache를 빠르게 비우는 최적화일 뿐
  correctness 근거가 아니다. outbox relay 지연·실패 중에도 DB generation 검증이
  오래된 snapshot 반환을 막는다.
- time boundary는 `effectiveFrom`, `effectiveUntil`, scheduled activation,
  emergency expiration과 DST 경계에서 분할한다.
- cold/warm latency, hit ratio, eviction, stale rejection을 관측한다.

## 13. API

tenant default:

```text
POST /api/{tenantCode}/admin/scheduling-policies/drafts
POST /api/{tenantCode}/admin/scheduling-policies/{id}/validate
POST /api/{tenantCode}/admin/scheduling-policies/{id}/preview
GET  /api/{tenantCode}/admin/scheduling-policies/preview-jobs/{jobId}
POST /api/{tenantCode}/admin/scheduling-policies/{id}/approve
POST /api/{tenantCode}/admin/scheduling-policies/{id}/schedule
POST /api/{tenantCode}/admin/scheduling-policies/{id}/activate
POST /api/{tenantCode}/admin/scheduling-policies/{id}/retire
POST /api/{tenantCode}/admin/scheduling-policies/activation-commands/{commandId}/replay
GET  /api/{tenantCode}/admin/scheduling-policies/effective
```

clinic override:

```text
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/drafts
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/validate
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/preview
GET  /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/preview-jobs/{jobId}
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/approve
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/schedule
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/activate
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/retire
POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/activation-commands/{commandId}/replay
GET  /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/effective
```

command API는 `Idempotency-Key`, expected revision/generation과 change reason을
요구한다. actor와 booking origin은 인증 context에서만 얻는다.

두 effective route는 `decisionAt`과 `serviceAt` query를 모두 필수로 받는다.
형식은 UTC 또는 명시적 offset을 포함한 RFC 3339이고 local date-time만 전달하는
요청은 DST gap/overlap 모호성을 피하기 위해 거절한다. 서버 현재시각 default는
없으며 `serviceAt < decisionAt`, 누락, parse 실패는
`400 POLICY_PAYLOAD_INVALID`다. 두 값을 `Instant`로 정규화한 뒤 compile하고,
응답은 사용한 `decisionAt`, `serviceAt`, tenant/clinic generation,
snapshot hash를 돌려준다.

tenant의 `POST /api/{tenantCode}/admin/scheduling-policies/{id}/preview`와 clinic의
`POST /api/{tenantCode}/admin/clinics/{clinicId}/scheduling-policies/{id}/preview`는
완료 시 `200`, async 전환 시 `202`다. job 조회는
`PENDING|RUNNING|COMPLETED|STALE|FAILED|CANCELLED`, progress cursor, pinned
revision/generation, result hash와 stable error를 반환한다. `STALE`과 partial
결과에는 activation에 사용할 수 있는 evidence token을 발급하지 않는다.

현재 `SecurityConfig`의 broad admin matcher와 일반 write matcher보다 policy
matcher를 먼저 둔다. transport layer는 tenant access와 `ADMIN|STAFF`를 확인하고,
domain layer는 policy별 approval/activation authority와 assurance를 확인한다.
모든 `{id}`와 `{jobId}`는 path의 tenant/clinic scope에 속하는지 DB로 확인하며,
불일치는 resource enumeration을 피하기 위해 `POLICY_RESOURCE_NOT_FOUND`로
응답한다.

## 14. stable error contract

| HTTP | Code | Retryable | 의미 | Caller action |
|---|---|---:|---|---|
| 400 | `POLICY_PAYLOAD_INVALID` | false | typed validation 실패 | payload 수정 |
| 400 | `POLICY_OVERRIDE_FORBIDDEN` | false | hard ceiling 완화 또는 필수값 disable | override 수정 |
| 403 | `POLICY_ACTOR_FORBIDDEN` | false | actor가 scope/명령 권한 없음 | 권한 확인 |
| 404 | `POLICY_RESOURCE_NOT_FOUND` | false | path scope와 policy/job scope 불일치 또는 없음 | 대상 scope 확인 |
| 409 | `POLICY_DRAFT_STALE` | false | expected draft revision 불일치 | 최신 draft 재조회 |
| 409 | `POLICY_PREVIEW_STALE` | false | preview가 현재 revision/generation과 불일치 | preview 재실행 |
| 409 | `POLICY_ACTIVATION_CONFLICT` | false | active head CAS 또는 interval 충돌 | 최신 head 재조회 |
| 409 | `POLICY_IDEMPOTENCY_CONFLICT` | false | 같은 key, 다른 명령 | 새 key로 의도 확인 |
| 409 | `POLICY_ACTIVATION_MISSED` | false | scheduled activation deadline 경과 | 원인 확인 후 manual replay/retire |
| 422 | `POLICY_APPROVAL_INSUFFICIENT` | false | 승인 수/assurance 부족 | 필요한 승인 수집 |
| 429 | `POLICY_PREVIEW_LIMITED` | true | tenant async queue/concurrency 포화 | `Retry-After` 뒤 같은 요청 재시도 |

모든 error는 stable code, operator action, `retryable`, correlation ID를 제공한다.
`retryable=true`는 caller 변경 없이 `Retry-After` 뒤 같은 요청을 반복해도 된다는
뜻이며, stale/conflict/approval 오류는 선행 caller action이 필요하므로 false다.

## 15. 보안

- Gateway JWT claim과 DB tenant/clinic 관계를 모두 검증한다.
- `PATIENT`는 자신의 patient subject 범위만 사용할 수 있다.
- admin/staff라도 allowed tenant와 clinic 범위를 넘을 수 없다.
- policy payload는 bounded size, strict schema, unknown-field rejection을 적용한다.
- canonical JSON과 hash 계산은 입력 크기 제한 뒤 수행한다.
- log와 event payload에 bearer token, 환자 개인정보, 전체 JWT claim을 남기지 않는다.
- 동일 actor가 이중 승인을 충족할 수 없게 distinct actor invariant를 둔다.
- `SYSTEM` activation은 service identity scope와 scheduler command evidence가 필요하다.

## 16. 운영과 관측

metric:

- activation success/conflict/stale/idempotent replay
- compile cold/warm latency
- cache hit/eviction/quota rejection
- preview sync/async/stale/deadline
- outbox pending/published/failed
- policy별 active generation과 capacity debt signal
- scheduled activation due/lateness/retry/missed
- authoritative generation read failure와 stale cache rejection

structured log는 tenant, clinic, policy kind, version, generation, actor audit ref,
correlation ID, stable result code를 포함한다. payload 전문은 기록하지 않는다.

기본 alert와 조치는 tenant별 조정 가능한 기준으로 시작한다.

| Signal | Default alert | Operator action |
|---|---|---|
| scheduled activation lateness | 60초 warning, 5분 critical | runner lease, DB clock, head conflict 확인 후 replay/retire |
| outbox oldest pending / failed | 60초 warning, failed 1건이 5분 지속하면 critical | relay와 transport 복구, 동일 event id 재발행 |
| preview deadline/stale ratio | 10분간 5% 초과 warning | queue/DB 부하와 변경 빈도 확인, job rate 제한 |
| activation conflict ratio | 10분간 5% 초과 warning | 동일 scope 자동화/관리자 동시 변경 조사 |
| authoritative generation read failure | 1분간 1% 초과 critical | stale 반환 없이 API degradation 선언, DB 복구 |

rollout:

- named feature flag 기본값은 off다.
- shadow compile로 기존 동작을 변경하지 않고 snapshot/hash를 검증한다.
- effective read와 admin API를 먼저 활성화한다.
- 실제 booking consumer는 후속 issue에서 별도 flag로 활성화한다.

rollback:

- 새 policy activation을 retire하고 이전 compatible version을 새 activation으로 복구한다.
- 이미 사용된 snapshot을 삭제하거나 수정하지 않는다.
- migration rollback은 destructive down migration 대신 forward repair를 사용한다.
- scheduled command는 DB row를 직접 수정하지 않고 manual replay 또는 명시적
  retire command로 복구한다.

## 17. 코드와 문서 설명 기준

업무 복잡성을 주석으로 감추지 않고 다음 위치에 설명을 둔다.

- public type과 service에는 책임, 호출자, transaction 소유권을 KDoc으로 설명한다.
- lifecycle enum과 command handler에는 허용 전이와 거절 이유를 설명한다.
- CAS/lock 코드에는 lock 순서와 보호하는 invariant를 설명한다.
- canonical hash 코드에는 포함·제외 필드와 schema version 규칙을 설명한다.
- stable error enum에는 caller/operator action과 retry 가능성을 설명한다.
- API DTO에는 인증 context에서 파생되는 값과 body로 받을 수 없는 값을 설명한다.
- migration에는 DB별 차이와 동일한 논리 constraint를 주석으로 남긴다.
- 구현과 함께 한국어 requirements 문서, OpenAPI, 대표 사용 예를 갱신한다.

설명은 코드의 동작을 반복하지 않고 “왜 이 경계가 필요한가”를 기록한다.

## 18. 주요 실패 시나리오

1. 두 관리자가 같은 draft를 동시에 활성화한다. 하나만 CAS에 성공한다.
2. scheduler가 같은 activation을 재실행한다. idempotent replay가 같은 결과를 반환한다.
3. draft가 바뀐 뒤 이전 preview로 활성화한다. `POLICY_PREVIEW_STALE`로 거절한다.
4. clinic override가 tenant hard ceiling을 완화한다. compile 전에 거절한다.
5. 고객이 body에 `actorType=ADMIN`을 넣는다. body 값은 무시되고 PATIENT로 처리된다.
6. 관리자가 다른 tenant/clinic 정책을 수정한다. Gateway claim과 DB scope 검증에서 거절한다.
7. tenant default activation 중 한 clinic override가 바뀐다. generation vector가 stale command를 막는다.
8. outbox insert가 실패한다. 전체 activation transaction을 rollback한다.
9. unknown schema version이 들어온다. 저장 또는 활성화 전에 거절한다.
10. cache boundary가 effective interval을 넘는다. boundary-aware key 테스트가 이를 검출한다.
11. activation event 전달이 지연된다. 다른 instance는 DB generation 검증으로 stale cache를 거절한다.
12. runner가 claim 후 죽는다. lease 만료와 동일 idempotency key로 다른 instance가 catch-up한다.
13. async preview queue가 포화된다. `429`와 `Retry-After`를 반환하고 state를 변경하지 않는다.

## 19. 테스트 전략

### unit

- typed payload validator
- `INHERIT`/`SET`/`DISABLE`
- platform/tenant ceiling
- actor context mapping과 role conflict
- booking TTL mode invariant와 confirmed change consent mode
- canonical JSON/hash determinism
- generation vector/token
- booking-origin policy compilation

### repository/dialect

- H2, PostgreSQL, MySQL V9 migration
- scope/version uniqueness
- interval overlap serialization
- active head CAS
- idempotent activation
- immutable snapshot
- generic outbox backfill과 insert

### API/security

- admin tenant/clinic command
- patient/admin actor escalation 거절
- cross-tenant, cross-clinic, cross-patient 거절
- stale revision/generation/preview
- stable error와 OpenAPI contract
- preview `200|202`, job polling과 path scope binding
- approval/activation authority 분리와 sensitive policy dual control

### concurrency/performance

- concurrent activation winner 1건
- retry-only success가 lifecycle failure를 숨기지 않는지 확인
- 10,000-row/2-second preview boundary
- partition resume와 stale discard
- scheduled activation crash/catch-up/lease/missed deadline
- delayed/failed outbox invalidation 중 stale cache rejection
- same-scope multi-kind due burst와 bounded retry
- cold/warm compile latency, cache quota, boundary correctness

## 20. 수용 기준

- tenant default와 clinic override가 입력 순서와 무관하게 같은 snapshot/hash를 만든다.
- 모든 override path는 source를 추적할 수 있다.
- activation은 CAS, idempotency, generation, outbox를 한 transaction에서 보장한다.
- 기존 snapshot은 새 정책 활성화 후에도 재현 가능하다.
- 관리자 직접 확정과 고객 가예약·관리자 승인 규칙이 typed policy로 compile된다.
- 확정 예약 변경은 새 proposal과 고객 동의가 필요하다는 규칙이 compile된다.
- actor, tenant, clinic, patient, booking origin을 request body로 상승시킬 수 없다.
- preview는 bounded하며 상태를 변경하지 않는다.
- async preview caller는 `202`, job location/status, terminal result를 완결되게 처리할 수 있다.
- H2, PostgreSQL, MySQL에서 같은 논리 constraint와 error 의미를 가진다.
- KDoc, 한국어 requirements, OpenAPI가 상태 전이와 운영 조치를 설명한다.
- 정책 consumer는 feature flag off 상태에서 기존 예약 동작을 변경하지 않는다.

## 21. 후속 작업

1. Visit and commitment:
   `PROVISIONAL`, `HELD`, `CONFIRMED`, 고객 동의와 resource allocation.
2. Fulfillment and commerce events:
   부분 완료, 잔여 항목, refund event.
3. Disruption recovery:
   장비 고장, 휴진, 공휴일 변경과 재예약 proposal.
4. Capacity operations:
   waitlist, reliability, reconfirmation, overbooking, operating extension.

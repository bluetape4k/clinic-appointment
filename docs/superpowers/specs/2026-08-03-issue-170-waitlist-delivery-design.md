# Issue #170 대기 목록 전달 계층 설계

## 1. 문서 상태와 권위

- 대상 이슈: GitHub Issue #170 `feat: recover same-day capacity with policy-driven waitlist offers`
- 선행 구현: PR #212에서 병합된 phase-one waitlist core와 V18 schema
- 기준 branch: `origin/develop`
- 구현 branch: `feat/issue-170-waitlist-delivery`
- 설계 승인 범위: clinic policy, vacancy event/job, notification, staff API, expiry/recovery scheduler

이 문서는 phase-one core를 대체하지 않는다. 기존 waitlist entry, offer, capacity hold,
offer event와 caller-owned Exposed transaction 계약을 유지하면서 외부 adapter와 운영
계층을 추가하는 phase-two 권위 문서다.

## 2. 문제와 목표

현재 V18 core는 대기 항목의 결정적 후보 조회, offer와 capacity hold의 원자적 생성,
offer claim/release, 만료 복구를 제공한다. 그러나 예약 취소나 `NO_SHOW`가 발생해도
vacancy 작업이 자동 생성되지 않으며, clinic policy 관리, 알림 전달, staff API,
leader-elected recovery 실행기가 연결되지 않았다.

목표는 다음과 같다.

1. 당일 `CONFIRMED -> CANCELLED | NO_SHOW` 전이를 durable vacancy 작업으로 변환한다.
2. clinic 소유의 versioned policy로 hard eligibility를 먼저 판단하고 eligible 후보만
   결정적으로 정렬한다.
3. 선택, offer, hold, policy snapshot, notification outbox를 하나의 DB transaction으로
   기록한다.
4. staff가 tenant-scoped API에서 대기 항목과 offer를 관리하고, 유효한 offer 하나만
   replacement appointment로 확정할 수 있게 한다.
5. event 유실, 프로세스 재시작, notification 실패, leader 교체, 중복 confirm에서도
   DB 권위와 감사 이력을 보존한다.

## 3. 고정 제약

- 모든 Exposed 접근은 호출자가 연 `transaction {}` 안에서 실행한다.
- PostgreSQL, MySQL, H2 schema 의미가 같아야 한다.
- claim과 slot 점유의 권위는 DB row lock, version, unique constraint다. Redis lock은
  scheduler 중복 실행 억제에만 사용한다.
- 이름, 전화번호, 이메일, 원문 clinical note를 waitlist/event/outbox payload에 복제하지
  않는다. member service의 opaque `MemberId`만 사용한다.
- `BookingBenefitGrant`는 clinical eligibility를 우회할 수 없다.
- `BookingRestriction`은 기간과 근거가 있는 제한이며 영구 환자 label이 아니다.
- 새 module, Kafka/RabbitMQ, 환자 self-service, payment/deposit, CRM connector,
  campaign attribution은 추가하지 않는다.
- KDoc과 내부 설계/계획/lesson은 한국어로 작성한다. `README.md`와
  `README.ko.md`는 source-equivalent하게 함께 갱신한다.

## 4. 검토한 접근과 결정

### 4.1 선택: 기존 모듈을 연결하는 application orchestration

- `appointment-core`: policy model, repository port, matcher 입력, offer/claim DB 권위
- `appointment-event`: opaque `SlotAvailable` application event
- `appointment-notification`: 기존 notification outbox와 resilient channel을 통한 전달
- `appointment-api`: staff HTTP API, transaction orchestration, configuration, scheduler

이 접근은 V18 core를 재사용하고 새 infrastructure를 도입하지 않는다. 각 adapter가
실패해도 durable vacancy job과 notification outbox에서 복구할 수 있다.

### 4.2 기각: `appointment-waitlist` 신규 module

module 경계는 선명하지만 V18 model/repository 이동, CI 등록, catalog와 문서 재구성이
필요하다. Issue #170 완결에 필요하지 않은 구조 변경이므로 기각한다.

### 4.3 기각: notification outbox 중심 orchestration

알림 전달에는 적합하지만 vacancy matching과 slot claim까지 notification 상태에
종속된다. transport 상태와 예약 DB 권위를 혼합하므로 기각한다.

## 5. 구성 요소와 책임

### 5.1 `appointment-core`

- `ClinicWaitlistPolicyRecord`와 deterministic evaluator
- `BookingRestrictionRecord`, `DisruptionRecoveryCreditRecord`,
  `BookingBenefitGrantRecord`
- policy/adjustment/vacancy repository
- 기존 `WaitlistCandidateMatcher`에 공급할 immutable evaluation snapshot
- 기존 `WaitlistOfferService`, `WaitlistOfferClaimService`,
  `WaitlistRecoveryService`의 transaction 계약 유지

core는 Spring, HTTP status, notification provider, Redis를 알지 못한다.

### 5.2 `appointment-event`

`SlotAvailable`은 다음 opaque 값만 가진다.

- `vacancyJobId`
- `tenantGroupId`
- `clinicId`
- `correlationId`
- `occurredAt`

appointment, member, contact, policy 상세를 event payload에 넣지 않는다. listener는
event를 처리 신호로만 사용하고 vacancy job을 다시 조회한다.

### 5.3 `appointment-notification`

- template purpose `WAITLIST_SLOT_OFFER` 지원
- 기존 member profile resolver로 실제 destination을 조회
- 기존 outbox retry, provider timeout, circuit breaker, bulkhead, health signal 재사용
- offer가 만료되거나 terminal이면 delivery 전에 suppression 처리
- delivery 결과를 waitlist audit adapter에 기록

알림 성공은 offer를 `ACCEPTED`로 만들지 않는다. phase two에는 환자 inbound action이나
self-service confirm API가 없으며, 고객의 전화/메시지 응답을 확인한 승인된 staff가
confirm/decline API를 호출하는 경로만 지원한다. 자동 patient reply mapping은 후속 범위다.

### 5.4 `appointment-api`

- tenant/clinic scope와 staff actor 검증
- vacancy application service와 after-commit event publication
- waitlist entry, offer, policy, adjustment, audit HTTP API
- replacement appointment 생성 adapter
- Redis leader election을 재사용하는 bounded expiry/recovery scheduler
- Micrometer metric, Actuator health, feature flag와 clinic allowlist

### 5.5 module dependency와 port 계약

- `appointment-core`는 `WaitlistVacancyJobStore`, `WaitlistPolicyStore`,
  `WaitlistCommandStore`, `WaitlistDecisionAuditPort` 같은 transaction-aware port와 domain
  contract만 소유한다. Spring event, HTTP DTO, notification provider, Redis type을 참조하지
  않는다.
- `appointment-event`는 `SlotAvailable` fast signal과 waitlist notification outbox codec 및
  repository adapter를 소유하되 core current-state 권위를 갖지 않는다.
- `appointment-notification`은 outbox claim/delivery adapter만 제공한다. offer 상태 조회와
  audit 기록은 core port를 통해 수행하며 API module을 의존하지 않는다.
- `appointment-api`가 Exposed adapter, JWT/path scope resolver, scheduler orchestration과
  Spring wiring을 소유한다. dependency 방향은 `api -> notification/event/core`,
  `notification -> event/core`, `event -> core`이고 역방향 또는 cycle을 허용하지 않는다.

## 6. V19 additive data model

V19는 V18 table을 수정하거나 rename하지 않고 다음 table을 추가한다.

### 6.1 `scheduling_waitlist_policy_versions`

- scope: `tenant_group_id`, `clinic_id`
- identity/version: `id`, `generation`, `policy_version`, `policy_digest`
- lifecycle: `status`, `effective_from`, nullable `effective_until`
- scoring configuration: bounded typed columns와 canonical policy document
- audit: `created_by`, `created_at`, nullable `retired_by`, `retired_at`

상태는 `DRAFT`, `ACTIVE`, `RETIRED`다. 한 clinic과 시점에는 하나의 effective
`ACTIVE` policy만 허용한다. activation은 generation CAS로 수행하고 overlapping
effective period를 거부한다.

policy document는 UTF-8 64 KiB 이하, nesting depth 8 이하로 제한한다. strict enum과
bounded integer만 허용하고 unknown field와 polymorphic/default typing을 거부한다.
digest는 검증된 document를 key 정렬·정수 표준 표현으로 canonical serialization한 뒤
계산한다.

activation은 항상 존재하는 `Clinics` row를 `(tenant_group_id, clinic_id)` predicate로
`FOR UPDATE`해 scope lock으로 사용하고 current generation을 CAS한 뒤 overlapping
`ACTIVE` window가 없음을 같은 transaction에서 다시 확인한다. 최초 activation은 expected
generation `0`, 이후는 현재 최대 generation을 요구한다. insert는
`(tenant_group_id, clinic_id, generation)` unique와 `status=ACTIVE` overlap query를 같은
clinic lock 아래 수행한다.
PostgreSQL/MySQL/H2 모두 동일 repository algorithm을 사용하고, dialect가 overlap을 독립
constraint로 표현하지 못하므로 scope lock과 generation unique constraint를 권위로 삼는다.

### 6.2 `scheduling_waitlist_policy_events`

policy 생성, 활성화, 교체, 폐기를 append-only로 기록한다. actor, correlation ID,
from/to generation, reason code를 저장하며 자유 형식 민감 정보는 받지 않는다.

### 6.3 `scheduling_booking_restrictions`

- opaque member scope와 rolling-window evidence digest
- policy version, restriction mode, starts/expiry
- actor, reason code, release/reversal version
- clinical urgent path를 막지 않는 `requires_staff_approval` 또는
  `exclude_automatic_offer` mode

만료되거나 release된 row는 이력을 위해 유지한다.

### 6.4 `scheduling_disruption_recovery_credits`

clinic-caused disruption의 appointment reference, typed reason, grant/expiry,
bounded credit, actor, reversal history를 저장한다. 소비 시 offer decision snapshot이
credit ID와 적용 값을 보존한다.

### 6.5 `scheduling_booking_benefit_grants`

승인된 외부 workflow가 전달한 opaque approval reference, scope, cap, grant/expiry,
revoke version을 저장한다. 원 campaign 또는 금전 정보는 저장하지 않는다.

### 6.6 `scheduling_waitlist_vacancy_jobs`

- source appointment와 vacancy key
- resource, capacity, treatment, optional doctor, starts/ends snapshot
- `READY`, `PROCESSING`, `OFFERED`, `NO_CANDIDATE`, `EXPIRED`, `FAILED` 상태
- attempt, next attempt, lease owner/expiry, version, last bounded reason code
- tenant/clinic/source transition에 대한 idempotency unique key
- `vacancy_generation`과 `(tenant_group_id, clinic_id, vacancy_key, vacancy_generation)`
  unique key

job은 예약 상태 전이와 같은 transaction에서 생성한다. `SlotAvailable` event 유실 시
scheduler가 `READY` 또는 expired `PROCESSING` job을 복구한다.

한 generation에는 active offer가 최대 하나이고, 한 entry에도 active offer가 최대 하나다.
decline/release가 끝난 뒤에만 vacancy generation을 증가시켜 다음 후보를 허용한다. 각
`PROCESSING -> terminal` update는 `lease_owner`, `version`, `lease_expires_at > now`를
predicate로 포함한다. fence를 잃은 worker는 offer/job을 terminal 처리하지 않고
`LEASE_FENCED` no-op/audit 결과로 종료한다.

active uniqueness는 PostgreSQL에서는 partial unique index를 사용한다. MySQL/H2에는
active 상태일 때만 deterministic scope/generation 또는 entry key를 저장하고 terminal이면
`NULL`로 비우는 `active_vacancy_key`, `active_entry_key` nullable column과 unique index를
사용한다. application transition과 migration matrix가 이 column 의미를 검증한다.

### 6.7 `scheduling_waitlist_command_records`

staff mutation의 scope, command type, `Idempotency-Key` digest, request digest,
result reference와 expiry를 저장한다. 동일 key와 동일 request는 기존 결과를 반환하고,
동일 key와 다른 request는 conflict다.

상태는 `PROCESSING`, `SUCCEEDED`, `FAILED`다. 짧은 reservation transaction이 scope,
command type, key digest unique insert를 commit한 뒤 business transaction을 시작하며
concurrent first request 중 하나만 실행권을 얻는다. 같은 request의 `PROCESSING` replay는
두 번째 mutation 없이 `202 Accepted`, `Retry-After: 1`, reason
`IDEMPOTENCY_IN_PROGRESS`, correlation ID를 반환하고 `Idempotent-Replay`는 생략한다.
성공 replay는 저장된 status/result body와 `Idempotent-Replay: true`를 반환한다. crash로
stale해진 `PROCESSING`은 expiry recovery가 typed failure를 저장하며, 같은 request의
`FAILED` replay는 저장된 stable error/status를 반환한다.

### 6.8 기존 V18 row 확장 원칙

offer의 policy version, policy hash, evaluation digest, rank, selection reason은 V18
column을 그대로 사용한다. 세부 score breakdown과 adjustment reference는 bounded
canonical decision document 또는 append-only offer event로 기록한다. V18 current-state
권위를 다른 V19 table로 이동하지 않는다.

## 7. Policy 평가 계약

### 7.1 hard eligibility

다음 조건을 순서대로 평가하고 하나라도 실패하면 scoring하지 않는다.

1. tenant와 clinic scope 일치
2. treatment type 일치
3. optional doctor와 preferred time window 일치
4. slot 시작 전이며 offer TTL이 양수
5. 현재 booking restriction 검토
6. clinic-configured clinical/operational rule 충족
7. 이미 active offer가 없는 entry와 vacancy

clinical urgent path는 relationship benefit보다 먼저 별도 rule로 판단한다.

candidate query는 `(tenant_group_id, clinic_id, state, treatment_type_id, ...)` scope index를
시작점으로 사용하고 doctor/time window, hard eligibility, typed policy factor를 DB projection에
적용한 뒤 §7.2의 전체 tuple로 정렬한다. active offer/restriction은 indexed anti-join으로
제외하며 contact/profile은 projection에 포함하지 않는다. 자동 delivery로 활성화 가능한
policy rule은 세 dialect에서 이 typed projection으로 표현 가능해야 하며 그렇지 않으면
activation을 거부한다. 첫 page의 첫 row가 global highest-ranked eligible candidate이고,
추가 page는 lock 재검증에서 stale/conflict가 난 후보를 건너뛰기 위한 것이다. vacancy
하나당 최대 4 page, page당 100 row까지만 재검증하고 없으면 `CANDIDATE_SCAN_LIMIT`으로
`NO_CANDIDATE` 처리한다. 세 dialect 실행 계획은 scope/order index에서 시작해야 한다.

### 7.2 deterministic scoring

eligible 후보의 기본 순위는 다음 tuple을 내림차순으로 비교하고 마지막 tie-breaker는
entry ID다.

1. clinic-defined clinical/service urgency
2. disruption recovery credit
3. approved booking benefit credit
4. booking reliability tier
5. waiting age
6. slot fit
7. entry ID 오름차순

모든 factor는 bounded integer 또는 enum으로 canonicalize한다. floating-point 합산은
사용하지 않는다. evaluator는 rule result, score component, policy version/digest,
adjustment ID, override reason code를 snapshot으로 반환한다.

explicit staff override는 기본 score component가 아니다. 기본은 `ADMIN` 또는 별도
`WAITLIST_OVERRIDE` permission을 받은 clinic `STAFF`만 hard eligibility를 통과한 후보
사이에서 기본 1위를 교체할 수 있는 별도 결정 단계다. override는 대상 후보와 기존 1위의
decision snapshot, actor, typed reason code와 correlation ID를 기록한다. override 시도와
성공은 별도 metric/audit query로 검토 가능해야 한다. clinical/operational eligibility,
active restriction의 staff-approval 요구, slot compatibility는 override로 우회할 수 없다.

### 7.3 preview와 실제 결정

preview는 mutation하지 않으며 같은 policy evaluator를 사용한다. 실제 offer는
transaction 안에서 policy와 adjustment row를 다시 읽고 snapshot digest를 검증한다.
preview generation이 달라졌으면 stale preview로 표시하고 실행 결과로 재사용하지 않는다.

## 8. Transaction과 event 흐름

### 8.1 vacancy 생성

1. 예약 row와 관련 resource를 잠근다.
2. `CONFIRMED -> CANCELLED | NO_SHOW` 전이를 검증한다.
3. 당일이며 아직 시작하지 않은 capacity인지 판단한다.
4. appointment 상태와 audit event를 기록한다.
5. eligible vacancy이면 `scheduling_waitlist_vacancy_jobs`를 idempotent insert한다.
6. transaction commit 후 `SlotAvailable(vacancyJobId, ...)`를 publish한다.

event publication 실패는 appointment transaction을 되돌리지 않는다. durable job이
복구 경로다.

당일/시작 전 판정은 clinic IANA time zone과 주입된 `Clock`을 사용한다. DST overlap은
저장된 slot `Instant`를 권위로 비교하고 local date는 clinic zone으로 계산한다. DST gap에
해당하는 local request time은 생성 시 validation error로 거부한다.

### 8.2 후보 선정과 offer

listener 또는 scheduler가 별도 transaction에서 수행한다.

1. vacancy job을 `FOR UPDATE SKIP LOCKED` 또는 dialect-equivalent bounded claim으로
   `PROCESSING` 전환한다.
2. resource/slot이 여전히 비어 있는지 확인한다.
3. effective policy와 adjustment를 조회한다.
4. keyset page 후보에 hard eligibility와 deterministic scoring을 적용한다.
5. 기존 `WaitlistOfferService`로 offer와 capacity hold를 생성한다.
6. policy decision snapshot과 offer event를 저장한다.
7. 기존 notification outbox에 waitlist offer notification을 기록한다.
8. vacancy job을 `OFFERED` 또는 `NO_CANDIDATE`로 전환한다.

1은 짧은 lease transaction이며 2–8은 별도의 processing transaction이다. processing
transaction은 시작과 terminal update 직전에 lease owner/version/expiry fence를 확인한다.
claim commit 직후 process가 종료되면 lease expiry 후 다른 worker가 같은 generation을
재개한다. outbox insert가 실패하면 offer와 hold도 rollback한다.

PostgreSQL과 MySQL은 `FOR UPDATE SKIP LOCKED`, H2는 조건부 version update 뒤 소유 row
재조회로 bounded claim한다. 세 dialect 모두 두 worker 중 하나만 유효 fence를 얻어야
하며 이를 실제 concurrency integration test로 검증한다.

모든 mutation의 canonical lock order는 command record → vacancy job → offer → entry →
capacity hold → appointment → resource allocation이다. 필요 없는 row는 건너뛰되 역순으로
잠그지 않는다. lock wait는 기본 2초이며 deadlock/serialization failure는 jitter를 포함해
최대 3회 재시도한 뒤 `WAITLIST_CONTENTION` `409`로 종료한다.

### 8.3 notification delivery

1. 짧은 transaction에서 outbox row를 claim하고 offer state/expiry를 다시 읽는다.
2. terminal/expired이면 같은 transaction에서 `SUPPRESSED`와 reason code를 기록한다.
3. claim transaction을 닫고 member profile/template을 resolve한다.
4. provider 호출 직전 두 번째 짧은 transaction에서 outbox claim version과 offer
   state/expiry를 다시 검증하고 stale이면 `SUPPRESSED`로 닫는다.
5. 검증 transaction을 닫고 provider IO를 수행한다.
6. 별도 transaction에서 claim version과 offer terminal state를 CAS해 성공/실패/unknown
   결과를 notification history와 waitlist offer event에 기록한다.

실패는 offer state를 변경하지 않는다. profile resolver와 provider는 서로 다른
timeout/bulkhead를 사용하고 provider deadline은 offer/hold expiry와 slot start 중 가장
이른 시각보다 앞이어야 한다. dispatch 직전에 state/expiry를 한 번 더 확인한다. retry의
next attempt는 expiry를 넘지 않게 clamp하며 late result는 terminal offer를 되살리지
않는다. `DELIVERY_RESULT_UNKNOWN`은 자동 중복 전송하지 않고 expiry까지 manual-review
queue와 metric에 남기며 staff가 suppression 또는 provider 확인을 기록한다.

### 8.4 staff confirm

1. command idempotency `PROCESSING` record를 unique insert해 실행권을 선점한다.
2. offer, entry, hold, vacancy resource를 정해진 순서로 잠근다.
3. scope, state, expiry, decision freshness, slot capacity를 다시 검증한다.
4. 기존 appointment/resource allocation service를 통해 replacement appointment를 만든다.
5. replacement가 hold를 consume한 뒤 offer/entry/hold를 `ACCEPTED`로 전이한다.
6. appointment와 waitlist audit event, command result를 같은 transaction에 기록한다.

두 동시 confirm 중 하나만 성공한다. 동일 idempotency request는 생성된 appointment를
반환한다. 다른 key 또는 다른 request의 stale confirm은 `409 Conflict`다. reservation과
business transaction 사이 crash는 stale command recovery가 `FAILED`로 닫고 appointment
reference가 이미 존재하면 그 결과로 reconciliation한다.

### 8.5 decline, withdraw, expiry

- decline은 active offer/hold를 `DECLINED`/released로 전환하고 다음 candidate를 위한
  동일 vacancy job을 새 generation으로 재활성화한다. staff가 typed reason으로 명시적으로
  decline한 경우에만 자동으로 다음 generation을 시작한다.
- withdraw는 entry와 active offer/hold를 terminal 처리한다. active offer가 있었다면 hold
  release 뒤 slot이 유효할 때 typed `ENTRY_WITHDRAWN` reason으로 다음 vacancy generation을
  자동 생성하고, slot이 유효하지 않으면 vacancy를 terminal 처리한다.
- expiry scheduler는 시작 시각, offer expiry, hold expiry 중 가장 이른 경계를 사용한다.
- expiry는 hold를 release하고 slot이 clinic-local clock 기준 아직 유효하면 typed
  `OFFER_EXPIRED` reason으로 다음 vacancy generation을 자동 생성한다. slot이 시작됐거나
  capacity가 사라졌으면 vacancy를 `EXPIRED`로 닫는다.
- 모든 반복 실행은 이미 terminal인 row를 no-op success로 처리한다.
- generation N의 offer/hold가 terminal이고 fence가 유효할 때만 N+1을 만들며, partial
  unique/indexed active-state 검사가 vacancy generation과 entry별 중복 active offer를 막는다.

## 9. Staff HTTP API

기본 path는 `/api/{tenantCode}/clinics/{clinicId}/waitlist`다.

controller 진입 시 `TenantScope(tenantGroupId, tenantCode, clinicId)`를 한 번 resolve한다.
JWT의 canonical tenant group/clinic membership과 path를 대조한 뒤에만 application service와
repository에 이 값 객체를 전달한다. unknown tenant는 `404`, 유효 tenant의 clinic membership
불일치는 `403`이다. durable idempotency uniqueness는 canonical `tenantGroupId`,
`clinicId`, command type, key digest만 사용하고 `tenantCode`는 path/audit context에만 둔다.

### 9.1 entry와 offer

- `POST /entries`
- `GET /entries?status=&cursor=&limit=`
- `GET /entries/{entryId}`
- `POST /entries/{entryId}/withdraw`
- `GET /offers/{offerId}`
- `POST /offers/{offerId}/confirm`
- `POST /offers/{offerId}/decline`
- `GET /offers/{offerId}/decision`
- `GET /offers?status=&memberId=&entryId=&expiresBefore=&deliveryState=&cursor=&limit=`

mutation은 `Idempotency-Key`를 요구한다. list는 bounded keyset pagination을 사용한다.
key는 ASCII 16–128자이며 보존 기간은 24시간이다. 동일 request replay는 최초 success
status/body와 `Idempotent-Replay: true`를 반환하고 다른 request는
`IDEMPOTENCY_REQUEST_MISMATCH` `409`다. entry/offer list는 `(updatedAt DESC, id DESC)`
cursor를 opaque base64url로 인코딩하며 기본 50, 최대 100이고 invalid cursor는
`INVALID_CURSOR` `400`이다.

cursor payload는 `{v:1, updatedAt, id, filterDigest}`를 HMAC 서명한 base64url이다.
filterDigest는 scope, caller audience와 모든 list filter를 묶으며 filter 변경,
signature/version/order key 오류는 `INVALID_CURSOR` `400`이다.

아직 `PROCESSING`인 동일 request replay는 `202 Accepted`, `Retry-After: 1`,
`IDEMPOTENCY_IN_PROGRESS` body를 반환하고 두 번째 mutation을 시작하지 않는다. `FAILED`
replay는 저장된 stable error/status를 동일하게 반환한다.

mutation 계약은 다음과 같다.

| API | 권한 | 요청 핵심 | 성공/replay | 주요 conflict |
|---|---|---|---|---|
| `POST /entries` | `STAFF`, `ADMIN` | member/treatment/window/optional doctor, typed reason | `201 EntryResponse` / 동일 body | duplicate active entry `409` |
| `POST /entries/{id}/withdraw` | `STAFF`, `ADMIN` | typed reason | `200 EntryResponse` | terminal/stale version `409` |
| `POST /offers/{id}/confirm` | `STAFF`, `ADMIN` | expected version, confirmation source | `201 AppointmentReferenceResponse` / 동일 body | expired/stale/occupied `409` |
| `POST /offers/{id}/decline` | `STAFF`, `ADMIN` | expected version, typed reason | `200 OfferResponse` | terminal/stale `409` |
| policy create/activate | `ADMIN` | strict policy document, expected generation | `201/200 PolicyResponse` | overlap/stale generation `409` |
| adjustment create/release/revoke | endpoint별 `ADMIN` 또는 승인된 `STAFF` | bounded typed amount/reason/reference/version | `201/200 AdjustmentResponse` | cap/stale/revoked `409` |

모든 DTO는 scope ID를 server-side 값으로 채우며 request body의 tenant/clinic 값은 받지
않는다. 공통 최초 성공 예시는 `{"id":"opaque-id","version":1,"status":"ACTIVE",
"correlationId":"..."}`이며 create는 `201`, transition은 `200`이다. success replay는
동일 status/body에 `Idempotent-Replay: true`, processing replay는 위 `202` 계약,
failed replay는 최초 stable error/status다. OpenAPI는 각 endpoint별 이 최초 성공/replay,
validation, authorization, conflict JSON example을 포함한다.

### 9.2 policy와 adjustment

- `POST /policies/versions`: `PolicyVersionCreateRequest(document,effectiveFrom,effectiveUntil,
  reasonCode)` → `201 PolicyResponse`
- `POST /policies/versions/{policyVersionId}/activate`:
  `PolicyActivateRequest(expectedGeneration,reasonCode)` → `200 PolicyResponse`
- `GET /policies/effective` → `200 PolicyResponse`, 없으면 `404 POLICY_NOT_FOUND`
- `POST /policies/preview`: `PolicyPreviewRequest(document,memberId,vacancy,
  adjustmentRefs)` → `200 PolicyPreviewResponse(eligible,reasonCodes,scoreComponents,
  rankPreview,policyDigest)`; mutation/idempotency key 없음
- `POST /restrictions`: `RestrictionCreateRequest(memberId,mode,startsAt,expiresAt,
  evidenceDigest,reasonCode)` → `201 RestrictionResponse`
- `POST /restrictions/{id}/release`: `VersionedReasonRequest` → `200 RestrictionResponse`
- `POST /recovery-credits`: `RecoveryCreditCreateRequest(memberId,appointmentRef,amount,
  expiresAt,reasonCode)` → `201 RecoveryCreditResponse`
- `POST /recovery-credits/{id}/reverse`: `VersionedReasonRequest` → `200 RecoveryCreditResponse`
- `POST /benefit-grants`: `BenefitGrantCreateRequest(memberId,approvalRef,cap,expiresAt,
  reasonCode)` → `201 BenefitGrantResponse`
- `POST /benefit-grants/{id}/revoke`: `VersionedReasonRequest` → `200 BenefitGrantResponse`
- `GET /audits?subjectType=&subjectId=&cursor=&limit=` → bounded `AuditPageResponse`
- `GET /operations/backlog?state=&cursor=&limit=` → `WaitlistBacklogPageResponse`
- `POST /operations/vacancy-jobs/{id}/requeue`:
  `VersionedReasonRequest(expectedVersion,reasonCode)` → `200 VacancyJobResponse` (`ADMIN`)
- `POST /operations/deliveries/{id}/suppress`:
  `VersionedReasonRequest(expectedVersion,reasonCode)` → `200 DeliveryReviewResponse` (`ADMIN`)

policy activation과 grant mutation은 `ADMIN` 권한을 요구한다. entry/offer 운영은
clinic `STAFF` 또는 `ADMIN`이 수행한다. 기존 `TenantClinicAccessChecker`와
`ActorContextResolver`를 재사용한다.

delivery가 enable/allowlist 상태지만 `ACTIVE` policy가 없으면 allowlist activation과
policy-dependent mutation을 `WAITLIST_POLICY_NOT_ACTIVE` `409`로 막는다. scheduler는 job을
claim하지 않고 readiness를 `OUT_OF_SERVICE`로 보고한다. admin은 policy 생성/preview/activate
후 readiness가 회복된 것을 확인해야 한다.

### 9.3 HTTP 오류 계약

- `400 Bad Request`: 형식, 범위, 중복 ID, invalid effective period
- `401 Unauthorized`: JWT 없음, signature/issuer/audience/expiry invalid
- `403 Forbidden`: tenant/clinic 권한 또는 actor role 부족
- `404 Not Found`: 해당 scope에서 보이지 않는 entry/offer/policy
- `409 Conflict`: stale version, expired offer, occupied slot, reused idempotency key
- `503 Service Unavailable`: 필수 adapter 또는 DB recovery subsystem 비가용

오류 body는 stable reason code와 correlation ID만 제공하며 내부 score나 PII를 포함하지
않는다.

reason code는 `VALIDATION_*`, `AUTH_*`, `SCOPE_*`, `IDEMPOTENCY_*`, `POLICY_*`,
`OFFER_EXPIRED`, `DECISION_STALE`, `SLOT_OCCUPIED`, `WAITLIST_CONTENTION`,
`DEPENDENCY_*` category로 고정한다. duplicate ID가 active-state uniqueness를 침해하면
`409`, 형식이나 request 내부 중복이면 `400`이다.

| endpoint/action | status | stable reason code |
|---|---:|---|
| confirm expired/stale/occupied/contention | 409 | `OFFER_EXPIRED` / `DECISION_STALE` / `SLOT_OCCUPIED` / `WAITLIST_CONTENTION` |
| decline terminal 또는 stale | 409 | `OFFER_TERMINAL` / `VERSION_CONFLICT` |
| withdraw terminal 또는 stale | 409 | `ENTRY_TERMINAL` / `VERSION_CONFLICT` |
| policy activate no policy/overlap/stale | 409 | `WAITLIST_POLICY_NOT_ACTIVE` / `POLICY_WINDOW_OVERLAP` / `VERSION_CONFLICT` |
| adjustment cap/revoked/stale | 409 | `ADJUSTMENT_CAP_EXCEEDED` / `ADJUSTMENT_REVOKED` / `VERSION_CONFLICT` |
| list cursor invalid/tampered/filter changed | 400 | `INVALID_CURSOR` |
| unauthenticated/forbidden scope | 401/403 | `AUTH_UNAUTHENTICATED` / `AUTH_SCOPE_DENIED` |

`DecisionResponse`는 offer/policy version, rank, stable eligibility/selection category,
redacted score components, adjustment presence, override 여부, evaluated timestamp,
correlation ID를 담는다. failed confirm의 `ErrorResponse`는 reason code, correlation ID,
retryable boolean, nullable retry-after만 담으며 internal score, raw member/clinical 정보는
포함하지 않는다.

### 9.4 OpenAPI canonical examples

공통 header는 mutation에 `Idempotency-Key: 01HX...`, authenticated request에
`Authorization: Bearer <jwt>`다. 아래 JSON은 endpoint별 canonical example이며 omitted
optional field는 OpenAPI schema에서 nullable로 표시한다.

| endpoint | request JSON | first success | replay/conflict example |
|---|---|---|---|
| `POST /entries` | `{"memberId":"m-1","treatmentTypeId":7,"windowStart":"2026-08-03T09:00:00Z","windowEnd":"2026-08-03T12:00:00Z","reasonCode":"STAFF_REQUEST"}` | `201 {"id":"e-1","version":1,"status":"ACTIVE","correlationId":"c-1"}` | `409 {"reasonCode":"ENTRY_ALREADY_ACTIVE","correlationId":"c-1","retryable":false}` |
| `POST /entries/e-1/withdraw` | `{"expectedVersion":1,"reasonCode":"MEMBER_DECLINED"}` | `200 {"id":"e-1","version":2,"status":"WITHDRAWN","correlationId":"c-2"}` | `409 {"reasonCode":"ENTRY_TERMINAL","correlationId":"c-2","retryable":false}` |
| `POST /offers/o-1/confirm` | `{"expectedVersion":1,"confirmationSource":"STAFF_PHONE"}` | `201 {"appointmentId":101,"offerId":"o-1","correlationId":"c-3"}` | `409 {"reasonCode":"OFFER_EXPIRED","correlationId":"c-3","retryable":false}` |
| `POST /offers/o-1/decline` | `{"expectedVersion":1,"reasonCode":"MEMBER_DECLINED"}` | `200 {"id":"o-1","version":2,"status":"DECLINED","correlationId":"c-4"}` | `409 {"reasonCode":"OFFER_TERMINAL","correlationId":"c-4","retryable":false}` |
| `POST /policies/versions` | `{"document":{"urgencyWeight":10},"effectiveFrom":"2026-08-04T00:00:00Z","reasonCode":"INITIAL"}` | `201 {"id":"p-1","generation":0,"status":"DRAFT","correlationId":"c-5"}` | `400 {"reasonCode":"VALIDATION_POLICY_DOCUMENT","correlationId":"c-5","retryable":false}` |
| `POST /policies/versions/p-1/activate` | `{"expectedGeneration":0,"reasonCode":"APPROVED"}` | `200 {"id":"p-1","generation":1,"status":"ACTIVE","correlationId":"c-6"}` | `409 {"reasonCode":"POLICY_WINDOW_OVERLAP","correlationId":"c-6","retryable":false}` |
| `POST /restrictions` | `{"memberId":"m-1","mode":"EXCLUDE_AUTOMATIC_OFFER","expiresAt":"2026-09-01T00:00:00Z","evidenceDigest":"sha256:...","reasonCode":"RELIABILITY"}` | `201 {"id":"r-1","version":1,"status":"ACTIVE","correlationId":"c-7"}` | `409 {"reasonCode":"VERSION_CONFLICT","correlationId":"c-7","retryable":false}` |
| `POST /restrictions/r-1/release` | `{"expectedVersion":1,"reasonCode":"REVIEWED"}` | `200 {"id":"r-1","version":2,"status":"RELEASED","correlationId":"c-8"}` | `409 {"reasonCode":"VERSION_CONFLICT","correlationId":"c-8","retryable":false}` |
| `POST /recovery-credits` | `{"memberId":"m-1","appointmentRef":"a-1","amount":20,"expiresAt":"2026-09-01T00:00:00Z","reasonCode":"CLINIC_DISRUPTION"}` | `201 {"id":"rc-1","version":1,"status":"ACTIVE","correlationId":"c-9"}` | `400 {"reasonCode":"VALIDATION_AMOUNT","correlationId":"c-9","retryable":false}` |
| `POST /recovery-credits/rc-1/reverse` | `{"expectedVersion":1,"reasonCode":"GRANT_ERROR"}` | `200 {"id":"rc-1","version":2,"status":"REVERSED","correlationId":"c-10"}` | `409 {"reasonCode":"VERSION_CONFLICT","correlationId":"c-10","retryable":false}` |
| `POST /benefit-grants` | `{"memberId":"m-1","approvalRef":"ap-1","cap":10,"expiresAt":"2026-09-01T00:00:00Z","reasonCode":"APPROVED_BENEFIT"}` | `201 {"id":"bg-1","version":1,"status":"ACTIVE","correlationId":"c-11"}` | `409 {"reasonCode":"ADJUSTMENT_CAP_EXCEEDED","correlationId":"c-11","retryable":false}` |
| `POST /benefit-grants/bg-1/revoke` | `{"expectedVersion":1,"reasonCode":"APPROVAL_REVOKED"}` | `200 {"id":"bg-1","version":2,"status":"REVOKED","correlationId":"c-12"}` | `409 {"reasonCode":"ADJUSTMENT_REVOKED","correlationId":"c-12","retryable":false}` |
| `POST /operations/vacancy-jobs/v-1/requeue` | `{"expectedVersion":3,"reasonCode":"OPERATOR_RETRY"}` | `200 {"id":"v-1","version":4,"status":"READY","correlationId":"c-13"}` | `409 {"reasonCode":"VERSION_CONFLICT","correlationId":"c-13","retryable":false}` |
| `POST /operations/deliveries/d-1/suppress` | `{"expectedVersion":2,"reasonCode":"PROVIDER_CONFIRMED"}` | `200 {"id":"d-1","version":3,"status":"SUPPRESSED","correlationId":"c-14"}` | `409 {"reasonCode":"VERSION_CONFLICT","correlationId":"c-14","retryable":false}` |

모든 mutation은 동일 request 처리 중이면 `202`와 `Retry-After: 1`, 성공 replay면 최초
status/body와 `Idempotent-Replay: true`를 반환한다. JWT 없음/invalid는
`401 {"reasonCode":"AUTH_UNAUTHENTICATED","correlationId":"c-auth","retryable":false}`,
scope/permission 거부는 같은 schema의 `AUTH_SCOPE_DENIED` `403`, cursor tamper는
`INVALID_CURSOR` `400`이다.

mutation별 replay 계약은 다음과 같이 고정한다. 각 success replay body는 위 표의 해당
first success JSON과 byte-equivalent이고 status도 동일하다.

| endpoint | success replay | processing replay |
|---|---|---|
| `POST /entries` | `201`, `Idempotent-Replay:true`, entry first-success body | `202`, `Retry-After:1`, common processing body |
| `POST /entries/{id}/withdraw` | `200`, replay header, withdraw first-success body | `202`, retry header, common body |
| `POST /offers/{id}/confirm` | `201`, replay header, confirm first-success body | `202`, retry header, common body |
| `POST /offers/{id}/decline` | `200`, replay header, decline first-success body | `202`, retry header, common body |
| policy create/activate | 각각 `201`/`200`, replay header, 해당 first-success body | `202`, retry header, common body |
| restriction create/release | 각각 `201`/`200`, replay header, 해당 first-success body | `202`, retry header, common body |
| recovery credit create/reverse | 각각 `201`/`200`, replay header, 해당 first-success body | `202`, retry header, common body |
| benefit grant create/revoke | 각각 `201`/`200`, replay header, 해당 first-success body | `202`, retry header, common body |
| vacancy requeue/delivery suppress | `200`, replay header, 해당 first-success body | `202`, retry header, common body |

common processing body는 `{"reasonCode":"IDEMPOTENCY_IN_PROGRESS",
"correlationId":"c-replay","retryable":true,"retryAfterSeconds":1}`다.
query canonical examples는 다음과 같다.

- `GET /entries` → `200 {"items":[{"id":"e-1","status":"ACTIVE"}],"nextCursor":"signed..."}`
- `GET /offers` → `200 {"items":[{"id":"o-1","status":"OFFERED","expiresAt":"2026-08-03T11:00:00Z"}],"nextCursor":null}`
- `GET /offers/o-1/decision` → `200 {"offerId":"o-1","policyVersion":1,"rank":1,"reasonCategory":"SELECTED","scoreComponents":["URGENCY"],"evaluatedAt":"2026-08-03T10:00:00Z","correlationId":"c-q1"}`
- `GET /policies/effective` → `200 {"id":"p-1","generation":1,"status":"ACTIVE","policyDigest":"sha256:..."}`
- `POST /policies/preview` → `200 {"eligible":true,"reasonCodes":[],"scoreComponents":{"urgency":10},"rankPreview":1,"policyDigest":"sha256:..."}`
- `GET /audits`와 `GET /operations/backlog` → `200 {"items":[],"nextCursor":null}`
- invalid/tampered cursor → `400 {"reasonCode":"INVALID_CURSOR","correlationId":"c-cursor","retryable":false}`
- scope/permission denial → `403 {"reasonCode":"AUTH_SCOPE_DENIED","correlationId":"c-auth","retryable":false}`

## 10. Security와 privacy

- path tenant/clinic 검증 후 repository에서도 같은 scope predicate를 반복한다.
- `MemberId`는 opaque string으로 취급하고 이름, 연락처, raw clinical note를 저장하거나
  log에 출력하지 않는다.
- policy explanation은 staff audience와 admin audience를 구분한다.
- 환자용 또는 unrelated staff view에는 restriction evidence, benefit source, detailed
  relationship score를 노출하지 않는다.
- actor reference, approval reference, correlation ID는 기존 bounded validator를 사용한다.
- log와 metric label은 tenant/member/offer ID 같은 high-cardinality 값을 포함하지 않는다.
- benefit grant는 hard eligibility 평가 뒤에만 score에 참여한다.

JWT는 configured issuer/audience, non-expired token, subject-to-actor mapping,
`tenant_group_id`, clinic membership, role/permission claim을 모두 요구한다. `STAFF`는 자신의
clinic path만, `ADMIN`은 tenant group 안의 clinic만 접근하며 claim 누락/불일치는 fail-closed
`403`이다. `WAITLIST_OVERRIDE`는 별도 permission claim으로 검증한다.

decision/audit response redaction은 다음과 같다.

| field | `ADMIN` | 같은 clinic `STAFF` | unrelated staff/patient |
|---|---|---|---|
| policy version, rank, stable reason category | 표시 | 표시 | 미노출 |
| bounded score breakdown | 표시 | 업무상 필요한 category만 | 미노출 |
| restriction evidence digest, benefit approval source | 표시 | redacted presence만 | 미노출 |
| clinical/service urgency detail | typed category만 | coarse category만 | 미노출 |
| override before/after snapshot과 actor | 표시 | 결과와 reason category만 | 미노출 |

인가 거부, cross-scope 시도, override 시도, 반복 idempotency mismatch, oversized/malformed
policy는 PII 없는 security audit event와 correlation ID로 기록한다.

## 11. Scheduler와 recovery

`appointment-api`의 기존 Redis leader election adapter를 재사용한다.

- `vacancy-dispatch`: `READY`와 lease-expired `PROCESSING` job을 bounded batch 처리
- `offer-expiry`: expired offer/hold를 bounded batch terminal 처리
- `notification-suppression`: terminal/expired offer의 pending notification을 suppression
- `stuck-hold-reconcile`: V18 recovery service를 호출해 누락/불일치 hold 복구

leader lease를 잃으면 현재 batch 이후 종료하고 DB lease가 만료된 뒤 다른 leader가
재개한다. scheduler는 한 transaction에 전체 batch를 넣지 않고 job/offer 단위 또는
작은 resource group 단위로 commit한다.

각 job 시작 전 leader lease를 다시 확인하고 DB lease는 최대 job budget의 2배 이상으로
설정한다. terminal write는 항상 fencing predicate를 사용하므로 Redis lease를 잃은 worker가
capacity를 부여할 수 없다. attempt는 claim 성공 시 증가하고 exponential backoff+jitter를
적용한다. max attempt 도달 시 `FAILED`; admin-only requeue가 version과 typed reason을
기록해 `READY`로 되돌린다. expired lease reclaim, failed requeue, unknown delivery,
stuck hold의 operator action은 runbook과 audit reason을 공유한다.

## 12. Configuration과 rollout

- `appointment.waitlist.core.enabled`: 기존 V18 core flag
- `appointment.waitlist.delivery.enabled=false`: phase-two global default
- `appointment.waitlist.delivery.clinic-allowlist`: 점진적 clinic 활성화
- `appointment.waitlist.offer-ttl=15m`
- bounded batch size, retry backoff, lease duration, maximum attempts

초기 안전값은 batch 25, candidate page 100/최대 4 page, DB job lease 30초, lock wait 2초,
최대 attempt 5다. notification worker concurrency는 instance당 8, profile timeout 500ms,
provider timeout 2초로 제한한다. profile/template cache는 clinic-independent immutable
template만 5분 캐시하고 member destination은 캐시하지 않는다.

rollout 순서는 V19 migration → adapter readiness → shadow preview → clinic allowlist →
active delivery다. 재활성화 전에 recovery와 backlog 상태를 확인한다. rollback/flag mode는
다음 표 하나를 권위로 삼는다.

| 동작 | active | clinic allowlist 제거 | global delivery off |
|---|---|---|---|
| cancellation 시 durable vacancy job 생성 | on | on | on |
| vacancy dispatch/new offer | on | off | off |
| 새 notification outbox delivery | on | off | off |
| offer expiry/hold release | on | on | on |
| notification suppression | on | on | on |
| stuck-hold reconcile | on | on | on |
| failed requeue/manual review | on | read-only/manual | read-only/manual |

durable job 생성은 예약 transaction의 안전 기록이므로 rollback에서도 중단하지 않는다.
allowlist 추가 전 active policy, adapter readiness, migration version, oldest backlog=0을
확인하고 rollback은 allowlist 제거 → dispatch/new delivery 0 확인 → in-flight fence 만료 →
expiry/suppression/reconcile drain 순서로 수행한다.

성능 acceptance baseline은 clinic당 active entry 10,000, pending vacancy 1,000,
notification backlog 5,000에서 단일 API instance 기준 vacancy 300건/분 이상, p95 최초 offer
생성 2초 이하, restart 후 backlog catch-up 10분 이하, DB lock wait p99 500ms 이하이다.
critical candidate query는 scope keyset index 기반이며 unbounded full scan/filesort를 허용하지
않는다.

5,000 notification backlog/10분 catch-up fixture는 profile resolve p95 100ms, provider p95
200ms, provider error 1%, timeout 0.1% 조건과 instance concurrency 8을 포함한다. provider를
제외한 DB-only drain은 별도 지표로 측정해 병목 원인을 분리한다.

## 13. Observability

low-cardinality metric을 추가한다.

- `appointment_waitlist_vacancy_jobs_total{outcome,reason}`
- `appointment_waitlist_candidate_evaluations_total{outcome,reason}`
- `appointment_waitlist_offer_deliveries_total{outcome,reason}`
- `appointment_waitlist_confirms_total{outcome,reason}`
- `appointment_waitlist_active_offers`, `appointment_waitlist_active_holds`
- `appointment_waitlist_expired_backlog`, `appointment_waitlist_oldest_vacancy_seconds`
- `appointment_waitlist_provider_attempts_total{outcome}`와 5분 rate 기반 failure ratio
- `appointment_waitlist_lease_reclaims_total{reason}`,
  `appointment_waitlist_lock_wait_seconds`, `appointment_waitlist_candidate_rows`
- `appointment_waitlist_security_events_total{reason}`, `appointment_waitlist_overrides_total{outcome}`

Actuator health는 DB backlog와 required adapter readiness를 분리해 보고한다. provider
실패는 waitlist claim readiness를 내리지 않지만 delivery readiness를 degraded로 표시한다.

readiness contract는 다음과 같다.

- `UP`: required adapter ready, active clinic policy 존재, oldest vacancy < 2분, failed job 0
- `DEGRADED`: provider failure ratio 5% 이상/5분, oldest vacancy 2–5분, unknown delivery 존재
- `OUT_OF_SERVICE`: adapter/schema/policy 없음, oldest vacancy > 5분, failed job > 0 또는
  expired backlog > 100

warning은 oldest vacancy 2분, expired backlog 20, retry exhaustion 1에서 on-call ticket으로
보내고 critical은 oldest vacancy 5분, failed job 1, adapter unavailable, expired backlog
100에서 page한다. metric에는 lease reclaim count/duration, candidate scanned page/row,
lock retry/contention, security denial/override attempt를 추가한다.

## 14. Compatibility, migration, rollback

- V19는 additive이며 V18 schema와 API를 유지한다.
- 기존 V18 offer는 V19 policy가 없으면 delivery 대상이 아니다.
- migration은 세 dialect에서 table, constraint, index, FK 의미를 검증한다.
- 배포 rollback은 위 mode matrix대로 dispatch/new delivery runner만 중단한다.
- schema downgrade와 row 삭제는 하지 않는다.
- partially processed job과 offer는 다음 배포의 recovery가 이어서 처리한다.

V19 preflight는 Flyway version, table-name collision, migration lock timeout 30초, 예상 row/index
공간과 active transaction을 확인한다. postcheck는 새 table/index/unique/FK 수, policy scope
CAS, vacancy generation uniqueness, command key uniqueness를 dialect별 SQL/metadata assertion으로
검증한다. 실패한 migration은 Flyway repair 전에 원인을 기록하고 새 migration으로만 수정하며
부분 schema를 application-ready로 보지 않는다.

retention owner는 `appointment-api` maintenance scheduler다. command record 24시간,
vacancy terminal row 90일, policy/adjustment event와 waitlist offer/audit 1년,
notification history/outbox는 기존 notification retention을 따른다. active, unresolved,
legal/audit hold row는 purge하지 않는다. purge query는 terminal status, `updated_at < cutoff`,
unresolved reference 없음, legal/audit hold 없음을 모두 predicate로 사용하고 oldest-first
100 row transaction으로 실행한다. 실패 시 같은 cursor를 exponential backoff로 재시도하고
purged/skipped/failed count와 oldest eligible age를 기록한다.

runbook preflight는 migration/readiness/active policy/backlog/Redis leader 상태를 확인한다.
rollback 성공 기준은 새 claim 0, in-flight DB lease 0, expiry/suppression backlog drain, active
hold 불일치 0이다. 재활성화는 failed/unknown row를 operator가 분류·requeue/suppress하고 위
readiness가 `UP`일 때만 허용한다. migration은 예상 여유 공간 2배 미만, 30초 이상 active
DDL-blocking transaction, Flyway lock 획득 실패가 있으면 hold한다. evidence에는 dialect,
schema version, row/index estimate, lock wait, constraint/index count, timestamp를 남긴다.

## 15. 주요 실패 모드와 대응

### 15.1 transaction commit 후 event publish 전에 프로세스 종료

durable vacancy job이 남으므로 scheduler가 처리한다. event는 latency 최적화일 뿐
신뢰성 권위가 아니다.

### 15.2 두 worker가 같은 vacancy를 처리

job lease/version, active vacancy unique key, offer/hold transaction이 중복 생성을
막는다. loser는 기존 offer를 조회해 idempotent 결과로 종료한다.

### 15.3 notification 성공 직후 worker 종료

provider result가 unknown이면 기존 notification outbox 정책에 따라
`DELIVERY_RESULT_UNKNOWN`으로 기록하고 자동 duplicate delivery를 피한다. offer는
`OFFERED`로 유지되며 staff audit/manual-review queue에 수동 조치가 표시된다. operator는
provider 조회 후 delivered evidence 기록 또는 suppression만 수행할 수 있고 offer를 직접
accept하지 않는다. expiry가 먼저 오면 terminal expiry가 우선한다.

### 15.4 confirm 중 slot이 다른 예약으로 점유

resource lock과 capacity 재검증이 replacement 생성을 거부한다. offer/hold는 일관된
terminal 또는 retryable 상태로 남고 `409 Conflict`를 반환한다.

### 15.5 policy activation과 offer 생성 경합

offer transaction이 읽은 policy generation/digest를 decision snapshot에 기록한다.
활성 generation이 transaction 중 바뀌면 bounded retry하거나 stale policy conflict로
job을 재시도한다.

### 15.6 leader lease 상실과 긴 batch

작은 transaction과 DB job lease를 사용한다. 다음 leader가 lease-expired job만 회수해
중복 terminal event를 만들지 않는다. 이전 worker의 terminal write는 expired fence로
거부되고 `LEASE_FENCED` audit를 남긴다.

## 16. 검증 전략

### 16.1 단위/도메인

- hard eligibility 각 실패 reason
- scoring 순서와 entry ID tie-breaker
- restriction expiry/release, recovery credit consume/reversal, benefit grant cap/revoke
- preview와 execution generation drift
- notification suppression과 TTL clamp
- strict policy document size/depth/unknown-field 검증과 canonical digest
- vacancy generation, command reservation, fence state machine

### 16.2 transaction/integration

- cancellation/no-show와 vacancy job atomicity
- vacancy job/offer/outbox rollback atomicity
- 동일 vacancy 동시 worker와 동일 offer 동시 confirm
- replacement appointment exactly-once와 stale slot conflict
- restart recovery, lease expiry, repeated expiry no-op
- canonical lock order, timeout, deadlock/serialization retry와 fence-loss no-op
- notification external IO가 DB transaction 밖에서 실행되고 late result가 terminal state를
  되살리지 않음

### 16.3 DB matrix

- H2, PostgreSQL, MySQL V19 migration
- table/index/FK/check constraint 동등성
- keyset query와 critical index `EXPLAIN`
- 세 dialect의 concurrent claim/confirm/policy activation 의미 동등성
- isolated fixture cleanup과 순차 Testcontainers 실행

### 16.4 API/security

- tenant/clinic scope와 `STAFF`/`ADMIN` role matrix
- issuer/audience/expiry/tenant-group/clinic membership/`WAITLIST_OVERRIDE` JWT negative matrix
- idempotency replay/conflict
- `400/403/404/409/503` mapping
- decision explanation redaction
- OpenAPI schema와 example

### 16.5 performance와 recovery drill

- 10,000 active entry/clinic, 1,000 vacancy, 5,000 notification backlog fixture에서
  300 vacancy/min, p95 2초, 10분 catch-up, lock wait p99 500ms 기준 검증
- candidate page/row cutoff와 PostgreSQL/MySQL/H2 query plan assertion
- Redis leader churn/outage 중 DB fence가 capacity/terminal event 중복을 막는 검증
- failed job requeue, unknown delivery suppression, flag-off drain과 readiness threshold drill

### 16.6 module 검증 순서

1. `:appointment-core:test`
2. `:appointment-event:test`
3. `:appointment-notification:test`
4. `:appointment-api:test`
5. affected module build와 Detekt/static scan
6. 세 dialect migration matrix를 순차 실행
7. `git diff --check`

재시도 후에만 통과한 lifecycle/concurrency 테스트는 원인을 조사하고 fresh rerun한다.

## 17. 문서 산출물

- `README.md`와 `README.ko.md`: staff waitlist 운영 흐름과 설정을 동등하게 설명
- requirements: policy, API, security, failure behavior
- 기존 V18 문서 `docs/runbooks/waitlist-core.md`는 유지하고 새
  `docs/runbooks/waitlist-delivery.md`에 V19 운영을 분리한다. 새 runbook은
  `./gradlew :appointment-api:flywayInfo`, Actuator health/metric query, deployment config의
  `appointment.waitlist.delivery.enabled`/allowlist 변경, failed-job requeue와 unknown-delivery
  suppress admin endpoint, 위 rollback mode matrix, 재활성화 gate를 exact command/expected
  output과 evidence template으로 제공한다.
- public/internal contract KDoc: 한국어
- `docs/lessons/`: 구현과 review에서 재사용 가능한 실패·복구 교훈

reader-facing diagram이 필요해지면 영문/한글 source-equivalent asset을 함께 만든다.
현재 설계 확정에는 diagram이 필수 증거가 아니므로 구현 계획에서 실제 README 인지
부하를 줄이는지 다시 평가한다.

## 18. Acceptance criteria

- [ ] 당일 cancellation 또는 `NO_SHOW`가 같은 transaction에서 하나의 durable vacancy
  job을 만든다.
- [ ] event 유실과 프로세스 재시작 후에도 vacancy job이 복구된다.
- [ ] tenant, clinic, treatment, doctor/time window, restriction, clinical rule의 hard
  eligibility가 scoring 전에 적용된다.
- [ ] deterministic policy 결과와 version/digest/score breakdown/adjustment reference가
  offer에 감사 가능하게 남는다.
- [ ] candidate query가 scope keyset index, bounded page/row cutoff, dialect별 실행 계획
  계약을 지킨다.
- [ ] offer, capacity hold, notification outbox, vacancy completion이 원자적으로 기록된다.
- [ ] notification 실패나 unknown 결과가 offer를 delivered 또는 accepted로 잘못
  전이하지 않는다.
- [ ] 중복·동시 confirm이 replacement appointment를 하나만 생성한다.
- [ ] command reservation, vacancy generation uniqueness, canonical lock order와 DB lease
  fencing이 동시 실행과 leader churn에서 exactly-once terminal 결과를 보장한다.
- [ ] expired offer, stale decision, occupied slot은 stable conflict로 거부된다.
- [ ] expired offer가 hold를 release하고 유효 slot이면 다음 generation으로 진행하며,
  clinic-local/DST 경계에서도 동일하게 판정된다.
- [ ] active offer를 가진 entry의 withdraw가 hold를 release하고 유효 slot이면 다음
  generation으로 진행한다.
- [ ] staff API가 tenant/clinic scope, role, idempotency, privacy redaction을 지킨다.
- [ ] JWT issuer/audience/actor/scope/permission 계약과 모든 mutation DTO/status/replay/error
  계약이 OpenAPI와 negative test로 고정된다.
- [ ] leader failover와 restart recovery가 bounded batch로 동작하며 Redis가 DB claim
  권위를 대체하지 않는다.
- [ ] readiness/alert/runbook, no-policy/flag-off 동작, failed/unknown work의 operator recovery가
  수치화된 기준과 함께 검증된다.
- [ ] baseline workload에서 300 vacancy/min, p95 2초, 10분 catch-up, lock wait p99 500ms를
  만족한다.
- [ ] H2/PostgreSQL/MySQL migration 및 core/event/notification/API 테스트가 통과한다.
- [ ] 한국어 KDoc·requirements/runbook과 영문/한글 README가 구현과 일치한다.
- [ ] exact-head Kotlin checklist와 6-R/7-Tier 결과가 P0=0/P1=0이다.

## 19. Definition of Done

- [ ] 승인된 spec에 대한 2-R review가 P0=0/P1=0이다.
- [ ] 모든 acceptance criterion이 3-R을 통과한 implementation plan에 매핑된다.
- [ ] TDD RED/GREEN 증거와 fresh module/DB matrix 결과가 있다.
- [ ] public API, KDoc, README locale, runbook과 metric/health 계약이 동기화됐다.
- [ ] exact implementation head의 6-R/7-Tier가 P0=0/P1=0이다.
- [ ] lesson이 commit되고 GNO에서 검색된다.
- [ ] Issue #170 metadata와 연결된 PR CI/review가 완료된다.
- [ ] fresh merge 승인 후 merge, local sync, worktree/branch cleanup까지 완료된다.

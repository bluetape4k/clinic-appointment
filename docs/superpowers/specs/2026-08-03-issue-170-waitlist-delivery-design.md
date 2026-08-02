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

알림 성공은 offer를 `ACCEPTED`로 만들지 않는다. 고객 응답 또는 승인된 staff confirm만
claim을 수행한다.

### 5.4 `appointment-api`

- tenant/clinic scope와 staff actor 검증
- vacancy application service와 after-commit event publication
- waitlist entry, offer, policy, adjustment, audit HTTP API
- replacement appointment 생성 adapter
- Redis leader election을 재사용하는 bounded expiry/recovery scheduler
- Micrometer metric, Actuator health, feature flag와 clinic allowlist

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

job은 예약 상태 전이와 같은 transaction에서 생성한다. `SlotAvailable` event 유실 시
scheduler가 `READY` 또는 expired `PROCESSING` job을 복구한다.

### 6.7 `scheduling_waitlist_command_records`

staff mutation의 scope, command type, `Idempotency-Key` digest, request digest,
result reference와 expiry를 저장한다. 동일 key와 동일 request는 기존 결과를 반환하고,
동일 key와 다른 request는 conflict다.

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

explicit staff override는 기본 score component가 아니다. 권한 있는 `ADMIN` 또는
clinic `STAFF`가 hard eligibility를 통과한 후보 사이에서만 기본 1위를 교체할 수 있는
별도 결정 단계다. override는 대상 후보, 기존 1위, actor, bounded reason code와
correlation ID를 기록한다. clinical/operational eligibility, active restriction의
staff-approval 요구, slot compatibility는 override로 우회할 수 없다.

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

2–8은 하나의 transaction이다. outbox insert가 실패하면 offer와 hold도 rollback한다.

### 8.3 notification delivery

1. worker가 outbox row를 claim한다.
2. offer state와 expiry를 다시 조회한다.
3. terminal/expired이면 `SUPPRESSED`와 reason code를 기록한다.
4. member profile과 template을 resolve해 resilient channel로 전달한다.
5. 성공/실패/unknown 결과를 notification history와 waitlist offer event에 기록한다.

실패는 offer state를 변경하지 않는다. retry가 offer expiry를 넘지 않도록 next attempt를
clamp한다.

### 8.4 staff confirm

1. command idempotency record를 확인한다.
2. offer, entry, hold, vacancy resource를 정해진 순서로 잠근다.
3. scope, state, expiry, decision freshness, slot capacity를 다시 검증한다.
4. 기존 appointment/resource allocation service를 통해 replacement appointment를 만든다.
5. replacement가 hold를 consume한 뒤 offer/entry/hold를 `ACCEPTED`로 전이한다.
6. appointment와 waitlist audit event, command result를 같은 transaction에 기록한다.

두 동시 confirm 중 하나만 성공한다. 동일 idempotency request는 생성된 appointment를
반환한다. 다른 key 또는 다른 request의 stale confirm은 `409 Conflict`다.

### 8.5 decline, withdraw, expiry

- decline은 active offer/hold를 `DECLINED`/released로 전환하고 다음 candidate를 위한
  동일 vacancy job을 새 generation으로 재활성화할 수 있다.
- withdraw는 entry와 active offer/hold를 terminal 처리한다.
- expiry scheduler는 시작 시각, offer expiry, hold expiry 중 가장 이른 경계를 사용한다.
- 모든 반복 실행은 이미 terminal인 row를 no-op success로 처리한다.

## 9. Staff HTTP API

기본 path는 `/api/{tenantCode}/clinics/{clinicId}/waitlist`다.

### 9.1 entry와 offer

- `POST /entries`
- `GET /entries?status=&cursor=&limit=`
- `GET /entries/{entryId}`
- `POST /entries/{entryId}/withdraw`
- `GET /offers/{offerId}`
- `POST /offers/{offerId}/confirm`
- `POST /offers/{offerId}/decline`
- `GET /offers/{offerId}/decision`

mutation은 `Idempotency-Key`를 요구한다. list는 bounded keyset pagination을 사용한다.

### 9.2 policy와 adjustment

- `POST /policies/versions`
- `POST /policies/versions/{policyVersionId}/activate`
- `GET /policies/effective`
- `POST /policies/preview`
- member-scoped restriction, recovery credit, benefit grant create/release/revoke API
- bounded policy/adjustment audit API

policy activation과 grant mutation은 `ADMIN` 권한을 요구한다. entry/offer 운영은
clinic `STAFF` 또는 `ADMIN`이 수행한다. 기존 `TenantClinicAccessChecker`와
`ActorContextResolver`를 재사용한다.

### 9.3 HTTP 오류 계약

- `400 Bad Request`: 형식, 범위, 중복 ID, invalid effective period
- `403 Forbidden`: tenant/clinic 권한 또는 actor role 부족
- `404 Not Found`: 해당 scope에서 보이지 않는 entry/offer/policy
- `409 Conflict`: stale version, expired offer, occupied slot, reused idempotency key
- `503 Service Unavailable`: 필수 adapter 또는 DB recovery subsystem 비가용

오류 body는 stable reason code와 correlation ID만 제공하며 내부 score나 PII를 포함하지
않는다.

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

## 11. Scheduler와 recovery

`appointment-api`의 기존 Redis leader election adapter를 재사용한다.

- `vacancy-dispatch`: `READY`와 lease-expired `PROCESSING` job을 bounded batch 처리
- `offer-expiry`: expired offer/hold를 bounded batch terminal 처리
- `notification-suppression`: terminal/expired offer의 pending notification을 suppression
- `stuck-hold-reconcile`: V18 recovery service를 호출해 누락/불일치 hold 복구

leader lease를 잃으면 현재 batch 이후 종료하고 DB lease가 만료된 뒤 다른 leader가
재개한다. scheduler는 한 transaction에 전체 batch를 넣지 않고 job/offer 단위 또는
작은 resource group 단위로 commit한다.

## 12. Configuration과 rollout

- `appointment.waitlist.core.enabled`: 기존 V18 core flag
- `appointment.waitlist.delivery.enabled=false`: phase-two global default
- `appointment.waitlist.delivery.clinic-allowlist`: 점진적 clinic 활성화
- `appointment.waitlist.offer-ttl=15m`
- bounded batch size, retry backoff, lease duration, maximum attempts

rollout 순서는 V19 migration → adapter readiness → shadow preview → clinic allowlist →
active delivery다. flag off는 새 vacancy job과 offer delivery를 중단하지만 기존 row를
삭제하지 않는다. 재활성화 전에 recovery와 backlog 상태를 확인한다.

## 13. Observability

low-cardinality metric을 추가한다.

- vacancy job created/processed/retried/failed count
- eligible/no-candidate/offer-created count와 typed reason
- offer delivery sent/suppressed/retry/exhausted count
- confirm success/idempotent/conflict count
- active offer/hold, expired backlog, oldest vacancy age
- policy preview/activation conflict count

Actuator health는 DB backlog와 required adapter readiness를 분리해 보고한다. provider
실패는 waitlist claim readiness를 내리지 않지만 delivery readiness를 degraded로 표시한다.

## 14. Compatibility, migration, rollback

- V19는 additive이며 V18 schema와 API를 유지한다.
- 기존 V18 offer는 V19 policy가 없으면 delivery 대상이 아니다.
- migration은 세 dialect에서 table, constraint, index, FK 의미를 검증한다.
- 배포 rollback은 delivery flag를 끄고 scheduler를 중단한다.
- schema downgrade와 row 삭제는 하지 않는다.
- partially processed job과 offer는 다음 배포의 recovery가 이어서 처리한다.

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
`OFFERED`로 유지되며 staff audit에 수동 조치가 표시된다.

### 15.4 confirm 중 slot이 다른 예약으로 점유

resource lock과 capacity 재검증이 replacement 생성을 거부한다. offer/hold는 일관된
terminal 또는 retryable 상태로 남고 `409 Conflict`를 반환한다.

### 15.5 policy activation과 offer 생성 경합

offer transaction이 읽은 policy generation/digest를 decision snapshot에 기록한다.
활성 generation이 transaction 중 바뀌면 bounded retry하거나 stale policy conflict로
job을 재시도한다.

### 15.6 leader lease 상실과 긴 batch

작은 transaction과 DB job lease를 사용한다. 다음 leader가 lease-expired job만 회수해
중복 terminal event를 만들지 않는다.

## 16. 검증 전략

### 16.1 단위/도메인

- hard eligibility 각 실패 reason
- scoring 순서와 entry ID tie-breaker
- restriction expiry/release, recovery credit consume/reversal, benefit grant cap/revoke
- preview와 execution generation drift
- notification suppression과 TTL clamp

### 16.2 transaction/integration

- cancellation/no-show와 vacancy job atomicity
- vacancy job/offer/outbox rollback atomicity
- 동일 vacancy 동시 worker와 동일 offer 동시 confirm
- replacement appointment exactly-once와 stale slot conflict
- restart recovery, lease expiry, repeated expiry no-op

### 16.3 DB matrix

- H2, PostgreSQL, MySQL V19 migration
- table/index/FK/check constraint 동등성
- keyset query와 critical index `EXPLAIN`
- isolated fixture cleanup과 순차 Testcontainers 실행

### 16.4 API/security

- tenant/clinic scope와 `STAFF`/`ADMIN` role matrix
- idempotency replay/conflict
- `400/403/404/409/503` mapping
- decision explanation redaction
- OpenAPI schema와 example

### 16.5 module 검증 순서

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
- `docs/runbooks/waitlist-core.md`: V19 readiness, rollout, rollback, backlog recovery
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
- [ ] offer, capacity hold, notification outbox, vacancy completion이 원자적으로 기록된다.
- [ ] notification 실패나 unknown 결과가 offer를 delivered 또는 accepted로 잘못
  전이하지 않는다.
- [ ] 중복·동시 confirm이 replacement appointment를 하나만 생성한다.
- [ ] expired offer, stale decision, occupied slot은 stable conflict로 거부된다.
- [ ] staff API가 tenant/clinic scope, role, idempotency, privacy redaction을 지킨다.
- [ ] leader failover와 restart recovery가 bounded batch로 동작하며 Redis가 DB claim
  권위를 대체하지 않는다.
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

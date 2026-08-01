# Issue #170 대기 목록·당일 빈자리 회복 코어 설계 기준

상태: 설계 승인 완료, 구현 전
작성일: 2026-08-01
대상: `clinic-appointment`
관련 이슈: #170, #176

## 1. 결정 요약

이번 변경은 Issue #170 전체를 한 번에 닫는 작업이 아니라, 대기 목록과 offer의 상태·영속성·동시성 경계를 먼저 고정하는 1차 코어 슬라이스다. 알림과 외부 API를 붙이기 전에 데이터베이스가 단일 claim을 보장하고, 기존 예약 상태 전환이 생태계 상태 머신으로 옮겨져도 같은 업무 계약을 유지하는 것을 완료 조건으로 삼는다.

핵심 결정은 다음과 같다.

1. `bluetape4k-dependencies:1.3.1`을 유지하고 versionless `bluetape4k-states` catalog alias와 `appointment-core` 의존성을 추가한다. 로컬 1.3.1 POM이 `bluetape4k-bom:1.11.0`을 import하고, 해당 BOM이 `bluetape4k-states:1.11.0`을 관리하는 근거를 확인했다. 실제 artifact resolution은 구현 첫 검증에서 확인하며, 실패하면 로컬 FSM을 복제하지 않고 release-train 후속 작업으로 중단한다.
2. 기존 `AppointmentStateMachine`의 공개 API와 현재 전이·terminal-state·callback 의미는 유지한다. 전이 선언의 권위는 ecosystem DSL로 옮기고, 동기 `nextState` 호환 facade와 suspend `TransitionResult` 경로를 동일 선언에서 동등성 테스트한다. hand-built `Map<Pair<State, EventClass>, State>`는 제거한다.
3. 대기 항목의 현재 lifecycle은 `WAITING -> OFFERED -> ACCEPTED | DECLINED | EXPIRED | WITHDRAWN`으로 고정한다. 상태 전이는 typed DSL과 append-only history로 남긴다.
4. 대기 항목은 tenant·clinic·member ID·진료 유형·선택적 의사·희망 날짜/시간대·명시 priority만 저장한다. 이름·전화번호·상담 메모·회원 프로필 원문은 저장하거나 로그에 기록하지 않는다.
5. 후보 선택은 먼저 tenant/clinic/진료 유형/의사/시간대의 hard eligibility를 SQL에서 적용하고, `BookingReliabilityDecision`을 소비해 자동 offer 가능 여부를 결정한다. 정책 점수·recovery credit·benefit grant의 전체 evaluator는 이번 단계에서 다시 구현하지 않는다.
6. 선택 순서는 `slotFit DESC -> priorityRank DESC -> waitingSince ASC -> entryId ASC`로 고정한다. slot fit과 priority는 bounded 값이며, 동률은 항상 양의 ID로 해소한다.
7. offer claim은 호출자가 소유한 Exposed `transaction {}` 안에서 offer 재조회, 만료·슬롯 점유·신뢰도 결정 재검증, `state + version` CAS를 수행한다. Redis lock은 사용하지 않는다. 이 단계의 claim 결과는 후속 appointment/member 경계가 사용할 durable handoff이며, 알림·expiry worker·실제 replacement appointment 생성은 다음 단계다.

## 2. 배경과 문제

현재 `NO_SHOW`와 `CANCELLED` 예약은 슬롯 계산에서 비점유 상태가 되지만, 빈자리를 대기 고객에게 안전하게 재사용하는 aggregate가 없다. Issue #176은 회원별 booking reliability 결정을 immutable snapshot으로 저장하고 있으므로, #170이 이름·전화번호·원 예약 이력을 복제하지 않고 그 결정만 소비해야 한다.

이 코어가 없는 상태에서 API나 알림부터 추가하면 다음 문제가 생긴다.

- 두 직원 요청이 같은 빈자리를 동시에 제안하거나 확정한다.
- 만료된 offer가 현재 시각 이후에도 accepted 상태가 된다.
- clinic이 다른 대기 항목을 잘못 선택할 수 있다.
- 기존 예약 FSM과 새 offer FSM의 오류·terminal-state 의미가 서로 달라진다.
- 고객관리시스템의 개인정보가 appointment DB와 알림 payload에 중복 저장된다.

따라서 이번 단계는 데이터 모델, 상태 전이, 후보 정렬, 결정 소비, DB claim 원자성만 고정한다.

## 3. 목표와 비목표

### 목표

- `bluetape4k-states` DSL 기반으로 기존 예약 FSM의 동작 동등성을 증명한다.
- tenant/clinic 범위의 영속 대기 항목과 구체적인 빈자리 offer를 저장한다.
- offer lifecycle과 상태 이력을 typed 계약으로 제공한다.
- `BookingReliabilityDecision`의 정책 버전·digest·만료를 offer snapshot에 결합한다.
- 같은 빈자리에 대한 동시 claim에서 성공을 하나로 제한한다.
- H2, PostgreSQL, MySQL에서 같은 migration과 conflict semantics를 검증한다.
- 후속 API·알림·replacement appointment 단계가 사용할 안정적인 core port를 제공한다.

### 비목표

- tenant-scoped staff API, patient self-service, magic link, 결제·보증금 처리는 구현하지 않는다.
- `SlotAvailable` domain event, `NotificationChannel`, delivery retry, expiry scheduler는 구현하지 않는다.
- `ClinicWaitlistPolicy`, booking restriction, disruption credit, benefit grant evaluator를 새로 만들지 않는다. 이번 단계는 이미 저장된 reliability decision을 소비한다.
- 회원 DB에서 이름·전화번호를 동기화하거나 appointment row에 임시 연락처를 채우지 않는다.
- claim을 Redis-only lock으로 보호하지 않는다.
- Kafka/RabbitMQ outbox, Timefold 실시간 최적화, 대규모 배치 재배정은 다루지 않는다.
- 이 PR로 Issue #170을 닫지 않는다. 후속 통합 PR은 `Refs #170`을 사용한다.

## 4. 현재 계약과 근거

| 근거 | 이번 설계에서 지키는 계약 |
|---|---|
| `appointment-core/.../statemachine/AppointmentStateMachine.kt` | `transition`, `nextState`, `canTransition`, `allowedEvents`, callback과 기존 오류 의미 |
| `appointment-core/.../model/reliability/BookingReliabilityRecords.kt` | opaque `MemberId`, decision stamp, bounded reason code와 digest |
| `appointment-core/.../repository/BookingReliabilityRepository.kt` | tenant·clinic·member 범위의 latest decision 조회와 `forUpdate` 경계 |
| `appointment-core/.../model/tables/ResourceCapacityBuckets.kt` | 자원 overlap을 고정 mutex row로 직렬화하는 기존 DB 경계 |
| `appointment-core/.../repository/ResourceAllocationRepository.kt` | capacity conflict와 active allocation 검증 |
| `appointment-core/.../repository/AppointmentRepository.kt` | Exposed transaction 안에서 예약 projection을 다루는 기존 repository 규약 |
| `appointment-api/src/main/resources/db/migration/*/V17__add_booking_reliability.sql` | 다음 additive migration 번호는 V18이며 H2/PostgreSQL/MySQL 파일을 함께 유지 |
| `~/.gradle/.../bluetape4k-dependencies/1.3.1.pom` | `bluetape4k-bom:1.11.0` import |
| `~/.gradle/.../bluetape4k-bom/1.11.0.pom` | `bluetape4k-states:1.11.0` dependency management |

모든 Exposed 조회·삽입·수정은 호출자가 소유한 `transaction {}` 안에서만 수행한다. 다른 서비스 호출을 열린 DB transaction 안에서 실행하지 않는다.

## 5. 대안과 선택

| 대안 | 장점 | 단점 | 결정 |
|---|---|---|---|
| ecosystem-first 코어 슬라이스 | 상태 DSL, DB claim, 개인정보 경계를 먼저 고정하고 API·알림을 후속으로 분리 | 이번 단계만으로 사용자 알림까지 동작하지 않음 | 채택 |
| 기존 local FSM 유지 후 API부터 구현 | 단기 변경량이 작음 | Issue의 ecosystem-first 요구를 어기고 두 상태 머신 계약이 생김 | 기각 |
| states release-train을 먼저 별도 변경 | artifact 공급 경계가 가장 명확함 | cross-repo release가 선행되어 clinic 작업이 지연됨 | artifact resolution 실패 시에만 전환 |

## 6. 식별자와 개인정보 원칙

### 6.1 판단·저장 키

대기 항목의 논리 범위는 `(tenantGroupId, clinicId, memberId)`다. `MemberId`는 회원 서비스가 발급한 불투명 식별자로 취급하며 예약 서비스가 이메일·전화번호 형식이나 내부 의미를 해석하지 않는다.

offer와 history에는 다음 식별자만 저장한다.

- tenant group ID, clinic ID, waitlist entry ID, offer ID
- opaque member ID
- treatment type ID, 선택적 doctor ID, vacancy key
- reliability decision ID, policy version/hash, evaluation digest

### 6.2 금지 항목

다음 값은 waitlist/offer table, audit history, metric label, 로그, 예외 메시지에 넣지 않는다.

- 회원 이름, 전화번호, 이메일, 주소와 기타 profile 원문
- 자유 입력 상담 메모, 직원의 주관적 “문제 고객” 설명
- 회원 프로필 조회 응답 전체와 JWT 원문
- reliability 사건 전체 payload와 원문 취소 사유

후속 API가 화면 표시용 이름·전화번호를 필요로 하면 회원관리시스템의 권한 경계에서 조회한다. 이 core는 member ID만 다음 단계로 전달한다.

## 7. 상태 모델과 DSL 계약

### 7.1 대기 항목 lifecycle

`WaitlistEntryState`는 다음 닫힌 집합이다.

```text
WAITING
OFFERED
ACCEPTED
DECLINED
EXPIRED
WITHDRAWN
```

허용 전이는 다음과 같다.

```text
WAITING  --offer selected--> OFFERED
WAITING  --staff withdraw--> WITHDRAWN
OFFERED  --claim accepted--> ACCEPTED
OFFERED  --patient/operation decline--> DECLINED
OFFERED  --expiry or start passed--> EXPIRED
OFFERED  --staff withdraw--> WITHDRAWN
```

terminal state는 `ACCEPTED`, `DECLINED`, `EXPIRED`, `WITHDRAWN`이다. phase 1에서는 terminal entry를 다시 `WAITING`으로 되돌리지 않는다. 다른 빈자리를 다시 기다리려면 새 entry를 만들며, 자동 requeue는 후속 정책 이슈다.

상태 전이 함수는 현재 상태와 typed event를 받아 ecosystem `suspendStateMachine`의 `TransitionResult`를 반환한다. transition error는 호출자가 예외를 삼키고 성공으로 처리할 수 없는 안정적인 domain exception으로 매핑한다. 상태와 history insert는 한 transaction에서 함께 수행한다.

### 7.2 기존 예약 FSM migration

기존 예약 FSM의 전이 선언은 local `Map` 대신 DSL builder에 등록한다. 기존 `transition(currentState, event)`는 suspend DSL machine을 사용하고, public `nextState`, `canTransition`, `allowedEvents`는 동일한 선언을 공유하는 compatibility facade에서 제공한다. facade가 별도 전이 목록을 갖지 않도록 다음을 검증한다.

1. 모든 기존 정상 경로가 같은 다음 상태를 반환한다.
2. invalid transition과 final state가 같은 예외 종류·메시지 범위를 유지한다.
3. callback은 성공 전이에만 한 번 호출된다.
4. `allowedEvents`와 `canTransition` 결과가 DSL machine과 일치한다.
5. `TransitionResult.previousState/currentState/event`가 현재 명령의 상태와 일치한다.

## 8. 영속 모델

### 8.1 `WaitlistEntries`

물리 table 이름은 `scheduling_waitlist_entries`로 고정한다.

| 컬럼 | 의미 |
|---|---|
| `id` | Long PK |
| `tenant_group_id` | SaaS tenant FK, `RESTRICT` |
| `clinic_id` | 병원 FK, clinic 범위 |
| `member_id` | opaque 회원 ID |
| `treatment_type_id` | 요청 진료 유형 FK |
| `doctor_id` | 선택적 담당 의사 FK |
| `preferred_date_from`, `preferred_date_to` | 희망 날짜 범위 |
| `preferred_start_time`, `preferred_end_time` | 희망 시간 범위 |
| `priority_rank` | 직원이 부여한 bounded priority, 큰 값이 우선 |
| `status` | `WaitlistEntryState` 문자열 |
| `version` | CAS version, 0부터 단조 증가 |
| `created_at`, `updated_at` | DB UTC 시각 |

`member_id`에는 길이·공백 validation을 적용한다. `(tenant_group_id, clinic_id, status, preferred_date_from, id)`와 `(clinic_id, treatment_type_id, status, id)` 조회 인덱스를 둔다. entry 하나에는 phase 1에서 동시에 하나의 concrete offer만 허용한다.

### 8.2 `WaitlistOffers`

물리 table 이름은 `scheduling_waitlist_offers`로 고정한다. offer는 대상 빈자리와 reliability decision의 immutable snapshot을 보존한다.

| 컬럼 | 의미 |
|---|---|
| `id` | Long PK |
| `waitlist_entry_id` | `WaitlistEntries` FK |
| `vacancy_key` | clinic·slot·resource capacity token의 canonical hash |
| `doctor_id`, `treatment_type_id` | offer 시점의 slot snapshot |
| `starts_at`, `ends_at` | UTC slot 시각 |
| `expires_at` | offer 수락 가능 만료 시각 |
| `booking_reliability_decision_id` | #176 decision FK/opaque reference |
| `booking_reliability_policy_version_id` | decision policy version |
| `booking_reliability_policy_hash` | lowercase SHA-256 |
| `booking_reliability_evaluation_digest` | decision digest |
| `booking_reliability_expires_at` | decision 유효 시각 |
| `candidate_rank` | 선택 당시 결정적 순위 snapshot |
| `selection_reason_code` | bounded 이유 코드 |
| `version` | offer claim CAS version |
| `created_at`, `updated_at` | DB UTC 시각 |

`(waitlist_entry_id)` unique로 현재 offer 하나를 보장하고, canonical hash인 `vacancy_key` 자체에 unique 제약을 둬 동일 capacity token의 중복 offer를 차단한다. vacancy key에는 clinic scope가 포함되므로 clinic 간 충돌을 허용하지 않는다. capacity가 여러 개인 자원은 기존 `ResourceCapacityBuckets`의 고정 bucket/resource ID를 vacancy key에 포함한다. 실제 appointment allocation의 최종 capacity 판정은 후속 replacement command가 기존 `ResourceAllocationRepository`를 통해 다시 수행한다.

### 8.3 `WaitlistOfferEvents`

물리 table 이름은 `scheduling_waitlist_offer_events`로 고정한다.

- `waitlist_entry_id`, nullable `offer_id`, `from_state`, `to_state`
- `reason_code`, `actor_ref`, `correlation_id`
- `occurred_at`, `event_version`

entry 생성 시 `offer_id`가 아직 없으므로 nullable인 history row로 `WAITING` snapshot을 append한다. concrete offer가 만들어진 뒤에는 `offer_id`를 채운 전이만 기록한다. 이후 모든 전이는 append-only로 기록하고 current state는 `WaitlistEntries.status`가 보유한다. 자유 텍스트 reason은 받지 않으며 actor는 검증된 command boundary에서만 전달한다.

## 9. 후보 매칭과 reliability decision 소비

### 9.1 hard eligibility

`WaitlistCandidateMatcher`는 vacancy를 다음 값으로 받는다.

```text
tenantGroupId, clinicId, treatmentTypeId, doctorId,
startsAt, endsAt, resourceCapacityToken, now
```

SQL에서 다음 조건을 먼저 적용한다.

1. entry의 tenant와 clinic이 vacancy 범위와 같다.
2. 상태가 `WAITING`이다.
3. treatment type이 일치한다.
4. 지정 doctor가 있으면 vacancy doctor와 일치한다. 미지정 doctor는 clinic 정책이 허용하는 범위의 후보로만 남긴다.
5. 희망 날짜와 시간이 vacancy 전체 구간을 포함한다.
6. vacancy 시작 시각이 `now`보다 뒤다.

tenant·clinic이 다른 row가 FK만으로 후보가 되지 않도록 scope predicate를 항상 SQL에 포함한다.

### 9.2 decision mapping

hard eligibility 통과 후보마다 `BookingReliabilityRepository` 또는 같은 계약의 provider에서 최신 decision을 읽는다. decision의 PII는 매칭 코드로 전달하지 않는다.

| decision | 자동 candidate 처리 |
|---|---|
| `ELIGIBLE`, `OVERRIDDEN`, `POLICY_DISABLED` | 후보로 유지 |
| `REQUIRES_STAFF_APPROVAL` | 자동 offer에서 제외하고 후속 staff-review 경로로 남김 |
| `RESTRICTED` | 자동 offer 후보에서 제외 |
| `STALE`, `UNAVAILABLE` | 자동 offer 후보에서 제외하고 재평가/수동 검토 필요 |
| decision 없음 | 판단 없이 추측하지 않고 `DECISION_UNAVAILABLE`으로 처리 |

decision이 없다는 사실을 영구 제한으로 해석하지 않는다. 다음 단계의 evaluator/재평가 job이 `ELIGIBLE` 또는 명시된 결과를 저장한 뒤 다시 매칭해야 한다.

offer에 저장하는 decision ID·policy version·hash·digest·expiresAt은 claim 직전에 다시 확인한다. decision이 만료됐거나 digest가 바뀌면 stale conflict로 실패하고 새 offer를 만들지 않는다.

### 9.3 결정적 순서

후보의 우선순위는 다음 tuple을 오름차순/내림차순 규칙과 함께 명시한다.

```text
(-slotFit, -priorityRank, waitingSince, entryId)
```

`slotFit`은 지정 의사가 vacancy와 정확히 일치하면 1, 미지정이면 0이다. `priorityRank`는 0 이상 bounded 정수이며, 같은 값이면 오래 기다린 entry를 먼저 선택한다. `entryId`는 최종 deterministic tie-break다. 정책 점수·recovery credit·benefit grant가 추가되는 후속 단계도 이 tuple의 마지막 `waitingSince, entryId` 안정성을 유지한다.

후보 조회는 keyset page를 사용하고 page size는 기본 100으로 제한한다. 한 page의 후보가 reliability decision에서 제외되면 다음 page를 같은 정렬 기준으로 계속 읽는다. offset paging과 random order는 사용하지 않는다.

## 10. 단일 claim transaction

`WaitlistOfferClaimService.claim(command)`은 caller가 연 `transaction {}`에서 다음 순서로 실행한다.

1. `offerId`, tenant, clinic, expected version을 양수·scope 검증한다.
2. `WaitlistEntries`와 `WaitlistOffers`를 tenant/clinic predicate와 함께 `forUpdate`로 다시 읽는다.
3. current state가 `OFFERED`인지 확인한다. 이미 `ACCEPTED`면 duplicate conflict, 다른 terminal state면 lifecycle conflict를 반환한다.
4. DB current timestamp로 `expiresAt`과 slot `startsAt`을 확인한다. 만료되었거나 시작 시각이 지났으면 `EXPIRED` 전이와 history를 기록하고 claim을 거부한다.
5. 저장된 reliability decision stamp를 최신 decision과 비교한다. `RESTRICTED`, `STALE`, `UNAVAILABLE`, 만료 decision은 claim을 거부한다.
6. 기존 resource capacity/appointment projection을 재검증한다. 다른 transaction이 먼저 capacity를 차지했으면 `SLOT_OCCUPIED`를 반환하고 offer를 accepted로 바꾸지 않는다.
7. `UPDATE ... WHERE id = ? AND status = OFFERED AND version = expectedVersion`을 실행하고 update count가 1인지 확인한다. 0이면 `VERSION_CONFLICT`다.
8. 성공하면 offer version과 entry version을 단조 증가시키고 `OFFERED -> ACCEPTED` history를 append한다. transaction 결과는 member ID와 accepted offer ID만 후속 replacement command에 전달한다.

같은 caller의 재시도도 새 appointment를 만들지 않는 idempotency 계약은 후속 API 단계에서 command idempotency와 함께 완성한다. 이번 core는 같은 offer를 두 번 accepted로 만들지 않는 것을 보장한다. DB unique conflict는 예상 가능한 `SLOT_OCCUPIED`/`DUPLICATE_CLAIM`으로 매핑하고, 원인 불명의 SQL 오류를 성공으로 숨기지 않는다.

## 11. 예외와 관측 경계

core는 다음 bounded 결과를 제공한다.

```text
CandidateFound
NoEligibleCandidate
DecisionUnavailable
OfferAlreadyExists
OfferExpired
OfferWithdrawn
OfferStateConflict
VersionConflict
SlotOccupied
OfferClaimed
```

예외와 log에는 ID·state·reason code·correlation ID만 포함한다. member ID를 metric label로 사용하지 않는다. `NoEligibleCandidate`는 제한된 후보 수와 reason code 집계만 남기고 후보 목록 전체를 로그에 출력하지 않는다.

## 12. Migration 방향

현재 booking reliability migration이 V17이므로 세 DB에 다음 additive migration을 추가한다.

```text
appointment-api/src/main/resources/db/migration/h2/V18__add_waitlist_core.sql
appointment-api/src/main/resources/db/migration/postgresql/V18__add_waitlist_core.sql
appointment-api/src/main/resources/db/migration/mysql/V18__add_waitlist_core.sql
```

각 dialect에서 다음을 동일 의미로 제공한다.

- 세 table과 FK, status/version not-null 제약
- tenant/clinic scope와 deterministic candidate 조회 인덱스
- entry당 active offer unique 제약
- vacancy key 중복 방지 제약
- offer expiry와 history 시간축 인덱스

기존 `scheduling_*` 이름을 변경하지 않는다. down migration이나 운영 table 삭제는 이번 변경에 포함하지 않는다. `TableSchemaTest`는 table/column/index 기대치를 추가하고, Flyway H2/PostgreSQL/MySQL 테스트는 V1부터 V18까지 순차 적용해 schema history와 unique constraint를 확인한다.

## 13. 검증 기준

### 상태 머신

1. 기존 예약 FSM의 전체 정상 전이가 이전과 같은 상태를 반환한다.
2. invalid transition, terminal state, callback 성공/실패 의미가 유지된다.
3. waitlist lifecycle의 모든 허용 전이와 terminal state가 성공한다.
4. 역방향·중복·terminal 전이는 bounded domain exception으로 거부된다.
5. transition result와 append-only history가 같은 from/to/event를 갖는다.

### 후보·결정

1. 다른 tenant/clinic/treatment/doctor/time-window row가 후보에 들어오지 않는다.
2. slot fit, priority, waiting age, ID 순서가 DB·메모리 실행에서 동일하다.
3. `ELIGIBLE` 계열만 자동 후보로 남고 제한·stale·unavailable decision은 제외된다.
4. decision stamp가 offer에 저장되고 만료·digest 변경이 claim을 거부한다.
5. member 이름·전화번호·자유 텍스트가 table, record, log에 나타나지 않는다.

### claim·migration

1. 두 DB connection이 같은 offer를 동시에 claim해도 정확히 하나만 `OfferClaimed`가 된다.
2. stale version, expired offer, started slot, occupied capacity, terminal state가 각각 deterministic conflict를 반환한다.
3. 성공한 claim은 entry/offer version과 history를 한 transaction에서 함께 갱신한다.
4. H2, PostgreSQL, MySQL migration과 unique/FK/index 검증이 통과한다.
5. `appointment-core:test`와 migration test가 순차 실행되고, pre-existing 테스트의 동작이 유지된다.

## 14. 구현 산출물과 후속 경계

이번 core PR의 예상 변경 영역은 다음과 같다.

- `gradle/libs.versions.toml`, `appointment-core/build.gradle.kts`
- `appointment-core/.../statemachine/AppointmentStateMachine.kt`와 동등성 테스트
- `appointment-core/.../model/waitlist/*` typed records와 lifecycle events
- `appointment-core/.../model/tables/WaitlistEntries.kt`, `WaitlistOffers.kt`, `WaitlistOfferEvents.kt`
- `appointment-core/.../repository/WaitlistRepository.kt`
- `appointment-core/.../service/WaitlistCandidateMatcher.kt`, `WaitlistOfferClaimService.kt`
- 세 dialect의 `V18__add_waitlist_core.sql`와 migration/schema tests

다음 단계에서 별도 설계·검증할 항목은 다음과 같다.

1. `SlotAvailable` 이벤트와 appointment cancellation/`NO_SHOW` publisher 연결
2. expiry worker와 clinic별 offer TTL 설정
3. tenant-scoped staff API와 policy preview/explanation
4. member ID를 받아 회원관리시스템에서 표시 identity를 채우는 notification/API adapter
5. replacement appointment 생성, command idempotency, outbox/알림 delivery history
6. full policy evaluator, restriction/recovery credit/benefit grant와 staff approval 경로

이 후속 항목이 완성되기 전에는 Issue #170을 완료 처리하지 않는다.

## 15. 설계 self-review checklist

- [x] 기존 예약의 `CONFIRMED` 등 terminal/보호 상태를 자동 변경하지 않는다.
- [x] 대기 후보와 offer에 이름·전화번호를 복제하지 않는다.
- [x] tenant와 clinic scope를 모든 조회·수정 predicate와 FK에 포함한다.
- [x] offer 만료와 slot start 시각을 DB current timestamp로 재검증한다.
- [x] Redis lock을 consistency source로 사용하지 않고 DB CAS를 권위로 둔다.
- [x] `bluetape4k-states` artifact가 실제로 resolve되지 않으면 local 복제로 우회하지 않는다.
- [x] H2/PostgreSQL/MySQL과 동시성 테스트를 구현 전에 검증 기준으로 고정했다.
- [x] API·알림·정책 evaluator를 이번 코어 PR의 완료 조건으로 과장하지 않는다.

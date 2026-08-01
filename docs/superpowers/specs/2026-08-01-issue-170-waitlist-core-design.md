# Issue #170 대기 목록·당일 빈자리 회복 코어 설계 기준

상태: 설계 승인 완료, P0/P1 경계 수정 승인 및 Step 2-R 재검토 중
작성일: 2026-08-01
대상: `clinic-appointment`
관련 이슈: #170, #176

> 2026-08-01 설계 수정: offer를 `ACCEPTED`로 바꾸기 전에 실제 자원 점유를
> `ResourceAllocationRepository`가 인식하는 durable capacity hold로 같은
> transaction 안에서 확보한다. 이 수정은 기존 승인 범위를 바꾸지 않고, 후속
> replacement appointment가 사용할 handoff의 정합성 경계를 보강한다.

## 1. 결정 요약

이번 변경은 Issue #170 전체를 한 번에 닫는 작업이 아니라, 대기 목록과 offer의 상태·영속성·동시성 경계를 먼저 고정하는 1차 코어 슬라이스다. 알림과 외부 API를 붙이기 전에 데이터베이스가 단일 claim을 보장하고, 기존 예약 상태 전환이 생태계 상태 머신으로 옮겨져도 같은 업무 계약을 유지하는 것을 완료 조건으로 삼는다.

핵심 결정은 다음과 같다.

1. `bluetape4k-dependencies:1.3.1`을 유지하고 versionless `bluetape4k-states` catalog alias와 `appointment-core` 의존성을 추가한다. 로컬 1.3.1 POM이 `bluetape4k-bom:1.11.0`을 import하고, 해당 BOM이 `bluetape4k-states:1.11.0`을 관리하는 근거를 확인했다. 실제 artifact resolution은 구현 첫 검증에서 확인하며, 실패하면 로컬 FSM을 복제하지 않고 release-train 후속 작업으로 중단한다.
2. 기존 `AppointmentStateMachine`의 공개 API와 현재 전이·terminal-state·callback 의미는 유지한다. 전이 선언의 권위는 ecosystem DSL로 옮기고, 동기 `nextState` 호환 facade와 suspend `TransitionResult` 경로를 동일 선언에서 동등성 테스트한다. hand-built `Map<Pair<State, EventClass>, State>`는 제거한다.
3. 대기 항목의 현재 lifecycle은 `WAITING -> OFFERED -> ACCEPTED | DECLINED | EXPIRED | WITHDRAWN`으로 고정한다. 상태 전이는 typed DSL과 append-only history로 남긴다.
4. 대기 항목은 tenant·clinic·member ID·진료 유형·선택적 의사·희망 날짜/시간대·명시 priority만 저장한다. 이름·전화번호·상담 메모·회원 프로필 원문은 저장하거나 로그에 기록하지 않는다.
5. 후보 선택은 먼저 tenant/clinic/진료 유형/의사/시간대의 hard eligibility를 SQL에서 적용하고, `BookingReliabilityDecision`을 소비해 자동 offer 가능 여부를 결정한다. 정책 점수·recovery credit·benefit grant의 전체 evaluator는 이번 단계에서 다시 구현하지 않는다.
6. 선택 순서는 `slotFit DESC -> priorityRank DESC -> waitingSince ASC -> entryId ASC`로 고정한다. slot fit과 priority는 bounded 값이며, 동률은 항상 양의 ID로 해소한다.
7. offer 생성과 claim은 호출자가 소유한 Exposed `transaction {}` 안에서 자원 mutex, durable capacity hold, offer·entry 상태를 함께 갱신한다. `ResourceAllocationRepository`는 확정 allocation과 `OFFERED`/`ACCEPTED` waitlist hold를 같은 overlap·capacity 계산에 포함한다. Redis lock은 사용하지 않는다. `ACCEPTED`는 실제 appointment가 이미 생성됐다는 뜻이 아니라, replacement command가 소비하거나 명시적으로 해제해야 하는 durable hold가 있다는 뜻이다.
8. offer와 hold의 scope는 `(tenantGroupId, clinicId, memberId)`를 기준으로 고정한다. reliability decision provider도 같은 세 값을 입력으로 받고 동일 scope stamp를 반환해야 하며, offer 단독 조회·수정은 repository scope predicate와 entry 일치 검증을 통과한 경우에만 허용한다.
9. `WaitlistOffers.status`를 offer aggregate의 권위 상태로 추가하고, vacancy의 재사용은 immutable `vacancy_key`와 active 상태에서만 채워지는 nullable `active_vacancy_key`의 unique 제약으로 보장한다. decline·expiry·withdraw 시 hold와 active key를 같은 transaction에서 해제한다.

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
- offer와 claim이 자원 mutex·capacity hold·offer·entry를 하나의 transaction에서
  확정하도록 하여, 실제 replacement appointment가 아직 생성되지 않은 구간에도
  빈자리를 다시 배정할 수 없게 한다.
- H2, PostgreSQL, MySQL에서 같은 migration과 conflict semantics를 검증한다.
- 후속 API·알림·replacement appointment 단계가 사용할 안정적인 core port를 제공한다.

### 비목표

- tenant-scoped staff API, patient self-service, magic link, 결제·보증금 처리는 구현하지 않는다.
- `SlotAvailable` domain event, `NotificationChannel`, delivery retry, expiry scheduler는 구현하지 않는다.
- `ClinicWaitlistPolicy`, booking restriction, disruption credit, benefit grant evaluator를 새로 만들지 않는다. 이번 단계는 이미 저장된 reliability decision을 소비한다.
- 회원 DB에서 이름·전화번호를 동기화하거나 appointment row에 임시 연락처를 채우지 않는다.
- claim을 Redis-only lock으로 보호하지 않는다.
- Kafka/RabbitMQ outbox, Timefold 실시간 최적화, 대규모 배치 재배정은 다루지 않는다.
- 외부 scheduler를 새로 구현하지는 않지만, offer 생성·claim transaction과 operator
  recovery command가 만료된 offer/hold를 bounded batch로 수렴시키는 경계를 제공한다.
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

`ResourceAllocationRepository`는 기존 `scheduling_resource_allocations`만 읽는
repository로 남기지 않는다. 이번 코어에서 추가하는
`scheduling_waitlist_capacity_holds`의 `OFFERED`와 `ACCEPTED` row도 동일한 자원
mutex와 overlap/capacity 검증에 포함한다. 따라서 후속 replacement command가
`createConfirmedAllocations`를 호출할 때도 살아 있는 waitlist hold를 무시할 수 없다.
hold를 `CONSUMED`로 바꾸고 confirmed allocation을 만드는 작업은 후속 command의
같은 transaction에서 수행하며, core claim은 그 handoff에 필요한 `holdId`만 반환한다.

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

offer와 hold의 저장 scope에는 `tenantGroupId`, `clinicId`, `memberId`가 함께
존재한다. offer의 scope 컬럼은 entry의 값을 복제한 immutable snapshot이며,
repository는 insert/update 시 entry와 동일한지 검증한다. 이 중복은 개인정보를
추가 저장하기 위한 것이 아니라 offer 단독 접근에서도 권한 경계를 SQL로 강제하기
위한 것이다. member ID는 여전히 opaque 값이며 metric label이나 로그에는 사용하지
않는다.

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

`WaitlistOffers.status`가 concrete offer의 권위 상태이고,
`WaitlistEntries.status`는 entry aggregate의 요약 상태다. active offer가 있는
동안 두 값은 동일한 전이 transaction에서 갱신되어야 하며, 불일치가 발견되면
읽기 경로는 보수적으로 `OfferStateConflict`를 반환하고 recovery backlog에
기록한다. 따라서 offer 단독 claim에서 entry 상태만으로 성공을 판정하지 않는다.

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
| `waiting_since` | WAITING 순서를 고정하는 immutable UTC 시각 |
| `created_at`, `updated_at` | DB UTC 시각 |

`member_id`에는 길이·공백 validation을 적용한다. `waiting_since`는 최초
`WAITING` 진입 시 한 번만 기록하고 재시도·requeue로 덮어쓰지 않는다.
날짜 범위를 먼저 줄이는
`(tenant_group_id, clinic_id, treatment_type_id, status, preferred_date_from,
preferred_date_to, id)` 보조 인덱스도 둔다.
`(tenant_group_id, clinic_id, treatment_type_id, status, priority_rank DESC,
waiting_since ASC, id ASC)`와 doctor가 지정된 경로를 위한
`(tenant_group_id, clinic_id, doctor_id, treatment_type_id, status,
priority_rank DESC, waiting_since ASC, id ASC)` 조회 인덱스를 둔다. 날짜/time-window hard
eligibility는 이 후보 집합에 bounded predicate로 적용하고, `slotFit`은
doctor-specific query를 먼저 실행한 뒤 unspecified-doctor query를 실행해
random/filesort를 피한다. entry 하나에는 phase 1에서 동시에 하나의 concrete
offer만 허용한다. 활성 offer 존재 여부는 entry 상태만 믿지 않고 offer의 active
status와 unique 제약으로 다시 확인한다.

### 8.2 `WaitlistOffers`

물리 table 이름은 `scheduling_waitlist_offers`로 고정한다. offer는 대상 빈자리와 reliability decision의 immutable snapshot을 보존한다.

| 컬럼 | 의미 |
|---|---|
| `id` | Long PK |
| `tenant_group_id`, `clinic_id`, `member_id` | entry와 일치하는 immutable scope snapshot |
| `waitlist_entry_id` | `WaitlistEntries` FK |
| `vacancy_key` | server가 clinic·slot·resource capacity token을 canonicalize한 SHA-256 hash |
| `active_entry_key` | server가 계산한 entry ID key를 `OFFERED`/`ACCEPTED` 동안만 보관하고 terminal 전이에서 NULL로 지우는 값 |
| `active_vacancy_key` | server가 계산한 `vacancy_key`를 `OFFERED`/`ACCEPTED` 동안만 복제하고 terminal 전이에서 NULL로 지우는 값 |
| `doctor_id`, `treatment_type_id` | offer 시점의 slot snapshot |
| `starts_at`, `ends_at` | UTC slot 시각 |
| `expires_at` | offer 수락 가능 만료 시각 |
| `status` | `OFFERED`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `WITHDRAWN` 중 offer의 권위 상태 |
| `booking_reliability_decision_id` | #176 decision FK/opaque reference |
| `booking_reliability_policy_version_id` | decision policy version |
| `booking_reliability_policy_hash` | lowercase SHA-256 |
| `booking_reliability_evaluation_digest` | decision digest |
| `booking_reliability_expires_at` | decision 유효 시각 |
| `candidate_rank` | 선택 당시 결정적 순위 snapshot |
| `selection_reason_code` | bounded 이유 코드 |
| `version` | offer claim CAS version |
| `created_at`, `updated_at` | DB UTC 시각 |

`(active_entry_key)`와 `(active_vacancy_key)`를 unique하게 유지한다.
PostgreSQL partial index에 의존하지
않고, 세 dialect에서 nullable unique 값에 terminal 전이 시 NULL을 기록하는
portable 규칙을 사용한다. immutable `vacancy_key` 자체는 이력·재시도 추적용이며,
동일 vacancy를 `DECLINED`/`EXPIRED`/`WITHDRAWN` 뒤 다시 offer할 수 있다.
vacancy key에는 clinic scope가 포함되며, capacity가 여러 개인 자원은 기존
`ResourceCapacityBuckets`의 고정 resource ID와 half-open 시간 구간을 포함한다.

`vacancy_key`, `active_entry_key`, `active_vacancy_key`는 client 입력을 받지 않고
server-side canonicalizer가 생성한다. offer 단독 조회·수정은 항상 `(tenant_group_id, clinic_id, id)` scope predicate를
사용하고, 같은 transaction에서 `waitlist_entry_id`의 scope/member와 일치하는지
검증한다. 이 검증이 실패하면 `OFFER_SCOPE_MISMATCH`로 거부한다.

offer를 `OFFERED`로 저장할 때 같은 transaction에서
`scheduling_waitlist_capacity_holds` row도 `OFFERED`로 만든다. hold가 살아 있는
동안 `ResourceAllocationRepository`는 이를 active occupancy로 집계하므로, 알림을
아직 보내지 않았거나 claim 요청이 재시도되는 동안에도 같은 vacancy를 다른
offer가 차지할 수 없다. offer terminal 전이에서는 hold를 먼저
`RELEASED`/`EXPIRED`로 바꾸고 `active_vacancy_key`를 NULL로 만든 뒤 history를
append한다.

### 8.2.1 `WaitlistCapacityHolds`

물리 table 이름은 `scheduling_waitlist_capacity_holds`로 고정한다. 이 table은
appointment proposal을 대신하는 임시 row가 아니라, 후속 replacement command가
소비할 수 있는 자원 점유의 durable handoff다.

| 컬럼 | 의미 |
|---|---|
| `id` | Long PK, claim 결과로 전달하는 hold ID |
| `tenant_group_id`, `clinic_id`, `member_id` | offer/entry와 일치하는 immutable scope snapshot |
| `offer_id` | `WaitlistOffers` FK, offer당 hold 하나를 보장하는 unique 키 |
| `vacancy_key`, `active_vacancy_key` | server-side canonical hash와 offer의 active key 규칙 |
| `resource_type`, `resource_id` | `ResourceAllocationRepository`가 잠그는 자원 key |
| `starts_at`, `ends_at`, `capacity_units`, `maximum_capacity` | allocation과 같은 half-open capacity snapshot |
| `status` | `OFFERED`, `ACCEPTED`, `CONSUMED`, `RELEASED`, `EXPIRED` |
| `hold_expires_at` | OFFERED는 offer 만료, ACCEPTED는 slot 시작 시각을 상한으로 하는 recovery deadline |
| `version`, `created_at`, `updated_at`, `released_at`, `consumed_at` | CAS·감사·recovery 정보 |

`OFFERED`와 `ACCEPTED`만 active 상태다. `ResourceAllocationRepository`의 기존
active allocation 조회·capacity timeline에 두 상태를 함께 포함하며, `CONSUMED`는
후속 replacement allocation이 성공한 뒤에만 기록한다. `RELEASED`/`EXPIRED`는
capacity를 반환하지만 row와 event를 삭제하지 않는다. accepted hold가 slot 시작
시각까지 소비되지 않으면 bounded recovery가 `EXPIRED`로 수렴시키고 operator
경보를 남긴다.

hold를 insert·update·reconcile·consume하는 모든 경로는 hold의
`tenant_group_id`, `clinic_id`, `member_id`가 연결된 offer와 entry의 동일 컬럼과
일치하는지 같은 transaction에서 검증한다. hold ID만으로 상태를 바꾸는 경로는
허용하지 않으며, 불일치는 `HOLD_SCOPE_MISMATCH`로 거부하고 capacity나 상태를
변경하지 않는다.

### 8.3 `WaitlistOfferEvents`

물리 table 이름은 `scheduling_waitlist_offer_events`로 고정한다.

- `waitlist_entry_id`, nullable `offer_id`, `from_state`, `to_state`
- nullable `hold_id`, `reason_code`, bounded `actor_ref`, `correlation_id`
- `occurred_at`, `event_version`

entry 생성 시 `offer_id`가 아직 없으므로 nullable인 history row로 `WAITING` snapshot을 append한다. concrete offer가 만들어진 뒤에는 `offer_id`를 채운 전이만 기록한다. 이후 모든 전이는 append-only로 기록하고 entry summary는 `WaitlistEntries.status`, concrete offer의 현재 값은 `WaitlistOffers.status`가 보유한다. 자유 텍스트 reason은 받지 않으며 actor는 검증된 command boundary에서만 전달한다.

hold 생성·활성화·소비·해제도 동일 event stream에 연결한다. `actor_ref`는
`SYSTEM`, 검증된 staff principal digest, 또는 bounded recovery command ID 중
하나이며 원문 이름·JWT·전화번호를 받지 않는다. reason은 enum/bounded code만
허용하고 raw SQL exception과 자유 입력을 저장하지 않는다.

## 9. 후보 매칭과 reliability decision 소비

### 9.1 hard eligibility

`WaitlistCandidateMatcher`는 vacancy를 다음 값으로 받는다.

```text
tenantGroupId, clinicId, treatmentTypeId, doctorId,
startsAt, endsAt, resourceCapacityToken, now
```

`resourceCapacityToken`은 API caller가 만든 문자열이 아니라 appointment/resource
projection에서 읽은 server-owned immutable descriptor다. descriptor에는 resource
type·ID·capacity units·maximum capacity와 half-open 시간 구간이 포함되며,
canonicalizer가 이 값 전체와 clinic scope를 hash해 `vacancy_key`를 만든다. 외부
caller는 token·active key·vacancy hash를 주입할 수 없다.

SQL에서 다음 조건을 먼저 적용한다.

1. entry의 tenant와 clinic이 vacancy 범위와 같다.
2. 상태가 `WAITING`이다.
3. treatment type이 일치한다.
4. 지정 doctor가 있으면 vacancy doctor와 일치한다. 미지정 doctor는 clinic 정책이 허용하는 범위의 후보로만 남긴다.
5. 희망 날짜와 시간이 vacancy 전체 구간을 포함한다.
6. vacancy 시작 시각이 `now`보다 뒤다.

tenant·clinic이 다른 row가 FK만으로 후보가 되지 않도록 scope predicate를 항상 SQL에 포함한다.

후보 조회는 현재 상태 snapshot을 잠그는 최종 transaction과 분리된 read 단계다.
read 단계는 `slotFit, priority_rank, waiting_since, id` keyset cursor와 bounded page(기본 100, 최대
500)를 사용하고, 후보를 실제 offer로 승격할 때는 자원 mutex를 먼저 잡은 뒤
entry를 다시 읽어 `WAITING`, active offer 없음, 같은 scope를 재확인한다. 같은
vacancy를 처리하는 두 호출이 동시에 들어오면 mutex와 `active_vacancy_key`
unique conflict 중 하나가 승자가 되고, 패자는 bounded retry 또는
`OfferAlreadyExists`로 끝난다.

### 9.2 decision mapping

hard eligibility 통과 후보 page마다 `BookingReliabilityRepository` 또는 같은
계약의 batch provider를 한 번 호출한다. 입력은
`(tenantGroupId, clinicId, memberIds, evaluatedAt)`이며, 반환 stamp마다
`tenantGroupId`, `clinicId`, `memberId`, `decisionId`, `policyVersion`, `digest`,
`expiresAt`가 포함되어야 한다. 후보별 단건 조회를 반복하는 N+1 경로는 사용하지
않으며, provider가 일부 member를 반환하지 않으면 해당 후보만
`DECISION_UNAVAILABLE`로 분류한다. decision의 PII와 사건 원문은 매칭 코드로
전달하지 않는다. 이 provider는 appointment DB 안의 immutable decision snapshot을
읽는 local repository port여야 한다. 외부 회원/정책 서비스 호출이 필요한 경우
transaction을 열기 전에 batch로 끝내고, transaction 안에서는 저장된 decision
row와 stamp만 재검증한다.

| decision | 자동 candidate 처리 |
|---|---|
| `ELIGIBLE`, `OVERRIDDEN`, `POLICY_DISABLED` | 후보로 유지 |
| `REQUIRES_STAFF_APPROVAL` | 자동 offer에서 제외하고 후속 staff-review 경로로 남김 |
| `RESTRICTED` | 자동 offer 후보에서 제외 |
| `STALE`, `UNAVAILABLE` | 자동 offer 후보에서 제외하고 재평가/수동 검토 필요 |
| decision 없음 | 판단 없이 추측하지 않고 `DECISION_UNAVAILABLE`으로 처리 |

decision이 없다는 사실을 영구 제한으로 해석하지 않는다. 다음 단계의 evaluator/재평가 job이 `ELIGIBLE` 또는 명시된 결과를 저장한 뒤 다시 매칭해야 한다.

offer에 저장하는 decision ID·policy version·hash·digest·expiresAt은 claim 직전에
같은 `(tenantGroupId, clinicId, memberId)` scope로 다시 확인한다. decision이
만료됐거나 digest가 바뀌거나 scope가 다르면 stale conflict로 실패하고 새 offer를
`ACCEPTED`로 만들지 않는다. claim은 transaction 시작 시 주입된 `Clock`에서
`now`를 한 번 캡처해 expiry·decision·slot 검증과 응답 snapshot에 재사용한다.

### 9.3 결정적 순서

후보의 우선순위는 다음 tuple을 오름차순/내림차순 규칙과 함께 명시한다.

```text
(-slotFit, -priorityRank, waitingSince, entryId)
```

`slotFit`은 지정 의사가 vacancy와 정확히 일치하면 1, 미지정이면 0이다. `priorityRank`는 0 이상 bounded 정수이며, 같은 값이면 오래 기다린 entry를 먼저 선택한다. `entryId`는 최종 deterministic tie-break다. 정책 점수·recovery credit·benefit grant가 추가되는 후속 단계도 이 tuple의 마지막 `waitingSince, entryId` 안정성을 유지한다.

`waiting_since`를 실제 column으로 저장하고 `(tenant_group_id, clinic_id,
treatment_type_id, status, priority_rank DESC, waiting_since ASC, id ASC)`와
doctor-specific 변형 index가 동일 실행 계획을 보장하는지 dialect별로 검증한다.
마이그레이션 메타데이터 assertion은 두 인덱스 모두
`priority_rank:D`, `waiting_since:A`, `id:A` 방향을 확인해야 한다. PostgreSQL과
MySQL representative dataset의 `EXPLAIN`은 full scan/filesort 없이 이
keyset 경로를 사용해야 한다.
후보 조회는 keyset page를 사용하고 page size는 기본 100, 최대 500으로 제한한다.
한 매칭 invocation은 최대 10 page/1,000 candidate 평가 또는 2초 budget 중 먼저
도달한 지점에서 멈추며, 남은 후보는 다음 recovery tick으로 넘긴다. 한 page의
후보가 reliability decision에서 제외되면 다음 page를 같은 정렬 기준으로 계속
읽는다. offset paging과 random order는 사용하지 않는다. 구현 계획에는
representative dataset에 대한 `EXPLAIN`/실행 계획, full scan/filesort 여부,
decision batch round-trip 수와 budget 초과 결과를 기록하는 검증을 포함한다.

### 9.4 offer 생성 원자성

`selectAndOffer(vacancy, now)`는 다음 순서를 지킨다.

1. transaction 시작 시 `now`를 한 번 캡처하고 vacancy의
   `ResourceCapacityBuckets` mutex를 정규화된 key 순서로 잠근다.
2. `WAITING` 후보를 keyset으로 읽고 local decision snapshot을 page 단위로
   batch 조회한다. 후보별 결정이 바뀌면 해당 후보만 제외하고 다음 후보로
   진행한다. 외부 provider refresh가 필요하면 이 transaction 전에 수행한다.
3. 후보 entry를 다시 `forUpdate`로 읽어 scope·version·active offer 부재를
   확인한 뒤, `WaitlistOffers(status=OFFERED, active_entry_key,
   active_vacancy_key)`와 `WaitlistCapacityHolds(status=OFFERED)`를 함께
   insert한다.
4. entry CAS, offer insert, hold insert, `WAITING -> OFFERED` history가 모두
   성공해야 commit한다. active entry/vacancy unique conflict는 예상 가능한
   `OfferAlreadyExists`로 분류하고 최대 3회까지만 다음 후보/재시도를 허용한다.
5. insert 실패가 원인 불명의 SQL error이면 transaction을 rollback하고 성공으로
   숨기지 않는다. hold를 만들지 못한 offer row는 남기지 않는다.

이 순서는 자원 mutex -> hold -> offer -> entry -> decision snapshot이라는 공통
lock order를 따른다. 단순 후보 read는 잠금을 잡지 않으며, concrete vacancy를
다루는 모든 mutation은 이 순서를 재사용한다. `FOR UPDATE SKIP LOCKED` 같은
dialect별 옵션은 필수가 아니며, H2/PostgreSQL/MySQL에서 같은 bounded retry와
unique conflict 의미를 사용한다.

## 10. 단일 claim transaction

`WaitlistOfferClaimService.claim(command)`은 caller가 연 `transaction {}`에서 다음
순서로 실행한다.

1. `offerId`, tenant, clinic, expected version을 양수·scope 검증한다. transaction
   시작 시 주입된 `Clock`에서 `now`를 한 번 캡처한다.
2. offer snapshot의 resource key를 사용해 `ResourceAllocationRepository`의
   resource mutex를 먼저 잠근다. 같은 transaction의 canonical lock order는
   `resource mutex -> capacity hold -> offer -> entry -> reliability snapshot`이다.
3. `(tenant_group_id, clinic_id, offer_id)` predicate로 offer와 연결 entry/hold를
   `forUpdate`로 다시 읽고, denormalized scope/member가 서로 일치하는지 확인한다.
   일치하지 않으면 `OFFER_SCOPE_MISMATCH`로 중단한다.
4. current offer status가 `OFFERED`인지 확인한다. 이미 `ACCEPTED`이면 기존
   `holdId`를 포함한 동일 결과를 replay하고, 다른 terminal state면 lifecycle
   conflict를 반환한다.
5. `now >= expiresAt` 또는 `now >= startsAt`이면 hold를
   `EXPIRED`로 전이하고 active key를 NULL로 만든 뒤 offer/entry도 `EXPIRED`로
   전이하고 history를 append한다. 만료 처리와 claim 거부는 같은 transaction이다.
6. transaction 밖에서 준비한 decision stamp를 local
   `BookingReliabilityRepository`의 같은 `(tenantGroupId, clinicId, memberId,
   now)` scope row와 다시 확인한다. 외부 service/network 호출은 열린 DB
   transaction 안에서 수행하지 않는다.
   `RESTRICTED`, `STALE`, `UNAVAILABLE`, 만료 decision 또는 scope mismatch면
   `DECISION_STALE`/`DECISION_UNAVAILABLE`로 거부하고 `ACCEPTED` 전이를 하지 않는다.
7. `ResourceAllocationRepository`가 `OFFERED` hold를 이미 보유하고 있는지
   확인한다. legacy 또는 repair 경로로 hold가 없으면 같은 transaction에서
   `reserveWaitlistCapacityHold`를 호출해 자원 capacity를 다시 검증한다. capacity
   conflict면 `SLOT_OCCUPIED`를 반환하고 offer/entry 상태를 유지한다.
8. hold를 `ACCEPTED`로 CAS하고, `UPDATE ... WHERE id = ? AND status = OFFERED
   AND version = expectedVersion` 및 entry CAS를 차례로 수행한다. 세 update count와
   history insert가 모두 성공해야 commit하며, 하나라도 0이면 transaction 전체를
   rollback한다.
9. 성공 결과는 `memberId`, `acceptedOfferId`, `holdId`, `holdExpiresAt`만 후속
   replacement command에 전달한다. `ACCEPTED`는 appointment 생성 완료를 뜻하지
   않으며, 후속 command가 같은 transaction에서 hold를 `CONSUMED`로 바꾸고
   `ResourceAllocations`를 insert해야 한다.

같은 caller의 재시도는 `ACCEPTED` offer와 기존 hold를 replay하므로 새 hold나
appointment를 만들지 않는다. decline·withdraw·expiry는 hold release와 offer
terminal 전이를 한 transaction에서 수행한다. DB unique conflict는 예상 가능한
`SLOT_OCCUPIED`/`DUPLICATE_CLAIM`으로 매핑하고, 원인 불명의 SQL 오류를 성공으로
숨기지 않는다.

### 10.1 Core port 계약

이번 단계에서 HTTP endpoint는 만들지 않지만 후속 API가 의존할 port와 결과를
다음처럼 고정한다.

| Port | 성공 결과 | 주요 실패 결과 | transaction 경계 |
|---|---|---|---|
| `selectAndOffer(vacancy, now)` | `CandidateFound(offerId, holdId, rank)` | `NoEligibleCandidate`, `OfferAlreadyExists`, `DecisionUnavailable`, `SlotOccupied` | 자원 mutex·offer·hold·entry·history를 하나로 확정 |
| `claim(command)` | `OfferClaimed(offerId, holdId, memberId, holdExpiresAt)` | `OfferExpired`, `VersionConflict`, `OfferScopeMismatch`, `DecisionStale`, `SlotOccupied` | caller가 연 `transaction {}` 안에서 hold/offer/entry/history CAS |
| `release(command)` | `OfferReleased(offerId, holdId, reason)` | `OfferStateConflict`, `VersionConflict`, `OfferScopeMismatch` | hold release와 terminal 전이를 하나로 확정 |
| `reconcileWaitlistHolds(limit, now)` | `CapacityHoldExpired(count, lastId)` | `RecoveryConflict`, `RecoveryBudgetExceeded` | resource별 bounded transaction 반복 |

모든 결과에는 `correlationId`와 stable reason code가 있으며 member ID는
`OfferClaimed` handoff에만 포함한다. HTTP status, notification payload, 회원 이름·전화번호
채움은 후속 adapter의 책임이다.

### 10.2 만료·recovery 경계

이번 코어에는 전역 scheduler를 넣지 않지만 `selectAndOffer`, `claim`, 그리고
operator가 호출하는 `reconcileWaitlistHolds(limit, now)`가 동일한 transaction
service를 사용한다. 한 번에 최대 100건(설정 상한 500건)만 처리하고, 각 row는
resource mutex를 잡은 뒤 `OFFERED` 만료 또는 slot 시작 이후의 `ACCEPTED` hold만
CAS로 `EXPIRED` 처리한다. 처리 중 connection이 끊기면 transaction rollback 후
다음 실행이 같은 row를 재시도한다.

운영 ownership은 `appointment-core` 서비스 owner가 갖고, `waitlist_hold_active`
및 `waitlist_expiry_backlog` health/metric을 통해 backlog를 관찰한다. active hold를
직접 삭제하는 SQL은 허용하지 않으며, operator command ID와 bounded reason을 가진
recovery command만 release할 수 있다. feature flag를 끄면 신규 offer/claim만
차단하고 기존 hold를 삭제하지 않으므로 rollback 뒤에도 durable 상태와 재처리
경로가 남는다.

`actor_ref`는 내부 opaque staff actor ID 또는 server secret으로 계산한
domain-separated HMAC digest만 허용한다. email·전화번호·JWT subject의 raw hash는
허용하지 않으며, key domain/rotation 정책은 operator runbook에 기록한다.

`correlation_id`는 command boundary에서 1..128자의 `[A-Za-z0-9._:-]` 형식으로
검증한 opaque 값만 허용한다. newline, email/phone/JWT 모양, profile text는 거부하고
로그·metric·exception에는 sanitized correlation ID만 전달한다.

모든 scope, keyset cursor, vacancy key, member ID, actor ref, reason code는
parameterized Exposed DSL expression으로만 전달한다. 문자열을 이어 붙이는 raw SQL,
동적 `ORDER BY`, SQL error text를 domain 결과로 재사용하는 구현은 금지한다.

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
CapacityHoldCreated
CapacityHoldReleased
CapacityHoldConsumed
CapacityHoldExpired
OfferScopeMismatch
DecisionStale
```

예외와 log에는 ID·state·reason code·correlation ID만 포함한다. member ID·vacancy
key 원문·decision payload를 metric label이나 log message에 사용하지 않는다.
`NoEligibleCandidate`는 제한된 후보 수와 reason code 집계만 남기고 후보 목록
전체를 로그에 출력하지 않는다. 최소 운영 지표는
`waitlist_offer_active`, `waitlist_hold_active`, `waitlist_claim_conflict_total`,
`waitlist_decision_unavailable_total`, `waitlist_expiry_backlog`,
`waitlist_hold_reconcile_age_seconds`이며 tenant/clinic은 low-cardinality
allowlist가 있는 경우에만 label로 사용한다.

## 12. Migration 방향

현재 booking reliability migration이 V17이므로 세 DB에 다음 additive migration을 추가한다.

```text
appointment-api/src/main/resources/db/migration/h2/V18__add_waitlist_core.sql
appointment-api/src/main/resources/db/migration/postgresql/V18__add_waitlist_core.sql
appointment-api/src/main/resources/db/migration/mysql/V18__add_waitlist_core.sql
```

각 dialect에서 다음을 동일 의미로 제공한다.

- 네 table(`WaitlistEntries`, `WaitlistOffers`, `WaitlistOfferEvents`,
  `WaitlistCapacityHolds`)과 FK, status/version not-null 제약
- offer/hold의 tenant/clinic/member scope 및 immutable snapshot 검증용 컬럼
- tenant/clinic scope, date-window hard eligibility, deterministic candidate 조회 인덱스
- nullable `active_entry_key`, `active_vacancy_key`의 unique 제약
- hold의 `(offer_id)` unique 제약과 active overlap 조회 인덱스
- offer expiry와 history 시간축 인덱스

기존 `scheduling_*` 이름을 변경하지 않는다. down migration이나 운영 table 삭제는
이번 변경에 포함하지 않는다. nullable unique의 terminal 재사용 semantics는 H2,
PostgreSQL, MySQL에서 각각 `DECLINED -> 새 OFFERED` fixture로 검증한다.
`TableSchemaTest`는 table/column/index 기대치를 추가하고, Flyway
H2/PostgreSQL/MySQL 테스트는 V1부터 V18까지 순차 적용해 schema history, FK,
active unique constraint, hold overlap index를 확인한다.

rollout은 additive migration 후 `appointment.waitlist.core.enabled=false`인
상태로 배포한다. readiness gate는 V18 schema/FK/index 검증,
`bluetape4k-states` artifact compile, 기존 `AppointmentStateMachine` 전이
동등성 테스트, hold/claim concurrency proof, shadow candidate 결과를 모두
요구한다. 이 gate와 representative contention/load proof가 끝난 clinic만
allowlist로 켠다. rollback은 flag off와
신규 command 차단으로 제한하며, 이미 생성된 offer/hold를 삭제하거나 migration을
되돌리지 않는다. 재활성화 전 `reconcileWaitlistHolds`와 backlog/active count를
확인한다.

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
2. 두 DB connection이 같은 vacancy에 동시에 offer를 만들면 하나의 active offer와
   하나의 `OFFERED` hold만 남고, 다른 호출은 bounded conflict/no-candidate로
   수렴한다.
3. accepted claim이 replacement appointment 없이 종료되어도
   `ResourceAllocationRepository`의 availability 조회가 해당 hold를 점유로
   계산하고, 다른 claim/confirmed allocation이 같은 자원을 차지하지 못한다.
4. stale version, expired offer, started slot, occupied capacity, terminal state,
   offer/entry scope mismatch가 각각 deterministic conflict를 반환한다.
5. 성공한 claim은 hold/entry/offer version과 history를 한 transaction에서 함께
   갱신하고, 같은 offer 재시도는 기존 hold ID를 replay한다.
6. decline/expiry/withdraw 뒤 동일 vacancy의 `active_vacancy_key`가 재사용되고,
   이전 hold/history row는 삭제되지 않는다.
7. H2, PostgreSQL, MySQL migration과 unique/FK/index 검증이 통과한다.
8. bounded candidate page에서 reliability provider round-trip이 page 수와
   일치하고, 외부 provider 호출이 열린 transaction 안에서 발생하지 않으며,
   `EXPLAIN`이 keyset/index 경로를 사용한다. 두 candidate 인덱스의
   `priority_rank DESC, waiting_since ASC, id ASC` 방향과 PostgreSQL/MySQL
   full scan/filesort 부재를 migration/query-plan test로 고정한다.
9. popular vacancy/offer contention에서 bounded JDBC pool을 사용해 100개
   동시 offer/claim 시도를 수행한다. active offer/hold 또는 claim 승자는 정확히
   하나이고 나머지는 stable conflict/no-candidate 결과로 수렴하며, deadlock이나
   예상 밖 SQL 오류가 0건이어야 한다. p95 latency와 pool 설정을 결과에 기록하고
   구현 단계의 운영 budget을 초과하지 않아야 한다.
10. hold replay·consume·reconcile에서 다른 member의 hold/offer/entry 조합을
   주입하면 `HOLD_SCOPE_MISMATCH`로 거부되고 상태·capacity·history가 바뀌지
   않는다.
11. `appointment-core:test`와 migration test가 순차 실행되고, pre-existing 테스트의
   동작이 유지된다.

### 운영·복구

1. feature flag off, migration-only, clinic allowlist enable, rollback flag off의
   네 단계를 fake clock과 representative dataset에서 재현한다.
2. `reconcileWaitlistHolds(limit, now)`가 만료 backlog를 100건 이하 batch로
   처리하고, 중간 rollback·재시작 뒤 중복 release나 active count drift가 없다.
3. metric/health/log에 member ID, 이름, 전화번호, raw decision payload가 없고,
   bounded reason·correlation ID만 남는다.
4. malicious cursor/member/actor/reason 입력이 parameterized Exposed predicate를
   벗어나지 않고, raw SQL·동적 sort·SQL error text가 결과나 로그에 나타나지 않는다.

## 14. 구현 산출물과 후속 경계

이번 core PR의 예상 변경 영역은 다음과 같다.

- `gradle/libs.versions.toml`, `appointment-core/build.gradle.kts`
- `appointment-core/.../statemachine/AppointmentStateMachine.kt`와 동등성 테스트
- `appointment-core/.../model/waitlist/*` typed records와 lifecycle events
- `appointment-core/.../model/tables/WaitlistEntries.kt`, `WaitlistOffers.kt`, `WaitlistOfferEvents.kt`
- `appointment-core/.../model/tables/WaitlistCapacityHolds.kt`
- `appointment-core/.../repository/WaitlistRepository.kt`
- `appointment-core/.../repository/ResourceAllocationRepository.kt`의 waitlist hold
  reserve/activate/release/consume 경계
- `appointment-core/.../service/WaitlistCandidateMatcher.kt`, `WaitlistOfferClaimService.kt`
- 세 dialect의 `V18__add_waitlist_core.sql`와 migration/schema tests
- `docs/runbooks/waitlist-core.md` — V18 readiness, feature flag rollout/rollback,
  expiry backlog, stale decision, slot conflict, stuck hold triage

다음 단계에서 별도 설계·검증할 항목은 다음과 같다.

1. `SlotAvailable` 이벤트와 appointment cancellation/`NO_SHOW` publisher 연결
2. expiry worker와 clinic별 offer TTL 설정(이번 코어는 bounded recovery service와
   feature flag/runbook만 제공)
3. tenant-scoped staff API와 policy preview/explanation
4. member ID를 받아 회원관리시스템에서 표시 identity를 채우는 notification/API adapter
5. replacement appointment 생성, command idempotency, outbox/알림 delivery history
6. full policy evaluator, restriction/recovery credit/benefit grant와 staff approval 경로

구현 전 계획 단계에서 `bluetape4k-states` alias를 실제 `appointment-core` compile
classpath에 resolve한다. alias 또는 API가 release train과 맞지 않으면 이 설계를
local FSM 복제로 우회하지 않고 dependency release 후속 이슈로 되돌린다.

이 후속 항목이 완성되기 전에는 Issue #170을 완료 처리하지 않는다.

## 15. 설계 self-review checklist

- [x] 기존 예약의 `CONFIRMED` 등 terminal/보호 상태를 자동 변경하지 않는다.
- [x] 대기 후보와 offer에 이름·전화번호를 복제하지 않는다.
- [x] tenant와 clinic scope를 모든 조회·수정 predicate와 FK에 포함한다.
- [x] transaction 시작 시 주입한 `Clock`의 단일 `now`로 offer 만료와 slot start를
  재검증하고, DB timestamp는 audit 전용으로 둔다.
- [x] Redis lock을 consistency source로 사용하지 않고 DB CAS를 권위로 둔다.
- [x] `bluetape4k-states` artifact가 실제로 resolve되지 않으면 local 복제로 우회하지 않는다.
- [x] H2/PostgreSQL/MySQL과 동시성 테스트를 구현 전에 검증 기준으로 고정했다.
- [x] `OFFERED`/`ACCEPTED` waitlist hold가 `ResourceAllocationRepository`의 active
  capacity 계산에 포함되고, accepted claim과 hold 확정이 같은 transaction이다.
- [x] offer와 hold의 active entry/vacancy unique key는 terminal 전이에서 NULL이
  되어 동일 vacancy를 안전하게 재사용할 수 있다.
- [x] reliability decision batch 조회의 scope stamp, page 상한, keyset/index
  검증을 명시했다.
- [x] feature flag, bounded recovery, metrics, rollback과 operator ownership을
  구현·운영 경계에 포함했다.
- [x] API·알림·정책 evaluator를 이번 코어 PR의 완료 조건으로 과장하지 않는다.

# Issue #176 예약 신뢰도 정책 설계 기준

상태: 구현 완료, 최종 review evidence 반영 완료
작성일: 2026-08-01
대상: `clinic-appointment`
관련 이슈: #176, #170

## 1. 결정 요약

반복적인 `NO_SHOW`와 고객 책임의 지각 취소를 새 예약의 자격 판단에 반영한다. 판단은 기존 `PRIORITY_AND_RELIABILITY` 정책의 유효 스냅숏을 사용하고, 결과를 변경 불가능한 감사 결정으로 저장한다.

핵심 결정은 다음과 같다.

1. 판단 키는 `clinicId + MemberId`다. 예약 도메인은 `MemberId`를 파싱하지 않으며 이름·전화번호·자유 입력 고객 설명을 저장하거나 점수화하지 않는다.
2. `NO_SHOW`와 취소 사건은 고객 책임, 병원·운영 책임, 미확정 책임을 구분하는 typed attribution을 거친다. 병원 휴진·장비 고장·의료진 지연 등 병원 책임 사건은 고객 신뢰도 계산에서 제외한다.
3. 반복 횟수·조회 기간·지각 취소 기준·cooling-off 기간은 정책 버전으로 고정한다. tenant 기준값과 clinic override는 현재 정책 컴파일러의 `INHERIT`/`SET` 규칙을 따른다.
4. evaluator는 `ELIGIBLE`, `RESTRICTED`, `OVERRIDDEN`, `UNAVAILABLE` 중 하나의 결정을 반환하고, 정책 버전·관찰 수·reason code·유효 기간을 함께 남긴다.
5. `#170`은 evaluator를 다시 구현하지 않고 이 결정 계약만 소비한다. 제안·선점·확정 명령 직전에 결정의 만료와 정책 버전을 재검증한다.
6. 기존 `CONFIRMED` 예약은 자동 취소·자동 이동·자동 자원 회수를 하지 않는다. 새 예약 또는 아직 확정되지 않은 `PROPOSED`/`HELD` 흐름에서만 자격 판단을 적용한다.
7. 직원의 override와 clear는 원 결정의 덮어쓰기가 아니라 별도 감사 사건이다. 모든 변경은 actor, 사유 코드, 만료 시각, correlation ID를 남긴다.

이 설계는 고객을 낙인찍는 차단 목록을 만드는 것이 아니다. 관찰 가능한 예약 결과와 책임 원인만으로 짧은 기간의 예약 자격을 계산하고, 설명 가능한 결정과 수동 검토 경로를 제공한다.

## 2. 배경과 문제

현재 `PriorityAndReliabilityPolicy`는 우선순위 가중치, `noShowPenalty`, `sameDayCancellationPenalty`, 최소 점수를 보유한다. 이 계약은 객관적 신호를 downstream optimizer에 전달하지만 다음 질문에는 답하지 않는다.

- 어떤 예약 결과가 실제로 고객 책임 사건인가?
- 몇 건을 어느 기간 동안 관찰해야 제한을 적용하는가?
- 해당 제한이 언제 만료되는가?
- 운영자가 당시의 정책 버전과 입력 사건을 재현할 수 있는가?
- `#170` 대기열·offer 흐름이 같은 결정을 멱등적으로 소비할 수 있는가?

이 정보가 없으면 병원별 기준이 코드에 흩어지고, 병원에서 발생시킨 일정 변경이 고객의 불이행으로 잘못 계산되며, 과거 결정의 근거를 재현할 수 없다. 반대로 예약 이력 전체를 사람의 이름·전화번호와 함께 복제하면 개인정보 보관 범위가 커지고 고객관리시스템과 책임이 중복된다.

## 3. 목표와 비목표

### 목표

- 반복 `NO_SHOW`와 고객 책임 late cancellation을 명시된 임계값으로 평가한다.
- tenant 기본 정책과 clinic별 override를 동일한 effective snapshot으로 컴파일한다.
- 결정·입력 사건·정책 버전·override 이력을 감사 가능한 형태로 보존한다.
- `#170`이 한 번의 조회로 설명 가능한 booking eligibility를 소비하도록 계약한다.
- 대규모 clinic에서도 예약 한 건의 판단은 해당 회원의 bounded history만 읽는다. 현재 구현의
  durable job은 회원 단위 재평가를 lease·cursor로 이어가며, clinic 전체 event keyset backfill은
  별도 후속 작업으로 남긴다.
- 민감정보를 예약 서비스에 복제하지 않고 직원에게 필요한 최소 설명만 제공한다.

### 비목표

- 영구 블랙리스트, 고객 등급, 사람의 성향·위험도 라벨을 도입하지 않는다.
- 결제 보류, 법률상 계약 해지, 보증금·위약금 정책을 구현하지 않는다.
- 고객 셀프서비스 이의신청 workflow를 구현하지 않는다. 다만 직원 override/clear 감사 경로는 제공한다.
- `#170`의 waitlist 정렬, offer 발행, 고객 응답 수명주기를 구현하지 않는다.
- 이미 `CONFIRMED`인 예약을 이 정책으로 자동 변경하거나 취소하지 않는다.
- 회원 DB의 이름·전화번호를 예약·정책 DB로 동기화하지 않는다.

## 4. 기존 계약과 경계

현재 checkout에서 확인한 근거 파일은 다음과 같다.

| 근거 | 확인한 계약 |
|---|---|
| `appointment-core/.../model/policy/CapacityAndReliabilityPolicies.kt` | `PriorityAndReliabilityPolicy`와 clinic override의 현재 필드·불변식 |
| `appointment-core/.../service/SchedulingPolicyPayloadCodec.kt` | policy kind별 wire codec과 `INHERIT`/`SET`/`DISABLE` decoding |
| `appointment-core/.../service/SchedulingPolicyValidator.kt` | payload 범위·override `DISABLE` 허용 규칙 |
| `appointment-core/.../service/SchedulingPolicyHasher.kt` | immutable policy snapshot 재현을 위한 deterministic hash |
| `appointment-core/.../model/commitment/AppointmentCommitmentModel.kt` | `PROPOSED`/`HELD`/`CONFIRMED` 전이와 `confirmedProposalId` 보호 |
| `appointment-core/.../model/identity/MemberId.kt` | opaque member ID의 공백·길이 검증과 legacy column 경계 |
| `appointment-api/.../policy/EffectiveSchedulingPolicyService.kt` | tenant/clinic effective policy 조회와 snapshot 경계 |
| `appointment-api/.../security/ActorContextResolver.kt` | principal 기반 clinic scope와 body actor 위조 방지 |
| `appointment-api/.../profile/ProfileReevaluationEndpoint.kt` | 기존 clinic-scoped preview/worker API의 권한·오류 응답 관례 |

| 기준 | 현재 계약 | 이번 명세에서의 사용 |
|---|---|---|
| 정책 payload | `SchedulingPolicyKind.PRIORITY_AND_RELIABILITY` | 신뢰도 threshold와 기간을 같은 kind의 versioned payload로 확장 |
| clinic override | `OverrideValue`의 `INHERIT`/`SET`/유효한 `DISABLE` | threshold별로 허용 여부를 검증하고 유효 스냅숏에 반영 |
| 회원 식별 | `MemberId`, 물리 호환 컬럼 `patient_external_id` | `MemberId` 값을 opaque key로만 사용 |
| 예약 합의 | `PROPOSED`, `HELD`, `CONFIRMED`, `EXPIRED`, `CANCELLED` | 새 자격 판단은 unconfirmed 흐름에 적용, `CONFIRMED` 보호 |
| 정책 재현 | `effectivePolicySnapshotId` | 결정과 offer/commitment 명령에 동일 snapshot reference 전달 |
| 감사 이유 | bounded stable reason code | 자유 텍스트 대신 allowlist code와 사건 ID를 저장 |

정책 compiler, payload codec, validator, hasher는 payload schema version을 함께 올려야 한다. 기존 snapshot은 역직렬화·재현 가능해야 하며 현재 활성 정책을 다시 읽어 과거 결정을 바꾸지 않는다.

## 5. 개인정보·식별자 원칙

### 5.1 판단 키

판단 키는 `(tenantId, clinicId, memberId)`로 고정한다. 구현에서 tenant가 clinic 경계로 이미 확정되는 경우 저장 projection은 `clinicId + MemberId`를 주 키로 사용할 수 있지만, 감사 조회에는 tenant scope를 포함한다.

`MemberId`는 회원 서비스가 발급한 불투명 값이다. 예약 서비스는 접두사, 숫자, 이메일 형식 등을 해석하지 않는다. 존재하지 않는 회원을 만들거나 ID를 역조회하는 책임도 갖지 않는다.

### 5.2 저장·로그 금지 항목

다음 값은 evaluator 입력·decision record·metric label·로그에 넣지 않는다.

- 이름, 전화번호, 이메일, 주소, 주민등록번호 등 회원 프로필 원문
- 상담 메모, 직원의 주관적 “문제 고객” 설명, 자유 텍스트 취소 사유
- 회원 ID를 해시한 별도 식별자와 원문을 함께 남기는 중복 projection
- 전체 예약 payload, JWT 원문, 연락처 조회 응답

직원 preview 응답은 접근 권한을 확인한 뒤 결정, bounded count, 기간, reason code, 정책 버전만 반환한다. 이름·전화번호가 화면에 필요하면 회원관리시스템의 별도 권한 경계에서 조회하며, 이 서비스의 decision API가 채워 주지 않는다.

### 5.3 접근과 감사

preview/override/clear는 clinic 접근 권한과 `booking-reliability:read` 또는 `booking-reliability:write` capability를 요구한다. actor 정보는 request body가 아니라 검증된 principal에서 얻는다. `MemberId`를 조회한 직원, 명령 actor, correlation ID, 결과 digest는 감사 로그에 남기되 운영 metric에는 raw ID를 넣지 않는다.

## 6. 정책 모델

### 6.1 payload 확장안

기존 `PriorityAndReliabilityPolicy`에 다음 객관 threshold를 추가한다. 정확한 Kotlin property 이름은 구현 계획에서 현재 codec/validator naming과 함께 확정한다.

| 개념 | 의미 | 제약 |
|---|---|---|
| `lookbackDays` | 고객 책임 사건을 볼 과거 기간 | 양수, 플랫폼 상한 이하 |
| `lateCancellationWindowMinutes` | 진료 시작 전 이 시간 이내의 고객 책임 취소를 late cancellation으로 보는 기준 | 양수, clinic override 허용 |
| `noShowThreshold` | 기간 내 고객 책임 no-show 누적 기준 | 0이면 제한 기능을 끄는 의미가 아니라 명시적 검증 규칙으로 처리 |
| `lateCancellationThreshold` | 기간 내 고객 책임 late cancellation 누적 기준 | no-show와 독립적으로 평가 |
| `coolingOffHours` | 제한 결정의 기본 유효 기간 | 양수, 최대 기간 상한 이하 |

기존 `priorityWeights`, penalty, `minimumPriorityScore`는 우선순위 계산과 설명용 입력으로 유지한다. 이번 evaluator의 제한 여부는 threshold와 책임 attribution으로 결정하며 점수 하나만으로 제한하지 않는다. `minimumPriorityScore`보다 낮은 점수라도 threshold에 도달하지 않으면 `ELIGIBLE`이다.

clinic override는 tenant payload에서 상속한다. 값의 범위·최대 기간·`DISABLE` 허용 여부는 validator에 명시한다. threshold를 비활성화할 수 있는 경우에는 `DISABLE`을 “제한 없음”으로 해석하고, 책임 attribution 기능 자체를 비활성화하는 flag는 별도 승인된 feature flag로 둔다. `INHERIT`와 `SET`의 출처는 effective policy 응답과 decision snapshot에 함께 기록한다.

### 6.2 책임 attribution

예약 결과를 정책 사건으로 바꾸는 경계에는 자유 텍스트가 아니라 다음 typed 값이 필요하다.

```text
Outcome: NO_SHOW | CANCELLED
Responsibility: MEMBER | CLINIC | SYSTEM | UNKNOWN
CancellationTiming: NOT_APPLICABLE | ON_TIME | LATE
Source: APPOINTMENT | CLINIC_OPERATION | STAFF_OVERRIDE | IMPORT
```

평가 대상은 다음 조건을 모두 만족하는 사건이다.

1. outcome이 `NO_SHOW`이거나 `CANCELLED`다.
2. responsibility가 `MEMBER`다.
3. `CANCELLED`인 경우 진료 시작 시각과 cancellation 시각으로 `LATE`가 계산된다.
4. 동일 appointment/event ID와 source version이 중복되지 않는다.
5. 사건이 현재 clinic·member scope에 속하고 lookback 기간 안에 있다.

`CLINIC`, `SYSTEM`, `UNKNOWN` 사건은 고객 제한 수에 포함하지 않는다. `UNKNOWN`은 데이터 품질 지표와 audit reason code로만 기록한다. 예를 들어 휴진, 장비 고장, provider 지연, 병원 운영상 강제 취소는 `CLINIC` 또는 `SYSTEM`으로 발행해야 한다.

### 6.3 reason code

모든 외부 결과·로그·감사는 아래 allowlist에서만 선택한다.

| code | 의미 |
|---|---|
| `NO_SHOW_THRESHOLD_EXCEEDED` | lookback 안 고객 책임 no-show가 기준 이상 |
| `LATE_CANCELLATION_THRESHOLD_EXCEEDED` | lookback 안 고객 책임 late cancellation이 기준 이상 |
| `COOLING_OFF_ACTIVE` | 직전 제한 결정의 만료 전 재평가 |
| `UNATTRIBUTED_EVENT_EXCLUDED` | 책임이 확정되지 않아 계산에서 제외 |
| `POLICY_DISABLED` | 유효 정책에서 해당 threshold가 비활성화됨 |
| `MANUAL_OVERRIDE` | 직원 override가 기본 결정을 대체함 |
| `MANUAL_CLEAR` | 직원이 활성 제한을 해제함 |
| `POLICY_SNAPSHOT_STALE` | 호출자가 보낸 snapshot이 더 이상 유효하지 않음 |
| `DECISION_UNAVAILABLE` | 평가 저장소·회원 경계 오류로 결정을 확정하지 못함 |

reason code에는 이름, 전화번호, 직원 메모를 붙이지 않는다. 상세 설명이 필요하면 원 사건 ID와 권한이 있는 감사 조회로만 확인한다.

## 7. evaluator와 결정 계약

### 7.1 조회 계약

`appointment-core`가 소유하는 순수 evaluator 또는 port는 다음 입력을 받는다.

```kotlin
data class BookingEligibilityQuery(
    val tenantId: Long,
    val clinicId: Long,
    val memberId: MemberId,
    val decisionAt: Instant,
    val requestedPolicySnapshotId: Long?,
)
```

결과는 다음 정보를 포함한다.

```kotlin
data class BookingEligibilityDecision(
    val decisionId: Long,
    val decision: BookingDecision, // ELIGIBLE, RESTRICTED, OVERRIDDEN, UNAVAILABLE
    val clinicId: Long,
    val memberId: MemberId,
    val policySnapshotId: Long,
    val policyVersion: Long,
    val noShowCount: Int,
    val lateCancellationCount: Int,
    val reasonCodes: Set<BookingReliabilityReasonCode>,
    val triggeringAppointmentIds: List<Long>,
    val effectiveFrom: Instant,
    val expiresAt: Instant?,
    val evaluationDigest: String,
)
```

`triggeringAppointmentIds`는 bounded 목록이다. 모든 이력 ID를 응답에 담지 않으며, 전체 사건은 감사 저장소의 권한 있는 조회로 확인한다. 동일 query digest와 동일 policy snapshot에 대해서는 같은 결정 record를 재사용할 수 있어야 한다.

### 7.2 판단 순서

1. 호출자의 tenant/clinic/member scope를 검증한다.
2. 요청 시각에 유효한 immutable policy snapshot을 읽는다.
3. 해당 clinic·member의 책임 attribution 사건을 lookback 기간으로 bounded 조회한다.
4. 중복 event ID와 이전 source version을 제거하고 no-show·late cancellation을 각각 집계한다.
5. cooling-off가 남아 있으면 `COOLING_OFF_ACTIVE`를 기록한다.
6. threshold를 넘은 reason code를 구성한다. 하나라도 넘으면 `RESTRICTED`, 아니면 `ELIGIBLE`이다.
7. 결정 snapshot과 evaluation digest를 원자적으로 upsert한다.
8. snapshot이 요청 중 변경됐거나 저장 CAS가 실패하면 재시도한다. 재시도 한도를 넘으면 `DECISION_UNAVAILABLE`을 반환하고 기존 확정 예약은 건드리지 않는다.

조회와 응답의 bounded 상한은 구현 전에 상수로 고정한다. 기본안은 한 평가에서 사건 원장으로부터 최대 100건만 읽고, 결정 응답의 `triggeringAppointmentIds`는 최대 32건만 담는 것이다. count가 상한을 넘으면 전체 ID를 반환하지 않고 `hasAdditionalTriggers=true`와 audit 조회 cursor를 사용한다. 이 상한은 정책 payload가 아니라 서비스 안전 한도이며, metric과 로그에도 같은 제한을 적용한다.

### 7.3 `#170` 소비 규칙

`#170`은 아래 read-only 계약을 호출한다. #176은 이 port와 신규 commitment gate/stamp를
제공하지만, waitlist 후보 생성·offer 발행·고객 응답 소비를 구현하지 않는다.

```text
evaluateBookingEligibility(query) -> BookingEligibilityDecision
```

`RESTRICTED`이면 offer/proposal 생성 정책에 따라 직원 검토 또는 다른 후보를 선택한다. `OVERRIDDEN`이면 override의 만료와 actor audit를 함께 전달한다. `UNAVAILABLE`은 고객을 자동 제한하는 결과가 아니며, #170의 명시된 재시도·수동 검토 경로로 보낸다.

`PROPOSED` 생성, `HELD` 선점, `CONFIRMED` 전환 직전에 decision ID·policy snapshot ID·evaluation digest를 다시 확인한다. 확인 대상이 이미 만료됐거나 정책 버전이 달라졌으면 새 evaluator 호출 없이 확정하지 않는다. 이 재확인은 동시 override와 정책 변경 사이의 stale decision 사용을 막는다.

사건 원장 반영과 결정 snapshot upsert는 호출자가 소유한 reliability transaction에서 수행한다.
정책 snapshot은 기존 권위 policy service 경계에서 읽고, adapter는 이미 열린 transaction을
재사용한다. 외부 회원 서비스·알림·waitlist 호출은 이 transaction 안에서 실행하지 않는다.
transaction commit 뒤 필요한 member 재평가 작업은 durable job으로 발행하고, 동일
`evaluationDigest`와 source version unique key로 재시작을 멱등화한다. clinic 전체 event
keyset backfill과 #170 waitlist/offer lifecycle 연결은 후속 이슈다.

### 7.4 안정적인 HTTP contract

기존 policy API의 tenant/clinic path 규칙을 따라 endpoint base path를 다음으로 고정한다.

```text
/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability/decision
/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability/override
/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability/clear
/api/{tenantCode}/clinics/{clinicId}/members/{memberId}/booking-reliability/audit
```

caller-facing error는 `BOOKING_REVIEW_REQUIRED`, `BOOKING_DECISION_UNAVAILABLE`, `BOOKING_DECISION_STALE`, `BOOKING_RELIABILITY_FORBIDDEN`으로 고정한다. 상세 `reasonCodes`와 사건 ID는 직원 preview/audit 응답에만 포함한다.

## 8. 예약 상태별 적용 범위

| 상태/동작 | 기본 결정 사용 | 자동 변경 허용 |
|---|---:|---:|
| 새 `PROPOSED` 생성 | 예 | 새 제안 생성 거부 또는 직원 검토 전환 |
| `PROPOSED` → `HELD` | 예 | 제한이면 선점하지 않음 |
| `HELD` → `CONFIRMED` | 예 | 결정이 유효하고 고객 동의가 맞을 때만 확정 |
| 이미 `CONFIRMED`인 예약 | 아니오 | 이 정책으로 취소·이동·allocation 회수 금지 |
| `CONFIRMED` 이후의 신규 예약 | 예 | 신규 예약에만 적용 |
| `EXPIRED`/`CANCELLED` 이력 | 사건 attribution을 통해서만 | 과거 상태를 다시 변경하지 않음 |

`CONFIRMED`를 변경해야 하는 운영 사건은 기존 commitment 변경 계약과 고객 동의를 사용한다. 이번 기능의 제한 결정이 그 우회 경로가 되어서는 안 된다.

## 9. 영속 모델과 migration 방향

정확한 table/record 이름은 구현 계획의 repository 조사 결과에 맞춰 확정하되, 다음 세 가지 책임을 분리한다.

### 9.1 사건 원장

`booking_reliability_events` 후보 테이블은 appointment outcome과 책임 attribution의 typed 사실을 보존한다.

- `event_id`, `appointment_id`, `clinic_id`, `member_id`
- `outcome`, `responsibility`, `cancellation_timing`, `source`
- `occurred_at`, `source_version`, `correlation_id`
- immutable insert timestamp와 bounded retention class

`(clinic_id, member_id, event_id)` unique key와 `(clinic_id, member_id, occurred_at, event_id)` 조회 인덱스를 둔다. 사건을 수정하지 않고 정정은 새 source version 사건으로 기록한다.

### 9.2 결정 snapshot

`booking_reliability_decisions` 후보 테이블은 evaluator 결과를 immutable row로 저장한다.

- decision ID와 evaluation digest
- tenant/clinic scope, opaque `member_id`
- policy snapshot ID와 policy version
- no-show/late cancellation bounded count
- bounded reason code set, bounded triggering appointment IDs
- `decision`, `effective_from`, `expires_at`
- 생성 actor는 시스템 evaluator로 기록하고 correlation ID를 보존

동일 digest의 재평가가 중복 row를 만들지 않도록 unique idempotency key를 둔다. 현재 유효 결정을 빠르게 읽기 위한 projection을 두더라도 원 snapshot을 수정하지 않는다.

### 9.3 override/clear 감사

`booking_reliability_overrides` 후보 테이블은 `OVERRIDE`와 `CLEAR`를 append-only로 보존한다.

- 대상 decision ID와 이전 decision digest
- action, actor ID/type, reason code
- effective/expires 시각, correlation ID
- optimistic version과 결과 digest

override는 decision row를 덮어쓰지 않는다. 조회 시 유효한 최신 override를 적용한 `OVERRIDDEN` 결과를 만들고, 기본 결정과 사람의 조치를 함께 설명한다.

H2, MySQL, PostgreSQL migration을 동일 의미로 추가한다. `MemberId` 원문을 별도 연락처 컬럼으로 복제하지 않으며, 인덱스 길이와 대소문자 비교 규칙은 현재 legacy `patient_external_id` 저장 계약과 일치시킨다.

### 9.4 보존·삭제

`MemberId`가 개인정보로 취급될 수 있음을 전제로 clinic의 보존 기간과 회원 서비스의 삭제 요청을 연결한다. 보존 기간이 지나거나 삭제 요청이 확정되면 사건·결정 projection에서 회원 직접 식별자를 제거하거나 irreversibly pseudonymize하고, aggregate audit에는 사건 수·정책 버전·reason code·삭제 처리 시각만 남긴다. 법적 보존이 필요한 감사 row는 별도 retention class와 접근 권한으로 격리한다. 이름·전화번호는 처음부터 이 저장소에 없으므로 삭제 작업이 회원 profile DB를 수정하지 않는다.

#176은 이 경계를 검증하는 bounded retention executor 계약과 부분 실패 격리 runner를 제공한다.
기본 executor는 no-op이며, 실제 삭제·가명화는 운영 배포가 tenant/clinic 범위와 감사 증적을
검증한 executor를 주입할 때만 수행한다.

## 10. 대규모 clinic 처리 전략

한 병원에 많은 회원이 있어도 예약 요청 하나가 전체 clinic을 스캔해서는 안 된다.

- 동기 evaluator는 `(clinicId, MemberId)`의 bounded history만 읽는다.
- 사건 backfill·정책 변경 재평가는 outbox 또는 durable job으로 분리한다.
- worker는 member 단위 durable job의 bounded cursor와 batch 상한·lease·재시도·pause/resume·dead-letter를 둔다.
- clinic 전체 event keyset backfill은 현재 범위가 아니며, 별도 job 설계에서 `(clinicId, memberId, occurredAt, eventId)` cursor를 추가한다.
- 동일 member key에 대한 concurrent evaluation은 DB unique digest와 optimistic CAS로 수렴한다.
- 정책 snapshot은 immutable cache 대상이지만 cache miss가 판단을 바꾸지 않는다.
- 직원 preview는 전체 환자 수나 연락처를 반환하지 않고 요청된 member의 bounded 결과만 반환한다.
- metric label은 clinic, outcome, decision, reason code처럼 bounded enum만 허용하고 `MemberId`·appointment ID는 넣지 않는다.

목표 지표는 전체 처리량보다 다음에 둔다.

1. 동기 eligibility p95/p99 latency
2. stale decision 재시도율
3. event attribution 누락률
4. 제한 결정의 override/clear 비율
5. batch backlog oldest age와 retry/quarantine 수

## 11. API와 권한 경계

구체적인 URL은 현재 policy controller convention에 맞춰 구현 계획에서 확정한다. 최소 계약은 다음과 같다.

| 동작 | 용도 | 권한 |
|---|---|---|
| `GET decision` | 직원 preview/explain | clinic-scoped `booking-reliability:read` |
| `POST override` | 제한을 특정 기간 동안 대체 | clinic-scoped `booking-reliability:write` |
| `POST clear` | 활성 override 또는 제한을 해제 | clinic-scoped `booking-reliability:write` + 사유 코드 |
| `GET audit` | override/clear command와 decision digest 참조 확인 | 별도 감사 read capability |

request body의 actor, clinic, member contact 정보는 권한 근거로 사용하지 않는다. path clinic이 principal allow-list와 기준 데이터에 속하지 않으면 `403`이다. 선언되지 않은 필드는 fail-closed로 거절한다. `MemberId` 자체를 고객에게 “신뢰도 점수”로 공개하는 고객용 endpoint는 만들지 않는다.

호출자는 결과를 다음처럼 해석한다.

| 결과 | 호출자 동작 | 사용자에게 보이는 의미 |
|---|---|---|
| `ELIGIBLE` | proposal/hold/confirmation을 정상 진행 | 일반 예약 가능 |
| `RESTRICTED` | 새 자원 선점은 만들지 않고 직원 검토 또는 대체 offer로 이동 | 현재 기준으로 온라인 확정 불가 |
| `OVERRIDDEN` | override 만료까지 승인된 경로를 진행 | 직원이 승인한 예외 |
| `UNAVAILABLE` | 제한하지 않고 bounded retry 또는 직원 검토 | 일시적인 판단 불가, 기존 확정 예약에는 영향 없음 |

`RESTRICTED`를 고객에게 “문제 고객”이나 내부 점수로 노출하지 않는다. 공개 오류는 안정적인 `BOOKING_REVIEW_REQUIRED`, `BOOKING_DECISION_UNAVAILABLE` 같은 caller-safe code로만 표현하고, 상세 reason code와 사건은 권한 있는 직원 화면에서 확인한다.

## 12. 실패·동시성 시나리오

### 12.1 회원/사건 저장소 장애

평가 결과를 증명할 수 없으면 `DECISION_UNAVAILABLE`로 종료한다. 고객을 잘못 제한하는 `RESTRICTED`로 추정하지 않으며, `CONFIRMED` 예약에는 어떠한 변경도 하지 않는다. #170은 bounded retry 또는 직원 검토로 전환한다. 동일 idempotency key 재시도는 같은 digest로 수렴해야 한다.

### 12.2 정책 snapshot 경쟁

평가 도중 새 정책이 활성화되면 이전 snapshot으로 저장한 결정은 `POLICY_SNAPSHOT_STALE`로 표시하고, caller가 새 snapshot으로 재평가한다. 이미 생성된 audit row를 수정하지 않는다.

### 12.3 책임 attribution 지연·오류

`UNKNOWN` 사건은 제한 count에 넣지 않고 `UNATTRIBUTED_EVENT_EXCLUDED`만 기록한다. 나중에 책임이 확정되면 새 source version 사건을 발행해 재평가한다. 병원 원인의 취소가 고객 책임으로 변환되지 않는지 계약 테스트로 고정한다.

### 12.4 동시 override와 booking command

override와 `PROPOSED`/`HELD`/`CONFIRMED` command는 decision version과 digest를 조건으로 CAS한다. command가 stale decision을 들고 있으면 선점·확정 전에 재조회한다. 기존 `CONFIRMED` commitment는 이 CAS의 대상이 아니다.

### 12.5 중복·역순 사건

`eventId + sourceVersion` dedupe를 적용한다. 역순 도착은 occurredAt 기준으로 재집계하되 이미 발행된 과거 결정 snapshot은 삭제하지 않는다. 현재 유효 projection만 새 decision ID로 갱신한다.

## 13. 배포·운영·rollback

기능은 `booking.reliability.mode=OFF|SHADOW|ENFORCE`로 단계적으로 전개한다. 기본값은 `OFF`이며, `SHADOW`에서는 사건 attribution과 decision snapshot을 만들지만 예약을 제한하지 않는다. clinic allow-list와 shadow 결과가 안정되면 일부 clinic만 `ENFORCE`로 전환한다. tenant/clinic override는 정책 snapshot과 같은 승인 흐름을 사용한다.

- migration은 additive로 먼저 배포하고, schema readiness가 확인될 때까지 evaluator worker와 `ENFORCE`를 켜지 않는다.
- `ENFORCE` rollback은 새 restriction 적용만 중지하고 `SHADOW` 또는 `OFF`로 내린다. 이미 `CONFIRMED`인 예약과 기존 decision/audit row는 삭제·변경하지 않는다.
- worker backlog, attribution 누락률, `DECISION_UNAVAILABLE`, override/clear 급증을 health/alert로 관찰한다. metric label은 clinic, mode, decision, reason code처럼 bounded 값만 허용한다.
- 장애 시 운영자는 affected clinic allow-list를 줄이고 backfill/reevaluation job을 pause한 뒤, decision audit와 migration 상태를 확인한다. 재개는 pause 시각 이후의 keyset cursor와 idempotency digest를 사용한다.
- `docs/runbooks/booking-reliability.md` 운영 runbook에는 schema readiness, shadow diff, canary 승격·중단, decision store 복구, stale snapshot redrive, retention/삭제 처리, rollback rehearsal를 포함한다.

초기 canary는 clinic 1곳에서 최소 24시간 또는 decision 1,000건 중 늦은 조건까지 관찰한다. 승격 기준은 중복 decision 0건, `CONFIRMED` mutation 0건, `DECISION_UNAVAILABLE` 미해결 backlog 0건, attribution 누락률 1% 미만, caller-safe 오류 외 raw 개인정보 노출 0건이다. 기준을 만족하지 못하면 해당 clinic만 `SHADOW`로 되돌리고 원인을 고정한 뒤 재검증한다.

## 14. 검증 계획

### 단위 테스트

- 반복 no-show가 threshold 미만이면 `ELIGIBLE`, 이상이면 `RESTRICTED`
- late cancellation cutoff 경계값과 날짜/시간대 변환
- `CLINIC`, `SYSTEM`, `UNKNOWN` 사건이 count에서 제외됨
- lookback 만료와 cooling-off 만료
- 동일 event 중복·역순 source version 수렴
- override/clear가 기본 결정과 분리된 audit를 남김
- `UNAVAILABLE`과 stale snapshot이 고객 제한으로 오인되지 않음

### 저장소·migration 테스트

- H2 schema 생성과 `SchemaUtils.createMissingTablesAndColumns` setup
- PostgreSQL/MySQL migration의 column, unique key, index 동등성
- `MemberId` 저장·조회 round trip과 legacy nullable row
- decision digest idempotency와 optimistic CAS 경쟁
- bounded triggering IDs와 reason code allowlist 상한

### API·보안 테스트

- 허용 clinic 밖의 preview/override/clear가 `403`
- actor/clinic을 body에서 위조해도 권한이 상승하지 않음
- 이름·전화번호·자유 텍스트가 response/log/metric에 포함되지 않음
- 직원 preview와 감사 조회가 서로 다른 capability를 요구함
- 선언되지 않은 JSON 필드가 거부됨

### commitment/#170 통합 테스트

- `PROPOSED` 생성과 `HELD` 선점이 `RESTRICTED` 결정에서 차단 또는 직원 검토로 이동
- 유효한 `OVERRIDDEN` 결정으로만 제한 흐름이 허용됨
- decision 만료·policy version 변경 시 stale command가 `CONFIRMED`가 되지 않음
- 이미 `CONFIRMED`인 예약이 no-show 재평가로 변경·취소되지 않음
- 동시 evaluator와 booking command가 중복 allocation 없이 수렴
- #176 범위에서는 위 gate가 decision stamp를 proposal/commitment에 보존하는지 검증한다.
- waitlist 후보 정렬, offer 발행·응답·소비 lifecycle은 #170 구현 시 별도 검증한다.

### 성능·운영 테스트

- 회원 수가 큰 clinic에서 한 건 조회가 full-clinic scan을 하지 않음
- member-level durable job이 재시작·lease 만료·retry·pause/resume 후 중복 없이 진행
- clinic 전체 event keyset backfill은 후속 이슈의 검증 범위로 남긴다.
- p95/p99 latency, oldest backlog, attribution 누락률 metric이 bounded label만 사용
- 장애 주입 시 `DECISION_UNAVAILABLE`과 retry/quarantine이 관찰됨

## 15. 문서·시각화

Markdown 명세가 기준 문서다. 구현 완료 시 다음 reader-facing 산출물을 별도로 검토한다.

1. 예약 자격 판단 흐름(`eligible → restricted → override → expiry`)은 업무 흐름이므로 `bluetape-diagram`의 HTML+PNG 형식을 사용한다.
2. HTML/PNG는 dark/light theme와 한국어/영어 locale을 모두 생성하고, 문자열·레이아웃·색상 대비를 각각 검증한다.
3. 영속 관계가 구현 계획에서 확정되면 ERD는 정적 SVG+PNG로 만든다. sequence/class가 필요한 경우에도 SVG+PNG를 우선한다.
4. source Markdown과 코드 계약이 HTML/PNG보다 우선하며, 시각화 파일에 새로운 정책 의미를 추가하지 않는다.

업무 흐름 companion: [한국어 light](../../visual-companions/booking-reliability-workflow-ko-light.html),
[영어 light](../../visual-companions/booking-reliability-workflow-en-light.html),
[한국어 dark](../../visual-companions/booking-reliability-workflow-ko-dark.html),
[영어 dark](../../visual-companions/booking-reliability-workflow-en-dark.html).

## 16. 대안과 기각 이유

### A. 새 예약에서만 제한하고 감사 snapshot은 만들지 않음

구현은 단순하지만 정책 버전과 입력 사건을 재현할 수 없고, `#170`이 서로 다른 판단을 할 수 있다. 감사·멱등성 요구를 충족하지 못하므로 기각한다.

### B. 고객 프로필의 이름·전화번호 또는 직원 label을 정책 입력으로 복제

개인정보 범위가 커지고 주관적 label의 정확성과 차별 위험을 통제할 수 없다. 회원 서비스가 원본 프로필을 소유하므로 예약 서비스가 이를 복제할 이유도 없다. 기각한다.

### C. 모든 기존 `CONFIRMED` 예약을 자동 취소·이동

고객 동의와 resource allocation을 깨뜨리고, 대규모 clinic에서 폭발적인 재계산을 만든다. 현재 commitment 계약의 확정 보호 원칙과 충돌하므로 기각한다.

### D. `#170` 내부에 evaluator를 함께 구현

waitlist/offer 수명주기와 신뢰도 정책의 변경 주기가 결합되고, 다른 예약 진입점이 다른 기준을 사용할 위험이 있다. #176은 독립된 auditable decision port만 제공하고 #170은 소비자로 남긴다.

## 17. 구현 완료 기준(DoD)

- [x] 기존 `PRIORITY_AND_RELIABILITY` payload/codec/validator/hasher에 versioned threshold가 추가됨
- [x] 고객 책임과 병원·운영 책임을 분리하는 typed attribution과 allowlist reason code가 구현됨
- [x] 사건 원장, immutable decision snapshot, override/clear audit가 H2/MySQL/PostgreSQL에서 동작함
- [x] `MemberId + clinicId` 범위와 개인정보 금지 항목이 테스트로 고정됨
- [ ] `#170` waitlist/offer lifecycle이 decision ID·policy snapshot·digest·expiry를 소비함 (후속 이슈)
- [x] #176 gate가 신규 proposal/commitment에 decision stamp를 저장하고 재사용함
- [x] `PROPOSED`/`HELD`/신규 `CONFIRMED` 경로가 제한 결정을 적용하고 기존 `CONFIRMED`를 변경하지 않음
- [x] 직원 preview/override/clear API와 capability·clinic scope 검증이 추가됨
- [x] idempotency, CAS, stale snapshot, outage, event reorder, batch retry 테스트가 통과함
- [x] 한국어 기준 문서와 필요 시 bilingual HTML+PNG/정적 다이어그램이 `bluetape-writer`·`bluetape-diagram` 계약을 통과함
- [x] 모듈 테스트·정적 검사·migration 검증·최종 review에서 P0/P1이 0건임

이 명세를 구현 계획의 기준으로 삼는다. 구현 중 schema 이름이나 threshold 범위를 바꿀 때는 이 문서와 `#170` 소비 계약을 함께 갱신하고, 기존 `CONFIRMED` 보호·개인정보 경계를 약화하지 않는다.

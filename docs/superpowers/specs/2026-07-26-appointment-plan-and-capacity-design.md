# 진료 계획·예약·수용량 관리 설계

- **날짜**: 2026-07-26
- **상태**: 설계 승인 후 문서화
- **대상 모듈**: `appointment-core`, `appointment-event`, `appointment-solver`, `appointment-notification`, `appointment-api`
- **시각 문서**: [독립 실행형 HTML](./2026-07-26-appointment-plan-and-capacity-design.html)

## 1. 목적

성형외과·피부과처럼 한 번의 구매로 여러 차례 또는 여러 종류의 시술을 제공하는 병원의 예약을 현실적으로 관리한다. 상품과 구매 계약을 예약 자체로 복제하지 않고, 구매 시점의 상품 BOM을 바탕으로 **앞으로 이행해야 할 진료 의무**, **실제 방문**, **방문 안의 세부 진료**, **자원 배정**을 분리한다.

다음 상황을 하나의 모델로 지원하는 것이 목표다.

- N일·N주 간격의 반복 시술
- 서로 다른 일정 규칙을 가진 패키지 시술
- 고객 희망일 기반 최초 가예약과 병원의 날짜 제안
- 한 번의 방문에서 여러 세부 진료 수행
- 일부 진료만 끝난 방문의 단계 분리와 재예약
- 선행 시술 완료 시점에 따라 달라지는 후속 시술
- 공휴일·휴진·장비 고장에 따른 대량 재조정
- 객관적인 고객 신뢰도와 서비스 등급을 고려한 배정
- 의도적인 오버부킹, 도착 시간대 예약, 날짜 대기형 예약
- 정상 영업시간을 넘겨 진료하는 운영 정책
- SaaS tenant 기본값과 병원별 override를 결합한 버전형 예약 정책

## 2. 서비스 경계

예약 서비스는 **진료 일정과 자원 배정**만 소유한다.

| 관심사 | 소유 서비스 | 예약 서비스의 역할 |
|---|---|---|
| 상품 정의·BOM·예약 규칙 | 상품관리서비스 | 버전이 있는 projection을 API로 동기화하고 향후 Pub/Sub event를 consume |
| 구매 계약·추가 구매 | 구매서비스 | 구매 event를 받아 새 `AppointmentPlan` 생성 |
| 실제 시술 완료의 원천 사실 | 시술/진료서비스 | 완료 event를 받아 계획 이행 여부와 후속 기간을 갱신 |
| 환불 가능 여부·금액·승인 | 결제/커머스서비스 | 환불 event를 받아 미래 예약 의무만 취소·재계산 |
| 고객 불만·사과·보상·상담 | 고객서비스/CRM | 지연·중단·재예약·SLA 위반 사실 event를 제공 |
| SaaS 예약 운영 정책 | 예약서비스 | tenant 기본값, clinic override, 유효 정책 snapshot과 감사 이력을 소유 |
| 일정·가예약·확정·자원 배정 | 예약서비스 | 소유 |

고객이 병원 사유로 화가 나서 재예약 또는 환불을 요구하더라도, 예약 서비스는 감정·민원·보상 정책을 판단하지 않는다. 다음과 같은 객관적 사실만 기록하고 발행한다.

- `AppointmentInterrupted`
- `AppointmentDelayExceeded`
- `RescheduleOffered`
- `AppointmentServiceLevelBreached`
- `CustomerConsentRequired`

예약 서비스는 외부에서 결정된 `CustomerRescheduleAccepted`, `PurchaseRefunded`, `PlanCancelled` 등을 consume하여 일정 상태를 반영한다.

호출자에게 보여 줄 문구는 채널별 현지화가 가능하지만, 예약이 발행하는 reason과 다음 행동은 고정 계약이다.

| 사실/상태 | 환자에게 전달할 핵심 | 운영자 다음 행동 | 후속 소유자 |
|---|---|---|---|
| `AppointmentInterrupted` | 완료/남은 항목, 원인, 새 후보 준비 안내 | 잔여 의무 확인 | 예약 → CRM 통지 |
| `AppointmentDelayExceeded` | 현재 예상 대기와 선택지 | 추가 자원·순번 확인 | 예약/Notification |
| `AppointmentServiceLevelBreached` | 약속 범위 초과와 상담 연결 | incident와 handoff 확인 | CRM |
| no valid slot | 자동 확정 불가와 연락 예정 | `BLOCKED_REVIEW` 처리 | 예약 운영 |
| proposal expired/rejected | 원 예약 보호 여부와 새 후보/상담 선택 | 최신 proposal 생성 여부 결정 | 예약/CRM |
| refund-driven cancellation | 취소된 미래 일정과 남은 방문 | 공유 방문 재계산 확인 | 커머스가 환불 설명 |

## 3. 선택한 모델과 기각한 대안

### 3.1 대안 비교

| 대안 | 설명 | 판단 |
|---|---|---|
| A. `AppointmentGroup` 아래 N개 예약 | 구매 단위를 예약 그룹으로 만들고 회차별 예약을 자식으로 둠 | 패키지의 서로 다른 시술, 한 방문의 다중 진료, 여러 구매의 합동 방문을 표현하기 어려워 기각 |
| B. 예약 아래 `N회차 예약` | 예약이 회차와 진료 의무를 모두 소유 | 방문 전 계획과 실제 방문이 섞이고 부분 완료·재시도 이력이 불명확해 기각 |
| C. `AppointmentPlan → PlannedTreatment ← AppointmentItem → Appointment` | 구매별 진료 의무와 실제 방문을 분리 | 채택 |

`N회차`는 독립 aggregate나 그룹 이름이 아니라 `PlannedTreatment.sequenceNo` 같은 표시·정렬 메타데이터다.

### 3.2 핵심 관계

```text
Purchase 1 ──> AppointmentPlan 1 ──> PlannedTreatment N
                                            ▲
                                            │ fulfills / attempts
Appointment 1 ──> AppointmentItem N ─────────┘
       │
       └── ResourceAllocation N

PlannedTreatment ── TreatmentDependency(DAG) ──> PlannedTreatment
```

한 `Appointment`에는 같은 고객의 여러 `AppointmentPlan`에서 온 `AppointmentItem`이 함께 들어갈 수 있다. 따라서 계획과 방문은 직접 1:N으로 묶지 않는다.

## 4. 도메인 모델

### 4.1 `ProductCatalogProjection`

상품관리서비스가 소유한 최신 상품 정의의 예약용 복제본이다.

- 키: `productId`, `catalogVersion`
- 내용: 상품명, BOM 항목, 반복 횟수, 간격·허용 기간, 의존 관계, 예상 소요시간, 필요한 의료진·장비·공간, 최초 예약 규칙
- 상태: `ACTIVE`, `RETIRED`
- 동기화 정보: `sourceUpdatedAt`, `receivedAt`, `payloadHash`

projection은 현재 REST API upsert로 갱신한다. 향후 Pub/Sub consumer도 동일한 `CatalogSyncApplicationService`를 호출한다.

버전 처리 규칙:

1. 더 높은 버전은 추가한다.
2. 같은 버전·같은 payload는 idempotent 성공이다.
3. 같은 버전·다른 payload는 충돌로 격리하고 경보한다.
4. 낮은 버전은 무시하되 관측 가능하게 기록한다.

### 4.2 `AppointmentPlan`

구매 한 건이 만든 진료 이행 계획이다.

- 한 구매당 하나의 plan
- `sourcePurchaseId` 전역 유일
- 생성 시 정확한 `catalogVersion`과 BOM snapshot을 불변으로 저장
- `ACTIVE`, `PARTIALLY_FULFILLED`, `FULFILLED`, `CANCELLED`
- 상품 카탈로그가 바뀌어도 기존 plan은 자동으로 덮어쓰지 않음

추가 구매는 기존 plan에 합치지 않고 언제나 새 `AppointmentPlan`을 만든다.

### 4.3 `AppointmentPlanRevision`

최신 상품 정의를 진행 중인 plan의 **미래 항목에만** 적용하려는 명시적 변경 기록이다.

- 적용 범위: `FUTURE_ONLY`
- `IN_PROGRESS`, `COMPLETED` 항목은 동결
- 미확정 미래 항목은 자동 재계산 가능
- `CONFIRMED` 예약 변경은 고객 동의 후에만 적용
- 적용 전·후 snapshot과 변경 이유, 요청자, 외부 event ID를 보존

카탈로그 변경 event 자체는 projection만 갱신한다. plan revision은 별도의 `PlanUpdateRequested` 명령 또는 event가 있어야 생성된다.

### 4.4 `PlannedTreatment`

상품 BOM에서 전개된 하나의 진료 의무다.

- `planId`, `bomItemId`, `sequenceNo`
- 대표 진료명과 세부 진료 코드
- 예상 소요시간
- 필요한 의료진 자격, 장비, 공간
- `earliestStartAt`, `latestStartAt`
- `PLANNED`, `SCHEDULED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED`, `BLOCKED_REVIEW`

반복 상품은 동일 BOM 항목을 횟수만큼 전개한다. 패키지는 서로 다른 BOM 항목을 각각 전개한다.

### 4.5 `TreatmentDependency`

`PlannedTreatment` 사이의 DAG edge다.

- `predecessorTreatmentId`
- `successorTreatmentId`
- `anchor`: `ACTUAL_COMPLETION`
- 최소·권장·최대 간격

구매일은 임상 의존성 anchor가 아니다. 선행 항목의 실제 완료 event가 들어오면 후속 항목의 허용 기간을 다시 계산한다.

### 4.6 `Appointment`

고객이 병원을 한 번 방문하는 예약 단위다.

- 대표 진료명
- 시작·종료 또는 도착 시간대
- commitment mode
- `PROPOSED`, `HELD`, `CONFIRMED`, `CHECKED_IN`, `IN_PROGRESS`, `COMPLETED`, `INTERRUPTED`, `CANCELLED`, `NO_SHOW`
- fulfillment: `NONE`, `PARTIAL`, `FULL`

### 4.7 `AppointmentItem`

한 방문에서 수행하거나 시도하는 세부 진료다.

- 반드시 하나의 `PlannedTreatment`를 참조
- 같은 방문에 여러 개 존재 가능
- 서로 다른 plan의 항목도 임상적으로 호환되면 함께 배치 가능
- `attemptNo`, `previousAttemptId`
- `PLANNED`, `IN_PROGRESS`, `COMPLETED`, `INTERRUPTED`, `DEFERRED`, `SKIPPED`, `FAILED`, `CANCELLED`

완료 판단은 방문 전체가 아니라 각 `AppointmentItem` 단위다.

### 4.8 `ResourceAllocation`

세부 진료별 의료진·장비·공간 점유다. 같은 방문 안에서도 항목마다 시간과 자원이 다를 수 있다.

### 4.9 `SchedulingPolicySet`과 `EffectiveSchedulingPolicy`

SaaS 고객마다 운영 방식이 다르므로 예약서비스는 정책을 코드 분기나 전역 환경변수가 아니라 typed·versioned aggregate로 관리한다.

```text
Platform safety guardrail
          │ hard ceiling
Tenant SchedulingPolicySet (default)
          │ typed overlay
Clinic SchedulingPolicySet (override)
          │ compile at evaluation time
EffectiveSchedulingPolicy snapshot
          │ referenced by
Appointment / RescheduleCase / SolverRun
```

`SchedulingPolicySet`의 범위는 `TENANT_DEFAULT` 또는 `CLINIC_OVERRIDE`다.

- 공통 식별자: `tenantId`, 선택적 `clinicId`, `policyKind`, `version`
- 수명주기: `DRAFT`, `SCHEDULED`, `ACTIVE`, `RETIRED`
- 시간: `effectiveFrom`, 선택적 `effectiveUntil`
- 무결성: `schemaVersion`, `payloadHash`, `createdBy`, `approvedBy`, `changeReason`
- 동시성: scope와 policy kind별 optimistic revision, 같은 시점의 active version은 하나

초기 `policyKind`는 다음 typed schema를 가진다.

| 정책군 | 대표 설정 |
|---|---|
| `BOOKING_COMMITMENT` | 허용 commitment mode, 직접 확정 허용 여부, 최초 proposal 전략 |
| `HOLD_AND_CONSENT` | hold TTL, proposal TTL, 고객 동의·만료·재제안 규칙 |
| `CAPACITY_AND_OVERBOOKING` | nominal capacity, quota, absolute limit, 자동 완화 |
| `PRIORITY_AND_RELIABILITY` | service tier weight, reliability allowlist와 회복·감쇠 |
| `RECONFIRMATION` | 대상, 시점, 채널, 미응답 처리 |
| `DISRUPTION_RECOVERY` | 자동 재계산 범위, 최소 변경 weight, 수동 검토 임계값 |
| `OPERATING_EXTENSION` | 자동·절대 연장 한도, 승인과 안전 제한 |
| `NOTIFICATION_AND_SLA` | mode별 대기·SLA 기준, 알림 cadence와 escalation |

clinic override의 각 필드는 `INHERIT`, `SET(value)`, `DISABLE` 중 하나다. 값 부재를 `null`과 혼용하지 않는다. `INHERIT`는 tenant 기본값을 사용하고, `DISABLE`은 schema가 선택 기능으로 선언한 항목만 끈다. 필수 policy kind와 필수 SLA·안전 필드는 `DISABLE`할 수 없으며 effective 결과에 필수값이 없으면 활성화를 거부한다. tenant의 hard ceiling과 platform safety guardrail은 clinic override로 완화할 수 없고, 수치 상한은 가장 엄격한 값을 사용한다.

`EffectiveSchedulingPolicy`는 `(tenantId, clinicId, decisionAt, serviceAt)`에 대해 활성 tenant 정책과 clinic override를 결정적으로 compile한 불변 snapshot이다. 각 typed policy는 평가 기준 시각을 고정한다.

- `DECISION_TIME`: hold·동의·proposal·disruption 처리처럼 지금 실행하는 workflow
- `SERVICE_TIME`: capacity·reconfirm·SLA·영업 연장처럼 실제 진료 예정 시점의 운영 조건

평가 기준은 tenant가 임의로 바꾸는 설정이 아니라 policy schema 계약이다. `effectiveFrom`과 `effectiveUntil`은 offset이 있는 입력을 `Instant`로 저장한다. clinic 화면에서 입력한 local time은 clinic timezone으로 정규화하며 DST gap·overlap을 명시적으로 검증한다.

- `effectivePolicyId`, 구성 policy별 version map, `compiledAt`, `payloadHash`
- 상속 결과와 각 값의 출처(`PLATFORM`, `TENANT`, `CLINIC`)
- validation warning과 비활성화된 기능
- 계산 결과는 동일 입력에 동일 hash를 반환

`Appointment`, `RescheduleCase`, `SolverRun`은 의사결정에 사용한 `effectivePolicyId`, version map, snapshot hash를 저장한다. 감사·재현을 위해 snapshot 본문도 별도 불변 저장소에 보존한다. `AppointmentPlan`은 생성 시 최초 예약 계산에 사용한 정책 참조만 남기며, 상품 BOM snapshot과 정책 snapshot을 하나로 합치지 않는다.

정책 변경의 기본 적용 범위는 `FUTURE_ONLY`다.

| 대상 | 새 정책 활성화 시 처리 |
|---|---|
| 새 예약·새 solver run | 새 effective policy 사용 |
| `PROPOSED` | 아직 고객이 수락하지 않았다면 최신 정책으로 supersede·재계산 가능 |
| `HELD` | 당시 snapshot과 allocation을 만료까지 보호하며 같은 allocation의 확정은 허용 |
| `CONFIRMED` | 당시 snapshot을 유지하며 자동 변경 금지; 변경에는 새 proposal과 고객 동의 필요 |
| `IN_PROGRESS`, `COMPLETED` | 과거 사실과 정책 근거를 영구 보존 |

상한을 낮춰 기존 hold·확정 합계가 새 한도를 넘더라도 기존 약속을 조용히 취소하지 않는다. 기존 hold를 같은 allocation의 confirmed로 바꾸는 것은 수용량 증가가 아니므로 당시 snapshot 아래 허용한다. 신규 hold와 신규 capacity를 늘리는 confirm은 차단하고 `POLICY_CAPACITY_DEBT`를 운영 경보로 만들며 disruption 또는 고객 동의 기반 재예약으로 해소한다. 실제 자원 안전이 깨진 경우는 정책 변경이 아니라 disruption 절차로 처리한다.

정책 publish는 `draft → validate → impact preview → approve → schedule/activate` 순서다. validation 또는 compile이 실패하면 새 version은 활성화하지 않고 직전 active policy를 유지한다. overbooking, service tier, 운영 연장, 확정 예약에 영향을 줄 수 있는 변경은 step-up 인증과 선택적 이중 승인을 요구한다. 긴급 override도 사유, 승인자, 만료시각을 필수로 하며 자동 만료 후 이전 effective policy로 돌아간다.

대규모 tenant의 정책 활성화가 모든 clinic snapshot을 한 번에 다시 쓰게 만들지 않는다. 활성화 트랜잭션은 scope의 `policyGeneration`을 증가시키고 `SchedulingPolicyActivated`를 발행한다. compiler cache는 `(tenantId, clinicId, policyGeneration, decisionAt/serviceAt bucket)`으로 key를 잡아 영향 범위만 무효화하고, effective snapshot은 다음 조회·예약 계산에서 lazy compile한다. impact preview와 cache warm-up은 clinic partition별 bounded job으로 실행한다.

slot/proposal 응답은 `effectivePolicyId`와 `policyGeneration`을 포함하고 새 hold·confirm 명령은 이를 `expectedPolicyGeneration`으로 돌려보낸다. allocation 트랜잭션이 최신 generation과 다르면 새 자원 점유는 `409 POLICY_CHANGED`로 거부하고 최신 후보를 반환한다. 단, 이미 만든 hold를 같은 allocation으로 확정하는 명령은 해당 hold의 pinned snapshot을 사용한다.

## 5. 예약 생성과 확정

### 5.1 최초 날짜

구매 시 고객에게 다음 중 하나를 입력받는다.

- 정확한 희망 날짜·시간
- 희망 날짜 범위
- 선호 요일·시간대

고객 희망 정보가 없다면 상품 snapshot의 최초 예약 규칙, 예를 들어 “구매 후 N일 이내”를 이용해 가예약 후보를 만든다. 이 규칙은 최초 예약의 운영 기한이지, 후속 시술의 임상 의존성이 아니다.

### 5.2 선점형과 제안형

| 상태 | 의미 | 수용량 점유 |
|---|---|---|
| `PROPOSED` | 병원 또는 시스템이 제안한 후보 | 없음 |
| `HELD` | 만료 시각이 있는 선점형 가예약 | 있음 |
| `CONFIRMED` | 고객 동의가 완료된 확정 예약 | 있음 |

상품 규칙과 해당 병원의 `EffectiveSchedulingPolicy`에 따라 `PROPOSED → HELD → CONFIRMED` 또는 직접 `CONFIRMED`가 가능하다. hold는 정책 snapshot에 고정된 TTL이 지나면 자동 해제한다.

### 5.3 확정 예약 변경

확정 예약의 시간, 방문 방식, 핵심 의료진, 세부 진료 구성이 실질적으로 달라지면 새 `RescheduleProposal`을 만들고 고객 동의를 받아야 한다. 병원은 내부 계산만으로 확정 예약을 조용히 변경할 수 없다.

### 5.4 만료·동시성·고객 동의

`HELD`와 `RescheduleProposal`은 서로 다른 만료 수명주기를 가진다.

| 대상 | 만료 시 처리 | 늦은 응답 |
|---|---|---|
| `HELD` | allocation을 idempotent하게 해제하고 `EXPIRED`로 전환 | 새 후보 조회를 요구 |
| `RescheduleProposal` | 후보 allocation을 해제하고 `EXPIRED`로 전환하되 기존 확정 예약은 그대로 보호 | `409 PROPOSAL_EXPIRED`와 현재 유효 proposal 반환 |

만료 작업은 중복 실행되어도 같은 결과로 수렴해야 한다. hold·확정·취소는 appointment와 resource version을 비교하는 트랜잭션 안에서 처리하며, 활성 allocation에는 자원·시간 충돌을 막는 exclusion/unique 제약 또는 동등한 직렬화 장치를 둔다. lock 순서는 appointment → item → allocation으로 고정한다.

고객 동의 명령은 다음을 포함한다.

- 인증된 고객 또는 법정 대리인 ID
- `proposalId`, `proposalVersion`, 일회용 nonce, `expiresAt`
- 기존 예약과 새 후보의 날짜·시간·commitment mode·변경 item·예상 대기 차이
- 변경 사유와 병원 귀책 여부
- `ACCEPT`, `REJECT`, `REQUEST_CALL` 중 하나
- 동의 채널과 수집 시각

재사용 nonce, 만료·구버전 proposal, 일부 필드만 바꾼 동의, tenant/clinic 불일치는 거부한다. 응답이 없으면 원래 확정 예약을 유지하고 운영자에게 만료 사실을 전달한다. 직접 확정, cross-plan 병합, 오버부킹은 유효한 typed 정책이 없으면 기본적으로 금지한다.

## 6. 한 방문의 부분 완료와 단계 분리

장비 고장, 의료진 긴급 이탈, 환자 상태, 시간 부족 등으로 일부 항목만 완료될 수 있다.

1. 원래 `Appointment`는 `INTERRUPTED`, fulfillment는 `PARTIAL`이 된다.
2. 완료한 item은 `COMPLETED`로 동결한다.
3. 진행 중 중단한 item은 `INTERRUPTED` attempt로 남긴다.
4. 시작하지 못한 item은 `DEFERRED`로 남긴다.
5. 미이행 `PlannedTreatment`마다 새 `AppointmentItem` attempt를 만든다.
6. 남은 항목은 새 `PROPOSED` 또는 `HELD` 예약으로 분리한다.
7. 새 일정은 고객 동의 후 `CONFIRMED`가 된다.

후속 의존 항목은 선행 `AppointmentItem`의 실제 완료 전까지 잠긴다. 최대 허용 기간을 벗어나면 자동 확정하지 않고 `BLOCKED_REVIEW`로 전환한다.

부분 완료 처리는 하나의 DB 트랜잭션에서 원 방문·item 상태, 새 attempt 생성 요청, outbox event를 원자적으로 기록한다. 후보 슬롯 계산처럼 외부 계산이 필요한 단계는 saga checkpoint로 분리한다.

```text
INTERRUPTION_RECORDED
  → RESIDUAL_OBLIGATIONS_IDENTIFIED
  → CANDIDATES_PREPARED
  → CUSTOMER_CONSENT_PENDING
  → RESCHEDULED
```

각 checkpoint는 재실행 가능하고, 중간 crash 뒤에는 완료된 item과 이미 생성된 attempt를 idempotency key로 재사용한다. repair job은 원 방문이 `PARTIAL`인데 잔여 의무 checkpoint가 없는 경우를 탐지해 다시 시작한다.

고객·운영자 화면에는 완료 항목, 남은 항목, 남은 임상 기한, 새 방문 필요 여부, 동의 기한을 분리해 표시한다. 고객이 재예약을 거절하면 예약은 사실 event를 발행하고 CRM·커머스의 상담/환불 workflow로 넘긴다.

## 7. 추가 구매와 합동 방문

진행 중 고객이 새 상품을 구매하면 새 구매 ID와 새 `AppointmentPlan`을 만든다. 기존 plan의 BOM이나 잔여 횟수를 변형하지 않는다.

단, 다음 조건을 모두 만족하면 서로 다른 plan의 항목을 같은 `Appointment`에 배치할 수 있다.

- 같은 환자와 병원
- 임상적으로 함께 수행 가능
- 각 항목의 허용 기간이 겹침
- 의료진·장비·공간을 다시 검증
- 고객이 합동 방문에 동의

한 구매가 환불되더라도 다른 plan의 item이 남아 있으면 공유 방문은 유지하고 시간·자원만 재계산한다.

## 8. 환불 event 반영

환불 판단과 금액 계산은 외부 서비스가 수행한다. 예약 서비스가 받는 event에는 다음이 필요하다.

- `externalEventId`
- `refundId`
- `sourcePurchaseId`
- `scope`: `FULL` 또는 `PARTIAL`
- 대상 `bomItemId` 또는 외부 line ID
- `effectiveAt`
- `reasonCode`

처리 규칙:

1. event는 `externalEventId`로 idempotent하게 처리한다.
2. 완료·진행 이력은 변경하지 않는다.
3. 환불 범위의 미래 `PlannedTreatment`를 `CANCELLED`로 만든다.
4. 연결된 미래 `AppointmentItem`과 allocation을 취소한다.
5. 공유 방문에 다른 item이 남으면 예약을 유지하고 소요시간·자원을 재계산한다.
6. 빈 예약이 되면 취소 사유를 `PURCHASE_REFUNDED`로 기록한다.

## 9. 운영 중단과 대량 재조정

### 9.1 통합 중단 모델

`ScheduleDisruption`은 다음 원인을 하나의 입력으로 정규화한다.

- 국가 공휴일 또는 임시공휴일 변경
- 병원 영업시간·휴진 변경
- 담당 의사 또는 의료진 부재
- 장비 고장·점검
- 공간 폐쇄

영향 분석은 예약 전체가 아니라 `AppointmentItem`과 `ResourceAllocation` 단위로 수행한다.

### 9.2 재조정 파이프라인

```text
Disruption event
  → ImpactDetector
  → RescheduleCase 병합
  → 최소 변경 후보 생성
  → Timefold 전역 최적화
  → RescheduleProposal
  → 고객 동의
  → 확정 반영
```

동일 고객·동일 방문에 여러 disruption이 겹치면 하나의 `RescheduleCase`로 병합한다. 이미 유효한 후속 예약은 불필요하게 이동하지 않는다.

### 9.3 최소 변경 우선순위

1. 같은 시간에 대체 의료진·장비 배정
2. 영향받은 item만 다른 방문으로 분리
3. 방문 전체 이동
4. 자동 해법이 없으면 수동 검토

`SlotCalculationService`는 유효 후보를 생성하고, Timefold Solver는 여러 예약을 함께 비교해 전체 손실을 최소화한다.

### 9.4 대량 중단과 Solver 경계

impact query는 `(tenantId, clinicId, dateRange, resourceType, resourceId, activeState)` 인덱스 범위로 제한한다. disruption은 `tenant/clinic/overlapping-time-window` merge key로 30초 debounce하며 같은 원천 aggregate의 낮거나 같은 version은 중복 case를 만들지 않는다.

- 한 `RescheduleCase`는 최대 10,000 affected item을 담고 500개씩 비동기 chunk 처리한다.
- 더 큰 장애는 날짜·resource group으로 분할하고 임상 긴급도와 가장 가까운 확정 방문부터 처리한다.
- customer message는 case/version별 한 번만 발행하고 별도 notification rate limit을 적용한다.
- 운영자는 새 proposal 생성을 pause/resume할 수 있지만 안전 위반 탐지는 멈출 수 없다.

Solver partition key는 `tenantId + clinicId + localDate + resourceGroup`이다. 기본 benchmark 한도는 partition당 appointment 2,000개, item 10,000개, allocation 20,000개다. 사용자 요청형 계산은 10초, 장애 batch는 60초 안에 best feasible solution을 반환하도록 termination budget을 둔다.

한도나 시간 budget을 넘으면 다음 순서로 degrade한다.

1. 같은 slot의 local/incremental re-solve
2. 영향 item만 날짜별 partition으로 나눔
3. 기존 feasible 배정을 유지하고 미해결 항목을 `BLOCKED_REVIEW`
4. 운영자 수동 검토

stale proposal은 새 case version이 발행되는 순간 `SUPERSEDED`가 되며 allocation을 해제한다. 소비자 backlog와 solver queue가 상한을 넘으면 backpressure를 걸고 새 저우선 자동 proposal보다 이미 확정된 고객 복구를 우선한다.

## 10. 우선순위와 고객 신뢰도

### 10.1 서비스 등급

주관적인 “좋은 고객/진상 고객” 라벨 대신 계약 또는 운영 정책으로 정의한 `SchedulingServiceTier`를 사용한다.

- `STANDARD`
- `RETURNING`
- `PRIORITY`
- `CONTRACTED`

등급은 soft weight일 뿐이다. 의료 안전, 임상 긴급도, 확정 예약 보호보다 앞설 수 없다.

### 10.2 예약 신뢰도

`BookingReliabilityProfile`은 객관적 예약 이력으로 계산한다.

- no-show 횟수와 최근성
- 고객 귀책 당일 취소
- reconfirm 응답 여부
- 정상 방문 누적에 따른 회복
- 오래된 위반의 시간 감쇠

병원 휴진·장비 고장·병원 지연 때문에 생긴 변경은 고객 신뢰도를 낮추지 않는다. 안전 사고나 폭력 기록은 별도 보안 도메인이며 scheduling 점수와 섞지 않는다.

### 10.3 우선순위 순서

1. 의료 안전과 자격·장비 hard constraint
2. 임상 긴급도와 의존 기간
3. 기존 확정 약속과 고객 동의
4. 대기 시간, 먼저 밀린 횟수, 병원 귀책 보상 등 공정성
5. 제한된 service tier 가중치
6. 예측된 no-show 확률과 운영 효율

VIP라는 이유만으로 정상 상태의 다른 확정 예약을 빼앗지 않는다.

## 11. 예약 방식과 통제된 오버부킹

### 11.1 Commitment mode

| 모드 | 고객에게 약속하는 것 | 운영 방식 |
|---|---|---|
| `FIXED_SLOT` | 정확한 시작 시각 | 시간 준수 우선 |
| `ARRIVAL_WINDOW` | 특정 도착 시간대 | 도착 후 순차 배정 |
| `DATE_QUEUE` | 특정 날짜의 진료 | 현장 대기와 순번 운영 |

모든 예약을 정확한 시각 예약으로 가장하지 않는다. “되는 대로 받고 와서 기다리는” 병원은 `DATE_QUEUE` 또는 `ARRIVAL_WINDOW`로 약속 수준을 명시한다.

각 병원은 mode별 `delayExceededAfter`와 `serviceLevelBreachedAfter`를 반드시 설정한다.

- `FIXED_SLOT`: 약속 시작 시각을 기준으로 측정
- `ARRIVAL_WINDOW`: 고지한 예상 대기 상한을 기준으로 측정
- `DATE_QUEUE`: 고지한 당일 latest service time을 기준으로 측정

설정이 없으면 해당 mode를 확정 예약에 사용할 수 없다.

### 11.2 `CapacityPolicy`

`CAPACITY_AND_OVERBOOKING` effective policy가 병원·진료군·요일·시간대별로 다음을 정의한다.

- `nominalCapacity`: 정상 운영 수용량
- `overbookingQuota`: 의도적으로 추가 확정할 수 있는 수
- `absoluteBookingLimit`: 어떤 경우에도 넘지 않는 예약 상한
- 재확인 시점과 채널
- 예측에 사용할 no-show 관측 기간

불변식:

```text
confirmedCount <= nominalCapacity + overbookingQuota
nominalCapacity + overbookingQuota <= absoluteBookingLimit
```

오버부킹은 명시적 정책 아래에서 `CONFIRMED` 예약을 정상 수용량보다 더 받는 것이다. 몰래 만든 중복 예약이 아니다.

Timefold에서는 의료 안전·필수 자원·절대 상한을 hard constraint로, 정상 수용량 초과·예상 대기·연장근무·재예약 피해·수익과 활용률을 soft constraint로 둔다.

### 11.3 모두 방문한 경우

초과 방문자가 모두 나타나도 임의로 진료를 거부하지 않는다. 다음 순서로 운영한다.

1. 임상 긴급도
2. commitment mode
3. 체크인 시각
4. 병원 귀책으로 이미 밀린 정도
5. 제한된 service tier

가용 의료진·장비·공간을 추가 투입하고, 대기를 안내하며, 자발적 재예약 후보를 제시한다. 보상 여부와 고객 응대는 외부 고객서비스가 담당한다.

### 11.4 수용량 경쟁·공개·자동 완화

hold와 confirm은 allocation write와 capacity counter 검증을 같은 트랜잭션에서 수행한다. optimistic version 또는 DB lock으로 정책 version과 현재 점유를 검증하고, 동시 요청이 `absoluteBookingLimit` 또는 같은 자원의 배타 점유를 넘으면 한 요청만 성공한다. stale hold는 작은 batch로 만료해 hot partition 장기 lock을 피한다.

고객은 확정 전에 commitment mode, 예상 대기 범위, queue 순서 규칙, 오버부킹/overflow 가능성, 자발적 재예약 조건을 확인한다. 이것은 숨은 내부 점수가 아니라 약속의 일부다.

no-show 모델은 보호 속성 proxy를 제외한 allowlist 특징만 사용하고 tenant/clinic별로 분리한다. 최소 표본 수, 관측 기간, calibration error, quota 재계산 주기를 정책에 고정한다. 다음 중 하나면 `overbookingQuota`를 자동 축소하고 운영 경보를 발행한다.

- calibration error가 기준을 연속 두 구간 초과
- commitment mode별 대기 SLO error budget 소진
- 모두 방문한 overflow가 주간 상한 초과

quota 변경은 권한 있는 운영자와 정책 publish workflow가 필요하며 cooldown과 변경 이력을 가진다. emergency disable은 quota를 0으로 만들 수 있지만 tenant ceiling이나 `absoluteBookingLimit`을 높이지 못한다.

## 12. 영업시간 연장

개인병원·공장형 시술 병원은 정상 종료 시각을 넘겨서라도 당일 고객을 진료할 수 있다. 이를 예외적인 데이터 오류가 아니라 `OPERATING_EXTENSION` effective policy로 모델링한다.

- `normalCloseTime`
- `autoExtensionLimit`
- `absoluteExtensionLimit`
- 연장 가능한 의료진·장비·공간
- 휴게·교대·법정 근로·장비 안전 제한

정상 종료 이후 분은 점진적으로 커지는 soft penalty다. Solver는 연장 진료 비용과 강제 재예약 피해를 비교한다. 단, 인력 자격, 휴게·근로 제한, 장비 안전, 절대 종료 시각은 hard constraint다.

## 13. 이벤트 계약

### 13.1 Consume

- `ProductCatalogChanged`
- `PurchaseCompleted`
- `PlanUpdateRequested`
- `TreatmentStarted`
- `TreatmentCompleted`
- `PurchaseRefunded`
- `PlanCancelled`
- `ClinicCalendarChanged`
- `PractitionerUnavailable`
- `EquipmentUnavailable`
- `CustomerRescheduleAccepted`
- `CustomerRescheduleRejected`

### 13.2 Publish

- `AppointmentPlanCreated`
- `AppointmentProposed`
- `AppointmentHeld`
- `AppointmentConfirmed`
- `AppointmentInterrupted`
- `AppointmentItemDeferred`
- `RescheduleRequired`
- `RescheduleOffered`
- `AppointmentDelayExceeded`
- `AppointmentServiceLevelBreached`
- `AppointmentCancelled`
- `SchedulingPolicyActivated`
- `EffectiveSchedulingPolicyChanged`

모든 외부 event는 `eventId`, `occurredAt`, `tenantId`, 원천 aggregate ID와 version을 포함한다. consumer는 inbox 또는 동등한 중복 방지 저장소를 사용하며, publish는 outbox 기반으로 원자성을 확보한다.

### 13.3 Event convergence와 신뢰

모든 event에는 `clinicId`, `producer`, `schemaVersion`, `correlationId`를 추가한다. transport는 mTLS 또는 서명된 envelope를 사용하고 event type별 허용 producer, issuer, audience를 검증한다. 서명 실패, tenant/clinic 불일치, 허용 replay window 밖 event는 반영하지 않고 quarantine한다.

| Event 군 | Dedupe key | Version 처리 | 충돌·복구 |
|---|---|---|---|
| catalog | `eventId`, `productId+catalogVersion` | 낮은 version 무시, 같은 version hash 충돌 격리 | catalog conflict queue |
| purchase/refund/cancel | `eventId`, source aggregate version | duplicate idempotent, gap 대기 | 원천 재조회 후 DLQ |
| treatment start/complete | `eventId`, treatment execution version | 완료 terminal, 낮은 version 무시 | gap이면 후속 의존 계산 보류 |
| disruption | `eventId`, resource aggregate version | merge key로 중복 collapse | case 재계산 가능 |
| customer consent | `eventId`, proposal version, nonce | 현재 proposal만 수락 | stale/replay 거부 |
| scheduling policy | `eventId`, scope+kind+version, payload hash | 낮은 version 무시, 같은 version hash 충돌 격리 | compile 실패 시 직전 active 유지 |

consumer side effect와 inbox 기록은 같은 트랜잭션이다. gap은 bounded retry 후 DLQ로 보내고, operator는 `eventId`와 aggregate version을 지정해 안전하게 re-drive할 수 있다. outbox는 batch publish 후 ack된 row만 완료 처리한다.

event 상세 payload는 별도 schema registry 또는 versioned contract 문서가 소유한다. 최소 계약은 다음과 같다.

| Event | 필수 도메인 payload | 예약 모듈 |
|---|---|---|
| `ProductCatalogChanged` | product/catalog version, hash, BOM snapshot | `appointment-api` → `appointment-core` |
| `PurchaseCompleted` | purchase/customer/clinic/product/version, desired window | `appointment-event` → `appointment-core` |
| `TreatmentStarted/Completed` | planned treatment, attempt, actual time, execution version | `appointment-event` |
| `PurchaseRefunded/PlanCancelled` | purchase, scope/targets, effective time, reason | `appointment-event` |
| disruption events | resource ID/type, interval, source version | `appointment-event` → `appointment-core` |
| consent events | proposal/version/nonce, decision, actor/channel/time | `appointment-api` |
| `SchedulingPolicyActivated` | scope, kind/version, effective window, hash, actor | `appointment-api` → core/solver/notification |

### 13.4 Lifecycle conflict와 우선순위

같은 item에 대한 event는 원천 aggregate version과 실제 발생 시각을 함께 비교한다. 이미 시작된 진료 사실은 예약·환불 명령보다 우선하고, 아직 시작하지 않은 미래 의무에는 환불·plan 취소가 proposal보다 우선한다.

| Event/명령 | `PROPOSED/HELD/CONFIRMED` | `CHECKED_IN/IN_PROGRESS` | `COMPLETED/INTERRUPTED` | `CANCELLED/NO_SHOW` |
|---|---|---|---|---|
| `PurchaseRefunded` | 대상 미래 item 취소, proposal supersede, allocation 해제 | 실제 시작 전이면 취소; 시작 후면 현재 attempt 보존하고 미래만 취소 | 과거 attempt 보존, 잔여 미래만 취소 | terminal 유지 |
| `PlanCancelled` | plan의 미래 item 전체 취소, 빈 방문 취소 | 시작된 attempt 보존, 이후 의무 취소 | 과거 보존 | terminal 유지 |
| reschedule accept | 현재 proposal/version이고 item이 유효할 때만 새 allocation 확정 | 거부, 수동 검토 | 거부 | 거부 |
| reschedule reject/expire | 후보 allocation 해제, 원 확정 보존 | 거부 | 거부 | idempotent 종료 |
| `TreatmentStarted` | 확정/check-in과 유효 의무가 있을 때 `IN_PROGRESS` | duplicate idempotent | 거부 또는 원천 정정 검토 | 취소 effective time 뒤 시작이면 quarantine |
| `TreatmentCompleted` | start gap이면 원천 조회 후 보류 | 성공 완료 한 번 기록 | duplicate idempotent; interrupted attempt의 늦은 완료는 version 검증 | 실제 start가 취소보다 앞섰음이 증명될 때만 과거 사실로 수용 |
| no-show | grace period 뒤 check-in이 없을 때만 `NO_SHOW`, allocation 해제 | 거부 | 거부 | idempotent 종료 |

동시 refund와 reschedule response에서는 refund가 대상 미래 의무를 취소한 뒤 proposal을 `SUPERSEDED`로 만든다. 고객 accept는 `409 PROPOSAL_SUPERSEDED`를 받고 환불로 취소된 item을 되살리지 못한다.

## 14. API와 기존 모델 호환

### 14.1 Catalog sync API

현재 단계의 진입점은 `PUT /api/{tenantCode}/clinics/{clinicId}/catalog-products/{productId}/versions/{catalogVersion}`다.

- 요청: `schemaVersion`, `sourceUpdatedAt`, BOM, 예약 규칙, `payloadHash`
- 응답: `201 Created`(새 version), `200 OK`(같은 version/hash 재전송), `409 Conflict`(같은 version/다른 hash)
- 더 낮은 version은 `202 Accepted`와 `STALE_IGNORED` 결과로 관측 가능하게 반환
- 인증 actor와 path의 tenant/clinic이 payload 및 상품 source authority와 일치해야 함
- Controller와 향후 Pub/Sub consumer는 같은 `CatalogSyncApplicationService`를 호출

날짜·기간·횟수·소요시간·capacity 값은 bounded validation을 거친다. clinic timezone을 기준으로 local date/time을 정규화하고 event에는 원래 offset과 UTC instant를 함께 보존한다. BOM dependency는 알려진 item만 참조하고 DAG cycle이 없어야 한다.

### 14.2 Policy management API

예약 정책은 tenant 관리자와 권한이 위임된 clinic 관리자가 다음 API로 관리한다.

- tenant 기본값: `PUT /api/{tenantCode}/scheduling-policies/{policyKind}/versions/{version}`
- clinic override: `PUT /api/{tenantCode}/clinics/{clinicId}/scheduling-policies/{policyKind}/versions/{version}`
- 검증·영향 미리보기: `POST .../versions/{version}/validate`, `POST .../versions/{version}/impact-preview`
- 승인·활성화: `POST .../versions/{version}/activate`
- 유효 정책 조회: `GET /api/{tenantCode}/clinics/{clinicId}/effective-scheduling-policy?at={instant}`

upsert는 `schemaVersion`, typed payload, `payloadHash`, `effectiveFrom`, 선택적 `effectiveUntil`, `changeReason`, idempotency key를 받는다. 같은 version과 hash는 idempotent 성공, 같은 version의 다른 hash는 `409 POLICY_VERSION_CONFLICT`, 더 낮은 version은 `202 STALE_IGNORED`다.

tenant 관리자는 tenant 기본값과 모든 소속 clinic 정책을 조회할 수 있다. clinic 관리자는 자기 clinic override만 수정할 수 있고 tenant hard ceiling은 변경할 수 없다. effective policy 조회는 각 값의 출처와 구성 version을 반환하되 PHI를 포함하지 않는다.

활성화 전 impact preview는 영향받는 `PROPOSED`, `HELD`, `CONFIRMED` 수, capacity debt, 예상 solver 재계산량, mode 비활성화 영향과 경고를 반환한다. preview 결과와 활성화 명령은 policy draft revision을 비교하며, 중간에 draft가 바뀌면 `409 POLICY_PREVIEW_STALE`로 거부한다.

slot/proposal 응답의 `effectivePolicyId`와 `policyGeneration`은 hold·confirm의 precondition이다. 새 allocation 전에 generation이 바뀌면 `409 POLICY_CHANGED`와 최신 effective policy 참조를 반환한다.

### 14.3 기존 appointment 상태와 스키마

기존 `scheduling_appointments`는 방문 shell로 유지한다. 새 테이블 `scheduling_appointment_plans`, `scheduling_planned_treatments`, `scheduling_treatment_dependencies`, `scheduling_appointment_items`, `scheduling_resource_allocations`, `scheduling_reschedule_proposals`, `scheduling_policy_sets`, `scheduling_effective_policy_snapshots`를 additive하게 도입한다.

기존 row는 하나의 legacy plan/treatment/item을 backfill해 읽기 호환성을 유지한다. 기존 doctor/treatment/equipment 필드는 migration 기간 동안 대표 item에서 채운 projection으로 유지한 뒤, 모든 consumer가 item API로 이동한 다음 deprecated한다.

상태 호환:

| 기존 상태 | 새 의미 |
|---|---|
| `PENDING` | legacy provisional; 신규 쓰기는 `PROPOSED` 또는 `HELD` |
| `REQUESTED` | 고객/병원 확정 대기; 신규 API에서는 `PROPOSED`로 해석 |
| `PENDING_RESCHEDULE` | active `RescheduleCase`와 proposal 대기 |
| `RESCHEDULED` | 원 방문의 terminal history; 새 방문은 별도 identity |

기존 `confirmReschedule`은 내부적으로 proposal accept command를 호출하도록 bridge한다. 이미 확정된 예약에는 동의 record가 없으면 적용하지 않는다.

`SlotCalculationService`의 기존 단일 doctor/treatment/date query는 유지한다. 새 query는 item별 resource demand, commitment mode, capacity policy version을 받고 candidate마다 feasible resource set, 예상 대기, soft cost, rejection reason을 반환한다.

## 15. 주요 불변식

1. `AppointmentPlan`은 정확히 하나의 구매를 참조한다.
2. plan 생성 후 상품 snapshot은 불변이다.
3. `AppointmentItem`은 정확히 하나의 `PlannedTreatment`를 참조한다.
4. 하나의 `PlannedTreatment`에는 여러 attempt가 있을 수 있지만 성공 완료는 한 번뿐이다.
5. 후속 의존 기간의 anchor는 선행 item의 실제 완료 시각이다.
6. `CONFIRMED` 예약의 실질 변경에는 고객 동의가 필요하다.
7. 완료·진행 항목은 catalog revision 또는 환불로 과거를 다시 쓰지 않는다.
8. 오버부킹은 `CAPACITY_AND_OVERBOOKING` effective policy와 `absoluteBookingLimit` 안에서만 가능하다.
9. 영업 연장은 안전·법정·절대 종료 hard constraint를 넘지 않는다.
10. 고객 불만, 환불 판단, 보상은 예약서비스가 소유하지 않는다.
11. event와 객체의 tenant/clinic/patient ownership이 일치하지 않으면 fail closed한다.
12. hold·confirm 동시 경쟁에서도 자원 배타성과 `absoluteBookingLimit`이 보존된다.
13. 동일 policy 입력은 동일한 `EffectiveSchedulingPolicy` hash로 compile된다.
14. clinic override는 tenant ceiling과 platform safety guardrail을 완화할 수 없다.
15. 정책 변경은 확정·진행·완료 예약의 snapshot과 과거 사실을 자동으로 다시 쓰지 않는다.

## 16. 보안·감사·개인정보

- tenant와 clinic 범위를 모든 plan·appointment·event 처리에 강제한다.
- 의료 안전 정보와 고객 신뢰도는 최소 권한으로 분리한다.
- 주관적 고객 평판 문자열을 scheduling 입력으로 허용하지 않는다.
- 관리자 수동 override는 이전 값, 새 값, 사유, 행위자, 시각을 감사 로그에 남긴다.
- 고객 연락처·진료 상세를 solver score explanation이나 일반 로그에 노출하지 않는다.
- 재예약 제안과 고객 동의는 version을 비교해 오래된 응답이 최신 제안을 덮어쓰지 못하게 한다.
- 모든 cross-plan join, 환불, disruption, 의료진·장비 event는 tenant/clinic/patient object ownership을 재검증한다.
- PHI는 전송·저장 시 암호화하고 event projection에는 최소 필드만 담는다. 로그·metric은 allowlist 기반 redaction을 적용하고 읽기 접근도 감사한다.
- solver에는 환자 identity를 비식별 key로 전달하고 임상적으로 필요한 최소 정보만 포함한다.
- 보존·삭제 기간은 진료기록 소유 서비스와 법적 정책을 따르며, 예약 projection은 원천 삭제/가명화 event를 반영한다.
- 외부 입력은 payload 크기, 횟수·기간·날짜 범위, 알려진 resource ID, capacity의 음수/절대 상한, timezone, DAG acyclicity를 검증한다.
- reliability 특징은 allowlist와 최소 표본 기준을 사용하며 고객에게 정정·이의제기 경로를 제공한다. 의료적으로 필요한 진료를 거부하는 근거로 사용할 수 없다.
- 고위험 override는 privileged role과 step-up 인증을 요구한다. consent, quota, safety 관련 override는 이중 승인을 지원하고 append-only tamper-evident audit와 경보를 남긴다.
- 어떤 override도 의료 안전, 법정 근로, absolute booking/extension limit, tenant/clinic 경계를 무시할 수 없다.
- policy 조회·수정·검증·preview·활성화 권한을 분리하고 tenant/clinic scope를 매 요청에서 재검증한다.
- policy activation audit는 이전·새 version, effective window, preview hash, 승인자, 사유를 append-only로 보존한다.
- policy payload는 typed allowlist schema와 bounded 값만 허용하며 임의 script, expression language, 외부 URL을 실행하지 않는다.

## 17. 관측 가능성·SLO·성능 기준

- projection lag, catalog version conflict
- plan 생성·revision·취소 건수
- `PROPOSED`, `HELD`, `CONFIRMED` 전환율과 hold 만료율
- item 단위 중단·분리·재시도율
- disruption별 영향 예약 수와 평균 복구 시간
- 재예약 제안 수락률과 고객 동의 대기시간
- commitment mode별 실제 대기시간
- 시간대별 nominal 초과율, 모두 방문한 날의 overflow
- 연장 진료 시간과 absolute limit 근접도
- no-show 예측 오차와 신뢰도 profile 편향
- SLA breach event 발행 건수
- policy activation·compile 실패, effective-policy cache hit/miss, clinic override 비율
- 정책 변경으로 생긴 `POLICY_CAPACITY_DEBT`와 해소 시간

운영 dashboard는 `Catalog & Event`, `Booking Funnel`, `Disruption Recovery`, `Capacity & Wait`, `Consent & SLA` 패널로 구성하고 scheduling on-call이 소유한다.

초기 검증 기준:

| 항목 | 기준 |
|---|---|
| slot search | benchmark partition에서 p95 500ms, p99 1s 이하 |
| hold/confirm | 외부 알림 제외 p95 300ms, p99 750ms 이하 |
| purchase → plan event | p95 30초 이하 |
| disruption → 첫 proposal | affected item 10,000개 이하에서 p95 5분 이하 |
| interactive solver | 10초 이내 feasible 결과 |
| batch solver | partition당 60초 termination |
| queue | 80%에서 warning, 95%에서 backpressure와 high alert |
| commitment wait | mode별 병원 정책 SLO를 필수 설정하고 error budget 추적 |

benchmark dataset은 빈 clinic, 정상일, 최대 partition, disruption 10,000 item, 동시 confirm 경쟁, 모두 방문한 overbooking day를 포함한다. 인프라별 최종 숫자는 배포 전 capacity test로 pin하되 위 항목 자체를 제거할 수 없다.

alert는 tenant/clinic 범위를 포함하고 PHI를 제외한다. `absoluteBookingLimit` 위반 시도, policy compile 실패, `POLICY_CAPACITY_DEBT`, 서명 실패, consent replay, outbox/DLQ 적체, solver timeout, SLA error budget 소진은 즉시 운영 경보 대상이다.

## 18. 운영 복구와 배포

### 18.1 Recovery runbook

- consumer 실패: bounded retry → DLQ → 원천 version 확인 → dry-run re-drive → `eventId` 지정 재처리
- stuck case: 최신 disruption version 재조회 → 기존 proposal supersede → idempotent recompute
- stuck hold/proposal: 만료 job 재실행 → allocation orphan 검사
- unsafe quota: emergency disable로 quota 0 → 신규 초과 confirm 차단 → 기존 확정은 보존
- storm: proposal 생성 pause → 임상 긴급 확정 고객부터 chunk 복구 → notification throttle → backlog 정상화 후 resume

Scheduling on-call은 일정 상태와 재처리를, CRM은 고객 연락 timeout을, 커머스는 환불 결정을, 진료서비스는 completion 사실 정정을 소유한다.

### 18.2 Migration·rollout·rollback

1. additive schema와 index 배포
2. 새 write 비활성 상태로 legacy row backfill·검증
3. event consumer를 shadow/dry-run으로 실행해 diff 확인
4. clinic feature flag로 plan/item read 활성화
5. tenant default policy를 현재 운영값으로 backfill하고 clinic별 effective snapshot shadow 비교
6. 신규 구매부터 plan/item write 활성화
7. consent bridge와 disruption pipeline 순차 활성화
8. clinic override를 feature flag로 활성화
9. capacity/overbooking은 quota 0 기본값에서 별도 승인 후 활성화

old/new event schema는 최소 한 릴리스 window 동안 함께 읽는다. rollback은 새 write flag를 끄고 legacy projection read로 돌아가되 생성된 plan/item history는 삭제하지 않는다. backfill 불일치, tenant scope 오류, absolute limit 위반, DLQ 급증, SLO error budget 급소진은 즉시 rollback 기준이다.

release evidence에는 schema dry-run, backfill count/hash, shadow diff, event replay, 동시성·성능 benchmark, alert smoke test, rollback rehearsal 결과가 포함되어야 한다.

## 19. 단계적 구현 순서

1. catalog projection과 purchase snapshot
2. `AppointmentPlan`, `PlannedTreatment`, dependency DAG
3. `AppointmentItem`과 item별 자원 배정
4. `PROPOSED`/`HELD`/`CONFIRMED`, 고객 동의
5. 실제 완료 event 기반 후속 일정 재계산
6. 부분 완료·중단·단계 분리
7. 환불·추가 구매·cross-plan 합동 방문
8. 통합 disruption과 최소 변경 재예약
9. service tier·reliability·reconfirm
10. tenant default·clinic override·effective policy snapshot
11. commitment mode·통제된 오버부킹·영업 연장

각 단계는 이전 event 계약과 상태 이력을 유지하는 additive migration으로 진행한다.

## 20. 인수 기준

1. 반복 상품의 N개 진료 의무가 구매 snapshot에서 정확히 생성된다.
2. 패키지의 서로 다른 항목과 DAG 의존성이 표현된다.
3. 고객 희망일이 있으면 우선 사용하고, 없을 때만 최초 예약 기한 규칙으로 가예약한다.
4. 선행 item 실제 완료 후 후속 허용 기간이 재계산된다.
5. 한 방문에서 여러 item을 수행하고 item별 완료·중단을 기록한다.
6. 일부 완료 후 남은 item만 새 예약 attempt로 분리된다.
7. 추가 구매는 새 plan이지만 호환 항목은 같은 방문에 배치할 수 있다.
8. 부분 환불은 대상 plan의 미래 항목만 취소하고 공유 방문을 재계산한다.
9. 공휴일·휴진·장비 고장이 item 수준 영향 분석과 최소 변경 재예약으로 수렴한다.
10. 확정 예약 변경은 고객 동의 전까지 원래 약속을 보존한다.
11. 주관적 고객 라벨 없이 service tier와 객관적 reliability만 점수에 반영한다.
12. 정책 범위에서 의도적 오버부킹이 가능하고 absolute limit은 넘지 않는다.
13. `FIXED_SLOT`, `ARRIVAL_WINDOW`, `DATE_QUEUE`의 약속과 대기 정책이 구분된다.
14. 안전 한도 안에서 정상 영업시간 이후 진료가 soft penalty로 최적화된다.
15. 불만·보상·환불 판단은 외부 서비스 경계에 남고 예약은 사실 event만 발행한다.
16. 중복·역순·version gap event 재생이 consumer convergence 표대로 수렴한다.
17. 만료 hold/proposal, 중복 expiry, 늦은 고객 응답이 allocation을 누수하거나 기존 확정을 덮지 않는다.
18. 부분 완료 처리 중 각 checkpoint에서 crash 후 재실행해도 완료 item과 attempt가 중복되지 않는다.
19. 겹치는 disruption storm이 하나의 active case/version으로 수렴하고 stale proposal은 supersede된다.
20. 동시 hold/confirm이 같은 자원을 이중 점유하거나 absolute limit을 넘지 않는다.
21. tenant/clinic/patient 불일치, 위조·stale event, consent replay는 fail closed와 quarantine으로 끝난다.
22. 고객은 확정 전 commitment mode·대기·overflow 조건을 보고, 재예약 proposal에서 전후 차이와 응답 기한을 확인한다.
23. 기존 appointment row/state/API가 additive backfill과 bridge를 통해 migration window 동안 동작한다.
24. 정의된 SLO·최대 partition·disruption benchmark와 recovery/rollback rehearsal가 통과한다.
25. tenant 기본값과 clinic override가 typed 규칙으로 결정적으로 compile되고 값별 출처를 조회할 수 있다.
26. clinic override가 tenant hard ceiling을 완화하려 하면 validation에서 거부된다.
27. 정책 변경 후 새 예약과 proposal만 새 snapshot을 사용하고 기존 확정 예약은 고객 동의 전까지 유지된다.
28. stale preview·동시 activation·같은 version의 다른 payload·compile 실패가 이전 active policy를 손상하지 않는다.
29. tenant 정책 활성화가 clinic 수만큼 동기 fan-out write를 만들지 않고 generation 기반 lazy compile로 수렴한다.
30. 정책 활성화와 동시 실행된 새 hold·confirm은 stale generation으로 자원을 점유하지 않으며 기존 hold 확정은 pinned snapshot으로 보호된다.

### 20.1 Acceptance commands

구현 계획에서 실제 test class 이름을 pin하고 다음 module-scoped 검증을 최소 기준으로 사용한다.

```bash
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-solver:test
./gradlew :appointment-notification:test
./gradlew :appointment-api:test
./gradlew :appointment-core:build :appointment-event:build :appointment-solver:build :appointment-api:build
```

추가로 DB별 Flyway 검증, catalog/policy API contract test, effective policy compiler determinism·inheritance·time-basis test, event duplicate/out-of-order test, hold/confirm concurrency test, disruption benchmark와 HTML parse/link/browser smoke test를 수행한다.

## 21. 남은 위험

| 위험 | 완화 |
|---|---|
| 상품 BOM 변경이 진행 중 plan을 예기치 않게 바꿈 | 버전 snapshot + 명시적 `FUTURE_ONLY` revision |
| 부분 완료가 중복 이행으로 계산됨 | attempt lineage와 성공 완료 유일성 |
| 여러 disruption이 재예약 폭풍을 만듦 | `RescheduleCase` 병합, debounce, versioned proposal |
| 고객 등급이 기존 확정 고객을 밀어냄 | 확정 보호와 bounded soft weight |
| no-show 예측이 특정 고객군에 불공정 | 설명 가능한 특징만 사용, 정기 편향 감사, 수동 override 기록 |
| 오버부킹으로 과도한 대기·연장 발생 | nominal/overbooking/absolute 3단계 한도와 SLA 관측 |
| 연장 진료가 안전·노동 제약을 침해 | 관련 제한을 hard constraint로 분리 |
| 예약 서비스가 고객 응대·환불 책임까지 흡수 | 객관적 사실 event만 발행하는 경계 테스트 |
| tenant와 clinic 정책 상속이 운영자에게 불투명 | effective 조회에서 값별 출처와 구성 version 제공 |
| 미래 정책의 적용 시점이 예약 생성일과 진료일 사이에서 혼동 | policy kind별 `DECISION_TIME`/`SERVICE_TIME` 평가 기준 고정 |
| 상한 축소가 기존 확정 예약을 대량 취소 | 기존 snapshot 보호, 신규 확정 차단, `POLICY_CAPACITY_DEBT` 복구 |
| tenant 정책 활성화가 수천 clinic에 fan-out storm을 만듦 | generation 기반 cache invalidation, lazy compile, bounded partition warm-up |

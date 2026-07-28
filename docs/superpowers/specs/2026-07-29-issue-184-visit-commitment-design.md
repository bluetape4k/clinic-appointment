# 방문 예약·확정 약속·상품 버전 전환 설계

> Issue: [#184](https://github.com/bluetape4k/clinic-appointment/issues/184)
>
> 상태: 대화형 설계 승인 후 문서화, 2-R 검토 전
>
> 기준일: 2026-07-29
>
> 시각 자료:
> [상품 예약 운영 특성 분류 승인안](./2026-07-29-issue-184-product-scheduling-classification.html) ·
> [패키지 상품 구성 그래프 승인안](./2026-07-29-issue-184-package-product-composition.html) ·
> [상품 실행 BOM의 예약 전개 흐름](./2026-07-29-issue-184-product-bom-to-appointment-flow.html)

## 1. 문제

현재 `Appointment`는 한 번의 예약을 표현하지만, 실제 병원 업무에서 필요한 다음
관계를 충분히 설명하지 못한다.

- 고객은 한 상품을 구매하고 여러 차례 방문한다.
- 미백치료 5회권처럼 같은 상품을 반복하는 패키지가 있다.
- 서로 다른 상품을 필수·선택·택일 조건과 선후행 관계로 묶는 패키지가 있다.
- 한 번의 방문에서 여러 세부 진료를 함께 받을 수 있다.
- 세부 진료마다 의료진, 장비, 진료 공간과 점유 시간이 다르다.
- 패키지 구성 상품마다 진료·준비·회복 시간이 다르며, 같은 날 묶을 수 없는
  항목도 있다.
- 고객이 요청한 날짜는 병원이 승인하기 전까지 확정 예약이 아니다.
- 병원이 제안한 일정은 고객이 동의하기 전까지 기존 확정 예약을 대체하지 못한다.
- 상품 설정은 구매 당시 버전으로 고정되지만, 상품팀이 고객 동의를 얻어 기존
  구매를 새 상품 버전으로 전환할 수 있다.
- 전환을 거부한 고객은 상담 대상이 되지만, 예약 자체는 여전히 유효할 수 있다.

예약서비스는 상품 계약, 시술 완료의 임상 판단, 환불 금액, 고객 보상을 소유하지
않는다. 대신 외부 서비스가 제공한 사실을 검증해 예약 계획, 방문, 확정 약속,
자원 점유와 운영 예외에 반영해야 한다.

## 2. 목표

1. 하나의 `Appointment`를 한 번의 방문으로 유지한다.
2. 한 방문에 여러 `AppointmentItem`과 항목별 `ResourceAllocation`을 둔다.
3. 고객 요청과 병원 제안을 가예약으로 관리하고, 승인과 동의가 충족된 정확한
   제안만 확정한다.
4. 확정 예약 변경 중에도 기존 예약을 보호한다.
5. Gateway가 제공한 인증 정보를 `ActorContext`로 정규화하고 업무 권한 판정과
   감사 기록에 사용한다.
6. 예약 결정에 사용한 상품·정책·동의 스냅샷을 불변으로 보존한다.
7. 반복형·복합형·선택형 패키지를 version이 고정된 구성 상품 그래프로 표현하고
   구매 시 실행 BOM으로 전개한다.
8. 상품 버전 전환은 상품팀이 소유한 동의 및 BOM 전환표로만 수행한다.
9. H2, PostgreSQL, MySQL에서 같은 업무 의미와 동시성 안전성을 유지한다.
10. 기존 예약 API와 과거 데이터를 파괴하지 않는 추가형 전환 경로를 제공한다.

## 3. 비목표와 서비스 경계

| 관심사 | 원천 서비스 | 예약서비스의 책임 |
|---|---|---|
| 상품 정의, 패키지 구성 그래프, BOM, 상품 버전 | 상품관리서비스 | 검증·전개된 실행 BOM과 구성 상품 version 출처를 저장하고 예약 판단에 사용 |
| 구매 계약과 추가 구매 | 구매서비스 | 구매 이벤트마다 새 `AppointmentPlan` 생성 |
| 상품 버전 전환 동의 | 상품관리서비스 | 권한과 동의 증빙이 있는 전환 이벤트만 소비 |
| 실제 시술 완료 | 진료/시술서비스 | 완료 이벤트를 받아 항목 이력과 후속 예약 범위 계산 |
| 환불 승인과 금액 | 결제/커머스서비스 | 환불로 취소할 미래 예약 범위만 반영 |
| 상담, 보상, 민원 해결 | CRM/상담서비스 | 운영 예외를 발행하고 상담 결과 이벤트를 소비 |
| 예약 제안, 확정, 자원 점유 | 예약서비스 | 소유 |
| 실제 일정 변경 동의 | 예약서비스 | 구체적인 날짜·시간·자원 변경에 대한 동의 증빙 관리 |

다음 기능은 이번 구현에서 제외한다.

- 진료 완료를 판정하는 임상 업무
- 환불 가능 여부와 금액 계산
- 고객 보상과 상담 사례 관리
- 추가 상품 구매를 기존 Plan에 병합
- 여러 구매 Plan을 하나의 방문에 합치는 교차 Plan 배치
- 대량 장애 재조정, 대기목록, 재확인 발송과 솔버 재설계
- Gateway 로그인, MFA, 신원 확인 구현
- 즉시 전면 전환 또는 기존 예약 데이터 일괄 역채움
- GitHub Pages 공개 구성

단, 후속 기능이 같은 모델을 사용할 수 있도록 항목별 이력, 의존 관계, 운영 예외와
outbox 계약은 이번 설계에 포함한다.

## 4. 핵심 결정

### 4.1 방문과 약속을 분리한다

`Appointment`는 방문의 대표 진료명, 날짜와 기존 호환 필드를 유지한다.
확정 약속의 상태와 제안 이력은 1:1 동반 aggregate인
`AppointmentCommitment`가 소유한다.

```text
AppointmentPlan 1 ── PlannedTreatment N
                           ▲
                           │ fulfills / attempts
Appointment 1 ── AppointmentItem N
      │
      ├── AppointmentCommitment 1
      │       ├── AppointmentProposal N
      │       └── ConsentDecision N
      │
      └── ResourceAllocation N
```

`Appointment`의 기존 날짜, 시간, 의료진과 장비 필드는 확정된
`AppointmentProposal`의 호환 projection이다. 두 모델을 독립적으로 수정하지
않는다.

### 4.2 확정된 제안을 명시적으로 가리킨다

`AppointmentProposal`은 수정하지 않고 새 revision을 추가한다.
`AppointmentCommitment.confirmedProposalId`는 고객과 병원이 합의한 정확한
revision을 가리킨다.

새 제안을 준비하는 동안 기존 `confirmedProposalId`와 자원 점유는 유지한다.
고객이 새 제안을 수락하면 다음 변경을 한 트랜잭션에서 수행한다.

1. 새 제안과 동의 증빙을 다시 검증한다.
2. 새 자원 점유를 획득한다.
3. `confirmedProposalId`를 새 revision으로 교체한다.
4. 기존 자원 점유를 해제한다.
5. 상태 이력과 outbox 이벤트를 기록한다.

새 자원 점유에 실패하면 기존 확정 예약을 그대로 유지한다.

### 4.3 상품은 하나의 enum이 아니라 예약 운영 특성의 조합이다

상품관리서비스는 상품기획자가 이해할 수 있는 다중 선택 항목으로 예약 운영
특성을 작성하고, 함께 사용할 수 없는 조합을 저장 전에 거부한다.

초기 특성은 다음과 같다.

| 상품기획 용어 | 예약 계약 식별자 |
|---|---|
| 여러 번 방문해서 진행 | `MULTI_VISIT` |
| 여러 시술을 묶은 패키지 | `PACKAGE` |
| 앞 시술 완료 후 다음 예약 | `PREDECESSOR_DEPENDENT` |
| 남은 시술을 다른 날 이어서 진행 | `PARTIAL_FULFILLMENT` |
| 짧은 시간에 많은 고객을 수용 | `MASS_PROMOTION` |
| 시간대별 최대 예약 인원 관리 | `CAPACITY_BUCKET` |
| 고객 한 명을 집중 관리 | `VIP_MANAGED` |
| 의료진·장비·공간 전담 배정 | `EXCLUSIVE_RESOURCE` |
| 수술 전 준비와 회복 시간 확보 | `SURGICAL_COORDINATION` |
| 병원 정책 범위에서 초과 예약 허용 | `OVERBOOKING_ALLOWED` |

예약서비스는 상품의 가격, 마케팅 문구와 계약 상세를 알 필요가 없다. 검증된
예약 운영 특성, 예상 소요시간, BOM과 자원 요구사항만 버전 스냅샷으로 받는다.

구성 상품 version이 정한 임상 안전 조건, 필수 자원, 최소 준비·회복 시간과
필수 선행 관계는 패키지가 완화할 수 없는 **강제 제약**이다. 반면 초과 예약,
우선순위, 재확인·알림, 선점형·제안형 같은 **운영 정책**은 플랫폼 안전 상한
안에서 병원 기본값을 상품 또는 패키지가 재정의할 수 있다.

상품 예상 소요시간과 병원 자원의 수용량 계산 단위는 서로 다르다. 예를 들어
30분 상품을 15분 단위 자원에 배정하면 연속 두 칸을 점유한다. 자원 계산 단위는
병원 또는 자원별로 10분, 15분, 30분, 60분이나 별도 값이 될 수 있다.

### 4.4 패키지는 version이 고정된 구성 상품 그래프다

`PACKAGE` 특성만으로는 패키지의 실제 구성을 설명할 수 없다. 상품관리서비스는
패키지 상품 version마다 구성 상품 node와 관계 edge를 가진 불변 그래프를
발행한다.

지원할 패키지 형태는 다음과 같다.

| 형태 | 예 | 구성 |
|---|---|---|
| 반복형 | 미백치료 5회권 | 같은 구성 상품 version 하나와 `quantity=5` |
| 복합형 | 진단 + 리프팅 + 진정 관리 | 서로 다른 구성 상품 version과 필수·선택·선후행 관계 |
| 선택형 | 피부관리 3개 중 2개 | 선택군과 `selectCount=2` |

구성 node는 정확한 `componentProductId`와 `componentProductVersionId`를
참조한다. 발행된 패키지가 구성 상품의 “최신 version”을 동적으로 따라가지
않는다.

각 node는 다음 정보를 가진다.

- `REQUIRED`, `OPTIONAL`, `CHOICE_GROUP` 구성 방식
- 수량과 반복 횟수
- 선택군 ID, 선택 가능한 수와 반드시 선택할 개수
- 구성 상품 version의 회차별 진료·준비·회복 시간
- 회차별 의료진, 장비, 공간과 수용량 사용량
- 고객에게 표시할 구성 상품명과 원본 version 출처

관계 edge는 두 종류의 독립된 제약을 표현한다.

1. 실행 의존성: `BLOCKING`, `NON_BLOCKING`, 실제 완료 기준의 최소·권장·최대 간격
2. 방문 묶음: `MUST_SAME_VISIT`, `MAY_SAME_VISIT`, `MUST_SEPARATE_VISIT`

패키지 전체에 하나의 `durationMinutes`를 두지 않는다. 같은 방문으로 묶인 항목도
각자의 시간 구간을 유지한다. 방문의 시작·종료는 순차 실행, 병렬 가능 여부,
준비·전환·회복 시간을 반영해 계산한다. 서로 다른 날 진행할 항목의 시간을 합산해
하나의 패키지 시간으로 표시하지 않는다.

상품관리서비스는 저장·발행 전에 다음을 검증한다.

- 구성 상품 version의 존재와 사용 가능 상태
- 필수·선택·택일 조건의 충족 가능성
- 조합 불가 상품과 자원 요구의 모순
- 실행 의존성과 방문 묶음 관계의 cycle 또는 직접 충돌
- `MUST_SAME_VISIT` 항목의 시간·자원 양립 가능성
- 모든 선택 결과가 실행 가능한 BOM으로 전개되는지

구매 시점에는 고객이 고른 선택지를 확정하고 반복 횟수를 실제 회차로 전개한다.
상품관리 또는 구매서비스는 **전개된 실행 BOM**과 구성 상품 version provenance를
이벤트에 담는다. 예약서비스는 패키지 그래프를 재귀 조회하거나 상품 의미를 다시
해석하지 않는다.

### 4.5 상품 실행 BOM을 예약 Plan과 방문으로 전개한다

상품관리 또는 구매서비스가 발행한 `PackageExecutionSnapshot`은 구매 당시
선택과 version이 고정된 불변 실행 계약이다. 예약서비스는 같은 event ID와
구매·상품 version 조합을 멱등하게 소비하고 다음 순서로만 전개한다.

1. 이벤트 출처, schema version, 구매·상품 version과 구성 node provenance를
   검증한다.
2. 실행 BOM을 새 `AppointmentPlan` revision의 입력 스냅샷으로 보존한다.
3. 반복 횟수, 선택 결과와 선후행 관계를 `PlannedTreatment`로 전개한다.
4. `MUST_SAME_VISIT`, `MAY_SAME_VISIT`, `MUST_SEPARATE_VISIT`와 항목별
   시간·자원 제약을 사용해 방문 후보를 묶는다.
5. 병원 정책과 고객 희망 일정을 적용해 하나 이상의
   `AppointmentProposal`을 만든다.
6. 필요한 병원 승인과 고객 동의를 받은 제안만 자원을 점유하고 확정한다.

예약서비스는 패키지 구성 의미를 작성하거나 원본 BOM을 재귀 해석하지 않는다.
완료·부분 이행·장비 고장·휴진 같은 후속 사실이 들어오면 이미 완료된
`PlannedTreatment`와 확정 이력은 유지하고, 아직 수행하지 않은 항목만 새 Plan
revision과 제안으로 재계산한다. 기존 확정 예약을 바꾸는 제안은 고객 동의 전까지
기존 `confirmedProposalId`와 자원 점유를 대체하지 않는다.

## 5. 도메인 모델

### 5.1 `AppointmentCommitment`

| 속성 | 의미 |
|---|---|
| `appointmentId` | 방문과의 1:1 식별자 |
| `status` | `PROPOSED`, `HELD`, `CONFIRMED`, `EXPIRED`, `CANCELLED` |
| `origin` | `PATIENT`, `CLINIC`, `SYSTEM` |
| `confirmedProposalId` | 현재 합의된 제안 revision |
| `effectivePolicySnapshotId` | 이 결정에 사용한 병원 정책 스냅샷 |
| `version` | 낙관적 동시성 제어 값 |

`CommitmentStatus`는 기존 `AppointmentState`와 분리한다. 방문의 접수·진행·완료
상태와 일정 합의 상태는 서로 다른 축이기 때문이다.

### 5.2 `AppointmentProposal`

| 속성 | 의미 |
|---|---|
| `revision` | commitment 안에서 단조 증가하는 번호 |
| `proposedStartAt`, `proposedEndAt` | 제안된 방문 시간 |
| `expiresAt` | 제안 또는 hold 만료 시각 |
| `representativeTreatmentName` | 방문 대표 진료명 |
| `proposalHash` | 날짜, 항목, 자원, 정책을 포함한 정규 hash |
| `policySnapshotId` | 제안 생성 당시 정책 |
| `supersedesProposalId` | 이전 제안 |
| `createdByActor` | 제안 행위자 감사 정보 |

이미 발행된 proposal은 수정하지 않는다. 날짜, 항목, 자원이나 정책이 달라지면
새 revision을 만든다.

### 5.3 `AppointmentItem`

한 방문에서 수행하려는 세부 진료다.

- 정확히 하나의 `PlannedTreatment`를 참조한다.
- tenant, clinic, patient와 Plan 범위가 모두 같아야 한다.
- `plannedTreatmentId`, `planRevisionId`, `productVersionId`를 보존한다.
- 한 방문에 여러 항목이 들어갈 수 있다.
- 부분 수행 또는 장비 고장으로 남은 항목은 후속 방문의 새 item attempt가 된다.
- 완료 판단은 방문 전체가 아니라 item 단위다.

### 5.4 `ResourceAllocation`

각 `AppointmentItem`이 점유할 의료진, 장비와 진료 공간을 표현한다.

- 자원 유형: `PRACTITIONER`, `EQUIPMENT`, `TREATMENT_SPACE`
- 점유 구간과 수용량 사용량을 저장한다.
- 전담 자원은 같은 구간의 공유 점유를 허용하지 않는다.
- `CAPACITY_BUCKET` 자원은 계산 단위별 사용량 합계가 상한을 넘지 않아야 한다.
- 구체적인 `TreatmentSpace`와 capability를 실제 자원으로 관리한다.

### 5.5 `ConsentDecision`

동의는 상태 필드를 덮어쓰지 않고 append-only 기록으로 남긴다.

| 속성 | 의미 |
|---|---|
| `subjectType`, `subjectId` | 상품 버전 전환 또는 예약 proposal |
| `decision` | `ACCEPTED`, `DECLINED`, `REVOKED` |
| `evidenceAuthority`, `evidenceId` | 동의 증빙의 원천과 식별자 |
| `evidenceHash` | 검증된 증빙 스냅샷 hash |
| `decidedAt` | 고객 결정 시각 |
| `actorRef` | 고객 또는 적법한 대리인 |

고객이 직접 요청한 날짜와 구성이 병원 승인 과정에서 바뀌지 않았다면 원 요청을
동의 증빙으로 사용할 수 있다. 날짜, 항목이나 중요한 조건이 달라지면 새 동의가
필요하다.

### 5.6 `OperationalException`

예약 생명주기를 바꾸지 않고 상담이나 수동 조정이 필요한 사실을 기록한다.

- `CUSTOMER_DECLINED_RESCHEDULE`
- 영향받은 appointment, proposal, Plan과 Plan Revision
- 발생 원인과 고객 응답
- `OPEN`, `ACKNOWLEDGED`, `RESOLVED`, `SUPERSEDED`
- 상담서비스 handoff event와 외부 처리 결과 참조

고객이 새 일정을 거부해도 기존 확정 예약은 `CONFIRMED` 상태를 유지한다.

### 5.7 `PackageExecutionSnapshot`

구매 시 선택과 반복 전개가 끝난 패키지 실행 계약이다.

| 속성 | 의미 |
|---|---|
| `packageProductId`, `packageProductVersionId` | 구매한 패키지의 불변 version |
| `selectedComponentVersions` | 선택된 구성 상품 ID와 version |
| `expandedTreatmentItems` | 반복과 선택을 반영한 실행 BOM |
| `executionDependencies` | 실제 완료 기준의 `BLOCKING` / `NON_BLOCKING` 관계 |
| `visitGroupingConstraints` | 같은 방문 필수·허용·분리 관계 |
| `snapshotHash` | 위 전체를 포함한 정규 hash |

`expandedTreatmentItems`의 각 항목은 자신의 진료·준비·회복 시간과 자원 요구를
가진다. 패키지 표시용 합계 시간이 있더라도 예약 계산의 원본으로 사용하지 않는다.
예약 Plan은 이 snapshot을 다시 전개하지 않고 그대로 `PlannedTreatment`로
복사한다.

## 6. 행위자와 API 흐름

### 6.1 신뢰 경계

API Gateway가 서명한 인증 정보에서 `ActorContext`를 만든다. request body의
사용자, tenant, clinic, patient, origin 값은 권한 판정에 사용하지 않는다.

필수 검증 항목은 issuer, audience, algorithm allowlist, signature, expiration,
not-before, token ID, actor type, 역할, tenant/clinic 범위와 patient subject다.
일반 `X-User-*` 헤더는 신뢰하지 않는다.

### 6.2 고객 요청

```text
고객 희망일 입력
  → PROPOSED 생성
  → 정책에 따라 자원 hold
  → 병원 검토
      ├─ 동일 조건 승인 → CONFIRMED
      └─ 조건 변경 → 새 proposal → 고객 동의 → CONFIRMED
```

고객 요청은 항상 가예약으로 시작한다. 고객에게 희망일을 받지 않은 상품만 상품
예약 규칙의 “N일 이내 제안”을 사용한다. 구매일은 임상 과정의 선후행 기준이
아니다.

### 6.3 병원 관리자 요청

병원 관리자는 예약을 직접 제안하거나 생성할 수 있다. 바로 확정하려면 다음 조건을
모두 충족해야 한다.

- 유효 병원 정책이 직접 확정을 허용한다.
- 정확한 proposal에 대한 고객 동의 증빙이 있다.
- 자원 충돌 검증과 점유가 성공한다.
- 행위자의 tenant/clinic 권한이 확인된다.

### 6.4 확정 예약 변경

확정 예약을 바꾸는 요청은 기존 예약을 먼저 취소하지 않는다. 새 proposal을 만들고
고객 동의를 기다린다. 거부하거나 만료되면 기존 예약을 유지한다.

## 7. 병원 정책과 상품 스냅샷

상품 계약과 병원 운영정책은 수명이 다르다.

- 상품 버전은 구매 시 고정한다.
- 상품 스냅샷은 Plan과 진료항목의 계약 출처다.
- 병원 운영정책은 proposal 또는 확정 판단 시점마다 스냅샷을 만든다.
- 가예약을 다시 계산할 때는 최신 유효 병원 정책을 사용할 수 있다.
- 확정 예약은 당시 proposal과 정책 스냅샷을 유지한다.
- 정책 변경만으로 확정 예약을 조용히 바꾸지 않는다.

예약 판단은 다음 입력을 합성한다.

```text
구매에 고정된 단일 상품 또는 패키지 실행 BOM
  + tenant 기본 정책
  + clinic override
  + 실제 자원 capability와 가용량
  = proposal에 고정할 Effective Scheduling Decision
```

상품 특성이 병원 정책이나 자원 capability와 양립하지 않으면 자동으로 약화하지
않고 stable reason code로 거부한다.

## 8. 상품 버전 고정과 승인된 전환

### 8.1 기본 원칙

상품 설정은 기존 version을 수정하지 않고 새 `ProductVersion`으로 발행한다.
구매와 `AppointmentPlan`은 구매 당시 `productVersionId`, 상품 스냅샷과 hash를
영구 참조한다. 새 상품 version은 이후 구매부터 적용한다.

패키지 구매는 패키지 상품 version뿐 아니라 선택된 모든 구성 상품 version과
전개된 실행 BOM을 고정한다. 이후 구성 상품의 새 version이 발행되어도 기존
패키지 구매를 자동으로 다시 전개하지 않는다.

새로운 구매는 항상 새 `AppointmentPlan`을 만든다.

### 8.2 예외적 버전 전환

상품팀이 기존 구매에도 새 상품 version을 적용해야 한다면 고객 동의를 확보하고
`ProductVersionMigrationApproved`를 발행한다.

필수 필드는 다음과 같다.

- `migrationId`
- 대상 구매와 Plan
- `fromProductVersionId`, `toProductVersionId`
- 동의 원천, 증빙 ID, hash와 동의 시각
- 적용 사유와 승인자
- 적용 시각과 source aggregate version
- 버전 간 BOM 전환표

예약서비스는 상품 동의 절차를 수행하지 않는다. 신뢰된 상품 authority, event
순서, 현재 version, 동의 증빙과 전환표를 검증한다. 불완전하거나 모순된 event는
부분 적용하지 않고 격리한다.

### 8.3 동일 Plan, 새 Revision

승인된 전환은 새 Plan을 만들지 않는다. 동일 구매의 `AppointmentPlan.id`를
유지하고 불변 `AppointmentPlanRevision`을 추가한다.

- 완료된 진료항목은 구 version과 구 revision에 남는다.
- 미진행 항목만 새 revision으로 승계한다.
- 각 항목은 자신이 만들어진 `productVersionId`와 `planRevisionId`를 유지한다.
- 전환 event 처리가 성공하면 새 Plan Revision을 즉시 활성화한다.
- 이미 확정된 appointment는 기존 proposal 스냅샷에 고정된다.
- 새 version 때문에 실제 일정 변경이 필요하면 별도의 proposal과 일정 변경
  동의를 받는다.

고객이 일정 변경을 거부해도 Plan Revision 전체를 롤백하지 않는다. 기존 확정
예약을 보호하고 `OperationalException`을 발행해 상담팀으로 넘긴다.

## 9. BOM 전환표와 의존 관계

상품팀은 version 사이의 의미를 가장 잘 알기 때문에 예약서비스가 코드나 이름으로
항목을 추측해 연결하지 않는다.

| 전환 유형 | 의미 |
|---|---|
| `KEEP` | 같은 진료 의무 유지 |
| `REPLACE` | 기존 항목을 새 항목으로 교체 |
| `SPLIT` | 하나의 미진행 항목을 여러 항목으로 분리 |
| `MERGE` | 여러 미진행 항목을 하나로 통합 |
| `REMOVE` | 미진행 항목 제거 |
| `ADD` | 새 진료 의무 추가 |

전환표 검증은 다음을 보장한다.

- 모든 미진행 source 항목은 정확히 한 번 설명된다.
- 수량, 반복 횟수와 source/target version이 일치한다.
- 완료된 항목은 `REPLACE`, `REMOVE`, `SPLIT`, `MERGE` 대상이 아니다.
- 결과 의존 그래프에 cycle이 없다.
- 새 항목의 자원 요구사항과 예약 특성이 새 상품 version에 존재한다.
- 패키지 전환이면 선택 결과, 구성 상품 version과 방문 묶음 제약이 모두 설명된다.
- 같은 event replay는 같은 결과를 반환한다.

예외 전파는 상품 전체의 B/C 플래그가 아니라 versioned BOM edge에 기록한다.

- `BLOCKING`: 선행 항목이 해결되지 않으면 후속 항목과 전이적으로 연결된
  `BLOCKING` 경로를 보류한다.
- `NON_BLOCKING`: 선행 항목에 운영 예외가 있어도 후속 항목을 계속 예약할 수 있다.

따라서 독립 항목만 있는 상품은 거부된 예약만 보류하고, 의존 관계가 있는 상품이나
혼합 패키지는 실제 차단 경로만 보류한다.

## 10. 자원 충돌과 수용량

기본 자원 충돌 방식은 시간 구간 점유다. PostgreSQL advisory lock을 같은 자원
구간의 직렬화 최적화로 사용할 수 있지만, 그것만을 유일한 정합성 장치로 삼지
않는다. H2, PostgreSQL, MySQL 공통 correctness는 다음 조합으로 보장한다.

- aggregate version을 사용한 compare-and-set
- 정렬된 자원 잠금 순서
- 동일 자원·시간 구간의 재검증
- transaction 안의 allocation 생성
- 충돌 시 전체 command rollback

`CAPACITY_BUCKET` 상품과 자원은 단위 시간별 사용량 합계로 판정한다. 병원은
정책 범위에서 초과 예약을 허용할 수 있지만 platform safety ceiling과 전담·수술
자원 제한을 넘을 수 없다.

## 11. 일관성, 멱등성과 이벤트

모든 상태 변경 command는 `tenant + clinic + actor scope + idempotency key`로
멱등성을 판정한다. 같은 key와 다른 command hash는 충돌이다.

다음 변경은 하나의 DB transaction에서 처리한다.

1. 현재 aggregate와 expected version 검증
2. proposal, commitment, consent 또는 Plan Revision 저장
3. 자원 점유 생성·교체·해제
4. 상태 및 운영 예외 이력 추가
5. transactional outbox event 추가
6. idempotency 결과 저장

대표 outbox event는 다음과 같다.

- `AppointmentProposed`
- `AppointmentHeld`
- `AppointmentConfirmed`
- `AppointmentProposalExpired`
- `AppointmentChangeConsentRequired`
- `AppointmentConfirmedProposalChanged`
- `ProductVersionMigrationApplied`
- `ProductVersionMigrationRejected`
- `CustomerDeclinedRescheduleExceptionOpened`
- `AppointmentOperationalExceptionResolved`

외부 event consumer는 inbox, source aggregate version과 payload hash를 사용한다.
version gap은 대기시키고, 같은 version의 다른 payload와 권한 불일치는 격리한다.

## 12. 호환성과 데이터베이스 전환

### 12.1 추가형 V10

H2, PostgreSQL, MySQL에 같은 의미의 V10 migration을 추가한다. 기존
`scheduling_*` table 이름은 바꾸지 않는다.

필요한 신규 table은 다음 범주를 포함한다.

- appointment commitment와 proposal revision
- appointment item과 resource allocation
- consent decision
- appointment plan revision과 BOM migration mapping
- operational exception과 상태 이력
- command idempotency 및 필요한 outbox 확장

구체적인 column, index, 외래키와 backend별 DDL은 구현 계획에서 확정한다.
Flyway가 운영 migration의 권위다. Exposed table 정의와 schema 검사 기능은 DDL
동등성 검증 및 테스트에 사용하지만 운영 migration을 대신하지 않는다.

### 12.2 기존 예약 호환

- companion aggregate가 없는 기존 appointment는 legacy 경로로 조회한다.
- 기존 `POST /appointments` 계약은 명시적인 compatibility/shadow 경로로 유지한다.
- 새 API로 생성한 예약만 commitment와 item 모델을 필수로 사용한다.
- legacy 필드는 확정 proposal의 projection으로만 갱신한다.
- 기존 row를 일괄 역채움하지 않는다.
- cutover와 기존 API 제거는 별도 승인된 작업으로 다룬다.

## 13. 실패 처리

| 실패 | 처리 |
|---|---|
| 고객 요청의 tenant/clinic/patient 범위 불일치 | command 전체 거부, 자원 점유 없음 |
| 관리자 직접 확정에 동의 증빙 없음 | `CONSENT_REQUIRED` |
| proposal 만료 후 수락 | 만료된 revision 거부, 새 proposal 요구 |
| 새 제안 자원 충돌 | 기존 확정 예약 유지 |
| 같은 idempotency key의 다른 내용 | stable conflict 응답 |
| 상품 migration의 from-version 불일치 | event 격리, Plan 불변 |
| BOM 전환표 누락·중복·cycle | event 전체 격리, 부분 적용 없음 |
| 고객이 실제 일정 변경 거부 | 기존 확정 예약 유지, `OperationalException` 생성 |
| 상담서비스 일시 장애 | outbox 재시도, 예약 transaction은 이미 완료 |
| `BLOCKING` 선행 항목 미해결 | 해당 항목과 전이적 차단 경로만 보류 |
| 알 수 없는 상품 또는 정책 schema version | 조기 거부, 이전 유효 snapshot 유지 |

오류 응답과 event는 stable reason code를 사용한다. JWT 원문, 동의 문서 원문,
민감한 환자 정보와 parser 내부 오류는 로그나 응답에 노출하지 않는다.

## 14. API 계약 원칙

행위자별 controller를 분리하고 동일 application command service를 호출한다.

- 고객: 예약 요청, proposal 조회, 수락과 거부
- 병원 관리자: 직접 제안, 승인, 정책 허용 시 직접 확정, 변경 제안
- 시스템 consumer: 상품 version 전환, 상담 결과, 환불과 진료 완료 event

모든 mutation API는 idempotency key와 expected version을 받는다. 인증 범위는
`ActorContext`에서 가져오며 body에 actor ID, tenant 또는 clinic을 중복 입력하지
않는다. OpenAPI는 각 endpoint의 허용 actor, 가예약 여부, 필요한 동의, 충돌과
만료 응답을 설명한다.

## 15. 테스트 전략

### 15.1 단위 테스트

- commitment와 proposal 상태 전이
- 확정 proposal 교체의 원자성
- 동의 증빙과 proposal hash 일치
- 상품 특성 조합 및 병원/자원 capability 검증
- 반복형, 복합형, N-of-M 선택형 패키지의 실행 BOM 전개
- 구성 상품별 진료·준비·회복 시간과 자원 provenance 보존
- `MUST_SAME_VISIT`, `MAY_SAME_VISIT`, `MUST_SEPARATE_VISIT` 검증
- 순차·병렬 항목과 준비·회복 시간을 반영한 방문 구간 계산
- BOM `KEEP/REPLACE/SPLIT/MERGE/REMOVE/ADD`
- `BLOCKING` 의존 경로 전파와 `NON_BLOCKING` 독립 진행
- Plan Revision 활성화와 항목 provenance
- 운영 예외가 예약 상태를 변경하지 않는 규칙

### 15.2 저장소와 동시성 테스트

- Exposed 작업은 모두 caller-owned `transaction {}` 안에서 수행
- 같은 proposal의 중복 confirm
- 서로 다른 proposal의 동시 accept
- 같은 자원 구간의 동시 점유
- capacity bucket 경계와 초과 예약 ceiling
- event replay, version gap과 same-version/different-payload
- outbox 원자성과 재시도

### 15.3 데이터베이스 순차 검증

H2, PostgreSQL, MySQL migration과 저장소 테스트를 순차 실행한다. 실제 운영
기준은 PostgreSQL이며 H2 성공만으로 완료를 주장하지 않는다. Testcontainers는
bluetape4k singleton launcher를 사용하고 `@Testcontainers`를 추가하지 않는다.

### 15.4 API와 보안 테스트

- 고객 요청이 관리자 승인 없이 확정되지 않음
- request body의 위조 actor/tenant/clinic 무시 또는 거부
- clinic 범위를 벗어난 관리자 거부
- 관리자 직접 확정의 정책과 동의 요구
- 확정 예약 변경에 새 동의가 필요함
- 안정적인 오류 코드와 개인정보 비노출

## 16. KDoc과 문서화

새 public 및 업무 규칙형 internal Kotlin 선언에는 한국어 KDoc을 작성한다.
특히 다음 속성은 단순 이름 반복이 아니라 불변조건과 상태 의미를 설명한다.

- `confirmedProposalId`
- `proposalHash`
- `effectivePolicySnapshotId`
- `productVersionId`
- `packageProductVersionId`, `componentProductVersionId`
- 선택군, 반복 횟수와 `PackageExecutionSnapshot`
- 진료·준비·회복 시간과 방문 묶음 제약
- `planRevisionId`
- `evidenceAuthority`, `evidenceId`, `evidenceHash`
- BOM mapping type과 `BLOCKING` edge
- `OperationalException` 상태와 원 예약 보존 규칙

KDoc은 한 줄 요약, 계약과 실패 조건, 필요한 경우 Kotlin 사용 예제를 포함한다.
README와 OpenAPI에는 고객/관리자 흐름, Gateway 인증 경계, 가예약·확정·변경
동의의 차이를 설명한다.

## 17. 인수 기준

- [ ] 한 방문이 여러 Plan-linked `AppointmentItem`을 포함한다.
- [ ] 항목별 의료진, 장비와 진료 공간을 점유한다.
- [ ] 고객 요청은 병원 승인 전까지 확정되지 않는다.
- [ ] 관리자 직접 확정은 유효 정책과 동의 증빙이 있을 때만 가능하다.
- [ ] 새 proposal 대기 중 기존 확정 예약과 자원 점유가 유지된다.
- [ ] 새 proposal 수락이 자원 점유와 `confirmedProposalId`를 원자적으로 교체한다.
- [ ] 모든 예약 결정이 상품, 정책, 동의 스냅샷을 조회 가능하게 보존한다.
- [ ] 미백치료 5회권이 같은 구성 상품 version의 다섯 회차로 전개된다.
- [ ] 복합 패키지가 구성 상품별 필수·선택·선후행 관계를 보존한다.
- [ ] N-of-M 패키지가 구매 시 선택된 구성 상품만 실행 BOM에 포함한다.
- [ ] 패키지 구성 상품별 진료·준비·회복 시간과 자원 요구가 개별 보존된다.
- [ ] 방문 묶음 제약에 따라 같은 날 조합하거나 별도 방문으로 분리한다.
- [ ] 패키지 전체 단일 시간이 개별 항목의 예약 시간을 덮어쓰지 않는다.
- [ ] 구매는 상품 version에 고정되고 새 version이 기존 구매에 자동 적용되지 않는다.
- [ ] 승인된 상품 version 전환만 동일 Plan의 새 Revision을 즉시 활성화한다.
- [ ] 완료 항목은 구 version에 남고 미진행 항목만 BOM 전환표로 승계된다.
- [ ] 실제 확정 일정 변경에는 상품 전환 동의와 별개의 예약 동의가 필요하다.
- [ ] 고객 거부가 기존 확정 예약을 취소하지 않고 운영 예외를 생성한다.
- [ ] `BLOCKING` 경로만 보류되고 독립·`NON_BLOCKING` 항목은 계속 진행한다.
- [ ] 중복 command와 event가 상태, 자원과 outbox를 중복 생성하지 않는다.
- [ ] 동시 확정과 자원 점유가 충돌 예약을 만들지 않는다.
- [ ] H2, PostgreSQL, MySQL에서 V10과 저장소 의미가 일치한다.
- [ ] 기존 예약 API와 row가 compatibility 경로로 유지된다.
- [ ] 복잡한 업무 속성과 public 계약에 상세한 한국어 KDoc이 있다.
- [ ] OpenAPI가 인증 주체, 가예약, 승인, 동의와 오류 규칙을 설명한다.

## 18. 구현 완료 정의

이 설계의 구현은 다음 조건을 모두 만족해야 완료된다.

1. 인수 기준이 구현 계획의 구체적인 작업과 테스트에 모두 연결된다.
2. 단위, 저장소, API, 보안, 동시성 테스트가 통과한다.
3. H2, PostgreSQL, MySQL 검증을 순차 실행하고 PostgreSQL 결과를 별도로 확인한다.
4. `git diff --check`, Kotlin 진단, 컴파일과 해당 모듈 테스트가 통과한다.
5. KDoc, README, OpenAPI와 schema가 실제 코드와 일치한다.
6. 2-R, 3-R, 구현 후 6-R과 PR 후 7-R의 P0/P1이 모두 0이다.
7. CI, 현재 review thread와 문서 시각 검토가 통과한다.
8. PR merge는 최신 head에 대한 별도의 명시적 승인 후에만 수행한다.

## 19. 후속 공개 문서

승인된 HTML은 현재 저장소의 `docs/superpowers/specs/`에 보존한다. GitHub Pages
workflow나 publication manifest는 이번 범위에서 만들지 않는다. 개발 완료 시점에
별도 Pages 작업이 실제로 병합되어 있고 공개 계약이 저장소에 존재하면, 그
allowlist와 presentation profile을 따라 이 HTML의 공개 항목을 추가한다.

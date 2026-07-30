# 프로필 변경 기반 진행 중 예약 재평가 설계

> 상태: 설계 및 정식 문서 리뷰 승인 완료
>
> 기준일: 2026-07-30
>
> 관련 설계:
> [진료 계획·예약·수용량 관리 설계](./2026-07-26-appointment-plan-and-capacity-design.md) ·
> [Scheduling Policy Foundation 설계](./2026-07-27-scheduling-policy-foundation-design.md) ·
> [방문 예약·확정 약속·상품 버전 전환 설계](./2026-07-29-issue-184-visit-commitment-design.md)

## 1. 문제

CRM이 환자의 예약 관련 프로필을 변경하면 이미 생성된 예약 후보와 임시 선점이
현재 조건에 맞지 않을 수 있다. 그러나 모든 진행 중 예약을 즉시 다시 계산하면
대규모 병원이나 환자가 많은 tenant에서 다음 문제가 생긴다.

- 한 번의 프로필 변경이 무제한 예약 스캔과 solver 실행으로 확장될 수 있다.
- 이미 고객이 동의한 `CONFIRMED` 예약이 설명 없이 바뀔 수 있다.
- `HELD` 자원을 먼저 해제한 뒤 대체 자원 확보에 실패하면 고객 보호 수준이
  불필요하게 낮아진다.
- CRM의 원본 개인정보, 행동 이력, 평가 특징과 점수가 예약서비스의 이벤트,
  작업, 로그와 메트릭으로 복제될 수 있다.
- 같은 환자의 프로필이 짧은 시간에 여러 번 바뀌면 오래된 revision이 최신
  revision보다 늦게 처리될 수 있다.
- 한 대형 병원의 작업 폭주가 다른 병원의 재평가 SLO를 잠식할 수 있다.

이 설계는 프로필 변경을 예약 상태에 안전하게 반영하면서도 고객 동의,
개인정보 최소화, 병원 간 공정성과 운영 복구 가능성을 보존한다.

## 2. 목표

1. 프로필의 실질적 변경이 있을 때 `PROPOSED`와 `HELD` 예약만 비동기로
   재평가한다.
2. `CONFIRMED` 예약은 자동으로 변경하지 않는다.
3. 유효한 기존 hold를 보존하고, 무효한 hold는 대체 자원 확보와 상태 변경을
   원자적으로 처리한다.
4. 환자별 전체 이력이 아니라 재평가 가능한 활성 예약만 범위 조회한다.
5. 같은 환자의 연속 변경은 최신 revision 하나로 병합한다.
6. 플랫폼 기본 시간과 tenant/clinic override를 결합해 병원별 처리 목표를
   운영할 수 있게 한다.
7. CRM의 원본 개인정보와 프로필 근거를 예약서비스에 복제하지 않는다.
8. 재시도, lease, catch-up과 비식별 관측 지표로 장애를 복구한다.
9. 대규모 병원 하나가 다른 병원의 작업을 독점하지 못하게 한다.

## 3. 비목표와 서비스 경계

### 3.1 책임 분리

| 관심사 | 원천 서비스 | 예약서비스의 책임 |
|---|---|---|
| 원본 개인정보와 연락처 | CRM | 소유·저장하지 않음 |
| 행동 이력과 프로필 특징 | CRM | 소유·설명·정정하지 않음 |
| 특징 산출 방식과 점수 | CRM | 수신·로그·영속화하지 않음 |
| 프로필 변경의 정정·이의제기 | CRM | CRM의 결과 event만 소비 |
| 예약에 필요한 최소 평가 결과 | CRM | 처리 시점에 제한적으로 조회하고 영속 본문은 남기지 않음 |
| 예약 상태와 자원 점유 | 예약서비스 | 소유 |
| proposal 생성과 supersede | 예약서비스 | 소유 |
| hold 유지·교체·해제 | 예약서비스 | 소유 |
| 확정 예약 변경 동의 | 예약서비스 | 별도 proposal과 명시적 고객 동의로 처리 |

예약서비스가 CRM의 “객관적 특징”을 공유받아 별도 고객 프로필을 구축할 이유는
없다. 예약서비스는 예약 계산에 필요한 최소 결과만 사용하며, 그 결과의 원인,
설명과 정정 절차는 CRM이 소유한다.

### 3.2 제외 범위

다음 항목은 이번 설계에서 제외한다.

- CRM 프로필 모델, 특징 추출, 점수 계산과 설명 화면
- 고객 세그먼트와 마케팅 자동화
- `CONFIRMED` 예약의 자동 변경 또는 자동 취소
- 고객 동의가 필요한 확정 예약 변경 workflow의 재설계
- 전체 환자나 전체 예약을 대상으로 하는 일괄 backfill
- CRM 원본 payload의 예약서비스 보관
- 프로필을 의료적으로 필요한 진료 거부의 hard constraint로 사용하는 기능

## 4. 핵심 결정

### 4.1 재평가 범위는 `PROPOSED`와 `HELD`다

상태별 계약은 다음과 같다.

| 현재 상태 | 프로필 변경 시 처리 |
|---|---|
| `PROPOSED` | 최신 평가 결과와 정책으로 새 proposal을 계산하고 기존 proposal을 supersede할 수 있음 |
| `HELD` | 기존 hold의 유효성을 먼저 평가하고, 필요할 때만 대체 |
| `CONFIRMED` | 자동 재평가와 변경 금지 |
| `IN_PROGRESS`, `COMPLETED`, `CANCELLED`, `EXPIRED` | 처리 대상 아님 |

`CONFIRMED` 예약을 바꾸려면 기존 확정 변경 계약에 따라 새 proposal과 고객
동의를 받아야 한다. 프로필 변경 event는 이 경계를 우회할 권한이 없다.

작업이 시작된 뒤 예약이 `CONFIRMED`가 되면 commit 직전 상태와 version을 다시
확인하고 `COMPLETED/SKIPPED_INELIGIBLE`로 종료한다. 예약과 allocation은 변경하지
않는다.

#### 4.1.1 실질 변경은 version이 고정된 allowlist로 판단한다

CRM은 예약 결과에 영향을 줄 수 있는 scheduling assessment 출력만 대상으로
`materialChange`를 계산한다. allowlist는 assessment schema version에 포함하며
양쪽 서비스가 같은 version을 검증한다.

실질 변경에는 후보 시간의 적합성, 허용된 예약 방식, 예약 계산에 필요한 자원
요구와 예약 정책 분류처럼 proposal 또는 hold 결과를 바꿀 수 있는 출력 변경만
포함한다. 연락처, 마케팅 동의, 자유서술, 원본 특징과 점수만 바뀐 경우에는
재평가를 시작하지 않는다.

예약서비스는 변경된 특징 목록이나 원인을 받지 않는다. `materialChange`,
`assessmentRef/hash`와 revision만으로 작업을 만들고, 처리 시점의 제한된
scheduling assessment를 이용해 현재 예약 결과가 달라지는지 다시 확인한다.

### 4.2 `HELD`는 보호 우선으로 처리한다

`HELD` 재평가는 다음 순서를 따른다.

1. 최신 평가 결과와 hold에 고정된 policy snapshot으로 기존 allocation이 여전히
   유효한지 확인한다.
2. 유효하면 기존 hold와 만료시각을 유지한다.
3. 무효하면 기존 hold를 유지한 상태에서 대체 후보를 계산한다.
4. 대체 allocation을 확보할 수 있으면 한 트랜잭션에서 새 allocation 획득,
   commitment/proposal 교체, 기존 allocation 해제와 outbox 기록을 수행한다.
5. 재평가가 정상 완료됐지만 대체 allocation이 없으면 한 트랜잭션에서 기존
   allocation을 해제하고 `PROPOSED`로 전환한다.

CRM 조회 실패, solver 오류, DB 오류, timeout이나 CAS 충돌은 “대체 후보 없음”과
다르다. 이러한 기술 실패에서는 예약과 기존 hold를 변경하지 않고 작업만
재시도한다.

이 workflow는 scheduling policy 변경의 `FUTURE_ONLY` 계약을 바꾸지 않는다.
policy 변경만으로 기존 hold를 재평가하지 않으며, 프로필 변경이 발생해도 기존
hold의 유효성은 당시 pinned snapshot으로 판단한다. 기존 hold가 무효여서 새
proposal과 allocation을 만들 때만 현재 effective policy를 사용한다.

### 4.3 event 기반 비동기 처리와 bounded catch-up을 결합한다

프로필 변경은 동기 예약 API 안에서 재평가하지 않는다. 정상 경로는 event-triggered
비동기 작업이고, 유실·장애 복구는 주기적 catch-up이 담당한다.

```text
CRM
  └─ PatientSchedulingAssessmentChanged
       └─ event inbox / latest revision coalescing
            └─ clinic-fair dispatcher
                 └─ bounded appointment pages
                      └─ appointment transaction
                           └─ outbox
```

작업의 논리 key는 다음 범위로 고정한다.

```text
(tenantGroupId, clinicId, patientReferenceFingerprint)
```

같은 key의 더 높은 `profileRevision`이 들어오면 아직 실행하지 않은 낮은 revision을
대체한다. 이미 실행 중인 작업은 page 또는 appointment commit 경계에서 최신
revision을 확인하고 오래된 결과를 `STALE`로 폐기한다.

재평가 대상은 다음 조건을 만족하는 예약만 인덱스로 조회한다.

```text
tenantGroupId
+ clinicId
+ patientReferenceFingerprint
+ commitmentStatus IN (PROPOSED, HELD)
```

환자 전체, tenant 전체 또는 예약 전체를 먼저 읽고 애플리케이션에서 필터링하는
구현은 금지한다. 한 작업은 고정된 page 크기와 실행 시간 예산을 가지며 cursor를
checkpoint한다.

### 4.4 처리 목표 시간은 계층형 설정으로 관리한다

플랫폼 기본값은 Spring의 environment-backed 설정으로 제공하고,
`EffectiveSchedulingPolicy`가 tenant와 clinic override를 합성한다.

| 대상 | 플랫폼 기본값 | 허용 범위 |
|---|---:|---:|
| `HELD` 재평가 완료 목표 | 5분 | 1분 이상 15분 이하 |
| `PROPOSED` 재평가 완료 목표 | 30분 | 5분 이상 120분 이하 |

우선순위는 다음과 같다.

```text
platform default → tenant default → clinic override
```

값은 `Duration`으로 검증하고 허용 범위를 벗어난 policy는 활성화하지 않는다.
이 시간은 개별 작업의 강제 timeout이 아니라 event 수신부터 terminal outcome까지의
p95 운영 목표다.

설정 변경은 다음 규칙을 따른다.

- 목표 시간이 짧아지면 아직 시작하지 않은 기존 작업의 due time을 앞당긴다.
- 목표 시간이 길어져도 이미 대기 중인 작업을 늦추지 않는다.
- 길어진 값은 설정 활성화 뒤 생성된 작업부터 적용한다.
- 실행 중 작업은 시작할 때 고정한 effective policy와 target duration을
  감사 근거로 사용하되, 최신 profile revision 여부는 계속 확인한다.

### 4.5 병원별 공정성과 backpressure를 강제한다

dispatcher는 전역 동시성 상한과 clinic별 동시성 상한을 함께 적용한다.
한 clinic에서 선택할 수 있는 작업 수를 cycle마다 제한하고, runnable clinic을
공정하게 순회한다. 동일 tenant 안에서도 특정 clinic의 backlog가 다른 clinic을
독점할 수 없다.

queue가 포화되면 다음 순서로 완화한다.

1. 같은 환자의 revision을 최신 하나로 병합한다.
2. `PROPOSED`보다 `HELD`의 due time을 우선한다.
3. clinic별 concurrency와 전역 concurrency를 넘는 claim을 차단한다.
4. oldest job age와 SLO lateness를 경보한다.
5. ingress 자체를 잃지 않도록 inbox/outbox와 catch-up cursor를 보존한다.

작업 수가 많다는 이유로 worker가 한 트랜잭션에서 모든 예약을 처리하거나
무제한 coroutine을 생성하면 안 된다.

## 5. 개인정보와 신뢰 경계

### 5.1 event 계약

CRM이 발행하는 event의 최소 허용 envelope은 다음과 같다.

```kotlin
data class PatientSchedulingAssessmentChanged(
    val eventId: String,
    val tenantGroupId: Long,
    val clinicId: Long,
    val patientReferenceFingerprint: String,
    val profileRevision: Long,
    val materialChange: Boolean,
    val assessmentRef: String,
    val assessmentHash: String,
    val occurredAt: Instant,
)
```

`profileRevision`은 환자와 clinic 범위에서 단조 증가해야 한다. `materialChange=false`
event는 inbox 멱등성 기록 뒤 재평가 작업을 만들지 않는다.

다음 값은 event에 포함할 수 없다.

- 이름, 연락처, 주소, 주민등록번호와 외부 환자 ID 원문
- 행동 이력과 자유서술
- 원본 특징 또는 특징별 값
- 위험도, 신뢰도와 고객 등급 점수
- 평가 이유와 설명문
- 인증 token, 암호문과 key material

`patientReferenceFingerprint`는 tenant/clinic 범위를 벗어나 join할 수 없는
pseudonymous reference여야 한다. 동일 문자열을 metric label로 사용하지 않는다.

### 5.2 처리 시점 조회

worker는 event payload에 평가 결과를 싣는 대신 처리 시점에 `assessmentRef`와
revision을 사용해 CRM의 제한된 scheduling assessment를 조회한다.

응답은 예약 계산에 필요한 allowlist field만 포함하고 다음 계약을 지킨다.

- tenant, clinic, pseudonymous patient scope가 event와 일치한다.
- 응답 revision과 hash가 event 또는 최신 revision 계약과 일치한다.
- 원본 특징, 점수와 설명을 포함하지 않는다.
- 메모리에서 계산에 사용한 뒤 예약 DB, job row, log와 metric에 본문을 남기지
  않는다.
- 필요하다면 transport와 process memory 보호를 적용하되, 장기 cache를 만들지
  않는다.

예약서비스의 감사 기록은 `profileRevision`, `assessmentRef`, `assessmentHash`,
발행 주체, event ID와 처리 outcome만 저장한다. 이 기록만으로 CRM의 원본
프로필을 복원할 수 없어야 한다.

### 5.3 권한과 scope 검증

event consumer와 assessment client는 신뢰된 service identity를 사용한다.
다음 불일치는 terminal security failure로 격리하고 예약을 변경하지 않는다.

- tenant 또는 clinic 불일치
- 환자 가명 참조 불일치
- revision rollback
- assessment hash 불일치
- 허용되지 않은 field 포함
- 발행 권한 또는 서명 검증 실패

보안 실패 payload를 그대로 로그에 남기지 않는다. bounded reason code와 event ID만
감사한다.

## 6. 작업 모델과 상태

재평가 작업은 최소한 다음 정보를 가진다.

| 필드 | 의미 |
|---|---|
| scope key | tenant, clinic, pseudonymous patient reference |
| `profileRevision` | 처리해야 할 최신 CRM revision |
| `assessmentRef/hash` | 평가 본문이 아닌 무결성 참조 |
| `state` | 작업 상태 |
| `cursor` | 다음 appointment page 위치 |
| `targetDuration` | 현재 작업에 적용되는 effective 목표 |
| `dueAt` | SLO 계산용 목표 시각 |
| `targetPolicyRef/generation` | 목표 시간의 platform/tenant/clinic 근거와 변경 이력 |
| `attempt` | 기술 재시도 횟수 |
| `nextAttemptAt` | 다음 claim 가능 시각 |
| `leaseOwner`, `leaseUntil` | 중복 worker 방지 |
| `lastErrorCode` | bounded 비식별 오류 |
| counts | scanned, kept, replaced, proposed, skipped, failed의 bounded 집계 |

상태는 다음과 같다.

| 상태 | 의미 |
|---|---|
| `PENDING` | 실행 대기 |
| `RUNNING` | 유효 lease를 가진 worker가 처리 중 |
| `RETRY_WAIT` | 기술 실패 후 backoff 대기 |
| `COMPLETED` | 현재 revision의 범위 처리가 끝남 |
| `STALE` | 더 높은 revision이 현재 결과를 대체함 |
| `FAILED` | 재시도 또는 deadline을 소진해 자동 처리 종료 |

appointment별 outcome은 작업 terminal state와 분리한다.

| outcome | 의미 |
|---|---|
| `PROPOSAL_SUPERSEDED` | `PROPOSED`를 최신 proposal로 교체 |
| `HOLD_KEPT` | 기존 hold가 여전히 유효 |
| `HOLD_REPLACED` | 새 allocation으로 원자 교체 |
| `FALLBACK_TO_PROPOSED` | 재평가 완료 후 대체 후보가 없어 hold를 해제하고 제안 상태로 전환 |
| `SKIPPED_INELIGIBLE` | 상태가 바뀌어 더 이상 대상이 아님 |
| `SKIPPED_UNCHANGED` | 평가 결과상 실질 변경이 없음 |

`FAILED`는 예약이나 hold를 자동 해제하는 사유가 아니다. catch-up 또는 운영자
redrive가 같은 revision을 다시 처리할 수 있다.

## 7. 처리 흐름

### 7.1 ingress와 병합

1. event signature, scope와 schema를 검증한다.
2. `eventId`로 inbox 멱등성을 확인한다.
3. `materialChange=false`이면 처리 완료로 기록한다.
4. scope key와 `profileRevision`으로 latest-job row를 upsert한다.
5. 더 낮은 revision의 `PENDING`/`RETRY_WAIT` 작업은 `STALE` 처리한다.
6. 현재 effective target duration으로 `dueAt`을 계산한다.
7. commit과 함께 runnable signal을 발행한다.

### 7.2 worker claim과 평가

1. dispatcher가 clinic별 공정성 한도 안에서 due job을 선택한다.
2. DB time 기준으로 lease를 claim한다.
3. 더 높은 revision이 있는지 확인한다.
4. CRM에서 제한된 scheduling assessment를 조회하고 scope/hash를 검증한다.
5. `PROPOSED`, `HELD` 범위 인덱스로 appointment ID page를 읽는다.
6. 각 appointment를 짧은 독립 트랜잭션으로 처리한다.
7. page cursor와 bounded 집계를 checkpoint하고 lease를 갱신한다.
8. 다음 page가 없고 revision이 여전히 최신이면 `COMPLETED`로 전환한다.

한 환자의 모든 예약을 하나의 장기 트랜잭션으로 묶지 않는다. page 사이 crash는
완료된 appointment outcome과 cursor를 이용해 멱등하게 재개한다.

### 7.3 appointment transaction

각 appointment transaction은 다음 precondition을 다시 검증한다.

- tenant, clinic과 pseudonymous patient scope
- commitment status
- appointment/commitment version
- 현재 allocation version
- 작업의 `profileRevision`
- effective scheduling policy generation

`PROPOSED`는 새 proposal을 append하고 최신 proposal pointer를 CAS로 바꾼다.
`HELD`는 4.2의 보호 우선 순서를 따른다. 성공한 상태 전이와 allocation 변경은
감사 및 outbox event와 한 트랜잭션으로 commit한다.

CAS 충돌은 현재 상태를 다시 읽은 뒤 다음과 같이 처리한다.

- `CONFIRMED` 또는 비대상 상태면 `SKIPPED_INELIGIBLE`
- 더 높은 profile revision이면 현재 작업을 `STALE`
- 같은 revision의 동등한 결과가 이미 있으면 idempotent success
- 여전히 대상이지만 version만 변했으면 bounded retry

## 8. 실패와 복구

### 8.1 기술 실패

CRM timeout, 일시적 인증 infrastructure 장애, solver 오류, DB 오류와 lease
경쟁은 예약을 변경하지 않는다. 작업은 `RETRY_WAIT`으로 이동하고 exponential
backoff와 jitter를 적용한다.

재시도 정책은 구현 계획에서 최대 횟수, 최대 elapsed time과 오류별 retryability를
고정한다. `targetDuration`은 운영 SLO이고 재시도 중단 기준과 동일하지 않다.
재시도 또는 처리 deadline을 소진하면 `FAILED`로 전환하고 경보한다.

### 8.2 lease와 worker crash

- claim과 lease 갱신에는 DB time을 사용한다.
- lease owner가 아닌 worker의 checkpoint와 terminal update는 거부한다.
- lease가 만료된 `RUNNING` 작업은 catch-up이 다시 claim할 수 있게 한다.
- 오래된 worker의 늦은 commit은 appointment CAS와 job lease fencing으로 막는다.
- 동일 appointment outcome은 revision과 idempotency key로 중복 commit되지 않는다.

### 8.3 catch-up

catch-up은 전체 환자를 스캔하지 않고 다음 bounded 범위만 조회한다.

- 만료된 `RUNNING` lease
- `nextAttemptAt`이 지난 `RETRY_WAIT`
- SLO를 넘긴 `PENDING`
- bounded recovery policy가 허용하거나 운영자가 명시한 `FAILED` redrive
- inbox에는 있으나 latest-job 연결이 없는 복구 대상

각 조회는 상태, due/lease 시각과 clinic scope를 지원하는 인덱스를 사용한다.
catch-up도 정상 dispatcher와 동일한 clinic별 공정성 및 concurrency 한도를
통과해야 한다.

`FAILED` row를 다시 `PENDING`으로 되돌리지는 않는다. redrive는 원래 job ID와
revision을 참조하는 새 attempt를 만들며, 자동 redrive 횟수와 cooldown을 bounded
recovery policy로 제한한다. 이를 소진한 뒤에는 운영자 명시 실행만 허용한다.

## 9. 데이터와 인덱스 원칙

구현은 기존 appointment/commitment 모델을 확장하되 다음 논리 제약을 만족해야
한다.

- scope key당 runnable latest revision은 하나다.
- event ID 소비는 멱등하다.
- job revision은 감소하지 않는다.
- cursor는 같은 revision 안에서만 전진한다.
- job과 appointment outcome에는 raw assessment 본문이 없다.
- 상태 전이와 outbox 기록은 원자적이다.
- 활성 lease 갱신과 terminal commit은 lease owner로 fencing한다.

최소 인덱스 목적은 다음과 같다.

| 조회 | 인덱스 선두 조건 |
|---|---|
| 환자의 재평가 대상 예약 | tenant, clinic, patient fingerprint, commitment status |
| runnable job | state, next attempt/due time, clinic |
| lease 복구 | state, lease until |
| 최신 revision 병합 | tenant, clinic, patient fingerprint, profile revision |
| inbox 멱등성 | event ID 또는 발행 주체별 event ID |

구체적인 table과 index DDL은 구현 계획에서 H2, PostgreSQL, MySQL의 동등한
업무 의미를 확인한 뒤 고정한다.

## 10. 관측 가능성과 운영

metric label에는 patient, appointment, event, assessment reference를 넣지 않는다.
tenant/clinic과 bounded state/reason code만 허용한다.

필수 지표는 다음과 같다.

- queue depth와 runnable clinic 수
- 상태별 oldest job age
- target duration 대비 lateness
- claim, lease expiry와 stale revision 수
- retry, failed와 redrive 수
- appointment outcome별 처리 수
- page 처리량과 appointment 처리 latency
- clinic별 concurrency 사용량과 throttling 수
- CRM assessment 조회 latency/오류율
- CAS conflict와 idempotent replay 수

필수 경보는 다음과 같다.

- `HELD` 또는 `PROPOSED` SLO error budget 소진
- oldest job age가 effective target을 지속적으로 초과
- `FAILED` 증가 또는 같은 오류의 연속 재시도
- lease expiry 급증
- 특정 clinic의 backlog가 다른 clinic의 lateness를 유발
- scope, hash 또는 발행 주체 검증 실패
- catch-up cursor 정체

로그는 job ID, event ID, profile revision, tenant/clinic, 상태와 bounded reason
code만 구조화한다. payload dump, assessment 본문과 patient fingerprint 출력은
금지한다.

운영 redrive는 tenant/clinic, revision 범위, reason과 실행자를 감사한다. redrive는
예약을 직접 수정하지 않고 같은 worker 계약을 다시 실행한다.

## 11. 테스트와 수용 기준

### 11.1 도메인 단위 테스트

- `PROPOSED`, `HELD`만 eligible이고 `CONFIRMED`는 항상 제외된다.
- `materialChange` allowlist가 비실질 변경을 걸러낸다.
- revision 비교와 latest-wins 병합이 중복·역순 event에서 같은 결과로 수렴한다.
- 플랫폼, tenant, clinic 설정 우선순위와 허용 범위를 검증한다.
- 짧아진 목표는 queued job을 앞당기고, 길어진 목표는 기존 job을 늦추지 않는다.
- job과 appointment 상태 전이의 허용 행렬을 검증한다.

### 11.2 저장소와 동시성 통합 테스트

- 유효한 `HELD`는 기존 allocation과 만료시각을 유지한다.
- 무효한 `HELD`는 새 allocation으로 원자 교체된다.
- 재평가가 정상 완료됐지만 대체 후보가 없을 때 기존 allocation 해제와
  `PROPOSED` 전환이 한 트랜잭션에서 수행된다.
- CRM, solver, DB와 CAS 기술 실패에서는 예약과 allocation이 변경되지 않는다.
- 처리 중 `CONFIRMED`가 된 예약은 `SKIPPED_INELIGIBLE`로 종료된다.
- 중복 event, 역순 revision, lease 만료, worker crash와 outbox replay가 중복
  상태 전이를 만들지 않는다.
- stale lease owner와 낮은 profile revision의 commit을 거부한다.
- H2, PostgreSQL, MySQL에서 같은 업무 결과를 검증한다.

실제 DB 통합 테스트는 저장소 규칙에 따라 singleton container launcher를 사용하고
순차 실행한다.

### 11.3 개인정보와 보안 테스트

schema, 직렬화 결과, job row, outbox, audit, log와 metric을 검사해 다음 값이
없음을 증명한다.

- 원본 특징과 점수
- 평가 설명과 자유서술
- 이름, 연락처와 외부 patient ID 원문
- patient fingerprint metric label
- 인증 token, 암호문과 key material

tenant/clinic/patient scope, revision, hash와 발행 주체 불일치를 각각 거부하며
오류 응답과 로그에도 금지 필드가 노출되지 않는지 검증한다.

### 11.4 부하와 공정성 검증

부하는 전체 환자 수가 아니라 재평가 대상인 활성 예약 수로 정의한다.

최소 fixture는 다음과 같다.

- 단일 clinic에서 `PROPOSED`/`HELD` 활성 예약 10,000건의 동시 변경 burst
- 최소 100개 clinic의 동시 backlog
- 같은 환자의 빠른 연속 revision과 out-of-order delivery
- lease expiry, CRM 일시 장애와 worker 재시작이 섞인 catch-up
- 처리 중 confirm 경쟁

수용 기준은 다음과 같다.

| 항목 | 기준 |
|---|---|
| `HELD` 완료 지연 | 기본 설정에서 p95 5분 이하 |
| `PROPOSED` 완료 지연 | 기본 설정에서 p95 30분 이하 |
| `CONFIRMED` 자동 변경 | 0건 |
| 중복 appointment commit | 0건 |
| cross-tenant/clinic write | 0건 |
| stale revision commit | 0건 |
| 개인정보 금지 필드 유출 | 0건 |

추가로 다음을 증명한다.

- PostgreSQL/MySQL `EXPLAIN`에서 환자와 상태로 범위가 제한된 인덱스를 사용한다.
- 전체 환자 또는 전체 appointment scan이 없다.
- page size, worker memory와 queue growth가 설정 상한 안에서 bounded다.
- 한 noisy clinic 때문에 다른 clinic의 oldest job age가 그 clinic의 effective
  target을 위반하지 않는다.
- catch-up이 전체 환자 스캔 없이 만료 lease와 재시도 대상을 회복한다.

SLO는 p95와 error budget으로 운영한다. 모든 개별 작업이 목표 시간 안에 끝난다는
절대 보장은 하지 않지만, 정확성·개인정보 위반 허용치는 항상 0이다.

## 12. 배포와 rollback

도입 순서는 다음과 같다.

1. schema와 인덱스를 추가하되 consumer와 worker를 비활성 상태로 배포한다.
2. privacy/schema contract test와 bounded query `EXPLAIN`을 통과한다.
3. 한 내부 clinic에서 event 소비와 dry-run outcome 집계만 활성화한다.
4. 예약 mutation 없이 revision 병합, 공정성과 SLO를 관찰한다.
5. `PROPOSED` mutation을 먼저 활성화한다.
6. `HELD` 유지와 교체를 별도 feature flag로 활성화한다.
7. clinic 범위를 점진적으로 확대한다.
8. catch-up과 redrive runbook을 검증한 뒤 일반 운영으로 전환한다.

rollback은 consumer claim과 새 mutation을 중지한다. 이미 완료한 정상 appointment
transaction을 일괄 되돌리지 않으며, 대기 job은 보존한다. `FAILED` 또는 중단된
작업 때문에 기존 hold를 해제하지 않는다. 재개 시 최신 revision과 현재 예약
상태를 다시 검증한다.

## 13. 시각화와 문서 계약

이 업무 흐름의 기준 문서는 이 Markdown 설계다. 독자가 상태별 처리, 개인정보
경계, 장애 복구와 병원 간 공정성을 탐색할 수 있도록 최종 시각 자료는 HTML과
PNG로 제공한다.

### 13.1 최종 산출물

- 영어 HTML companion: `2026-07-30-profile-change-reservation-reevaluation.html`
- 한국어 HTML companion:
  `2026-07-30-profile-change-reservation-reevaluation.ko.html`
- PNG fallback:
  - `2026-07-30-profile-change-reservation-reevaluation.en.light.png`
  - `2026-07-30-profile-change-reservation-reevaluation.en.dark.png`
  - `2026-07-30-profile-change-reservation-reevaluation.ko.light.png`
  - `2026-07-30-profile-change-reservation-reevaluation.ko.dark.png`

두 HTML은 source-equivalent하며 각각 `auto`, `light`, `dark` theme을 지원한다.
PNG는 같은 고정 viewport에서 결정적으로 렌더링하고 생성 명령을 두 번 실행해
dimension과 hash가 같은지 확인한다.

### 13.2 표현 범위

최종 HTML은 다음 세 관점을 포함한다.

1. CRM → inbox → latest revision job → clinic-fair worker → appointment
   transaction의 업무 흐름
2. `PROPOSED`, `HELD`, `CONFIRMED` 상태별 결과
3. 기술 실패, retry/lease/catch-up과 개인정보 경계

sequence, class, ERD처럼 정적인 구조·관계 자료가 추가로 필요하면 SVG와 PNG를
사용한다. 이 업무 흐름 자체를 위해 hand-maintained SVG를 별도의 기준 문서로
관리하지 않는다.

향후 README에 넣을 때는 `<picture>`로 light/dark PNG를 선택하고 이미지를 같은
언어의 HTML companion으로 연결한다. `README.md`와 `README.ko.md`의 설명,
링크와 정보 범위를 source-equivalent하게 유지한다.

brainstorming 단계의 임시 preview 파일은 승인 보조 자료일 뿐 최종 문서 계약의
산출물이 아니다.

## 14. 검토한 대안과 기각 사유

### A. 새 예약부터만 적용

기존 `PROPOSED`와 `HELD`가 최신 조건과 어긋난 채 남을 수 있어 기각한다. 처리
비용은 가장 낮지만 임시 선점과 후보 품질이 장시간 stale해진다.

### B. `PROPOSED`와 `HELD`만 재평가

채택한다. 고객이 아직 확정하지 않은 상태는 최신 조건으로 정리하되,
`CONFIRMED`의 고객 동의와 예약 안정성을 보존한다.

### C. `CONFIRMED`까지 모두 자동 재평가

기각한다. 확정 약속을 프로필 변경만으로 바꾸면 고객 동의, 운영 예측 가능성과
감사 가능성을 훼손한다. 대규모 환자 tenant에서는 mutation fan-out과 상담 부담도
가장 크다.

### D. CRM의 원본 특징과 점수를 event에 포함

기각한다. 예약서비스에 개인정보와 민감한 평가 근거가 복제되고, 설명·정정 책임이
불분명해진다. 최소 참조와 처리 시점의 제한된 scheduling assessment만 사용한다.

### E. event마다 환자의 모든 예약을 즉시 동기 재평가

기각한다. 요청 latency와 장애 범위를 키우고 병원별 공정성, revision 병합과
backpressure를 구현하기 어렵다. 비동기 bounded job과 catch-up을 사용한다.

### F. 기술 실패 시 기존 hold를 선제 해제

기각한다. 인프라 장애를 업무 판단으로 오인해 고객의 유효한 자원 선점을 잃게
한다. 기존 hold 해제는 재평가를 정상 완료한 결과 대체 후보가 없는 경우에만
허용한다.

## 15. 구현 계획 진입 조건

구현 계획은 다음 사항을 구체적인 file, migration, API, test와 명령으로 고정해야
한다.

- 현재 `AppointmentCommitment`, proposal, allocation 모델의 재사용 지점
- event inbox, latest revision job, outcome과 outbox의 정확한 schema
- platform property와 scheduling policy override key
- H2, PostgreSQL, MySQL migration 및 index
- clinic-fair dispatcher와 concurrency/backpressure 설정
- CRM assessment client의 allowlist schema와 보안 검증
- appointment transaction의 CAS와 lock 순서
- retry/lease/catch-up/redrive runbook
- privacy contract test와 log/metric 검사
- 10,000건·100 clinic 부하 fixture와 SLO 측정 명령
- 한국어·영어, light/dark HTML+PNG 생성 및 README 연결

이 설계 문서가 사용자 리뷰를 통과한 뒤 구현 계획을 작성한다.

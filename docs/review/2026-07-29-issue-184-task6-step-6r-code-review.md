# Issue #184 Task 6 Step 6-R 코드 리뷰

## 검토 범위

- `appointment-core`의 commitment, proposal, consent, appointment item,
  resource allocation, command idempotency table·repository·record
- `appointment-api`의 commitment command 계약과 application service
- H2, PostgreSQL, MySQL Flyway V10 정합성
- 고객 요청, 관리자 승인, 직접 확정, 변경 제안, 수락·거부·만료,
  idempotency replay, rollback, PostgreSQL 동시성 테스트
- 기준: `bluetape-kotlin-patterns`, Exposed caller-owned transaction,
  JUnit 5 Given/When/Then, singleton Testcontainers, 한국어 KDoc

## 최종 판정

| Tier | 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---|---:|---:|---:|---:|---|
| 1 | 본 세션 통합·업무규칙 | 0 | 0 | 0 | 0 | PASS |
| 2 | 사용자·호출자 | 0 | 0 | 0 | 0 | PASS |
| 3 | 개발자·API | 0 | 0 | 0 | 0 | PASS |
| 4 | 성능 | 0 | 0 | 3 | 2 | PASS |
| 5 | 안정성·동시성 | 0 | 0 | 0 | 0 | PASS |
| 6 | 보안 | 0 | 0 | 2 | 1 | PASS |
| 7 | 운영·SRE | 0 | 0 | 2 | 3 | PASS |

최종 차단 집계는 `P0=0`, `P1=0`이다. P2/P3은 현재 내부 command 경계의
정확성을 깨지 않으며 아래 후속 Task의 명시적 검증 항목으로 이관한다.

## 리뷰 중 발견하고 닫은 결함

### Kotlin 불변식과 저장 경계

- 생성자에서 검증하는 command·domain 값을 `data class.copy()`로 우회할 수 있던
  계약을 일반 불변 class로 바꾸고, bluetape4k validation helper의 반환값을 실제
  속성에 저장했다.
- appointment item 저장 전에 tenant, clinic, patient fingerprint, Plan revision,
  treatment snapshot을 함께 검증한다. 잘못된 item이나 다른 proposal의
  `appointmentItemKey`는 allocation 전에 안정적인
  `APPOINTMENT_ITEM_INVALID`로 거부하고 command transaction 전체를 rollback한다.
- 직접 확정은 정책이 요구한 정확한 `termsHash`와 허용된 `evidenceType`을 검증하고,
  검증한 값을 세 dialect schema와 consent read model에 보존한다.
- capacity 요청은 첫 항목 하나가 아니라 모든 `CAPACITY_BUCKET` 요청을 검증한다.
  같은 resource key에서 전담 요청이 먼저 오는 혼합 입력도 회귀 테스트로 고정했다.

### 동일 proposal 종결 경쟁

초기 안정성 검토에서는 확정 예약의 동일 변경 proposal에 수락과 거부 또는 만료가
동시에 도착하면 두 결과가 함께 commit될 수 있는 P1을 발견했다. 원인은 거부와
확정 후 변경 만료가 commitment version을 소비하지 않고 read-time 검사만 사용한
것이었다.

수정 후 모든 종결 command는 다음 순서를 공유한다.

```text
proposal SELECT FOR UPDATE
→ commitment 재조회
→ expected version 검증
→ consent 또는 만료 표식 기록
→ commitment version CAS
→ 감사·outbox·idempotency 결과
```

수락은 새 확정 포인터를 쓰면서 version을 증가시키고, 거부·만료는 기존 확정
포인터를 보존하면서 같은 version을 소비한다. 따라서 한 command만 성공하며 loser의
consent, 만료 표식, allocation, outbox, idempotency claim은 transaction rollback된다.

### 만료 응답 스냅숏

초기 안정성 검토에서는 DB의 `expired_at`을 갱신한 뒤 갱신 전 proposal 객체를
응답과 idempotency 결과에 저장하는 P2도 발견했다. 만료 update 뒤 proposal을
재조회하도록 수정해 최초 응답과 replay가 모두 DB에 확정된 `expiredAt`을
반환한다.

## 후속 Task 경계

### Task 7 — Gateway actor 기반 API

- HTTP body에서 `CommitmentCommandContext`,
  `DirectConfirmationPolicyDecision`, `ConfirmedAppointmentProjectionTarget`을
  신뢰하지 않는다.
- Gateway 인증 principal과 서버측 policy·inventory 조회로 command를 구성하고,
  감사·동의 식별자는 opaque reference만 받는다.
- 중복 consent evidence는 DB 예외가 아니라 안정적인 API 오류로 변환한다.

### Task 9 — 운영·관측성

- retry exhaustion, resource conflict, idempotency conflict, proposal expiry,
  outbox backlog에 metric을 제공한다.
- 로그의 commitment/proposal/correlation 식별자를 metric label로 승격하지 않고
  privacy-safe·저카디널리티 규칙을 문서화한다.
- consent evidence의 전역 유일성 계약 또는 tenant scope를 확정한다.

### Task 10 — 성능·장기 회귀

- 패키지 item 다건 insert의 round-trip, consent subject 조회 index,
  idempotency replay 조회 수를 실제 PostgreSQL 데이터 규모에서 측정한다.
- allocation overlap의 메모리 스캔과 중복 reference 검증 비용을 최대 BOM
  상한에서 확인한다.

## 검증 증거

- `AppointmentCommitmentCommandServiceTest`: 22개, 실패 0, 오류 0
- `VisitCommitmentConcurrencyTest`: PostgreSQL 5개, 실패 0, 오류 0
- `appointment-core:test`: 444개, 실패 0, 오류 0, skipped 0
- H2, PostgreSQL, MySQL Flyway migration: 각 1개, 실패 0
- `appointment-api:build --no-build-cache --rerun-tasks`: 323개,
  실패 0, 오류 0, 기존 skipped 2
- 변경 Kotlin 전체 `ktlint`: 위반 0
- `git diff --check`: 위반 0
- production 금지 패턴: `!!`, `println`, broad `runCatching`,
  `synchronized` 추가 0

전체 API build의 첫 실행에서는 Task 6과 무관한 `EquipmentControllerTest`가
일시적으로 403을 반환했다. 같은 class 단독 실행과 전체 API test 재실행에서
재현되지 않았고, 최종 전체 build가 통과했다. Task 6 회귀로 분류하지 않되 기존
Spring 통합 테스트 격리의 비결정성은 후속 운영 품질 점검에서 관찰한다.

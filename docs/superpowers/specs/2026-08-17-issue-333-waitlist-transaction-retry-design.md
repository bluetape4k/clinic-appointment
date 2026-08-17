# Issue #333 waitlist contention 재시도 경계 설계

## 문제

`WaitlistDeliveryRepository.claim`은 caller-owned Exposed transaction 안에서
`withContentionRetry`를 실행한다. PostgreSQL의 `SET LOCAL lock_timeout` 또는
deadlock/serialization 오류가 transaction을 abort하면 같은 connection의 transaction은
rollback 전까지 추가 SQL을 실행할 수 없다. 현재 retry loop는 sleep 뒤 동일 block을 다시
실행하므로, 실패한 transaction을 재사용하는 경계가 생긴다.

현재 delivery 계약은 다음과 같다.

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt:161-195`
  의 `claim`이 `TransactionManager.current()`를 사용하는 block 안에서 retry한다.
- 같은 파일 `:482-516`의 `withContentionRetry`는 retryable `ExposedSQLException` 또는
  `SQLException`을 잡고 같은 callback을 반복한다.
- PostgreSQL 전략은 `SET LOCAL lock_timeout = '2s'`와 `FOR UPDATE`를 사용한다.
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/service/waitlist/WaitlistDeliveryService.kt`
  는 claim, offer/hold, notification enqueue, terminal fence를 caller-owned 한 transaction에
  묶도록 정의한다.
- 테스트는 이미 `Containers.Postgres = PostgreSQLServer.Launcher.postgres`와
  `TestDB.POSTGRESQL`을 사용하지만, abort된 PostgreSQL transaction 뒤 fresh retry를 검증하지
  않는다.

## 제약과 범위

- 기존 `WaitlistDeliveryService.process(claim)` caller-owned transaction 계약을 유지한다.
- retry가 claim만 감싸서 notification enqueue와 terminal fence를 분리하지 않는다.
- `claim` public method는 한 transaction에서 한 번만 시도한다.
- retry owner는 caller가 transaction 밖에서 `withContentionRetry`를 호출하고, callback 안에서
  `inTopLevelTransaction(database) { claim + process + enqueue + terminal fence }` 전체를
  재실행한다.
- PostgreSQL lock timeout SQLSTATE `55P03`은 PostgreSQL 전략에서만 retryable로 분류한다.
  기존 `40001`, `40P01`, MySQL error code `1205` 계약은 유지한다.
- 새 모듈·dependency·raw `GenericContainer`·`@Testcontainers`는 추가하지 않는다.
- 실제 production 배포·canary 증거는 이 예제 issue의 범위가 아니다. acceptance는
  bluetape4k PostgreSQL singleton을 사용한 실제 PostgreSQL 시뮬레이션으로 판정한다.

## 대안과 선택

### 대안 1 — caller-owned retry 경계를 유지한다 (선택)

`claim`에서 내부 retry를 제거하고, `withContentionRetry`를 transaction 밖에서 사용하는
계약을 KDoc과 회귀 테스트로 고정한다. 각 callback attempt는 `inTopLevelTransaction`으로
새 top-level Exposed transaction을 열고 전체 delivery 단위를 수행한다. 이 방식은 기존
public API와 notification 원자성을 유지하면서 변경 범위를 repository·service 계약 문서와
테스트로 제한한다.

### 대안 2 — repository에 `claimWithContentionRetry(Database, ...)`를 추가한다

repository가 fresh transaction을 만들 수 있지만 claim만 별도 transaction으로 감싸기 쉽다.
그 결과 process/enqueue/terminal fence와 transaction이 분리되어 현재 원자성 계약을 깨뜨릴
수 있으므로 거부한다.

### 대안 3 — service에 새 `processWithContentionRetry` public 진입점을 추가한다

전체 orchestration을 한 API에서 감쌀 수 있지만 현재 production wiring이 없고 job 입력,
database, owner 계약을 새 public API로 확장한다. 이번 P2 결함의 최소 수정 범위를 넘으므로
거부한다.

## 선택한 동작 흐름

```text
caller (no transaction)
  -> repository.withContentionRetry {
       inTopLevelTransaction(postgres) {
         repository.claim(...)       // exactly one attempt
         deliveryService.process(...) // offer/hold + enqueue + terminal fence
       }
     }
```

첫 attempt에서 PostgreSQL transaction이 `55P03`, `40001`, 또는 `40P01`로 abort되면
`inTopLevelTransaction`이 rollback과 connection 정리를 수행한다. 바깥 retry coordinator는
정책에 따라 sleep한 뒤 다음 callback을 실행한다. 다음 callback은 새 top-level transaction을
사용하므로 이전 abort 상태를 재사용하지 않는다. retry가 소진되면 기존 `WaitlistContention`
계약으로 원인을 보존한다. retry 대상이 아닌 SQLSTATE는 즉시 같은 exception instance를
전파한다.

## 실패 모드와 대응

1. **PostgreSQL lock timeout (`55P03`)**: blocker가 row lock을 보유한 동안 첫 attempt가
   abort된다. blocker 해제 뒤 다음 fresh transaction이 claim을 성공시키는 회귀 테스트로
   증명한다.
2. **Serializable conflict (`40001`)**: 두 serializable transaction이 같은 snapshot을
   읽고 서로 다른 row를 갱신하는 write-skew를 만들어 한 attempt가 abort된다. retry callback의
   두 번째 fresh transaction이 성공하는지 검증한다.
3. **Non-retryable SQLSTATE**: `42000` 같은 오류는 sleep·재시도 없이 원인 instance를
   호출자에게 전파한다.
4. **Notification enqueue failure**: retry 경계와 무관하게 `process` 내부에서 예외가
   전파되고 offer/hold/vacancy terminal write가 caller transaction과 함께 rollback되는
   기존 테스트를 유지한다.
5. **Retry exhaustion/interruption**: 기존 `WaitlistContention` wrapping과 interrupt flag
   복구 테스트를 유지한다.

## 호환성 및 문서

- `WaitlistDeliveryRepository.claim`와 `WaitlistDeliveryService.process`의 인자·반환형은
  변경하지 않는다.
- `withContentionRetry`의 의미를 “transaction 밖에서 전체 작업 callback에 적용하는 retry
  coordinator”로 KDoc에 명시한다.
- PostgreSQL strategy의 SQLSTATE 판정만 확장하며 H2/MySQL 분기와 기존 exception 계약은
  유지한다.
- README나 공개 모듈 등록은 변경하지 않는다. Issue/PR/lesson은 repository-local Korean
  artifact 정책에 따라 작성한다.

## 수용 기준

1. `claim`이 내부 retry loop 없이 한 attempt만 실행한다.
2. retry callback의 각 attempt가 fresh top-level Exposed transaction/connection 경계를
   사용한다.
3. caller-owned transaction 안의 `process`와 notification enqueue 원자성이 유지된다.
4. 실제 `PostgreSQLServer.Launcher.postgres`에서 lock timeout abort 후 다음 attempt가
   성공한다.
5. 실제 PostgreSQL serializable contention에서 `40001` abort 후 다음 attempt가 성공한다.
6. non-retryable SQLSTATE는 즉시 원인 instance를 전파하고, exhaustion/interruption 기존
   테스트가 계속 통과한다.
7. `:appointment-core:test`와 targeted PostgreSQL test가 fresh no-build-cache 실행으로
   통과한다.

## DoD

- 변경 파일이 `appointment-core` waitlist repository/service와 해당 테스트, Korean
  superpowers spec/plan/lesson으로 제한된다.
- 새 dependency/module 없이 기존 bluetape4k assertions와 PostgreSQL singleton을 사용한다.
- `git diff --check`, targeted compile/test, PostgreSQL Testcontainers test, full
  `:appointment-core:test`가 PASS한다.
- review에서 P0/P1이 없고, merge 전에는 exact PR head와 CI를 다시 확인한다.

Constraint: caller-owned transaction과 notification enqueue 원자성을 같은 transaction으로 유지한다.
Rejected: claim-only repository retry와 새 public service orchestration API | 각각 원자성 분리 또는 불필요한 public surface 확장을 유발한다.
Confidence: high
Scope-risk: moderate
Directive: retry coordinator는 transaction 밖에서 사용하고 callback 안에서 fresh top-level transaction을 열어야 한다.
Tested: 현재 source/test/helper와 Exposed 1.4.0 `inTopLevelTransaction` semantics를 확인했다.
Not-tested: 설계 승인 전 실제 PostgreSQL contention 실행은 아직 하지 않았다.

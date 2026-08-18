# Issue #333 waitlist transaction retry 교훈

## 상황

`WaitlistDeliveryRepository.claim`이 `withContentionRetry`를 내부에서 호출해 같은
Exposed transaction 안에서 재시도하고 있었다. PostgreSQL은 lock timeout이나
serializable 충돌 뒤 현재 transaction을 abort하므로, 다음 attempt가 같은 경계를
재사용하면 원래 contention보다 `25P02 current transaction is aborted`가 먼저
노출된다.

## 재현된 실패

실제 `PostgreSQLServer.Launcher.postgres` singleton을 사용하는 두 회귀 테스트를
추가했다.

- `55P03`: blocker가 `FOR UPDATE` lock을 보유한 동안 첫 claim이 lock timeout으로
  abort되고, 기존 구현은 내부 retry 중 `SET LOCAL lock_timeout`에서 `25P02`를
  발생시켰다.
- `40001`: 두 `SERIALIZABLE` transaction이 같은 두 row snapshot을 읽은 뒤 충돌하는
  update를 수행했고, 기존 구현은 `claim` 내부 retry 경계에서 동일하게 aborted
  transaction을 재사용했다.

두 테스트 모두 callback별 transaction identity를 기록하고, bounded latch/executor
정리와 최종 lease 상태를 확인한다. 이 RED 증거는 production 수정 전에 별도 커밋으로
보존했다.

## 해결책

1. `claim`은 현재 transaction에서 단일 attempt만 수행한다. lock timeout 설정과
   cleanup은 기존 dialect strategy 계약을 유지한다.
2. 호출자는 `withContentionRetry`를 transaction 경계 밖에서 호출한다.
3. 각 callback은 fresh top-level Exposed transaction 안에서 claim부터
   `WaitlistDeliveryService.process`, notification enqueue, terminal fence까지를
   함께 실행한다. 그러면 delivery 원자성과 retry 경계가 동시에 유지된다.
4. SQLSTATE `55P03`은 PostgreSQL strategy에서만 retryable로 분류한다. 기존
   `40001`, `40P01` SQLSTATE와 non-retryable exception identity 계약은 유지한다.

## 검증 결과와 경계

- repository와 실제 PostgreSQL contention targeted run: **15/15 통과**
- `WaitlistDeliveryServiceTest`: **4/4 통과**; outbox 실패 rollback 계약 유지
- `./gradlew :appointment-core:test --no-build-cache --no-daemon --console=plain`:
  **552 tests 통과**
- Colima running, Docker context `default`, Docker socket override와
  `PostgreSQLServer.Launcher.postgres` singleton을 확인한 뒤 PostgreSQL 테스트를
  순차 실행했다.

이 증거는 예제 서비스의 실제 PostgreSQL 시뮬레이션 계약을 증명한다. production
배포·canary·SLO·외부 운영 endpoint 증거는 사용자가 정한 범위 밖이므로 이 이슈의
완료 조건이 아니며 N/A로 둔다.

## 재발 방지 규칙

1. Exposed transaction을 abort할 수 있는 contention retry는 repository 내부
   transaction과 분리하고, callback마다 fresh top-level transaction을 연다.
2. PostgreSQL lock timeout은 실제 `55P03`과 aborted transaction 후속 동작을
   Testcontainers에서 검증한다. H2 단독 테스트를 PostgreSQL 증거로 승격하지 않는다.
3. retryable SQLSTATE를 전역으로 넓히지 말고 dialect strategy로 제한한다.
4. concurrency 테스트는 bounded latch, executor shutdown, transaction identity,
   최종 상태 assertion을 함께 유지한다.
5. 운영 배포 증거가 없는 예제 이슈에서는 Testcontainers 시뮬레이션과 코드 계약을
   명확히 기록하되 production readiness를 완료 조건으로 되돌리지 않는다.

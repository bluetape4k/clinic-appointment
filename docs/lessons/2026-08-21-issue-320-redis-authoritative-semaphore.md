# Issue #320: Redis 권위 notification outbox 동시성

## 결정

`NotificationOutboxDispatcher`의 프로세스 로컬 global·clinic `Semaphore`를
Lettuce expirable semaphore 기반 coordinator로 대체했다. 기본 모드는 `REDIS`이며,
단일 프로세스 테스트·개발에서만 `LOCAL`을 명시한다. Redis connection이 없거나
capacity 계약이 다르면 자동으로 로컬 경로로 낮추지 않고 시작 또는 admission을
실패시킨다.

## 정합성 경계

- Redis permit은 다중 인스턴스 admission 상한만 조정한다.
- 데이터베이스 claim·lease·fencing은 발송 정합성의 최종 권위로 유지한다.
- provider idempotency key는 provider 재시도의 중복 발송을 막는 마지막 경계다.
- Redis acquire·renew·release의 불확실성은 provider 호출 전 `NOT_READY` backpressure로
  바꾸고, claim된 행은 lease 만료 뒤 기존 `recoverExpired` 경로로 재처리한다.
- ambiguous acquire는 동일한 `SemaphoreRequestId`로 reconcile한 뒤에만 action을 실행한다.

## 구현 규칙

- clinic permit을 먼저 취득한 뒤 global permit을 취득해 hot clinic이 global admission을
  오래 점유하는 경로를 줄인다.
- Redis key는 고정 namespace/hash tag 아래 `global`과
  `clinic-{tenantGroupId}-{clinicId}`로 만든다. capacity는 별도 contract key에
  기록하고 다른 값이면 `INTEGRITY_FAILURE`로 차단한다.
- permit lease는 갱신 loop가 lease의 1/3 주기로 동작한다. renew 결과가
  `OWNERSHIP_LOST`·`EXPIRED`·backend failure이면 action을 취소하고 두 permit을
  `NonCancellable` cleanup으로 정리한다. 각 renew 호출도 lease의 1/4 이내 timeout으로
  제한해 Redis command hang이 lease 만료까지 action을 붙잡지 않게 한다.
- blocking provider adapter는 `runInterruptible(Dispatchers.IO)` 경계와
  `InterruptedException` 변환으로 coordinator cancellation을 외부 호출 작업까지
  전달한다. interrupt를 무시하는 SDK는 자체 timeout으로 제한해야 하며, DB fencing과
  provider idempotency가 최종 중복 방어선이다.
- `lease-duration`은 in-process provider bound와 `3 * poll-interval` 안전 여유를
  포함하고 최소 1초 이상이어야 한다.
- clinic semaphore client는 최근 idle 항목을 최대 256개까지 재사용하고, 초과하면 가장
  오래 idle인 항목을 닫아 제거한다. capacity contract Redis key는 capacity drift 방지를
  위해 만료시키지 않으며, 높은 clinic cardinality 측정은 후속 성능 작업으로 남긴다.
- `close()`는 active reference가 남은 registry entry를 map에서 먼저 제거하지 않는다.
  client를 닫은 뒤 action의 `NonCancellable` release가 entry를 정리할 수 있어 shutdown
  race가 예외로 변하지 않는다.

## 검증

- fake semaphore 단위 테스트: global rollback, ambiguous reconcile, cancellation cleanup,
  renew ownership loss.
- Redis 8 singleton 통합 테스트: 두 coordinator의 global·clinic 상한 공유, capacity
  contract, lease 만료 후 재취득, 닫힌 connection fail-closed, in-flight action 취소와
  다른 coordinator의 lease 만료 후 재취득.
- dispatcher 경계 테스트: Redis admission 실패 후 실제 H2 DB lease를 만료시킨 행이
  다음 tick의 `recoverExpired`에서 다시 처리됨.
- Spring lifecycle·metric 테스트: dedicated connection 단일 close, active registry
  shutdown race, `RELEASED`·`EXPIRED` reason tag를 확인한다.

## 후속 범위

Redis 7.2/8.8 명시적 이미지 매트릭스와 전역 lockfile 도입은 별도 후속 Issue로 관리한다.
운영 다중 인스턴스에서 `LOCAL`을 선택하지 않도록 배포 검증을 유지하고, 고 clinic cardinality
churn·admission p95 비교는 별도 성능 후속 범위로 관리한다.

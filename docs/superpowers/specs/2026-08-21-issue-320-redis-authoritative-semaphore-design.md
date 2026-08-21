# Notification Outbox Redis 권위 동시성 설계

## 목표

Issue #320의 목표는 `NotificationOutboxDispatcher`가 여러 애플리케이션 인스턴스에서
동작해도 Redis가 전역 및 병원별 동시성 상한의 권위 있는 조정자가 되도록 하는 것이다.
현재의 프로세스 로컬 `Semaphore`는 단일 인스턴스에서는 상한을 지키지만 인스턴스가
늘어나면 총 provider 호출 수가 상한을 초과할 수 있다.

DB claim/lease/fencing과 provider idempotency는 계속 최종 정합성 권위로 유지한다.
Redis permit은 provider 호출을 admission하는 운영 상한이며, business correctness나
exactly-once 보장 수단으로 사용하지 않는다.

## 범위와 비범위

### 범위

- `NotificationOutboxDispatcher`의 전역 동시성(`globalConcurrency`)을 Redis semaphore로
  공유한다.
- `(tenantGroupId, clinicId)`별 동시성(`perClinicConcurrency`)을 Redis semaphore로
  공유한다.
- `bluetape4k-lettuce 1.12.1`의 suspend expirable semaphore를 사용한다.
- owner/request identity, acquire ambiguous 결과의 reconcile, release와 만료 처리를
  명시한다.
- 정상 종료 시 permit client를 닫고, process exit 또는 Redis 장애에서는 만료와
  backpressure로 안전하게 회복한다.
- Redis가 구성되지 않은 기존 테스트·단일 프로세스 사용자는 기존 로컬 semaphore
  동작을 유지한다.
- Redis 8 `RedisServer.Launcher.redis`를 사용하는 단일 통합 검증과 다중 coordinator
  동시성 검증을 추가한다.

### 비범위

- DB claim/lease/fencing 모델 변경
- provider idempotency key 또는 exactly-once 의미 변경
- `NotificationDirectOutboxDelivery`의 별도 로컬 semaphore 경로 변경
- Redis 7.2/8.8 이미지 매트릭스 및 전역 lockfile 도입(별도 후속 이슈)
- 기존 Issue #201의 로컬 registry 정리 작업 재수행

## 대안 검토

### A. 프로세스 로컬 semaphore 유지

구현은 가장 작지만 인스턴스 간 상한을 조정하지 못한다. Issue의 핵심 성공 조건을
만족하지 못하므로 채택하지 않는다.

### B. 고정 Redis semaphore

고정 permit은 API가 단순하고 release가 명확하다. 그러나 process exit나 네트워크 단절
중 release 불능 permit을 별도 reconciliation 없이 회수할 수 없어, outbox worker의
장애 경계에 추가적인 stale allocation 복구가 필요하다.

### C. 만료형 Redis semaphore + 명시적 로컬 fallback (채택)

`LettuceSuspendPermitExpirableSemaphore`의 Redis server-time lease를 사용한다. 정상
경로에서는 release하고, 처리 시간이 길어지면 lease를 갱신하며, process exit에는
만료가 permit을 회수한다. acquire/release가 ambiguous이면 같은 owner/request
identity로 한 번 reconcile한 뒤 fail-closed backpressure를 적용한다.

Redis 연결이 없는 구형 단일 프로세스 환경만 로컬 semaphore로 동작한다. 연결은 있으나
Redis 조정이 실패한 경우에는 로컬 permit으로 몰래 전환하지 않고 해당 notification을
`NOT_READY`로 남겨 다음 DB lease recovery가 다시 시도하게 한다. 이 정책이 다중
인스턴스에서 상한을 초과하지 않는 유일한 안전한 fallback이다.

## 구성 요소와 흐름

### `NotificationOutboxConcurrencyCoordinator`

새 coordinator는 dispatcher가 사용할 단일 admission port다.

- Redis가 없으면 전역 `Semaphore`와 참조 카운트 기반 clinic registry를 생성한다.
- Redis가 있으면 전역 semaphore와 clinic별 semaphore를 lazy-create한다.
- 각 semaphore는 같은 namespace 아래에서 `trySetPermits`로 capacity를 한 번만
  초기화한다. 이미 다른 인스턴스가 초기화한 경우 `AlreadyInitialized`를 정상으로
  취급한다.
- Redis key는 `clinic:notification:outbox:global` 및
  `clinic:notification:outbox:clinic:<tenantGroupId>:<clinicId>`로 구성한다. 숫자
  scope 값만 key에 사용하며 metric tag에는 노출하지 않는다.
- owner는 dispatcher 인스턴스 생성 시 `SemaphoreOwnerId.random()`으로 한 번 생성한다.
  이 값은 현재 DB lease owner 문자열(`notification-outbox-worker`)과 분리된 Redis 전용
  identity이며, 두 dispatcher 인스턴스는 서로 다른 owner를 사용해야 한다. request는
  한 notification의 한 permit acquire 시도마다 새로 생성하고, ambiguous 결과를
  reconcile할 때만 재사용한다.

### 한 notification의 admission 순서

1. 전역 expirable permit을 bounded wait로 acquire한다. `leaseTime`은
   `worker.leaseDuration`으로 고정하고, `acquireWaitTime`은
   `min(worker.pollInterval, leaseTime / 4)`로 제한한다. worker 설정의 기존 검증
   (`leaseDuration > providerAttemptsPerLease * longestProviderTimeout`)이 provider
   호출 상한보다 lease가 길다는 전제를 유지한다.
2. 병원별 expirable permit을 같은 방식으로 acquire한다. clinic acquire가 실패하거나
   취소되면 이미 획득한 global handle을 반드시 `NonCancellable`로 먼저 반납한다.
3. 두 permit을 모두 소유한 동안에만 `worker.process`를 호출한다.
4. `renewInterval = max(leaseTime / 3, 100ms)` 간격으로 Redis server-time 기준으로
   renew한다. renew 결과의 새 handle을 원자적으로 교체한다.
5. worker 종료·예외·취소 후 `NonCancellable` cleanup에서 clinic permit, global
   permit 순으로 release한다.
6. release가 ambiguous이면 원래 request로 reconcile하고, owned handle이 확인될 때만
   한 번 더 release한다. 결과가 불명확해도 lease 만료가 최종 회수 경계다.

Acquire가 `Unavailable`, `TimedOut`, backend failure, integrity failure를 반환하면
worker를 호출하지 않고 `NOT_READY`를 반환한다. `Ambiguous`는 reconcile 결과가
소유권을 확인할 때만 worker를 호출한다. Redis 연결이 살아 있는 동안 로컬 semaphore로
대체하지 않는다.

renew가 ownership을 잃거나 integrity/backend 결과를 신뢰할 수 없게 되면 renew job이
현재 action coroutine을 취소하고 permit을 재사용하지 않는다. dispatcher는 이
전용 `NotificationPermitLostException`을 `NOT_READY`로 매핑하며, 원래 caller의
`CancellationException`은 그대로 재전파한다. cleanup 실패는 warning log와 bounded
metric으로 남긴다.

## 실패·복구 정책

| 상황 | 정책 | 정합성 권위 |
|---|---|---|
| Redis 연결 없음 | 기존 로컬 semaphore 경로 | DB lease/fencing |
| Redis acquire timeout/unavailable | worker 미호출, `NOT_READY`, 다음 tick backpressure | DB lease recovery |
| Acquire ambiguous | 동일 owner/request reconcile, owned일 때만 계속 | Redis request idempotency |
| Process exit | expirable lease 만료 후 Redis cleanup | Redis server time |
| Renew ownership lost | 작업 중단, permit 재사용 금지 | Redis handle generation |
| Release ambiguous | 동일 request reconcile 후 bounded 재시도, 최종적으로 lease 만료 | Redis handle/reconcile |
| DB lease lost/provider retry | 기존 worker 결과와 DB fencing 유지 | DB/provider idempotency |

Failure metric은 `mode`(`local`, `redis`)와 다음 고정 reason만 사용한다:
`UNAVAILABLE`, `TIMED_OUT`, `BACKEND_FAILURE`, `INTEGRITY_FAILURE`, `AMBIGUOUS`,
`OWNERSHIP_LOST`, `RELEASE_FAILURE`. tenant, clinic, member, appointment, request
identity는 tag로 사용하지 않는다.

## 테스트 설계

### 단위 테스트

- 기존 dispatcher의 local global/clinic 상한, fairness, recovery 테스트를 그대로
  통과시킨다.
- fake distributed semaphore로 두 coordinator가 같은 global/clinic capacity를
  공유할 때 총 active worker 수가 상한을 넘지 않는지 검증한다.
- unavailable/timeout/backend failure는 worker를 호출하지 않고 `NOT_READY`를
  반환하는지 검증한다.
- ambiguous acquire는 동일 request reconcile 후 owned handle일 때만 worker를
  호출하는지 검증한다.
- renew ownership loss와 cancellation에서 release가 호출되고
  `CancellationException`이 재전파되는지 검증한다.
- global을 먼저 획득한 뒤 clinic acquire가 실패·취소되는 경우 global permit이
  누수 없이 반납되는지 검증한다.
- clinic registry가 idle key를 제거하고 active reference 동안 같은 semaphore를
  공유하는지 검증한다.

### Redis 8 통합 테스트

- lockfile이 고정한 `bluetape4k-testcontainers:1.12.1`의
  `RedisServer.Launcher.redis` 하나만 사용하고, resolved artifact의
  `RedisServer.TAG == "8"`과 container image를 함께 확인한다. upstream develop의
  미배포 tag 변경은 이 테스트 계약의 근거가 아니다.
- 두 coordinator가 같은 global key와 clinic key를 사용해 동시에 작업할 때 Redis
  live active 수가 전역 및 병원별 상한 이내인지 검증한다.
- 정상 release 후 available permits가 회복되는지 확인한다.
- 짧은 expirable lease에서 release를 생략한 allocation이 만료 후 회복되는지
  확인한다.
- coordinator별 Lettuce connection을 분리하고, 한 coordinator의 connection을
  성공적인 allocation 뒤 닫아 renew가 `NOT_READY`와 provider 미호출로 fail-closed
  되는지 확인한다. 다른 coordinator의 Redis capacity는 계속 회복 가능해야 한다.

### 수용 기준

- single instance와 두 coordinator instance 모두 `globalConcurrency` 및
  `perClinicConcurrency`를 초과하지 않는다.
- Redis 장애에서 provider 호출을 허용해 상한을 초과하지 않으며, DB lease recovery가
  claimed row를 다시 처리할 수 있다.
- 기존 dispatcher 동작과 `appointment-notification` 모듈 전체 테스트가 통과한다.

## 구현상 안전장치

- `CancellationException`은 catch에서 재전파한다.
- suspend cleanup은 `withContext(NonCancellable)` 안에서 수행한다.
- Redis semaphore client는 dispatcher `close`에서 닫으며, shared Lettuce connection은
  coordinator가 소유하지 않는다.
- 설정과 Redis 응답에 대한 검증은 `require`/`check`로 명시하고, permit identity는
  라이브러리의 redacted value object를 사용한다.
- 전역 lockfile 및 Redis image matrix는 이 설계에서 생성하거나 수정하지 않는다.

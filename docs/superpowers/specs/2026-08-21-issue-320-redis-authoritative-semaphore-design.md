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
- `concurrency-mode=LOCAL`을 명시한 테스트·단일 프로세스 사용자는 기존 로컬 semaphore
  동작을 유지한다. 기본값은 `REDIS`이며, Redis 연결이 없으면 시작을 거부한다.
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

### C. 만료형 Redis semaphore + 명시적 로컬 모드 (채택)

`LettuceSuspendPermitExpirableSemaphore`의 Redis server-time lease를 사용한다. 정상
경로에서는 release하고, 처리 시간이 길어지면 lease를 갱신하며, process exit에는
만료가 permit을 회수한다. acquire/release가 ambiguous이면 같은 owner/request
identity로 한 번 reconcile한 뒤 fail-closed backpressure를 적용한다.

운영 기본값은 `REDIS`다. Redis 연결이 없는 상태에서 `REDIS`를 선택하면 auto-configuration이
시작을 거부하므로 인스턴스별 설정 오류로 전역 상한을 우회할 수 없다. 단일 프로세스 테스트나
명시적으로 분리된 개발 실행에서만 `concurrency-mode=LOCAL`을 사용한다. 연결은 있으나
Redis 조정이 실패한 경우에는 로컬 permit으로 전환하지 않고 해당 notification을 `NOT_READY`로
남겨 다음 DB lease recovery가 다시 시도하게 한다.

## 구성 요소와 흐름

### `NotificationOutboxConcurrencyCoordinator`

새 coordinator는 dispatcher가 사용할 단일 admission port다.

- `concurrency-mode=LOCAL`이면 전역 `Semaphore`와 참조 카운트 기반 clinic registry를 생성한다.
- `concurrency-mode=REDIS`이면 전용 `NotificationConcurrencyRedisConnection`이 없을 때
  시작을 거부하고, 전역 semaphore와 clinic별 semaphore를 lazy-create한다.
- 각 semaphore는 같은 namespace 아래에서 capacity metadata를 확인한 뒤
  `trySetPermits`로 capacity를 한 번만 초기화한다. 이미 다른 인스턴스가 초기화한
  경우에도 metadata가 요청 capacity와 일치할 때만 `AlreadyInitialized`를 정상으로
  취급한다.
- Lettuce synchronizer의 name 규칙에 맞춰 namespace는
  `clinic-notification-outbox-v1`, hashTag는 `notification-outbox`로 고정한다.
  global name은 `global`, clinic name은 `clinic-<tenantGroupId>-<clinicId>`처럼
  영숫자·점·밑줄·하이픈만 사용한다. 라이브러리가 생성하는 full key는
  `namespace:{hashTag}:semaphore:<name>:...`이며, 숫자 scope 값만 key에 사용하고
  metric tag에는 노출하지 않는다.
- 각 semaphore name의 capacity metadata를 같은 hashTag의
  `...:capacity-contract:<name>` key에 `SETNX`로 기록한다. 이미 값이 있으면
  요청 capacity와 반드시 일치해야 하며, 불일치·metadata/backend 오류는 fail-closed
  한다. namespace/hashTag/name과 capacity 계약은 rolling deployment 동안 변경하지
  않는다.
- owner는 dispatcher 인스턴스 생성 시 `SemaphoreOwnerId.random()`으로 한 번 생성한다.
  이 값은 현재 DB lease owner 문자열(`notification-outbox-worker`)과 분리된 Redis 전용
  identity이며, 두 dispatcher 인스턴스는 서로 다른 owner를 사용해야 한다. request는
  한 notification의 한 permit acquire 시도마다 새로 생성하고, ambiguous 결과를
  reconcile할 때만 재사용한다.

### 한 notification의 admission 순서

1. clinic expirable permit을 bounded wait로 acquire한다. clinic permit을 먼저 확인해
   hot clinic 대기자가 global permit을 오래 점유하지 않게 한다. `leaseTime`은
   `worker.leaseDuration`으로 고정하고, `acquireWaitTime`은
   `min(worker.pollInterval, leaseTime / 4)`로 제한한다. worker 설정의 기존 검증
   (`leaseDuration > providerAttemptsPerLease * longestProviderTimeout`)이 provider
   호출 상한보다 lease가 길다는 전제를 유지한다. Redis coordinator는 추가로
   `leaseTime >= max(1s, inProcessProviderBound + 3 * pollInterval)`을 요구하며,
   `renewInterval = leaseTime / 3`이 항상 `0 < renewInterval < leaseTime`이 되도록 한다.
2. global expirable permit을 같은 방식으로 acquire한다. global acquire가 실패하거나
   취소되면 이미 획득한 clinic handle을 반드시 `NonCancellable`로 먼저 반납한다.
3. 두 permit을 모두 소유한 동안에만 `worker.process`를 호출한다.
4. `renewInterval = leaseTime / 3` 간격으로 Redis server-time 기준으로 renew한다.
   각 renew 호출은 `min(leaseTime / 4, max(250ms, pollInterval * 4))` timeout으로
   제한하고, renew 결과의 새 handle을 원자적으로 교체한다. timeout은
   `BACKEND_FAILURE`로 action을 중단한다.
5. worker 종료·예외·취소 후 `NonCancellable` cleanup에서 clinic permit, global
   permit 순으로 release한다.
6. release가 ambiguous이면 원래 request로 reconcile하고, owned handle이 확인될 때만
   한 번 더 release한다. 결과가 불명확해도 lease 만료가 최종 회수 경계다.

Acquire가 `Unavailable`이면 `UNAVAILABLE`, `CapacityExceeded`이면 `CAPACITY_EXCEEDED`,
`TimedOut`이면
`TIMED_OUT`, `Closed`이면 `CLOSED`, backend/integrity failure는 각 유형의 고정
reason으로 매핑하고 worker를 호출하지 않는다. 이 모든 `Backpressured` 결과는
dispatcher에서 `NOT_READY`로 변환된다. `Ambiguous`는 reconcile 결과가 소유권을
확인할 때만 worker를 호출하고, reconcile 실패는 `AMBIGUOUS` backpressure로 처리한다.
Redis 연결이 살아 있는 동안 로컬 semaphore로 대체하지 않는다.

renew 결과가 `Renewed`가 아니면 결과 유형을 다음처럼 매핑한다: `Expired`는
`EXPIRED`, 의도된 `Released`는 `RELEASED`, `OwnershipLost`/`StaleGeneration`은
`OWNERSHIP_LOST`, `Closed`는 `CLOSED`,
backend failure는 `BACKEND_FAILURE`, integrity failure는 `INTEGRITY_FAILURE`,
ambiguous는 `AMBIGUOUS`. renew job은 이 failure를 `NotificationPermitLostException`으로
action에 전달해 현재 coroutine을 중단하고 permit을 재사용하지 않는다. dispatcher는
이 전용 예외를 `NOT_READY`로 매핑하며, 원래 caller의 `CancellationException`은 그대로
재전파한다. cleanup 실패는 식별 가능한 `RELEASE_FAILURE` bounded metric으로 남긴다.

## 실패·복구 정책

| 상황 | 정책 | 정합성 권위 |
|---|---|---|
| REDIS 전용 연결 없음 | 시작 거부, 로컬 semaphore로 자동 fallback하지 않음 | DB lease/fencing |
| Redis acquire timeout/unavailable | worker 미호출, `NOT_READY`, 다음 tick backpressure | DB lease recovery |
| Acquire ambiguous | 동일 owner/request reconcile, owned일 때만 계속 | Redis request idempotency |
| Process exit | expirable lease 만료 후 Redis cleanup | Redis server time |
| Renew ownership lost | 작업 중단, permit 재사용 금지 | Redis handle generation |
| Renew timeout | 작업 중단, `BACKEND_FAILURE` backpressure | Redis lease와 DB recovery |
| Release ambiguous | 동일 request reconcile 후 bounded 재시도, 최종적으로 lease 만료 | Redis handle/reconcile |
| DB lease lost/provider retry | 기존 worker 결과와 DB fencing 유지 | DB/provider idempotency |

Failure metric은 `mode`(`local`, `redis`)와 다음 고정 reason만 사용한다:
`UNAVAILABLE`, `CAPACITY_EXCEEDED`, `TIMED_OUT`, `BACKEND_FAILURE`, `INTEGRITY_FAILURE`, `AMBIGUOUS`,
`OWNERSHIP_LOST`, `EXPIRED`, `RELEASED`, `CLOSED`, `RELEASE_FAILURE`. tenant, clinic,
member, appointment, request identity는 tag로 사용하지 않는다. `RELEASED`와
`EXPIRED`를 분리해 정상 cleanup과 stale allocation 회수를 운영상 구별한다.

`NotificationOutboxConcurrencyCoordinator`와 그 port는 모듈 내부 전용(`internal`)이다.
Coordinator의 내부 outcome은 `Acquired(value)`와 `Backpressured(reason)` 두 가지다.
dispatcher는 후자를 `NotificationOutboxWorkerResult.NOT_READY`로 변환한다.
`NotificationPermitLostException`은 coordinator 내부 action 중단 신호이며, 외부
호출자 cancellation과 혼동하지 않는다.

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
- clinic을 먼저 획득한 뒤 global acquire가 실패·취소되는 경우 clinic permit이
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
- namespace/hashTag/name으로 생성된 full key와 capacity metadata가 같은 Redis slot을
  사용하고, capacity mismatch가 fail-closed 되는지 확인한다.
- coordinator별 Lettuce connection을 분리한다. acquire 전에 한 connection을 닫아
  admission failure가 `NOT_READY`와 provider 미호출로 fail-closed 되는지 확인하고,
  action barrier 뒤 connection을 닫아 in-flight action이
  `NotificationPermitLostException` 경계로 취소되는지 확인한다. 다른 coordinator의
  Redis capacity는 lease 만료 뒤 계속 회복 가능해야 한다.
- 동일 workload를 한 coordinator와 두 coordinator로 각각 실행하는 observed peak와
  admission latency 비교, unique clinic churn은 별도 성능 후속 범위로 둔다. 이 변경은
  Redis key contract와 동시성 상한을 검증하는 통합 테스트에 집중한다.

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
- distributed clinic registry는 reference count를 추적하고 idle client를 최대 256개까지
  재사용한다. 상한을 넘으면 가장 오래 idle인 entry를 닫고 map에서 제거하며, active
  reference가 있는 동안에는 제거하지 않는다. close와 retain/release race는 단위 테스트로
  고정한다.
- blocking provider adapter는 `runInterruptible(Dispatchers.IO)` 경계에서 coordinator
  cancellation을 전달한다. interrupt를 무시하는 SDK는 자체 provider timeout과 DB
  fencing/provider idempotency에 의해 제한된다.
- Spring context destroy에서 coordinator/client가 정확히 한 번 close되고 shared
  `StatefulRedisConnection`은 열린 상태로 남는 것을 lifecycle test로 검증한다.
- Redis connection provider는 별도 조건부 auto-configuration phase로 둔다. `LOCAL`에서는
  Redis/Lettuce classpath·connection이 없어도 dispatcher가 유지되며, `REDIS`에서는
  전용 connection 부재를 startup failure로 처리한다. 두 모드와 connection ownership을
  각각 context-runner로 검증한다.
- 설정과 Redis 응답에 대한 검증은 `require`/`check`로 명시하고, permit identity는
  라이브러리의 redacted value object를 사용한다.
- 전역 lockfile 및 Redis image matrix는 이 설계에서 생성하거나 수정하지 않는다.

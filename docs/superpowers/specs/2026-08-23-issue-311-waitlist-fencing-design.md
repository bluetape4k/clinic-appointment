# Issue #311 waitlist scheduler fencing 설계

## 결정 요약

현재 저장소에는 `LettuceFencedLock`을 안전하게 운영 권위로 연결할 수 있는
production runner와 token propagation 경로가 없다. 따라서 이번 범위에서는
`LettuceFencedLock`을 형식적으로 추가하지 않고, 기존 DB lease/version fence를
최종 권위로 유지한다. Redis fenced lock 도입은 아래의 end-to-end 계약이 먼저
구현될 때까지 명시적으로 보류한다.

이 결정은 Redis lock 획득 자체를 거부하는 것이 아니라, Redis fencing token이
DB terminal mutation에 strict-greater 조건으로 전달되지 않는 상태에서 lock을
추가하면 현재보다 강한 안전성을 제공하지 못한다는 판단이다.

## 범위와 성공 조건

### 현재 Issue #311에서 확인한 범위

- `WaitlistDeliverySchedulingRunner`의 scheduler 순서와 advisory lease 경계를
  보존한다.
- scheduler가 실제 row mutation을 시작하기 전에 leader lease 결과를 확인하는
  현재 동작을 보존한다.
- DB가 소유한 vacancy lease/version fence가 stale worker의 terminal write를
  거부하는 계약을 최종 write authority로 유지한다.
- `LettuceFencedLock` 도입을 보류하는 이유와 재개 조건을 코드 독자가 추적할 수
  있도록 문서화한다.
- 향후 도입을 위한 실패·관측·롤아웃 계약을 이 문서에 고정한다.

### 이번 범위에 포함하지 않는 것

- production `WaitlistDeliverySchedulingRunner` bean wiring을 새로 만드는 것
- `WaitlistLeaderLease`를 Boolean 포트에서 fenced handle 포트로 변경하는 것
- `scheduling_waitlist_vacancy_jobs`에 Redis `epoch/sequence` 컬럼을 추가하는
  migration
- 기존 DB `owner/version/leaseVersion/leaseExpiresAt` fence를 Redis token으로
  대체하는 것
- `LeaderGroupElector` reminder recovery 경계를 변경하는 것
- lock을 business authority로 승격하거나 기존/신규 lock을 동시에 획득하는 것
- lock key namespace를 변경하는 것

## 현재 구현의 증거

### scheduler 경계

`appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/waitlist/WaitlistDeliveryScheduling.kt`
의 `WaitlistLeaderLease`는 다음 두 동작만 노출한다.

```kotlin
fun tryAcquire(owner: String, leaseUntil: Instant): Boolean
fun release(owner: String)
```

`WaitlistDeliverySchedulingRunner`는 획득 실패 시 DB 작업을 시작하지 않고,
획득 성공 시 expiry, suppression, hold reconcile, dispatch를 순서대로 실행한
뒤 `finally`에서 owner를 release한다. 이 포트에는 Redis epoch/sequence나
request identity가 없으며, 현재 저장소에는 이 runner를 실제 Redis adapter와
연결하는 production bean도 없다. `WaitlistDeliverySchedulingConfiguration`은
runner bean이 이미 주입된 경우에만 polling adapter를 등록한다.

### DB 최종 fence

`appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt`
의 `terminalUpdate`는 다음 조건을 동시에 만족해야 한다.

- 같은 job id
- `PROCESSING` 상태
- 같은 `leaseOwner`
- 같은 `version`
- 같은 `leaseVersion`
- `leaseExpiresAt > now`

조건을 만족하지 못하면 affected row가 0이 되어 stale owner의 terminal write가
성공하지 않는다. 현재 `VacancyClaim`과 `WaitlistVacancyJobs`에는 Redis fencing
token을 저장하거나 비교하는 필드가 없다. 따라서 현행 fence는 Redis owner token이
아니라 DB claim의 owner/version/lease expiry 계약이다.

### 외부 계약 근거

`bluetape4k-lettuce`의 `LettuceFencedLock`은 downstream 저장소가 token을
strictly greater하게 받아야 fencing 의미가 생긴다. 공식 저장소의
[`CoordinationLocks.ko.md`](https://github.com/bluetape4k/bluetape4k-projects/blob/1.12.1/infra/lettuce/CoordinationLocks.ko.md)는
다음 사항을 요구한다.

- traffic 전에 `bootstrapFencing()`을 수행한다.
- downstream write가 `(epoch, sequence)`를 이전 값보다 엄격히 큰 경우에만
  반영하도록 한다.
- bounded critical section에는 fixed lease를 사용하고, watchdog를 사용하더라도
  TTL·renewal interval·max lifetime을 제한한다.
- `Ambiguous` 결과는 같은 owner/request로 reconcile하고 recovered handle은
  정확히 한 번 release한다.
- `close()`는 새 작업과 local watchdog를 중지하지만 Redis connection을 닫거나
  ownership을 자동 해제한다고 가정하지 않는다.
- metrics/logs에는 raw owner, request, token, key를 노출하지 않는다.

## 선택한 설계

### 1. 권위 분리

이번 구현의 권위는 다음처럼 고정한다.

| 계층 | 권위 | 이번 결정 |
|---|---|---|
| Redis scheduler lease | advisory execution gate | 현행 Boolean port와 bounded lease 유지 |
| DB vacancy claim | stale worker 차단 | `owner/version/leaseVersion/expiry` CAS 유지 |
| DB terminal mutation | business state authority | Redis token 없이는 기존 DB fence만 사용 |
| reminder recovery | 별도 recovery authority | 변경하지 않음 |

Redis lock을 획득했다는 사실만으로 business mutation을 허용하지 않는다. DB
repository가 반환한 claim fence를 가진 transaction만 terminal mutation을 수행한다.

### 2. Boolean port를 당장 변경하지 않는 이유

현재 포트를 typed fenced handle로 바꾸면 호출자는 token을 받은 것처럼 보이지만,
DB mutation은 그 값을 소비하지 않는다. 이는 API 모양만 강하게 만들고 실제 stale
write 차단 능력은 강화하지 않는 위험한 부분 전환이다. 따라서 token을 모든
downstream mutation까지 전달할 수 있는 호출 graph와 migration이 준비될 때까지
현재 포트를 유지한다.

### 3. 재개 조건: end-to-end fenced path

다음 조건을 모두 충족하기 전에는 `LettuceFencedLock`을 production path에
연결하지 않는다.

1. 실제 scheduler runner와 Redis connection lifecycle이 확인된다.
2. logical owner와 tick별 request identity를 정의하고, lock key namespace와
   versioning을 문서화한다.
3. `tryAcquire` 결과가 `Acquired`, `Reentered`, `Contended`, `Ambiguous`,
   backend failure, timeout, cancellation을 구분하는 typed result가 된다.
4. `bootstrapFencing()` 실행 시점과 counter 보존·backup/restore 정책이 정해진다.
5. fixed lease 또는 bounded watchdog 정책과 `close`, cancellation, timeout,
   unknown/ambiguous reconcile, rollback 동작이 정해진다.
6. Redis token을 DB claim 또는 최종 mutation 입력까지 전달할 수 있는 단일
   호출 경로가 생긴다.
7. 모든 Redis-token 보호 대상 terminal mutation에 strict-greater
   `(epoch, sequence)` predicate와 update가 적용된다. 일부 call site만 적용하는
   부분 전환은 허용하지 않는다.
8. 기존 DB `owner/version/leaseVersion/expiry` fence와 새 Redis fence의 관계,
   migration 순서, rollback 시 monotonic counter 보존이 검증된다.
9. stale owner/new owner, lease expiry takeover, Redis error, cancellation,
   close/task leak, release 중복, metrics redaction을 포함한 회귀 테스트가
   production-like Redis와 PostgreSQL에서 통과한다.

### 4. 보류 중 운영 계약

보류 기간의 운영자는 다음을 따른다.

- `appointment.waitlist.delivery.enabled=false`를 rollback 스위치로 사용할 수
  있으며, expiry/suppression/reconcile의 recovery semantics는 유지한다.
- Redis lease 획득 실패는 scheduler tick을 건너뛰는 신호이지 DB 상태를 변경할
  권한이 아니다.
- DB stale-owner 거부는 정상적인 fencing 결과이며, retry가 이전 Exposed
  transaction을 재사용하지 않도록 기존 fresh transaction 경계를 지킨다.
- raw·truncated owner, request id, fencing token, lock key 또는 비키드 해시를
  metrics/logs에 기록하지 않는다. decision/audit sample과 provider evidence에도
  raw actor·request·token·key·payload를 export하지 않으며 필요한 경우 서버 생성
  random 또는 full keyed HMAC 형태의 비가역 opaque reference만 사용한다. actor는
  `SYSTEM` 또는 `hmac:vN:<64 hex>`만 허용하고 `staff:<suffix>`,
  `recovery:<suffix>`, 임의 suffix, truncated·비키드 hash는 거부한다. evidence
  correlation은 일반 HTTP trace `CorrelationId`와 분리한 서버 생성 random/keyed
  opaque 값만 허용하며 caller/domain-shaped 원문은 금지한다. 결과 category,
  bounded count, latency만 기록한다.

  현재 일반 waitlist audit 경계에는 caller correlation 보존과
  `staff:<sha256...take(24)>` 비키드·truncated actor가 남아 있다. 이는 fenced
  evidence 계약의 미충족 상태이며, 이번 docs-only 범위에서 조용히 변경하지 않는다.
  해당 경계의 보정과 회귀 검증 전에는 production path를 활성화하지 않는다.
- 현재 Boolean lease 포트는 tick 시작의 획득 실패만 관측하며 획득 뒤 Redis lease
  expiry/ownership loss를 감지하거나 차단하지 않는다. 획득 뒤 ownership loss가
  발생해도 terminal write의 최종 판단은 Redis 상태가 아니라 DB claim fence가
  내린다.
- Redis 장애나 ambiguous/unknown 결과를 근거로 business mutation을 강제로
  재시도하지 않는다. 같은 owner/request reconcile이나 명확한 lease expiry로
  `NOT_HELD`가 확인될 때까지 결과를 quarantine하고 새 acquire, dispatch,
  requeue를 시작하지 않는다.

### 5. 시간 예산·장애 backoff·관측 보류 조건

현재 기본 `jobLease`는 30초이고 `pollInterval`은 1초이며 Boolean port에는 renewal과
scheduler tick duration 계측이 없다. 따라서 현재 구현은 lease가 전체 tick 예산보다
짧지 않다는 것을 증명하지 않는다. fenced path를 재개할 때는 expiry·reclaim·dispatch를
포함한 tick p95/p99와 안전 여유를 측정하고 `jobLease >= worst-case tick + safety
margin` invariant를 검증한다.

Redis backend error와 ambiguous 결과에는 contention과 구분되는 bounded exponential
backoff+jitter 또는 circuit breaker와 retry budget이 필요하다. 현재 runner는 매
poll tick마다 Boolean acquisition을 시도하므로, 이 backoff/회로 차단 계약이 없는
상태에서는 Redis 장애 시 acquisition retry storm을 안전하게 제한한다고 주장하지
않는다.

향후 metric은 `scheduler_tick_duration`, `lease_acquire_duration`, `lease_outcome`,
`ownership_loss_total`처럼 고정된 이름과 enum category만 사용한다. 현재 facade에는
scheduler/acquire latency와 실행 중 ownership-loss 계측이 없으므로, 이 관측 계약과
identity-derived tag 부재를 regression으로 검증하기 전에는 fenced path를 활성화하지
않는다.

## 테스트 및 검증 계약

### 이번 설계 단계에서 고정할 검증

- `WaitlistDeliverySchedulingTest`: leader lease 획득 실패가 mutation을 시작하지
  않는지,
  release 순서가 보존되는지 확인한다.
- `WaitlistDeliveryRecoveryDrillTest`: stale worker의 DB terminal write가
  거부되고 새 worker가 유효한 claim으로 진행하는지 확인한다.
- `WaitlistDeliveryRepositoryTest`와 PostgreSQL contention test: 기존
  owner/version/leaseVersion/expiry predicate가 유지되는지 확인한다.

### fenced lock 도입 시 필수로 추가할 검증

| 시나리오 | 기대 결과 |
|---|---|
| stale owner가 lease expiry 후 write | strict-greater/DB fence에서 거부 |
| new owner takeover | 더 큰 fence만 성공 |
| Redis failover/backend error | bounded failure, mutation 미실행 |
| timeout/cancellation | handle 회수 또는 명확한 unknown 상태 |
| `Ambiguous` acquire | 같은 owner/request reconcile 후 한 번만 release |
| `close()` | 새 작업 중지, connection/ownership 의미를 오해하지 않음 |
| task/watchdog 종료 | thread·scheduler leak 없음 |
| metrics/logs 및 운영 증거 | raw owner/request/token/key/actor/payload 없음; `SYSTEM`/full keyed HMAC actor와 서버 생성 opaque evidence correlation만 허용 |

이번 단계에서는 위 표의 새 Redis integration test를 추가하지 않는다. 실제
production path와 token propagation이 없기 때문에 지금 추가하면 존재하지 않는
계약을 테스트하게 된다. 단, 향후 구현에서는 ambiguous/unknown quarantine과
redaction을 별도 회귀 시나리오로 포함해야 한다.

## 대안 검토

### Redis lock 어댑터만 추가

기각한다. DB가 Redis token을 읽거나 strict-greater 비교를 하지 않는 상태에서는
기존 DB fence보다 강한 보장을 제공하지 않으며, caller에게 거짓된 fencing
capability를 노출한다.

### 즉시 DB schema를 Redis epoch/sequence로 확장

현재 scheduler runner와 모든 terminal mutation caller가 확인되지 않은 상태에서
schema와 세 dialect migration을 먼저 추가하는 것은 부분 전환을 만들 위험이
있다. production caller graph와 rollback/counter 보존 정책이 확인된 별도 변경으로
추진한다.

### 기존 DB fence 제거

기각한다. Redis는 scheduler lease의 advisory 보조 수단이며, DB claim fence를
대체할 business authority가 아니다.

## 후속 작업 제안

Issue #311 자체를 production runner/wiring, typed result, token propagation,
multi-dialect migration, production-like test를 포함하는 재개 tracker로 유지한다.
동일 범위의 별도 Issue를 만들어 책임을 중복 소유하지 않는다. 조사 중 #311이
소유하지 않는 독립 prerequisite가 확인될 때만 중복 확인 후 별도 Issue를 만든다.

1. production scheduler runner와 Redis adapter/wiring을 식별한다.
2. typed fenced result와 token propagation 경계를 설계한다.
3. PostgreSQL/H2/MySQL migration 및 strict-greater predicate를 추가한다.
4. production-like Redis/PostgreSQL failover·reconcile·redaction 테스트를 추가한다.
5. 위 조건을 모두 검증한 뒤에만 `LettuceFencedLock` 도입을 활성화한다.

독립 prerequisite Issue가 필요할 때만 기존 open issue/epic과 중복을 확인하고,
Issue #311과 양방향 링크를 남긴다.

## 결정 상태

- 결정: **기존 DB fence 유지, `LettuceFencedLock` production 도입 보류**
- 근거: token propagation 및 production runner/wiring 부재
- 안전성: 현재 DB stale-owner 차단 계약을 약화하지 않음
- 다음 승인 지점: 이 설계 문서 검토 후 구현/문서화 계획 승인

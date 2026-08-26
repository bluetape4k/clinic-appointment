# Issue #311 waitlist scheduler fenced production 설계

## 설계 상태

- 상태: 승인된 Type A 구현 설계
- 대상: `bluetape4k/clinic-appointment#311`
- 기준 커밋: `1859b5cb3ae68c25e918236b0923d74d845e6726`
- 기존 보류 문서: `docs/superpowers/specs/2026-08-23-issue-311-waitlist-fencing-design.md`

기존 문서는 production runner와 token propagation이 없어서 fenced lock을 보류했다.
이번 설계는 그 보류 조건을 해소하는 범위만 추가한다. 기존 Boolean 테스트 포트를
형식적으로 감싸거나 DB fence를 제거하지 않는다.

## 문제와 경계

`WaitlistDeliverySchedulingRunner`는 현재 `WaitlistLeaderLease`의 Boolean 결과를
확인한 뒤 expiry, suppression, hold reconcile, vacancy dispatch를 순서대로 호출한다.
그러나 `appointment-api`에는 이 runner를 실제 Redis adapter와 연결하는 bean이 없고,
`WaitlistDeliveryRepository`의 `VacancyClaim`에는 Redis fencing token이 없다. 따라서
Redis lease가 만료된 뒤 새 owner가 같은 vacancy를 처리해도 DB는 owner/version/lease
조건만 비교한다.

이번 변경은 다음 책임을 분리한다.

| 계층 | 권위 | 계약 |
|---|---|---|
| Redis | scheduler 실행 lease와 fencing token 발급 | `LettuceFencedLock`, `bt4k:coord:v1` namespace |
| API runner | lease 결과 해석과 token 전달 | typed `WaitlistLeaderLease`와 handle |
| Core DB claim | vacancy 점유와 monotonic token 저장 | 기존 owner/version/lease 조건 + strict-greater token |
| Core DB terminal write | business state authority | claim의 동일 token과 기존 DB fence 모두 일치 |
| reminder recovery | 별도 authority | 변경하지 않음 |

Redis lock을 획득했다는 사실만으로 business write를 허용하지 않는다. DB claim이
반환한 owner/version/lease와 fencing token을 같은 transaction에서 검증해야 한다.

## 목표와 비목표

### 목표

1. `LettuceFencedLock`의 typed acquire/reconcile/release lifecycle을 waitlist runner에
   연결한다.
2. `FencedLockHandle(epoch, fencingToken)`을 `VacancyClaim`까지 전달한다.
3. DB가 이미 저장한 token보다 엄격히 큰 `(epoch, sequence)`만 새 claim으로 받아들이고,
   terminal write는 해당 claim token을 exact-match로 재검증한다.
4. fixed lease, bounded timeout, cancellation, ambiguous 결과 reconcile, close,
   rollback과 key namespace를 문서와 테스트로 고정한다.
5. owner/request/token/key를 log나 metric tag에 넣지 않고 outcome·latency·ownership
   loss만 닫힌 값으로 관측한다.
6. 실제 dispatcher/recovery port가 없을 때 scheduler를 자동으로 활성화하지 않고
   fail-closed한다.

### 비목표

- reminder recovery의 `LeaderGroupElector` 교체
- Redis lock을 DB outbox, idempotency 또는 provider retry의 business authority로 승격
- 기존 DB owner/version/lease fence 제거
- 기존 lease와 새 lock의 이중 획득
- Fair/ReadWrite/Spin/MultiLock 도입
- `scheduling_*` 테이블명 변경
- waitlist candidate 정책이나 offer/hold 상태 머신 변경

## 재사용 우선 결정

- lock 구현은 새 Redis 스크립트가 아니라 이미 `appointment-api`가 의존하는
  `io.github.bluetape4k:bluetape4k-lettuce:1.12.1`의 `LettuceFencedLock`을 사용한다.
- native lock 호출에는 `LockOwnerId.from(...)`와 `LockRequestId.random()`을 사용한다.
  DB에 남기는 opaque owner reference는 bluetape4k `Base58.randomString(8)`으로
  생성하므로 `UUID.randomUUID()`를 추가하지 않는다. native identity의 raw value는
  adapter 밖으로 내보내지 않는다.
- Exposed query와 transaction 경계는 기존 `WaitlistDeliveryRepository`를 재사용하고,
  모든 public repository 호출은 caller-owned `transaction {}` 안에서 실행한다.
- metric과 logging은 기존 `WaitlistDeliveryMetrics`와 `KLogging`을 확장하고,
  식별자 기반 tag를 새로 만들지 않는다.
- 테스트는 `bluetape4k-assertions` assertion과 저장소의 singleton Testcontainers fixture를
  사용하며 `@Testcontainers`를 추가하지 않는다.

## API 계약

### Fencing token

`appointment-core`에 다음 값을 둔다.

```kotlin
data class WaitlistFencingToken(
    val epoch: Long,
    val sequence: Long,
) : Serializable {
    init {
        require(epoch >= 0L) { "epoch must be zero or positive" }
        require(sequence >= 0L) { "sequence must be zero or positive" }
    }

    fun isRedisIssued(): Boolean = epoch > 0L && sequence > 0L

    fun isStrictlyGreaterThan(previous: WaitlistFencingToken): Boolean =
        epoch > previous.epoch || (epoch == previous.epoch && sequence > previous.sequence)
}
```

DB의 초기 sentinel `(0, 0)`은 저장 표현으로만 허용한다. `claimFenced` 입력은
`isRedisIssued()`를 통과한 양의 epoch/sequence만 수락하고, Redis library가 발급하지 않은
값은 즉시 거부한다. token의 문자열 표현은 fence 값도 redaction한다.

`VacancyClaim`에는 `fencingToken: WaitlistFencingToken?`를 추가한다. null은 기존
DB-only 내부 호출의 하위 호환 경로이고 production scheduler가 만드는 claim은 null을
허용하지 않는다. token이 있는 claim은 claim 시 strict-greater 조건을 통과해야 하며,
`requireValidFence`, `lockVacancy`, `completeOffer`, `completeNoCandidate`, `markExpired`,
`markFailed`는 저장된 token과 exact-match를 확인한다.

`VacancyJobRecord`와 `WaitlistVacancyJobs`에는 다음 non-null 값을 저장한다.

- `fence_epoch BIGINT NOT NULL DEFAULT 0`
- `fence_sequence BIGINT NOT NULL DEFAULT 0`

기존 V19 migration은 수정하지 않고 V31 additive migration에서 두 컬럼을 추가한다.
기존 행은 `(0, 0)`으로 시작하며, 새 fenced claim은 이 값보다 큰 token만 기록한다.

### Typed lease

`appointment-api`의 기존 Boolean port는 다음 의미를 보존하면서 typed 결과를 추가한다.

```kotlin
data class WaitlistLeaseHandle internal constructor(
    val owner: String,
    val token: WaitlistFencingToken,
    val leaseUntil: Instant,
    internal val nativeHandle: FencedLockHandle,
)

sealed interface WaitlistLeaseAttempt {
    data class Acquired(val handle: WaitlistLeaseHandle) : WaitlistLeaseAttempt
    data class Reentered(val handle: WaitlistLeaseHandle, val holdCount: Int) : WaitlistLeaseAttempt
    data class Contended(val remainingTtlMillis: Long) : WaitlistLeaseAttempt
    data class TimedOut(val category: WaitlistLeaseFailure) : WaitlistLeaseAttempt
    data class Ambiguous(val category: WaitlistLeaseFailure) : WaitlistLeaseAttempt
    data class Failed(val category: WaitlistLeaseFailure) : WaitlistLeaseAttempt
}
```

`owner`는 서버가 `Base58.randomString(8)`으로 생성한 DB opaque reference다. native
`LockOwnerId`, `LockRequestId`, `FencedLockHandle`은 adapter 내부에만 보관하며 log, metric,
직렬화 경계로 내보내지 않는다. `TimedOut`/`Ambiguous`의 실제 owner/request pair는
adapter의 pending state에만 남고, reconcile은 같은 pair로 수행한다.

production runner는 `Acquired`와 `Reentered`에서만 작업을 시작한다. `Ambiguous`는
같은 owner/request로 `reconcile`한 뒤 recovered handle이 명확히 확인될 때만 한 번
release한다. `Contended`, `TimedOut`, `Failed`, reconcile 실패는 DB mutation 없이
이번 tick을 종료한다.

기존 테스트 adapter를 보존할 수 있도록 `WaitlistLeaderLease`는 기본 Boolean 메서드를
남기되, production bean은 `FencedWaitlistLeaderLease`를 주입한다. runner는 typed
handle을 dispatcher에 전달하는 `WaitlistFencedVacancyDispatcher` port를 사용할 때만
fenced mode로 생성된다. legacy Boolean runner에는 token propagation을 가장하지 않는다.

### Redis lifecycle

- bean 초기화 시 `bootstrapFencing()`을 한 번 호출하고 결과를 확인한다.
- lock identity는 `LockConfig(namespace = "bt4k:coord:v1")`와 library-safe resource
  `waitlist-delivery`로 고정한다. bluetape4k가 생성하는 파생 key는
  `bt4k:coord:v1:{waitlist-delivery}:lock:waitlist-delivery`와 state·generation·holds·
  terminal·fence-counter key이며, key version을 바꾸면 새 protocol로 별도 rollout하고
  기존 counter를 보존한다. resource 구성요소에 콜론을 넣지 않는다.
- `LeasePolicy.Fixed(properties.jobLease)`를 기본으로 사용한다. bounded watchdog가
  필요한 경우에도 TTL, renewal interval, max lifetime을 설정으로 고정하고 p95/p99
  tick 예산을 초과하지 않도록 `tickBudget < jobLease`를 설정 검증한다. 각 bounded
  작업 전 monotonic elapsed를 확인하고 예산을 넘으면 다음 mutation을 시작하지 않는다.
- `close()`는 local lock task와 신규 작업만 중지한다. Redis connection은
  `RedisClient` bean이 소유하고, close가 자동 unlock을 의미한다고 가정하지 않는다.
- acquire/release 중 cancellation과 timeout은 `UNKNOWN`으로 기록하고 새 acquire를
  즉시 반복하지 않는다. release handle은 같은 native handle에 대해 한 번만 bounded
  retry할 수 있도록 pending으로 보존하고, reconcile 또는 lease expiry 확인 뒤에만
  다음 작업을 시작한다.

## DB strict-greater 계약

claim transaction은 row lock으로 현재 row를 읽은 뒤 다음을 모두 확인한다.

```text
status = READY
  또는 status = PROCESSING AND lease_expires_at <= now
AND incoming token > (fence_epoch, fence_sequence)
```

성공한 update는 `status`, `lease_owner`, `lease_version`, `lease_expires_at`, `version`,
`fence_epoch`, `fence_sequence`를 함께 갱신한다. 같은 token으로 동일 row를 다시 claim할
수 없으며, 새 owner가 가져간 뒤 이전 owner의 claim은 기존 owner/version/lease 조건과
token exact-match에서 거부된다.

terminal update는 다음 predicate를 함께 사용한다.

```text
status = PROCESSING
AND lease_owner/version/lease_version = claim
AND lease_expires_at > now
AND (fence_epoch, fence_sequence) = claim.token
```

따라서 strict-greater는 새 claim을 수락하는 지점에만 적용하고, terminal write는
이미 수락한 claim token을 exact-match로 묶는다. legacy null token은 기존 DB fence만
검증한다.

## Production wiring 경계

`WaitlistFencedSchedulingConfiguration`은 다음 조건을 모두 만족할 때만 bean을 만든다.

- `appointment.waitlist.delivery.enabled=true`
- `RedisClient`, `DataSource`, `WaitlistFencedVacancyDispatcher`, expiry/suppression/
  reconcile port가 모두 존재
- migration readiness가 V31 fence columns를 확인

조건이 하나라도 없으면 runner를 만들지 않으며, no-op dispatcher나 fake lock을 production
bean으로 등록하지 않는다. 이 방식으로 현재 저장소처럼 domain service graph가 외부
adapter로 주입되는 예제에서도 미완성 wiring이 scheduler를 조용히 활성화하지 않는다.

dispatcher는 다음 호출 순서를 단일 transaction으로 구현한다.

1. `WaitlistDeliveryRepository.claimFenced(jobId, owner, now, leaseUntil, token)`
2. 반환된 claim을 `WaitlistDeliveryService.process(claim, ...)`에 전달
3. offer/hold/outbox와 terminal update를 같은 Exposed transaction에서 완료
4. contention은 `withContentionRetry` 바깥에서 fresh transaction으로 재시도

expiry, suppression, hold reconcile port에는 lock handle을 전달하지 않는다. 이 작업들은
기존 recovery semantics를 따르며, vacancy terminal mutation을 수행할 때는 자체 DB
fence를 사용한다.

## 관측과 redaction

다음 metric 이름과 닫힌 값만 허용한다.

- `appointment_waitlist_lease_acquire_total{outcome=acquired|contended|timeout|ambiguous|failed}`
- `appointment_waitlist_lease_acquire_seconds{outcome=...}`
- `appointment_waitlist_scheduler_tick_seconds{mode=active|clinic_disabled|global_off}`
- `appointment_waitlist_ownership_loss_total{source=redis|db}`

owner, request, token, Redis key, tenant/member/entry/offer ID를 tag나 message에 넣지
않는다. correlation은 기존 caller 입력을 재사용하지 않고 서버 생성 opaque 값을
사용한다. 로그에는 outcome, bounded count, duration, exception category만 남긴다.

## 실패 모드와 복구

| 상황 | 동작 | business mutation |
|---|---|---|
| contention/timeout | bounded 결과 기록 후 tick 종료 | 없음 |
| backend error | retry budget 밖의 즉시 재시도 금지, 다음 poll에서 backoff | 없음 |
| ambiguous acquire | 같은 owner/request reconcile | 명확한 handle 전까지 없음 |
| lease expiry | 새 token으로만 reclaim 허용 | stale claim 거부 |
| wrong-owner release | `OwnershipLost` 기록, 다른 owner release 금지 | 없음 |
| cancellation/close | local task 중지, connection ownership 유지 | 미완료 작업 재시작 금지 |
| DB strict-greater 거부 | `LEASE_FENCED`로 분류 | 해당 transaction rollback |

## 호환성과 rollback

V31은 nullable이 아닌 default `(0,0)` additive column이므로 V19~V30 행을 읽을 수 있다.
애플리케이션 rollout은 다음 순서다.

1. V31 migration 적용
2. fence-aware repository와 테스트 배포
3. 모든 production dispatcher가 token을 전달하는지 readiness 확인
4. `enabled=true`로 scheduler 활성화

rollback 시 counter를 되돌리거나 key version을 재사용하지 않는다. 애플리케이션만
`enabled=false`로 내리고 DB fence columns와 Redis counter는 보존한다. protocol 변경은
`bt4k:coord:v2`처럼 새 namespace로 별도 rollout한다.

## 수용 기준과 테스트

### Core

- H2 단위 테스트: `(epoch, sequence)`가 낮거나 같은 claim은 거부하고, 더 큰 claim만
  저장한다.
- H2 단위 테스트: stale owner, wrong token, expiry, duplicate terminal write를
  거부한다.
- PostgreSQL contention test: expiry 후 새 owner가 더 큰 token으로 reclaim하고 이전
  owner terminal write가 0 row가 되는지 확인한다.
- `TableSchemaTest`와 V31 migration contract: `fence_epoch`, `fence_sequence`와
  default를 검증한다.

### API/Redis

- fake lock 결과로 acquired, reentered, contended, timeout, ambiguous+reconcile,
  backend failure, ownership loss, release 중복을 검증한다.
- `WaitlistDeliverySchedulingRunner`는 handle을 dispatcher에 전달하고 lease가
  없으면 expiry/suppression/reconcile/dispatch를 시작하지 않는다.
- Redis Testcontainers singleton으로 실제 `LettuceFencedLock` acquire/release,
  expiry takeover, close 후 task 정리와 metric redaction을 검증한다.
- Spring context는 required port가 없을 때 fenced scheduler를 만들지 않고,
  enabled 상태에서 일부 dependency가 빠지면 fail-closed한다.

### 명령

```bash
./gradlew :appointment-core:test --no-build-cache --no-daemon --console=plain
./gradlew :appointment-api:test --no-build-cache --no-daemon --console=plain
```

## 설계 대안

### Boolean adapter만 추가

기각한다. Redis token이 DB mutation에 도달하지 않아 stale write 보장이 강화되지
않으며, caller가 fenced capability를 받았다는 잘못된 인상을 준다.

### API에 전체 waitlist service graph를 기본 조립

기각한다. 현재 candidate/reliability/notification port는 외부 adapter 경계이고,
기본 wiring은 업무 정책을 새로 발명한다. 명시적으로 제공된 port만 조립하는 conditional
configuration이 이 예제의 책임 경계를 보존한다.

### 기존 DB fence 제거

기각한다. Redis는 scheduler lease 보조 권위이고, business state의 최종 권위는
DB transaction이다.

## SPW writer DoD

- SPW-01: 대상/독자/언어/근거 경로와 미확정 wiring을 상단에 고정했다.
- SPW-02: 경계, API, 실패 모드, 호환성, 수용 기준, rollback을 모두 포함했다.
- SPW-03: `korean-naturalness-checklist.md` KO-01~KO-07 검토를 완료했다.
- SPW-04: 현재 소스, Issue #311, `bluetape4k-lettuce:1.12.1` 계약과 문장별 대조했다.
- SPW-05: Markdown read-back과 링크·식별자·명령 검토를 완료했다.

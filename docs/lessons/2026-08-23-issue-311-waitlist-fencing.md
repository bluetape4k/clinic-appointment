# Issue #311 waitlist scheduler fencing 교훈

## 조사 결과

`WaitlistDeliverySchedulingRunner`는 `WaitlistLeaderLease`의
`tryAcquire(owner, leaseUntil): Boolean` 결과만 받아 tick 실행 여부를 결정한다.
획득에 실패하면 DB 작업을 시작하지 않고, 성공하면 expiry, suppression, hold
reconcile, dispatch를 bounded 순서로 실행한 뒤 `finally`에서 owner를 release한다.
현재 저장소에는 이 runner를 `LettuceFencedLock` adapter와 연결하는 production bean이
없으며, Boolean 포트에도 Redis epoch/sequence나 request identity가 없다.

`WaitlistDeliveryRepository.terminalUpdate`는 job id, `PROCESSING` 상태,
`leaseOwner`, `version`, `leaseVersion`, `leaseExpiresAt > now`를 함께 비교한다.
따라서 stale owner의 terminal write는 affected row 0으로 거부되고, 새 worker는
새로운 DB claim fence를 획득한 뒤에만 진행한다. 이 DB 조건이 현재 waitlist
business mutation의 최종 권위다.

## 결정

이번 Issue #311 범위에서는 기존 DB fence를 유지하고 `LettuceFencedLock`의
production 도입을 보류한다. Redis token을 DB까지 전달하지 않는 adapter-only
전환은 API 모양만 강하게 만들 뿐 stale write 보호를 강화하지 않으므로 채택하지
않았다. DB schema를 먼저 `epoch/sequence`로 확장하는 것도 실제 runner와 전체
terminal mutation caller가 확인되지 않은 상태의 부분 전환이므로 분리한다.

Redis는 scheduler 중복 실행을 줄이는 advisory lease이고, DB claim fence는
business state authority다. reminder recovery의 `LeaderGroupElector` 경계와
현재 lock key namespace는 변경하지 않는다.

## Lettuce 계약에서 확인한 재개 조건

공식 [`CoordinationLocks.ko.md`](https://github.com/bluetape4k/bluetape4k-projects/blob/1.12.1/infra/lettuce/CoordinationLocks.ko.md)의
계약을 waitlist에 적용하려면 다음을 모두 검증해야 한다.

- traffic 전에 `bootstrapFencing()`을 실행하고 counter를 rollout,
  rollback, backup/restore 전반에서 보존한다.
- downstream DB write가 이전 `(epoch, sequence)`보다 strictly greater한 token만
  반영하도록 한다.
- critical section은 fixed lease를 우선하고 watchdog를 사용하더라도 TTL,
  renewal interval, max lifetime을 제한한다.
- `Ambiguous` acquire는 같은 owner/request로 reconcile하며 recovered handle은
  정확히 한 번 release한다.
- `close()`는 새 작업과 local watchdog를 중지하지만 Redis connection을 닫거나
  ownership을 자동 해제한다고 가정하지 않는다.
- metrics와 log에는 raw·truncated owner, request, token, key 또는 비키드 해시를
  노출하지 않고 bounded outcome·latency·결과 category만 기록한다. decision/audit
  sample과 provider evidence에도 raw actor·request·token·key·payload를 export하지
  않으며, 필요한 경우 `SYSTEM` 또는 `hmac:vN:<64 hex>` full keyed HMAC 형태의
  비가역 opaque actor만 사용한다. `staff:<suffix>`, `recovery:<suffix>`, 임의
  suffix, truncated·비키드 hash는 허용하지 않는다. evidence correlation은 일반 HTTP
  trace와 분리한 서버 생성 random/keyed opaque 값만 사용하고 caller/domain-shaped
  원문은 금지한다.

현재 일반 waitlist audit 경계에는 caller correlation 보존과
`staff:<sha256...take(24)>` 비키드·truncated actor가 남아 있어 이 계약을 아직
충족하지 않는다. 이번 docs-only 범위에서 해당 일반 경계를 조용히 변경하지 않으며,
보정·회귀 검증 전에는 fenced path를 활성화하지 않는 hold 조건으로 기록한다.

현재 Boolean lease 포트는 tick 시작의 획득 실패만 관측한다. 획득 뒤 Redis lease
expiry/ownership loss를 감지하거나 차단하지 않으며, 이 경우에도 terminal write의
최종 판단은 Redis 상태가 아니라 DB claim fence가 내린다. 현재 관측 가능한 bounded
outcome은 acquisition failure와 DB stale-owner 거부이고, post-acquire ownership
loss/backend failure 분류는 fenced path 활성화 후 typed result·ownership-loss
metric acceptance로만 다룬다.

`Ambiguous` 또는 unknown 결과는 같은 owner/request reconcile이나 명확한 lease
expiry로 `NOT_HELD`가 확인될 때까지 quarantine한다. 그 전에는 새 acquire,
dispatch, requeue 또는 business mutation을 시작하지 않는다.

## 아직 증명하지 못한 운영 예산

기본 `jobLease`는 30초이고 `pollInterval`은 1초이며, 현재 Boolean port에는 renewal,
scheduler tick duration, acquisition backoff가 없다. 따라서 tick이 lease보다 오래
실행되지 않는다는 invariant를 현재 코드로 증명할 수 없다. 도입 재개 시 expiry·
reclaim·dispatch를 포함한 tick p95/p99와 안전 여유를 측정해
`jobLease >= worst-case tick + safety margin`을 검증해야 한다.

Redis backend error와 ambiguous 결과에는 contention과 구분되는 bounded exponential
backoff+jitter 또는 circuit breaker, retry budget이 필요하다. 이 계약이 없으면
1초 poll interval이 인스턴스 수만큼 acquisition retry storm으로 확대될 수 있다.

향후 `scheduler_tick_duration`, `lease_acquire_duration`, `lease_outcome`,
`ownership_loss_total`처럼 고정 category만 사용하는 metric을 추가하고,
identity-derived tag가 없음을 regression으로 확인한다. 현재 facade에는 이 계측이
없으므로 운영 readiness를 이미 충족했다고 해석하지 않는다.

## 필수 검증과 후속 작업

도입을 재개할 때는 production-like Redis/PostgreSQL 환경에서 stale owner/new
owner takeover, lease expiry, Redis failover/backend error, timeout/cancellation,
ambiguous reconcile, close, duplicate release, watchdog/task leak, metrics redaction을
검증한다. Redis token을 모든 보호 대상 terminal mutation에 전달하고 strict-greater
predicate를 일관되게 적용하지 못하면 도입하지 않는다.

production runner/wiring, typed fenced result, token propagation, multi-dialect
migration, failover/reconcile 테스트는 원 Issue #311이 소유하는 재개 tracker로
유지한다. 동일 범위의 별도 Issue를 만들지 않으며, #311이 소유하지 않는 독립
prerequisite가 발견될 때만 중복 확인 후 별도 Issue로 추적한다. 원 Issue #311은
이 보류 결정을 기록한 상태로 유지하며, 실제 fenced implementation이 완료되기
전에는 닫지 않는다.

## 운영 규칙

- `appointment.waitlist.delivery.enabled=false`는 새 dispatch를 멈추는 rollback
  스위치이며 expiry/suppression/hold reconcile은 계속 수행한다.
- tick 시작 acquisition failure와 DB stale-owner 거부만 현재 bounded outcome으로
  관측한다. post-acquire ownership loss/backend failure는 fenced path 활성화 후
  typed result·ownership-loss metric acceptance로 분리한다. 어느 경우에도 business
  mutation 강제 재시도의 근거로 삼지 않는다.
- ambiguous/unknown 결과는 동일 owner/request reconcile 또는 명확한 lease expiry로
  `NOT_HELD`가 확인될 때까지 quarantine한다. 그 전에는 다음 tick의 acquire,
  recovery, dispatch, requeue 또는 business mutation도 시작하지 않는다.
- 운영 증거와 incident 기록에는 member ID, 연락처, JWT, raw lock identity, 원문
  actor/request/token/key/payload를 남기지 않는다. actor는 `SYSTEM` 또는 full keyed
  HMAC, correlation은 서버 생성 opaque evidence 값만 사용한다. 현재 일반 audit
  경계의 caller correlation과 비키드·truncated actor는 보정 전까지 fenced evidence에
  사용하지 않는다.

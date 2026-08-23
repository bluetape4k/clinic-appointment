# 대기 목록 전달 런북

## 사전 조건

1. 대상 dialect의 Flyway 버전과 additive V19 테이블을 확인합니다.
2. 활성 clinic policy, adapter/schema readiness, oldest vacancy age, failed job,
   unknown delivery, Redis leader state를 확인합니다.
3. migration과 복구 검사가 통과할 때까지
   `appointment.waitlist.delivery.enabled=false`를 유지합니다.

```bash
./gradlew :appointment-api:test --tests "*FlywayMigrationTest" --no-build-cache
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" \
  http://localhost:8080/actuator/health/waitlistDelivery
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" \
  http://localhost:8080/actuator/metrics/appointment_waitlist_oldest_vacancy_seconds
```

management token은 배포 secret store에서 제공해야 합니다. shell history, log,
issue, metric label에 넣지 않습니다.

## Scheduler fenced lock 보류 확인

현재 scheduler Redis lease는 advisory execution gate이며, DB claim fence를
대체하지 않습니다. 아래 재개 조건 전체를 확인하기 전에는
`LettuceFencedLock`을 production path에 연결하지 않습니다.

1. 실제 scheduler runner와 Redis connection lifecycle이 확인되어야 합니다.
2. logical owner, tick별 request identity, versioned lock key namespace가 문서화되어야
   합니다.
3. `bootstrapFencing()`과 counter 보존·backup/restore 정책이 검증되어야 합니다.
4. fixed lease 또는 TTL·renewal interval·max lifetime이 제한된 watchdog 정책이
   있어야 합니다.
5. `Acquired`, `Reentered`, `Contended`, `Ambiguous`, backend failure, timeout,
   cancellation을 구분하는 typed result가 있어야 합니다.
6. `Ambiguous` 결과를 같은 owner/request로 reconcile하고 recovered handle을
   정확히 한 번 release하는 경로가 있어야 합니다.
7. Redis token이 모든 보호 대상 DB terminal mutation까지 전달되고, 이전
   `(epoch, sequence)`보다 strictly greater한 값만 반영되어야 합니다.
8. 기존 DB `owner/version/leaseVersion/expiry` fence와 새 Redis fence의 관계,
   migration 순서, rollback 시 monotonic counter 보존이 검증되어야 합니다.
9. stale/new owner, lease expiry takeover, Redis error, cancellation, close/task
   leak, 중복 release와 metric redaction 회귀 테스트가 production-like
   Redis/PostgreSQL에서 통과해야 합니다.
10. expiry·reclaim·dispatch를 포함한 scheduler tick p95/p99와 안전 여유를 측정하고,
    `jobLease >= worst-case tick + safety margin` invariant를 검증해야 합니다.
11. Redis backend error와 ambiguous 결과에 contention과 구분되는 bounded
    exponential backoff+jitter 또는 circuit breaker와 retry budget이 있어야 합니다.
12. `scheduler_tick_duration`, `lease_acquire_duration`, `lease_outcome`,
    `ownership_loss_total` 같은 고정 metric과 enum category가 추가되고,
    identity-derived tag가 없음을 regression으로 확인해야 합니다.

현재 Boolean lease 포트는 tick 시작의 획득 실패만 관측합니다. 획득 뒤 Redis
lease expiry/ownership loss를 감지하거나 차단하지 않으며, 그 경우에도 terminal
write의 최종 판단은 Redis 상태가 아니라 DB claim fence입니다. DB는
`leaseOwner`, `version`, `leaseVersion`, `leaseExpiresAt` 조건으로 stale worker를
차단합니다. 현재 Boolean port에서 관측 가능한 bounded outcome은 tick 시작
acquisition failure와 DB stale-owner 거부입니다. 획득 뒤 ownership loss와 backend
failure는 현재 감지·분류하지 않으며, fenced path 활성화 후 typed result와
ownership-loss metric의 acceptance 조건으로만 다룹니다. 어느 경우에도 business
mutation을 강제 재시도하지 않습니다.

`Ambiguous` 또는 unknown 결과는 같은 owner/request reconcile이나 명확한 lease
expiry로 `NOT_HELD`가 확인될 때까지 quarantine합니다. 그 전에는 새 acquire,
dispatch, requeue 또는 business mutation을 시작하지 않습니다.

운영 증거에는 raw·truncated owner, request id, fencing token, lock key 또는 비키드
해시를 남기지 않습니다. decision/audit sample과 provider evidence에도 raw 식별자,
연락처, actor, request/token/key 또는 원문 payload를 export하지 않고, 필요한 경우
서버가 생성한 비가역 opaque reference만 남깁니다. actor reference는 `SYSTEM` 또는
`hmac:vN:<64 hex>` full keyed HMAC만 허용하고, `staff:<suffix>`,
`recovery:<suffix>`, 임의 suffix, truncated·비키드 hash는 거부합니다. correlation은
일반 HTTP trace 값과 분리한 서버 생성 random/keyed opaque 값만 사용합니다. 결과
category, bounded count, latency만 기록합니다.
`close()`는 새 작업과 local watchdog를 멈추는 lifecycle 신호로만 해석하며 Redis
connection 종료나 ownership 해제를 자동으로 가정하지 않습니다.

## 단계적 전환과 롤백

1. V19을 배포하고 table/constraint/index 개수를 확인합니다.
2. shadow preview를 실행하고 mutation이 없는 decision/audit sample을 확인합니다.
3. clinic 하나를 `clinic-allowlist`에 추가하고 `UP`, 2분 미만 oldest vacancy,
   failed job count 0을 확인합니다.
4. `enabled=true`로 설정하고 제한된 한 구간 동안 dispatch, offer, notification,
   suppression metric을 관찰합니다.

rollback 순서는 `allowlist removal -> dispatch/new delivery zero -> in-flight DB
lease expiry -> expiry/suppression/hold-reconcile drain`입니다. V19 row를 삭제하거나
schema를 낮추지 않습니다. failed/unknown row에 대한 운영자 판단이 끝나고 health가
`UP`으로 돌아온 뒤에만 다시 활성화합니다.

## 운영자 작업

- lease가 만료된 `PROCESSING`은 다음 leader가 reclaim합니다. tick 시작의 Redis
  acquisition failure이면 runner가 작업을 시작하지 않지만, post-acquire Redis
  ownership loss는 현재 Boolean port에서 감지되지 않습니다. 이 경우 write 성공
  여부는 Redis 상태가 아니라 DB claim fence가 판단합니다.
- failed job은 version precondition과 typed reason이 있을 때만 requeue합니다.
- 알 수 없는 provider result는 수동 검토 대상으로 표시하거나 provider evidence를
  확인한 뒤 suppress합니다. acceptance로 처리하지 않습니다.
- 만료되었거나 terminal인 offer는 pending notification을 suppress하고 hold를
  해제합니다.
- Retention은 terminal이면서 미해결 항목이 없는 row만 bounded batch로 purge하며,
  active·legal-hold·audit-hold row는 건너뜁니다.

## Health와 alert

`UP`은 adapter/schema/policy readiness, 2분 미만 oldest vacancy, failed job 없음이
필요합니다. `DEGRADED`는 provider failure ratio 5%, 2–5분 oldest vacancy, unknown
delivery 중 하나에서 시작합니다. dependency/policy 누락, 5분 초과 oldest vacancy,
failed job 발생, 100건 초과 expired backlog이면 `OUT_OF_SERVICE`여야 합니다. alert
규칙은 [`docs/alerts/waitlist-delivery.yml`](../alerts/waitlist-delivery.yml)에
있습니다.

incident ticket에 dialect, Flyway version, metric snapshot, opaque evidence
correlation reference, opaque operator actor reference, action reason, post-action
health를 기록합니다.
일반 HTTP trace의 `CorrelationId`는 caller가 보낸 bounded 값일 수 있으므로
decision/audit sample이나 ticket의 evidence 식별자로 재사용하지 않습니다. evidence
correlation은 서버가 생성한 random/keyed opaque 값만 허용하고 client/domain-shaped
원문은 금지합니다. raw member ID, 전화번호, JWT, provider payload, 원문
actor·request·token·key는 decision/audit sample과 ticket에 기록하거나 export하지
않습니다. 현재 일반 waitlist audit 경계의 caller correlation 보존과
`staff:<sha256...take(24)>` 비키드·truncated actor는 이 계약을 충족하지 않으므로,
해당 경계를 보정하고 회귀 검증하기 전에는 fenced path를 활성화하지 않습니다.

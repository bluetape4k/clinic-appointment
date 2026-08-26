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

## Scheduler fenced lock 운영 확인

fenced scheduler는 `appointment.waitlist.delivery.enabled=true`와 모든 required
bean이 준비된 환경에서만 활성화됩니다. 기본값은 `false`이며 dispatcher가 없는
예제 실행에서는 scheduler가 생성되지 않습니다. 배포 전에 다음을 순서대로 확인합니다.

1. V31 migration이 적용되어 `scheduling_waitlist_vacancy_jobs`의
   `fence_epoch`, `fence_sequence` column이 존재하는지 확인합니다.
2. `RedisClient`, `DataSource`, `WaitlistFencedVacancyDispatcher`, expiry·suppression·
   hold-reconcile port, `MeterRegistry`가 같은 application context에 있는지 확인합니다.
3. `WaitlistFencedSchedulingConfiguration`의 readiness가 성공하고
   `bootstrapFencing()` 결과가 `Initialized` 또는 `AlreadyInitialized`인지 확인합니다.
4. lock lease가 `LeasePolicy.Fixed(jobLease)`이고 `jobLease <= 5m`인지 확인합니다.
5. dispatcher가 받은 `WaitlistLeaseHandle`의 token을 모든 vacancy claim과 terminal
   write에 전달하는지 확인합니다. DB claim은 기존 token보다 strictly greater한
   값만 수락하고 terminal write는 exact-match해야 합니다.
6. `Acquired`/`Reentered`에서만 expiry → suppression → hold reconcile → dispatch를
   수행하는지 확인합니다. `Contended`, `TimedOut`, `Failed`, reconcile 실패는
   business mutation 없이 tick을 종료해야 합니다.
7. `Ambiguous` 응답은 동일 owner/request pair로 한 번만 reconcile하며, recovered
   handle이 명확해질 때까지 새 acquire나 dispatch를 시작하지 않는지 확인합니다.
8. `close()`가 새 tick과 local lock task를 중지하지만 shared `RedisClient`를 닫거나
   자동 unlock하지 않는지 확인합니다.
9. Redis singleton 통합 테스트에서 lease expiry takeover, 더 큰 token, stale release,
   ambiguous reconcile, cleanup, metric redaction을 확인합니다.

readiness 실패는 `WaitlistFencingReadinessException`으로 startup을 실패시킵니다.
Redis lock의 logical resource는 library-safe한 `waitlist-delivery`이며 namespace는
`bt4k:coord:v1`입니다. 파생 key와 native owner/request/token은 운영 log나 metric에
기록하지 않습니다.

허용 metric은 다음 네 종류뿐이며 tag 값도 enum으로 제한합니다.

- `appointment_waitlist_lease_acquire_total{outcome=acquired|contended|timeout|ambiguous|failed}`
- `appointment_waitlist_lease_acquire_seconds{outcome=...}`
- `appointment_waitlist_scheduler_tick_seconds{mode=active|clinic_disabled|global_off}`
- `appointment_waitlist_ownership_loss_total{source=redis|db}`

rollback은 `appointment.waitlist.delivery.enabled=false`로 수행합니다. 이때 새
vacancy dispatch와 notification delivery는 중지하지만 expiry, suppression,
hold-reconcile은 계속하고, DB fence column과 Redis counter는 보존합니다.

## 단계적 전환과 롤백

1. V31 additive migration을 배포하고 `fence_epoch`, `fence_sequence` column과 기존
   table/constraint/index 개수를 확인합니다.
2. shadow preview를 실행하고 mutation이 없는 decision/audit sample을 확인합니다.
3. clinic 하나를 `clinic-allowlist`에 추가하고 `UP`, 2분 미만 oldest vacancy,
   failed job count 0을 확인합니다.
4. `enabled=true`로 설정하고 제한된 한 구간 동안 dispatch, offer, notification,
   suppression metric을 관찰합니다.

rollback 순서는 `allowlist removal -> dispatch/new delivery zero -> in-flight DB
lease expiry -> expiry/suppression/hold-reconcile drain`입니다. V31 row와 Redis
counter를 삭제하거나 schema를 낮추지 않습니다. failed/unknown row에 대한 운영자 판단이 끝나고 health가
`UP`으로 돌아온 뒤에만 다시 활성화합니다.

## 운영자 작업

- lease가 만료된 `PROCESSING`은 다음 leader가 reclaim합니다. fenced runner는
  `Acquired`/`Reentered` handle을 얻은 경우에만 작업을 시작하고, release가
  `OWNERSHIP_LOST`이면 해당 metric을 기록합니다. write 성공 여부는 Redis 상태가
  아니라 DB claim fence가 판단합니다.
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

incident ticket에 dialect, Flyway version, metric 관측값, opaque evidence
correlation reference, opaque operator actor reference, action reason, post-action
health를 기록합니다.
일반 HTTP trace의 `CorrelationId`는 caller가 보낸 bounded 값일 수 있으므로
decision/audit sample이나 ticket의 evidence 식별자로 재사용하지 않습니다. evidence
correlation은 서버가 생성한 random/keyed opaque 값만 허용하고 client/domain-shaped
원문은 금지합니다. raw member ID, 전화번호, JWT, provider payload, 원문
actor·request·token·key는 decision/audit sample과 ticket에 기록하거나 export하지
않습니다. 일반 waitlist audit 경계의 actor/correlation 정책은 이 scheduler metric
경계와 별도 계약입니다. audit evidence를 추가할 때도 위 raw identity 금지 규칙과
서버 생성 opaque correlation 규칙을 재사용하고, 일반 audit 변경은 별도 검토와
회귀 검증으로 추적합니다.

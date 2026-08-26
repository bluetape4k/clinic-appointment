# Issue #311 대기목록 fenced production 교훈

## 배경

기존 `WaitlistDeliverySchedulingRunner`는 Boolean Redis lease만 확인하고
`WaitlistDeliveryRepository`에는 owner/version/lease 조건만 있었다. lease가 만료된
stale worker가 같은 vacancy를 재개할 때 Redis identity를 DB write까지 전달하지 않으면
실행 gate만 강화되고 business mutation은 보호되지 않는 상태였다.

## 결정

기존 DB fence를 유지하면서 V31 additive column
`fence_epoch`, `fence_sequence`를 추가하고, 새 claim에는
`WaitlistFencingToken`을 전달했다. claim은 저장 token보다 strictly greater한 값만
수락하고 terminal update는 동일 token을 exact-match한다. Redis는
bluetape4k `LettuceFencedLock`을 scheduler 실행 lease와 token 발급에만 사용하며,
DB가 business state의 최종 권위다.

API 쪽은 native Lettuce identity를 얇은 typed adapter로 감싼다. owner reference는
`Base58.randomString(8)`으로 만들고 request·native handle은 adapter 내부 pending
state에만 둔다. 명확한 `Acquired`/`Reentered`에서만 expiry → suppression → hold
reconcile → dispatch를 시작하며, `Ambiguous`는 동일 owner/request로 한 번 reconcile한
뒤 recovered handle이 확인될 때만 release한다. `close()`는 local scheduling과 lock
task만 닫고 shared `RedisClient`를 닫지 않는다.

Spring wiring은 `enabled=true`, V31 readiness, Redis/DataSource, typed dispatcher와
recovery port, `MeterRegistry`가 모두 있을 때만 조립된다. 조건이 빠지면 no-op
dispatcher를 만들지 않고 fail-closed한다. `WaitlistDeliveryMetrics`는
`MeterRegistry`에서 자동 생성하되 outcome·mode·source enum만 tag로 허용한다.

## 결과와 증거

- Task 1~3: `appointment-core`의 strict-greater/exact-match repository와 H2·PostgreSQL·
  MySQL V31 migration contract가 GREEN이다.
- Task 4~5: typed adapter/runner의 opaque owner, ambiguous reconcile, close gate,
  bounded fixed lease와 metric allowlist 단위 테스트가 GREEN이다.
- Task 6: `WaitlistFencedSchedulingConfigurationTest`에서 disabled/incomplete wiring,
  typed V31 readiness failure, complete ports와 자동 metric bean을 검증했다.
- Task 7: Redis 8.8 singleton launcher에서 fixed lease expiry → 새 owner의 더 큰
  token → stale release 거부, 실제 Redis handle의 ambiguous reconcile, metric redaction을
  검증했다.

검증 명령:

```bash
./gradlew :appointment-core:test --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryRepositoryTest' --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest'
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.WaitlistFencingMigrationContractTest'
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedLeaderLeaseTest'
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedDeliverySchedulingTest'
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedSchedulingConfigurationTest'
./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistFencedRedisIntegrationTest'
```

모든 위 명령은 각 구현 단계에서 `BUILD SUCCESSFUL`로 확인했다. 최종 멀티모듈
회귀와 PR CI 결과는 Task 9 이후에만 이 lesson에 추가한다.

## 놓친 점

초기 설계 문서에는 resource 문자열에 콜론이 포함된 logical key를 그대로 적었다.
실제 bluetape4k `LettuceFencedLock`은 resource 구성요소를 ASCII letters, digits,
dots, underscores, hyphens로 제한하므로 wiring 테스트가 이를 즉시 드러냈다. 구현은
`waitlist-delivery`로 고정하고 library가 생성하는 파생 key를 API 문서와 런북에
기록했다.

또한 readiness failure 테스트에서 Spring `startupFailure`의 가장 안쪽 원인만
검사해 typed wrapper가 사라진 것처럼 보였다. 이후 전체 cause chain에서
`WaitlistFencingReadinessException`을 찾도록 바꿨다.

## 다음 guard

1. lock resource/namespace를 직접 문자열로 재현하지 말고 bluetape4k factory와
   validation을 통과하는 상수만 사용한다.
2. Spring 조건부 bean은 외부 port 누락과 schema readiness 실패를 각각 테스트하고,
   no-op 대체 구현을 추가하지 않는다.
3. Redis integration은 singleton launcher, `Base58` 격리, owned-key cleanup을
   유지하며 Docker 실패를 skip/pass로 기록하지 않는다.
4. 새 metric tag에는 raw owner, request, token, key, domain ID와 비키드 hash를
   추가하지 않는다.
5. schema·API·문서가 바뀔 때마다 Issue/PR 본문과 DoD에 실제 명령 결과를 read-back하고,
   final CI와 fresh merge approval 전에는 완료로 표시하지 않는다.

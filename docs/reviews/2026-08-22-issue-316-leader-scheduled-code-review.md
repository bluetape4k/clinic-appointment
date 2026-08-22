# Issue #316 `@LeaderScheduled` 구현 six-perspective code review

## 검토 범위와 기준

- 검토 대상 head: `38236640` (`feat/issue-316-leader-scheduled`)
- base: `develop` (`44833bdf`)
- 범위: reminder runner, leader health/metrics adapter, auto-configuration, AOP·Redis·Spring lifecycle 회귀 테스트, 계획·lesson 산출물
- 제외: Issue #317의 leader 정책 외부화, Issue #319의 lease extension·ownership-loss telemetry, DB claim/fencing 및 schema 변경

## 관점별 검토

### 성능

- `@LeaderScheduled` AOP가 tick마다 단일 elector 획득 경계를 제공하며, 기존 scheduler 본문에 새로운 polling loop·retry·blocking loop를 추가하지 않았다.
- coroutine bridge는 기존 `suspendBridgeTimeout` bounded 경계를 유지한다. health 실패 목록은 최대 128개로 잘리고, metric lock tag는 upstream sanitization으로 low-cardinality를 유지한다.
- `ScheduledTaskHolder`의 실제 fixed-delay 등록과 Redis 두 elector lease 재취득 테스트가 실행 비용과 schedule semantics를 함께 확인한다.

판정: P0=0, P1=0.

### 안정성·수명주기

- CGLIB proxy가 생성되도록 runner class/method를 `open`으로 두었고, application-ready 호출은 별도 bootstrap bean이 proxied runner를 통해 전달한다.
- contention은 action을 실행하지 않고, backend 오류는 `SKIP` 경계에서 흡수하며, `CancellationException`은 재전파한다.
- Spring context close에서 `ScheduledFuture`가 취소되는지, Redis 8.8에서 첫 action을 lease보다 오래 유지할 때 두 번째 elector가 만료 후 재취득하는지 검증했다.
- reminder 실행의 최종 중복 방어인 DB claim/fencing 코드는 변경되지 않았다.

판정: P0=0, P1=0.

### 보안·정합성

- 새 사용자 입력·권한·secret 경계를 추가하지 않았다. 고정 reminder lock 이름만 AOP/health adapter에 전달한다.
- upstream `leader.aop.*` metric은 `redacted-lock` tag를 사용하며 tenant, payload, raw identifier를 tag나 로그에 넣지 않는다.
- health elector는 upstream factory가 소유한 shared Redis connection을 재사용하고 notification bean이 connection을 닫지 않는다. host-provided elector가 있으면 자체 elector를 만들지 않는다.

판정: P0=0, P1=0.

### 운영성·관측성

- 기존 `shedlock.leader.*` 중복 metric을 제거하고 upstream `leader.aop.*` recorder를 사용한다. 정상 contention을 health acquisition failure로 세지 않고 `BACKEND_ERROR`만 기록한다.
- leader health는 기본 비활성이고 명시적으로 켰을 때만 monitor와 notification recorder를 만든다. Redis/factory/classpath가 없으면 모듈 startup을 막지 않는다.
- Redis Lettuce single elector 0.5.0은 `supportsAuditLeaderState=false`라 `state(lockName)`가 기본 empty를 반환한다. 따라서 health가 실제 Redis ownership/lease 시각을 직접 read-back한다는 주장을 하지 않고, 해당 한계를 lesson에 기록했다.

판정: P2=1 (후속 Issue #319에서 upstream audit-state/ownership-loss 관측 범위를 재검토), P0=0, P1=0.

### 개발자·API

- `NotificationReminderSchedulingRunner` 생성자는 scheduler, optional metrics, timeout만 받도록 단순화했고, 직접 `runIfLeader`를 호출하는 내부 API를 제거했다.
- `NotificationReminderSchedulingBootstrap`을 별도 bean으로 두어 self-invocation과 AOP 우회 위험을 명시적인 wiring으로 차단했다.
- old deprecated `ReminderRecoveryTriggerGuard` 직접 호출 surface와 scheduler public API는 유지했다. repository 내 runner 생성 호출부와 auto-configuration 조건을 모두 회귀 확인했다.
- API가 notification의 공개 `bluetape4k-leader-spring-boot`를 소비할 때 upstream 범용
  election auto-configuration이 없는 Exposed backend를 eager import하는 경로를 확인했다.
  `AppointmentApiApplication`은 해당 범용 설정만 제외하고 notification Redis elector와
  AOP factory 경로를 유지한다.

판정: P0=0, P1=0.

### 사용자·호출자 영향

- 애플리케이션 ready 직후 첫 recovery 실행, fixed-delay property key, lock name, scheduler 결과 metric/logging, 일반 예외 흡수, cancellation semantics를 유지한다.
- leader contention 시 scheduler action이 실행되지 않고 다음 tick을 기다리며, leader lock이 비즈니스 정합성의 유일한 권위가 되지 않는다.
- Redis 없는 단일 JVM은 upstream local factory 경로를 통해 계속 동작하고, host-provided elector/optional bean 조건은 `ApplicationContextRunner` matrix로 확인했다.

판정: P0=0, P1=0.

## Fresh verification

| 명령·증거 | 결과 |
|---|---|
| targeted runner/health/recorder/auto-config test | 44 tests passed |
| `NotificationLeaderMicrometerTest` + `NotificationLeaderScheduledIntegrationTest` | 8 tests passed |
| `RedisLeaderScheduledLeaseIntegrationTest` | 1 test passed on Redis 8.8 singleton |
| `./gradlew :appointment-notification:test --no-build-cache --no-configuration-cache --console=plain` | 198 tests passed, `BUILD SUCCESSFUL` |
| `./gradlew :appointment-notification:build --no-build-cache --no-configuration-cache --console=plain` | `BUILD SUCCESSFUL`; Kover verify passed |
| `./gradlew :appointment-api:test --no-daemon -Dspring.profiles.active="test,test-postgresql" --no-build-cache --no-configuration-cache --console=plain` | 832 tests passed, 1 skipped, `BUILD SUCCESSFUL` |
| `git diff --check` | PASS |
| Korean terminology audit for plan/lesson | PASS, findings=0 |

## 최종 verdict

- P0: **0**
- P1: **0**
- P2: **1** (Redis single-elector audit-state 미지원; Issue #319 범위로 추적)
- P3: **0**
- 구현·검증·문서화는 merge 전 상태로 적합하다. PR CI와 원격 metadata read-back을 다음 delivery gate에서 확인하며, 사용자 새 승인 전에는 merge·auto-merge·tag·branch deletion을 수행하지 않는다.

## CI 보정 재검토

초기 PR CI는 `appointment-api` context 초기화 중
`NoClassDefFoundError: io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElector`로
실패했다. 이는 runner 동작이나 DB claim/fencing 회귀가 아니라 공개된 upstream
선택적 backend auto-configuration 경계 문제였다. API 애플리케이션 exclusion을 추가한
뒤 CI와 동일한 PostgreSQL 프로필에서 832 tests(1 skipped)가 성공했으며, P0/P1 판정은
변경되지 않는다.

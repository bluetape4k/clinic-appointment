# Issue #317 구현 inline review

## 검토 범위와 기준

- 대상: `NotificationAutoConfiguration.kt`, `NotificationSchedulingRunners.kt`,
  관련 context/unit test, API profile YAML, notification README
- 기준: #317 설계·계획, upstream `LeaderScheduledPolicyProperties`/registry/BPP,
  기존 DB claim/fence·provider idempotency 계약
- 방식: 사용자 지침에 따라 main session에서 여섯 관점을 순서대로 검토했다.
  별도 reviewer agent와 별도 worktree는 사용하지 않았다.

## 관점별 판정

| 관점 | 파일/라인 근거 | 판정 |
|---|---|---|
| 성능 | `NotificationSchedulingRunners.kt`의 `poll()`은 기존 `@Scheduled` fixed delay와 bounded bridge만 유지한다. 정책 registry와 AOP proxy는 startup에 구성되며 tick마다 factory/scheduler를 생성하지 않는다. | P0/P1 없음 |
| 안정성 | `NotificationAutoConfiguration.kt`의 reminder runner는 `AppointmentReminderScheduler`, `LeaderElectorFactory`, `LeaderScheduledPolicyRegistry`, enabled property가 모두 있을 때만 생성된다. selector 누락과 `lease-time < suspendBridgeTimeout`은 bean 생성 단계에서 거부한다. contention/backend/cancellation/context-close는 `NotificationLeaderScheduledIntegrationTest`가 검증한다. | P0/P1 없음 |
| 보안 | selector는 upstream exact `beanName#methodName` registry에 맡기고 wildcard/regex/runtime reload를 문서에서 금지한다. YAML에는 secret material을 넣지 않으며 backend bean 이름만 참조한다. | P0/P1 없음 |
| 운영 | `appointment-api/src/main/resources/application.yml`은 운영 policy를 명시하고, test profile은 policy를 끈다. 긴급 rollback은 `bluetape4k.leader.scheduling.enabled=false`이며 timestamp dependency는 stable 1.0.x 전환 후 별도 검증한다. | P0/P1 없음 |
| 개발/API | clinic 전용 policy model을 만들지 않고 upstream properties/registry/BPP를 재사용한다. 테스트의 `ReusableLeaderElectorFactory`는 클래스 companion에서 한 번만 만들고 각 context에는 elector만 교체해 반복적인 `mockk<LeaderElectorFactory>()` 생성을 없앴다. API 테스트는 Java 25 toolchain에 맞춰 upstream `bluetape4k-virtualthread-jdk25` provider를 선택하고 jdk21 preview provider를 제외한다. | P0/P1 없음 |
| Kotlin 패턴 | production diff에 `!!`, suspend `runCatching`, 취소 삼킴, blocking monitor가 없다. 새 context 검증은 `bluetape4k-assertions`를 사용하고, runner의 plain `@Scheduled`/외부 policy 경계를 테스트 이름과 메시지에 반영했다. | P0/P1 없음 |
| 사용자/호출자 | 두 notification README와 profile YAML이 같은 selector/name/lease/backend 예시를 제공한다. leader lock은 중복 tick을 줄이는 경계이고 DB claim/fence와 provider idempotency가 최종 상태 권위임을 명시했다. | P0/P1 없음 |

## 검증 증거

| 검증 | 결과 |
|---|---|
| 대상 3개 테스트 클래스 | PASS: 46 tests, 0 failures; 전체 `appointment-notification:test`는 210 passing |
| `NotificationSchedulingRunnersTest` metadata | PASS: fixed delay `@Scheduled`, explicit `@LeaderScheduled` 없음 |
| policy negative matrix | PASS: default-off, factory 부재, selector 누락, 짧은 lease startup 경계 |
| configured lock name wiring | PASS: custom policy name이 health monitor state 조회와 AOP/Observation filter에 전달됨 |
| dependency/build governance | PASS: consumer fixtures, `:appointment-notification:build`, `verifyDependencyGovernance` |
| dependency compile | PASS: `:appointment-notification:compileKotlin` with Java 25 timestamp 사전 릴리스 |
| Java 25 virtual-thread provider | PASS: `:appointment-api:test` 869 tests, 0 failures, 0 errors, 3 skipped; `TenantContextTest` provider 로딩 포함 |
| compile/static | PASS: root compile-only build, root `detekt` (`NO-SOURCE`), dependency lock/contract, strict verification |
| P0/P1 | 0 / 0 |

## 후속 위험

- 현재 upstream은 immutable timestamp 사전 릴리스에 의존한다. stable 1.0.x가 배포되면
  catalog·lockfile·verification metadata를 함께 stable BOM으로 전환하고 동일 테스트를
  다시 실행해야 한다.
- CI exact-head와 전체 module build/detekt는 PR 단계에서 fresh evidence로 확인한다.

## 결과

구현 inline review는 P0=0, P1=0으로 PASS다. 별도 추정성 리팩터링이나 새로운
leader abstraction은 추가하지 않았다.

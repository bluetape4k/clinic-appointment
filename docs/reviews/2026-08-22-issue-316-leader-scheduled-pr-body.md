Closes #316

## 변경 목적

리마인더 복구 scheduler의 수동 `@Scheduled`·`runIfLeader` 조합을
`bluetape4k-leader 0.5.0`의 `@LeaderScheduled` 단일 실행 경계로 전환한다.
DB claim/fencing은 최종 정합성 권위로 유지하고, application-ready 첫 실행·취소
전파·bounded health·관측 metric·optional classpath 계약을 보존한다.

## 주요 변경

- `NotificationReminderSchedulingRunner`에 기존 lock name/fixed-delay와
  `failureMode=SKIP`을 가진 `@LeaderScheduled`를 적용한다.
- proxied runner를 호출하는 `NotificationReminderSchedulingBootstrap`을 분리해
  self-invocation으로 AOP 경계를 우회하지 않도록 한다.
- group elector wiring을 제거하고 upstream `LeaderElectorFactory` 기반 health elector,
  `LeaderState` monitor, notification bounded AOP recorder를 조건부 구성한다.
- `leader.aop.*` upstream Micrometer namespace와 redacted lock tag를 사용하고
  `shedlock.leader.*` 중복 metric을 제거한다.
- Spring proxy/스케줄 등록/contended skip/backend error/cancellation/context close,
  Redis 8.8 lease 만료 재취득 회귀 테스트와 Korean lesson/review를 추가한다.
- `@LeaderScheduled` public API type-use가 누락되지 않도록 root consumer fixture
  API scope allowlist와 `LeaderScheduled` compile fixture inventory를 갱신한다.

## DoD Status

| 항목 | 상태 | 증거 |
|---|---|---|
| 수동 scheduler/leader 조합 제거 | PASS | `NotificationSchedulingRunnersTest`, runner diff |
| application-ready proxied 첫 실행 | PASS | `NotificationLeaderScheduledIntegrationTest` |
| contention/backend/cancellation/shutdown | PASS | scheduled integration 5 tests |
| Redis lease 만료 후 다음 elector 재취득 | PASS | `RedisLeaderScheduledLeaseIntegrationTest`, Redis 8.8 |
| health/metrics/optional bean 조건 | PASS | health/recorder/micrometer/auto-config tests |
| DB claim/fencing 및 public scheduler contract 유지 | PASS | 해당 source diff에 schema/claim 변경 없음 |
| module test/build/lock/diff/document audit | PASS | 아래 검증 결과 |

## 검증 결과

- targeted runner/health/recorder/auto-config: 44 tests passed
- Micrometer + Spring AOP/lifecycle: 8 tests passed
- Redis 8.8 lease integration: 1 test passed
- `./gradlew :appointment-notification:test --no-build-cache --no-configuration-cache --console=plain`: 198 tests passed, `BUILD SUCCESSFUL`
- `./gradlew :appointment-notification:build --no-build-cache --no-configuration-cache --console=plain`: `BUILD SUCCESSFUL`, Kover verify passed
- `./gradlew assertModuleConsumerFixtureApiVariants --no-configuration-cache --no-parallel --console=plain`: `BUILD SUCCESSFUL`
- `./gradlew compileModuleConsumerFixtures --no-configuration-cache --no-parallel --console=plain`: `BUILD SUCCESSFUL`
- `./gradlew build -x test -x :frontend:appointment-frontend:build --parallel --refresh-dependencies`: `BUILD SUCCESSFUL` (기존 configuration-cache warning만 보고됨)
- `scripts/verify-dependency-locking.sh`: PASS
- `git diff --check`: PASS
- Korean terminology audit: PASS, findings=0

## 알려진 범위

Redis Lettuce single elector 0.5.0은 `supportsAuditLeaderState=false`라
`state(lockName)`가 기본 empty를 반환한다. 이번 PR은 이를 실제 ownership 증거로
포장하지 않고 두 elector의 lease 재취득을 통합 증거로 사용한다. audit-state와
ownership-loss telemetry는 Issue #319에서 upstream capability를 재검토한다.

## 복귀

문제 발생 시 DB schema나 scheduler public API를 건드리지 않고 이 PR의 dependency,
runner, auto-configuration 변경을 commit 단위로 revert한다. Issue #317의 leader 정책
외부화는 이 PR에 포함하지 않는다.

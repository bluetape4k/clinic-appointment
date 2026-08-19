# Issue #307 구현 리뷰

## 결론

Issue #307의 bounded non-suspend 파일럿과 Spring transaction 경계 증명은 PASS입니다. 외부 Spring bean의 `@Transactional` 호출은 Exposed `SpringTransactionManager`가 관리하는 실제 transaction과 동일한 JDBC connection을 사용했고, direct Exposed `transaction {}`는 publisher가 fail-closed로 거부했습니다. 세 durable fixture row의 commit·rollback 원자성, synchronous listener와 `@TransactionalEventListener(AFTER_COMMIT)`의 시점 차이, 두 listener 실패 의미를 H2에서 회귀 검증했습니다.

이번 변경은 `AppointmentService` 전체 wiring이나 outbox schema/relay/retry/lease를 바꾸지 않습니다. 기존 예약 row와 notification/messaging outbox intent가 durable authority이고, legacy `ApplicationEventPublisher` 호출은 보조 signal이라는 KDoc을 보강했습니다. 검증된 publisher는 독립 fixture의 bounded adapter 후보로만 남겼습니다.

## 변경 파일과 결정

| 파일 | 변경 | 결정 근거 |
|---|---|---|
| `appointment-api/build.gradle.kts` | `bluetape4k-exposed-spring-boot-jdbc`를 `testImplementation`으로 추가 | 공유 BOM이 `1.12.1`을 선택하며 runtime wiring을 변경하지 않음 |
| `appointment-api/src/test/.../AppointmentDddEventTransactionBoundaryTest.kt` | H2 fixture, aggregate, 외부 proxy command, listener와 auto-configuration 조건 테스트 추가 | direct/Spring 경계와 lifecycle을 실제 Spring context에서 고정 |
| `appointment-api/src/main/.../AppointmentService.kt` | 예약·두 outbox intent의 권위와 legacy signal의 보조 역할 KDoc 명시 | 기존 method signature·transaction 위치·best-effort 동작 보존 |
| `docs/superpowers/plans/2026-08-19-issue-307-ddd-event-transaction-boundary-plan.md` | 실제 auto-configuration과 fixture handle 수명 반영 | 실제 JetBrains Exposed manager와 테스트 전용 publisher를 분리하고, publisher auto-configuration 조건은 별도 runner에서 검증 |

실제 transaction fixture는 애플리케이션과 같은 JetBrains `ExposedAutoConfiguration`의 `SpringTransactionManager`를 로드하고, test-only `PublisherFixtureConfiguration`이 동일한 `ApplicationEventPublisher` 기반 adapter를 주입합니다. publisher auto-configuration은 공식 manager와 bean 이름이 충돌하므로 fixture에 섞지 않고, 별도 bluetape4k `ExposedSpringDataAutoConfiguration` runner에서 단일 후보·ambiguous 후보·primary 후보 조건만 검증했습니다. `@EnableTransactionManagement`와 open command를 통해 Spring proxy를 강제했고, fixture와 context가 같은 `DataSource`를 공유할 때 fixture database를 context cleanup에서 중복 해제하지 않도록 소유권을 분리했습니다.

## Issue 완료 조건 매핑

| 완료 조건 | 구현 증거 | 결과 |
|---|---|---|
| direct와 Spring-managed transaction의 synchronization 차이 | `direct Exposed transaction is fail closed while external transactional proxy shares its connection`에서 두 flag가 `false`; proxy 관찰값이 `true` | PASS |
| publisher 경계는 Spring-managed transaction 안에서만 동작 | `TransactionalPilotCommand.commit/rollback`의 외부 bean `@Transactional` + publisher 호출, direct baseline fail-closed | PASS |
| commit·rollback·listener 실패의 row 원자성 | commit/rollback 및 synchronous listener failure 테스트의 appointment·notification·messaging 각 row count | PASS |
| outbox가 durable authority, fast signal은 보조 신호 | `AppointmentService` class KDoc과 구현 리뷰 결정 | PASS |
| 검증 실패 시 기존 경로 유지 | 실제 서비스 publisher wiring과 outbox schema 변경 없음; 파일럿은 fixture에 한정 | PASS |
| 관련 모듈 targeted test 통과 | targeted 7 tests, Kotlin compile, 전체 `appointment-api:test` | PASS |

## 설계 수용 기준 10개 매핑

| 기준 | 테스트 또는 문서 증거 | 결과 |
|---|---|---|
| direct와 Spring `@Transactional` 차이 | direct/proxy 경계 테스트의 두 synchronization flag | PASS |
| 세 durable row commit | `commit keeps three durable rows...`의 `RowCounts(1, 1, 1)` | PASS |
| 예외 시 세 row rollback과 buffer 보존 | `rollback removes all durable rows...`의 `RowCounts(0, 0, 0)`와 buffer 1개 | PASS |
| synchronous/AFTER_COMMIT 시점 분리 | listener의 `synchronousIds`와 `afterCommitIds` 비교 | PASS |
| 두 listener 실패 결과 분리 | synchronous 실패는 command 예외·rollback, AFTER_COMMIT 실패는 command 정상 반환·commit row 유지·재시도 없음 | PASS |
| 종료 resource 정리 | 각 command 후 `TransactionSynchronizationManager`와 `TransactionManager.currentOrNull()` 확인, context DB 해제 | PASS |
| 기존 경로 유지 | `AppointmentService` 실행 로직 불변, KDoc만 보강 | PASS |
| H2·모듈·정적 검증 | 공식 Exposed manager fixture, publisher 조건 runner, dependencyInsight, targeted test, compile, 전체 module test, diff/audit | PASS |
| outbox authority·signal·호출 계약 문서화 | `AppointmentService` KDoc, 본 문서, 계획 문서 | PASS |
| opaque ID·listener 외부 I/O 금지 | `PilotEvent.opaqueId`, 메모리 listener만 사용 | PASS |

## 검증 결과

실행한 명령과 결과는 다음과 같습니다.

```text
./gradlew :appointment-api:dependencyInsight --dependency bluetape4k-exposed-spring-boot-jdbc --configuration testRuntimeClasspath
  BUILD SUCCESSFUL
  io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.12.1

./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.AppointmentDddEventTransactionBoundaryTest"
  SUCCESS: Executed 7 tests in 3s

./gradlew :appointment-api:compileKotlin :appointment-api:compileTestKotlin
  BUILD SUCCESSFUL in 4s

./gradlew :appointment-api:test -Djunit.jupiter.execution.parallel.mode.classes.default=same_thread
  BUILD SUCCESSFUL in 3m
  SUCCESS: Executed 818 tests in 2m 56s (3 skipped)

기본 test 설정은 Testcontainers class를 concurrent로 실행하므로 Docker client 전략 초기화가 동시에 일어나는 환경에서는 실패할 수 있다. 동일한 Docker/Colima 상태에서 classes를 `same_thread`로 고정해 전체 모듈 결과를 재현했다.

git diff --check
  PASS

node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/superpowers/plans/2026-08-19-issue-307-ddd-event-transaction-boundary-plan.md \
  docs/review/2026-08-19-issue-307-implementation-review.md \
  docs/superpowers/INDEX.md
  Terminology audit passed
```

## 보류·되돌리기 조건

connection identity 불일치, 부분 commit, rollback 시 AFTER_COMMIT listener 호출, resource 누수, 모호한 transaction manager 선택, H2 context 기동 실패가 다시 관찰되면 publisher adapter 승격을 중단하고 현재 direct transaction·legacy signal·worker polling 경계를 유지합니다. 이번 구현에서는 해당 실패가 관찰되지 않았습니다.

## DoD Status

- [x] bounded DDD publisher pilot과 Spring transaction proof가 통과했다.
- [x] durable outbox authority와 rollback/listener 계약이 회귀 테스트로 고정됐다.
- [x] 기존 `AppointmentService` 경계와 legacy signal 동작을 유지하는 결정이 KDoc과 리뷰 문서에 기록됐다.
- [x] targeted·compile·전체 module test·정적 검증이 통과했다.

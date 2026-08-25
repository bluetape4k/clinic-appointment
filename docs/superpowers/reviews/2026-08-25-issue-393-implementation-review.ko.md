# Issue #393 구현 7-Tier 검토

검토일: 2026-08-25

## 검토 기준과 범위

- 기준선: `develop` `28e38915cc153fc01275a2c6acad632d99340b93`
- 현재 검토 기준: branch `HEAD` `8717d740b32dd65d8d2b8cff30e42b8fefddd565`
- 소스 구현 commit: `2e7b48ad9c79f62b0bbc79d35535b423575b54e8`; 계획 provenance commit: `8717d740b32dd65d8d2b8cff30e42b8fefddd565`
- 설계 기준: `c59124cf757d3fe95220f61311cbdb5b93e37a4b`
- 승인 spec 기준: `4858dd28d46b79f8e5e947a552c1c7f6a8aacb89`
- 대상: `appointment-event`, `appointment-messaging`, `appointment-notification`의 API
  경계·readiness·문서·consumer fixture·strict lockfile
- 제외: 물리 outbox table/repository 이동, V19 SQL 변경, notification의
  `api(project(":appointment-event"))` 제거, PR 병합

이번 구현은 event persistence 소유권을 이동하지 않고 Gradle API leakage만
줄인다. `appointment-messaging`은 core를 `api`로, event를 `implementation`으로
소비하며, notification은 Issue #409의 transitional exception을 유지한다.
H2 테스트 DB namespace에는 `System.nanoTime()`과 bluetape4k
`Base58.randomString(8)`을 함께 사용한다. 이는 테스트 격리용 suffix이며 도메인
UUID identity 계약은 변경하지 않는다.

## 6-lane 독립 검토

| 관점 | 판정 | 현재 근거와 disposition |
|---|---|---|
| architect | PASS | ADR-15와 source-path matrix가 table/write/claim/readiness/migration 소유자를 분리한다. messaging caller migration note를 두 README에 명시했다. |
| code reviewer | PASS | public writer signature와 typed consumer fixture를 대조했고, event table/status는 implementation 내부에 남겼다. |
| spec verifier | PASS | 승인 spec·plan·#393/#409/#407 traceability와 변경 allowlist가 일치한다. |
| performance | PASS | dispatcher 선행 readiness 1회와 worker row별 확인으로 추가 probe가 `1 + globalConcurrency` 이하이며 기존 bounded claim을 유지한다. |
| security/data boundary | PASS | caller-owned `transaction {}` 경계, tenant preflight, provider payload 비저장 원칙과 waitlist table/index fail-closed를 유지한다. |
| operations/integration | PASS | root/benchmark lockfile scope를 재생성하고 V14/V19/V21/V22 SQL no-diff, dialect migration contract, module fixture를 검증했다. |

Finding disposition: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## 모듈별 7-Tier 판정

| 모듈 | 성능 | 안정성 | 보안/데이터 경계 | 운영 | 개발자/API | 사용자/호출자 | 통합/테스트 |
|---|---|---|---|---|---|---|---|
| `appointment-event` | CLEAR — 물리 table 정의만 유지: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingOutboxEvents.kt` | CLEAR — write 구현을 event로 이동하지 않음: `appointment-event/README.md:125` | CLEAR — Kafka/provider를 event가 소유하지 않음: `appointment-event/README.md:125` | CLEAR — ADR-15 ownership matrix: `docs/requirements/architecture.md:350` | CLEAR — public event contract의 기존 source/ABI 유지 | CLEAR — messaging caller migration은 README가 명시: `appointment-messaging/README.md:31` | CLEAR — event suite 213개 통합 실행에서 성공 |
| `appointment-messaging` | CLEAR — writer에 새 loop/provider I/O 없음: `AppointmentOutboxWriter.kt:15-48` | CLEAR — `core api`/`event implementation`: `appointment-messaging/build.gradle.kts:6-8` | CLEAR — typed fixture가 core 타입·nullable reason-code를 고정: `MessagingApiConsumerFixture.kt:135-147` | CLEAR — root와 benchmark lockfile suffix만 갱신, rollback은 ADR-15에 기록 | CLEAR — public writer가 core import와 원래 parameter 순서를 유지: `AppointmentOutboxWriter.kt:16-43` | CLEAR — 직접 event 사용 caller의 `implementation` 선언과 import 이동을 안내: `appointment-messaging/README.md:31-35` | CLEAR — messaging suite 125개, API scope assertion 및 compile fixture fresh run 성공 |
| `appointment-notification` | CLEAR — pre-claim readiness 1회 + row별 확인, dispatcher concurrency test: `NotificationOutboxDispatcher.kt:86-117` | CLEAR — V19 table과 세 index를 fail-closed 목록에 추가: `NotificationSchemaReadiness.kt:85-142` | CLEAR — waitlist ownership을 event adapter에 두고 readiness만 검사: `NotificationSchemaReadiness.kt:117-125` | CLEAR — missing table/index exact reason과 migration contract를 검증 | CLEAR — 기존 worker/dispatcher API와 transitional event API 유지 | CLEAR — readiness DOWN은 운영자가 조치할 table/index 이름을 반환 | CLEAR — readiness 11/11, notification suite 210개 통합 테스트 성공 |

## Fresh verification evidence

모든 명령은 worktree `/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/issue-393-outbox-ownership`에서 실행했다.

| 검증 | 결과 |
|---|---|
| `./gradlew --no-daemon --no-configuration-cache --no-parallel --rerun-tasks :appointment-event:test :appointment-messaging:test :appointment-notification:test` | `BUILD SUCCESSFUL`, event `213 passing` + messaging `125 passing` + notification `210 passing` = 총 `548 passing`, 1분 2초 |
| `./gradlew --no-daemon --no-configuration-cache --no-parallel --rerun-tasks :appointment-notification:test --tests '*NotificationSchemaReadinessTest*'` | `BUILD SUCCESSFUL`, `11 passing` |
| `./gradlew --no-daemon --no-configuration-cache --no-parallel :appointment-notification:test --tests '*NotificationOutboxDispatcherTest*'` | `BUILD SUCCESSFUL`, `8 passing` |
| `./gradlew --no-daemon --no-configuration-cache --no-parallel --rerun-tasks :appointment-api:test` + Flyway/PostgreSQL/MySQL/Waitlist contract 4 class | `BUILD SUCCESSFUL`, `25 passing`, `1 pending`(production MySQL endpoint 미설정) |
| `./gradlew --no-daemon --no-configuration-cache --no-parallel --rerun-tasks assertModuleConsumerFixtureApiVariants compileModuleConsumerFixtures` | `BUILD SUCCESSFUL`, `19 tasks executed` |
| `./gradlew --no-daemon --no-configuration-cache --no-parallel --rerun-tasks :appointment-messaging-benchmark:compileKotlin` | `BUILD SUCCESSFUL` |
| `git diff --check`와 Korean terminology audit 7개 문서 | `PASS`, `findings=0` |
| V14/V19/V21/V22 12개 migration SQL `git diff --exit-code` | `PASS`, SQL 변경 없음 |

초기 red gate에서 messaging expected scope mismatch가
`unexpected=[project::appointment-event], missing=[project::appointment-core]`로
관찰됐고, scope/import/fixture/lockfile 수정 후 green으로 전환됐다. strict lockfile
변경은 root `appointmentMessagingConsumerFixtureClasspath`와 benchmark
`compileClasspath`에서 R2DBC 좌표 4개의 configuration suffix를 제거한 것뿐이며
버전과 다른 configuration은 변하지 않았다.

## 잔여 범위와 결론

- 이번 구현은 #393 child에 한정한다. #409의 notification event API 축소는 별도
  설계·consumer fixture gate 뒤에 수행한다.
- implementation review artifact를 제외한 현재 range diff는 plan의 허용 경로와
  일치하며, migration SQL과 runtime schema는 변경하지 않았다.
- 전체 train(#392~#402)이 끝나기 전에는 PR merge나 최종 승인 요청을 하지 않는다.

최종 판정: `PASS` — P0/P1/P2/P3 blocker 없음.

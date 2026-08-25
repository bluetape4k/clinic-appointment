# Issue #395 fixture·assertions 7-Tier 검토

검토일: 2026-08-25  
검토 branch: `refactor/issue-395-fixture-assertions`  
선행 child base: `b02af747a0363631620732238adaf7152fe73392` (`#399` PR #415)  
구현 source tip: `5410749712fac442e6fc3f7d8666fcf2d1fd193b`

## 검토 범위

- `appointment-messaging` 일반 H2/PostgreSQL fixture의 Exposed schema/reset 계약
- outbox store/query-plan/writer scope와 consumer/replay 테스트의 assertion API
- 성능 benchmark fresh-schema 예외와 compliance guard
- fixture/assertion 회귀 테스트, Kotlin checklist, 문서·stacked train 전달 경계
- 제외: production messaging API/SQL semantics, benchmark 수치 개선, 다른 module 구현

## 재사용 판단

| 후보 | 판정 | 근거 |
|---|---|---|
| Exposed `SchemaUtils.createMissingTablesAndColumns` | 채택 | 일반 fixture의 기존 Table 목록과 transaction 경계를 유지하면서 incremental schema 계약을 적용했다. |
| Exposed `Table.deleteAll()` | 채택 | 각 fixture가 의존 테이블 역순으로 reset해 반복 실행과 parallel test 오염을 막는다. |
| `io.bluetape4k.assertions` | 채택 | `shouldBeEqualTo`, `shouldBeTrue`, `assertFailsWith`로 값·boolean·예외 검증을 통일했다. |
| bluetape4k singleton launcher/Base58 | 유지 | Kafka/PostgreSQL integration은 기존 `KafkaServer`, `PostgreSQLServer`, `Base58.randomString(8)` 경계를 그대로 재사용한다. |
| 새 fixture/assertion abstraction | 제외 | 기존 ecosystem API가 충분해 중복 wrapper와 dependency를 추가하지 않았다. |
| benchmark의 `SchemaUtils.create` | 제한적 채택 | fresh PostgreSQL schema와 query-plan 비용을 격리하는 성능 전용 예외이며 KDoc·guard에 명시했다. |

## 모듈별 7-Tier 판정

| Tier | 판정 | 현재 근거 |
|---|---|---|
| 성능 | PASS | 일반 fixture는 기존 table 수와 seed를 유지하며 incremental create로 전환했다. benchmark의 fresh-schema/20,000-row claim 경로는 변경하지 않았다. |
| 안정성/수명주기 | PASS | 각 fixture가 `transaction {}` 안에서 schema를 보장한 뒤 reverse `deleteAll()`을 수행한다. PostgreSQL default database 복구와 singleton lifecycle은 유지한다. |
| 보안/데이터 경계 | PASS | fixture reset과 assertion 변환만 변경했고 payload·credential·tenant 식별자 저장/로그 경계를 넓히지 않았다. |
| 운영/관측성 | PASS | 테스트 실패가 표준 bluetape4k assertion으로 보고되며 benchmark 예외와 일반 fixture 계약이 compliance test에서 명시된다. |
| 개발자/API | PASS | production 공개 API와 module dependency graph를 변경하지 않고 기존 Exposed/bluetape4k API를 재사용했다. |
| 사용자/호출자 | PASS | 예제 애플리케이션의 실행·Kafka wire·outbox claim 계약은 그대로이고 테스트 lifecycle만 일관화했다. |
| 통합/테스트/빌드 | PASS | fixture compliance와 consumer/writer/query-plan/Kafka/PostgreSQL 경로를 포함한 module test가 통과했다. |

판정: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## Kotlin checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| KT-01 | PASS | null-safety·immutability·기존 Exposed transaction 경계를 유지하고 assertion extension을 자연스럽게 사용했다. |
| KT-02 | PASS | 변경 전 fixture 목록, assertion 호출자, singleton launcher와 Base58 사용처를 source에서 확인한 뒤 최소 범위만 수정했다. |
| KT-03 | PASS | 새 `!!`, `runCatching`, JUnit/kotlin generic assertion, local `check`, dependency가 없다. |
| KT-04 | PASS | compliance RED 후 GREEN, targeted/full test, `check`/`build`, diff/doc audit을 순차 수행했다. |
| KT-05 | PASS | 적용 가능한 Kotlin rows `11/11`, testing rows `5/5`; production API/auto-configuration 변경이 없어 별도 auto-configuration 설계는 `N/A`다. |

## Fresh verification

| 명령 | 결과 |
|---|---|
| compliance RED: `:appointment-messaging:test --tests '*AppointmentMessagingFixturePatternComplianceTest'` (구현 전) | 기대 실패, direct create/generic assertion 위반 검출 |
| targeted H2 consumer/writer/replay/config tests | `BUILD SUCCESSFUL` |
| targeted Kafka integration + PostgreSQL outbox/query-plan tests | `15 passing`, `BUILD SUCCESSFUL` |
| `./gradlew :appointment-messaging:test --no-daemon` | `135 passing`, `BUILD SUCCESSFUL` |
| `./gradlew :appointment-messaging:check --no-daemon` | `BUILD SUCCESSFUL`, Kover gate 포함 |
| `./gradlew :appointment-messaging:build --no-daemon` | `BUILD SUCCESSFUL` |
| `./gradlew :appointment-messaging:tasks --all | rg detekt` | task 미등록, 실행 대상 없음 |
| `git diff --check` | `PASS` |
| changed test source generic assertion/check scan | 결과 없음 |
| bluetape-writer Korean terminology audit | `2 files`, `findings: []` |

`detekt`는 이 모듈에 task가 등록되어 있지 않아 실행할 수 없다. 모듈의
authoritative static/build gate인 `:appointment-messaging:check`를 사용했고, task
부재를 코드 실패로 해석하지 않았다.

## Finding disposition

- P0/P1: 없음.
- P2: 별도 benchmark 성능 수치를 주장하지 않으며, benchmark direct create는
  성능 전용 예외로 문서화했다.
- P3: 없음.
- 기존 production API, SQL, Kafka wire와 outbox claim semantics는 변경하지 않았고
  full module test로 회귀 여부를 확인했다.

## PR 전 결론

`PASS` — #395 구현·문서·7-Tier 검토 gate를 통과했다. branch는 #399 exact head 위에
fixture/assertion 변경을 쌓았으며, PR 생성·exact-head CI·live metadata read-back은
delivery 단계에서 수행한다. 전체 stacked train의 merge approval은 남은 child가
완료될 때까지 요청하지 않는다.

## 문서 작성 점검

- [x] SPW-01: 범위, source tip, 선행 base, exception과 제외 항목을 고정했다.
- [x] SPW-02: 7-Tier 판정, Kotlin checklist, findings, 검증과 gap을 포함했다.
- [x] SPW-03: 한국어 기술 문체와 정확한 API·명령·식별자를 유지했다.
- [x] SPW-04: source·test·launcher·benchmark·문서 audit 결과를 대조했다.
- [ ] SPW-05: 최종 PR head, exact CI run, Issue/PR live read-back을 반영한다.

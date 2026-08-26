# Issue #409 알림 event·persistence contract 구현 7-Tier 검토

검토일: 2026-08-26  
검토 branch: refactor/issue-409-contract-boundary  
기준 develop: 8d68b1e3bc8c944bc1ba1f9e6e8233417d23cff8  
구현 범위: appointment-event, appointment-notification, appointment-api, 루트 Gradle consumer fixture와 문서  
선행 설계/계획: bda78b01, 7303ed4f, 4294bb6f

## 검토 기준과 근거

이번 검토는 승인된 Issue #409의 구현 diff와 현재 branch source를 기준으로 수행했다. bluetape-workflow Type A gate, bluetape-kotlin-patterns의 KT-FIN-01..11과 Spring/testing/module reference, bluetape-writer의 SPW-01..05를 적용했다. 기존 #392~#402의 기능·schema를 다시 구현하지 않고 contract 경계와 재사용 경계만 검증했다.

주요 근거는 다음과 같다.

- event 공개 surface: NotificationOutboxWriter.kt, WaitlistNotificationOutboxContracts.kt
- persistence 소유: appointment-notification/.../persistence/의 JdbcNotificationOutboxRepository, table, claim/work/observation store, waitlist persistence
- API consumer: ServiceConfig.kt, DefaultAppointmentNotificationWriter, JdbcAppointmentReminderRecoveryStore
- 회귀 guard: 루트 assertAppointmentEventNotificationBoundary와 ApiDependencyBoundaryContractTest
- source/문서 delivery: event·notification·API README, ADR-15, consumer fixture, 본 review와 lesson

## 재사용 우선 판정

| 재사용 대상 | 판정 | 근거 |
|---|---|---|
| io.bluetape4k.assertions | 채택 | contract·wiring·migration 테스트가 shouldBeEqualTo, shouldBeTrue, assertFailsWith를 사용한다. |
| Base58.randomString(8) | 채택 | H2/test 격리 suffix를 UUID/JDK 임의 문자열 대신 기존 codec helper로 통일했다. |
| 기존 codec·hasher·draft/value object | 유지 | event contract는 기존 payload/해시 의미를 재사용하고 persistence 이동으로 중복 계층을 만들지 않았다. |
| Exposed transaction·claim/lease helper | 유지 | SQL, transaction 경계, CAS/fence, retry/retention을 이동 전후 동일하게 보존했다. |
| bluetape4k leader·Resilience4j·Redis lifecycle | 유지 | 기존 scheduler policy, readiness, retry/bulkhead, singleton/cleanup 경계를 새 adapter로 복제하지 않았다. |
| 새 dependency/module/schema migration | 제외 | build.gradle.kts에는 migration scanner package 보정만 있고 새 외부 의존성·Flyway SQL 변경은 없다. |

## 모듈별 7-Tier 판정

| 모듈/관점 | Tier 0 계약 | Tier 1 API/ABI | Tier 2 동작/트랜잭션 | Tier 3 보안/데이터 | Tier 4 성능/운영 | Tier 5 문서/호출자 | Tier 6 통합/검증 | 판정 |
|---|---|---|---|---|---|---|---|---|
| appointment-event | NotificationOutboxWriter·opaque receipt와 순수 draft/envelope만 노출 | event jar에 persistence entry가 없음 | writer port의 idempotency/suppression 의미를 보존 | table·lease·claim metadata가 event에 역참조되지 않음 | codec/hasher round trip과 allocation 경로를 추가하지 않음 | README 두 변형과 KDoc이 port 중심으로 동기화됨 | event test·jar/source guard 통과 | PASS |
| appointment-notification | JDBC repository/table/claim lifecycle의 소유권이 persistence로 고정 | JdbcNotificationOutboxRepository가 event port를 구현하고 기존 worker 호출자를 유지 | transaction, lease token fence, retry, retention, readiness semantics 보존 | opaque receipt는 id만 보유하고 payload/key/lease를 노출하지 않음 | 새 polling/round trip/lock widening 없음; scanner는 persistence package만 탐색 | README가 event consumer와 persistence owner를 설명함 | notification compile·targeted/full regression 및 migration 검증 | PASS |
| appointment-api | writer/recovery store constructor가 event port만 참조 | concrete notification repository/codec import가 production source에서 제거됨 | ServiceConfig 조건은 기존 hasher 경계를 보존하고 recovery runner 조립을 회귀 검증 | unavailable writer는 기존 fail-closed 경계를 유지 | API hot path와 DB query를 변경하지 않음 | API README와 fixture가 port migration을 반영 | API boundary·wiring test와 consumer compile 통과 | PASS |
| 루트 fixture/문서 | event→persistence 단방향 의존을 source/jar로 고정 | public fixture가 writer port를 포함하고 stale event repository를 제거 | migration checksum과 schema ownership을 재검증 | forbidden package/entry가 회귀하면 즉시 실패 | fixture는 compile-only로 제한하고 운영 benchmark를 과장하지 않음 | ADR-15·README·lesson·Issue link가 동일한 결정을 가리킴 | Gradle fixture/boundary task 통과 | PASS |

## Kotlin checklist 결과

| 항목 | 판정 | 현재 근거 |
|---|---|---|
| KT-FIN-01 | PASS | source·caller·test·README·ADR·fixture를 읽고 영향 범위를 고정했다. |
| KT-FIN-02 | PASS | 새 caller validation은 기존 require/check 계약을 유지하고 NotificationOutboxWriteReceipt id만 양수로 검증한다. |
| KT-FIN-03 | PASS | 새 production !!, suspend runCatching, cancellation 삼킴, monitor가 없다. runner는 CancellationException을 먼저 재전파한다. |
| KT-FIN-04 | PASS | Hikari/Exposed DB, Redis/Lettuce, worker lease와 context close 수명주기를 기존 owner와 테스트로 확인했다. |
| KT-FIN-05 | PASS | persistence 이동 후 모든 Exposed 호출은 기존 transaction {} 경계와 receiver shadowing 방어를 유지한다. |
| KT-FIN-06 | PASS | testing·Spring auto-configuration·module move reference를 모두 적용했다. |
| KT-FIN-07 | PASS | wiring/consumer/boundary 테스트가 named behavior를 직접 확인하며 bluetape4k assertions를 사용한다. |
| KT-FIN-08 | PASS | public/internal KDoc와 README 변형, ADR-15가 실제 package·class·port를 반영한다. |
| KT-FIN-09 | PASS | compile output에서 touched source 오류가 없고 기존 Exposed deprecation warning만 남는다. |
| KT-FIN-10 | PASS | targeted compile/test, schema/Flyway, fixture/jar guard, git diff --check를 fresh 실행했다. |
| KT-FIN-11 | PASS | 최종 diff는 #409 범위와 wiring 조건 보정으로 제한되며 P0/P1은 없다. |

## 발견사항과 조치

| 등급 | 위치/증거 | 판단 | 조치 |
|---|---|---|---|
| P0 | 없음 | 데이터 손실·보안 우회·복구 불가 증거 없음 | 해당 없음 |
| P1 | 없음 | event jar/source forbidden guard, schema checksum, transaction/lease/retry/readiness 회귀가 없음 | 해당 없음 |
| P2 | notification worker와 fixture의 일부 public 생성자가 JdbcNotificationOutboxRepository, JdbcNotificationOutboxWorkStore, JdbcNotificationOutboxObservationStore 같은 persistence 타입을 아직 노출한다. | event write port 분리는 완료됐지만 notification 내부 capability port까지 완전히 닫히지는 않았다. 이번 Issue의 비목표를 넘지 않는 후속 작업이다. | 후속 Issue [#425](https://github.com/bluetape4k/clinic-appointment/issues/425)에 범위·완료 조건을 등록하고 #409·PR·lesson에 링크한다. |
| P2 | migration scanner를 넓은 notification package에서 실행하면 coroutine method의 static-method 오류가 발생했다. | 동작 결함이 아니라 scanner가 실제 persistence table package만 받아야 한다는 운영 경계 누락이다. | tablesPackage = "...notification.persistence"로 좁히고 generated SQL을 삭제한 뒤 V14/V19/V21/V22 checksum과 Flyway matrix를 재검증했다. |
| P2 | #408 API wiring test에서 auto-config bean 조건을 넓히면 notificationReminderSchedulingRunner#poll unmatched가 발생했다. | @ConditionalOnBean이 user configuration에서 후속 auto-config bean보다 먼저 평가되는 ordering 위험이다. | ServiceConfig recovery 조건을 기존 NotificationOutboxHasher 기준으로 되돌리고 wiring test를 GREEN으로 고정했다 (482c4da1). |

P0=0, P1=0이며, P2는 모두 owner·조치·검증을 가진 비차단 항목이다.

## 검증 증거

| 검증 | 결과 |
|---|---|
| ./gradlew :appointment-event:test :appointment-notification:test --no-daemon --console=plain baseline | 성공 |
| event·notification·API targeted compile/test 및 Base58 회귀 | 성공 (notification 36건, API notification 34건) |
| generateModuleConsumerFixtureVariantReport assertModuleConsumerFixtureApiVariants compileModuleConsumerFixtures assertAppointmentEventNotificationBoundary | BUILD SUCCESSFUL |
| SchemaInitConfigTest, FlywayMigrationTest, MySQL/PostgreSQL migration matrix | 26 tests, 1 skipped, 성공 |
| migration checksum V14/V19/V21/V22 (H2/MySQL/PostgreSQL) | 12개 기존 digest 모두 불변 |
| NotificationReminderRecoveryWiringTest 재검증 | 1건 성공; 조건 보정 후 runner 조립 확인 |
| git diff --check | 통과 |
| Korean terminology audit | 기존 API README의 기준 데이터 용어 5건만 context 예외로 확인; 이번 문서가 새 용어 drift를 만들지 않음 |

## SPW 문서 gate

- [x] SPW-01: 구현 review의 독자, 목적, 기준 commit, source ledger와 unknown을 고정했다.
- [x] SPW-02: scope/basis, 7-Tier, severity, concrete evidence, disposition, gap와 verdict를 포함했다.
- [x] SPW-03: 한국어 기술 문체를 적용하고 code·command·identifier·exact error를 보존했다.
- [x] SPW-04: 승인 명세·계획·실제 source·test·README·ADR·Issue 후속 조치를 대조했다.
- [x] SPW-05: 최종 Markdown read-back과 terminology audit를 수행하고 PR에서 live metadata/CI를 추가 확인한다.

## 결론

appointment-event는 순수 event contract만, appointment-notification은 내구성 persistence를, appointment-api는 event port 소비를 소유한다. 이 단방향 경계와 기존 DB·lease·retry·readiness semantics가 source, jar, Gradle fixture, context test, migration checksum으로 확인되었다.

**판정: PASS (P0=0, P1=0, P2=3 / 후속 Issue 등록 후 PR delivery 가능)**

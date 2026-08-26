# Issue #425 persistence capability 구현 7-Tier 검토

## 검토 범위와 기준

- 대상: [Issue #425](https://github.com/bluetape4k/clinic-appointment/issues/425)
- 저장소: `bluetape4k/clinic-appointment`
- 기준 ref: `origin/develop` / `5399ff63649f1cc78ae73f00d121c37195817fb8`
- 구현 기준 commit: `800aa0f9a6f526aeff759e71848a8cbf3d6967fe`
- 검토 대상: `appointment-notification` persistence 경계, Spring 자동 구성, consumer fixture, API canary, 테스트·Gradle guard
- 검토 방식: 현재 source/diff read-back, TDD RED→GREEN 증거, targeted/full module verification, ABI/source/jar 검사, 문서·용어 audit
- 독자/언어: 구현자와 유지보수자를 위한 한국어 구현 검토 문서다.

## 판정

**PASS — P0=0, P1=0, P2=0, P3=0**

worker와 observation wrapper의 public constructor는 capability port만 받으며, concrete
`JdbcNotificationOutboxRepository`는 Spring 자동 구성의 내부 조립 지점에 남아 있다.
waitlist adapter의 concrete repository constructor overload와 consumer fixture의 concrete
repository import도 제거했다. 기존 transaction·lease·retry·readiness 동작은 변경하지
않고, constructor/source/fixture/API 경계를 회귀 테스트로 고정했다.

## 7-Tier 결과

| 관점 | 결과 | 확인한 근거 |
|---|---|---|
| 성능 | PASS | 기존 `Dispatchers.IO`, bounded observation limit, caller transaction과 SQL 경로를 유지했다. |
| 안정성 | PASS | claim/recover/complete/retry/retention 순서와 lease fence를 같은 repository 연산으로 보존했다. |
| 보안 | PASS | concrete JDBC 구현은 내부 wiring에 한정하고 tenant/clinic eligible scope를 capability signature에 유지했다. |
| 운영 | PASS | `NotificationAutoConfigurationTest`가 기본 concrete repository → capability wrapper 연결을 확인한다. |
| 개발/API | PASS | work/observation capability port, intentional constructor ABI 변경, `repository` named-argument의 `persistence` migration을 확인했다. |
| 사용자/Caller | PASS | consumer fixture가 capability만 사용하고 wrapper fake를 생성자 직접 주입으로 대체할 수 있다. |
| 통합/테스트 | PASS | notification module, API canary, fixture compile·variant·task graph와 source/jar guard를 통과했다. |

## 변경별 검토

| 경계 | 검토 결과 | 증거 |
|---|---|---|
| Work capability | `NotificationOutboxWorkPersistence`가 worker에 필요한 query·claim·lifecycle 연산만 표현 | `NotificationOutboxPersistenceCapabilities.kt`, contract test |
| Observation capability | `NotificationOutboxObservationPersistence`가 bounded ready 관찰만 표현 | capability contract와 observation delegate test |
| JDBC 구현 | `JdbcNotificationOutboxRepository`가 두 capability와 기존 writer를 구현 | Kotlin compile, repository regression |
| Worker/observation wrapper | concrete repository import·생성자 결합 제거, `persistence` 필드로 위임 | reflection/source read-back, lease/readiness test |
| Spring wiring | 기본 자동 구성은 concrete 구현을 내부에서 wrapper에 주입 | `기본 persistence wiring은 concrete repository를 capability wrapper에 연결한다` PASS |
| Waitlist/fixture | concrete overload/import 제거, sink/lambda와 capability fixture 유지 | waitlist contract, fixture source guard/API variant PASS |
| DB 계약 | SQL migration 파일과 schema 이름을 변경하지 않음 | V14/V19/V21/V22 fingerprint no-diff |

## TDD와 재검증 증거

- RED: concrete wrapper constructor, waitlist concrete overload, fixture concrete import을
  가리키는 3개 boundary assertion이 먼저 실패했다.
- GREEN: `NotificationPersistenceCapabilityContractTest` 5 tests가 `BUILD SUCCESSFUL`을
  반환했다. 이 중 fake work/observation delegate와 `Base58.randomString(8)` fixture가
  capability 위임을 직접 확인한다.
- targeted regression: `NotificationOutboxWorkerLeaseTest`, `NotificationSchemaReadinessTest`,
  `JdbcNotificationOutboxRepositoryTest`, `WaitlistNotificationOutboxAdapterTest`가 통과했다.
- Spring regression: `NotificationAutoConfigurationTest.기본 persistence wiring은 concrete repository를 capability wrapper에 연결한다`가 통과했다.
- fixture/API boundary: notification jar, consumer fixture compile, module fixture API variant,
  fixture task graph가 통과했다.
- full module: `./gradlew :appointment-notification:check`가 `BUILD SUCCESSFUL`이며 Kover
  verify를 포함한다.
- API integration: `:appointment-api:compileKotlin`, `:appointment-api:compileTestKotlin`,
  canary simulation test가 `BUILD SUCCESSFUL`이다.
- source/jar/ABI: fixture concrete token source scan, jar class inventory, wrapper constructor
  reflection이 모두 의도한 capability 경계를 확인했다.
- assertions/patterns: 변경 테스트는 `bluetape4k-assertions`를 사용하고 새 DB 식별자는
  `Base58.randomString(8)`으로 생성했다. Exposed transaction과 기존 singleton/launcher 정책은
  변경하지 않았다.
- 문서: `git diff --check` PASS, 한국어 terminology audit 8개 문서 findings=0이다.

## 이슈 및 위험

- P0/P1/P2/P3: 0.
- migration/schema diff: 없음.
- ABI 주의: wrapper의 concrete repository constructor와 waitlist concrete overload 제거는
  의도적인 source/JVM ABI 변경이다. README와 plan에 named-argument migration 및
  constructor-only fake 주입 범위를 기록했다.
- 원격 CI와 PR review는 PR 생성 후 exact head 기준으로 별도 확인해야 한다. 따라서 이
  문서는 merge 승인을 의미하지 않는다.

## Writer DoD

- [x] SPW-01 — 독자·목적·source·commit·식별자·검토 범위를 고정했다.
- [x] SPW-02 — 변경 경계·대안·호환성·실패 모드·수용 기준·검증 증거를 포함했다.
- [x] SPW-03 — 한국어 technical register와 exact code/command/API token을 보존했다.
- [x] SPW-04 — source, spec, plan, Issue #425, 테스트 결과를 대조했다.
- [x] SPW-05 — heading/table/code fence/link와 최종 문서 흐름을 read-back했다.

## Korean naturalness DoD

- [x] KO-01 — 사실·숫자·식별자·링크·불확실성을 보존했다.
- [x] KO-02 — 동작 주장을 테스트와 source/jar 증거로 제한했다.
- [x] KO-03 — 직접 서술을 사용하고 번역투·홍보성 표현을 배제했다.
- [x] KO-04 — capability·persistence·transaction·ABI 용어를 일관되게 사용했다.
- [x] KO-05 — 발명한 비유와 근거 없는 효과 주장을 사용하지 않았다.
- [x] KO-06 — 제목·표·링크·코드 표면을 read-back했다.
- [x] KO-07 — contextual terminology audit findings=0을 확인했다.

## 결론

구현은 Issue #425의 persistence capability 경계를 닫고 기존 동작 계약을 보존한다.
로컬 검증과 독립 7-Tier 검토는 PASS이며, 다음 gate는 문서·lesson을 commit한 exact
head의 PR/CI 상태 확인이다. merge와 worktree 정리는 fresh approval 전까지 보류한다.

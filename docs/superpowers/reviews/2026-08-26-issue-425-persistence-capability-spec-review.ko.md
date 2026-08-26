# Issue #425 persistence capability 명세 7-Tier 검토

## 검토 범위와 기준

- 대상: [Issue #425](https://github.com/bluetape4k/clinic-appointment/issues/425)
- 저장소: `bluetape4k/clinic-appointment`
- 기준 ref: `origin/develop` / `5399ff63649f1cc78ae73f00d121c37195817fb8`
- 현재 검토 HEAD: `800aa0f9a6f526aeff759e71848a8cbf3d6967fe`
- 검토 artifact: `docs/superpowers/specs/2026-08-26-issue-425-persistence-capability-design.ko.md`
- 검토 방식: 독립 7-Tier lane 결과와 main session의 현재 source·plan·test read-back을 통합했다.
- 독자/언어: 구현자와 유지보수자를 위한 한국어 설계 검토 문서다.

## 판정

**PASS — P0=0, P1=0, P2=0, P3=0**

초기 검토에서 발견한 Spring bean 대체 계약의 모호성(P2)은 명세에 capability 교체를
public constructor 직접 주입으로 한정하고, Spring 자동 구성은 concrete 구현을 내부에서
조립하며 사용자 fake bean·`@Primary`·`@Qualifier` 계약을 제공하지
않는다고 명시해 해소했다. 기본 wiring은 `NotificationAutoConfigurationTest`로,
fake 대체는 wrapper 직접 주입 contract test로 검증한다.

## 7-Tier 결과

| 관점 | 결과 | 근거 |
|---|---|---|
| 성능 | PASS | bounded observation limit과 기존 `Dispatchers.IO`·caller transaction 경계를 유지한다. |
| 안정성 | PASS | fair cursor, eligible scope, lease fence, retry, retention 순서를 바꾸지 않는다. |
| 보안 | PASS | JDBC concrete 구현을 조립 경계에 남기고 tenant/clinic allowlist 의미를 유지한다. |
| 운영 | PASS | Spring 기본 후보와 연결을 회귀 검증하며 사용자 fake bean 우선순위 계약을 만들지 않는다. |
| 개발/API | PASS | work/observation port와 intentional JVM ABI migration, named-argument migration을 명시한다. |
| 사용자/Caller | PASS | public wrapper와 fixture가 capability를 사용하고 직접 fake 주입을 허용한다. |
| 통합/테스트 | PASS | constructor/source guard, capability delegation, Spring wiring, fixture/API variant 경계를 계획에 포함한다. |

## 수정 반영 확인

| 초기 finding | 반영 내용 | 재검증 |
|---|---|---|
| Spring fake bean·priority 계약 불명확 | constructor-only fake 주입, concrete 내부 auto-configuration, `NotificationAutoConfigurationTest` 기본 wiring을 명시 | spec section 1, 3.1, 4.4, acceptance/DoD read-back |
| trailing whitespace | build script 공백 제거 | `git diff --check` PASS |
| source guard configuration-cache 경고 | guard task에 `notCompatibleWithConfigurationCache` 선언 | fixture/task graph 실행 PASS |
| fake capability 호출 증거 부족 | work/observation fake delegate test 추가 | capability contract 4 tests PASS |
| named-argument source migration 누락 | `repository =` → `persistence =` migration을 명세/plan/README에 기록 | source call-site read-back |
| assertion/jar regex 불명확 | 실제 regex와 두 capability class pattern으로 수정 | plan command read-back |

## 증거

- baseline `:appointment-notification:test`: `BUILD SUCCESSFUL`.
- baseline `:appointment-api:compileTestKotlin` retry: `BUILD SUCCESSFUL`.
- RED contract: 기존 concrete constructor·waitlist overload·fixture import를 가리키는 3개
  assertion이 실패했고, 이를 최소 변경의 출발점으로 사용했다.
- GREEN capability contract: 4 tests, `BUILD SUCCESSFUL`.
- targeted lease/readiness/repository/waitlist regression: `BUILD SUCCESSFUL`.
- Spring default wiring regression: 대상 test, `BUILD SUCCESSFUL`.
- notification jar·consumer fixture·API variant·task graph: `BUILD SUCCESSFUL`.
- Korean terminology audit: 8 files, findings=0.
- 현재 migration SQL diff: 없음으로 유지한다.

## Writer DoD

- [x] SPW-01 — audience·purpose·source·identifiers·unknowns를 고정했다.
- [x] SPW-02 — 설계 경계·대안·호환성·실패 모드·수용 기준·DoD를 포함했다.
- [x] SPW-03 — 한국어 technical register와 exact code/command/API token을 보존했다.
- [x] SPW-04 — current source, Issue #425, #409 follow-up, plan과 claim을 대조했다.
- [x] SPW-05 — heading/table/code fence/link를 rendered read-back했다.

## Korean naturalness DoD

- [x] KO-01 — 사실·숫자·식별자·링크·불확실성을 보존했다.
- [x] KO-02 — 일반적인 효과 주장을 behavior와 test evidence로 대체했다.
- [x] KO-03 — 번역투와 중복 전환을 제거하고 직접 서술했다.
- [x] KO-04 — capability, persistence, transaction, ABI 용어를 일관되게 사용했다.
- [x] KO-05 — 유머·홍보성 표현·발명한 비유를 사용하지 않았다.
- [x] KO-06 — 제목·표·링크·코드 블록을 포함한 모든 독자 표면을 확인했다.
- [x] KO-07 — contextual terminology audit findings=0을 확인했다.

## 남은 범위

이 문서는 명세 검토만 다루며 implementation/plan review와 원격 CI·PR 상태는 별도
artifact에서 갱신한다. merge는 exact live head에 대한 fresh approval 전까지 수행하지 않는다.

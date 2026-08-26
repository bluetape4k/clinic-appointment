# Issue #425 persistence capability 구현 계획 7-Tier 검토

## 검토 범위와 기준

- 대상: [Issue #425](https://github.com/bluetape4k/clinic-appointment/issues/425)
- 저장소: `bluetape4k/clinic-appointment`
- 기준 ref: `origin/develop` / `5399ff63649f1cc78ae73f00d121c37195817fb8`
- 현재 검토 HEAD: `800aa0f9a6f526aeff759e71848a8cbf3d6967fe`
- 검토 대상: `docs/superpowers/plans/2026-08-26-issue-425-persistence-capability-plan.ko.md`
- 선행 명세: `docs/superpowers/specs/2026-08-26-issue-425-persistence-capability-design.ko.md`
- 독자/언어: 구현자와 리뷰어를 위한 한국어 실행 계획이다.

## 판정

초기 main review는 PASS였지만 독립 Step 3-R에서 traceability·rollback·schema gate·artifact
ordering P2 4건과 verification/evidence strength P3 2건을 발견했다. 계획에 최소 수리를
반영하고 source/document read-back을 다시 수행한 현재 판정은 다음과 같다.

**PASS — P0=0, P1=0, P2=0, P3=0**

계획은 capability contract RED→GREEN, wrapper와 waitlist 경계, consumer fixture와
Gradle guard, behavior regression, Spring 기본 wiring, 문서·7-Tier·PR hold를 순서대로
연결한다. 독립 검토에서 지적된 파일 책임 누락, rollback 명령 불명확성, migration diff
검증 강도, artifact 생성 순서, forbidden scan fail-fast, untracked evidence 누락을 계획에
반영했다. 실제 구현에서도 constructor/source contract, fake delegate, targeted lifecycle,
fixture/API variant와 task graph를 계획된 범위 안에서 검증했다.

## Traceability 검토

| 계획 영역 | 검증 결과 | 판정 |
|---|---|---|
| 요구사항·범위 | Issue URL, repo/base/head, 기준 SHA, module/dependency/schema 제외와 root dirty 경계를 고정 | PASS |
| 의존 순서 | baseline → RED → interface/repository → wrapper/waitlist → fixture/guard → behavior → docs/review → PR hold | PASS |
| 파일 책임 | capability file, JDBC repository, wrappers, waitlist, fixture/Gradle, tests, README와 durable artifacts를 명시 | PASS |
| 검증 명령 | module test/compile, lease/readiness/repository/waitlist, Spring wiring, fixture/API variant/task graph, jar/source/diff checks | PASS |
| 실패·복구 | compile, behavior, fixture, docs drift, CI별 rerun/rollback 조건을 기록 | PASS |
| 승인·side effect | PR 생성 target은 계획에 고정하고 merge·branch/worktree cleanup은 fresh approval 뒤로 보류 | PASS |
| 독립 Step 3-R disposition | P2 4건/P3 2건을 계획 수리로 해소하고 재검토 | PASS |

## 7-Tier 결과

| 관점 | 결과 | 확인한 계획 경계 |
|---|---|---|
| 성능 | PASS | bounded observation, 기존 `Dispatchers.IO`, sequential `--max-workers=1`, query/lock/round-trip scan |
| 안정성 | PASS | transaction·lease fence·retry·retention·readiness regression과 failure rerun |
| 보안 | PASS | concrete persistence 내부 조립, tenant/clinic scope 검증, migration/schema 불변 |
| 운영 | PASS | Spring 기본 wiring test, fake bean 우선순위 계약 배제, PR/merge hold |
| 개발/API | PASS | public constructor source/ABI migration, named-argument 갱신, fixture inventory/source guard |
| 사용자/Caller | PASS | capability parameter compile fixture와 direct fake injection test |
| 통합/테스트 | PASS | producer jar → fixture compile → variant/task graph 순서와 final verifier 명령 |

## 반영한 hygiene 수리

- 계획의 code fence를 정상 Markdown으로 read-back했다.
- Spring wiring test, API canary와 해당 exact file path를 파일 책임 지도에 추가했다.
- assertion source scan은 실제 regex를 사용하도록 고쳤다.
- forbidden scan은 match 시 실패하고 positive ecosystem scan은 `rg -q`로 강제하도록 고쳤다.
- jar scan은 `NotificationOutboxWorkPersistence`와
  `NotificationOutboxObservationPersistence`를 직접 포괄한다.
- migration committed/staged/worktree 세 상태를 `git diff --exit-code`로 검증하도록 고쳤다.
- rollback에 checkpoint SHA, path-scoped reverse diff, `git apply --check`, status/test 재검증을 추가했다.
- implementation review·lesson을 audit 전에 생성하도록 artifact ordering을 고쳤다.
- verifier에 `git status --short`와 committed/staged/worktree `diff --check`를 추가해
  untracked evidence 누락을 막았다.
- Spring default wiring을 확인하는 Step 7.3A를 추가했다.
- source guard task의 configuration-cache 비호환을 선언했다.
- named-argument migration을 Task 3.1에 명시했다.
- 미확정 placeholder 문자열을 계획 점검에서 제거했다.

## 실행 증거

- RED contract: 기존 concrete constructor·waitlist overload·fixture import가 실패하는
  3개 boundary assertion을 확인했다.
- GREEN contract: capability contract 4 tests, `BUILD SUCCESSFUL`.
- targeted regression: lease/readiness/repository/waitlist, `BUILD SUCCESSFUL`.
- Spring wiring: `NotificationAutoConfigurationTest` 대상 test,
  `BUILD SUCCESSFUL`.
- fixture/API: notification jar, consumer fixture, API variant, task graph,
  `BUILD SUCCESSFUL`.
- docs: Korean terminology audit 8 files findings=0, `git diff --check` PASS.
- independent Step 3-R: 초기 COMMENT(P2=4/P3=2) 후 위 수리 항목을 read-back했으며,
  추가 P0/P1/P2/P3 finding은 없다.

## Writer DoD

- [x] SPW-01 — 계획 독자·목적·source·식별자·미확정 항목을 기록했다.
- [x] SPW-02 — ordered task, exact file/action, expected evidence, tests, rollback, approval gate를 포함했다.
- [x] SPW-03 — 한국어 technical register와 명령·API token을 보존했다.
- [x] SPW-04 — 명세 acceptance와 implementation/test/fixture/PR task를 추적했다.
- [x] SPW-05 — Markdown code fence, table, link, heading을 read-back했다.

## Korean naturalness DoD

- [x] KO-01 — evidence와 exact identifier를 보존했다.
- [x] KO-02 — 추상적 효율성 주장을 검증 명령·결과로 제한했다.
- [x] KO-03 — 단계와 조건을 직접 동사로 서술했다.
- [x] KO-04 — capability·persistence·transaction·fixture 용어를 일관되게 유지했다.
- [x] KO-05 — 홍보성 표현과 발명한 비유가 없다.
- [x] KO-06 — 표·코드·링크·체크리스트 표면을 확인했다.
- [x] KO-07 — contextual terminology audit findings=0을 확인했다.

## 결론

계획은 구현·검증·문서·PR hold를 빠뜨리지 않으며 현재 명세와 source boundary에
traceable하다. 독립 검토의 P2/P3는 계획과 증거 강도를 보강해 해소했다. 구현 단계는
완료했으므로 다음 gate는 full module verification, implementation review, lesson, live PR
readiness다. merge는 fresh approval 전까지 진행하지 않는다.

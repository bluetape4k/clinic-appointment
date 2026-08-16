# 이슈 #334 solver planning fact version fence 워크플로 체크리스트

상태: `PR #347 OPEN / CI PENDING` — Task 1~4 구현과 module-wide
H2/PostgreSQL 검증, 독립 review, lesson, PR metadata parity를 완료했고
exact-head CI와 최신 머지 승인만 남아 있다.

## 분류와 범위

- 작업 유형: `Type-A / Full Feature`
- 근거: solver 결과 계약, Exposed transaction 경계, PostgreSQL 직렬화 동시성,
  planning fact 변경 무효화, 회귀 테스트와 Testcontainers 검증을 함께 변경한다.
- 포함: `appointment-solver`의 결과/적용 경로, planning fact canonical hash,
  H2 회귀 테스트, `PostgreSQLServer.Launcher.postgres` 기반 동시성 테스트,
  한국어 설계·계획·lesson 문서.
- 제외: master-data HTTP API 신설, Flyway 스키마 변경, 배포 canary, `@Testcontainers`,
  raw `GenericContainer`, 운영 데이터 변경.
- 적용 스킬: `bluetape-workflow`, `bluetape-full-feature`,
  `bluetape-kotlin-patterns`, `ecc-kotlin-exposed`, `bluetape-writer`.

## Router/receipt 게이트

- [x] WF-01 — Type-A 분류를 이슈 #334와 복잡도 신호에 연결했다.
- [x] WF-02 — 사용자에게 ordered Type-A 실행계획을 공개하고 승인받았다.
- [x] WF-03 — 초기 범위/계획 승인과 설계 3번 승인을 receipt에 기록했다.
- [x] WF-04 — router, leaf, Kotlin/Exposed, writer 지침을 읽었다.
- [x] WF-05 — `requirements-design -> implementation -> verification -> delivery`
      topology를 main lane에 등록했다.
- [x] WF-06 — main lane startup ack와 mutation-check가 현재 run에 대해 통과했다.

증거: receipt run `20260816T131339Z-92451251`, topology sequence `7`,
worktree `fix/issue-334-solver-fact-version-fence`, base `90e50da`.

## 공통 게이트

- [x] CL-01 — 이 checklist를 첫 설계 문서 mutation 전에 인스턴스화했다.
- [x] CL-02 — 적용/조건부/N/A 범위와 제외 surface를 기록했다.
- [x] CL-03 — 설계 문서 사용자 review/승인 후 implementation plan 순서를 지켰다.
- [x] CL-04 — 각 단계에서 command, path, result를 즉시 기록한다.
- [x] CL-05 — 설계/계획/테스트/CI pending 항목은 unchecked로 fail-closed 유지한다.
- [x] CL-06 — receipt 또는 테스트 증거가 stale하면 해당 단계와 downstream proof를 재실행한다.
- [x] CL-07 — PR/merge/branch 삭제 같은 외부 side effect 직전에 authority와 exact head를 재검증한다.
- [ ] CL-08 — 완료 시 `Required checks: X/Y; N/A: N; Blocked: N`을 계산한다.
- [x] CL-09 — PR 전 재사용 가능한 lesson을 기록하고 검토한다.
- [x] CL-10 — final review, fresh verification, scoped commit을 pre-PR에 수렴한다.

## Type-A 산출물 게이트

- [x] A-01/A-02 — 요구사항·research source ledger와 설계 문서 자체 review를 완료했다.
- [x] A-03 — 설계 문서 사용자 승인을 받았다.
- [x] A-04 — 구현계획 작성·검토·사용자 승인을 받았다.
- [x] A-05 — 동시성/직렬화/성능·안정성 risk prediction을 작성했다.
- [x] A-06 — RED/GREEN 테스트 우선 구현.
- [x] A-07 — targeted 및 module verification.
- [x] A-08 — 독립 final code review와 lesson.
- [ ] A-09 — PR/CI/merge-ready 보고.

## Kotlin/Testcontainers 계약

- [x] KT-01 — 변경된 공개/내부 Kotlin 계약과 KDoc을 한국어로 점검한다.
- [x] KT-02 — Exposed 읽기·검증·적용이 명시적 transaction 경계를 유지한다.
- [x] KT-03 — 기존 appointment CAS, rollback, pinned 의미론을 보존한다.
- [x] KT-04 — H2에서 planning fact 추가/수정/삭제 회귀를 검증한다.
- [x] KT-05 — PostgreSQL은 `PostgreSQLServer.Launcher.postgres` singleton만 사용한다.
- [x] KT-06 — `@Testcontainers`/`GenericContainer`를 사용하지 않고, Docker/Colima
      bind-mount 오류를 skip으로 처리하지 않는다.
- [x] KT-07 — real DB/concurrency 테스트는 순차 실행하고 serialization conflict를 판정한다.
      PostgreSQL 동시성 시나리오는 appointment lock/CAS stale 수렴을 검증했고,
      planning-fact 변경은 별도 snapshot hash mismatch로 검증했다.

## Writer/SPW 게이트

- [x] SPW-01 — 대상 독자와 결정 질문을 문서 첫 부분에 명시했다.
- [x] SPW-02 — 문제, 선택지, 계약, 실패모드, 테스트, DoD 구조를 완성했다.
- [x] SPW-03 — 한국어 자연스러움 KO-01..06 자체 검토를 수행했다.
- [x] SPW-04 — 각 핵심 주장을 issue/source path/test evidence에 연결했다.
- [x] SPW-05 — 저장 후 read-back, `git diff --check`, 미완료 표식 scan을 통과했다.

## 외부 side effect 조건부 게이트

- [x] CG-11 — PR 대상 repository/base/head authority를 재확인한다.
- [ ] CG-12 — exact head를 push하고 remote SHA를 확인한다.
- [x] CG-13 — 한국어 PR body와 `## DoD Status` metadata parity를 확인한다.
- [ ] CG-14 — exact-head CI, review, thread, artifact를 fresh read 한다.
- [ ] CG-15 — merge-ready report를 작성한다.
- [ ] CG-16 — merge 전 새 사용자 승인을 받는다.
- [ ] CG-17 — 사용자가 지정한 rebase merge 후 ancestry를 검증한다.
- [ ] CG-18 — `develop` local sync와 merged worktree/branch 정리를 검증한다.
- [ ] CG-X01 — tag/release/dispatch/publication/deletion은 이슈 범위 밖이며 실행하지 않는다.

## 추적성 ledger

| 주장/결정 | 근거 |
|---|---|
| 결과가 appointment version만 fence한다 | `appointment-solver/.../service/SolverService.kt`, `SolverResult.kt` |
| snapshot이 mutable planning fact를 읽는다 | `SolverService.loadSnapshot`, `ScheduleSolution` |
| 기존 appointment CAS/rollback/pinned을 보존해야 한다 | `SolverServiceTest.kt` stale/CAS/rollback/pinned 회귀 테스트 |
| PostgreSQL singleton launcher를 사용한다 | `bluetape4k-testcontainers`의 `PostgreSQLServer.Launcher.postgres`, sibling test pattern |
| baseline이 깨끗하다 | `./gradlew :appointment-solver:test --no-build-cache --no-daemon` — 10 suites / 98 tests, skipped 0, failures 0, errors 0 |
| planning fact hash가 안정적이다 | `PlanningFactVersionHasherTest` 4개와 `SolverServiceTest` 12개 fact mutation 회귀 |
| PostgreSQL 동시성 경계가 재현된다 | `SolverServicePostgresConcurrencyTest` 2개, `PostgreSQLServer.Launcher.postgres`, Colima Docker |
| 독립 review와 재사용 lesson이 남아 있다 | `docs/review/2026-08-16-issue-334-solver-fact-version-fence-step-6r-code-review.md`, `docs/lessons/2026-08-16-issue-334-solver-fact-version-fence.md` |

## 현재 stop condition

TDD RED/GREEN 구현과 module-wide 검증, 독립 review/lesson, PR metadata parity가
완료됐다. 코드 기준 정적 검사와 `git diff --check`도 통과했다. PR #347은 exact-head
CI를 진행 중이며, CI/review/thread를 fresh read한 뒤 최신 사용자 승인 없이는 merge하지
않는다. 현재 계획 경로는
`docs/superpowers/plans/2026-08-16-issue-334-solver-fact-version-fence-plan.md`다.

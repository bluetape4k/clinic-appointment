# Issue #313 최종 diff inline review

## 검토 범위

- 대상 diff: `git diff origin/develop...HEAD`
- 이슈: [#313](https://github.com/bluetape4k/clinic-appointment/issues/313)
- 검토 ref: `feat/issue-313-jdbc-caffeine-pilot`, review 시작 HEAD `a160756c`
- 변경 파일: 17개, 2,072 insertions; `*/src/main/**` 변경 0개
- 사용자 요청: 독립 리뷰 대신 main session inline review
- 생산 범위: `appointment-api/build.gradle.kts:48-49`의
  `testImplementation(libs.exposed.jdbc.caffeine)`와 benchmark task만 추가했고,
  `appointment-api/src/main`, `appointment-core/src/main`, Spring bean graph,
  Flyway, frontend는 변경하지 않았다.

## Six-lens findings

| Lens | 심각도 | 근거 | disposition |
|---|---|---|---|
| Performance | P2 | H2 반복 측정에서 candidate가 4개 latency p50 모두 높다. `Benchmark.kt:49-151`은 hot-hit, cold-fill, invalidation, cold-start를 baseline/candidate에 대칭 적용하고 `:167-199`에서 warm-up/measurement를 분리한다. | 결함이 아니라 **HOLD** 근거로 보존. PostgreSQL·실제 트래픽 측정은 후속 범위. |
| Stability | N/A | `Fixture.kt:81-109`은 Exposed root transaction 안에서만 stage하고 `maxAttempts=1`을 고정한다. `:129-131`에서 `TransactionManager.closeAndUnregister`를 호출하며 raw sleep/thread/Testcontainers를 새로 추가하지 않았다. | PASS |
| Security | N/A | 실제 `EffectivePolicyCacheKey`가 `tenantGroupId`와 `clinicId`를 포함하고(`Fixture.kt:66-74`), report는 `rawPayloadIncluded=false`다(`Benchmark.kt:216-217`, `provenance.json`). secret·token·Entity·transaction object를 report/cache payload에 넣지 않는다. | PASS |
| Operations | P2 | chart와 provenance가 `sourceCommit`, 실행 명령, `productionSloEvidence=false`를 기록한다. 다만 production DB, multi-node fence, SLO와 운영 rollback은 검증하지 않았다. | 운영 도입 금지와 후속 evidence 조건으로 명시. |
| Developer/API | N/A | fixture와 benchmark 선언은 `internal`/test source에 한정되고 새 Exposed import는 `org.jetbrains.exposed.v1.*` 계열이다(`Fixture.kt:10-19`). production public API는 추가하지 않았다. | PASS; 공개 API 검토는 N/A(변경 없음). |
| User/caller | N/A | `PilotTest.kt:16-101`이 commit, rollback, generation conflict, local fence, tenant/clinic 격리, miss 재사용 방지, toggle OFF를 직접 검증한다. chart는 단위·방향·p50/p95·HOLD를 함께 보여 준다. | PASS |

### Severity summary

- P0: 0
- P1: 0
- P2: 2건(대표성/운영 evidence 부족, measured candidate 비용 증가)
- P3: 0
- N/A: 4건(생산 API·보안·수명·caller contract의 test-only 범위)

P2는 현재 이슈가 명시한 파일럿 경계를 벗어나므로 production adoption blocker로
분류하지 않는다. 대신 `summary.ko.md:27-35`의 HOLD 결론과 후속 조건을 유지한다.

## Contract and hygiene read-back

- `rg '@Testcontainers|Thread\\.sleep|newSingleThreadExecutor|Thread\\('`를 새
  fixture/benchmark에 실행한 결과는 allocation probe의
  `Thread.currentThread()` 한 곳뿐이다. 새 raw thread, sleep, Testcontainers는 없다.
- `git diff --name-only origin/develop...HEAD -- '*/src/main/**'`는 출력이 없다.
- lockfile의 새 artifact는 `testCompileClasspath,testRuntimeClasspath`에만 있고
  production/runtime configuration으로 승격되지 않았다.
- chart validator는 schema, benchmark family, sourceCommit, profile matrix,
  `productionSloEvidence=false`, `rawPayloadIncluded=false`를 fail-closed로
  검사한다(`generate-issue313-jdbc-caffeine-chart.mjs:63-112`).
- PNG는 3200×2440, opaque, margin/occupancy/asset pair 감사를 통과했고 SVG
  semantic ledger는 node 8, comparison edge 4, branch/loop 0이다.

## Inline review verdict

**PASS — P0/P1 없음.** Issue #313 구현은 test-only 계약 파일럿으로 안전하게
격리되어 있고, `HOLD` 판정은 측정값과 대표성 한계를 함께 보존한다. 운영 캐시
교체를 의미하는 production wiring이나 공개 API를 이 diff에서 승인하지 않는다.

## SPW-01~05

- **SPW-01 PASS:** 최종 diff, 이슈, source paths, benchmark/chart provenance와
  미검증 production claims를 명시했다.
- **SPW-02 PASS:** review scope, line evidence, severity, disposition, gaps와
  verdict를 six-lens 표로 완결했다.
- **SPW-03 PASS:** 한국어 technical register, 일관된 용어와 exact identifiers를
  유지했다.
- **SPW-04 PASS:** line evidence, diff path audit, test/build/chart 결과를 다시
  대조했다.
- **SPW-05 PASS:** Markdown 표와 코드 인용을 read-back했고 terminology audit
  findings 0을 기록한다.

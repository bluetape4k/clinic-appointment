# Issue #313 JDBC Caffeine 정책 기준 데이터 파일럿 lesson

## Context

기존 `EffectivePolicyCache`를 `bluetape4k-exposed-jdbc-caffeine:1.12.1` 경로로
교체할 근거가 있는지 확인해야 했다. production cache/service/bean wiring은
변경하지 않고, `appointment-api/src/test`의 H2 JDBC root transaction fixture로
계약과 비용을 먼저 확인했다.

## Decision

이번 변경은 운영 도입이 아니라 test-only 계약 파일럿으로 제한한다. 후보는
commit 뒤에만 게시하고 rollback이면 버리며, generation conflict와 clinic local
fence가 있는 오래된 miss를 거부해야 한다. tenant/clinic key를 분리하고 one-shot
miss token을 재사용하지 않는다. benchmark와 chart는 `productionSloEvidence=false`
를 강제하고, production DB·멀티노드·실제 SLO 증거가 없으면 `HOLD`로 남긴다.

## Outcome

- 계약 테스트 6개가 commit-only publication, rollback discard, generation conflict,
  local fence, tenant/clinic 격리·miss 재사용 방지, pilot OFF를 통과했다.
- JavaExec benchmark가 warm-up 5회와 measurement 20회를 사용해 4개 프로필을
  baseline/candidate로 비교했다. candidate latency p50은 hot-hit, cold-fill,
  invalidation, cold-start 모두 baseline보다 높았고 allocation도 증가했다.
- 동일 JSON source에서 한국어 SVG/PNG chart, normalized data, semantic ledger,
  provenance와 summary를 생성했다. chart는 방향·단위·p50/p95·HOLD·대표성 한계를
  함께 표시한다.
- 최종 판정은 **HOLD**다. PostgreSQL 대표성, 멀티노드 경합, 장애/재시도, 실제 SLO를
  확인하기 전에는 production `EffectivePolicyCache` 교체를 진행하지 않는다.

## Fresh verification

| 증거 | 결과 |
|---|---|
| `:appointment-core:test --tests '*EffectivePolicyCacheTest'` | 6/6 PASS |
| `:appointment-api:test --tests '*EffectiveSchedulingPolicyServiceTest'` | 7/7 PASS |
| `:appointment-api:test --tests '*JdbcCaffeineEffectivePolicyPilotTest'` | 6/6 PASS |
| `:appointment-api:build --no-daemon -Djunit.jupiter.execution.parallel.mode.classes.default=same_thread` | `BUILD SUCCESSFUL in 3m 30s` |
| `:appointment-api:build -x test` | `BUILD SUCCESSFUL` |
| chart/XML/semantic/geometry/endpoint/PNG/asset audits | 모두 PASS |
| `git diff --check`와 Korean terminology audit | PASS, findings 0 |

전체 build를 기본 클래스 병렬 모드로 처음 실행했을 때 기존
`EquipmentUnavailabilityControllerTest` 6건이 `403`과 기대 응답 불일치로 실패했다.
Docker/Colima 상태와 socket override는 정상이고, 해당 테스트 단독 실행은 feature와
`origin/develop`에서 각각 14/14 passing이었다. JUnit class 병렬성을 `same_thread`로
고정한 재실행은 성공했으므로 Issue #313 회귀로 분류하지 않고 테스트 harness의
전역 상태 상호작용으로 기록한다.

## Surprise and guard

첫 RED 실행은 fixture symbol 부재뿐 아니라 dependency verification metadata와
`testRuntimeClasspath` lock이 없어서 중단됐다. `--write-verification-metadata sha256`
와 Gradle resolver 기반 `--write-locks`로 실제 결과를 생성한 뒤에야 의도한 RED가
드러났다. 다음 test-only dependency pilot에서는 처음부터 다음 guard를 적용한다.

1. catalog 추가 직후 compile/runtime lock과 verification metadata를 함께 생성한다.
2. production/runtime classpath read-back으로 dependency leakage를 확인한다.
3. fixture 없는 RED를 먼저 실행해 dependency/lock 실패와 contract 실패를 분리한다.
4. benchmark report의 `sourceCommit`, 환경, unsupported flags를 chart provenance에
   전달하고, report를 갱신하면 chart를 다시 생성한다.
5. API 전체 build는 기본 병렬 실행과 순차 실행 결과를 구분해 기록하며, 전역 상태
   상호작용을 Issue #313 결함으로 오인하지 않는다.

## Verifier DoD mapping

| ID | 요구 | 증거 |
|---|---|---|
| A-VER-01 | 명세 요구사항 추적 | 설계/계획 Task 1~4, 계약 테스트 6개, benchmark 8 profile, chart summary |
| A-VER-02 | 계획·commit·명령 evidence | 계획 checkbox, commits `25d51d55`, `e94be58a`, `311befc3`, `30bc82af`, `a160756c`, fresh Gradle 출력 |
| A-VER-03 | diff와 production boundary | `git diff --stat`, `*/src/main/**` 0개, test-only lock scope |
| A-VER-04 | public API와 문서 mapping | production public API 변경 없음, test-only fixture/benchmark와 Korean summary/provenance |
| A-VER-05 | rollback·generation·fence·lifecycle·tenant 위험 | `JdbcCaffeineEffectivePolicyPilotTest.kt:16-101`, fixture cleanup와 `maxAttempts=1` |
| A-VER-06 | current HEAD/module/command/result | feature branch `a160756c` 기준, `:appointment-api` targeted/build 결과 |
| A-VER-07 | production gap와 disposition | `summary.ko.md:27-35`, `productionSloEvidence=false`, 최종 **HOLD** |

## SPW-01~05

- **SPW-01 PASS:** context, 독자, 이슈, source, dependency/version, unknowns를
  고정했다.
- **SPW-02 PASS:** lesson 계약인 context, decision, outcome, verification,
  surprise, future guard를 모두 포함했다.
- **SPW-03 PASS:** 한국어 technical register와 승인 용어를 유지하고 수치·명령·API
  token을 보존했다.
- **SPW-04 PASS:** fresh test/build/chart output과 계획/summary/provenance를
  다시 대조했다.
- **SPW-05 PASS:** 이 문서를 read-back했고 Korean terminology audit findings 0을
  확인한다.

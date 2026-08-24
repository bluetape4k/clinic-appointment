# Issue #313 구현 계획 inline review

## 검토 범위와 기준

- 대상: `docs/superpowers/plans/2026-08-25-issue-313-jdbc-caffeine-pilot-plan.md`
- 이슈: [#313](https://github.com/bluetape4k/clinic-appointment/issues/313)
- 목적: 생산 `EffectivePolicyCache`를 변경하지 않고 JDBC Caffeine 경로의 계약,
  측정, 보류 조건을 계획대로 증명했는지 확인한다.
- 리뷰 방식: 사용자가 요청한 대로 독립 reviewer lane 없이 main session inline review로
  최종 diff와 산출물을 다시 읽었다.
- 기준 ref: `feat/issue-313-jdbc-caffeine-pilot` / 현재 HEAD `a160756c`

## Source ledger

| 주장 | source와 read-back |
|---|---|
| test-only dependency 경계 | `appointment-api/build.gradle.kts:47-50`, `appointment-api/gradle.lockfile`, `gradle/libs.versions.toml` |
| commit/rollback/fence 계약 | `JdbcCaffeineEffectivePolicyPilotFixture.kt:78-130`, `JdbcCaffeineEffectivePolicyPilotTest.kt:16-101` |
| 반복 측정과 unsupported claim | `JdbcCaffeineEffectivePolicyPilotBenchmark.kt:10-47,167-235` |
| chart 입력 검증과 provenance | `scripts/generate-issue313-jdbc-caffeine-chart.mjs:63-157`, `docs/benchmarks/issue-313-jdbc-caffeine-pilot/provenance.json` |
| production 경계 | `git diff --name-only origin/develop...HEAD -- '*/src/main/**'` 결과 공백, `appointment-api/build.gradle.kts:48-49` |

## Step 3-R 판정

| 영역 | 심각도 | 근거 | 판정 |
|---|---|---|---|
| 명세 범위 | N/A | production non-goal, commit-only publication, rollback, generation/fence, dependency boundary, benchmark/chart가 계획 Task 1~4와 Issue #313 범위에 연결된다. | PASS |
| 실행 순서 | N/A | catalog/lock → RED/GREEN fixture → JavaExec benchmark → JSON/chart → verifier 순서이며 각 산출물의 source path가 앞 단계 결과를 가리킨다. | PASS |
| Exposed API | N/A | 새 fixture의 import는 `org.jetbrains.exposed.v1.jdbc.*` 계열이고 `transaction(database)` 안에서만 `stageSnapshot`/`stageInvalidation`을 호출한다(`Fixture.kt:14-19,81-109`). | PASS |
| 성능·안정성 | P2 | H2 단일 JVM과 optional `ThreadMXBean` allocation만 측정한다(`Benchmark.kt:10-15,303-315`). 운영 DB 대표성은 증명하지 않으며 HOLD로 제한한다. | FOLLOW-UP, 결함 아님 |
| 문서·출처 | N/A | `summary.ko.md`, `benchmark.json`, `chart.data.json`, `chart.semantic.json`, `provenance.json`이 동일 report와 sourceCommit을 기록한다. | PASS |
| rollback | N/A | toggle OFF는 baseline만 사용하고 candidate는 비어 있다(`Fixture.kt:121-127`, `PilotTest.kt:92-101`). production source/bean wiring은 diff에 없다. | PASS |
| 시각 검증 | N/A | SVG/XML, semantic, endpoint, geometry, PNG opaque·full-size audit를 모두 통과했다. | PASS |

P0=0, P1=0. 남은 P2는 production 대표성의 후속 근거이며 이번 test-only 파일럿의
보류 결론과 일치한다. 계획을 수정해야 할 blocker는 없다.

## Fresh verification read-back

| 명령 | 결과 |
|---|---|
| `./gradlew :appointment-core:test --tests '*EffectivePolicyCacheTest' --no-daemon` | PASS, `tests=6 skipped=0 failures=0 errors=0` |
| `./gradlew :appointment-api:test --tests '*EffectiveSchedulingPolicyServiceTest' --no-daemon` | PASS, `tests=7 skipped=0 failures=0 errors=0` |
| `./gradlew :appointment-api:test --tests '*JdbcCaffeineEffectivePolicyPilotTest' --no-daemon` | PASS, `tests=6 skipped=0 failures=0 errors=0` |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :appointment-api:build --no-daemon` | 첫 실행은 기존 클래스 병렬 통합 테스트의 `EquipmentUnavailabilityControllerTest` 6건 응답 불일치로 실패했다. 변경 파일과 무관한 실패이며 단독 재실행은 feature와 `origin/develop` 모두 14 passing이었다. |
| 위 build + `-Djunit.jupiter.execution.parallel.mode.classes.default=same_thread` | PASS, `BUILD SUCCESSFUL in 3m 30s`, `26 actionable tasks` |
| `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./gradlew :appointment-api:build -x test --no-daemon` | PASS, compile/bootJar/Kover/package 경계를 확인했다. |
| `git diff --check` | PASS |

Docker는 `colima status`, `docker context show`, `docker info`로 healthy 상태를
확인했고, managed socket override를 적용했다. 병렬 build 실패를 숨기지 않고
sequential JUnit build를 최종 근거로 사용한다.

## SPW-01~05

- **SPW-01 PASS:** 독자(이슈 reviewer), 목적(test-only pilot 판정), source path,
  exact dependency/version, H2와 production SLO의 미검증 범위를 고정했다.
- **SPW-02 PASS:** review 계약에 범위, 근거, severity, disposition, gap, verdict와
  fresh command 결과를 포함했다.
- **SPW-03 PASS:** 한국어 기술 문체와 `정책 기준 데이터`, `보류(hold)`, `무효화`,
  `세대`, `local fence` 용어를 유지하고 명령·identifier·수치를 보존했다.
- **SPW-04 PASS:** 계획, source, benchmark report, chart provenance와 검증 출력의
  숫자·경로를 다시 대조했다.
- **SPW-05 PASS:** 이 문서를 처음부터 다시 읽었고 표·코드·링크 구조가 깨지지 않았다.
  Korean terminology audit는 findings 0으로 확인한다.

## Verdict

**PASS — 구현 계획과 검증 순서는 충족했다.** P0/P1 blocker는 없고, production
adoption은 benchmark summary와 동일하게 **HOLD**다. PostgreSQL 대표성,
멀티노드 경합, 실제 SLO는 별도 후속 Issue 근거 없이는 승격하지 않는다.

# Issue #394 7-Tier 검토

## 검토 대상

- 현재 tip: `refactor/issue-394-api-dependency`
- 기준 tip: `43f1d9b233711e71dbce483a61db821de5ead91e`
- 변경: `appointment-api/build.gradle.kts`, lockfile, README 2종, dependency boundary contract test

## 7-Tier 결과

| Tier | 판정 | 근거 |
|---|---|---|
| 성능 | PASS | API bootJar runtime classpath에서 Timefold/solver artifact를 제거해 불필요한 로딩·배포 footprint를 줄였다. |
| 안정성 | PASS | API main/test 전체 875건(3 skipped)이 통과했고 기존 endpoint source는 변경하지 않았다. |
| 보안/데이터 경계 | PASS | 불필요한 solver dependency와 취약점 대응 표면을 API runtime에서 제거했다. |
| 운영 | PASS | compile/runtime dependency graph와 bootJar `BOOT-INF/lib`를 직접 확인했다. |
| 개발자/API | PASS | public dependency metadata와 실제 API 호출자 목록을 일치시켰다. |
| 사용자/호출자 | PASS | API endpoint 동작과 내부 core/event/notification/messaging 경계를 유지했다. |
| 통합/테스트 | PASS | `bluetape4k-assertions` contract test 2건, check/build, bootJar 검증이 통과했다. |

## 증거

- compile artifact graph: `appointment-solver`, `timefold-solver-core/benchmark/jaxb` 없음
- runtime graph: `appointment-solver`, `timefold-solver-*` 없음
- bootJar: `BOOT-INF/lib`에 Timefold/solver jar 없음
- lockfile: compile-only 공통 `timefold-solver-bom` constraint만 남고 runtime scope artifact 없음
- blocker: P0=0, P1=0, P2=0, P3=0

## 판단

API에는 solver symbol 사용이 없으므로 public project dependency 제거가 경계를 바로잡는다.
향후 실제 solver 호출이 도입될 때는 API가 solver domain을 다시 공개하지 않도록 별도
application adapter 또는 worker 경계를 설계해야 한다.

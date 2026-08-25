# Issue #394 API 배포 dependency inventory

## 측정 범위

변경 전 `api(project(":appointment-solver"))`와 변경 후 API의 compile/runtime graph,
`bootJar` 내부 `BOOT-INF/lib`를 비교했다.

## 결과

| 대상 | 결과 |
|---|---|
| compile artifact graph | `appointment-solver`, `timefold-solver-core/benchmark/jaxb` 없음 |
| runtime graph | `appointment-solver`, `timefold-solver-*` 없음 |
| bootJar | Timefold/solver jar 없음 |
| API test | 875 통과, 3 skipped |

공통 `bluetape4k-dependencies`가 제공하는 compile-only `timefold-solver-bom` constraint는
catalog metadata에 남지만, solver 구현 artifact는 API compile/runtime/bootJar에 전파되지 않는다.

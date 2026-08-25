# Issue #394 API solver 의존성 제거 계획

## 목표

`appointment-api`가 실제로 호출하지 않는 `appointment-solver` public dependency와
Timefold runtime 전파를 제거하고, API compile·test·bootJar 경계를 회귀 테스트로 고정한다.

## 실행 순서

1. API main/test source에서 solver symbol 사용 여부와 현재 dependency metadata를 확인한다.
2. `api(project(":appointment-solver"))`를 제거하고 dependency lock을 재생성한다.
3. compile/runtime graph와 bootJar를 검사해 solver·Timefold artifact가 배포 경계에서 사라졌는지 확인한다.
4. `bluetape4k-assertions` 기반 metadata/README boundary contract test를 추가한다.
5. API 전체 test, check, build를 실행하고 7-Tier review 및 한국어 문서 audit을 남긴다.

## 보존할 계약

- API가 사용하는 core/event/notification/messaging 경계와 endpoint 동작은 변경하지 않는다.
- `bluetape4k-dependencies`가 제공하는 compile-time Timefold BOM constraint는 공통 catalog 계약이므로 유지할 수 있지만, solver 구현 artifact와 runtime 전파는 허용하지 않는다.
- 향후 solver endpoint/worker가 추가될 때는 API public API가 아닌 명시적 application boundary dependency로 재검토한다.

## 완료 기준

- API compile/test가 solver 없이 통과한다.
- API bootJar가 생성되고 `BOOT-INF/lib`에 Timefold/solver artifact가 없다.
- lockfile runtime scope와 reader-facing dependency inventory가 실제 경계와 일치한다.
- 7-Tier blocker P0/P1/P2/P3가 0/0/0/0이다.

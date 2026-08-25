# Issue #394 작업 교훈

## 재사용 우선 판단

`appointment-api`가 `appointment-solver`를 public dependency로 선언하고 있었지만,
main/test source에는 solver API 호출이 없었다. 따라서 새 adapter를 추가하지 않고
Gradle dependency graph, lockfile, bootJar inventory, reader-facing README를 하나의
boundary contract로 정렬하는 것이 가장 작은 수정이었다.

## 검증 교훈

- compile classpath에는 `bluetape4k-dependencies`의 공통 Timefold BOM constraint가 남을 수 있으므로 BOM과 runtime artifact를 구분해야 한다.
- lockfile은 generated output이므로 dependency 제거 뒤 `--write-locks`로 재생성하고, runtime scope에 남은 solver artifact를 별도 검사해야 한다.
- source-level `bluetape4k-assertions` contract test는 public dependency와 문서 inventory가 다시 어긋나는 회귀를 빠르게 잡는다.

## 후속 경계

향후 solver endpoint 또는 worker가 필요하면 API에 solver domain을 직접 공개하지 말고,
명시적인 application service/adapter 모듈에서 계약과 책임을 다시 검토한다.

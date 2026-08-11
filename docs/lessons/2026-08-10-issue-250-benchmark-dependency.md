# Issue #250 benchmark 의존성 경계 lesson

## 문제

messaging outbox/consumer benchmark가 실제로 사용하지 않는
`appointment-api` 애플리케이션에 의존하면서 web, security, solver,
notification까지 benchmark classpath에 전이됐다. 애플리케이션 packaging이나
전이 의존성이 바뀔 때 benchmark가 함께 깨질 수 있고, 측정 환경도 불필요하게
무거워진다.

## 결정

- benchmark의 production project 의존은 실제 호출 계약인
  `appointment-messaging`에서 시작한다.
- 애플리케이션 jar가 제공하는 resource를 얻기 위해 application project를
  의존하지 않는다. `sourceSets.main.resources.srcDir(...)`로 production Flyway
  resource를 명시적으로 재사용하고, migration tree 복제는 하지 않는다.
- 공유 resource 경계는 최신·구 migration 대표 파일을 classpath에서 읽는 계약
  테스트로 고정한다. 현재 `V1`과 `V25`가 격리 benchmark에 노출되는지 확인한다.
- Kotlin 테스트 assertion은 `io.bluetape4k.assertions` 표준을 사용한다. 저장소
  assertion 확장과 `kotlin.test`를 임의로 혼합하지 않는다.

## 재발 방지 guard

1. benchmark Gradle 변경 시 `runtimeClasspath`를 확인해 `appointment-api`와
   web/security/solver/notification 전이가 다시 들어오지 않는지 본다.
2. Flyway migration 위치를 바꾸면 resource contract 테스트와 실제 benchmark
   task를 같은 변경에서 실행한다.
3. benchmark 결과 계약 테스트는 report schema 검증과 resource 경계 검증을
   분리해 실패 원인을 쉽게 식별한다.
4. benchmark가 API 타입을 정말 사용하게 되는 경우에는 역의존을 되살리기보다
   messaging 전용 contract/artifact를 먼저 검토한다.

## 확인한 명령

- `:appointment-messaging-benchmark:test` — 4개 테스트 통과
- `:appointment-messaging-benchmark:mainSmokeBenchmark` — 성공
- `:appointment-messaging-benchmark:mainBenchmark` — 성공
- `:appointment-messaging-benchmark:dependencies --configuration runtimeClasspath`
  — API/web/security/solver/notification 전이 제거 확인
- `git diff --check` — 성공

## 범위 경계

이번 변경은 benchmark build graph와 local resource/assertion 계약에 한정한다.
remote CI/PR/merge, production benchmark capacity, deployment SLO는 이 lesson의
성공 조건이 아니며 별도 환경 증적이 필요하다.

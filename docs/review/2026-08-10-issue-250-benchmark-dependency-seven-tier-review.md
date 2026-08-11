# Issue #250 benchmark 의존성 7-tier 검토

검토일: 2026-08-10
검토 범위: `appointment-messaging-benchmark`
검토 기준: 7-tier code review, `$bluetape-kotlin-patterns`, Issue #250 완료 조건

## 변경 전 finding

`benchmark/appointment-messaging-benchmark/build.gradle.kts`는 실제 benchmark가
사용하는 `appointment-messaging` 대신 `appointment-api` 애플리케이션을 직접
의존했다. baseline `runtimeClasspath`에는 `appointment-api`와 함께
`appointment-core`, `appointment-event`, `appointment-solver`,
`appointment-notification`, Spring Boot web/security/validation/actuator 및
Timefold가 전이됐다. 이 결합은 benchmark가 측정하지 않는 애플리케이션 조립부의
변경을 benchmark compile/runtime 실패로 확장한다.

같은 파일의 Flyway resource 재사용은 API application jar 의존과 별개의 계약이다.
benchmark가 production migration을 사용해야 하므로 기존의 명시적
`sourceSets.main.resources.srcDir(rootProject.file("appointment-api/src/main/resources"))`
를 유지하고, migration tree를 복제하지 않았다.

`BenchmarkReportContractTest`의 `assertFailsWith`는 저장소 표준인
`io.bluetape4k.assertions.assertFailsWith` 대신 `kotlin.test`를 사용하고 있었다.

## 수정 결과

- `implementation(project(":appointment-api"))`를 제거했다.
- benchmark의 직접 runtime 경계를 `appointment-messaging` 및 benchmark 실행에
  필요한 라이브러리로 제한했다.
- PostgreSQL `V1__init_schema.sql`과 `V25__bind_appointment_replay_hash_to_partition.sql`
  resource가 격리된 benchmark classpath에 계속 존재하는지 계약 테스트를 추가했다.
- assertion을 `io.bluetape4k.assertions.assertFailsWith`로 교체하고
  `shouldNotBeNull` 확장 assertion을 사용했다.

수정 후 dependency graph는 `appointment-messaging -> appointment-event ->
appointment-core` 경로만 애플리케이션 프로젝트로 남기며 `appointment-api`,
web/security/validation/actuator, Timefold 전이가 사라졌다.

## seven-tier 판정

| Tier | 최종 판정 | P0/P1 | 근거 |
|---|---|---:|---|
| 1. 성능 | PASS. 불필요한 web/security/solver/notification classpath 로딩을 제거했다. | 0/0 | 수정 전·후 `runtimeClasspath` 비교, `mainSmokeBenchmark`, `mainBenchmark` |
| 2. 안정성 | PASS. API application packaging과 benchmark 실행 경계를 분리하고 migration resource만 명시적으로 유지했다. | 0/0 | resource contract 4번째 테스트, 모듈 test |
| 3. 보안·개인정보 | PASS. 보안 동작을 변경하지 않았고 benchmark에 불필요한 API security graph를 끌어오지 않는다. | 0/0 | dependency graph 및 변경 파일 범위 검토 |
| 4. 운영 | PASS. PostgreSQL production schema resource를 단일 원천으로 재사용하며 별도 migration tree drift를 만들지 않는다. | 0/0 | `sourceSets` 선언 유지, V1/V25 resource test |
| 5. 개발자/API | PASS. benchmark의 의존 방향이 실제 사용 계약(`appointment-messaging`)과 일치하고 assertion이 저장소 표준을 따른다. | 0/0 | Gradle diff, `$bluetape-kotlin-patterns` import/assertion 검토 |
| 6. 사용자·호출자 | PASS. benchmark report schema와 측정 대상 API는 변경하지 않았다. | 0/0 | `BenchmarkReportContract` 기존 3개 계약 테스트 통과 |
| 7. 통합·main-session | PASS. 이슈 완료 조건, 코드 변경, focused test, smoke/full benchmark, lesson이 같은 worktree 증적으로 연결된다. | 0/0 | workflow receipt 및 아래 검증 명령 |

최종 집계: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## 검증 증적

```text
./gradlew :appointment-messaging-benchmark:test \
  --no-daemon --no-configuration-cache --console=plain
4 tests passed; BUILD SUCCESSFUL

TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark \
  --no-daemon --no-configuration-cache --console=plain
BUILD SUCCESSFUL

TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
./gradlew :appointment-messaging-benchmark:mainBenchmark \
  --no-daemon --no-configuration-cache --console=plain
BUILD SUCCESSFUL in 2m 55s
```

baseline graph에는 `project ':appointment-api'`와 web/security/solver/notification
전이가 있었고, 수정 후 graph에는 `appointment-messaging`, `appointment-event`,
`appointment-core`만 애플리케이션 프로젝트로 남았다. `git diff --check`도 통과했다.

## 남은 경계

이 검토는 로컬 branch의 compile/test/benchmark와 dependency graph를 검증한다.
remote CI, PR 생성·리뷰, merge 및 실제 production benchmark capacity/SLO는 실행하지
않았으므로 별도 운영·통합 증적으로 남긴다.

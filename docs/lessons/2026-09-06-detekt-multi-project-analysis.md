# 멀티 프로젝트 detekt 실행 경로와 도구 classpath

## 배경

루트 프로젝트에만 `dev.detekt` 플러그인을 적용한 상태에서 CI가
`./gradlew detekt`를 실행했다. 루트에는 Kotlin 소스가 없으므로 작업은
`NO-SOURCE`로 성공했고, 실제 Kotlin 코드가 있는 하위 모듈은 분석되지 않았다.

detekt `2.0.0-alpha.6`은 Kotlin compiler `2.4.10`으로 빌드되지만 프로젝트의
Spring dependency management와 strict lock은 detekt 실행 configuration까지
Kotlin `2.4.0`으로 정렬했다. 하위 모듈에 플러그인만 적용하면 분석 시작 전에
도구 runtime 불일치로 실패하는 상태였다.

## 원인

- `withType<Detekt>()`는 이미 존재하는 task만 설정하며 플러그인이나 task를
  새로 만들지 않는다.
- 루트 `detekt` task는 하위 프로젝트의 source를 자동으로 집계하지 않는다.
- 전역 BOM 정렬이 분석 대상의 compile classpath뿐 아니라 detekt 자체의
  `detekt` configuration에도 적용됐다.
- 기존 소스의 정적 분석 부채와 새 위반을 분리하는 baseline이 없었다.

## 결정

- `src/main/kotlin`이 있는 7개 하위 프로젝트에만 `dev.detekt`를 적용한다.
- 루트 `detekt` lifecycle task가 모듈 task와 `verifyDetektModuleCoverage`에
  의존하도록 해 기존 CI 명령을 유지한다.
- `detekt` configuration의 `org.jetbrains.kotlin` 의존성은
  `getSupportedKotlinVersion()`으로 고정한다. 애플리케이션 compile/runtime은
  기존 Kotlin `2.4.0`을 유지하고 분석 도구 runtime만 분리한다.
- 모듈별 baseline에는 현재 위반만 기록한다. `ignoreFailures`나 전역 suppress로
  검사를 무력화하지 않으며, baseline에 없는 새 위반은 즉시 실패시킨다.
- 공유 경로에 병렬로 쓰는 report merge task는 제거하고 각 모듈의 Checkstyle
  report를 CI artifact로 수집한다.

## 검증

- 변경 전 `./gradlew detekt --rerun-tasks`는 `:detekt NO-SOURCE`로 성공했다.
- 변경 전 `verifyDetektModuleCoverage`는 7개 Kotlin 소스 모듈을 모두 누락으로
  보고하며 실패했다.
- 변경 후 같은 coverage task와 7개 모듈 detekt task가 모두 실행됐다.
- 임시 `EmptyFunctionBlock` 위반을 `appointment-core`에 추가했을 때
  `:appointment-core:detekt`가 rule ID와 함께 실패했고, fixture 제거 후 통과했다.
- `dependencyInsight`에서 detekt configuration의 Kotlin compiler와 stdlib가
  `2.4.10`으로 잠겼음을 확인했다.

## 참고

- [detekt Gradle 플러그인](https://detekt.dev/docs/gettingstarted/gradle/)
- [detekt type resolution](https://detekt.dev/docs/gettingstarted/type-resolution/)
- [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)

## 후속 지침

멀티 프로젝트 정적 분석 도구를 추가할 때는 task 존재만 확인하지 말고 실제
소스 모듈 task의 실행 결과와 report를 확인한다. 분석 도구가 compiler에 결합된
경우 애플리케이션 BOM이 도구 configuration을 덮어쓰지 않는지
`dependencyInsight`와 lock diff로 검증한다.

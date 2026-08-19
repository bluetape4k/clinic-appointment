# Issue #310 Lettuce dependency resolution 교훈

## 배경

`bluetape4k-dependencies:1.4.0`과 `bluetape4k-lettuce:1.12.1`을 사용하는
`appointment-api`, `appointment-notification`에서 `lettuce-core`가
요청 버전 `7.6.0.RELEASE`가 아닌 `7.5.2.RELEASE`로 내려갔다. 두 모듈의
Redis cache와 leader election 경로가 같은 클라이언트 그래프를 공유하므로,
새 Redis 명령을 도입하지 않고 실제 해석 결과만 정렬하는 것이 목표였다.

근거는 다음과 같다.

- [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml)은 기존에
  `lettuce-core` 버전을 `spring-boot4-dependencies` BOM에 맡겼다.
- [`build.gradle.kts`](../../build.gradle.kts)은 `bluetape4k-dependencies`
  BOM과 Spring Boot BOM을 함께 import한다.
- `./gradlew :appointment-api:dependencyInsight --dependency lettuce-core --configuration runtimeClasspath`
  와 notification의 동일 명령은 `7.5.2.RELEASE` 선택 및
  `7.6.0.RELEASE -> 7.5.2.RELEASE` conflict를 재현했다.
- [`bluetape4k-lettuce:1.12.1 POM`](https://repo1.maven.org/maven2/io/github/bluetape4k/bluetape4k-lettuce/1.12.1/bluetape4k-lettuce-1.12.1.pom)은
  `lettuce-core:7.6.0.RELEASE`를 요청한다.

## 원인과 결정

Spring Boot BOM이 제공하는 `lettuce-core:7.5.2.RELEASE` 관리 constraint가
dependency-management plugin의 선택 규칙으로 적용됐다. BOM import 순서를
Spring Boot 우선으로 바꾸는 실험도 네 그래프에서 `7.5.2.RELEASE`를 유지했으므로,
import 순서에 의존하는 해결책은 채택하지 않았다.

`gradle/libs.versions.toml`에 다음 override를 추가했다.

```toml
lettuce-core = "7.6.0.RELEASE"
lettuce-core = { module = "io.lettuce:lettuce-core", version.ref = "lettuce-core" }
```

이제 catalog가 Spring Boot가 제공하는 낮은 버전보다 실제 bluetape4k artifact가
요구하는 버전을 명시한다. 모듈별 직접 버전 선언이나 새 dependency는 추가하지
않았고, BOM import 순서는 원래대로 유지했다.

## 검증 결과

- RED: override 전 `scripts/verify-dependency-1.4.0.sh`가
  `lettuce-core-api-runtime`에서 `7.5.2.RELEASE`를 선택하고 exit 1로 종료했다.
- GREEN: 같은 스크립트에 API/notification의 `runtimeClasspath`와
  `testRuntimeClasspath` 네 검사를 추가한 뒤 네 그래프 모두
  `io.lettuce:lettuce-core:7.6.0.RELEASE`를 선택했다.
- 전체 dependency contract가 `[PASS] bluetape4k-dependencies 1.4.0 dependency contract`로
  종료했다. `dependencyInsight` 출력에는 Spring Boot의
  `7.5.2.RELEASE -> 7.6.0.RELEASE` 요청도 남아 선택 근거를 재현할 수 있다.
- `./gradlew :appointment-api:test :appointment-notification:test --rerun-tasks
  --no-daemon --console=plain`은 Redis Testcontainers singleton을 사용하는
  NearCache, notification leader auto-configuration, connection lifecycle 및
  기존 API 통합 경로를 포함해 `BUILD SUCCESSFUL in 3m 24s`로 끝났다.
- 저장소에는 Gradle lockfile이나 dependency verification metadata가 없으므로,
  이번 변경은 기존 CI 계약 스크립트와 `dependencyInsight`를 재현 가능한 근거로
  사용한다. 전역 lock 도입은 이 좁은 버그 수정 범위를 넘는다.

## Redis 호환성 경계

현재 테스트는 `io.bluetape4k.testcontainers.storage.RedisServer.Launcher.redis`를
사용하며 저장소 안에서 Redis image tag를 고정하지 않는다. 따라서 이번 실행은
Redis 7.2와 8.8 각각의 명시적 matrix 검증으로 해석하지 않는다. 기존 코드가
Redis 8.8 전용 Array/INCREX/XNACK 명령을 호출하지 않는 것도 확인했으며, 해당
명령을 도입하는 작업은 별도 기능 이슈로 분리해야 한다.

7.2/8.8 image를 고정한 cache, `LettuceLeaderGroupElector`, Lua fallback,
connection release/close matrix는 후속 호환성 작업의 범위다. 이 문서의 현재
결론은 두 모듈의 client graph를 7.6.0으로 정렬했으며, Redis server major
호환성을 새로 보장했다는 뜻이 아니다.

## 놓칠 뻔한 점과 다음 작업의 규칙

- BOM import 순서만 바꾸면 constraint 충돌이 사라질 것이라고 가정하지 않는다.
  실제 `dependencyInsight`의 선택 버전과 conflict reason을 먼저 확인한다.
- artifact가 BOM보다 높은 버전을 요구하면 catalog의 override와
  `version.ref`를 같은 변경에 추가하고, 두 모듈의 runtime/test graph를 CI
  dependency contract에 함께 고정한다.
- Redis server 버전 matrix가 필요하면 기존 singleton launcher를 우회하지 말고,
  허용된 launcher 설정으로 7.2와 8.8을 각각 기동하는 별도 테스트/이슈를 만든다.

## 롤백

이 변경을 되돌릴 때는 catalog의 `lettuce-core` version entry와
`version.ref`를 제거하면 된다. 그러면 dependency contract의 네 Lettuce 검사가
의도적으로 실패해 `7.5.2.RELEASE` 회귀를 즉시 드러낸다. 운영 배포나 Redis
데이터에는 변경을 가하지 않았다.

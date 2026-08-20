# Redis 8 고정 호환성 검증 설계

## 목표

`clinic-appointment`의 Redis 서비스 계약을 Redis 8 계열로 고정하고, 현재 배포된 `RedisServer.Launcher.redis` singleton을 사용해 API 캐시/NearCache와 notification의 Lettuce leader election이 실제 Redis에서 동작하는지 검증한다.

이번 작업은 Redis 7.2/8.8 이미지 매트릭스를 도입하지 않는다. `bluetape4k-testcontainers`의 현재 `RedisServer.Launcher.redis`는 `RedisServer.TAG = "8.8.1"`을 사용하므로 CI가 검증하는 이미지는 `redis:8.8.1`이다.

## 근거와 현재 상태

- `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/Containers.kt`는 이미 `RedisServer.Launcher.redis`를 지연 초기화해 사용한다.
- 현재 배포된 `RedisServer.Launcher.redis` 구현은 태그를 인자로 받지 않고 `RedisServer()`를 생성한다.
- `RedisServer.TAG`의 현재 값은 `8.8.1`이다.
- API의 `NearCacheWireCompatibilityTest`와 `CacheIntegrationTest`는 이미 `Containers.Redis.url`을 통해 실제 Redis를 사용한다.
- notification 모듈은 `LettuceLeaderGroupElector`를 자동 설정하지만 Redis 기반 통합 테스트와 `bluetape4k-testcontainers` 테스트 의존성은 없다.

## 설계

### 1. Redis 컨테이너 수명 주기

- API 테스트는 기존 `Containers.Redis`를 변경하지 않고 `RedisServer.Launcher.redis`를 계속 사용한다.
- notification 통합 테스트도 `RedisServer.Launcher.redis`와 `RedisServer.Launcher.LettuceLib`를 사용한다.
- raw `GenericContainer`, `@Testcontainers`, 별도 image tag fixture, 테스트마다 새 Redis 컨테이너를 생성하는 방식을 추가하지 않는다.
- singleton은 `ShutdownQueue`에 의해 JVM 종료 시 정리된다. 테스트가 만든 Lettuce connection은 테스트의 `finally` 블록에서 직접 닫는다.

### 2. API Redis 8 계약 테스트

다음 테스트를 추가한다.

`appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/test/RedisServerContractTest.kt`

- `Containers.Redis === RedisServer.Launcher.redis`로 API 테스트가 launcher singleton을 우회하지 않는지 확인한다.
- `Containers.Redis.dockerImageName`이 `redis:${RedisServer.TAG}`와 일치하는지 확인한다.
- `RedisServer.TAG`이 Redis 8 계열(`8.`)인지 확인해 의도하지 않은 기본 이미지 변경을 즉시 실패시킨다.

기존 `NearCacheWireCompatibilityTest`와 `CacheIntegrationTest`는 수정하지 않고 같은 singleton 경로를 계속 실행한다. 기존 Toxiproxy 종료 테스트는 네트워크 alias를 컨테이너 시작 전에 설정해야 하므로 dedicated `RedisServer()`를 유지하며, 이 설계에서 새 예외 경로를 추가하지 않는다.

### 3. Notification Lettuce 통합 테스트

`appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/RedisLeaderGroupCompatibilityTest.kt`를 추가한다.

테스트는 다음 순서로 실행한다.

1. `RedisServer.Launcher.redis`를 지연 초기화하고 image 이름이 `redis:${RedisServer.TAG}`인지 확인한다.
2. `RedisServer.Launcher.LettuceLib.getRedisClient()`로 Lettuce client를 얻고 connection을 연다.
3. `SCRIPT FLUSH`를 실행해 `EVALSHA` 캐시가 없는 상태를 만든다.
4. 실제 `LettuceLeaderGroupElector`로 leader action을 실행한다. 첫 호출은 `NOSCRIPT` 뒤 `EVAL` fallback을 거쳐 성공해야 한다.
5. action 결과를 확인하고 `activeCount`가 0, `availableSlots`가 `maxLeaders`로 복구되는지 확인해 release lifecycle을 검증한다.
6. 고유 lock name의 Redis key를 정리하고 connection을 닫은 뒤 `isOpen == false`를 확인한다.

이 테스트는 production auto-configuration을 재설계하지 않는다. 현재 `NotificationAutoConfiguration`이 사용하는 `StatefulRedisConnection`과 동일한 `LettuceLeaderGroupElector` 구현을 실제 Redis에 연결해 검증한다.

### 4. Gradle과 CI

- `appointment-notification/build.gradle.kts`에 테스트 전용 `libs.bluetape4k.testcontainers` 의존성을 추가한다.
- 기존 `ci.yml`의 `test-api`와 `test-notification` 전체 테스트 job이 새 테스트를 실행한다. Redis matrix job이나 별도 Docker 실행 스크립트는 추가하지 않는다.
- `nightly.yml`은 모듈 전체 테스트를 이미 실행하므로 workflow 구조를 변경하지 않는다.
- `appointment-api`의 기존 Testcontainers 의존성은 변경하지 않는다.

### 5. 문서와 Issue 계약

- Issue #360의 제목과 완료 조건을 `redis:8.8.1` 단일 검증으로 정정한다.
- `docs/lessons/2026-08-20-issue-360-redis-8-contract.md`에 고정 버전, launcher 선택 이유, 매트릭스와의 경계, 검증 결과를 기록한다.
- Redis 7.2/8.8 매트릭스와 전역 Gradle dependency locking은 각각 별도 후속 범위로 남기며 Issue #361은 수정하지 않는다.
- Redis 8 전용 Array/INCREX/XNACK 명령은 이 작업에 추가하지 않는다.

## 실패와 롤백

- `RedisServer.TAG`가 Redis 8이 아니면 계약 테스트가 실패한다. 지원 이미지가 변경될 때는 테스트와 이 문서를 함께 수정한다.
- Redis 컨테이너 기동, Lua fallback, leader release, connection close 중 하나라도 실패하면 CI가 실패하며 해당 결과를 PR에 기록한다.
- production 설정, lettuce/BOM 버전, 분산 lock 구현은 변경하지 않으므로 실패 시 새 테스트와 테스트 의존성만 되돌릴 수 있다.

## 설계 관점 검토

| 관점 | 결과 | 근거와 조치 |
|---|---|---|
| 성능 | N/A | production hot path를 변경하지 않고 테스트에서 기존 singleton과 단일 Lettuce connection만 사용한다. |
| 안정성 | PASS | `SCRIPT FLUSH` 후 action, slot release, key cleanup, connection close를 순서대로 검증하고 singleton 종료는 `ShutdownQueue`에 맡긴다. |
| 보안 | N/A | 인증·권한·직렬화 경계를 변경하지 않는다. 테스트 lock name은 고유 값을 사용하고 입력을 production 설정으로 전달하지 않는다. |
| 운영 | PASS | Redis 8.8.1을 지원 기준으로 명시하고, 다른 버전과의 matrix는 별도 이슈로 분리하며 실패 시 테스트·의존성만 롤백할 수 있다. |
| 개발자/API | PASS | `RedisServer.Launcher.redis`, `RedisServer.Launcher.LettuceLib`, 기존 API 테스트 경계를 재사용한다. 새 production API를 만들지 않는다. |
| 사용자/호출자 | PASS | Issue와 lesson에서 실제 검증 버전, 미지원 matrix, Redis 8 전용 명령의 별도 이슈 경계를 명시한다. |

통합 결과: P0=0, P1=0. N/A 관점은 변경 표면이 없어 별도 구현 작업으로 만들지 않는다.

## 완료 조건

- [ ] API가 `RedisServer.Launcher.redis` singleton을 사용하고 Redis 8 image contract를 검증한다.
- [ ] API cache/NearCache 기존 통합 테스트가 성공한다.
- [ ] notification이 Redis 8에서 `SCRIPT FLUSH` 후 Lua fallback과 leader release를 성공시킨다.
- [ ] connection close와 singleton lifecycle을 검증한다.
- [ ] `appointment-api:test`와 `appointment-notification:test`가 로컬 및 CI에서 성공한다.
- [ ] Issue #360, lesson 문서, PR 본문이 실제 단일 Redis 8.8.1 범위와 일치한다.

## 범위에서 제외한 대안

| 대안 | 제외 이유 |
|---|---|
| Redis 7.2/8.8 태그별 matrix fixture | 서비스 계약을 Redis 8로 고정한다는 결정과 현재 launcher API 제약에 맞지 않는다. |
| `bluetape4k-testcontainers` launcher API 확장 | 별도 저장소 릴리스와 의존성 승격이 필요해 이번 작업보다 범위가 크다. |
| CI `docker run` 또는 Compose | 저장소의 launcher/Testcontainers lifecycle과 테스트 endpoint가 달라진다. |

## 문서 검수 기록

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 | PASS | 현재 `Containers.Redis`, `RedisServer.TAG`, API/notification 테스트 표면을 기준으로 작성했다. |
| SPW-02 | PASS | 경계, lifecycle, 실패, 호환성, acceptance, DoD를 포함했다. |
| SPW-03 | PASS | 한국어 기술 문체와 `RedisServer.Launcher.redis`, 명령, 경로, 버전을 보존했다. |
| SPW-04 | PASS | Issue #360, 현재 launcher 소스, 모듈 build/test 구조와 대조했다. |
| SPW-05 | PASS | Markdown 제목·표·코드 토큰을 작성 후 다시 읽었다. |

# Issue #368 Redis 8.8 명시적 이미지 계약 lesson

## 배경

Issue #360의 현재 테스트 계약은 `bluetape4k-testcontainers:1.12.1`이 제공하는
`RedisServer.TAG = "8"`에 묶여 있었다. 이 상태에서는 API cache/NearCache와
notification leader·outbox 검증이 실제로 어느 Redis 이미지를 사용하는지 소스만
읽어서는 즉시 알기 어렵고, 태그가 바뀌어도 회귀 신호가 약하다.

Issue #368의 범위는 production Redis 설정이나 Lettuce/Spring/BOM 의존성을
변경하는 것이 아니라, 테스트에서만 `redis:8.8`을 명시하고 그 계약을 CI에서
실패 가능하게 만드는 것이다. Redis 버전 행렬, Redis 8 전용 명령, 새로운
locking/fencing 알고리즘, production rollout은 이 작업에서 제외했다.

## 결정

- API와 notification 테스트 소스에 각각 `Redis88Launcher`를 둔다.
- 런처는 bluetape4k `RedisServer(image = "redis", tag = "8.8")`를 lazy singleton으로
  시작하고 `ShutdownQueue`에 등록한다. raw `GenericContainer`와 `@Testcontainers`는
  사용하지 않는다.
- API의 공유 `Containers.Redis`와 notification leader/outbox 통합 테스트가 이
  런처를 사용한다. notification Lettuce client는 런처가 기동한 URL을 명시적으로
  사용한다.
- 계약 테스트는 이미지 이름 `redis:8.8`, 태그 `8.8`, 공유 singleton identity를
  고정한다. 기존 `test-api`와 `test-notification` CI job이 모듈 테스트를 실행하므로
  태그가 누락되거나 의도하지 않게 바뀌면 해당 회귀 검사가 실패한다.

## 구현 결과

- `appointment-api`에 `Redis88Launcher`를 추가하고 `Containers.Redis`의 기본
  launcher를 교체했다.
- `appointment-notification`에 같은 테스트 전용 계약을 추가하고 leader/Lua
  fallback 및 outbox concurrency 통합 테스트의 Redis client를 교체했다.
- API와 notification의 `RedisServerContractTest` 검증 태그를 `8.8`로 고정했다.
- 루트 `README.md`, `README.ko.md`에 테스트 이미지의 지원 범위, CI 회귀 경계,
  롤백 절차를 기록했다.

## 검증 결과

로컬 Docker는 Colima `default` context(`28.4.0`, Linux)에서 실행했고,
Testcontainers Docker socket override도 이미 셸에 설정되어 있었다. 테스트는
모듈 간 컨테이너 경합을 피하려고 순차 실행했다.

### 컴파일

```text
./gradlew :appointment-api:compileTestKotlin --no-daemon --console=plain
→ BUILD SUCCESSFUL

./gradlew :appointment-notification:compileTestKotlin --no-daemon --console=plain
→ BUILD SUCCESSFUL
```

### 실제 Redis 8.8 검증

```text
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.test.RedisServerContractTest" \
  --no-daemon --console=plain
→ SUCCESS: Executed 1 tests; BUILD SUCCESSFUL

./gradlew :appointment-notification:test \
  --tests "io.bluetape4k.clinic.appointment.notification.RedisLeaderGroupCompatibilityTest" \
  --no-daemon --console=plain
→ SUCCESS: Executed 1 tests; BUILD SUCCESSFUL

./gradlew :appointment-notification:test \
  --tests "io.bluetape4k.clinic.appointment.notification.NotificationOutboxRedisConcurrencyIntegrationTest" \
  --no-daemon --console=plain
→ SUCCESS: Executed 5 tests; BUILD SUCCESSFUL

./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.config.NearCacheWireCompatibilityTest" \
  --tests "io.bluetape4k.clinic.appointment.api.controller.CacheIntegrationTest" \
  --no-daemon --console=plain
→ SUCCESS: Executed 10 tests; BUILD SUCCESSFUL
```

위 검증은 API cache/NearCache round-trip과 Spring cache hit, notification의
leader lifecycle·Lua fallback·global/clinic admission·lease expiry·connection
복구 경로를 `redis:8.8`에서 실제로 실행했다.

## 운영 및 롤백

이 변경은 production Redis image, application configuration, dependency graph를
수정하지 않는다. 테스트 호환성 기준을 되돌려야 하면 두 모듈의
`Redis88Launcher`, 계약 테스트, `README.md`, `README.ko.md`, 이 lesson을 함께
이전 계약으로 복원하고 API·notification 모듈 테스트를 재실행한다. Redis 7.2/8.8
행렬이나 Redis 8 전용 명령을 추가하는 것은 별도 Issue와 별도 launcher 계약으로
분리한다.

## 문서 검수

| 항목 | 결과 |
|---|---|
| SPW-01 문제·범위·독자 명시 | PASS |
| SPW-02 구조와 근거 연결 | PASS |
| SPW-03 용어·명령 일관성 | PASS |
| SPW-04 결정·제외 범위 기록 | PASS |
| SPW-05 실제 검증 결과 기록 | PASS |
| KO-01 한국어 문장 자연스러움 | PASS |
| KO-02 기술 용어 일관성 | PASS |
| KO-03 명령·식별자 원문 보존 | PASS |
| KO-04 중복·번역투 최소화 | PASS |
| KO-05 독자별 정보 경계 | PASS |
| KO-06 근거와 주장 일치 | PASS |
| KO-07 롤백 안내 명확성 | PASS |

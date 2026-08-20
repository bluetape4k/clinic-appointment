# Issue #360 Redis 8 고정 계약 lesson

## 배경

`clinic-appointment`은 API 캐시/NearCache와 notification leader election에서
Redis를 공통으로 사용한다. 현재 해석된
`bluetape4k-testcontainers:1.12.1`의
`RedisServer.Launcher.redis`는 `RedisServer.TAG = "8"`을 사용하므로,
이 작업의 서비스 검증 기준은 `redis:8`이다.

## 결정

- API와 notification 테스트는 모두 `RedisServer.Launcher.redis` singleton을
  사용한다.
- API는 기존 `Containers.Redis`가 같은 singleton과 `redis:8` image 계약을
  유지하는지 검증한다.
- notification은 같은 Redis에서 `SCRIPT FLUSH` 이후 Lua fallback, leader
  release, Lettuce connection close를 검증한다.
- Redis 7.2/8.8 명시적 image matrix와 전역 Gradle dependency locking은 각각
  별도 후속 범위로 남긴다. 전역 lockfile 작업은 Issue #361의 범위다.

## 구현 결과

- `RedisServerContractTest`를 추가해 `Containers.Redis`와
  `RedisServer.Launcher.redis`의 identity, image 이름, `TAG = "8"`을 고정했다.
- `RedisLeaderGroupCompatibilityTest`를 추가해 실제 Redis 8에서 script cache를
  비운 뒤 leader action이 성공하고, active slot이 해제되며, connection이 닫히는
  경로를 확인했다.
- notification 테스트에 기존 bluetape4k Testcontainers 의존성을 추가했다.
- production 코드, 운영 배포 설정, 기존 toxiproxy 전용 `RedisServer()` fixture는
  변경하지 않았다. toxiproxy fixture는 시작 전에 network alias를 붙여야 하므로
  launcher singleton과 수명 계약이 다르다.

## 검증 결과

### TDD와 의존성 확인

처음에는 `RedisServer.TAG.startsWith("8.")`를 기대하는 RED 테스트를 실행했다.
실패 메시지는 `Expected <false> to be <true>, but was not.`이었고, 이를 계기로
해석된 jar를 `javap -constants`로 확인했다. `1.12.1`의 실제 상수는
`TAG = "8"`이므로 테스트와 문서를 정확한 계약으로 수정했다.

`dependencyInsight` 결과는 API와 notification 모두
`io.github.bluetape4k:bluetape4k-testcontainers:1.12.1`을 선택했다.

### 테스트 명령

```text
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.test.RedisServerContractTest" \
  --no-daemon --console=plain
→ SUCCESS: Executed 1 tests in 2.5s; BUILD SUCCESSFUL in 9s

./gradlew :appointment-notification:test \
  --tests "io.bluetape4k.clinic.appointment.notification.RedisLeaderGroupCompatibilityTest" \
  --no-daemon --console=plain
→ SUCCESS: Executed 1 tests in 4s; BUILD SUCCESSFUL in 13s

./gradlew :appointment-api:test --no-daemon --console=plain
→ SUCCESS: Executed 824 tests in 2m 44s (3 skipped); BUILD SUCCESSFUL in 2m 47s

./gradlew :appointment-notification:test --no-daemon --console=plain
→ SUCCESS: Executed 162 tests in 8.6s; BUILD SUCCESSFUL in 13s

./gradlew :appointment-api:compileTestKotlin \
  :appointment-notification:compileTestKotlin \
  --no-daemon --console=plain
→ BUILD SUCCESSFUL in 4s
```

모든 Testcontainers 검증은 Colima의 `default` Docker context에서 순차 실행했다.
두 모듈의 기존 캐시/NearCache와 notification 통합 테스트도 전체 모듈 실행에
포함되어 함께 통과했다.

문서와 변경 파일에는 `git diff --check`를 적용했고, 한국어 용어 검수는 다음
명령으로 통과했다.

```text
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  --series clinic-appointment --json \
  docs/superpowers/specs/2026-08-20-issue-360-redis-8-contract-design.md \
  docs/superpowers/plans/2026-08-20-issue-360-redis-8-contract-plan.md \
  docs/lessons/2026-08-20-issue-360-redis-8-contract.md
→ findings 없음
```

PR과 CI 링크는 브랜치 게시 후 이 문서에 추가한다.

## 다음 작업 경계

Redis 7.2/8.8 matrix, 전역 lockfile, 또는 Redis 8 전용
`Array`/`INCREX`/`XNACK` 명령을 추가하려면 별도 Issue와 launcher/API 계약이
필요하다. 이 작업은 `redis:8` 서비스 계약과 현재 Lettuce leader/cache 경로의
검증만 보장하며, 다른 Redis tag의 호환성을 보장하지 않는다.

## 문서 검수

| 항목 | 결과 |
|---|---|
| SPW-01 문제·범위·독자 명시 | PASS |
| SPW-02 구조와 근거 연결 | PASS |
| SPW-03 용어·명령 일관성 | PASS |
| SPW-04 결정·제외 범위 기록 | PASS |
| SPW-05 실제 검증 결과 기록 | PASS |

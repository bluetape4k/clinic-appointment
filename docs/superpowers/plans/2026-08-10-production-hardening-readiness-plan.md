# 운영 캐시 보안과 배포 증거 강화 구현 계획

> **에이전트 작업자:** 이 계획은 승인된 설계를 작업 단위별로 실행한다. 각 단계의 체크박스와 명령을 순서대로 수행하고, 실패한 명령은 원인을 기록한 뒤 다음 단계로 진행하지 않는다.

**목표:** `appointment-api` Redis near-cache를 운영 TLS/ACL 정책과 등록 강제 Fory v3 wire 계약으로 보강하고, v2 rollback 및 production evidence를 자동 검증 가능한 형태로 제공한다.

**아키텍처:** Redis URL 정책은 API 설정 경계의 순수 validator로 분리한다. 세 DTO 전용 ThreadSafeFory serializer와 명시적 `LettuceBinaryCodec`을 각 near-cache에 연결하고, 새 payload는 `clinic-*-v3`에만 기록한다. 운영 스크립트는 local evidence와 production evidence를 구분하고 v2 보존/rollback 절차를 runbook으로 고정한다.

**기술 스택:** Kotlin 2.3, Spring Boot 4, Lettuce NearCache, Apache Fory 1.5.0, LZ4, JUnit 5, bluetape4k assertions, MockK/Spring MockK 규칙, Redis singleton launcher, Bash와 Node.js 22+.

---

## 변경 파일과 책임

| 파일 | 책임 |
| --- | --- |
| `appointment-api/build.gradle.kts` | 명시적 `bluetape4k-io` serializer/compressor 의존성 추가 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/RedisCacheSecurityPolicy.kt` | `rediss`·host·ACL URI의 fail-closed 검증 |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt` | Redis 정책 주입, 등록 강제 Fory serializer, v3 codec/near-cache wiring |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfigSecurityTest.kt` | 정책 허용/거부와 Redis client wiring 회귀 |
| `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt` | 세 DTO의 독립 client round-trip, v3 raw-key 격리, serializer 경계 |
| `scripts/verify-cache-rollout-evidence.sh` | JSON evidence 형식, live gate, 선택적 threshold 검증 |
| `docs/runbooks/dependency-1.4.0-cache-migration.md` | v2→v3 canary, TLS/ACL, bounded clear, rollback 절차 |
| `docs/lessons/2026-08-10-production-hardening-readiness.md` | 로컬 검증 결과와 production-only PENDING 경계 기록 |
| `README.ko.md` | 캐시 migration runbook과 evidence validator 진입점 노출 |

새 파일을 추가하지 않고 기존 `CacheConfigSecurityTest.kt`에 정책 단위 테스트를 둔다. 정책은 외부 collaborator가 없는 순수 함수이므로 불필요한 mock을 만들지 않는다. collaborator를 격리해야 하는 테스트가 생길 때만 MockK/Spring MockK를 사용하며 Mockito 의존성이나 import는 추가하지 않는다.

## Task 1: Redis URL 보안 정책과 설정 경계

**Files:**

- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/RedisCacheSecurityPolicy.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt:33-40`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfigSecurityTest.kt`

- [ ] **Step 1: 정책 실패 테스트를 먼저 작성한다.**

  `CacheConfigSecurityTest`에 다음 케이스를 추가한다. 문자열 검증에는 `io.bluetape4k.support.requireNotBlank` 계열과 `io.bluetape4k.assertions.assertFailsWith`를 사용하고, 예외 메시지에 `secret` 또는 password가 포함되지 않는지 함께 확인한다.

  ```kotlin
  @Test
  fun `TLS가 꺼지면 local redis URL을 허용한다`() {
      val uri = RedisCacheSecurityPolicy().validate("redis://localhost:6379", requireTls = false)
      uri.scheme shouldBeEqualTo "redis"
      uri.host shouldBeEqualTo "localhost"
  }

  @Test
  fun `TLS가 켜지면 인증된 비 loopback rediss URL을 허용한다`() {
      val uri = RedisCacheSecurityPolicy().validate(
          "rediss://cache-user:cache-secret@cache.example.internal:6380",
          requireTls = true,
      )
      uri.scheme shouldBeEqualTo "rediss"
      uri.host shouldBeEqualTo "cache.example.internal"
  }

  @Test
  fun `TLS가 켜지면 plain URI와 local host를 거부하고 credential을 노출하지 않는다`() {
      val policy = RedisCacheSecurityPolicy()
      listOf(
          "redis://cache-user:cache-secret@cache.example.internal:6379",
          "rediss://cache-user:cache-secret@localhost:6380",
          "rediss://:cache-secret@cache.example.internal:6380",
          "rediss://cache-user@cache.example.internal:6380",
          "not a URI",
      ).forEach { url ->
          val failure = assertFailsWith<IllegalArgumentException> {
              policy.validate(url, requireTls = true)
          }
          failure.message.orEmpty().contains("cache-secret").shouldBeFalse()
      }
  }
  ```

- [ ] **Step 2: targeted test가 새 타입 부재로 실패하는지 확인한다.**

  실행:

  ```bash
  ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.CacheConfigSecurityTest"
  ```

  예상 결과: `RedisCacheSecurityPolicy`가 아직 없어 compilation failure가 발생한다. 이 실패를 확인하기 전에는 구현으로 넘어가지 않는다.

- [ ] **Step 3: 순수 정책 validator를 구현한다.**

  `RedisCacheSecurityPolicy`는 무상태 class로 두고 다음 순서로 구현한다.

  1. `url.requireNotBlank("redisUrl")`를 호출한다.
  2. `runCatching { URI.create(url) }.getOrElse { throw IllegalArgumentException("Redis URL is invalid") }`로 URI를 파싱하되 원본 URL을 예외 메시지에 넣지 않는다.
  3. `requireTls=false`이면 URI를 반환한다.
  4. `scheme.equals("rediss", ignoreCase = true)`, host 존재, `localhost`, `127.0.0.1`, `::1`, `[::1]` 및 loopback literal 거부를 검사한다.
  5. `userInfo`에서 첫 `:`의 앞뒤를 분리해 username/password가 모두 blank가 아닌지 검사한다. password 전체를 저장·로그하지 않는다.
  6. 실패는 `IllegalArgumentException`으로 통일한다.

  검증 조건은 외부 DNS를 조회하지 않고 URI의 명시적인 host/literal만 판단한다. 이 class에 logging을 추가하지 않는다.

- [ ] **Step 4: CacheConfig에 fail-closed policy를 연결한다.**

  `redisClient`의 인자를 다음처럼 확장한다.

  ```kotlin
  @Bean(destroyMethod = "shutdown")
  fun redisClient(
      @Value("\${spring.data.redis.url:redis://localhost:6379}") url: String,
      @Value("\${scheduling.cache.redis.require-tls:false}") requireTls: Boolean,
  ): RedisClient = RedisClient.create(
      RedisCacheSecurityPolicy().validate(url, requireTls).toString()
  )
  ```

  기본값은 `false`로 유지해 기존 singleton Redis 테스트와 local boot를 깨지 않는다. 운영 배포는 외부 설정에서 `scheduling.cache.redis.require-tls=true`와 인증된 `rediss://` URI를 함께 제공해야 한다.

- [ ] **Step 5: wiring regression과 보안 테스트를 통과시킨다.**

  `CacheConfig().redisClient("redis://localhost:6379", false)`가 생성한 client는 반드시 `shutdown()`으로 닫는다. TLS flag에서 invalid URL을 넣은 직접 wiring 호출은 같은 `IllegalArgumentException`을 내야 한다. 실행:

  ```bash
  ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.CacheConfigSecurityTest"
  ```

  예상 결과: 모든 security test PASS, 실패 메시지에 credential 없음.

- [ ] **Step 6: 첫 구현 단위로 커밋한다.**

  ```bash
  git add appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/RedisCacheSecurityPolicy.kt \
    appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt \
    appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfigSecurityTest.kt
  git commit -m "운영 Redis URL 검증을 fail-closed 경계로 고정한다" \
    -m "Constraint: local/test fallback은 유지하고 운영 flag에서만 TLS와 ACL을 요구한다.
Rejected: URL을 그대로 RedisClient에 전달하는 기존 경계는 loopback과 credential 누락을 검출하지 못해 제외했다.
Confidence: high
Scope-risk: narrow
Directive: 운영 profile은 require-tls와 rediss ACL URI를 함께 설정한다.
Tested: CacheConfigSecurityTest
Not-tested: 인증된 production Redis 연결"
  ```

## Task 2: 등록 강제 Fory serializer와 v3 near-cache wiring

**Files:**

- Modify: `appointment-api/build.gradle.kts:23-31`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt:1-90`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt`

- [ ] **Step 1: v3 namespace와 serializer 경계 테스트를 먼저 바꾼다.**

  기존 세 테스트의 제목과 remote key 변수를 v2에서 v3로 변경하고, 각 round-trip 뒤에 다음 조건을 추가한다.

  ```kotlin
  rawCommands.exists(v3Key) shouldBeEqualTo 1L
  rawCommands.exists(v2Key) shouldBeEqualTo 0L
  rawCommands.exists(v1Key) shouldBeEqualTo 0L
  ```

  cleanup은 `unlink(v3Key, v2Key, v1Key)`로 모든 namespace를 exact key 단위로 지운다. `CacheConfig.secureCacheSerializer`를 `internal`로 노출해 같은 module test가 다음 negative path를 검증한다.

  - 등록되지 않은 local `UnsupportedCacheValue`를 serialize하면 `IllegalArgumentException`이 발생한다.
  - secure Fory config가 `requireClassRegistration=true`, `deserializeUnknownClass=false`, `maxDepth=32`, `maxGraphMemoryBytes=8 MiB`를 유지하는지 확인한다.

  테스트는 예외의 secret 문자열이 아니라 실패 여부와 serializer가 payload를 반환하지 않았다는 사실만 확인한다. Redis round-trip은 기존 `Containers.Redis` singleton과 독립 writer/reader `RedisClient`를 유지한다.

- [ ] **Step 2: 변경 전 테스트가 v2 기대 불일치로 실패하는지 확인한다.**

  ```bash
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.NearCacheWireCompatibilityTest"
  ```

  예상 결과: 구현이 아직 v2를 사용하므로 v3 raw-key assertion이 실패한다. Redis가 시작되지 않는 환경이면 launcher 오류를 별도 기록하고, serializer unit assertion부터 같은 명령으로 확인한다.

- [ ] **Step 3: 명시적 serializer 의존성을 추가한다.**

  `appointment-api/build.gradle.kts`에 version catalog가 이미 제공하는 alias를 사용한다.

  ```kotlin
  implementation(libs.bluetape4k.io)
  ```

  버전 문자열이나 새 dependency를 만들지 않는다. `fory-kotlin`, `bluetape4k-lettuce`, at.yawk LZ4 provider는 기존 선언을 유지한다.

- [ ] **Step 4: secure serializer와 고정 registration id를 구현한다.**

  `CacheConfig` companion에 다음 상수와 internal serializer를 둔다.

  ```kotlin
  internal const val DOCTOR_REGISTRATION_ID = 1001
  internal const val EQUIPMENT_REGISTRATION_ID = 1002
  internal const val TREATMENT_TYPE_REGISTRATION_ID = 1003

  internal val secureCacheSerializer: BinarySerializer by lazy {
      val fory = Fory.builder()
          .withLanguage(Language.JAVA)
          .withCompatibleMode(CompatibleMode.COMPATIBLE)
          .withRefTracking(true)
          .withRefCopy(true)
          .withStringCompressed(true)
          .withAsyncCompilation(true)
          .withCodegen(true)
          .requireClassRegistration(true)
          .withDeserializeUnknownClass(false)
          .withMaxDepth(32)
          .withMaxGraphMemoryBytes(8L * 1024 * 1024)
          .buildThreadSafeForyPool(4)
          .also { threadSafeFory ->
              threadSafeFory.register(DoctorRecord::class.java, DOCTOR_REGISTRATION_ID)
              threadSafeFory.register(EquipmentRecord::class.java, EQUIPMENT_REGISTRATION_ID)
              threadSafeFory.register(TreatmentTypeRecord::class.java, TREATMENT_TYPE_REGISTRATION_ID)
          }
      CompressableBinarySerializer(ForyBinarySerializer(fory), LZ4Compressor())
  }
  ```

  실제 import는 `io.bluetape4k.io.serializer.*`, `io.bluetape4k.io.compressor.LZ4Compressor`, `org.apache.fory.*`를 사용한다. `secureCacheSerializer`는 bean으로 공개하지 않고 cache factory와 동일한 singleton을 테스트가 관찰할 수 있게 `internal`로 둔다. registration id를 자동 할당하거나 DTO 외 class를 등록하지 않는다.

- [ ] **Step 5: 기본 near-cache DSL을 codec overload로 교체한다.**

  remote constant를 다음으로 변경한다.

  ```kotlin
  internal const val DOCTORS_REMOTE_CACHE_NAME = "clinic-doctors-v3"
  internal const val EQUIPMENTS_REMOTE_CACHE_NAME = "clinic-equipments-v3"
  internal const val TREATMENT_TYPES_REMOTE_CACHE_NAME = "clinic-treatment-types-v3"
  ```

  `LettuceBinaryCodecs.codec(secureCacheSerializer)`로 값 타입별 codec을 만들고, 다음 공통 helper로 기존 TTL/local size/RESP3 동작을 보존한다.

  ```kotlin
  private fun <V> nearCache(
      redisClient: RedisClient,
      codec: RedisCodec<String, V>,
      cacheName: String,
  ): NearCacheOperations<V> = LettuceCaches.nearCache(
      redisClient,
      codec,
      LettuceNearCacheConfig(
          cacheName = cacheName,
          maxLocalSize = MASTER_CACHE_LOCAL_SIZE,
          frontExpireAfterWrite = MASTER_CACHE_LOCAL_TTL,
          frontExpireAfterAccess = null,
          redisTtl = MASTER_CACHE_REDIS_TTL,
          useRespProtocol3 = true,
          recordStats = false,
      ),
  )
  ```

  세 bean은 각각 `nearCache(redisClient, LettuceBinaryCodecs.codec<List<DoctorRecord>>(secureCacheSerializer), DOCTORS_REMOTE_CACHE_NAME)` 형태로 연결한다. 논리 Spring cache name은 변경하지 않는다.

- [ ] **Step 6: wire/negative test를 통과시킨다.**

  ```bash
  ./gradlew :appointment-api:test --tests "io.bluetape4k.clinic.appointment.api.config.NearCacheWireCompatibilityTest"
  ```

  예상 결과: 세 DTO가 독립 writer/reader client 사이에서 동일하게 복원되고, Redis raw key는 v3만 존재한다. 등록되지 않은 class가 거부되고 secure Fory 설정의 graph bound가 고정된다. Colima 환경에서 Testcontainers Ryuk socket mount가 실패하면 위의 `TESTCONTAINERS_RYUK_DISABLED=true`를 사용하되, production 설정으로 전파하지 않는다.

- [ ] **Step 7: 두 번째 구현 단위로 커밋한다.**

  ```bash
  git add appointment-api/build.gradle.kts appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt \
    appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/NearCacheWireCompatibilityTest.kt
  git commit -m "등록 강제 Fory와 v3 캐시 namespace로 wire 경계를 격리한다" \
    -m "Constraint: v2 payload는 rollback window 동안 보존하고 논리 cache 이름은 유지한다.
Rejected: 기본 codec과 동일 namespace 혼합은 rolling deployment의 역방향 decode를 보장하지 못해 제외했다.
Confidence: medium
Scope-risk: moderate
Directive: DTO 추가 시 고정 registration id와 wire test를 함께 갱신한다.
Tested: NearCacheWireCompatibilityTest
Not-tested: production Redis canary"
  ```

## Task 3: 운영 evidence validator

**Files:**

- Create: `scripts/verify-cache-rollout-evidence.sh`

- [ ] **Step 1: validator 계약을 실행 가능한 입력으로 고정한다.**

  스크립트 사용법은 `scripts/verify-cache-rollout-evidence.sh <report.json|-> [--require-live] [--thresholds <thresholds.json>]`이다. `-`는 stdin을 뜻해 fixture를 임시 파일로 만들지 않고 테스트한다. JSON root와 필수 필드는 다음이다.

  ```json
  {
    "schemaVersion": 1,
    "environment": "local",
    "capturedAt": "2026-08-10T00:00:00Z",
    "deploymentSloEvidence": false,
    "redis": { "tls": false, "acl": false, "namespace": "v3", "rollbackNamespace": "v2" },
    "postgres": { "lockWaitMs": 0 },
    "broker": { "lagSeconds": 0 },
    "cache": { "hits": 1, "misses": 0 },
    "rollback": { "result": "NOT_RUN" }
  }
  ```

  `schemaVersion=1`, ISO timestamp, `environment`(`local|staging|production`), boolean 필드, namespace(`v3`, `v2`), 음수가 아닌 number/integer를 검증한다. rollback result는 `PASS|FAIL|NOT_RUN`만 허용한다. 누락, null, 잘못된 type, 음수, trailing JSON 데이터는 non-zero로 종료한다.

- [ ] **Step 2: live gate와 threshold를 구현한다.**

  `--require-live`가 있으면 `deploymentSloEvidence=true`, `environment=production`, `redis.tls=true`, `redis.acl=true`, `rollback.result=PASS`를 모두 요구한다. `--thresholds`를 받은 경우 threshold JSON의 `postgresLockWaitMs`와 `brokerLagSeconds`를 읽어 실제 값이 초과하면 실패한다. threshold 파일이 없으면 저장소가 임의의 production SLO를 만들지 않고 값의 존재/형식만 검증한다.

  script는 Node.js 표준 `fs`, `process`, `URL`만 사용하고 secret을 stdout/stderr에 출력하지 않는다. 성공은 `0`, 모든 검증 실패는 `1` 이상으로 종료한다. `set -euo pipefail`과 `bash -n`에 맞는 실행 파일로 만든다.

- [ ] **Step 3: validator positive/negative command를 실행한다.**

  local positive:

  ```bash
  printf '%s\n' '{"schemaVersion":1,"environment":"local","capturedAt":"2026-08-10T00:00:00Z","deploymentSloEvidence":false,"redis":{"tls":false,"acl":false,"namespace":"v3","rollbackNamespace":"v2"},"postgres":{"lockWaitMs":0},"broker":{"lagSeconds":0},"cache":{"hits":1,"misses":0},"rollback":{"result":"NOT_RUN"}}' \
    | scripts/verify-cache-rollout-evidence.sh -
  ```

  예상 결과: `0`.

  live negative:

  ```bash
  if printf '%s\n' '{"schemaVersion":1,"environment":"staging","capturedAt":"2026-08-10T00:00:00Z","deploymentSloEvidence":false,"redis":{"tls":false,"acl":false,"namespace":"v3","rollbackNamespace":"v2"},"postgres":{"lockWaitMs":0},"broker":{"lagSeconds":0},"cache":{"hits":1,"misses":0},"rollback":{"result":"NOT_RUN"}}' \
    | scripts/verify-cache-rollout-evidence.sh - --require-live; then
      echo "live gate unexpectedly passed" >&2
      exit 1
  fi
  ```

  예상 결과: validator가 production/live 조건 부족을 보고 non-zero로 종료한다. 추가로 `bash -n scripts/verify-cache-rollout-evidence.sh`를 실행한다.

- [ ] **Step 4: 실행 권한과 validator를 커밋한다.**

  ```bash
  chmod +x scripts/verify-cache-rollout-evidence.sh
  git add scripts/verify-cache-rollout-evidence.sh
  git commit -m "캐시 rollout evidence를 자동 검증 가능한 계약으로 만든다" \
    -m "Constraint: local benchmark는 deployment SLO가 아니며 live evidence를 별도 gate로 분리한다.
Rejected: 운영자가 PASS 문자열만 작성하는 수동 runbook 검사는 필수 field와 rollback 결과를 보장하지 못해 제외했다.
Confidence: high
Scope-risk: narrow
Directive: production rollout은 --require-live와 승인된 threshold를 함께 사용한다.
Tested: bash -n, local positive, live negative
Not-tested: authenticated production evidence collector"
  ```

## Task 4: migration runbook·README·lesson 갱신

**Files:**

- Modify: `docs/runbooks/dependency-1.4.0-cache-migration.md`
- Create: `docs/lessons/2026-08-10-production-hardening-readiness.md`
- Modify: `README.ko.md`

- [ ] **Step 1: 기존 runbook의 namespace 의미를 v2→v3으로 바로잡는다.**

  표와 모든 절차에서 현재 구 binary namespace를 `clinic-*-v2`, 새 binary namespace를 `clinic-*-v3`으로 명시한다. v2는 rollback window 동안 삭제하지 않고, v3 canary는 한 pod에서 시작한다. preflight는 v2/v3 exact `SCAN COUNT 500`, `INFO stats`, Redis TLS/ACL 연결 및 evidence JSON 수집을 설명한다.

- [ ] **Step 2: bounded clear와 rollback 순서를 재작성한다.**

  `KEYS`, `FLUSHALL`, `FLUSHDB`, glob `DEL/UNLINK` 금지와 primary shard별 exact-key `SCAN` + bounded `UNLINK`를 유지한다. rollback은 traffic drain → 새 writer 중지 → 구 binary 재기동으로 L1 제거 → v2 warm-up → v3 보존/조사 → evidence 기록 순서로 고정한다. 성공 후 TTL 1시간과 observation window를 기다린 뒤에만 v2를 정리한다.

- [ ] **Step 3: README와 lesson에 운영 경계를 연결한다.**

  `README.ko.md` 문서 표에 migration runbook과 `scripts/verify-cache-rollout-evidence.sh`를 추가한다. lesson에는 실제 실행한 targeted test/build, validator exit code, v3 raw-key 결과를 기록한다. production Redis/PostgreSQL/broker/SLO/rollback을 실행하지 않았다면 `PENDING`으로 명시하고 local benchmark의 `deploymentSloEvidence=false`를 production 증거로 표현하지 않는다.

- [ ] **Step 4: 문서 커밋을 만든다.**

  ```bash
  git diff --check
  git add docs/runbooks/dependency-1.4.0-cache-migration.md docs/lessons/2026-08-10-production-hardening-readiness.md README.ko.md
  git commit -m "v2 rollback과 v3 cache rollout 운영 절차를 고정한다" \
    -m "Constraint: exact-key bounded clear와 production-only evidence 경계를 유지한다.
Rejected: v2를 canary 전에 삭제하는 절차는 rollback reader를 보존하지 못해 제외했다.
Confidence: high
Scope-risk: narrow
Directive: live canary 수치가 없으면 운영 DoD를 PENDING으로 유지한다.
Tested: git diff --check
Not-tested: 실제 Redis shard와 production rollback"
  ```

## Task 5: 종합 검증과 완료 판단

**Files:**

- Verify: all files from Tasks 1–4

- [ ] **Step 1: 변경 파일 정적 검사를 실행한다.**

  ```bash
  git diff --check origin/develop...HEAD
  bash -n scripts/verify-cache-rollout-evidence.sh
  if git diff origin/develop...HEAD --unified=0 -- \
    appointment-api/src/main appointment-api/src/test \
    | rg -n '^\\+[^+].*Mockito'; then
      echo "Mockito was added by this change" >&2
      exit 1
  fi
  if git diff origin/develop...HEAD --unified=0 -- \
    appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt \
    | rg -n '^\\+[^+].*clinic-(doctors|equipments|treatment-types)-v2'; then
      echo "legacy writer namespace was added to CacheConfig" >&2
      exit 1
  fi
  ```

  예상 결과: 이번 diff의 추가 라인에는 Mockito import/의존성이 없고, `CacheConfig`의 새 writer에는 구 namespace가 없다. 기존 baseline 테스트의 Mockito와 wire compatibility test의 rollback v2 파생 key 검증은 scope 밖으로 보존한다. historical Issue #253 lesson/spec의 v2 provenance는 수정하지 않는다.

- [ ] **Step 2: API targeted test와 module build를 실행한다.**

  ```bash
  TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test \
    --tests "io.bluetape4k.clinic.appointment.api.config.CacheConfigSecurityTest" \
    --tests "io.bluetape4k.clinic.appointment.api.config.NearCacheWireCompatibilityTest"
  ./gradlew :appointment-api:build
  ```

  예상 결과: security/wire test와 API module build가 모두 `BUILD SUCCESSFUL`이다. Redis singleton 또는 dependency resolution 실패가 발생하면 실패 원인을 lesson에 남기고 production 완료를 주장하지 않는다.

- [ ] **Step 3: non-frontend aggregate 선택을 확인한다.**

  ```bash
  ./gradlew :appointment-core:build :appointment-event:build :appointment-solver:build \
    :appointment-notification:build :appointment-messaging:build :appointment-api:build \
    :appointment-messaging-benchmark:build --dry-run > /tmp/clinic-appointment-nonfrontend-dry-run.txt
  if rg -n ':frontend:' /tmp/clinic-appointment-nonfrontend-dry-run.txt; then
      echo "frontend task leaked into non-frontend verification" >&2
      exit 1
  fi
  ```

  예상 결과: `:frontend:` task가 0건이다. 이 명령은 build를 실행하지 않고 task graph만 확인한다.

- [ ] **Step 4: flow evidence와 completion claim을 갱신한다.**

  각 component에 `check-result`를 첨부한다.

  - `security-contracts`: CacheConfigSecurityTest와 NearCacheWireCompatibilityTest
  - `operational-evidence`: validator positive/negative와 runbook diff
  - `verification-delivery`: API build, non-frontend dry-run, `git diff --check`

  production authenticated Redis TLS/ACL, PostgreSQL lock-wait, broker lag, cache hit/miss, 실제 rollback 결과가 없으면 `deploymentSloEvidence`를 true로 만들지 않고 최종 상태를 `PENDING`으로 보고한다. 새 GitHub issue/PR/merge는 이 로컬 구현 검증과 별도 외부 변경 gate로 분리한다.

- [ ] **Step 5: 최종 DoD를 작성한다.**

  최종 보고에는 변경 파일, 각 명령의 fresh 결과, 미검증 live evidence, 후속 issue 후보(#254~#257과 중복되지 않는 production hardening readiness)를 포함한다. 다음 조건을 모두 만족할 때만 구현 lane을 `DONE`으로 표시한다.

  - Redis policy와 Fory v3 wire test가 PASS
  - validator schema/live negative test가 PASS
  - API build와 non-frontend dry-run이 PASS
  - diff에 Mockito 또는 허가되지 않은 frontend 변경이 없음
  - production-only 항목은 별도 `PENDING`으로 명시됨

## 셀프 리뷰 체크리스트

- [ ] 설계의 모든 요구사항(TLS/ACL, registration id, unknown-class/depth/memory bound, v3 namespace, v2 rollback, evidence 필드, local/live 분리)이 Task 1–5에 대응한다.
- [ ] 모든 구현 task가 정확한 파일과 심볼, 테스트 명령 및 예상 결과를 가진다.
- [ ] 미정 표시나 실행을 위임하는 placeholder가 없다.
- [ ] `CacheConfig`의 serializer, codec, near-cache constructor와 테스트의 remote key 이름이 일치한다.
- [ ] 기존 Issue #253 historical fixture/spec는 수정 대상에서 제외되고 현재 운영 runbook/lesson만 갱신된다.
- [ ] production evidence 부재를 코드 실패로 오해하지 않고 `PENDING` 경계로 보고한다.

# Issue #314 `jdbc-lettuce` master-data 파일럿 설계

## 상태

- Issue: [#314](https://github.com/bluetape4k/clinic-appointment/issues/314)
- 설계 결정: 2026-08-24 승인
- 구현 범위: 운영 코드 전환이 아닌 `appointment-api` 테스트 전용 파일럿
- 대상 브랜치: `feat/issue-314-jdbc-lettuce-master-cache`

## 1. 문제와 목표

`DoctorRepository`, `EquipmentRepository`, `TreatmentTypeRepository`의
`findByScope`는 `LongJdbcRepository`와 Spring `@Cacheable`을 사용해
`tenantGroupId:clinicId`를 키로 하는 `List<Record>`를 캐시한다. API는
`NearCacheAdapter`와 `NearCacheCacheManager`를 통해 Caffeine L1과 Redis L2,
Fory/LZ4 codec, v3 namespace를 이미 운영 경계로 고정하고 있다.

`bluetape4k-exposed-jdbc-lettuce:1.12.1`의
`AbstractJdbcLettuceRepository`는 `IdTable` 기반 per-ID read-through와
`findAll(where)` 결과의 cache warm, `READ_ONLY` 모드, 명시적 `RedisCodec`,
`invalidate`/`clear`/`close` 계약을 제공한다. 이 API가 현재 master-data의
scope-list 캐시를 대체할 수 있는지는 아직 검증되지 않았다.

이번 파일럿의 목표는 다음을 같은 fixture와 DB 경계에서 확인하는 것이다.

1. 기존 Spring `@Cacheable` scope-list 경로와 `AbstractJdbcLettuceRepository`
   후보의 결과·tenant 격리·빈 목록 계약이 같은지 확인한다.
2. 후보가 `findAll(where)`와 per-ID `get`에서 실제로 어떤 SQL·Redis 동작을
   만드는지 기록한다.
3. 명시적 codec·namespace·TTL, stale 값·invalidate, Redis 장애 fallback,
   transaction 경계와 `close()`를 검증한다.
4. scope-list 키와 per-ID 키의 의미가 다르면 production 전환을 보류하고,
   측정된 사유와 후속 선택지를 남긴다.

## 2. 범위와 제외

### 포함

- `gradle/libs.versions.toml`에 BOM이 관리하는
  `bluetape4k-exposed-jdbc-lettuce` alias를 추가한다.
- `appointment-api`에 해당 artifact를 `testImplementation`으로만 추가한다.
- 테스트 소스에 세 master-data 타입의 얇은 `AbstractJdbcLettuceRepository`
  probe를 둔다. probe는 테스트가 요구하는 `ResultRow` 매핑과 ID 추출만
  연결하고, 추상 API를 만족하는 쓰기 매핑은 정의하되 `READ_ONLY` 경로에서
  호출되지 않는지 확인한다.
- 기존 `CacheIntegrationTest`와 같은 tenant/clinic fixture를 사용해 legacy와
  candidate를 비교한다.
- Redis key, TTL, SQL statement 수, cache miss 후 DB fallback,
  invalidation/재조회, 장애·종료 evidence를 한국어 문서와 테스트 출력으로
  보존한다.

### 제외

- `appointment-core` repository 상속 구조, `@Cacheable`, `CacheConfig`,
  `NearCacheAdapter`, API 응답 계약을 변경하지 않는다.
- production feature flag, 새 runtime wrapper, 신규 cache abstraction을
  만들지 않는다.
- Redis를 transactional source of truth나 write-through writer로 사용하지
  않는다.
- 세 repository를 production 경로에서 일괄 전환하거나 기존 #20/#52의
  Spring Cache 전환을 반복하지 않는다.
- 이번 파일럿에서 운영 SLO나 실제 트래픽 채택을 주장하지 않는다.

## 3. 기존 계약과 후보 계약

| 항목 | 현재 legacy 경로 | `jdbc-lettuce` candidate |
|---|---|---|
| API 호출 | `findByScope(scope)` | `findAll(where = scope predicate)`, 필요 시 `get(id)` |
| 캐시 값 | `List<Record>` 한 건 | `Record` per-ID 엔트리 |
| 캐시 키 | `scope.cacheKey()` (`tenantGroupId:clinicId`) | `keyPrefix` + `id.toString()` |
| 빈 결과 | `unless = empty`로 저장하지 않음 | `findAll`은 빈 목록을 반환하고 엔트리를 만들지 않음 |
| DB 원본 | Exposed Table DSL, ambient `transaction {}` | `AbstractJdbcLettuceRepository`의 Exposed JDBC transaction |
| codec | 기존 v3 Fory/LZ4 list codec | 타입별 `ExposedLettuceCodecs.jackson3(Record::class.java)` |
| TTL | 기존 NearCache: L1 10분, Redis 1시간 | `LettuceCacheConfig.READ_ONLY`를 namespace별로 복사하고 TTL을 명시 |
| 무효화 | Spring `Cache.clear()` 또는 기존 adapter 계약 | `invalidate(id)`, `invalidateAll`, `clear` |
| 쓰기 | master-data production writer 없음 | `WriteMode.NONE`; 추상 API를 만족하는 DB write 매핑은 정의하되 READ_ONLY 경로에서는 호출하지 않음 |
| 장애 | NearCache miss/fallback 및 종료 timeout 정책 | loader 결과는 반환하고 Redis warm/get 실패는 ecosystem 로그 계약으로 확인 |

핵심 판정 기준은 결과가 같다는 사실만이 아니다. 후보가 scope-list를
한 번의 cache hit으로 반환하지 않고 매번 scope SQL을 실행한다면, per-ID
warm은 재사용 가능하더라도 현재 API의 drop-in replacement가 아니다.

## 4. 구성과 데이터 흐름

파일럿은 production Spring bean graph에 등록하지 않는다. 테스트는 기존
`AbstractApiIntegrationTest`가 제공하는 singleton Redis launcher, Flyway/H2
설정, `RedisClient` bean과 `DoctorRepository`·`EquipmentRepository`·
`TreatmentTypeRepository` bean을 그대로 사용한다.

각 probe는 다음 설정을 고정한다.

```kotlin
override val config = LettuceCacheConfig.READ_ONLY.copy(
    keyPrefix = "issue314:jdbc-lettuce:doctors",
    ttl = Duration.ofHours(1),
)

valueCodec = ExposedLettuceCodecs.jackson3(DoctorRecord::class.java)
```

실제 실행 흐름은 다음과 같다.

1. fixture가 두 tenant/clinic과 master-data 행을 transaction 안에서 만든다.
2. legacy의 첫 `findByScope`와 두 번째 호출을 수행해 scope-list cache hit과
   SQL 0건 재호출을 확인한다.
3. candidate의 `findAll(where)`를 수행해 tenant predicate 결과와 per-ID
   Redis key warm을 확인한다.
4. candidate `get(id)`를 warm 뒤 호출해 DB SQL이 발생하지 않는지, key를
   지운 뒤 호출해 read-through가 다시 DB를 읽는지 확인한다.
5. DB 값을 변경하거나 삭제한 뒤 candidate key를 invalidate하고 재조회해
   stale 값이 남지 않는지 확인한다.
6. Redis 연결 실패 조건에서 DB 결과가 반환되는지 확인하고 마지막에
   `close()`를 호출해 connection·scheduler 종료를 확인한다.

모든 Exposed query는 `transaction {}` 안에서 실행한다. 테스트 setup/teardown은
기존 규칙대로 `SchemaUtils.createMissingTablesAndColumns(Table)`와
`Table.deleteAll()`을 사용하며, raw `@Testcontainers`를 추가하지 않는다.

## 5. 오류·수명주기 경계

- **Redis read/warm 실패:** 후보가 DB 결과를 반환하는지 확인한다. 실패를
  성공으로 숨기지 않고 test ledger에 operation, error type, fallback 결과를
  기록한다.
- **stale 값:** candidate가 `READ_ONLY`이므로 DB writer는 없다. 외부 DB
  변경 뒤 `invalidate(id)` 전후 결과를 비교해 무효화 책임을 명확히 한다.
- **빈 목록:** 빈 scope는 Redis에 엔트리를 만들지 않아 새 행이 추가된 뒤
  빈 값이 영구히 남지 않는지 확인한다.
- **codec/namespace 충돌:** 각 타입이 서로 다른 prefix와 explicit codec을
  사용하고, 기존 `clinic-*-v3` namespace를 읽거나 쓰지 않는지 확인한다.
- **transaction 경계:** `findAll(where)`의 SQL이 ambient Exposed transaction을
  재사용하는지 확인한다. commit 전후 관찰은 같은 fixture에서 수행하고,
  transaction 밖에서 Query 객체를 보관하지 않는다.
- **close:** probe의 `close()` 후 Redis connection이 새 작업을 받지 않는지
  확인한다. 종료 중 실패가 있으면 원래 테스트 예외와 cleanup 예외를 분리해
  기록한다.

## 6. 검증과 evidence

필수 검증은 다음과 같다.

1. `:appointment-api:test --tests "*.JdbcLettuceMasterDataPilotTest"` —
   legacy/candidate 결과, tenant 격리, 빈 결과, codec/namespace, TTL,
   invalidation, Redis 장애, close.
2. 테스트 내부 `StatementInterceptor` — master-data SELECT 수를 경로와
   호출 단계별로 기록한다.
3. Redis raw key/TTL 조회 — candidate prefix만 생성되고 TTL이 양수이며
   기존 v3 prefix와 섞이지 않는지 확인한다.
4. `:appointment-api:test` — baseline 851 tests, 3 skipped 기준에서 회귀가
   없는지 확인한다.
5. `:appointment-api:build`와 `git diff --check` — test-only dependency가
   runtime/bootJar에 유출되지 않고 문서·코드 diff가 깨끗한지 확인한다.
6. 결과 문서 — candidate가 scope-list drop-in replacement가 아니면
   `보류`로 판정하고, production 전환 조건을 문서화한다.

성능 결과는 측정한 SQL 수·cache hit/miss 단계·대표 latency가 있을 때만
기록한다. 테스트가 제공하지 않는 운영 SLO, 실제 트래픽, 장기 TTL 안정성을
추정하지 않는다.

## 7. 롤백과 채택 기준

이번 변경의 production rollback은 의존성·테스트 파일 제거로 충분하다.
운영 cache namespace에는 쓰지 않으므로 Redis 데이터 rollback 작업은
필요하지 않다.

### 채택 가능 조건

- 세 타입의 결과·tenant 격리·빈 결과가 legacy와 동일하다.
- 후보의 per-ID read-through와 invalidate/close가 명시된 codec·TTL로
  안정적으로 동작한다.
- scope 조회 SQL 재실행이 없어지거나, 그 비용이 명시적으로 수용 가능하다.
- production에서 적용할 key/index 경계와 외부 writer 무효화 책임을 추가
  추상화 없이 설명할 수 있다.

### 보류 조건

- `findAll(where)`가 scope-list hit을 제공하지 않아 호출마다 scope SQL을
  실행한다.
- cache hit/miss 또는 Redis 장애 fallback의 운영 지표를 현재 API에서
  관측할 수 없다.
- codec/TTL/close 동작이 현재 v3 NearCache 계약과 양립하지 않는다.

보류 시 기존 Spring Cache/NearCache 경로를 유지하고, list-key를 직접
지원해야 하는 후속 설계만 별도 Issue로 등록한다. 이번 변경에서 해당
wrapper를 미리 만들지 않는다.

## 8. 설계 DoD

- [ ] test-only dependency와 probe가 실제 `bluetape4k-exposed-jdbc-lettuce`
  API를 사용한다.
- [ ] 기존 production source/API와 `clinic-*-v3` namespace가 변경되지 않는다.
- [ ] 결과·tenant·empty-list·codec·TTL·invalidation·Redis failure·close
  테스트가 통과한다.
- [ ] SQL/cache evidence가 scope-list와 per-ID 차이를 드러낸다.
- [ ] 6관점 inline review와 spec self-review에서 P0/P1·미해결 모순이 없다.
- [ ] 보류/채택 판정과 rollback 경계가 Korean lesson/PR DoD에 기록된다.

## 근거

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt`
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/CacheConfig.kt`
- `/Users/debop/work/bluetape4k/bluetape4k-exposed/exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/repository/AbstractJdbcLettuceRepository.kt`
- `/Users/debop/work/bluetape4k/bluetape4k-exposed/exposed/jdbc-lettuce/src/main/kotlin/io/bluetape4k/exposed/lettuce/map/ExposedLettuceLoadedMap.kt`
- `gh issue view 314 --repo bluetape4k/clinic-appointment`

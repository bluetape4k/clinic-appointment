# Issue #314 `jdbc-lettuce` master-data 파일럿 설계 inline review

## 판정

- **파일럿 구현:** PASS
- **운영 경로 전환:** HOLD
- **파일럿 구현 범위의 P0/P1:** 0건
- **운영 전환을 막는 P1 위험:** scope-list와 per-ID 캐시 계약 불일치, 운영 지표 공백
- **review 방식:** 독립 리뷰어를 두지 않고 이 세션에서 6관점을 순차 검토
- **대상 명세:** `docs/superpowers/specs/2026-08-24-issue-314-jdbc-lettuce-master-cache-design.md`

이번 검토의 PASS는 `appointment-api` 테스트 전용 파일럿에만 적용한다. 기존
Spring Cache/NearCache 경로를 바꾸거나 새 runtime wrapper를 추가하는 운영
전환 승인은 아니다.

## 검토 근거

- 현재 세 repository는 `LongJdbcRepository`와 `findByScope`의 scope-list
  캐시를 사용한다.
- 실제 `bluetape4k-exposed-jdbc-lettuce:1.12.1`의
  `AbstractJdbcLettuceRepository`는 `findAll(where)`에서 DB 조회 후 per-ID
  엔트리를 warm하고, `get(id)`에서 read-through한다.
- `LettuceCacheConfig.READ_ONLY`, explicit `RedisCodec`, `invalidate`,
  `clear`, `close`는 후보 API가 직접 제공한다.
- clinic-appointment의 기존 v3 namespace와 Fory/LZ4 codec은 production
  source of truth로 유지하고, 파일럿은 `issue314:jdbc-lettuce:*` test-only
  prefix를 사용한다.
- baseline `:appointment-api:test`는 851 tests, 3 skipped, SUCCESS였다.

## 1. 아키텍처·경계 검토

### 확인

- 의존성은 `testImplementation`에만 둔다.
- probe는 실제 `AbstractJdbcLettuceRepository`와 기존 Table/Record를
  연결하는 얇은 테스트 코드로 제한한다.
- `appointment-core` repository 상속, `CacheConfig`, `NearCacheAdapter`,
  API response, DB source of truth는 변경하지 않는다.
- 세 타입별 prefix를 분리해 key collision을 막고, 기존 `clinic-*-v3`
  namespace와 읽기·쓰기를 섞지 않는다.
- `findAll(where)`의 결과와 per-ID `get`의 cache hit을 별도로 측정해
  list-key 계약을 몰래 대체하지 않는다.

### 위험과 결정

`findAll(where)`는 scope-list를 캐시하지 않고 매번 scope SQL을 실행한다.
따라서 후보는 현재 `findByScope`의 drop-in replacement가 아니다. 이 차이를
파일럿의 측정 결과로 남기고 production adoption을 HOLD하는 것은 명세와
일치한다. list-key를 지원하는 wrapper를 이번 변경에 미리 만들지 않는다.

**판정:** 파일럿 경계는 PASS. 운영 전환은 HOLD.

## 2. 보안 검토

### 확인

- 후보 codec은 `ExposedLettuceCodecs.jackson3(Record::class.java)`로
  타입을 명시한다. 기본 implicit codec을 사용하지 않는다.
- test-only namespace를 사용하므로 운영 v3 값과 codec 교차 읽기가 없다.
- 테스트에는 credential, raw Redis payload, 외부 Redis endpoint를 문서나
  로그로 남기지 않는다.
- Redis 장애는 예외를 성공으로 변환하지 않고 operation/error type/fallback
  결과만 ledger에 남긴다.
- `READ_ONLY`는 DB write-through를 하지 않는다. 추상 클래스가 요구하는
  update/insert mapping은 API를 만족하기 위한 정의이며, 해당 경로가
  호출되지 않는지 테스트한다.

### 남은 경계

Jackson3 codec을 운영 공용 namespace에 적용하는 것은 별도 threat model과
호환성 검토가 필요하다. 이번 파일럿은 격리된 테스트 prefix에 한정하므로
그 결정을 선행하지 않는다.

**판정:** 파일럿 보안 위험 P0/P1 없음. 운영 codec 채택은 별도 승인 필요.

## 3. 성능 검토

### 측정 설계

- `StatementInterceptor`로 legacy 첫 호출·두 번째 호출, candidate
  `findAll`, warm 이후 `get`, key 삭제 후 read-through를 단계별 집계한다.
- Redis raw key와 TTL을 직접 확인한다.
- candidate의 per-ID warm이 후속 `get` SQL을 줄이는지와 scope 조회 자체가
  반복되는지를 분리해 기록한다.
- latency는 실제 측정값이 있을 때만 기록하고, SQL 수만으로 운영 SLO를
  추정하지 않는다.

### 판단

legacy scope-list hit은 scope SQL을 생략하지만 candidate `findAll(where)`는
  scope SQL을 다시 실행한다. 따라서 데이터가 큰 경우 candidate가 오히려
  불리할 수 있으며, 파일럿 결과 없이 도입 이득을 주장하지 않는다. 별도의
  benchmark module이나 새 metrics abstraction은 추가하지 않는다.

**판정:** 측정 범위 PASS. 운영 성능 채택은 evidence가 충족될 때까지 HOLD.

## 4. SRE·운영성 검토

- 운영 bean graph와 Redis production namespace를 변경하지 않아 rollback은
  dependency와 test probe 제거로 끝난다.
- singleton Redis launcher와 기존 integration-test lifecycle을 재사용하며
  `@Testcontainers`를 새로 만들지 않는다.
- Redis GET/SET/warm 실패 시 DB 결과와 error type을 함께 확인한다.
- stale 값은 외부 DB 변경 뒤 `invalidate(id)` 전후로 비교한다. 외부 writer의
  자동 무효화가 현재 API에 없다는 사실을 운영 책임으로 명시한다.
- `close()`에서 connection과 scheduler가 종료되는지 확인한다. cleanup
  실패가 원래 assertion을 덮지 않도록 예외를 분리한다.
- 이번 변경으로 운영 SLO, alert, dashboard, cache hit/miss metric이
  생긴다고 주장하지 않는다. 필요하면 후속 운영 이슈로 분리한다.

**판정:** 파일럿 운영성 PASS. 운영 관측 공백은 채택 HOLD 사유로 기록.

## 5. 사용자 API·개발자 경험 검토

- public production API와 `findByScope`의 호출 계약은 그대로 둔다.
- 테스트 probe가 외부 모듈에 노출되지 않고 runtime classpath/bootJar에
  artifact가 유출되지 않도록 build 검증을 추가한다.
- Kotlin 구현은 기존 Exposed transaction 규칙, `Serializable` record,
  explicit codec, ecosystem repository contract를 그대로 따른다.
- 한국어 설계·리뷰·lesson을 남기고 코드/식별자/명령은 원문 계약을 보존한다.
- 별도 adapter, feature flag, cache abstraction, dependency를 새로
  만들지 않는다.

**판정:** 사용자 API 영향 없음. 테스트 전용 개발자 경험 PASS.

## 6. 검증·테스트 검토

필수 시나리오는 명세와 구현 계획에 다음 순서로 고정한다.

1. 세 record의 결과와 tenant 격리
2. 빈 scope가 key를 만들지 않는지와 이후 행 추가 재조회
3. explicit codec, 타입별 namespace, positive TTL
4. warm 후 `get` cache hit과 key 삭제 후 DB read-through
5. `invalidate`, 삭제/재조회, stale 값 제거
6. Redis 장애 시 DB fallback과 실패 ledger
7. transaction 경계와 `close()` lifecycle
8. SQL count와 runtime/bootJar dependency leakage 검사

검증 명령은 우선 `:appointment-api:test --tests
"*.JdbcLettuceMasterDataPilotTest"`로 좁히고, 이후 `:appointment-api:test`,
`:appointment-api:build`, `git diff --check` 순서로 확장한다. 모든 결과는
실행 출력과 Korean lesson/PR DoD에 연결한다.

**판정:** 계획된 검증으로 설계 위험을 관측할 수 있음. 구현 후 fresh
evidence가 없으면 완료로 보고하지 않는다.

## 리뷰 결론과 다음 단계

- 명세 self-review에서 placeholder와 terminology 오류가 없었다.
- 6관점에서 파일럿 구현을 막는 미해결 모순은 없다.
- production adoption은 scope-list/per-ID 계약과 운영 지표가 해결될 때까지
  HOLD한다.
- 다음 단계는 새 추상화를 만들지 않고, 승인된 명세를 TDD 테스트와
  test-only dependency로 최소 구현한 뒤 targeted/full verification을 수행하는
  것이다.

# Issue #314 `jdbc-lettuce` 구현·검증 최종 inline review

## 판정

- **test-only 파일럿:** PASS
- **production cache 경로 전환:** HOLD
- **P0/P1:** 0건
- **검토 방식:** 독립 리뷰어 없이 이 세션에서 변경 diff와 fresh 검증 결과를
  6관점으로 순차 검토
- **검토 기준:** `origin/develop...HEAD`와 미커밋 최종 변경, 승인된 명세·계획

이번 PASS는 `appointment-api` 테스트 classpath의 세 thin probe와 계약 테스트에
한정한다. 기존 `LongJdbcRepository`·Spring Cache/NearCache·`clinic-*-v3`
production 경로는 변경하지 않았으며, 운영 전환은 별도 승인 없이는 하지 않는다.

## 1. 아키텍처·경계

- `bluetape4k-exposed-jdbc-lettuce:1.12.1`은 version catalog alias와
  `testImplementation`으로만 연결했다.
- probe는 실제 `AbstractJdbcLettuceRepository`를 직접 상속하고 기존
  `ResultRow` mapper와 `LettuceCacheConfig.READ_ONLY`를 재사용한다.
- `issue314:jdbc-lettuce:<type>` prefix를 사용하며 production source/API와
  v3 namespace에는 변경이 없다.
- candidate `findAll(where)`가 scope-list drop-in replacement가 아니라는
  사실을 SQL assertion과 lesson에 남겼고 새 wrapper·feature flag는 만들지
  않았다.

**판정:** PASS. 운영 전환은 scope-list/per-ID 계약이 해결될 때까지 HOLD.

## 2. 보안·호환성

- 타입별 `ExposedLettuceCodecs.jackson3(...)`를 명시하고 raw payload를
  출력하지 않는다.
- Redis 장애 테스트는 `127.0.0.1:1`과 singleton Redis의 별도 client 종료를
  사용하며 운영 client를 종료하지 않는다.
- test-only artifact의 runtime classpath, production runtime classpath,
  `bootJar` 유출을 별도 확인했다.

**판정:** PASS. 운영 공용 codec·namespace 채택은 이번 범위가 아니다.

## 3. 성능

- legacy 두 번째 scope 조회의 cache hit(SELECT 0)와 candidate `findAll`의
  warm(SELECT 1)을 `StatementInterceptor`로 분리했다.
- candidate warm 뒤 per-ID `get`은 SELECT 0, `invalidate` 뒤에는 SELECT 1로
  read-through을 확인했다.
- targeted 8개 테스트는 8.6초에 완료했고, latency/SLO를 SQL count에서
  추정하지 않았다.

**판정:** PASS. scope SQL 반복 비용 때문에 운영 성능 채택은 HOLD.

## 4. 안정성·운영성

- 빈 결과는 key를 만들지 않고 이후 행 삽입을 읽는다.
- 외부 DB 변경은 invalidate 전 stale, invalidate 후 최신 값이며 삭제 후
  `null`과 key 제거를 확인한다.
- 이미 연결된 Redis client 종료 뒤 명령 실패 fallback과 미사용 endpoint의
  warm fallback을 200ms timeout으로 각각 확인한다.
- 반복 `close()`와 `@AfterEach` cleanup이 예외를 삼켜 원래 assertion을
  덮지 않도록 `runCatching` 경계를 사용한다.

**판정:** PASS. hit/miss·fallback 운영 지표는 추가하지 않았고 채택 보류 사유로
기록했다.

## 5. Kotlin·API·유지보수성

- 모든 Exposed 접근은 `transaction {}` 안에서 수행하고 fixture는
  `SchemaUtils.createMissingTablesAndColumns`와 자식 우선 `deleteAll()`을
  사용한다.
- write mapper는 `READ_ONLY` 추상 API의 컴파일 계약을 만족시키는 최소 정의이며
  테스트에서 write-through를 호출하지 않는다.
- production abstraction이나 새 dependency를 추가하지 않았고, 변경 코드는
  테스트·build metadata·Korean 설계/lesson 문서에 국한된다.

**판정:** PASS. 기존 bluetape4k ecosystem 계약을 재사용했다.

## 6. 검증 완전성·회귀

| 검증 | 결과 |
|---|---|
| TDD RED | 의도적 RED 주입 시 8개 중 1개 실패, `BUILD FAILED` 확인 |
| TDD GREEN | 복구 후 `SUCCESS: Executed 8 tests in 8.6s`, `BUILD SUCCESSFUL` |
| module build | `:appointment-api:build` 859 tests, 3 skipped, `BUILD SUCCESSFUL` |
| dependency 경계 | runtime/production runtime classpath 일치 항목 없음 |
| bootJar | jdbc-lettuce test artifact 유출 없음 |
| 문서/패치 | `git diff --check` 및 Korean 용어 감사 대상 문서 통과 |

**판정:** PASS. 실제 운영 traffic, 장기 TTL 안정성, 운영 SLO/metrics는
파일럿에서 검증하지 않았다.

## 통합 결론

파일럿의 결과·tenant 격리·빈 목록·codec/namespace/TTL·SQL/cache hit/miss·
invalidation/stale/delete·Redis fallback·transaction·close 계약은 검증됐다.
기존 production cache는 그대로 유지하며, scope-list를 한 번의 hit으로
제공하거나 per-ID warm 비용을 수용할 근거와 운영 관측 계약이 생기기 전에는
전환하지 않는다.

**최종 상태:** PASS (test-only pilot) / HOLD (production adoption)

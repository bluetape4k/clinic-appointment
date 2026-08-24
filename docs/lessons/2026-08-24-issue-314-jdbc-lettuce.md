# Issue #314 `jdbc-lettuce` master-data 파일럿 결과

## 결론

`bluetape4k-exposed-jdbc-lettuce`의 실제 `AbstractJdbcLettuceRepository`를
운영 코드에 등록하지 않고, `appointment-api` test classpath에만 연결한 파일럿을
완료했다. 기존 `DoctorRepository`, `EquipmentRepository`,
`TreatmentTypeRepository`의 결과·테넌트 격리와 per-ID read-through 계약은
검증되었지만, 후보의 `findAll(where)`는 scope 목록 cache hit을 제공하지 않고
매번 SQL을 실행한다. 따라서 운영 cache 경로 전환은 `보류`한다.

## 검증 결과

| 항목 | 근거 | 결과 |
|---|---|---|
| 세 타입 결과와 tenant 격리 | doctor/equipment/treatment type의 legacy 대 candidate 비교, 다른 tenant와 잘못된 tenant scope 조회 | PASS |
| 빈 결과 | 빈 clinic 조회 뒤 Redis key 0개, 행 삽입 후 즉시 조회 | PASS |
| codec/namespace/TTL | explicit Jackson3 codec, `issue314:jdbc-lettuce:*` prefix, Redis TTL 1~3600초, `clinic-*-v3` 비혼입 | PASS |
| SQL/cache hit·miss | legacy 첫 조회 1 SQL·두 번째 0 SQL, candidate `findAll` 1 SQL, warm 뒤 `get` 0 SQL·invalidate 뒤 1 SQL | PASS |
| stale/invalidate/delete | 외부 DB 이름 변경은 invalidate 전 stale 유지, invalidate 후 갱신, 삭제 후 null | PASS |
| Redis 장애와 수명주기 | 사용하지 않는 `127.0.0.1:1`에서 warm DB fallback, 연결 후 client 종료 뒤 `get` DB fallback, 200ms timeout, 반복 `close()` | PASS |
| runtime 경계 | `runtimeClasspath`·`productionRuntimeClasspath` 일치 항목 없음, `bootJar` 내부 일치 항목 없음 | PASS |
| 전체 회귀 | `:appointment-api:test` 859 tests, 3 skipped, 3분 12초; baseline 851 tests 대비 파일럿 8개 추가 | PASS |

## 재사용한 ecosystem 계약

- 새 production abstraction을 만들지 않고 `AbstractJdbcLettuceRepository`와
  `ExposedLettuceCodecs.jackson3`를 테스트 probe에서 직접 사용했다.
- `LettuceCacheConfig.READ_ONLY.copy(...)`로 타입별 prefix와 1시간 TTL만
  지정했다. write mapper는 추상 API의 요구를 충족하지만 파일럿은 `put`이나
  write-through를 호출하지 않는다.
- Spring 통합 테스트의 singleton Redis launcher, `ResourceLock`,
  `SchemaUtils.createMissingTablesAndColumns`, 자식 우선 `deleteAll()`과
  bluetape4k assertions를 재사용했다.

## Redis 장애 주입의 경계

`ExposedLettuceLoadedMap`은 cache 객체를 만들 때 Redis connection을 eager하게
연다. 따라서 사용하지 않는 포트에서는 `findAll`의 warm 실패와 DB 결과 반환을
검증하고, 이미 연결된 client를 종료한 뒤 `get` 명령 실패 fallback을 별도로
검증했다. 이 방식은 singleton Redis를 종료하지 않으며, endpoint·payload를
로그에 남기지 않는다.

## 운영 채택 보류 사유와 다음 조건

후보는 per-ID key를 warm하지만 현재 API가 제공하는 scope-list key의 drop-in
replacement가 아니다. 또한 운영 hit/miss와 Redis 장애 지표를 연결할 production
관측 경계가 없다. 기존 Spring Cache/NearCache 경로는 그대로 유지한다.

운영 전환을 다시 검토하려면 다음 증거가 별도 설계·승인되어야 한다.

1. scope 목록을 한 번의 cache hit으로 제공하거나, per-ID warm의 반복 SQL 비용을
   수용할 근거
2. 외부 writer의 update/delete와 `invalidate`를 연결하는 운영 계약
3. hit/miss, warm 실패, fallback, TTL을 확인할 운영 지표
4. 기존 `clinic-*-v3` namespace와 codec 호환성에 대한 migration/rollback 계획

이번 변경에는 production adapter, feature flag, metrics abstraction, benchmark
module을 추가하지 않았다.

## 변경·검증 식별자

- 의존성 경계: `0ba2b8e6`
- 파일럿 구현과 검증: `f43c4ce7`
- targeted pilot: 8 tests, 8.6초 (최종 GREEN 재실행)
- full module regression: 859 tests, 3 skipped, 3분 12초

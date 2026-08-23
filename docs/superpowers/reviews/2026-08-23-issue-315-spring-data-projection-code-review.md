# Issue #315 구현 코드 리뷰

## 검토 범위

`appointment-api/src/test/.../projection`의 test-only Entity/repository/adapter,
narrow `ApplicationContextRunner`, H2/PostgreSQL fixture, lifecycle helper와
benchmark evidence를 검토했다. production source, route, dependency scope,
Flyway SQL과 public ABI는 변경하지 않았다.

## 관점별 결과

| 관점 | 확인 내용 | 결과 |
|---|---|---|
| API/경계 | 모든 pilot 타입 `internal`, adapter는 `Long` tenant 입력만 공개하고 `EntityID`를 숨김 | PASS |
| 데이터/SQL | `Clinics` 재사용, typed PartTree predicate, `id ASC`, raw `@Query` 없음, 대표 SELECT 1회 | PASS |
| transaction | `springTransactionManager` 하나, Spring/Exposed physical connection identity, bean wiring read-back | PASS |
| lifecycle | unique schema, pool close 후 schema drop, sentinel restore, callback/close 실패 suppressed | PASS |
| 성능 | 동일 total metric, 교대 실행, median/p95, component timing은 진단용으로 분리 | PASS |
| 보안/운영 | raw sanitization·gitleaks, runtimeClasspath/bootJar exact boundary, authz 경계 문서화 | PASS |

## 잔여 위험

`poolConcurrency`, full-row column-level projection, authenticated route 권한
검증은 이 diff의 범위가 아니다. candidate가 H2/PostgreSQL 모두 측정 구간에서
legacy보다 느렸으므로 production 채택을 권장할 근거도 없다.

## 최종 판정

P0/P1 결함 없음. test-only pilot로는 병합 가능한 품질이며, 운영 채택은
위험·성능·권한 증거가 추가될 때까지 보류한다.

판정: **PASS WITH ADOPTION HOLD**

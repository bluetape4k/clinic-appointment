# Issue #244 appointment-core 신뢰 경계와 Kotlin 패턴 lesson

## 결론

`appointment-core`의 raw tenant/clinic 좌표와 database에서 확인된 scope를 같은 값 객체로
표현하면, 양쪽 ID가 각각 유효해도 서로 다른 tenant의 clinic을 조합할 수 있다. 공개
constructor가 있는 data class를 단순히 private constructor로 바꾸는 것만으로는 Kotlin/JVM
synthetic constructor와 `copy()` 경로를 다시 검토해야 한다. 이번 변경은 외부 구현이 불가능한
sealed `VerifiedTenantClinicScope`와 파일 내부의 private 구현을 사용하고, repository가
clinic membership를 확인한 뒤에만 구현을 반환하도록 고정했다.

## 적용한 규칙

- `AppointmentRepository.findVerifiedScope`와 `findVerifiedScopeByIdAndTenant`가
  `Clinics.tenantGroupId`를 조회한 뒤 verified scope를 반환한다.
- verified scope 기반 `findByIdAndVerifiedScope`를 추가했다. 기존
  `TenantClinicScope` API는 여러 모듈의 호환성을 위해 유지하되, 권한 증명으로 사용하지
  않도록 KDoc에서 legacy 경계를 명시했다.
- policy, persistence record, plan input, profile/reliability summary와 scheduling-policy
  private wire `data class`에 `Serializable` 및 `serialVersionUID`를 명시했다. 자식 sealed
  결과는 부모의 Serializable 계약을 상속하므로 UID를 별도로 고정했다.
- `KotlinProductionPatternComplianceTest`가 장기 보존 모델과 wire class 목록을 source-level로
  검사한다. 새 wire/data class를 추가할 때 목록과 UID를 함께 갱신해야 한다.
- 일반 `withTables` fixture와 동시성 fixture는 `SchemaUtils.createMissingTablesAndColumns`를
  사용하고, 종료 시 FK 자식부터 `deleteAll()`한 뒤 DDL을 정리한다. 직접 `SchemaUtils.create`
  를 사용하는 경로는 schema-contract 테스트로 한정했다.
- JUnit `assertThrows`를 `io.bluetape4k.assertions.assertFailsWith`로 교체했다.

## 검증

| 검증 | 결과 |
|---|---|
| verified scope·직렬화·repository·TimeRange targeted | 29건 성공; sealed/public constructor 없음, cross-tenant 조합 거부 |
| `:appointment-core:test` | 689건 성공 |
| `:appointment-core:check` | 689건 성공, Kover verify 포함 |
| 직접 DDL 정적검사 | schema-contract 2개 테스트만 `SchemaUtils.create` 사용 |
| JUnit `assertThrows` 정적검사 | `appointment-core/src/test`에서 0건 |

## 남은 경계

기존 API·solver·event·notification 호출자는 아직 구조적 `TenantClinicScope`를 사용한다.
따라서 이 변경은 신규 repository 경계를 제공하고 legacy API를 보존하는 단계이며, 모든
adapter 호출을 verified scope로 전환하고 legacy constructor를 제거하는 작업은 별도 범위로
추적해야 한다. production DB/원격 CI와 PR·merge는 이번 로컬 변경에서 실행하지 않았다.

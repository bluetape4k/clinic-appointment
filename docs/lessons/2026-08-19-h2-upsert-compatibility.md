# Exposed insertIgnore는 H2 공통 경로가 아니다

## 상황

`SchedulingPolicyRepository`의 scope head 초기화가 Exposed `insertIgnore`에 의존하고
있었다. API 통합 테스트의 H2 프로파일은 PostgreSQL 모드 URL을 사용하므로, 첫 scope
잠금에서 `UnsupportedByDialectException`이 발생했다. 같은 repository의 immutable
effective snapshot 중복 삽입도 동일한 dialect 제약을 갖고 있었다.

## 원인

Exposed의 H2 `insertIgnore` 경로는 `MODE=MYSQL` 전제에 묶여 있다. 따라서 PostgreSQL
모드 H2나 일반 H2를 PostgreSQL과 같은 conflict-ignore 경로로 사용할 수 없다. 문제는
H2 fixture 자체가 아니라, 고유 키 충돌을 무시해야 하는 repository가 특정 H2 mode의
문법을 공통 persistence 계약으로 사용한 데 있었다.

## 해결책

- scope head는 `(tenantGroupId, scope, clinicScopeKey)` 고유 키를 명시한 `upsert`로
  초기화한다.
- 충돌 시에는 고유 키를 같은 값으로 쓰는 no-op update만 수행해 이미 증가한
  `revision`, `generation`, `clinicGenerationEpoch`를 덮어쓰지 않는다.
- immutable snapshot도 `(tenantGroupId, clinicId, snapshotHash)` 고유 키를 명시한
  동일한 no-op upsert로 중복 삽입을 보존한다.
- 새로운 dependency나 dialect 분기를 추가하지 않고 Exposed JDBC API만 사용한다.

## 검증

- H2 회귀: `SchedulingPolicyRepositoryTest`의 H2 및 H2_COMMITMENT fixture에서 scope
  head 재접근 후 revision/generation 보존을 확인했다.
- API H2 통합: `SchedulingPolicyDialectIntegrationTest` 5/5 통과.
- API PostgreSQL Testcontainers 통합: 같은 테스트 5/5 통과.
- `:appointment-core:test`: 554/554 통과.
- `:appointment-api:test`: 801 통과, 3개 명시적 skip.

## 재사용 지침

1. H2를 PostgreSQL 모드로 실행하더라도 Exposed `insertIgnore`를 공통 dialect 계약으로
   가정하지 않는다.
2. 충돌 시 기존 행을 보존해야 하는 idempotent insert는 고유 conflict key를 명시한
   no-op `upsert`를 우선 검토한다.
3. `upsert` body의 기본 update가 기존 상태를 초기화할 수 있으므로, immutable 또는
   monotonic row에는 명시적인 `onUpdate`를 함께 지정한다.
4. H2 성공만으로 PostgreSQL 호환성을 선언하지 말고, 동일한 Testcontainers 회귀를
   두 dialect에서 순차 실행한다.

production 배포·canary 증거는 이 예제 서비스의 범위 밖이며, 이 수정의 완료 조건은
  H2와 Testcontainers 기반 PostgreSQL 시뮬레이션 증거로 한정한다.

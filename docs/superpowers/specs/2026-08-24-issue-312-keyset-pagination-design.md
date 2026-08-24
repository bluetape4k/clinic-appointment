# Issue #312: Exposed 목록 API keyset cursor 경로 설계

상태: 설계 승인 완료, 구현 전

Issue: https://github.com/bluetape4k/clinic-appointment/issues/312

## 문제와 목표

`DoctorRepository`, `EquipmentRepository`, `TreatmentTypeRepository`의
tenant·clinic 목록은 현재 `LongJdbcRepository.findPage`를 호출한다. 이
공통 구현은 전체 `COUNT`와 `page * size` `OFFSET` 조회를 모두 실행한다.
대규모 clinic 목록에서 뒤쪽 페이지를 읽을수록 불필요한 행을 건너뛰며,
동시 삽입·삭제가 페이지 경계를 흔들 수 있다.

이번 변경은 기존 offset API를 유지하면서 PostgreSQL 운영 목록에 한해
추가 keyset cursor 경로를 제공한다. keyset 경로는 `(clinic_id, id)`의
고정 정렬 경계를 사용하고, 전체 개수 조회나 `OFFSET` 없이 제한된 행만
읽는다.

## 현재 근거

| 근거 | 확인 내용 |
|---|---|
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/DoctorRepository.kt:97-102` | `findPage`가 tenant·clinic predicate를 부모 `findPage`에 전달한다. |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/EquipmentRepository.kt:66-71` | 장비 목록도 같은 offset 위임 구조를 사용한다. |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/TreatmentTypeRepository.kt:119-124` | 시술 유형 목록도 같은 범위 predicate와 offset 위임을 사용한다. |
| `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/DoctorController.kt:58-71` | 기존 endpoint가 `page`, `size`, `ExposedPage`를 사용하고 `TenantClinicAccessChecker`와 `transaction {}`을 적용한다. 장비·시술 유형 controller도 동일하다. |
| `bluetape4k-exposed/.../JdbcRepository.kt:631-652` | 공통 `findPage`가 `countBy` 후 `offset = page * size`를 적용한다. |
| `bluetape4k-exposed/.../ExposedPage.kt:16-40` | offset 응답은 `content`, `totalCount`, `pageNumber`, `pageSize`와 페이지 계산 필드를 제공한다. |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt:291-346` | 기존 repository가 `id > afterId`, `ORDER BY id ASC`, `LIMIT`을 조합하는 keyset 패턴을 사용한다. |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyImpactRepository.kt:117-225` | 복합 cursor를 검증하고 bounded page를 반환하는 repository 패턴이 존재한다. |
| `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/{Doctors,Equipments,TreatmentTypes}.kt` | 세 테이블 모두 `clinic_id` index와 전역 Long primary key `id`를 갖는다. |

## 범위와 제외

### 포함

- 세 repository에 동일한 `ClinicKeysetCursor`·`ClinicKeysetPage<T>` 계약을
  적용한다.
- 세 resource에 별도 `/cursor` GET 경로를 추가한다.
- cursor의 clinic 경계, limit 범위, 빈 결과, 마지막 cursor, sparse id,
  concurrent insert/delete, 잘못된 clinic cursor를 테스트한다.
- H2 및 활성화된 PostgreSQL Testcontainers 테스트에서 keyset SQL이
  `OFFSET`을 사용하지 않는지 확인한다.
- offset과 cursor의 응답·정렬·권한 차이와 PostgreSQL 실행계획/간단한
  비교 결과를 설계·lesson 증거에 남긴다.

### 제외

- 기존 offset endpoint, `ExposedPage`, page/size 호출자 제거 또는 변경
- 모든 repository를 한 번에 cursor 방식으로 전환
- 이번 변경에서 새 모듈·의존성·Redis/cache·frontend를 추가
- 공개되지 않은 bluetape4k API를 복제하거나 별도 migration index를 추가

`clinic_id` 단일 index와 primary key 조합의 실제 실행계획이 충분하지 않으면
그 결과를 후속 index 이슈로 남긴다. 이번 범위에서 migration을 추가해 성능을
가정하지 않는다.

## 제안 계약

### Core 모델

`appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/KeysetPagination.kt`
를 새로 만든다.

```kotlin
/** clinic 목록의 정렬 경계를 나타내는 exclusive cursor입니다. */
data class ClinicKeysetCursor(
    val clinicId: Long,
    val id: Long,
)

/** 전체 count 없이 다음 cursor만 제공하는 bounded page입니다. */
data class ClinicKeysetPage<T>(
    val content: List<T>,
    val nextCursor: ClinicKeysetCursor?,
)
```

두 식별자는 양수여야 한다. cursor는 마지막으로 반환한 행을 가리키며,
다음 조회는 그 행을 제외하는 exclusive 경계다.

### Repository

각 repository에 다음 메서드를 추가한다.

```kotlin
fun findKeysetPage(
    scope: TenantClinicScope,
    cursor: ClinicKeysetCursor? = null,
    limit: Int,
): ClinicKeysetPage<Record>
```

구현 규칙:

1. `scope`와 `limit`을 기존 `requirePositiveNumber`/범위 검증 관례에
   맞춰 검증한다.
2. cursor가 있으면 `cursor.clinicId == scope.clinicId`를 요구한다.
3. tenant membership predicate와 clinic predicate를 항상 유지한다.
4. `(clinic_id ASC, id ASC)`로 정렬한다.
5. cursor가 있으면 `(clinic_id > cursor.clinicId) OR
   (clinic_id = cursor.clinicId AND id > cursor.id)` 경계를 적용한다.
   scope가 단일 clinic으로 제한되므로 실제 반환 행은 같은 clinic에 남는다.
6. `limit + 1`건을 조회하고, 초과 행이 있으면 content를 `limit`건으로
   줄인 뒤 마지막 content의 `(clinic_id, id)`를 `nextCursor`로 만든다.
   초과 행이 없으면 `nextCursor = null`이다.
7. `countBy`, `offset`, 전체 목록 materialization은 호출하지 않는다.
8. Exposed 호출은 controller가 제공하는 `transaction {}` 안에서 실행한다.

### API

기존 경로와 응답을 보존하면서 다음 경로를 세 resource에 각각 추가한다.

```text
GET /api/{tenantCode}/clinics/{clinicId}/doctors/cursor
GET /api/{tenantCode}/clinics/{clinicId}/equipments/cursor
GET /api/{tenantCode}/clinics/{clinicId}/treatment-types/cursor
```

요청 query:

| 이름 | 필수 | 기본값 | 규칙 |
|---|---:|---:|---|
| `cursor` | 아니오 | 없음 | 이전 응답의 opaque cursor |
| `limit` | 아니오 | `20` | `1..PaginationDefaults.MAX_PAGE_SIZE`로 clamp |

응답은 `appointment-api`의 새 `KeysetPageResponse<T>`로 직렬화한다.

```json
{
  "success": true,
  "data": {
    "items": [{"id": 101}],
    "nextCursor": "djE6MTA6MTAx"
  }
}
```

`nextCursor`가 `null`이면 더 읽을 행이 없다. `totalCount`, `totalPages`,
`pageNumber`는 cursor 응답에 포함하지 않는다.

cursor codec은 API 모듈의
`ClinicKeysetCursorCodec.kt`에서 `v1:<clinicId>:<id>`를 URL-safe Base64
without padding으로 인코딩한다. 디코딩 시 버전·세그먼트 수·양수 ID·길이를
검증한다. path clinic과 cursor clinic이 다르면 `IllegalArgumentException`을
발생시켜 기존 전역 400 계약으로 보낸다. tenant/clinic 접근 검사는 cursor
디코딩보다 먼저 `TenantClinicAccessChecker.verifyClinic`으로 수행한다.

## 데이터 흐름과 실패 경계

```text
HTTP path/query
  -> clinicId 양수 및 tenant/clinic 권한 확인
  -> cursor decode + path clinic 일치 확인
  -> transaction { repository.findKeysetPage(...) }
  -> limit+1 SQL 결과를 bounded page로 변환
  -> nextCursor encode
  -> ApiResponse<KeysetPageResponse<T>>
```

- `clinicId <= 0`, `limit`의 비정상 값, malformed/negative cursor는 400이다.
- 유효한 tenant가 아닌 path clinic은 기존 `TenantClinicAccessChecker`의
  응답 경계를 그대로 따른다.
- 다른 clinic에서 발급된 cursor는 데이터가 섞이지 않도록 400으로 조기
  거부한다.
- cursor anchor 행이 삭제되어도 `id > anchor` 조건으로 다음 sparse 행을
  계속 읽는다.
- 첫 페이지에서 새 행이 삽입되거나 이전 행이 삭제되어도 이미 반환한
  `(clinic_id,id)`보다 뒤의 행만 읽으므로 중복·offset drift를 피한다.
- DB/transaction 예외는 기존 전역 internal error 경계를 재사용하며 raw SQL나
  cursor 원문을 응답에 반사하지 않는다.

## 호환성과 보안

- 기존 세 endpoint의 method signature, path, `page`/`size` semantics,
  `ExposedPage` JSON을 변경하지 않는다.
- cursor endpoint는 새 응답 타입을 사용하므로 consumer가 `totalCount`를
  기대하는 offset contract와 혼동하지 않는다.
- cursor는 scope-bound opaque 값이지만 인증 토큰이 아니다. 권한 판단은
  항상 path tenant/clinic 검증과 repository predicate가 담당한다.
- codec 입력 길이와 숫자 범위를 제한해 과도한 decode 및 비정상 SQL 조건을
  막는다.

## 검증 설계

### 단위·repository 회귀

- 공통 모델의 양수 검증, encode/decode round trip, malformed/wrong-clinic
  거부를 검증한다.
- 세 repository에서 first/next/last, empty, sparse id, cursor anchor 삭제,
  이후 insert, 다른 tenant/clinic 차단을 활성화된 dialect별로 검증한다.
- `StatementInterceptor`로 cursor query의 SQL을 수집하고 `offset`이
  없으며 `limit + 1`, `clinic_id`, `id` 경계가 포함되는지 확인한다.

### Controller 계약

- 세 controller가 tenant scope를 만들고 repository에 decoded cursor와
  normalized limit을 전달하는지 확인한다.
- 다음 cursor가 API 응답에서 encode되는지 확인한다.
- malformed 및 다른 clinic cursor가 controller 호출에서 400으로 변환 가능한
  `IllegalArgumentException`을 발생시키는지 확인한다.
- 기존 offset method 테스트가 동일하게 통과하는지 확인한다.

### PostgreSQL 증거

- repository integration test에서 singleton PostgreSQL launcher를 사용하고
  `@Testcontainers`는 사용하지 않는다.
- 실제 production-like schema에서 `EXPLAIN (FORMAT JSON)`을 수집해 cursor
  query에 `OFFSET`이 없음을 확인한다.
- 동일 fixture에서 offset과 cursor의 실행 시간/읽은 행 방향을 기록한다.
  작은 fixture 결과는 인덱스 선택을 증명하지 않는다는 caveat를 함께 남긴다.

기준선으로 실행한 `./gradlew :appointment-core:test :appointment-api:test
--no-daemon`은 기존 Docker 환경 초기화 실패로 완료되지 않았다. 구현 후에는
Docker 상태를 확인하고 변경 범위의 core targeted test를 먼저 실행하며,
PostgreSQL 검증은 순차적으로 재실행한다.

## 완료 조건 매핑

| Issue #312 조건 | 사양/검증 근거 |
|---|---|
| 기존 page/size 호출자 불변 | 기존 endpoint와 repository `findPage`를 수정하지 않음 |
| cursor SQL에 OFFSET 없음 | `findKeysetPage` SQL capture + PostgreSQL EXPLAIN |
| tenant/clinic cursor 재사용 거부 | codec/controller wrong-clinic 테스트와 repository scope 검증 |
| 안정적 정렬 | `(clinic_id ASC, id ASC)`와 sparse/concurrent 테스트 |
| 성능 비교 및 채택/보류 결정 | PostgreSQL 실행계획·비교 결과를 lesson에 기록 |

## 승인 게이트

- [x] Issue #312 concrete plan 승인
- [x] keyset 경로 설계 승인
- [ ] 사양 문서 리뷰 승인
- [ ] 구현 계획 리뷰 승인
- [ ] 구현·검증·inline review 완료

## Superpowers Writer DoD

- [x] SPW-01: 대상 독자, 목적, Issue/source ledger, 기술 토큰과 미확인 범위를 고정했다.
- [x] SPW-02: 문제, 경계, 계약, 오류, 호환성, 테스트, 성능, 완료 조건을 포함했다.
- [x] SPW-03: 한국어 기술 문체와 일관된 `cursor`, `tenant`, `clinic`, `offset` 용어를 적용했다.
- [x] SPW-04: 현재 repository/controller/의존 라이브러리 소스와 설계 주장을 대조했다.
- [x] SPW-05: 자체 read-back에서 placeholder, 모순, scope drift가 없음을 확인했다.

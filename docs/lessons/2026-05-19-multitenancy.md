# Multitenancy — issue #16 구현 회고

## 배경

Issue #16은 기존 단일 tenant 예약 API를 tenant-scoped backend로 전환하는 작업이었다.
핵심 변경은 `TenantGroups` 도입, `Clinics`/`Holidays` tenant FK 추가, Flyway V3-V6
마이그레이션, JWT `allowedTenants` 인가, `/api/{tenantCode}/...` route rewrite다.

## 핵심 의사결정

### 1. URL tenantCode + JWT allowedTenants 이중 가드

Tenant 식별은 URL path의 `/api/{tenantCode}/...`로 고정하고, JWT의 `allowedTenants`
claim과 비교한다. 인증이 없으면 tenant DB lookup을 하지 않고 Spring Security 인가 단계로
넘겨 401을 반환한다. 인증된 요청에서 모르는 tenant는 404, JWT tenant mismatch는 403이다.

이 구분을 `TenantContextFilterTest`, `TenantAuthorizationManagerTest`,
`MultitenancyIntegrationTest`로 나눠 검증했다.

### 2. ThreadLocal tenant context는 controller 경계까지만 사용

Suspend service나 core repository 내부에서 `TenantContext.current()`에 의존하지 않는다.
Controller에서 tenant를 해석한 뒤 `tenantGroupId`를 명시적으로 넘기는 방식이 더 안전하다.
`rg -n "TenantContext\\.current\\(\\)" appointment-api/src/main | rg -v "api/tenant/"`
검사에서 tenant package 밖 사용이 0건임을 확인했다.

### 3. Tenant guard는 tenant뿐 아니라 path clinic까지 확인해야 한다

초기 구현은 path `clinicId`를 tenant에 속한 clinic인지 확인했지만, appointment create,
slot lookup, equipment-unavailability 일부 endpoint에서 `doctorId`, `treatmentTypeId`,
`equipmentId`가 같은 clinic에 속하는지 끝까지 묶지 못할 수 있었다.

보강 패턴:
- `TenantClinicAccessChecker.verifySchedulingResources(...)`
- `TenantClinicAccessChecker.verifyEquipment(...)`
- equipment unavailability direct `id`는 tenant + path `clinicId` + path `equipmentId`까지 비교

회귀 테스트:
- `AppointmentControllerTest`: 다른 clinic의 doctor로 예약 생성 시 404
- `SlotControllerTest`: 다른 clinic의 doctor로 slot 조회 시 404
- `EquipmentUnavailabilityControllerTest`: 다른 clinic의 equipment로 조회 시 404

### 4. H2 migration만으로 DDL 안전성을 증명하지 않는다

H2, MySQL, PostgreSQL의 기존 unique/index/drop constraint 문법이 달라서 V3-V6를 DB별로
분리했다. 특히 H2는 `scheduling_holidays` 재생성으로 기존 inline unique를 제거했고,
MySQL/PostgreSQL은 기존 unique constraint/index 이름을 DB별로 제거했다.

검증은 H2 migration test와 PostgreSQL/MySQL profile migration test를 분리해서 수행했다.

### 5. README route drift는 breaking change와 함께 바로 고친다

Backend route가 `/api/...`에서 `/api/{tenantCode}/...`로 바뀌면 root README와
`appointment-api/README.md`, `appointment-api/README.ko.md` 모두 같이 갱신한다.
Frontend tenant routing은 Phase 2 scope로 남기고, local seed tenant는 `tenant-default`로 문서화한다.

## 검증 증거

- `./gradlew :appointment-api:compileKotlin :appointment-api:test --tests "*.AppointmentControllerTest" --tests "*.SlotControllerTest" --tests "*.EquipmentUnavailabilityControllerTest"`: 35 passing
- `./gradlew :appointment-core:compileKotlin :appointment-api:compileKotlin :appointment-core:test --tests "*.TenantGuardRepositoryTest" :appointment-api:test --tests "*.JwtTokenParserTest" --tests "*.TenantContext*Test" --tests "*.TenantPathResolverTest" --tests "*.TenantAuthorizationManagerTest" --tests "*.SecurityProfileAssertionTest" --tests "*.MultitenancyIntegrationTest" --tests "*.MultitenancyMigrationTest"`: API 23 passing, core guard test up-to-date
- Route drift checks:
  - old main/test route literals: 0 hits
  - tenant context use outside `api/tenant`: 0 hits

## Future guards

- New tenant-scoped endpoint tests should include at least one cross-clinic or cross-tenant resource-id negative case.
- Avoid adding service-level `TenantContext.current()` calls; pass `tenantGroupId` explicitly.
- Keep old API docs under `docs/superpowers` as historical design artifacts, but update user-facing README files on route changes.

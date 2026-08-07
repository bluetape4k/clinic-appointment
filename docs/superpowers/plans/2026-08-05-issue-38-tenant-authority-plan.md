# 통합 Tenant 경로 권한 구현 계획

> **에이전트 작업자 필수 안내:** 필수 하위 스킬로 `executing-plans` 또는 저장소에서 승인한 단계별 실행 경로를 사용합니다. 진행 상황은 체크박스(`- [ ]`) 문법으로 추적합니다.

**목표:** 모든 appointment commitment endpoint를 `/api/{tenantCode}/...`로 이동하고, 경로의 tenant와 검증된 JWT membership을 유일한 HTTP tenant authority로 사용합니다.

**아키텍처:** 기존 `TenantContextFilter`, `TenantAuthorizationManager`, `ActorContextResolver`, clinic membership 검사, 내부 `tenantGroupId`/Exposed key를 유지합니다. `/api/v2` Gateway-selected 예외를 tenant-aware route matcher로 바꾸고, commitment actor resolution에 경로 tenant를 명시적으로 전달합니다. tenant header를 추가하거나 database/key migration을 수행하지 않습니다.

**기술 스택:** Kotlin 2.3, Spring Boot 4 MVC/Security, JJWT, Springdoc OpenAPI, JUnit 5, MockK/bluetape4k assertions, Exposed JDBC, coroutine `ThreadContextElement`.

---

## 파일 맵과 소유권

| 책임 | 파일 | 계획한 결과 |
|---|---|---|
| Canonical slug와 pre-auth guard | `TenantCodeRules.kt`, `TenantPathValidationFilter.kt` 생성; `TenantPathResolver.kt`, `JwtTokenParser.kt`, `SecurityConfig.kt` 수정 | 소문자 ASCII tenant-code 규칙 하나를 path, JWT, matcher, actor, error classification이 공유합니다. 잘못된 경로와 `v1`/`v2` root는 JWT parsing 전에 fail closed 합니다. |
| Tenant filter lifecycle | `TenantContextFilter.kt`, `TenantContextFilterTest.kt`, `TenantPathValidationFilterTest.kt` 수정 | Active-tenant lookup 실패는 privacy-safe stable error를 사용하고, 요청 경계에서 오래된 ThreadLocal state를 지우며, 정상 요청은 성공/error/async dispatch 후 context를 복원합니다. |
| Commitment controller | `AdminAppointmentController.kt`, `CustomerAppointmentController.kt`, `AppointmentCommitmentQueryController.kt` 수정; 외부 참조가 없음을 compile로 확인한 경우에만 controller class rename | Class mapping은 `/api/{tenantCode}`를 사용하고 모든 actor resolution에 경로 tenant를 전달합니다. |
| Actor와 service scope 경계 | `AppointmentCommitmentHttpSupport.kt`, `ActorContextResolver.kt`(`ActorContext` data class와 resolver), `TenantAuthorizationManager.kt`, `AppointmentCommitmentAccessResolver.kt`, `ActorContextResolverTest.kt`, `AppointmentCommitmentAccessResolverTest.kt`, controller unit test 수정; `DefaultAppointmentCommitmentApplicationService.kt` call site 점검 | Multi-tenant JWT는 `ActorContext.selectedTenantCode`에서 canonical path tenant 하나를 선택합니다. 모든 downstream scope/consent lookup은 이를 사용하고 tenant-authority `singleOrNull()`은 제거하며 clinic claim은 fail closed를 유지합니다. Cross-layer cache를 추가하지 않고 기존 service call graph로 route-specific lookup bound를 검증합니다. |
| Security 경계 | `SecurityConfig.kt`, `AppointmentCommitmentSecurityIntegrationTest.kt`, `ProfileReevaluationEndpointSecurityTest.kt` 수정 | Commitment 전용 patient/admin/read rule은 tenant-aware이며 generic tenant write보다 먼저 평가됩니다. 열 개 route 모두 role, tenant, invalid-token, active/inactive-tenant 범위를 검증합니다. |
| Stable error routing | `AppointmentCommitmentApiException.kt`, `AppointmentCommitmentExceptionResolutionTest.kt` 수정 | Error registry는 canonical tenant commitment route만 인식하고 reserved-root rejection을 공유합니다. Scope로 숨겨진 resource는 문서화된 403 privacy contract를 유지합니다. |
| HTTP/OpenAPI test | `AppointmentCommitmentOpenApiTest.kt`, `NotificationOpenApiTest.kt`, `AppointmentCommitmentFeatureOffIntegrationTest.kt`, `TenantPathResolverTest.kt` 수정 | 정확한 열 개 path/method operation, required header, error code, legacy alias 부재 및 enabled/disabled route 동작을 검증합니다. |
| Context lifecycle과 suspend 경계 | `TenantContextTest.kt`, `TenantContextFilterTest.kt`, `appointment-api/build.gradle.kts`(기존 `libs.kotlinx.coroutines.test` catalog alias 사용), focused context test 수정; ambient tenant read가 발견된 경우에만 suspend controller 수정 | 불필요한 ambient-context propagation을 추가하지 않고 Thread-local cleanup, nested restoration, servlet async/error 처리 및 coroutine `TenantContextElement` 동작을 검증합니다. 기존 suspend controller는 명시적인 tenant scope contract를 유지합니다. |
| Public/internal 문서 | `docs/requirements/architecture.md`, `docs/api/visit-commitment.md`, `docs/runbooks/visit-commitment-operations.md`, `docs/runbooks/profile-reevaluation.md`, `docs/runbooks/profile-reevaluation.ko.md`, `appointment-api/README.md`, `appointment-api/README.ko.md` 수정 | Active docs와 bilingual module README가 하나의 tenant path contract를 설명합니다. 승인된 spec revision을 기록하고 plan/spec 재승인을 요구하며, 관련 없는 historical spec/plan은 변경하지 않습니다. |

## 추적성 매트릭스

| Spec acceptance criterion | 계획 task |
|---|---|
| 모든 commitment route가 `/api/{tenantCode}/...`를 사용함 | Tasks 1, 2, 3, 4, 6 |
| Multi-tenant JWT가 허용된 path tenant를 선택함 | Tasks 1, 2, 3 |
| 401/403/404 불일치 매트릭스 | Tasks 1, 3, 4 |
| Role과 clinic membership이 약화되지 않음 | Tasks 2, 3 |
| TenantContext cleanup/coroutine propagation | Task 5 |
| Active OpenAPI/docs에 `/api/v2`가 없음 | Tasks 4, 6 |
| 기존 test와 module test가 통과함 | Tasks 8, 9 |
| 내부 key/FK 또는 Exposed 경계 변경이 없음 | Tasks 2, 6, 8 |
| 잘못된 path가 authentication 전에 실패하고 filter envelope가 안정적임 | Tasks 1, 3, 4, 5 |
| 선택된 tenant가 service scope에 도달하고 spoofed header/body가 이를 덮어쓰지 못함 | Tasks 2, 3, 4 |
| Active/historical docs, bilingual README, review/lesson, PR/CI evidence가 완비됨 | Tasks 6, 9 |

## Task 0: 깨끗한 baseline 캡처

**파일:** Product 변경 없음. Workflow receipt와 plan note에 evidence만 기록합니다.

- [ ] **Step 1: Branch와 baseline worktree 확인**

Run:

```bash
git status --short --branch
git rev-parse HEAD origin/develop
```

예상 결과: branch `issue-38-tenant-authority`가 `origin/develop`을 기반으로 하며, 수정된 spec·plan·plan-review artifact commit과 ignore된 임시 workflow evidence가 존재합니다. 구현 전 재승인을 기다립니다.

- [ ] **Step 2: 기존 tenant/security unit baseline 실행**

저장소 context-mode Gradle helper를 통해 순차 실행합니다.

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilterTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.TenantAuthorizationManagerTest' \
  --no-parallel --no-build-cache --rerun-tasks
```

예상 결과: 현재 baseline이 green 상태를 유지합니다(이전 fresh run: 24 tests passed). 실패하면 red test를 작성하기 전에 baseline을 중단하고 진단합니다.

## Task 1: Canonical tenant-code와 route failure contract 고정

**파일:**
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantCodeRules.kt`
- 생성: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathValidationFilter.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolver.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParser.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolverTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathValidationFilterTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/JwtTokenParserTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfigFilterOrderTest.kt`

- [ ] **Step 1: 실패하는 canonical-slug 및 pre-auth test 작성**

`tenant-a`가 resolve되고 `Tenant-A`, `tenant a`, 빈 segment, `v1`, `v2`가 resolve되지 않음을 검증하는 test를 추가합니다. Canonical rule은 Flyway V20과 일치해야 합니다. 즉 소문자 ASCII 영숫자 segment를 단일 hyphen으로 연결하고, dot·underscore·앞뒤 hyphen·반복 hyphen은 허용하지 않으며 slug 길이는 최대 64자입니다. `allowedTenants = ["tenant-a"]`는 parse되고 `allowedTenants = ["Tenant-A"]`는 거부되며, duplicate/maximum-size claim은 bounded 상태로 유지되고 immutable set으로 materialize되는지 JWT test로 검증합니다. Malformed·encoded-ambiguous·reserved root가 JWT parser 호출 전에 privacy-safe 404 envelope로 거부되는지 filter test를 추가합니다. `%2f`, `%2e`, `%5c`, semicolon path parameter, double-encoded separator에 대해 raw `requestURI`와 `servletPath`/path-info 표현을 모두 실행합니다. Decode한 path가 단일 canonical servlet path가 아닌 표현은 Spring Security matching 전에 거부합니다. Parser가 호출되지 않았다는 assertion은 standalone filter 호출이 아니라 spy parser 또는 동등한 filter-chain probe를 포함한 실제 `MockMvc`/security-chain fixture로 실행해야 합니다.

- [ ] **Step 2: 새 test만 실행하여 RED 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathValidationFilterTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --no-build-cache --rerun-tasks
```

예상 결과: 새 uppercase/space JWT, resolver, pre-auth filter case가 현재 mixed-case/reserved-root 동작에서 실패합니다. 관련 없는 failure는 허용하지 않습니다.

- [ ] **Step 3: 공유 rule 하나 구현**

Normalization 없이 다음 형태로 구현합니다.

```kotlin
internal object TenantCodeRules {
    private val CANONICAL = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val RESERVED_ROOTS = setOf("v1", "v2")

    fun isCanonical(value: String): Boolean =
        value.length <= 64 && CANONICAL.matches(value) && value !in RESERVED_ROOTS
}
```

`TenantPathResolver.resolve`는 `/api/` 다음의 첫 segment가 canonical인 경우에만 반환합니다. `JwtTokenParser`는 모든 `allowedTenants` entry에 동일한 rule을 사용합니다. Invalid value는 거부하며 암묵적으로 lower-case로 바꾸지 않습니다. `TenantPathValidationFilter`는 correlation setup 이후, `JwtAuthenticationFilter` 이전에 실행해야 합니다. 이를 `addFilterAfter(tenantPathValidationFilter, correlationIdFilter)` 다음 `addFilterAfter(jwtAuthenticationFilter, tenantPathValidationFilter)`(또는 정확히 동등한 방식)로 명시적으로 설정하고, 두 filter를 모두 correlation 기준으로 배치하지 않습니다. Malformed·encoded-ambiguous·`v1`/`v2` root는 JWT parsing을 호출하지 않고 `RESOURCE_NOT_FOUND`로 거부합니다. 문법상 canonical이지만 존재하지 않는 tenant는 JWT authentication을 계속 수행한 뒤 `TenantContextFilter`가 authenticated `RESOURCE_NOT_FOUND`로 처리합니다.

새 filter는 disabled servlet `FilterRegistrationBean`을 사용하는 Spring bean으로 등록합니다. 이는 correlation/JWT/tenant filter에 사용하는 동일한 `enabled=false` convention이며, filter가 `SecurityFilterChain`에서 한 번만 실행되도록 합니다. Bean registration(`enabled == false`)과 실제 chain order correlation → tenant-path validation → JWT → tenant-context를 검사하는 `SecurityConfigFilterOrderTest`를 추가합니다. Pre-auth filter는 tenant database를 조회하거나 JWT claim을 검사하면 안 됩니다.

- [ ] **Step 4: focused test를 실행하여 GREEN 확인**

Task 1 command를 다시 실행합니다. 예상 결과: 기존 valid lower-case tenant를 포함해 모든 path와 JWT test가 통과합니다.

## Task 2: Commitment actor resolution을 path-scoped로 변경

**파일:**
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentHttpSupport.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContext.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContextResolver.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/TenantAuthorizationManager.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolver.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentController.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/CustomerAppointmentController.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentQueryController.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ActorContextResolverTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolverTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Test.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentRequestV2Test.kt`

- [ ] **Step 1: Multi-tenant resolver regression test 추가**

Actor test fixture을 `allowedTenants = setOf("tenant-a", "tenant-b")`로 확장합니다. `resolve(authentication, "tenant-b", 7L, ...)`가 `selectedTenantCode == "tenant-b"`로 성공하고 `tenant-c`, malformed code, reserved root가 실패하는지 검증합니다. Active tenant A/B와 동일한 multi-tenant actor를 사용해 `AppointmentCommitmentAccessResolverTest`에 service-scope regression을 추가하고, path B가 tenant B row에 도달하며 tenant A data를 B를 통해 조회할 수 없음을 검증합니다. 하나의 universal lookup count를 assertion하지 않습니다. Authenticated request에서 filter는 active-tenant lookup을 정확히 한 번 수행하지만 service call graph에는 route-specific count가 있습니다. `requestAppointment`과 `directCreate`는 `resolvePlan`과 `requireConsentAuthority`를 호출하므로 endpoint lookup 두 번, `decideProposal`과 `directConfirm`은 `requireAppointmentAccess`와 `requireConsentAuthority`를 호출하므로 두 번, `approveProposal`, `declineProposal`, `createChangeProposal`, `query`, `expireProposal`, `cancelAppointment`은 scope resolver 하나를 호출하므로 한 번입니다. Focused test는 정확한 route count를 검증하고 short-circuit case는 실패 지점까지의 bound를 적용해야 합니다. Role-denied path는 filter 1/service 0을 assertion합니다. 기존 clinic mismatch failure도 유지합니다. 현재 helper와 service가 tenant `singleOrNull()`을 사용하므로 selected-tenant와 access-resolver를 바꾸기 전에는 이 test가 실패해야 합니다.

기존 policy/background fixture와 모든 `ActorContext(...)` construction site가 source-compatible 상태를 유지하도록 `ActorContext`에 기본값이 `null`인 `selectedTenantCode: String? = null`을 추가합니다. `rg -n 'ActorContext\(' appointment-api/src/main appointment-api/src/test`로 site를 inventory합니다. 이 field가 필요한 곳은 commitment access boundary뿐이며 field가 null이면 fail closed 해야 합니다. 관련 없는 policy actor에 이를 소급 적용하거나 전체 grant set에서 selected tenant를 조용히 추론하지 않습니다.

- [ ] **Step 2: Helper contract 변경**

Internal extension을 다음 명시적 signature로 변경합니다.

```kotlin
internal fun ActorContextResolver.resolveAppointmentActor(
    authentication: Authentication?,
    tenantCode: String,
    request: HttpServletRequest,
): ActorContext
```

Authenticated principal을 resolve하고 `TenantCodeRules.isCanonical(tenantCode)`를 요구하며, 선택된 `clinicId` membership check를 유지하고 `resolve(authentication, tenantCode, clinicId, correlationId)`를 호출합니다. Resolver는 audit를 위해 full immutable grant set을 보존하면서 canonical path value를 `ActorContext.selectedTenantCode`에 저장합니다. `AppointmentCommitmentAccessResolver.resolveScope`는 `selectedTenantCode`와 active tenant-group lookup을 사용하고 다시 검증해야 합니다. `allowedTenantCodes.singleOrNull()`에서 authority를 추론하면 안 됩니다. Tenant header, body field, internal tenant ID를 받지 않습니다. Unknown body field는 기존 400 contract를 유지하며 known consent `evidenceAuthority`가 selected tenant namespace와 일치하지 않으면 문서화된 403을 반환합니다.

- [ ] **Step 3: 모든 commitment controller mapping과 call site 변경**

다음 class mapping을 사용합니다.

```kotlin
@RequestMapping("/api/{tenantCode}")
// Query controller:
@RequestMapping("/api/{tenantCode}/appointments")
```

모든 commitment handler에 `@PathVariable tenantCode: String`을 추가하고 `resolveAppointmentActor`에 전달합니다. 새 path가 승인된 spec table과 일치하도록 resource suffix는 변경하지 않습니다. Class를 rename한다면 `AdminAppointmentController`/`CustomerAppointmentController`와 직접 참조하는 unit test를 함께 rename합니다. Duplicate bean이나 legacy route alias는 유지하지 않습니다.

- [ ] **Step 4: Controller/unit test를 실행하여 GREEN 확인**

Run:

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentAccessResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AdminAppointmentV2Test' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AppointmentRequestV2Test' \
  --no-parallel --no-build-cache --rerun-tasks
```

예상 결과: Multi-tenant path selection이 service의 selected tenant scope에 도달하고, direct controller call이 새 path argument와 함께 compile되며, cross-tenant/clinic data는 계속 접근할 수 없습니다. State-machine/key 동작은 변경되지 않습니다.

## Task 3: v2 Security matcher를 tenant-aware commitment rule로 교체

**파일:**
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentSecurityIntegrationTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/ProfileReevaluationEndpointSecurityTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/TenantAuthorizationManagerTest.kt`

- [ ] **Step 1: 실패하는 authorization matrix case 추가**

기존 singleton integration fixture를 통해 active `tenant-default`와 `tenant-other`, inactive tenant, authenticated missing tenant를 seed합니다. 허용되는 각 route에 대해 정확한 application-service method를 deterministic success response로 stub하고 selected tenant/clinic scope로 호출되었는지 검증합니다. Response header/body marker는 보조 assertion으로만 사용합니다. 이렇게 하면 stub하지 않은 downstream call이 만든 2xx/4xx가 matcher 성공으로 오인되지 않습니다. Integration test path를 `/api/tenant-default/...`로 바꾸고 다음 열 개 route 전체를 검증합니다.

```text
POST /api/tenant-default/appointment-requests: PATIENT + allowed tenant -> post-auth marker; ADMIN/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/admin/appointments: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/approve: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/confirm: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/proposals/{proposalId}/expire: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/cancel: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/change-proposals: ADMIN -> post-auth marker; PATIENT/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/proposals/{proposalId}/accept: PATIENT -> post-auth marker; ADMIN/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
POST /api/tenant-default/appointments/{id}/proposals/{proposalId}/decline: PATIENT -> post-auth marker; ADMIN/STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
GET /api/tenant-default/appointments/7/commitment: ADMIN or PATIENT -> post-auth marker; STAFF/DOCTOR/SYSTEM -> 403 `SCOPE_FORBIDDEN`
Any route with a JWT allowed only for tenant-default under `/api/tenant-other/...`: 403 `FORBIDDEN` from the tenant filter; unknown or inactive authenticated tenant: 404 `RESOURCE_NOT_FOUND`; missing, invalid, or expired JWT: 401 `UNAUTHORIZED`.
Malformed/uppercase/space/encoded-ambiguous/v1/v2 roots: pre-auth 404 `RESOURCE_NOT_FOUND` and JWT parser is not invoked.
The same multi-tenant JWT with `allowedTenants={tenant-default,tenant-other}` must select the path tenant and reach the matching tenant group; an appointment in another clinic remains 403 `SCOPE_FORBIDDEN` without revealing data.
Conflicting `X-Tenant-Code`, `X-Clinic-Id`, or `tenantGroupId` headers/body fields never override the path. Unknown fields are 400; known consent namespace conflicts are 403.
```

기존 singleton infrastructure와 `TestJwtProvider`를 사용합니다. 저장소의 `API_INTEGRATION_RESOURCE`/명시적 database resource lock 아래에서 `SchemaUtils.createMissingTablesAndColumns`와 `Table.deleteAll()`로 tenant row를 생성하고 정리합니다. `@Testcontainers`나 raw container는 추가하지 않습니다.

Fixture는 기본 `tenant-default` seed에 의존하지 않고 namespaced active A/B tenant(예: `issue38-a`/`issue38-b`), inactive tenant, missing-code case를 deterministic하게 seed해야 합니다. 공유 Spring-context seed를 보존하고 이 namespaced row만 dependency-safe order로 삭제합니다. Cached integration context가 `tenant-default`를 다시 seed하지 않으므로 모든 tenant/clinic table을 비우면 안 됩니다. Exposed `Database.connect` setup 주변에서 `TransactionManager.defaultDatabase`를 저장·복원하고 setup 또는 teardown이 실패해도 원래 default를 복원합니다. 이를 통해 병렬 integration class가 process-global Exposed database를 변경하지 못하게 합니다.

- [ ] **Step 2: Matrix를 실행하여 RED 확인**

`AppointmentCommitmentSecurityIntegrationTest`와 `TenantAuthorizationManagerTest`만 실행합니다. 예상 결과: 새 tenant path는 현재 404를 반환하거나 generic role rule로 흘러 matcher/controller 경계가 아직 migration되지 않았음을 보여야 합니다. Malformed matcher variable은 fail closed 해야 합니다. Raw `context.variables["tenantCode"]`가 uppercase, space 포함, `v1`/`v2`, encoded/ambiguous인 manager case를 추가하고 matcher variable을 신뢰하지 않은 채 모두 deny되는지 검증합니다.

- [ ] **Step 3: Generic tenant write보다 먼저 explicit matcher 구현**

모든 `/api/v2/**` matcher를 제거하고 broad `GET/POST/PATCH/DELETE /api/{tenantCode}/**` rule보다 앞에 다음 rule을 추가합니다.

```kotlin
.requestMatchers(HttpMethod.POST, "/api/{tenantCode}/appointment-requests")
    .access(patientTenantAccess(tenantAuthorizationManager))
.requestMatchers(HttpMethod.POST,
    "/api/{tenantCode}/appointments/*/proposals/*/accept",
    "/api/{tenantCode}/appointments/*/proposals/*/decline",
).access(patientTenantAccess(tenantAuthorizationManager))
.requestMatchers(HttpMethod.GET, "/api/{tenantCode}/appointments/*/commitment")
    .access(commitmentReadTenantAccess(tenantAuthorizationManager))
.requestMatchers(HttpMethod.POST,
    "/api/{tenantCode}/appointments/*/approve",
    "/api/{tenantCode}/appointments/*/confirm",
    "/api/{tenantCode}/appointments/*/proposals/*/expire",
    "/api/{tenantCode}/appointments/*/cancel",
    "/api/{tenantCode}/appointments/*/change-proposals",
).access(commitmentAdminTenantAccess(tenantAuthorizationManager))
```

`patientTenantAccess`는 `hasRole(PATIENT)`과 tenant manager를 결합하고, `commitmentReadTenantAccess`는 `hasAnyRole(ADMIN, PATIENT)`과 manager를 결합하며, `commitmentAdminTenantAccess`는 `hasRole(ADMIN)`과 manager를 결합합니다. Direct creation은 기존 tenant-aware `/api/{tenantCode}/admin/**` rule이 계속 담당합니다. Generic STAFF write access가 commitment mutation에 도달하지 못하도록 specific admin rule이 필요합니다. Spring이 `context.variables["tenantCode"]`를 제공하더라도 `TenantAuthorizationManager`는 membership 전에 matcher variable을 canonicalize하고 reserved-root를 검사해야 합니다. Raw matcher input을 신뢰하면 안 됩니다.

Tenant path validation filter는 correlation 이후, JWT authentication 이전에 등록하고 servlet registration은 disabled로 설정해 security chain 밖에서 실행되거나 chain 주변에서 두 번 실행되지 않게 합니다. Tenant context filter는 JWT authentication 이후에 두고 동일하게 chain-only로 유지합니다. Tenant lookup/membership failure는 의도적으로 기존 foundation envelope(`RESOURCE_NOT_FOUND`/`FORBIDDEN`)를 사용하고, endpoint role/clinic failure는 commitment envelope(`SCOPE_FORBIDDEN`)를 사용합니다. 이 구분은 public matrix의 일부이며 filter-level test로 assertion합니다.

- [ ] **Step 4: Integration matrix를 실행하여 GREEN 확인**

Integration fixture와 unit matcher test에 대해 Task 3 test command를 별도의 `--no-parallel --rerun-tasks` invocation으로 실행합니다. 현재 test resource가 global parallel execution을 비활성화하더라도 Exposed default database는 process-global이므로 명시적 serialization/resource lock을 유지합니다. 예상 결과: 열 개 route rule이 정확히 한 번 선택되고 status/error envelope가 matrix와 일치하며, post-auth marker가 matcher 성공을 입증하고 generic tenant route는 기존 동작을 유지합니다.

## Task 4: Stable commitment error routing과 OpenAPI test 갱신

**파일:**
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentApiException.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentExceptionResolutionTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentOpenApiTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/NotificationOpenApiTest.kt`
- 테스트: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentFeatureOffIntegrationTest.kt`

- [ ] **Step 1: 모든 v2 path fixture를 canonical tenant path로 교체**

구체적인 MVC path에는 `tenant-default`를 사용하고 OpenAPI path에는 `{tenantCode}`를 사용합니다. `AppointmentCommitmentExceptionResolutionTest`에서 실패하는 controller mapping을 `/api/{tenantCode}/appointments/{id}/commitment`로 갱신하고 `/api/v2/other` 및 다른 모든 reserved-root commitment shape가 classifier에 의해 인식되지 않음을 assertion합니다. Profile actuator의 legacy `/api/v2/profileReevaluation` guard에 대한 명시적 negative test는 유지합니다.

- [ ] **Step 2: Canonical path classifier 구현**

Item/proposal regex와 `/api/{tenantCode}/appointment-requests`, `/api/{tenantCode}/admin/appointments`에 대한 exact check에서 공유 `TenantCodeRules.isCanonical`을 사용합니다. 여기에는 database-compatible lower-case alnum/hyphen rule과 reserved `v1`/`v2` root가 포함됩니다. 모든 `/api/{tenantCode}/**` path를 commitment error로 분류하지 않습니다. 관련 없는 API는 자체 error registry를 유지해야 합니다. 현재 fail-closed scope contract도 유지합니다. Tenant/clinic mismatch와 scope된 commitment/proposal이 없는 경우 모두 403 `SCOPE_FORBIDDEN`을 반환하고, 404는 filter boundary의 malformed/reserved path와 unknown/inactive tenant group에만 사용합니다. 이 issue에서 authorized absence를 구분하려고 별도 existence query를 추가하지 않습니다. `AppointmentCommitmentAccessResolver`, controller OpenAPI, test가 이 privacy contract와 일치하도록 맞춥니다.

- [ ] **Step 3: OpenAPI와 feature-off 동작 검증**

Enabled OpenAPI test는 정확한 열 개 path/method operation, unique operation ID, required header, success/error response set, duplicate/shadow operation 부재를 assertion해야 합니다. 또한 canonical lower-case alnum/hyphen pattern과 reserved-root description을 가진 required `tenantCode` path parameter와 `/api/v2` 부재도 확인해야 합니다. Disabled test는 active tenant를 seed하고 `/api/tenant-default/appointments/7/commitment` 및 모든 legacy `/api/v2` commitment shape를 호출해 모두 404를 기대하며, 새 commitment path가 OpenAPI에서 빠져 있음을 assertion해야 합니다. Query operation은 data-existence-sensitive 404를 약속하는 대신 fail-closed scope absence에 대한 403 `SCOPE_FORBIDDEN`을 문서화합니다.

- [ ] **Step 4: focused HTTP/document test를 실행하여 GREEN 확인**

Focused HTTP/document test를 `--no-build-cache --rerun-tasks`와 함께 별도의 `--no-parallel` invocation으로 실행합니다. 예상 결과: Stable commitment/foundation error envelope와 정확한 route inventory가 확인되고 OpenAPI path는 통합 tenant contract만 사용합니다.

## Task 5: Tenant-context와 coroutine lifecycle 검증 완료

**파일:**
- 수정: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilter.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathResolverTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantPathValidationFilterTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextFilterTest.kt`
- 수정: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/tenant/TenantContextTest.kt`
- 수정: `appointment-api/build.gradle.kts`에 이미 catalogued된 `kotlinx-coroutines-test` test dependency 추가
- 수정: 기존 integration contract에서 필요할 때만 focused suspend-controller/context test와 `junit-platform.properties` resource-lock 사용

- [ ] **Step 1: 기존 version-root test를 legacy rejection coverage로 교체**

`/api/v2/...`는 tenant로 resolve되지 않는 reserved legacy path로 유지하고, lower-case path 성공과 upper-case/space/encoded-ambiguous rejection을 추가합니다. Pre-auth filter test는 malformed/reserved root에서 JWT parser가 호출되지 않음을 입증해야 합니다. 이전 commitment route 열 개 shape 전체에 대해 table-driven negative request를 추가하고 별도의 profile-reevaluation 404 guard도 유지합니다.

- [ ] **Step 2: 전파와 cleanup에 coroutine-test 사용**

Context propagation test를 `runTest`로 바꾸고 `withContext(Dispatchers.Default)`를 사용해 실제 dispatcher hop에서 `TenantContextElement.updateThreadContext`/`restoreThreadContext`가 동작함을 검증합니다. Nested A→B→A restoration, throwing-block restoration, parallel child cleanup, cancellation assertion을 추가합니다. `TenantContextFilter`는 request entry/finally에서 stale context를 지우고 tenantless/error path에서는 이전 request state를 복원하지 않아야 합니다. Tenant-group lookup failure는 기존 privacy-safe `PlanFoundationError.INTERNAL_ERROR`(general request의 HTTP 500) 또는 `SchedulingPolicyErrorCode.POLICY_INTERNAL_ERROR`(policy request)로 변환해야 하며, 어느 outage path도 404/403으로 mapping하면 안 됩니다. Internal `tenantGroupId`를 노출하는 기존 success debug field를 제거합니다. Outage log에는 correlation ID와 sanitized tenant code만 포함하고 token이나 internal identifier는 포함하지 않으며, response/error-log test로 이 경계를 assertion합니다. `TenantPathValidationFilter`는 correlation 이후 JWT 이전에 등록하고, `TenantContextFilter`는 JWT 이후에 등록하며 두 servlet registration은 disabled로 둡니다. 기존 `AppointmentController`와 `NotificationOperationsController`는 이미 explicit tenant scope를 전달하므로 실제 ambient read가 구현 점검에서 발견되지 않는 한 변경하지 않습니다. 이 issue만을 위해 `withContext` propagation을 도입하지 않습니다. 명시적 `ActorContext.selectedTenantCode`가 commitment authority로 남습니다. `GlobalScope`, manual continuation, swallowed cancellation은 사용하지 않습니다.

- [ ] **Step 3: Tenant lifecycle test 실행**

`TenantPathResolverTest`, `TenantPathValidationFilterTest`, `TenantContextFilterTest`, `TenantContextTest`를 각각 별도의 `--no-parallel --no-build-cache --rerun-tasks` invocation으로 실행합니다. 예상 결과: pre-auth rejection, active/inactive/unknown tenant envelope, DB-failure cleanup, stale-thread isolation, nested restoration, coroutine element dispatcher propagation, servlet async/error cleanup이 모두 통과합니다. Production ambient propagation을 주장하지 않습니다. 현재 suspend controller는 explicit `TenantClinicScope`를 전달하므로 실제 ambient consumer가 구현 점검에서 발견되지 않는 한 test는 reusable element와 filter lifecycle만 검증합니다. Filter fixture는 `transaction {}` 내부에서 `SchemaUtils.createMissingTablesAndColumns`와 `Table.deleteAll()`을 사용하고 명시적 shared database/resource lock을 사용해야 합니다. Process-global H2 connection이 누출되지 않도록 `try/finally`에서 원래 `TransactionManager.defaultDatabase`를 저장하고 복원해야 합니다.

## Task 6: Active documentation과 bilingual module README 갱신

**파일:**
- 수정: `docs/requirements/architecture.md`
- 수정: `docs/api/visit-commitment.md`
- 수정: `docs/runbooks/visit-commitment-operations.md`
- 수정: `docs/runbooks/profile-reevaluation.md`
- 수정: `docs/runbooks/profile-reevaluation.ko.md`
- 수정: `appointment-api/README.md`
- 수정: `appointment-api/README.ko.md`

- [ ] **Step 1: ADR-14 exception 교체**

Authority table을 변경해 모든 HTTP appointment route가 path-selected임을 명시합니다. JWT `allowedTenants`는 membership proof이고 header는 authority가 아니며 `/api/v2`는 지원하는 version root가 아니고 internal key는 server-side에 남는다고 적습니다. 명시적 background/coroutine scope rule은 유지합니다. Canonical database-compatible slug pattern, filter와 endpoint의 error code 차이, fail-closed 403 scope contract를 문서화합니다(404는 malformed/reserved path와 unknown/inactive tenant group에만 사용하며 existence-sensitive aggregate distinction은 약속하지 않음).

- [ ] **Step 2: API/runbook example 갱신**

Active visit-commitment와 cancellation example을 `/api/{tenantCode}` path로 바꾸고 path slug를 포함하는 `allowedTenants`를 보여 줍니다. Profile reevaluation의 “not `/api/v2/**`” warning은 “not exposed under tenant appointment routes”로 다시 표현하고 actuator의 실제 `/actuator/profileReevaluation` path는 유지합니다. Rollout checklist를 추가합니다. Commitment `api-enabled=false`를 유지하거나 old pod를 drain하고, controller/security/filter/docs를 atomic하게 배포하며, 모든 pod가 새 버전이 된 뒤에만 client/Gateway를 전환합니다. 새 route 열 개와 legacy negative 열 개를 smoke-test하고 401/403/404/error counter를 관찰하며, old-route smoke와 readiness check가 통과한 뒤에만 rollback합니다. Mixed old/new pod traffic은 명시적으로 지원하지 않습니다.

- [ ] **Step 3: English/Korean README parity 유지**

두 module README의 class table과 authentication section을 갱신합니다. English README는 English로, Korean README는 Korean으로 유지하며 identifier, URL, command, exact error text만 변경하지 않습니다. Visual review에 의존하지 말고 commitment route table과 authentication contract에 대해 parity check를 실행합니다.

- [ ] **Step 4: Active docs 검증**

Run:

```bash
rg -n 'api/v2' docs/api docs/runbooks docs/requirements/architecture.md appointment-api/README.md appointment-api/README.ko.md
rg -n '\bv2\b|version root|V2' docs/api docs/runbooks docs/requirements/architecture.md appointment-api/README.md appointment-api/README.ko.md
rg -n 'api/v2' appointment-api/src/main --glob '*.kt' --glob '!build/**'
rg -n 'api/v2' . --glob '*.md' --glob '*.kt' --glob '!build/**' | tee tmp/issue38-api-v2-residuals.txt
comm -3 \
  <(rg -o '/api/\{tenantCode\}/[^`| ]+' appointment-api/README.md | sort -u) \
  <(rg -o '/api/\{tenantCode\}/[^`| ]+' appointment-api/README.ko.md | sort -u)
git diff --check
```

예상 결과: Active API/runbook/README route 또는 request example에서 `/api/v2`를 사용하지 않습니다. Root가 reserved/unsupported라는 짧은 명시 문구만 허용합니다. 그 문구 외에는 stale version-root/V2 wording이 남지 않아야 합니다. 남은 모든 `api/v2` hit는 명시적 historical 또는 negative-test allowlist로 분류하고 캡처한 inventory를 review evidence에 첨부합니다. README route/authentication entry는 두 언어에서 동등하게 다룹니다.

## Task 7: 동작이 green이 된 뒤에만 내부 V2 controller symbol rename

**파일:**
- 이름 변경: `AdminAppointmentV2Controller.kt` → `AdminAppointmentController.kt`
- 이름 변경: `CustomerAppointmentV2Controller.kt` → `CustomerAppointmentController.kt`
- 해당 controller unit test와 `appointment-api/README.md`, `README.ko.md` 수정

- [ ] **Step 1: 외부 symbol dependency가 없는지 확인**

Run:

```bash
rg -n 'AdminAppointmentV2Controller|CustomerAppointmentV2Controller' appointment-api --glob '!build/**'
```

예상 결과: 두 production file, 직접 참조하는 unit test, README class table만 나열됩니다. Legacy-negative test의 route string은 동작 fixture이며 symbol dependency가 아닙니다.

- [ ] **Step 2: Mapping을 바꾸지 않고 symbol rename 및 갱신**

`git mv`를 사용하고 class/test reference를 갱신하며 동일한 constructor dependency를 유지합니다. Alias class나 두 번째 Spring bean을 추가하지 않습니다.

- [ ] **Step 3: 영향받는 test source compile**

Task 2의 controller unit test를 실행합니다. 예상 결과: Symbol cleanup으로 인한 동작 또는 route regression이 발생하지 않습니다.

## Task 8: 비례적 검증과 최종 diff 수렴

**파일:** Tasks 1–7에서 변경한 모든 파일; spec update 없이 새 scope를 추가하지 않습니다.

- [ ] **Step 1: 영향 범위가 가장 작은 test를 다시 실행**

Complete focused matrix를 별도 invocation으로 실행합니다. Exposed를 사용하지 않는 pure unit class만 함께 실행하고, 모든 DB-backed 또는 Spring-context class는 각자 `--no-parallel` invocation으로 실행합니다. 현재 test resource가 global parallel execution을 비활성화하더라도 class-mode 설정과 process-global Exposed default database 때문에 명시적 serialization과 resource lock이 더 안전한 증거입니다.

```bash
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.JwtTokenParserTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ActorContextResolverTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.config.AppointmentCommitmentExceptionResolutionTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.security.TenantAuthorizationManagerTest' \
  --no-parallel --no-build-cache --rerun-tasks

./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantPathValidationFilterTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.SecurityConfigFilterOrderTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextFilterTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.tenant.TenantContextTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.service.AppointmentCommitmentAccessResolverTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentSecurityIntegrationTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentOpenApiTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.AppointmentCommitmentFeatureOffIntegrationTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.security.ProfileReevaluationEndpointSecurityTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.NotificationOpenApiTest' \
  --no-parallel --no-build-cache --rerun-tasks
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AdminAppointmentV2Test' \
  --tests 'io.bluetape4k.clinic.appointment.api.controller.AppointmentRequestV2Test' \
  --no-parallel --no-build-cache --rerun-tasks
```

예상 결과: 선택한 모든 test가 통과하고 pre-auth parser ordering, selected downstream tenant, controller mapping, context cleanup, scope error, legacy negative를 다시 검증합니다. Container-backed integration 작업은 계속 sequential로 실행합니다.

- [ ] **Step 2: 영향받는 module 전체 test 실행**

Run:

```bash
./gradlew :appointment-api:test --no-parallel --no-build-cache --rerun-tasks
```

예상 결과: `appointment-api` test task가 통과합니다. 실제 test count와 기존 deprecation warning은 failure와 분리해 기록합니다.

- [ ] **Step 3: Static 및 scope check 실행**

Run:

```bash
git diff --check
rg -n 'api/v2' appointment-api/src/main --glob '*.kt' --glob '!build/**'
rg -n 'singleOrNull\(\)' \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentHttpSupport.kt \
  appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolver.kt
rg -n 'api/v2' appointment-api/src/test --glob '*.kt' --glob '!build/**' | tee tmp/issue38-api-v2-test-residuals.txt
git status --short
```

예상 결과: Production `api/v2` route, classifier, actor resolver 어디에도 제거한 v2 authority가 남지 않습니다. Migration 뒤 `appointment-api/src/main`의 literal `api/v2` hit는 0이어야 합니다(profile-reevaluation guard는 별도의 non-API path에서 test하므로 allowlist exception이 아닙니다). Test residual inventory는 legacy-negative route fixture, feature-off assertion, profile-reevaluation protection만 allowlist에 포함하며 다른 hit는 blocker입니다. 두 authority file에는 `singleOrNull()` tenant selection이 없어야 합니다(다른 domain의 관련 없는 `singleOrNull()`은 blocker가 아님). 관련 없는 file은 변경하지 않습니다.

- [ ] **Step 4: Kotlin final checklist와 performance/stability scan 실행**

Final diff에서 새 `!!`, coroutine dispatcher의 blocking call, swallowed cancellation, Exposed transaction drift, lifecycle cleanup, matcher ordering, route-level authorization cache 동작을 점검합니다. Bounded route/JWT/context 작업을 relevant hot path로 보고 기존 JWT limit(token ≤8192 bytes, tenant claim count ≤64, tenant code ≤64 characters)을 유지합니다. Boundary/duplicate/max-cardinality test를 추가하고 MockK/fixture counter로 filter가 active-tenant lookup을 정확히 한 번 수행하는지 확인합니다. 각 service 도달 route도 기존 call-graph budget 안에 있어야 합니다: `requestAppointment`, `directCreate`, `decideProposal`, `directConfirm`은 endpoint lookup 두 번, `approveProposal`, `declineProposal`, `createChangeProposal`, `query`, `expireProposal`, `cancelAppointment`은 한 번이며 role-denied path는 filter 1/service 0입니다. No-cache 결정을 문서화합니다. Cross-layer cache를 추가하지 않고 기존 service call graph를 이 issue 때문에 확장하지 않습니다. Performance N/A라고 하지 말고 focused test에서 route-specific assertion을 기록하며 JMH dependency는 필요하지 않습니다.

## Task 9: Review, lesson 및 delivery handoff

**파일:**
- 생성: `docs/reviews/2026-08-05-issue-38-tenant-authority-plan-review.ko.md` (plan gate)
- 생성: `docs/reviews/2026-08-05-issue-38-tenant-authority-review.ko.md` (final diff gate)
- 생성: `docs/lessons/2026-08-05-issue-38-tenant-authority.md`
- branch validation 이후에만 PR body 수정

- [ ] **Step 1: Six-perspective final review 실행**

정확한 diff를 기준으로 security, stability, performance, API contract, Kotlin quality, documentation을 review합니다. P0/P1은 0이어야 합니다. Korean review artifact에 route inventory, 401/403/404 matrix, filter error envelope, selected-tenant proof, lookup-count/no-cache rationale, rollout checklist, test command/result, residual risk를 기록합니다. Review artifact는 Korean-first이며 English는 code, command, identifier, exact error code에만 남깁니다.

- [ ] **Step 2: PR 생성 전에 lesson 기록**

Client가 제공한 header는 tenant authority가 될 수 없고 hidden Gateway mode보다 explicit path selector 하나가 바람직하다는 durable decision을 기록합니다. Multi-tenant selection, pre-auth ambiguity rejection, filter ordering, fail-closed scope error, lookup bound, legacy `/api/v2` rejection을 입증하는 exact test를 포함합니다. Lesson은 Korean으로 작성하고 English identifier/command는 보존합니다.

- [ ] **Step 3: Lore trailer와 함께 implementation, review, lesson commit**

Diff가 독립적으로 review할 수 있을 만큼 크면 focused commit을 분리합니다. 모든 commit에는 `Constraint`, `Rejected`, `Confidence`, `Scope-risk`, `Directive`, `Tested`, `Not-tested` trailer가 있어야 합니다.

- [ ] **Step 4: English issue-linked PR 게시 및 검증**

`issue-38-tenant-authority`를 push하고 `develop`을 base로 PR을 생성하며 `Closes #38`을 연결합니다. Issue assignee/label/milestone parity를 유지하고 `## DoD Status`를 PR의 마지막 section으로 둡니다. CI를 기다리기 전에 다음 명령으로 live body와 정확한 remote head를 확인합니다.

```bash
gh pr view <number> --json body,headRefName,headRefOid,baseRefName,labels,assignees,milestone,statusCheckRollup,reviews,reviewThreads,closingIssuesReferences
git rev-parse HEAD origin/issue-38-tenant-authority
```

CI가 끝나면 정확한 `headRefOid`를 기준으로 같은 readback을 반복합니다. 모든 required check가 성공했고 모든 review thread가 resolved이며 final body section이 `## DoD Status`이고 issue/PR metadata가 여전히 일치하는지 확인합니다. Merge 전에 정확한 head를 다시 readback하고 해당 SHA에 연결된 fresh user approval을 받습니다. Auto-merge는 활성화하지 않습니다.

## 위험 예측과 재실행 지점

| 위험 | 신호 | 완화책 | 재실행 지점 / rollback |
|---|---|---|---|
| Security matcher 순서로 STAFF가 admin commitment mutation에 도달함 | Integration test에서 STAFF가 403 대신 domain response를 반환함 | Generic tenant write보다 explicit commitment admin rule을 앞에 두고 role matrix를 test | Task 3 재실행; 해결되지 않으면 matcher-only commit revert |
| 숨은 `singleOrNull()` 때문에 multi-tenant JWT가 계속 거부됨 | Selected path에서 allowed tenant set size 2가 실패함 | Helper를 통해 path tenant를 전달하고 `ActorContextResolver`에서 membership 재검증 | Task 2 재실행; helper/controller commit revert |
| Service scope resolution 전에 selected path tenant가 사라짐 | A/B JWT가 controller까지 도달했지만 `AppointmentCommitmentAccessResolver`가 tenant를 선택하지 못하거나 잘못 선택함 | `ActorContext`에 `selectedTenantCode`를 저장하고 access resolver에서 검증하며 tenant B에 대한 정확한 service call assertion | Task 2와 access-resolver matrix 재실행; authority `singleOrNull()`이 하나라도 남아 있으면 merge 금지 |
| Unknown/invalid tenant가 data 또는 잘못된 error envelope를 노출함 | Unknown path가 200을 반환하거나 403/404 mismatch가 발생함 | 공유 canonical rule, filter DB lookup, path-specific error registry test | Tasks 1, 4 재실행; route commit revert |
| Pre-auth path ambiguity가 JWT 또는 matcher까지 도달함 | `%2f`, `%2e`, `%5c`, semicolon, double-encoded path에서 JWT parser invocation 또는 controller marker가 나타남 | JWT 전에 raw/decoded URI 형태를 검증하고 malformed/reserved root를 foundation 404로 거부하며 chain-only filter order를 입증 | Task 1/3 pre-auth test 재실행; parser가 호출되면 merge 금지 |
| 새 validation filter가 두 번 실행되거나 순서가 틀림 | Filter가 chain 밖, correlation 전, 또는 request마다 두 번 실행됨 | Disabled servlet registration을 추가하고 correlation → validation → JWT → tenant-context 순서를 assertion | Task 1/3 security configuration test 재실행 |
| 기존 API route가 commitment route와 충돌함 | Spring mapping ambiguity 또는 OpenAPI duplicate path | Commitment suffix를 기존 appointment base 아래에 유지하고 context/OpenAPI test 실행 | Merge 전에 중단; route mapping correction을 후속 작업으로 분리 |
| Servlet/coroutine 경계를 넘어 TenantContext가 누출됨 | Filter/test 이후에도 context가 남거나 다른 dispatcher에 나타남 | `try/finally`/ThreadContextElement와 coroutine-test로 검증 | Task 5 재실행; cleanup이 입증될 때까지 merge 금지 |
| Tenant lookup outage가 tenant 부재 또는 authorization failure로 잘못 보고됨 | DB exception이 404/403으로 변환되거나 stack trace를 노출함 | Lookup failure를 기존 privacy-safe internal error로 mapping하고 correlation-only logging을 적용하며 cleanup과 response code assertion | Task 5 재실행; failure contract가 안정될 때까지 merge 보류 |
| 중복 tenant lookup으로 hot path 작업량이 무제한 증가함 | 예상 밖 repository call count 또는 latency 증가 | Filter lookup을 한 번으로 유지하고 기존 route-specific service budget(Endpoint lookup 1회 또는 2회)을 강제하며 role-denied path는 filter 1회에서 중단. 명시적 no-cache 결정을 기록 | Task 8 performance/stability scan 재실행; 측정된 regression은 별도 후속 처리 |
| Documentation/client example이 code와 어긋남 | Active `rg api/v2` hit 또는 bilingual README mismatch | 같은 branch에서 active docs를 갱신하고 scope search 실행 | Task 6 재실행; parity가 복구될 때까지 PR 보류 |
| Mixed old/new pod가 호환되지 않는 route contract를 노출함 | Rolling deployment 또는 rollback 중 request가 실패함 | api-enabled/drain gate, atomic controller/security/docs cutover, readiness/smoke check를 요구하고 mixed traffic unsupported를 문서화 | Rollout을 중단하고 runbook rollback checklist 실행 |
| 광범위한 route 변경으로 예상하지 못한 HTTP 동작 또는 latency가 발생함 | Full module failure 또는 integration timeout | DB/query/key는 변경하지 않고 sequential integration test와 diff scan 실행 | 전체 PR revert; compatibility alias를 암묵적으로 추가하지 않음 |

## Self-review 결과

- 모든 spec acceptance criterion은 traceability matrix의 하나 이상의 task에 매핑됩니다.
- 정의되지 않은 symbol에 의존하는 task가 없으며 새 helper signature와 matcher helper를 명시적으로 제시했습니다.
- 각 behavior change 전에 예상 결과와 함께 RED/GREEN command가 있습니다.
- 승인된 spec revision과 plan-review artifact가 새로운 acceptance/security clarification을 기록하며, 결정 이력을 보존하기 위해 관련 없는 historical document는 변경하지 않습니다.
- 새로운 external dependency, migration, key/FK 또는 production database query 변경은 계획하지 않습니다. 기존 `kotlinx-coroutines-test` catalog alias만 test scope에 연결할 수 있습니다.

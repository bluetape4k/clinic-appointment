# Issue #38 — 단일 tenant path authority 설계

## 결정 요약

모든 비공개 appointment API는 `/api/{tenantCode}/...`를 유일한 외부
tenant 선택 방식으로 사용한다. 기존 `/api/v2/...` Gateway-selected 예외는
제거한다. `tenantCode`는 호출자가 제시하는 routing 입력일 뿐이며, 실제 권한은
검증된 JWT의 `allowedTenants` membership과 활성 `TenantGroup` 조회를 함께
통과해야 한다.

이 변경은 아직 소비자가 고정되지 않은 예제 애플리케이션의 API 일관성을
우선한다. 내부 `tenantGroupId`, clinic ownership, Exposed key/FK 구조는
변경하지 않는다.

## 현재 근거

- 일반 appointment, clinic, policy, waitlist, notification API는 이미
  `/api/{tenantCode}/...`를 사용한다.
- commitment API 세 controller만 `/api/v2` 아래에 있어 JWT의
  `allowedTenants.singleOrNull()`에 의존한다.
- `TenantContextFilter`와 `TenantAuthorizationManager`는 path tenant와
  JWT membership을 검증할 수 있다.
- `ActorContextResolver.resolve`는 이미 path tenant를 받아 membership을
  재검증하므로 commitment도 같은 경계를 사용할 수 있다.
- `docs/requirements/architecture.md`의 ADR-14에는 현재 `/api/v2` 예외가
  남아 있어 이번 결정과 함께 갱신해야 한다.

## 외부 경로 계약

기존 `/api/v2` 경로를 다음처럼 tenant path로 바꾼다.

| 역할 | 기존 경로 | 새 경로 |
|---|---|---|
| 고객 가예약 | `POST /api/v2/appointment-requests` | `POST /api/{tenantCode}/appointment-requests` |
| 관리자 직접 생성 | `POST /api/v2/admin/appointments` | `POST /api/{tenantCode}/admin/appointments` |
| 관리자 승인 | `POST /api/v2/appointments/{id}/approve` | `POST /api/{tenantCode}/appointments/{id}/approve` |
| 관리자 확정 | `POST /api/v2/appointments/{id}/confirm` | `POST /api/{tenantCode}/appointments/{id}/confirm` |
| 관리자 만료 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/expire` | `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/expire` |
| 관리자 취소 | `POST /api/v2/appointments/{id}/cancel` | `POST /api/{tenantCode}/appointments/{id}/cancel` |
| 관리자 변경 제안 | `POST /api/v2/appointments/{id}/change-proposals` | `POST /api/{tenantCode}/appointments/{id}/change-proposals` |
| 고객 제안 수락 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/accept` | `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept` |
| 고객 제안 거절 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/decline` | `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/decline` |
| 고객·관리자 조회 | `GET /api/v2/appointments/{id}/commitment` | `GET /api/{tenantCode}/appointments/{id}/commitment` |

`/api/v1` 또는 `/api/v2`라는 버전 root는 더 이상 공개 API 의미를 갖지
않는다. `v2`를 tenant slug로 사용하지 않도록 resolver에서 reserved root로
계속 차단한다.

## 권한 흐름

1. JWT filter가 서명, issuer, audience, time claim과 닫힌 claim 집합을
   검증하고 `SchedulingUserPrincipal`을 만든다.
2. `TenantPathResolver`가 `/api/{tenantCode}/...`의 첫 segment를 읽는다.
3. `TenantContextFilter`가 활성 tenant를 DB에서 조회하고, 인증 principal의
   `allowedTenants`에 없으면 403, 존재하지 않으면 인증된 요청에 404를
   반환한다. 요청 종료 시 `TenantContext`를 반드시 복구한다.
4. Spring Security matcher는 path tenant membership과 endpoint role/scope를
   함께 검사한다.
5. commitment controller는 path `tenantCode`를
   `resolveAppointmentActor(authentication, tenantCode, request)`에 전달한다.
   resolver는 `tenantCode in allowedTenants`를 다시 확인하고, JWT의
   `clinicId`가 `allowedClinicIds`에 포함되는지 검증한다.
6. application service와 repository는 `tenantGroupId` 및 clinic ownership을
   내부 scope로 확인한다. 외부 요청 body/header의 tenant 또는 내부 key는
   권위 값으로 사용하지 않는다.

`X-Tenant-Code`, `X-Clinic-Id`, `tenantGroupId` 같은 header는 이 계약에
추가하지 않는다. 향후 Gateway assertion이 필요해지더라도 서명된 scope와
서버 재검증 계약 없이는 header를 신뢰하지 않는다.

## 구현 경계

### 변경 대상

- commitment 세 controller의 class mapping과 `@PathVariable tenantCode`
  인자 추가
- `AppointmentCommitmentHttpSupport`의 actor resolver가 path tenant를
  받도록 변경하고 multi-tenant JWT를 path membership으로 처리
- `SecurityConfig`의 commitment matcher를 tenant-aware matcher로 통합
- `AppointmentCommitmentApiException`의 공개 path classifier를 새 경로로
  변경
- `TenantPathResolver`/JWT tenant code 검증을 lower-case canonical slug로
  일치시킴
- controller/security/OpenAPI/exception integration tests의 경로와 권한
  matrix 갱신
- ADR-14, visit commitment API 문서, 운영 runbook의 활성 경로 갱신

### 유지 대상

- `tenantGroupId`와 모든 Exposed primary/foreign key
- clinic ID의 path 사용 및 `allowedClinicIds` membership 의미
- appointment commitment service의 상태 머신, 동의, idempotency, ETag,
  오류 code
- `/api/{tenantCode}/clinics/...` 기존 endpoint 계약
- actuator/profile reevaluation 같은 tenantless 운영 endpoint

### 제거 대상

- `AdminAppointmentV2Controller`, `CustomerAppointmentV2Controller`의
  `V2` 명명은 route 의미가 없으므로 구현 완료 후 일반 명칭으로 정리한다.
  파일 이동/rename은 동작 변경과 분리 가능한 경우에만 수행한다.
- `allowedTenants.singleOrNull()`을 tenant 선택 근거로 사용하는 로직
- `/api/v2/**` 전용 security matcher와 오류 path 정규식

## 실패 및 보안 계약

| 상황 | 결과 |
|---|---|
| JWT 없음/검증 실패 | 401 |
| path tenant가 JWT `allowedTenants`에 없음 | 403 |
| 인증된 요청의 tenant가 DB에 없음/inactive | 404 |
| path tenant가 대문자·공백·허용되지 않은 slug | 인증 전에 거부되어 401/403 경계를 우회하지 않음 |
| clinic claim이 `allowedClinicIds` 밖임 | 403 scope mismatch |
| 다중 tenant JWT가 path tenant를 명시함 | 해당 membership tenant만 허용 |
| body/header가 path tenant와 다른 값을 보냄 | body/header는 무시하며 path+JWT scope만 사용 |
| 요청 종료 후 다른 요청/코루틴에서 TenantContext가 남음 | 테스트로 복구 및 coroutine context 전파를 검증 |
| `/api/v2/...` legacy path 호출 | tenant path controller에 매핑되지 않아 404/보호된 경로 거부 |

## 하위 호환 및 롤백

호환성은 아직 고정되지 않은 예제 앱의 현재 branch 소비자보다 단일 권한
모델을 우선한다. legacy `/api/v2` alias는 추가하지 않는다. 부분 배포 시
old/new route가 섞여 권한 경계가 달라지는 것을 막기 위해 controller,
security matcher, 문서와 테스트를 하나의 PR에서 함께 갱신한다.

롤백은 해당 PR을 revert하여 기존 `/api/v2` route와 resolver를 복원하는
것으로 한정한다. DB migration, key migration, data backfill은 필요하지
않다.

## 수용 기준

- [ ] commitment의 모든 HTTP route가 `/api/{tenantCode}/...`로만 노출된다.
- [ ] 다중 tenant JWT가 path에 선택한 허용 tenant로 정상 동작한다.
- [ ] path/JWT mismatch, unknown tenant, invalid token의 401/403/404 계약이
  각 endpoint matrix에서 고정된다.
- [ ] admin/patient role과 clinic membership이 기존보다 약화되지 않는다.
- [ ] `TenantContext`의 thread-local cleanup와 coroutine context element의
  전파/복구가 테스트된다.
- [ ] OpenAPI, API 문서, 운영 runbook에 `/api/v2` 활성 경로가 남지 않는다.
- [ ] 기존 appointment-api 테스트와 새 tenant authority 테스트가 통과한다.
- [ ] 내부 key/FK와 Exposed transaction 경계에는 변경이 없다.

## DoD

- 변경된 코드와 테스트에 한국어 KDoc/설명이 필요한 경우 추가한다.
- `git diff --check`, targeted tests, `:appointment-api:test`, 정적 검토를
  수행한다.
- 보안·안정성·성능 관점의 독립 review에서 P0/P1이 0이어야 한다.
- issue #38과 연결된 영어 PR body의 마지막 section은 `## DoD Status`로
  둔다.
- CI가 통과하고 exact PR head에 대한 별도 merge 승인을 받은 뒤에만 merge,
  local develop sync, worktree cleanup을 수행한다.


# Issue #295 tenant API·인증 계약 설계

## 목적

환자 포털과 직원·관리자 화면이 같은 백엔드의 tenant path 계약을 사용하도록 프론트엔드 호출 경계를 단일화한다. 환자 cookie session과 Gateway workforce Bearer token을 분리하고, tenant 누락·인증 실패·권한 실패를 공통 상태로 전달한다. 이 문서는 Issue #295의 잔여범위만 다루며 백엔드 endpoint와 Capacitor 모바일은 변경하지 않는다.

## 현재 근거

- 백엔드 인증 컨트롤러는 `/api/{tenantCode}/auth/{csrf|register|login|session|logout}`만 제공한다. workforce 로그인 endpoint는 없다.
- 백엔드 `JwtTokenParser`는 workforce JWT의 `allowedTenants`, `allowedClinicIds`, `roles`, `clinicId`를 검증한다.
- `PortalApiClient`는 이미 tenant path, ETag, `Retry-After`, structured error를 구현하고 있다.
- 관리용 `AppointmentService`, `ClinicService`, `DoctorService`, `EquipmentService`, `SlotService`, `RescheduleService`, `EquipmentUnavailabilityService`, `TreatmentTypeService`, `DashboardStatsService`는 `environment.apiUrl` 또는 raw `HttpClient`로 tenant segment를 생략한다.
- `AuthService`는 토큰을 메모리에만 보관하지만 실제 host bootstrap 호출자는 없다. 기존 `auth_token` storage는 생성 시 제거된다.
- 기준 검증은 `npm test -- --watch=false` 40개 파일·290개 테스트 통과, `npm run build` 성공이다. Gradle 프론트 build는 Node archive dependency verification metadata 누락으로 실패했다.

## 선택지와 결정

### 선택지 A — 서비스마다 tenant URL을 직접 조립

수정량은 작지만 URL 인코딩, tenant 누락, 인증 scope가 다시 복제된다. Issue #295의 공통 계층 계약을 만족하지 못하므로 거부한다.

### 선택지 B — 기존 `PortalApiClient`를 workforce까지 확장

ETag·환자 오류 모델을 재사용할 수 있으나 환자 session cache와 workforce CRUD가 한 클래스에 결합된다. 인증 cookie/Bearer와 반환 envelope가 섞여 회귀 범위가 커지므로 거부한다.

### 선택지 C — 공통 `TenantApiClient` transport를 추가하고 역할별 client가 재사용 (채택)

`TenantApiClient`가 tenant URL 생성과 `HttpResponse` transport를 소유한다. `HttpContext`의 `patient-cookie`와 `workforce-bearer` scope를 호출자가 명시한다. 기존 `PortalApiClient`는 patient scope로 이 transport를 재사용하고 관리 서비스는 workforce scope로 같은 transport를 사용한다. URL·tenant 검증·인증 헤더 정책을 한 곳에서 테스트할 수 있어 현재 코드와 계약의 차이를 가장 작게 줄인다.

## 계약

### Tenant transport

- 모든 API path는 `/api/{encodeURIComponent(tenantCode)}{path}` 형식이다.
- tenant가 없거나 path가 절대 URL이면 네트워크 전에 실패한다.
- `TenantApiClient`는 `HttpResponse<T>`를 반환하며 response envelope와 domain 변환은 호출 client가 담당한다.
- patient 요청은 `withCredentials: true`, `patient-cookie` scope를 사용한다.
- workforce 요청은 `withCredentials: false`, `workforce-bearer` scope를 사용한다.
- scope가 없는 raw request에는 Bearer를 붙이지 않는다.

### Workforce bootstrap

- 백엔드 login endpoint를 만들지 않는다. Gateway 또는 host가 매 page load마다 `AuthService.bootstrap(token, tenantCode)`를 호출한다.
- token은 localStorage/sessionStorage/cookie에 저장하지 않는다.
- `allowedTenants`가 있으면 선택 tenant가 그 집합에 포함되어야 한다. 선택 tenant가 없고 허용 tenant가 정확히 하나인 경우에만 자동 선택한다.
- 선택 tenant가 허용되지 않으면 token과 tenant를 모두 폐기하고 공통 unauthorized 상태를 기록한다.

### 공통 상태

`SessionStateService`는 `patient` 또는 `workforce` scope별로 `anonymous`, `authenticated`, `unauthorized`, `forbidden`, `tenant-missing` 상태를 signal로 제공한다. interceptor와 route guard는 이 상태를 갱신하고, domain component가 scope별 로그인·권한 화면으로 전환할 수 있는 단일 상태를 사용한다.

## 실패 모드와 처리

| 상황 | 처리 | 검증 |
|---|---|---|
| tenant 미설정 | 요청 전 `tenant-missing` 상태 기록 후 실패 | transport unit test |
| workforce 요청에 patient cookie session만 존재 | Bearer를 붙이지 않고 요청, 401을 workforce 상태로 기록 | interceptor test |
| patient 요청에 workforce token이 존재 | Bearer를 붙이지 않고 cookie credentials만 사용 | interceptor/client test |
| JWT tenant가 선택 tenant를 허용하지 않음 | bootstrap에서 token·tenant 제거, unauthorized 상태 | AuthService test |
| 백엔드 401/403 | workforce token 제거 여부와 forbidden 상태를 status별 처리 | error interceptor/guard test |
| tenant path가 변경되는 중 응답 도착 | 기존 PortalApiClient session epoch/cache 규칙 유지 | 기존 portal tests + tenant client test |

## 호환성과 범위

- 기존 `PortalApiClient` public method와 patient DTO/error 모델은 유지한다.
- 관리 서비스 public method와 컴포넌트 호출 시그니처는 유지하고 내부 transport만 교체한다.
- backend controller, OpenAPI schema, database, proxy rewrite, Capacitor는 변경하지 않는다.
- Kotlin source는 변경하지 않는다. 따라서 Kotlin 패턴 KT-01~KT-05는 적용 대상이 아니며 최종 diff에서 Kotlin 파일 0개로 확인한다.

## 수용 기준

1. 환자·직원/관리자 API 호출이 모두 `/api/{tenantCode}/...`를 사용한다.
2. patient cookie와 workforce Bearer가 서로의 요청에 섞이지 않는다.
3. workforce bootstrap이 비영속 token과 tenant allow-list를 검증한다.
4. tenant 누락, 401, 403, tenant scope 불일치가 공통 session state와 route guard에 전달된다.
5. raw management `HttpClient`/`environment.apiUrl` URL이 없음을 정적 계약 테스트가 검출한다.
6. 기존 portal ETag, `Retry-After`, structured error 테스트와 전체 frontend unit/build 검증이 통과한다.

## DoD

- [ ] 공통 transport/auth scope/session state 구현 및 단위 테스트
- [ ] 관리 서비스 전체 tenant path 전환 및 기존 서비스 테스트 갱신
- [ ] PortalApiClient·PatientAuthService transport 재사용 및 cookie 계약 검증
- [ ] raw URL 정적 계약 테스트와 role/error 상태 테스트
- [ ] 한국어 설계·계획·리뷰·lesson 문서와 SPW-01~05 read-back
- [ ] fresh test/build/e2e 또는 실행 불가 근거, diff check, Kotlin diff 0개 증거

## 문서 게이트

- SPW-01: PASS — 현재 Issue #295, backend controllers, frontend source/tests, baseline 명령을 근거로 독자·목적·미확정 범위를 고정했다.
- SPW-02: PASS — 경계, 계약, 실패 모드, 호환성, 수용 기준, DoD를 포함했다.
- SPW-03: PASS — 한국어 기술 문체와 동일 용어(`tenant`, `workforce`, `patient`, `transport`, `scope`)를 사용했다.
- SPW-04: PASS — backend mapping과 실제 frontend 파일·baseline 결과를 대조했다.
- SPW-05: PASS — Markdown을 다시 읽고 표·코드 토큰·미확정 workforce login endpoint를 확인했다.


# 프론트엔드 설계

**모듈**: `frontend/appointment-frontend`
**기술**: Angular 22, TypeScript 6, Node.js 22

## 개요

병원 예약 관리 Angular SPA. `appointment-api` REST API와 연동하여 직원 예약 관리와
환자 포털을 제공한다. 백엔드는 `/api/{tenantCode}/...` 테넌트 범위를 요구하며,
직원 화면과 환자 포털의 연결 완료 범위가 다르므로 이 문서에서 분리해 설명한다.

## 빌드 통합

Gradle `node-gradle` 플러그인으로 Kotlin 빌드 시스템에 통합:

```kotlin
// frontend/appointment-frontend/build.gradle.kts
plugins {
    id("com.github.node-gradle.node")
}

node {
    version.set("22.14.0")
    download.set(true)
}
```

빌드 명령:

```bash
# 프론트엔드 빌드
./gradlew :frontend:appointment-frontend:build

# 개발 서버 실행 (Angular CLI 직접)
cd frontend/appointment-frontend
npm start    # http://localhost:4200
```

## API 연동

- API 서버: `http://localhost:8080`
- 환자 포털 인증: tenant별 HttpOnly cookie session과 CSRF bootstrap
- 직원·관리자 인증: legacy JWT Bearer token (Authorization 헤더)
- 개발 환경 프록시: `proxy.conf.json`으로 CORS 우회

### API origin·CORS·cookie/XSRF 계약

- 브라우저 개발과 same-origin production은 `environment.apiOrigin=''`을 사용하고
  `/api` proxy 또는 reverse proxy가 API를 전달한다.
- cross-origin production은 `environment.prod.ts` 또는 제한된
  `globalThis.__CLINIC_API_CONFIG__.apiOrigin`에 HTTPS origin만 지정한다. Capacitor
  WebView는 명시적인 HTTPS origin을 요구하며, origin에는 credentials·path·query·fragment를
  넣지 않는다.
- 모든 요청은 `TenantApiClient`를 통해 `/api/{tenantCode}/...`를 만들고, patient
  cookie 요청은 `withCredentials=true`와 `X-XSRF-TOKEN`을 사용한다. workforce Bearer는
  `AuthService`의 메모리 token만 사용하고 cookie를 보내지 않는다. patient JWT는
  `localStorage`나 `sessionStorage`에 저장하지 않는다.
- cross-origin API의 CSRF bootstrap은 앱 origin에서 읽을 수 있는 `XSRF-TOKEN` cookie를
  발급해야 한다. API host에만 있는 host-only cookie는 `HttpXsrfTokenExtractor`가 읽을
  수 없으므로 same-site/reverse proxy 조건을 먼저 정하고 token을 storage로 복사하지
  않는다.
- API cross-origin을 실제로 허용할 때는 `scheduling.security.cors.enabled=true`,
  유한한 `allowed-origins`, `allow-credentials=true`를 함께 설정한다. wildcard는
  거부하며 CORS는 `/api/**`에만 적용한다. `http://localhost`와 `http://127.0.0.1`은
  개발 진단용이고 운영 origin은 HTTPS여야 한다.
- `SameSite=Strict` patient cookie의 native cross-site 동작과 cookie bridge는
  [Issue #24](https://github.com/bluetape4k/clinic-appointment/issues/24)와
  [Issue #27](https://github.com/bluetape4k/clinic-appointment/issues/27)의 실기기
  검증 경계로 남긴다.

### Tenant routing 완료 범위

환자 포털의 `/portal/login`과 `/portal/register`는 tenant code를 입력받습니다.
`TenantContextService`가 이를 같은 탭의 `sessionStorage`에 저장하고,
`PatientAuthService`와 `PortalApiClient`가 모든 포털 요청을
`/api/{tenantCode}/...`로 구성합니다. `/portal/appointments`,
`/portal/notifications`, `/portal/profile`은 `patientAuthGuard`가 session을
복원한 뒤에만 열립니다.

직원·관리자 경로(`/calendar`, `/appointments`, `/management`)의 legacy
`AuthService`와 일부 서비스는 아직 `/api/...`를 직접 호출합니다. 이 경로는
백엔드의 tenant-scoped endpoint 계약을 완전히 소비하지 않으므로 tenant-aware
직원 routing/auth는 완료로 표시하지 않습니다. 직원/auth residual은
[Issue #295](https://github.com/bluetape4k/clinic-appointment/issues/295)에서
추적합니다. 이 문서 범위에서는 source behavior를 변경하지 않습니다.

## 페이지 구성

| 경로 | 컴포넌트 | 설명 |
|------|---------|------|
| `/` | `DashboardComponent` | 대시보드 |
| `/appointments` | `AppointmentListComponent` | 예약 목록 |
| `/appointments/new` | `AppointmentFormComponent` | 예약 생성 |
| `/appointments/:id` | `AppointmentDetailComponent` | 예약 상세 |
| `/calendar/day` | `DayViewComponent` | 일별 캘린더 |
| `/calendar/week` | `WeekViewComponent` | 주별 캘린더 |
| `/calendar/month` | `MonthViewComponent` | 월별 캘린더 |
| `/reschedule` | `RescheduleListComponent` | 재배정 관리 |
| `/equipment-unavailability` | `EquipmentUnavailabilityListComponent` | 장비 사용불가 관리 |
| `/management/clinics` | `ClinicListComponent` | 클리닉 관리 |
| `/management/doctors` | `DoctorListComponent` | 의사 관리 |
| `/management/treatment-types` | `TreatmentTypeListComponent` | 진료유형 관리 |
| `/portal/login` | `PatientLoginPageComponent` | tenant code와 환자 계정 로그인 |
| `/portal/register` | `PatientRegisterPageComponent` | tenant-scoped 환자 계정 등록 |
| `/portal/appointments` | `PatientAppointmentsPageComponent` | 환자 예약 조회·요청·취소 |
| `/portal/notifications` | `PatientNotificationsPageComponent` | 환자 알림 조회 |
| `/portal/profile` | `PatientProfilePageComponent` | 환자 프로필 조회 |

## 개발 환경

```bash
cd frontend/appointment-frontend
npm install
npm start        # 개발 서버 (http://localhost:4200)
npm run build    # 프로덕션 빌드 (dist/)
npm test -- --watch=false   # Vitest 단위·계약 테스트
npm run test:e2e             # Playwright Chromium 브라우저 시나리오
npm run docs:verify          # package·route·README 계약 검증
```

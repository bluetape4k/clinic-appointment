# appointment-frontend

[한국어 본문](README.md) | [한국어 참고본](README.ko.md)

Angular 22 기반 병원 예약 관리 웹 UI입니다. 직원 화면과 환자 포털(`/portal`)을
같은 standalone workspace에서 route로 분리합니다.

환자 포털의 `/portal/login`·`/portal/register`는 tenant code를 입력받고,
`TenantContextService`가 같은 탭의 `sessionStorage`에 scope를 보관합니다.
`PatientAuthService`와 `PortalApiClient`는 이 scope를 `/api/{tenantCode}/...` 경로에
반영하며, 인증된 포털 내부 route는 `patientAuthGuard`로 보호합니다. 이 범위의
tenant routing은 구현되어 있습니다.

직원·관리자 화면의 legacy JWT `AuthService`와 일부 서비스는 아직 `/api/...` 경로를
직접 호출하므로 tenant-aware 직원 routing/auth는 미완료입니다. 이 잔여 범위는
[Issue #295](https://github.com/bluetape4k/clinic-appointment/issues/295)에서
추적하며, 이 예제는 두 사용자 영역의 완료 상태를 구분해 설명합니다.

## 개발 서버 실행

```bash
cd frontend/appointment-frontend
npm install
npm start   # http://localhost:4200
```

API 서버(`http://localhost:8080`)가 먼저 실행되어 있어야 합니다.

환자 포털의 취소는 등록된 사유 code를 한국어 label로 선택하는 code-only
흐름입니다. 요청에는 최신 ETag와 `Idempotency-Key`가 포함되고, 성공하면
상태 stepper가 `CANCELLED` terminal 단계로 이동합니다. 관리자·직원용 상세
사유는 환자 포털 모델이나 완료 응답에 포함하지 않습니다.

## 사용자 흐름

![환자 예약 시나리오 시퀀스](../../docs/requirements/assets/user-scenarios-01-patient-booking-ko.png)

![장비 사용 불가 시나리오 시퀀스](../../docs/requirements/assets/user-scenarios-04-equipment-unavailability-ko.png)

## 빌드

```bash
# Angular CLI 직접
npm run build   # dist/ 생성

# Gradle 통합 빌드
./gradlew :frontend:appointment-frontend:build
```

## 테스트

```bash
npm test -- --watch=false   # Vitest 기반 Angular 단위·계약 테스트
npm run test:e2e             # Playwright Chromium 브라우저 시나리오
```

브라우저 테스트는 Angular 개발 서버를 자동으로 시작합니다. 처음 실행하는
환경에서는 `npx playwright install chromium`으로 Chromium을 준비하세요.

API 계약과 환자 포털의 테스트 경계는
[포털 테스트 게이트](../../docs/ci/patient-portal-testing.md)에 정리되어 있습니다.

## 설계 문서

- [프론트엔드 설계](../../docs/requirements/frontend.md)

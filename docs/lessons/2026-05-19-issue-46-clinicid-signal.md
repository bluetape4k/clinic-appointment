# Issue #46 — AuthService.clinicId signal 도입

## Root Cause

8개 Angular 컴포넌트에 `clinicId = 1` 또는 `const CLINIC_ID = 1`이 하드코딩되어 있어
멀티테넌시 확장이나 배포 환경 변경 시 실제 JWT의 clinicId를 반영하지 못함.

## Decision

1. `AuthService`에 `_decodedToken` computed signal 추가 — JWT payload를 lazy 파싱
2. `clinicId` computed signal 추가 — `_decodedToken()?.['clinicId'] ?? 0`
3. 8개 컴포넌트에서 하드코딩 제거 → `this.authService.clinicId()` 호출로 교체

Backend `JwtTokenParser.kt`의 `clinicId` claim (`CLAIM_CLINIC_ID = "clinicId"`)과 키 이름 일치 확인 후 적용.

## Affected Files

| File | Change |
|------|--------|
| `auth.service.ts` | `_decodedToken` + `clinicId` computed signal 추가 |
| `appointment-detail.component.ts` | `clinicId = 1` 제거 → `authService.clinicId()` |
| `appointment-form.component.ts` | `AuthService` import/inject 추가, `clinicId = 1` 제거 |
| `appointment-list.component.ts` | `clinicId = 1` 제거 → `authService.clinicId()` |
| `day-view.component.ts` | `CLINIC_ID = 1` 제거 → `authService.clinicId()` |
| `month-view.component.ts` | `CLINIC_ID = 1` 제거 → `authService.clinicId()` |
| `week-view.component.ts` | `CLINIC_ID = 1` 제거 → `authService.clinicId()` |
| `doctor-list.component.ts` | `CLINIC_ID = 1` 제거 → `authService.clinicId()` |
| `treatment-type-list.component.ts` | `CLINIC_ID = 1` 제거 → `authService.clinicId()` |

## Outcome

- `ng build --configuration production` ✅ (errors 0)
- 하드코딩된 `clinicId = 1` / `CLINIC_ID = 1` 0건 잔존

## Future Guidance

- 새 Angular 서비스/컴포넌트에서 clinicId가 필요한 경우 항상 `inject(AuthService).clinicId()` 사용.
- JWT payload에 `clinicId` claim이 없는 경우 기본값은 `0` — 인증 미완료 상태이므로 API 호출 전 `isAuthenticated()` 체크 병행 권장.
- `_decodedToken`은 `computed()`로 구현되어 토큰 변경 시 자동 갱신됨.

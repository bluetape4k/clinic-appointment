# Issue #48 — Angular environment.ts API baseUrl 주입

## 원인

모든 Angular service가 `/api/...` baseUrl을 문자열 리터럴로 하드코딩하고 있어
배포 대상에 따라 빌드 시 API root URL을 바꿀 수 없었다.

## 결정

Angular 표준 environment file 패턴을 도입한다.
- `src/environments/environment.ts` — development (production: false)
- `src/environments/environment.prod.ts` — production (production: true)
- `angular.json`의 fileReplacements로 `--configuration=production`에서 파일 교체
- 모든 service의 `baseUrl` 필드를 `${environment.apiUrl}/...`로 교체

두 파일은 의도적으로 `apiUrl: '/api'`(상대 경로)를 사용한다.
- Dev에서는 Angular proxy(`proxy.conf.json`)가 `/api` → backend로 전달한다.
- Prod에서는 Nginx/reverse proxy가 `/api` → backend로 전달한다.
- 향후 배포에서는 `environment.prod.ts`만 수정해 절대 URL을 사용할 수 있다.

## 결과

- `ng build --configuration=development` ✅
- `ng build --configuration=production` ✅ (fileReplacements 적용)
- 이 변경과 무관한 기존 spec 실패 8건(response-shape mismatch가 있는
  `HttpTestingController` spec — 별도 추적)

## 향후 지침

- 새 Angular service를 추가할 때는 항상 `environment.apiUrl`을 base로 사용하고
  `/api`를 하드코딩하지 않는다.
- spec 파일의 `httpTesting.expectOne()`에 URL 문자열이 아직 하드코딩되어 있다면
  `environment.apiUrl`을 사용하도록 수정해 URL 변경 시 조용한 drift를 방지한다.

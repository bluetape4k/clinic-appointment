# 환자 포털 테스트 게이트

`frontend/appointment-frontend`의 환자 포털은 서버 계약, Angular 화면, 실제
브라우저 탐색을 서로 다른 게이트로 검증한다. 로컬에서 실행할 때는 먼저
`npm ci`를 수행한다.

## 게이트별 명령

```bash
cd frontend/appointment-frontend

# API DTO, tenant path, precondition header, 오류 상태
npm test -- --watch=false --include='src/app/core/api/portal-api-contract.spec.ts' --include='src/app/core/api/portal-api-client.spec.ts'

# 환자 포털 facade·component·shell 및 전체 Angular 단위 테스트
npm test -- --watch=false

# 실제 Chromium에서 /portal/appointments 탐색·폼 label·알림 route 확인
npx playwright install chromium
npm run test:e2e

# 배포 번들
npm run build
```

`playwright.config.ts`가 `npm run start -- --host 127.0.0.1 --port 4200`을
자동으로 실행하므로 별도 개발 서버를 띄우지 않는다. CI에서는 Chromium만
설치하고 worker를 하나로 고정하며 실패 시 trace와 screenshot을 보존한다.

## 계약 범위

- 모든 환자 API 요청은 `/api/{tenantCode}` 경로를 사용한다.
- 최초 예약 요청은 `Idempotency-Key`와 `If-None-Match: *`를 함께 보낸다.
- proposal 결정은 `Idempotency-Key`와 최신 `If-Match`를 보낸다.
- 401/403/409/410/412/422/428/429/503 응답은 UI에서 재동기화·만료·재시도
  상태로 분류하고 `Retry-After`와 correlation ID를 보존한다.
- 예약 제목은 상품명이 있으면 상품명, 없으면 일정 fallback을 사용하며 회차는
  `N회차 / M회` 또는 `N회차`로만 표시한다.

## 브라우저 시나리오와 접근성

Playwright 시나리오는 `/portal/appointments`에서 shell 탐색, 활성 링크의
`aria-current`, 예약 요청 폼의 native label/type, `/portal/notifications`
전환과 tenant 안내 live region을 확인한다. 320px·736px·1024px viewport에서
가로 overflow가 없는지와 native link keyboard focus도 함께 확인한다. 화면 상태
변경은 `role="status"`와 `aria-live="polite"`를 사용하고, 모든 조작은 native
link/button/input과 `focus-visible` outline을 유지한다.

## 알려진 경계

포털 알림 SSE endpoint는 현재 backend에 없으므로 브라우저 smoke는 tenant가
없는 초기 상태에서 연결 안내를 검증한다. 실제 SSE/polling 데이터와 상품·회차
persistence는 backend 계약이 제공되는 시점에 별도 integration fixture로
확장한다.

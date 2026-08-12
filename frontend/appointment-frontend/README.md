# appointment-frontend

[한국어 본문](README.md) | [한국어 참고본](README.ko.md)

Angular 22 기반 병원 예약 관리 웹 UI입니다. 직원 화면과 환자 포털(`/portal`)을
같은 standalone workspace에서 route로 분리합니다.

## 개발 서버 실행

```bash
cd frontend/appointment-frontend
npm install
npm start   # http://localhost:4200
```

API 서버(`http://localhost:8080`)가 먼저 실행되어 있어야 합니다.

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

환자 포털의 취소 흐름은 등록된 사유 code만 전송합니다. 환자 화면에는
한국어 label이 표시되고, 요청에는 최신 ETag와 `Idempotency-Key`가 포함됩니다.
취소 후에는 `CANCELLED` terminal step을 표시하며 관리자·직원용 상세 사유는
포털 응답에 노출하지 않습니다.

브라우저 테스트를 처음 실행하는 환경에서는
`npx playwright install chromium`으로 Chromium을 준비하세요.

## 설계 문서

- [프론트엔드 설계](../../docs/requirements/frontend.md)

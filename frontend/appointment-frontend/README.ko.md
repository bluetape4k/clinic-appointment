# appointment-frontend

[English](README.md) | [한국어](README.ko.md)

Angular 18 기반 병원 예약 관리 웹 UI.

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
npm test   # Karma 단위 테스트
```

## 설계 문서

- [프론트엔드 설계](../../docs/requirements/frontend.md)

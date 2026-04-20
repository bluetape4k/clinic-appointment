# 프론트엔드 설계

**모듈**: `frontend/appointment-frontend`
**기술**: Angular 21, TypeScript, Node.js 22

## 개요

병원 예약 관리 Angular SPA. `appointment-api` REST API와 연동하여 예약 조회/생성/상태 변경을 제공한다.
백엔드 30개 엔드포인트 전체 연결 완료 (v0.3.0).

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
- 인증: JWT Bearer token (Authorization 헤더)
- 개발 환경 프록시: `proxy.conf.json`으로 CORS 우회

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

## 개발 환경

```bash
cd frontend/appointment-frontend
npm install
npm start        # 개발 서버 (http://localhost:4200)
npm run build    # 프로덕션 빌드 (dist/)
npm test         # Karma 단위 테스트 (18개 spec, 118 tests)
```

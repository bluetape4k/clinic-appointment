# appointment-api

Spring Boot 4 REST API 서버 — JWT 인증, Flyway 마이그레이션, Swagger UI, Gatling 부하 테스트.

## 책임

- **하는 것**: HTTP API 제공, 인증/인가, DB 마이그레이션, 도메인 이벤트 발행
- **하지 않는 것**: 알림 직접 발송 없음 (이벤트로 위임), Solver 직접 호출 가능

## API 엔드포인트

| 그룹 | 경로 | 설명 |
|------|------|------|
| 예약 | `GET /api/appointments` | 기간별 예약 목록 조회 |
| 예약 | `POST /api/appointments` | 예약 생성 |
| 예약 | `PATCH /api/appointments/{id}/status` | 상태 변경 |
| 예약 | `DELETE /api/appointments/{id}` | 예약 취소 |
| 슬롯 | `GET /api/slots` | 가용 슬롯 조회 |
| 재배정 | `POST /api/reschedule/closure` | 임시휴진 날짜 재배정 |
| 재배정 | `GET /api/reschedule/candidates` | 재배정 후보 목록 |
| 장비 사용불가 | `GET/POST/PUT/DELETE /api/equipment-unavailability` | 장비 사용불가 구간 CRUD |
| 클리닉 | `GET /api/clinics`, `/{id}`, `/{id}/operating-hours`, `/{id}/break-times` | 클리닉 조회 |
| 의사 | `GET /api/clinics/{id}/doctors`, `/doctors/{id}` | 의사 조회 |
| 진료유형 | `GET /api/clinics/{id}/treatment-types` | 진료유형 조회 |
| 장비 | `GET /api/clinics/{id}/equipments` | 장비 조회 |

**Swagger UI**: 서버 기동 후 `http://localhost:8080/swagger-ui.html`

## 인증

JWT Bearer Token:
- 헤더: `Authorization: Bearer <token>`
- 설정: `JwtSecurityProperties` (`scheduling.security.jwt.*`)
- 필터: `JwtAuthenticationFilter` → `SchedulingUserPrincipal`

## DB 마이그레이션

Flyway — `src/main/resources/db/migration/V*.sql`

> **주의**: `scheduling_*` 테이블명은 Flyway 스크립트에 고정되어 있으므로 변경 금지.

## 테스트 실행

```bash
# H2 in-memory (기본)
./gradlew :appointment-api:test

# PostgreSQL Testcontainer
./gradlew :appointment-api:test -Dspring.profiles.active=test,test-postgresql

# MySQL8 Testcontainer
./gradlew :appointment-api:test -Dspring.profiles.active=test,test-mysql
```

### 테스트 구조

| 클래스 | 역할 |
|--------|------|
| `AbstractApiIntegrationTest` | `@SpringBootTest(RANDOM_PORT)` + `@DynamicPropertySource` 기반 추상 클래스 |
| `Containers` | PostgreSQL / MySQL8 Testcontainer singleton |

- Spring Profile에 따라 DataSource를 동적으로 주입 (`@DynamicPropertySource`)
- Controller 테스트는 `RestClient` 방식 사용. MockMvc 미사용
- CI에서 H2 / PostgreSQL / MySQL8 세 환경을 병렬로 검증

## 타임존

API 응답에는 항상 `timezone` 과 `locale` 필드가 포함됩니다.

- `appointmentDate` / `startTime` / `endTime` 은 클리닉 현지 시간 기준
- UTC 변환은 서버에서 수행하지 않음 — 날짜 경계 문제 방지
- `locale` 은 날짜/시간 표시 형식 전용

## 실행

```bash
./gradlew :appointment-api:bootRun
./gradlew :appointment-api:build
./gradlew :appointment-api:gatlingRun
```

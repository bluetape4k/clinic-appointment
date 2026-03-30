# clinic-appointment

개인병원 환자 예약 관리 시스템

## Tech Stack

- Kotlin 2.3, Java 25
- Spring Boot 4
- Exposed ORM (JDBC)
- Timefold Solver (AI 스케줄 최적화)
- Resilience4j (CircuitBreaker, Retry)
- Redis / Lettuce (Leader Election)
- PostgreSQL + Flyway
- JWT 인증
- Angular 18 (프론트엔드)
- Gatling (부하 테스트)

## Modules

| Module | Description |
|--------|-------------|
| `appointment-core` | 도메인 모델, Exposed 테이블, 리포지토리, 상태 머신 |
| `appointment-event` | Spring 도메인 이벤트 퍼블리싱/로깅 |
| `appointment-solver` | Timefold Solver 기반 AI 최적 예약 배정 |
| `appointment-notification` | 예약 알림 스케줄러 (HA, Resilience4j) |
| `appointment-api` | REST API 서버 (Spring Boot MVC) |
| `frontend/appointment-frontend` | Angular 프론트엔드 |

## Build

```bash
# 전체 빌드 (frontend 제외)
./gradlew build -x :frontend:appointment-frontend:build

# 모듈별 빌드
./gradlew :appointment-core:build
./gradlew :appointment-api:bootRun
```

## Prerequisites

- JDK 25
- Node.js 20+ (frontend 빌드 시)
- Docker (Testcontainers)
- PostgreSQL (운영)

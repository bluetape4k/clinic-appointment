# 요구사항 & 구현 상태

clinic-appointment 프로젝트의 전체 요구사항 목록과 구현 상태를 추적합니다.

## 구현 상태표

| 요구사항 | 모듈 | 상태 | 상세 문서 |
|---------|------|------|----------|
| 예약 CRUD + 상태머신 | `appointment-core` | ✅ 완료 | [domain-model.md](domain-model.md) |
| 슬롯 계산 (단건 가용 시간 조회) | `appointment-core` | ✅ 완료 | [domain-model.md](domain-model.md) |
| 임시휴진 시 예약 재배정 | `appointment-core` | ✅ 완료 | [domain-model.md](domain-model.md) |
| 도메인 이벤트 발행/구독 | `appointment-event` | ✅ 완료 | [architecture.md](architecture.md) |
| AI 최적 스케줄링 (배치 최적화) | `appointment-solver` | ✅ 완료 | [solver.md](solver.md) |
| HA 알림 스케줄러 | `appointment-notification` | ✅ 완료 | [notification.md](notification.md) |
| 예약 리마인더 (전날/당일) | `appointment-notification` | ✅ 완료 | [notification.md](notification.md) |
| REST API + JWT 인증 | `appointment-api` | ✅ 완료 | [architecture.md](architecture.md) |
| Flyway DB 마이그레이션 | `appointment-api` | ✅ 완료 | — |
| Swagger UI | `appointment-api` | ✅ 완료 | — |
| Angular 18 웹 UI | `appointment-frontend` | ✅ 완료 | [frontend.md](frontend.md) |
| GitHub Actions CI | `.github/workflows` | ✅ 완료 | — |
| **실제 알림 채널 (Email/SMS/Push)** | `appointment-notification` | ❌ 미구현 | [notification.md](notification.md) |
| **Docker Compose 로컬 개발 환경** | — | ❌ 미구현 | — |
| **환자 포털 (자가 예약 웹앱)** | `appointment-patient-portal` (신규) | ❌ 미구현 | — |
| **멀티테넌시 (병원 그룹 데이터 격리)** | `appointment-core` | ❌ 미구현 | — |
| **메시지 큐 (Kafka/RabbitMQ 비동기)** | `appointment-messaging` (신규) | ❌ 미구현 | — |
| **관리자 대시보드 (통계/분석)** | `appointment-dashboard` (신규) | ❌ 미구현 | — |

## 설계 문서 목록

| 문서 | 내용 |
|------|------|
| [architecture.md](architecture.md) | 모듈 의존성 그래프, 주요 설계 결정 (ADR 스타일) |
| [domain-model.md](domain-model.md) | 16개 도메인 엔티티, 예약 상태머신 전이도, Exposed 테이블 목록 |
| [solver.md](solver.md) | Timefold Solver Planning Variable, Hard/Soft 제약조건 전체 목록 |
| [notification.md](notification.md) | NotificationChannel 인터페이스, HA 구성, Resilience4j 설정, 미구현 항목 |
| [frontend.md](frontend.md) | Angular 페이지 구성, API 연동, 빌드 설정 |

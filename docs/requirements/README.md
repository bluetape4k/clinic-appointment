# 요구사항 & 구현 상태

clinic-appointment 프로젝트의 전체 요구사항 목록과 구현 상태를 추적합니다.

## 구현 상태표

> 최종 갱신: 2026-04-20 (v0.3.0)

### 완료

| 요구사항 | 모듈 | 버전 | 상세 문서 |
|---------|------|------|----------|
| 예약 CRUD + 상태머신 | `appointment-core` | v0.1.0 | [domain-model.md](domain-model.md) |
| 슬롯 계산 (단건 가용 시간 조회) | `appointment-core` | v0.1.0 | [domain-model.md](domain-model.md) |
| 임시휴진 시 예약 재배정 | `appointment-core` | v0.1.0 | [domain-model.md](domain-model.md) |
| 도메인 이벤트 발행/구독 | `appointment-event` | v0.1.0 | [architecture.md](architecture.md) |
| AI 최적 스케줄링 (Timefold Solver) | `appointment-solver` | v0.1.0 | [solver.md](solver.md) |
| HA 알림 스케줄러 (Redis Leader Election) | `appointment-notification` | v0.1.0 | [notification.md](notification.md) |
| 예약 리마인더 (전날/당일) | `appointment-notification` | v0.1.0 | [notification.md](notification.md) |
| REST API + JWT 인증 | `appointment-api` | v0.1.0 | [architecture.md](architecture.md) |
| Flyway DB 마이그레이션 (벤더별 분리) | `appointment-api` | v0.1.0 / v0.3.0 | — |
| Swagger UI | `appointment-api` | v0.1.0 | — |
| GitHub Actions CI (gitleaks, Detekt, JaCoCo) | `.github/workflows` | v0.1.0 / v0.3.0 | — |
| 장비 사용불가 스케줄 CRUD + Solver 제약 | `appointment-core` / `appointment-solver` | v0.2.0 | [domain-model.md](domain-model.md) |
| `@Profile` 환경 분리 (local/dev/prod) | `appointment-api` | v0.2.0 | — |
| 마스터 데이터 CRUD API (Clinic/Doctor/TreatmentType/Equipment) | `appointment-api` | v0.3.0 | [architecture.md](architecture.md) |
| Angular 21 웹 UI (30개 엔드포인트 전체 연결) | `appointment-frontend` | v0.1.0 / v0.3.0 | [frontend.md](frontend.md) |
| Gatling 부하 테스트 (멀티 클리닉 포함) | `appointment-api` | v0.3.0 | — |
| Solver 벤치마크 baseline | `appointment-solver` | v0.3.0 | — |

### 미구현 (Backlog)

| 요구사항 | 모듈 | 우선순위 | 비고 |
|---------|------|----------|------|
| **환자 포털 (자가 예약 웹앱)** | `appointment-patient-portal` (신규) | MEDIUM | TODO 섹션 9.1 |
| **멀티테넌시 (병원 그룹 데이터 격리)** | `appointment-core` | MEDIUM | TODO 섹션 9.2, clinicId 해소 선행 |
| **메시지 큐 (Kafka/RabbitMQ 비동기)** | `appointment-messaging` (신규) | LOW | TODO 섹션 9.3 |
| **관리자 대시보드 (통계/분석)** | `appointment-dashboard` (신규) | LOW | TODO 섹션 9.4 |
| **SSE 기반 일괄 재배정 진행 표시** | `appointment-api` / `appointment-frontend` | HIGH | TODO 섹션 3.5 |

## 설계 문서 목록

| 문서 | 내용 |
|------|------|
| [architecture.md](architecture.md) | 모듈 의존성 그래프, 주요 설계 결정 (ADR 스타일) |
| [domain-model.md](domain-model.md) | 17개 도메인 엔티티, 예약 상태머신 전이도, Exposed 테이블 목록 |
| [solver.md](solver.md) | Timefold Solver Planning Variable, Hard/Soft 제약조건 전체 목록 |
| [notification.md](notification.md) | NotificationChannel 인터페이스, HA 구성, Resilience4j 설정 |
| [frontend.md](frontend.md) | Angular 21 페이지 구성, API 연동, 빌드 설정 |
| [erd.md](erd.md) | 전체 테이블 ERD (Mermaid), 관계 요약, 컬럼 타입 규칙 |
| [data-flow.md](data-flow.md) | 예약 생성·슬롯 조회·재배정·Solver·알림 데이터 흐름 다이어그램 |
| [user-scenarios.md](user-scenarios.md) | 예약 생성·체크인·임시휴진 재배정·리마인더 시퀀스 다이어그램 |

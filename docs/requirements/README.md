# 요구사항 & 구현 상태

clinic-appointment 프로젝트의 전체 요구사항 목록과 구현 상태를 추적합니다.

## 구현 상태표

> 최종 갱신: 2026-08-04 (v0.3.0)

### 완료

| 요구사항 | 모듈 | 버전 | 상세 문서 |
|---------|------|------|----------|
| 예약 CRUD + 상태머신 | `appointment-core` | v0.1.0 | [domain-model.md](domain-model.md) |
| 슬롯 계산 (단건 가용 시간 조회) | `appointment-core` | v0.1.0 | [domain-model.md](domain-model.md) |
| 임시휴진 시 예약 재배정 | `appointment-core` | v0.1.0 | [domain-model.md](domain-model.md) |
| 도메인 이벤트 발행/구독 | `appointment-event` | v0.1.0 | [architecture.md](architecture.md) |
| AI 최적 스케줄링 (Timefold Solver) | `appointment-solver` | v0.1.0 | [solver.md](solver.md) |
| 내구성 알림 outbox 발송·복구 | `appointment-notification` | v0.1.0 | [notification.md](notification.md) |
| 예약 리마인더 (전날/당일) | `appointment-notification` | v0.1.0 | [notification.md](notification.md) |
| REST API + JWT 인증 | `appointment-api` | v0.1.0 | [architecture.md](architecture.md) |
| Flyway DB 마이그레이션 (벤더별 분리) | `appointment-api` | v0.1.0 / v0.3.0 | — |
| Swagger UI | `appointment-api` | v0.1.0 | — |
| GitHub Actions CI (gitleaks, Detekt, JaCoCo) | `.github/workflows` | v0.1.0 / v0.3.0 | — |
| 장비 사용불가 스케줄 CRUD + Solver 제약 | `appointment-core` / `appointment-solver` | v0.2.0 | [domain-model.md](domain-model.md) |
| `@Profile` 환경 분리 (local/dev/prod) | `appointment-api` | v0.2.0 | — |
| 마스터 데이터 CRUD API (Clinic/Doctor/TreatmentType/Equipment) | `appointment-api` | v0.3.0 | [architecture.md](architecture.md) |
| 멀티테넌시 기반 (`TenantGroup`, tenant path/JWT guard, 핵심 resource ID guard) | `appointment-core` / `appointment-api` | v0.3.0 | [architecture.md](architecture.md#adr-14-멀티테넌시-식별자와-key-authority) |
| Angular 21 웹 UI (30개 엔드포인트 전체 연결) | `appointment-frontend` | v0.1.0 / v0.3.0 | [frontend.md](frontend.md) |
| Gatling 부하 테스트 (멀티 클리닉 포함) | `appointment-api` | v0.3.0 | — |
| Solver 벤치마크 baseline | `appointment-solver` | v0.3.0 | — |

### 미구현 (Backlog)

| 요구사항 | 모듈 | 우선순위 | 비고 |
|---------|------|----------|------|
| **환자 포털 (자가 예약 웹앱)** | `appointment-patient-portal` (신규) | MEDIUM | TODO 섹션 9.1 |
| **멀티테넌시 계약 정합화** | `appointment-core` / `appointment-api` / `appointment-solver` | HIGH | 기반은 PR #118에서 완료. #37~#39에서 schema·HTTP authority·repository 격리 보강. [감사 기록](../reviews/2026-08-04-multitenancy-audit.md) |
| **메시지 큐 (Kafka4 비동기)** | `appointment-messaging` (신규) | LOW | broker 결정 #40 완료, 구현 #41/#42 — TODO 섹션 9.3 |
| **관리자 대시보드 (통계/분석)** | `appointment-dashboard` (신규) | LOW | TODO 섹션 9.4 |
| **SSE 기반 일괄 재배정 진행 표시** | `appointment-api` / `appointment-frontend` | HIGH | TODO 섹션 3.5 |

## 설계 문서 목록

| 문서 | 내용 |
|------|------|
| [architecture.md](architecture.md) | 모듈 의존성 그래프, 주요 설계 결정 (ADR 스타일) |
| [멀티테넌시 감사 기록](../reviews/2026-08-04-multitenancy-audit.md) | PR #118 이후 #36~#39 구현 상태, 키 계약, 후속 순서 |
| [domain-model.md](domain-model.md) | 17개 도메인 엔티티, 예약 상태머신 전이도, Exposed 테이블 목록 |
| [solver.md](solver.md) | Timefold Solver Planning Variable, Hard/Soft 제약조건 전체 목록 |
| [notification.md](notification.md) | 내구성 outbox 생명주기, 병원별 전환, 회원정보 경계, provider 장애 격리 |
| [frontend.md](frontend.md) | Angular 21 페이지 구성, API 연동, 빌드 설정 |
| [erd.md](erd.md) | 전체 테이블 ERD (Mermaid), 관계 요약, 컬럼 타입 규칙 |
| [data-flow.md](data-flow.md) | 예약 생성·슬롯 조회·재배정·Solver·알림 데이터 흐름 다이어그램 |
| [user-scenarios.md](user-scenarios.md) | 예약 생성·체크인·임시휴진 재배정·리마인더 시퀀스 다이어그램 |

## 다이어그램 산출물

Mermaid 원본 블록은 각 요구사항 문서에 유지하고, GitHub와 문서 사이트에서
바로 확인할 수 있도록 PNG를 본문에 삽입했습니다. SVG와 추출된 Mermaid 원본은
[`assets/`](assets/)에 함께 둡니다.

| 문서 | 렌더링한 다이어그램 |
|------|---------------------|
| [architecture.md](architecture.md) | 모듈 의존성 그래프 |
| [domain-model.md](domain-model.md) | 예약 상태머신 |
| [erd.md](erd.md) | 전체 테이블 ERD |
| [data-flow.md](data-flow.md) | 예약 생성, 슬롯 조회, 임시휴진 재배정, 장비 사용불가, 알림 outbox, Solver 데이터 흐름 |
| [user-scenarios.md](user-scenarios.md) | 환자 예약, 상태 변경, Solver 재배정, 장비 사용불가, 내구성 리마인더 시퀀스 |

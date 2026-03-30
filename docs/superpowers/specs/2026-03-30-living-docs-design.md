# Living Documentation 설계 — README / CHANGELOG / 요구사항 문서화

> **Date**: 2026-03-30
> **Scope**: clinic-appointment 저장소 문서 체계 구축
> **Status**: Approved

---

## 1. 목표

| # | 목표 | 측정 기준 |
|---|------|----------|
| G1 | README 전면 개편 | 기능 설명, 아키텍처, 빠른 시작 포함 |
| G2 | CHANGELOG 도입 | Keep a Changelog 1.1.0 표준, v0.1.0 초기 항목 포함 |
| G3 | 요구사항 문서화 | `docs/requirements/` 구조, 구현 상태표 포함 |
| G4 | 모듈별 README | 6개 모듈 각각 `README.md` 작성 (Living Docs) |

---

## 2. 파일 구조

```
clinic-appointment/
├── README.md                              # 전면 개편
├── CHANGELOG.md                           # Keep a Changelog 형식
└── docs/
    └── requirements/
        ├── README.md                      # 요구사항 인덱스 + 구현 상태표
        ├── architecture.md                # 모듈 의존성, 설계 결정 (ADR 스타일)
        ├── domain-model.md                # 도메인 엔티티, 상태머신, 테이블 관계
        ├── solver.md                      # Timefold Solver 설계
        ├── notification.md                # 알림 모듈 설계
        └── frontend.md                    # Angular 프론트엔드 설계

appointment-core/README.md                 # 도메인 모델, 상태머신, 리포지토리 사용법
appointment-event/README.md                # 이벤트 타입, 발행/구독 패턴
appointment-solver/README.md               # Solver 설정, 제약조건, 실행 방법
appointment-notification/README.md         # 알림 채널, 설정값, Resilience4j 구성
appointment-api/README.md                  # API 엔드포인트, 인증, Swagger URL
frontend/appointment-frontend/README.md    # 개발 서버 실행, 빌드
```

---

## 3. 루트 README.md 구조

> **대상 독자: 사용자 (기능 중심)**

```markdown
# clinic-appointment

![CI](badge) ![License](badge)

개인병원 환자 예약 관리 시스템 — Kotlin 2.3 + Spring Boot 4 + Timefold Solver

## 주요 기능
- 예약 생성/확정/취소/재배정 (상태 머신 기반)
- AI 최적 스케줄링 (Timefold Solver — 의사/장비/시간 최적 배치)
- 고가용성 알림 (Redis Leader Election + Resilience4j)
- JWT 인증 REST API (Swagger UI 제공)
- Angular 18 웹 UI

## 아키텍처
Mermaid 모듈 의존성 다이어그램

## 모듈
각 모듈별 한 문단 — 역할 + 핵심 기술 + 개발자 README 링크

## 빠른 시작
TODO: Docker Compose 환경 구성 후 업데이트

## 빌드 & 테스트
기존 내용 유지

## 문서
### 요구사항 & 설계
| 문서 | 내용 |
|------|------|
| [요구사항 인덱스](docs/requirements/README.md) | 전체 요구사항 목록 + 구현 상태 |
| [아키텍처](docs/requirements/architecture.md) | 모듈 의존성, 설계 결정 |
| [도메인 모델](docs/requirements/domain-model.md) | 엔티티, 상태머신, 테이블 관계 |
| [AI 스케줄러](docs/requirements/solver.md) | Timefold Solver 설계 |
| [알림 모듈](docs/requirements/notification.md) | 알림 채널, HA 구성 |
| [프론트엔드](docs/requirements/frontend.md) | Angular 구성 |

### 변경 이력
- [CHANGELOG.md](CHANGELOG.md)
```

---

## 4. CHANGELOG.md 구조

- 형식: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) 1.1.0
- 섹션: `Added`, `Changed`, `Fixed`, `Removed`
- 버전 정책:
  - 기능 추가 → MINOR 증가 (`0.1.0` → `0.2.0`)
  - 버그픽스 → PATCH 증가 (`0.1.0` → `0.1.1`)
- 초기 버전: `0.1.0` (2026-03-30) — 6개 모듈 독립 저장소 이전 완료 시점

---

## 5. docs/requirements/ 구조

### README.md — 구현 상태표

| 요구사항 | 모듈 | 상태 | 문서 |
|---------|------|------|------|
| 예약 CRUD + 상태머신 | appointment-core | ✅ 완료 | [domain-model.md](domain-model.md) |
| 도메인 이벤트 퍼블리싱 | appointment-event | ✅ 완료 | [architecture.md](architecture.md) |
| AI 최적 스케줄링 | appointment-solver | ✅ 완료 | [solver.md](solver.md) |
| HA 알림 스케줄러 | appointment-notification | ✅ 완료 | [notification.md](notification.md) |
| REST API + JWT + Swagger | appointment-api | ✅ 완료 | [architecture.md](architecture.md) |
| Angular 18 웹 UI | appointment-frontend | ✅ 완료 | [frontend.md](frontend.md) |
| GitHub Actions CI | .github/workflows | ✅ 완료 | — |
| 실제 알림 채널 (Email/SMS/Push) | appointment-notification | ❌ 미구현 | [notification.md](notification.md) |
| Docker Compose 로컬 환경 | — | ❌ 미구현 | — |
| 환자 포털 | appointment-patient-portal | ❌ 미구현 | — |
| 멀티테넌시 | appointment-core | ❌ 미구현 | — |
| 메시지 큐 (Kafka/RabbitMQ) | appointment-messaging | ❌ 미구현 | — |

### architecture.md
- 모듈 의존성 그래프 (Mermaid)
- 주요 설계 결정 (ADR 스타일): 디렉토리 구조, Composite Build, 패키지명 등
- 출처: 기존 `2026-03-30-clinic-appointment-design.md` 재정리

### domain-model.md
- 도메인 엔티티 16개 (AppointmentRecord, ClinicRecord, DoctorRecord 등)
- 예약 상태 머신 (AppointmentState / AppointmentEvent)
- 테이블 관계도

### solver.md
- Planning Variable 정의 (doctorId, appointmentDate, startTime)
- 제약조건 목록 (Hard / Soft)
- SlotCalculationService와의 역할 분리
- 출처: 기존 `2026-03-21-appointment-solver-design.md` 재정리

### notification.md
- NotificationChannel 인터페이스
- 알림 이벤트 타입 (CREATED, CONFIRMED, CANCELLED, RESCHEDULED, REMINDER)
- HA 구성 (Redis Leader Election)
- Resilience4j 설정 (CircuitBreaker, Retry, Bulkhead)
- 미구현 항목: 실제 Email/SMS/Push 채널
- 출처: 기존 `2026-03-21-appointment-notification-design.md` 재정리

### frontend.md
- 페이지 구성 및 라우팅
- API 연동 방식
- 빌드 설정 (node-gradle plugin)
- 출처: 기존 `2026-03-21-appointment-frontend-design.md` 재정리

---

## 6. 모듈별 README.md 내용 가이드

> **대상 독자: 개발자 (구현 중심)**

### 기본 템플릿 (모든 모듈 공통)

```
# <module-name>

한 줄 역할 설명

## 책임
- 이 모듈이 하는 것
- 하지 않는 것 (경계 명확화)

## 핵심 클래스
주요 클래스/인터페이스 목록 + 한 줄 설명

## 의존성
- 내부: project(":xxx")
- 외부: 주요 라이브러리

## 설정
application.yml 관련 설정 키 (해당 시)

## 설계 문서
- [도메인 모델 UML](../docs/requirements/domain-model.md)  ← 역할 비중이 큰 모듈에 링크

## 테스트 실행
./gradlew :<module>:test
```

### 모듈별 상세 수준

역할 비중이 큰 모듈은 핵심 클래스 섹션을 더 상세히 작성한다.

| 모듈 | 상세 수준 | 추가 섹션 |
|------|----------|----------|
| `appointment-core` | **상세** | 상태머신 전이도 (Mermaid), 엔티티 관계도 링크, 리포지토리 사용 예제 코드 |
| `appointment-solver` | **상세** | 제약조건 목록 (Hard/Soft), Planning Variable 설명, Solver 실행 흐름도 링크 |
| `appointment-notification` | **상세** | 알림 채널 인터페이스 설명, Resilience4j 설정 예시, Leader Election 동작 설명 |
| `appointment-api` | **중간** | API 엔드포인트 그룹 목록, Swagger URL, 인증 흐름 |
| `appointment-event` | **간략** | 이벤트 타입 목록, 발행/구독 패턴 예시 |
| `appointment-frontend` | **간략** | 개발 서버 실행, 빌드, API 프록시 설정 |

---

## 7. 작업 순서

1. 루트 `README.md` 전면 개편
2. `CHANGELOG.md` 생성 (v0.1.0 초기 항목)
3. `docs/requirements/README.md` — 상태표
4. `docs/requirements/architecture.md` — 기존 design doc에서 재정리
5. `docs/requirements/domain-model.md`
6. `docs/requirements/solver.md` — 기존 solver design doc에서 재정리
7. `docs/requirements/notification.md` — 기존 notification design doc에서 재정리
8. `docs/requirements/frontend.md` — 기존 frontend design doc에서 재정리
9. 모듈별 README.md 6개

---

## 8. 비포함 (Out of Scope)

- 실제 알림 채널 구현 (Email/SMS/Push)
- Docker Compose 환경 구성
- API 엔드포인트 상세 문서 (Swagger가 커버)
- 배포/운영 가이드

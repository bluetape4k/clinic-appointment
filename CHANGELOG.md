# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- (다음 릴리스에 포함될 항목)

---

## [0.3.0] - 2026-04-20

### Added
- `appointment-api`: `ClinicController`, `DoctorController`, `TreatmentTypeController`, `EquipmentController` — 마스터 데이터 CRUD API 추가
- `appointment-api`: Gatling 멀티 클리닉 부하 테스트 시뮬레이션 추가
- `appointment-solver`: Solver 벤치마크 baseline 정의 및 `ConcurrencyResolver` 동시예약 통합 테스트
- `frontend/appointment-frontend`: Reschedule + Equipment Unavailability 관리 페이지 구현
- `frontend/appointment-frontend`: 실제 백엔드 API 연결 (30개 엔드포인트 전체 커버)
- `frontend/appointment-frontend`: 캘린더 뷰 및 재배정 컴포넌트 로딩 스피너 추가
- CI: Flyway 벤더별 마이그레이션 분리 + 멀티 DB 마이그레이션 테스트 매트릭스 추가
- CI: 프론트엔드 빌드 CI 추가
- CI: gitleaks 보안 스캔 설정 추가

### Changed
- `appointment-api`: Controller 테스트 전면 전환 (`MockMvc` → `RestClient`)
- `buildSrc/Libs.kt`: bluetape4k `1.5.0-RC1` → `1.5.0` → `1.6.2` 업데이트
- `buildSrc/Libs.kt`: jackson3 및 Exposed API 의존성 업데이트
- `frontend/appointment-frontend`: Angular 21 유지 결정

---

## [0.2.0] - 2026-03-31

### Added
- `appointment-core`: `EquipmentUnavailabilityRepository` — 장비 사용불가 구간 CRUD + 예외일 관리
- `appointment-core`: `UnavailabilityExpander` — 반복 규칙(SKIP/RESCHEDULE 예외) 기반 사용불가 기간 전개
- `appointment-core`: `EquipmentUnavailabilityService` — CRUD + 기간 전개, 단일 트랜잭션 검증 적용
- `appointment-core`: `SlotCalculationService` — 장비 사용불가 체크 단계 추가 (슬롯 검증 파이프라인)
- `appointment-solver`: `EquipmentUnavailabilityFact` — ProblemFact 추가 및 `ScheduleSolution` 등록
- `appointment-solver`: H11 제약 — 장비 사용불가 기간 중 예약 배정 금지 (Hard Constraint)
- `appointment-api`: `EquipmentUnavailabilityController` — 사용불가 스케줄 CRUD + 충돌 감지 API
- `appointment-api`: 장비 사용불가 Request/Response DTO 추가
- `appointment-api`: `@Profile` 기반 환경 분리 (`local` / `dev` / `prod`) + `ClinicTimezoneService` API 연결
- `frontend/appointment-frontend`: `RescheduleService`, `EquipmentUnavailabilityService` Angular 서비스 추가

### Fixed
- `appointment-api`: `RescheduleService.getClosureCandidates` — GET → POST (백엔드 계약 수정)
- `appointment-core`: `EquipmentUnavailabilityService` — `checkNotNull` → bluetape4k `requireNotNull` 수정

### Changed
- `buildSrc/Libs.kt`: bluetape4k 버전 `1.5.0-Beta3` 업데이트, 신규 모듈 11개 추가
- `appointment-solver`: H11 제약 이름 camelCase 통일, `Joiner` nullable 명시

---

## [0.1.0] - 2026-03-30

### Added
- `appointment-core`: 도메인 모델 16개 엔티티 (Clinic, Doctor, Appointment, TreatmentType, Equipment 등), Exposed ORM 테이블, 예약 상태머신 (10개 상태, 10개 이벤트)
- `appointment-core`: 슬롯 계산 서비스 (`SlotCalculationService`), 임시휴진 재배정 서비스 (`ClosureRescheduleService`), 동시성 해결기 (`ConcurrencyResolver`)
- `appointment-event`: Spring `ApplicationEvent` 기반 도메인 이벤트 (Created, StatusChanged, Cancelled, Rescheduled), 이벤트 로그 Exposed 테이블 저장
- `appointment-solver`: Timefold Solver AI 최적 스케줄링 — Hard 제약 10개 (영업시간, 의사 스케줄, 부재, 휴식, 임시휴진, 공휴일, 동시환자, 장비, 진료유형, 클리닉 소속), Soft 제약 2개 (의사 부하 분산, 스케줄 갭 최소화)
- `appointment-notification`: Redis Leader Election 기반 단일 노드 알림 보장, Resilience4j CircuitBreaker/Retry/Bulkhead 적용, 예약 리마인더 스케줄러 (전날/당일), 알림 이력 DB 저장
- `appointment-api`: Spring Boot 4 REST API — 예약 CRUD (`/api/appointments`), 슬롯 조회 (`/api/slots`), 재배정 (`/api/reschedule`), JWT 인증, Flyway 마이그레이션, Swagger UI, Gatling 부하 테스트
- `frontend/appointment-frontend`: Angular 18 웹 UI
- GitHub Actions CI workflow (PR 빌드 + 테스트)
- `settings.gradle.kts`: bluetape4k-projects 조건부 Composite Build (`includeBuild`) 연결

[Unreleased]: https://github.com/bluetape4k/clinic-appointment/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/bluetape4k/clinic-appointment/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/bluetape4k/clinic-appointment/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/bluetape4k/clinic-appointment/releases/tag/v0.1.0

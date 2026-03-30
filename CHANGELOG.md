# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- (다음 릴리스에 포함될 항목)

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

[Unreleased]: https://github.com/bluetape4k/clinic-appointment/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/bluetape4k/clinic-appointment/releases/tag/v0.1.0

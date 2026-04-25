# TODO — clinic-appointment

> 최종 점검일: 2026-04-20

---

## 0. 공통 원칙 — bluetape4k 모듈 적극 활용

> **소스 위치:** `~/work/bluetape4k/bluetape4k-projects`

직접 구현하기 전에 반드시 bluetape4k에 이미 있는 모듈을 확인하고 활용할 것.

### 현재 프로젝트에서 사용 중인 모듈

| 모듈 | 용도 |
|------|------|
| `bluetape4k-core` | 확장 함수, requireNotBlank, requireNotNull 등 |
| `bluetape4k-coroutines` | 코루틴 확장 |
| `bluetape4k-logging` | KLogging |
| `bluetape4k-junit5` | 테스트 유틸 |
| `bluetape4k-testcontainers` | TC 싱글턴 패턴 |
| `bluetape4k-exposed-core` | Exposed ORM 확장 |
| `bluetape4k-exposed-jdbc` | JDBC 트랜잭션 지원 |
| `bluetape4k-exposed-jdbc-tests` | Exposed 테스트 `withTables` |
| `bluetape4k-leader` | Redis Leader Election |
| `bluetape4k-lettuce` | Redis 클라이언트 |
| `bluetape4k-resilience4j` | CircuitBreaker/Retry 확장 |

### 도입 예정 모듈

| 모듈 | 용도 | 우선순위 | 이슈 |
|------|------|----------|------|
| `bluetape4k-cache-core` | Caffeine 기반 로컬 캐시 추상화 | **HIGH** | [EPIC #12](https://github.com/bluetape4k/clinic-appointment/issues/12) |
| `bluetape4k-exposed-jdbc-caffeine` | Exposed 쿼리 결과 Caffeine 캐싱 | **HIGH** | [#20](https://github.com/bluetape4k/clinic-appointment/issues/20) |
| `bluetape4k-jackson3` | Jackson 3 직렬화 유틸 | MEDIUM | [#49](https://github.com/bluetape4k/clinic-appointment/issues/49) |
| `bluetape4k-http` | HTTP 클라이언트 확장 (테스트 시) | LOW | — |
| `bluetape4k-virtualthread-jdk25` | Virtual Thread 지원 | LOW | — |

### 활용 가이드라인

1. **캐시**: `bluetape4k-cache-core` (Caffeine) → 슬롯 조회, 의사/장비 목록 등 읽기 빈도 높은 API에 적용
2. **Exposed 캐시**: `bluetape4k-exposed-jdbc-caffeine` → Repository 레벨 쿼리 캐싱
3. **테스트**: `bluetape4k-junit5` + `bluetape4k-exposed-jdbc-tests`의 `withTables` 패턴 사용
4. **검증**: `requireNotBlank`, `requireNotNull` 등 bluetape4k 확장 함수 우선 사용 (stdlib check() 지양)
5. **로깅**: `KLogging` companion object 패턴 일관 적용
6. **Spring Boot 4**: `bluetape4k-projects/spring-boot4/` 하위 모듈 참고 (exposed-jdbc, redis 등)

---

## 1. 테스트 커버리지 보강 (HIGH)

### 1.1 API Controller 테스트

4개 컨트롤러 모두 RestClient + `@SpringBootTest(RANDOM_PORT)` 방식으로 작성 완료 (35 tests passing):

| Controller | 테스트 | 상태 |
|---|---|---|
| `AppointmentController` | `AppointmentControllerTest.kt` | ✅ |
| `EquipmentUnavailabilityController` | `EquipmentUnavailabilityControllerTest.kt` | ✅ |
| `RescheduleController` | `RescheduleControllerTest.kt` | ✅ |
| `SlotController` | `SlotControllerTest.kt` | ✅ |

### 1.2 Gatling 부하 테스트 ✅

4개 시뮬레이션 작성 완료:

| Simulation | 설명 |
|---|---|
| `AppointmentApiSimulation` | 기본 CRUD 부하 |
| `DateRangeQuerySimulation` | 날짜 범위 조회 부하 |
| `ClosureRescheduleSimulation` | 휴진 + 재배정 시나리오 |
| `MultiClinicScaleSimulation` | 멀티 클리닉 규모 (10/100/300명 동시) |

리포트: `docs/gatling-multi-clinic-report.md`, `docs/gatling-reports/`

### 1.3 프론트엔드 테스트 커버리지 (MEDIUM)

26개 spec 파일, 176 tests passing. ✅ 완료:

- ✅ `ClinicService` 테스트 — `clinic.service.spec.ts` (10 tests)
- ✅ `EquipmentService` 테스트 — `equipment.service.spec.ts` (7 tests)
- ✅ `RescheduleService` 테스트 — `reschedule.service.spec.ts` (8 tests)
- ✅ Calendar 컴포넌트 테스트 — `day-view` (5), `week-view` (4), `month-view` (5)
- ✅ Management 컴포넌트 테스트 — `doctor-list` (4), `treatment-type-list` (4), `clinic-list` (4)

---

## 2. 모바일 WebView 대응 — Angular 21 유지 + Capacitor (MEDIUM)

### 결정 (ADR 2026-04-19)

Angular 21 유지. 프레임워크 마이그레이션 **미채택**.

**근거:**
- 현재 코드베이스가 이미 Angular 21 최적 경로 (zoneless, signals, standalone, 최소 RxJS)
- 번들 사이즈 차이 미미: Angular 21 zoneless ~45KB vs React ~47KB vs Vue ~33KB (12-20KB 차이)
- WebView 고려사항은 프레임워크 무관 (Capacitor, postMessage, Safe Area 모두 동일)
- 마이그레이션 비용 32-48 영업일 — ROI 부적합
- Angular는 헬스케어/규제 도메인에서 지배적 (opinionated 구조, 필수 TypeScript)

### 할 일 ([EPIC #13](https://github.com/bluetape4k/clinic-appointment/issues/13))

- ⬜ Capacitor 프로젝트 초기화 (`@capacitor/core`, `@capacitor/ios`, `@capacitor/android`) — [#23](https://github.com/bluetape4k/clinic-appointment/issues/23)
- ⬜ iOS Safari WebView + Android WebView 통합 테스트 — [#24](https://github.com/bluetape4k/clinic-appointment/issues/24)
- ⬜ PWA 지원 강화 (`@angular/pwa`, Service Worker, 오프라인 캐싱) — [#25](https://github.com/bluetape4k/clinic-appointment/issues/25)
- ⬜ 모바일 UX 최적화 (뷰포트, 터치 제스처, Safe Area, 키보드 처리) — [#26](https://github.com/bluetape4k/clinic-appointment/issues/26)
- ⬜ Lazy loading / code splitting 최적화 (라우트별 번들 분리)
- ⬜ 네이티브 ↔ WebView 통신 계층 설계 (Deep link, postMessage) — [#27](https://github.com/bluetape4k/clinic-appointment/issues/27)

---

## 3. 프론트엔드 개선 (MEDIUM)

### 3.1 API 커버리지 ✅

전체 백엔드 엔드포인트 프론트엔드 연결 완료:

| Controller | 엔드포인트 | 구현 | 미구현 |
|---|---|---|---|
| AppointmentController | 5 | 5 | 0 |
| SlotController | 1 | 1 | 0 |
| RescheduleController | 4 | 4 | 0 |
| EquipmentUnavailabilityController | 8 | 8 | 0 |
| ClinicController | 4 | 4 | 0 |
| DoctorController | 4 | 4 | 0 |
| TreatmentTypeController | 2 | 2 | 0 |
| EquipmentController | 2 | 2 | 0 |

### 3.2 ClinicListComponent 실제 API 연결 (HIGH) ✅

`clinic-list.component.ts` MOCK_CLINICS 제거, `ClinicService.getAll()` 연결 완료.

- ✅ `ClinicListComponent` → `ClinicService.getAll()` 연결
- ✅ 목 데이터(`MOCK_CLINICS`) 제거

### 3.3 하드코딩 clinicId 해소 (MEDIUM) — [#46](https://github.com/bluetape4k/clinic-appointment/issues/46)

8개 컴포넌트에서 `const CLINIC_ID = 1` 또는 `private readonly clinicId = 1` 하드코딩됨:

| 컴포넌트 | 위치 |
|----------|------|
| `day-view.component.ts` | `const CLINIC_ID = 1` |
| `week-view.component.ts` | `const CLINIC_ID = 1` |
| `month-view.component.ts` | `const CLINIC_ID = 1` |
| `doctor-list.component.ts` | `const CLINIC_ID = 1` |
| `treatment-type-list.component.ts` | `const CLINIC_ID = 1` |
| `appointment-list.component.ts` | `private readonly clinicId = 1` |
| `appointment-form.component.ts` | `private readonly clinicId = 1` |
| `appointment-detail.component.ts` | `private readonly clinicId = 1` |

**해결 방안:** JWT 토큰에서 clinicId 추출하는 `AuthService.clinicId` signal 추가 → 각 컴포넌트에서 inject하여 사용.

### 3.4 Management 라우트 권한 보호 (MEDIUM)

`/management` 하위 라우트에 `roleGuard` 미적용:
- `/appointments/new`, `/appointments/:id/edit`은 guard 있음 ✅
- `/management/**` 전체에 `canActivate: [roleGuard]` 필요 (ADMIN/STAFF만 접근)

- ⬜ `management.routes.ts`에 상위 레벨 `canActivate` 추가 — [#47](https://github.com/bluetape4k/clinic-appointment/issues/47)

### 3.5 SSE 기반 일괄 재배정 진행 상황 표시 (HIGH) — [EPIC #14](https://github.com/bluetape4k/clinic-appointment/issues/14)

건당 예약 취소/변경은 단순 spinner로 충분하지만, 휴진 일괄 재배정은 N건을 순차 처리하므로 실시간 진행 피드백이 필요.

**백엔드:**
- ⬜ `GET /api/reschedule/batch/stream` — `text/event-stream` SSE 엔드포인트 — [#29](https://github.com/bluetape4k/clinic-appointment/issues/29)
- ⬜ 예약별 처리 결과를 이벤트로 스트리밍: `data: {"appointmentId": N, "status": "SUCCESS|FAILED", "newAppointmentId": M, "progress": "3/15"}`
- ⬜ 완료 시 `event: complete` + 요약 데이터 전송

**프론트엔드:**
- ⬜ `RescheduleService`에 `EventSource` 기반 SSE 연결 메서드 추가 — [#30](https://github.com/bluetape4k/clinic-appointment/issues/30)
- ⬜ `reschedule-list.component` — 일괄 재배정 시 progress bar + 개별 결과 실시간 테이블 갱신 — [#31](https://github.com/bluetape4k/clinic-appointment/issues/31)
- ⬜ 연결 실패/타임아웃 시 fallback (polling 또는 에러 표시)

**설계 포인트:**
- 건당 예약 → spinner (현재 구현 완료)
- 일괄 재배정 (관리자) → SSE 진행률 + 실시간 결과 스트리밍

### 3.6 환경 설정 파일 (LOW) — [#48](https://github.com/bluetape4k/clinic-appointment/issues/48)

현재 API baseUrl이 `/api/` 상대경로로 하드코딩. 프로덕션 배포 시 `environment.ts` 구성 필요:

- ⬜ `src/environments/environment.ts` + `environment.prod.ts` 추가
- ⬜ 각 서비스의 `baseUrl`을 환경 설정에서 주입

---

## 4. 백엔드 API 확장 ✅

- ✅ `ClinicController` — 클리닉 조회 API (GET /api/clinics, /{id}, /{id}/operating-hours, /{id}/break-times)
- ✅ `DoctorController` — 의사 조회 API (GET /api/clinics/{clinicId}/doctors, /doctors/{id}, /{id}/schedules, /{id}/absences)
- ✅ `TreatmentTypeController` — 진료 유형 조회 API (GET /api/clinics/{clinicId}/treatment-types, /treatment-types/{id})
- ✅ `EquipmentController` — 장비 조회 API (GET /api/clinics/{clinicId}/equipments, /equipments/{id})
- ✅ `EquipmentRepository` — 장비 전용 리포지토리 생성

---

## 5. 문서화 (LOW-MEDIUM)

### 5.1 Living Documentation (미구현 부분)

`docs/requirements/` 디렉토리 구조는 존재하나 내용 보강 필요:

- ✅ `docs/requirements/README.md` — 요구사항 인덱스 + 구현 상태표 (2026-04-20 최신화)
- ✅ `docs/requirements/architecture.md` — ADR 스타일 설계 결정 기록 (2026-04-20 최신화, ADR-7/8 추가)
- ✅ `docs/requirements/domain-model.md` — 엔티티/상태머신/관계 다이어그램 (2026-04-20 최신화)
- ✅ `docs/requirements/solver.md` — Timefold 제약 조건 설명 (2026-04-20 최신화, H11 추가)
- ✅ `docs/requirements/notification.md` — 알림 모듈 아키텍처 (2026-04-20 최신화)
- ✅ `docs/requirements/frontend.md` — Angular 프론트엔드 구조 (2026-04-20 최신화)

### 5.2 모듈별 README

- ✅ `appointment-core/README.md`
- ✅ `appointment-event/README.md`
- ✅ `appointment-solver/README.md`
- ✅ `appointment-notification/README.md`
- ✅ `appointment-api/README.md`

---

## 6. 인프라 & DevOps (LOW)

- ~~`docker-compose.yml`~~ — 불필요. `bluetape4k-testcontainers` (`PostgreSQLServer`, `MySQL8Server`, `RedisServer`) + `@Profile` 전략으로 대체. 프로파일별 원하는 서버 기동 및 커넥션 설정.
- ✅ GitHub Actions CI — 모듈별 병렬 테스트 + gitleaks + Detekt + JaCoCo 커버리지
- ✅ Flyway 마이그레이션 검증 자동화 — 벤더별(H2/PostgreSQL/MySQL) 마이그레이션 SQL 분리 + `FlywayMigrationTest`로 H2 검증 (CI 포함)
- ✅ CI 프론트엔드 빌드 추가 — `npm ci && npm run build` (Angular 21 프로덕션 빌드 검증, Node.js 22)
- ✅ gitleaks allowlist 수정 — `TestJwtProvider.kt` 테스트 시크릿 제외
- ✅ CI 멀티 DB 테스트 매트릭스 — H2/PostgreSQL/MySQL Flyway 마이그레이션 검증 (Testcontainers, matrix strategy)

---

## 7. 코드 품질 (LOW)

- ✅ Solver 벤치마크 — `BenchmarkTest.kt` 결과 기준선(baseline) 정의 ✅ → [리포트](docs/solver-benchmark-report.md)
- ✅ `ConcurrencyResolver` — 동시 예약 충돌 시나리오 통합 테스트 ✅
- ~~Notification 모듈~~ — 외부 Application 사용 예정, `DummyNotificationChannel` 유지 (의도적)

---

## 8. 의존성 관리 (LOW) — [#49](https://github.com/bluetape4k/clinic-appointment/issues/49)

- ⬜ bluetape4k BOM 버전 최신화 모니터링 (현재 1.6.2)
- ⬜ Jackson 3 마이그레이션 완료 여부 확인
- ⬜ Spring Boot 4 GA 업데이트 추적

---

## 9. 백로그 — 미구현 요구사항 (`docs/requirements/README.md` 기준)

### 9.1 환자 포털 (MEDIUM) — [EPIC #15](https://github.com/bluetape4k/clinic-appointment/issues/15)

자가 예약 웹앱 — 환자가 직접 예약/확인/취소하는 별도 프론트엔드.

- ⬜ `appointment-patient-portal` 모듈 신규 생성 (Angular 또는 별도 SPA) — [#32](https://github.com/bluetape4k/clinic-appointment/issues/32)
- ⬜ 환자 인증 (별도 JWT Role: `PATIENT`) — [#33](https://github.com/bluetape4k/clinic-appointment/issues/33)
- ⬜ 예약 생성/조회/취소 UI — 기존 `/api/appointments` 재사용 — [#34](https://github.com/bluetape4k/clinic-appointment/issues/34)
- ⬜ 슬롯 선택 캘린더 UI — [#35](https://github.com/bluetape4k/clinic-appointment/issues/35)
- ⬜ 예약 확인 알림 수신 (SSE 또는 polling)

### 9.2 멀티테넌시 — 병원 그룹 데이터 격리 (MEDIUM) — [EPIC #16](https://github.com/bluetape4k/clinic-appointment/issues/16)

현재 clinicId를 단순 FK로 관리. 병원 그룹(테넌트)별 데이터 격리 필요 시 별도 설계 필요.

- ⬜ 테넌트 ID 전략 결정 (Row-level 격리 vs Schema 분리 vs DB 분리) — [#36](https://github.com/bluetape4k/clinic-appointment/issues/36)
- ⬜ `Clinic` → `TenantGroup` 상위 엔티티 도입 검토 — [#37](https://github.com/bluetape4k/clinic-appointment/issues/37)
- ⬜ JWT 토큰에 `tenantId` 클레임 추가 + 스프링 시큐리티 필터 연동 — [#38](https://github.com/bluetape4k/clinic-appointment/issues/38)
- ⬜ Exposed Row-level Security 또는 테넌트 필터 인터셉터 구현 — [#39](https://github.com/bluetape4k/clinic-appointment/issues/39)
- ⬜ 기존 `clinicId` 하드코딩 해소(섹션 3.3, [#46](https://github.com/bluetape4k/clinic-appointment/issues/46)) 이후 진행 권장

### 9.3 메시지 큐 — 비동기 이벤트 처리 (LOW) — [EPIC #17](https://github.com/bluetape4k/clinic-appointment/issues/17)

현재 Spring `ApplicationEvent`로 동기 처리 중. 대용량/외부 시스템 연동 시 Kafka/RabbitMQ 필요.

- ⬜ Kafka 또는 RabbitMQ 도입 검토 (bluetape4k 지원 여부 확인 선행) — [#40](https://github.com/bluetape4k/clinic-appointment/issues/40)
- ⬜ `appointment-messaging` 신규 모듈 생성 — [#41](https://github.com/bluetape4k/clinic-appointment/issues/41)
- ⬜ 도메인 이벤트(Created/StatusChanged/Cancelled/Rescheduled) → 메시지 큐 발행
- ⬜ 외부 시스템(알림, 통계) 구독 컨슈머 구현 — [#42](https://github.com/bluetape4k/clinic-appointment/issues/42)
- ⬜ 이벤트 스키마 버전 관리 (Avro/JSON Schema Registry)

### 9.4 관리자 대시보드 — 통계/분석 (LOW) — [EPIC #18](https://github.com/bluetape4k/clinic-appointment/issues/18)

예약 현황, 의사별 부하, 클리닉 운영 지표를 시각화하는 관리자 전용 뷰.

- ⬜ `appointment-dashboard` 신규 모듈 또는 프론트엔드 `/admin` 라우트 확장
- ⬜ 집계 쿼리 API 설계 — 일별/주별 예약 건수, 취소율, 의사 부하 분포 — [#44](https://github.com/bluetape4k/clinic-appointment/issues/44)
- ⬜ Exposed `groupBy`/`sum`/`count` 집계 쿼리 구현
- ⬜ 차트 라이브러리 도입 (Chart.js 또는 Recharts) — [#45](https://github.com/bluetape4k/clinic-appointment/issues/45)
- ⬜ 관리자 Role Guard 연동 (섹션 3.4 완료 선행, [#47](https://github.com/bluetape4k/clinic-appointment/issues/47))

---

## 완료 항목 (참고)

- ✅ v0.1.0 — 도메인 모델, 상태머신, Solver, Notification, API 기본 구현
- ✅ v0.2.0 — 장비 사용불가 스케줄, @Profile 환경 분리, ClinicTimezoneService
- ✅ CHANGELOG.md — Keep a Changelog 형식 유지 중
- ✅ GitHub Actions CI workflow 구성
- ✅ API Controller 테스트 전면 전환 — MockMvc → Spring Boot 4 RestClient (2026-04-19)
- ✅ 프론트엔드 30개 엔드포인트 API 연결 완료 (2026-04-19)
- ✅ 백엔드 Clinic/Doctor/TreatmentType/Equipment Controller 추가 (2026-04-19)
- ✅ Solver 벤치마크 baseline 정의 + ConcurrencyResolver 통합 테스트 (2026-04-19)
- ✅ v0.3.0 — 마스터 데이터 Controller 4개, 프론트엔드 30개 API 연결, CI 강화, bluetape4k 1.6.2 업그레이드 (2026-04-20)

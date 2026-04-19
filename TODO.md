# TODO — clinic-appointment

> 최종 점검일: 2026-04-19

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

| 모듈 | 용도 | 우선순위 |
|------|------|----------|
| `bluetape4k-cache-core` | Caffeine 기반 로컬 캐시 추상화 | **HIGH** |
| `bluetape4k-exposed-jdbc-caffeine` | Exposed 쿼리 결과 Caffeine 캐싱 | **HIGH** |
| `bluetape4k-jackson3` | Jackson 3 직렬화 유틸 | MEDIUM |
| `bluetape4k-http` | HTTP 클라이언트 확장 (테스트 시) | LOW |
| `bluetape4k-virtualthread-jdk25` | Virtual Thread 지원 | LOW |

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

### 할 일

- [ ] Capacitor 프로젝트 초기화 (`@capacitor/core`, `@capacitor/ios`, `@capacitor/android`)
- [ ] iOS Safari WebView + Android WebView 통합 테스트
- [ ] PWA 지원 강화 (`@angular/pwa`, Service Worker, 오프라인 캐싱)
- [ ] 모바일 UX 최적화 (뷰포트, 터치 제스처, Safe Area, 키보드 처리)
- [ ] Lazy loading / code splitting 최적화 (라우트별 번들 분리)
- [ ] 네이티브 ↔ WebView 통신 계층 설계 (Deep link, postMessage)

---

## 3. 프론트엔드 API 커버리지 ✅

18개 백엔드 엔드포인트 전체 프론트엔드 연결 완료:

| Controller | 엔드포인트 | 구현 | 미구현 |
|---|---|---|---|
| AppointmentController | 5 | 5 | 0 |
| SlotController | 1 | 1 | 0 |
| RescheduleController | 4 | 4 | 0 |
| EquipmentUnavailabilityController | 8 | 8 | 0 |

- [x] `RescheduleService` — 4개 엔드포인트 화면 연결
- [x] `EquipmentUnavailabilityService` — 8개 엔드포인트 화면 연결
- [x] Reschedule 관리 페이지 컴포넌트 작성
- [x] Equipment Unavailability 관리 페이지 컴포넌트 작성

---

## 4. 백엔드 API 확장 ✅

- [x] `ClinicController` — 클리닉 조회 API (GET /api/clinics, /{id}, /{id}/operating-hours, /{id}/break-times)
- [x] `DoctorController` — 의사 조회 API (GET /api/clinics/{clinicId}/doctors, /doctors/{id}, /{id}/schedules, /{id}/absences)
- [x] `TreatmentTypeController` — 진료 유형 조회 API (GET /api/clinics/{clinicId}/treatment-types, /treatment-types/{id})
- [x] `EquipmentController` — 장비 조회 API (GET /api/clinics/{clinicId}/equipments, /equipments/{id})
- [x] `EquipmentRepository` — 장비 전용 리포지토리 생성

---

## 5. 문서화 (LOW-MEDIUM)

### 4.1 Living Documentation (미구현 부분)

`docs/requirements/` 디렉토리 구조는 존재하나 내용 보강 필요:

- [ ] `docs/requirements/README.md` — 요구사항 인덱스 + 구현 상태표
- [ ] `docs/requirements/architecture.md` — ADR 스타일 설계 결정 기록
- [ ] `docs/requirements/domain-model.md` — 엔티티/상태머신/관계 다이어그램
- [ ] `docs/requirements/solver.md` — Timefold 제약 조건 설명
- [ ] `docs/requirements/notification.md` — 알림 모듈 아키텍처
- [ ] `docs/requirements/frontend.md` — Angular 프론트엔드 구조

### 4.2 모듈별 README

- [ ] `appointment-core/README.md`
- [ ] `appointment-event/README.md`
- [ ] `appointment-solver/README.md`
- [ ] `appointment-notification/README.md`
- [ ] `appointment-api/README.md`

---

## 6. 인프라 & DevOps (LOW)

- ~~`docker-compose.yml`~~ — 불필요. `bluetape4k-testcontainers` (`PostgreSQLServer`, `MySQL8Server`, `RedisServer`) + `@Profile` 전략으로 대체. 프로파일별 원하는 서버 기동 및 커넥션 설정.
- [x] GitHub Actions CI — 모듈별 병렬 테스트 + gitleaks + Detekt + JaCoCo 커버리지
- [ ] Flyway 마이그레이션 검증 자동화 — H2, PostgreSQL, MySQL8 각각 empty DB → migrate 테스트 (`@Profile` + Testcontainers)
- [ ] CI 멀티 DB 테스트 매트릭스 — H2, PostgreSQL, MySQL8 별 빌드+테스트 (`@Profile` + Testcontainers)
- [ ] CI 프론트엔드 빌드 추가 — `npm ci && npm run build` (Angular 21 프로덕션 빌드 검증)

---

## 7. 코드 품질 (LOW)

- [ ] Solver 벤치마크 — `BenchmarkTest.kt` 결과 기준선(baseline) 정의
- [ ] Notification 모듈 — 실제 SMS/Email 채널 구현체 (현재 `DummyNotificationChannel`만 존재)
- [ ] `ConcurrencyResolver` — 동시 예약 충돌 시나리오 통합 테스트

---

## 8. 의존성 관리 (LOW)

- [ ] bluetape4k BOM 버전 최신화 모니터링 (현재 1.6.2)
- [ ] Jackson 3 마이그레이션 완료 여부 확인
- [ ] Spring Boot 4 GA 업데이트 추적

---

## 완료 항목 (참고)

- ✅ v0.1.0 — 도메인 모델, 상태머신, Solver, Notification, API 기본 구현
- ✅ v0.2.0 — 장비 사용불가 스케줄, @Profile 환경 분리, ClinicTimezoneService
- ✅ CHANGELOG.md — Keep a Changelog 형식 유지 중
- ✅ GitHub Actions CI workflow 구성
- ✅ API Controller 테스트 전면 전환 — MockMvc → Spring Boot 4 RestClient (2026-04-19)

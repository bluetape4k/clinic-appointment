# 관리자 대시보드 집계 API + Angular 차트 UI 구현 계획

> 연관 스펙: `docs/superpowers/specs/2026-05-19-dashboard-stats-design.md`
> Issue [#44](https://github.com/bluetape4k/clinic-appointment/issues/44) (백엔드), [#45](https://github.com/bluetape4k/clinic-appointment/issues/45) (프론트엔드)
> 작성일: 2026-05-19
> Worktree: `.worktrees/feat/issue-44-45-dashboard/`

---

## 개요

스펙 §9 구현 순서를 4단계로 재구성한다.

- **Phase 1 — 백엔드**: Repository → DTO → Service → Controller → SecurityConfig → ServiceConfig. 각 컴포넌트는 RED→GREEN TDD로 구현하며 단위 테스트가 같은 태스크에 포함된다.
- **Phase 2 — 프론트엔드**: Models → Service → 차트 3종 → 컨테이너 → ManagementDashboard 통합. 각 컴포넌트는 vitest 단위 테스트를 같은 태스크에 포함한다.
- **Phase 3 — 교차/회귀/통합 테스트**: 단일 컴포넌트에 귀속되지 않는 SecurityConfig 매처 순서, 컨트롤러 200/401/403/400 계약, 회귀 테스트.
- **Phase 4 — 문서 + PR 준비**: README(한/영), CHANGELOG, KDoc, code-reviewer, PR.

코딩 컨벤션 준수 사항:

- 모든 Exposed 호출은 `transaction {}` 내부.
- DTO/DoctorStatusCount는 `Serializable` + `serialVersionUID = 1L`.
- 동일 타입(`Long`, `Long`) 파라미터 위치 혼동 방지 위해 `DoctorStatusCount` named data class 사용 (Triple 금지 — CLAUDE.md).
- bluetape4k `requireXxx` / `requireNotNull` 사용.
- Kotlin `@Configuration(proxyBeanMethods = false)` 항상 명시.
- `runCatching` 금지 (suspend 호출 영역). 단순 IAE는 `GlobalExceptionHandler`로 위임.

> **[F1] 커밋 경계 주의**: 각 태스크의 RED/GREEN 단계는 하나의 커밋 단위이다. RED(컴파일 실패/테스트 실패)를 독립 커밋으로 push하지 말 것 — RED는 동일 태스크 내 구현 시작 전 확인 단계이며, GREEN(PASS)에서만 커밋한다. Phase 단위 중간 커밋(예: T2 완료 후, T5 완료 후, T10 완료 후, T18 완료 후)을 권장한다.

---

## Phase 1 — 백엔드

### T1: AppointmentStatsRepository H2 단위 테스트 (RED)
- **complexity**: medium
- **files**:
  - `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentStatsRepositoryTest.kt` (신규)
  - `appointment-core/src/test/resources/junit-platform.properties` (없으면 신규)
  - `appointment-core/src/test/resources/logback-test.xml` (없으면 신규)
- **dependencies**: 없음
- **작업**:
  - **[F2] AbstractExposedTest 패턴** — `EquipmentUnavailabilityRepositoryTest.kt`를 canonical 참조로 사용.
    - `AppointmentStatsRepositoryTest : AbstractExposedTest()` 상속.
    - 각 테스트는 `withTables(testDB, Clinics, Doctors, Appointments) { ... }` 블록 안에서 실행.
    - `@ParameterizedTest @MethodSource(ENABLE_DIALECTS_METHOD)` per test.
    - **`@BeforeEach SchemaUtils + deleteAll` 패턴 사용 금지** (CLAUDE.md Module Gotchas와 달리 appointment-core 실제 패턴은 AbstractExposedTest이다).
  - 시나리오 (실패 상태):
    - `countByDateAndStatus` — 날짜 경계/상태 필터/빈 결과/clinicId 분리 검증.
    - `countByDoctorAndStatus` — 의사 3명 × 상태 다종 시드로 모든 (doctorId, status, count) 조합 반환 검증; **SQL LIMIT/orderBy 미포함을 결과 행 수로 단언**.
    - (**[F3]** `countCancellationsByDate` 테스트 제외 — 해당 메서드는 리포지토리에 존재하지 않는다; 취소 집계는 Service가 `countByDateAndStatus`로 처리).
  - 백틱 한국어 테스트명, AAA 패턴.
- **DoD**: 컴파일 후 메서드 부재로 실패 (RED 확인).

---

### T2: AppointmentStatsRepository 구현 (GREEN)
- **complexity**: high
- **files**:
  - `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentStatsRepository.kt` (신규)
- **dependencies**: T1
- **작업**:
  - 패키지 `io.bluetape4k.clinic.appointment.repository`, `companion object : KLogging()`.
  - `DoctorStatusCount(doctorId: Long, status: AppointmentState, count: Long) : Serializable` — 동일 파일 선언, KDoc에 Triple 금지 사유 명시 (동일 타입 Long 두 개 위치 혼동 위험, CLAUDE.md 규칙).
  - **[F3] 두 메서드만 구현** (`countCancellationsByDate` 삭제 — dead code):
    - `countByDateAndStatus(clinicId, dateRange, statuses?) : List<Triple<LocalDate, AppointmentState, Long>>` — date/status는 다른 타입이므로 Triple 허용.
    - `countByDoctorAndStatus(clinicId, dateRange) : List<DoctorStatusCount>` — **SQL LIMIT/orderBy 없음** (Service가 Kotlin groupBy + sortedByDescending + take(limit)로 의사 단위 상위 N 선별; SQL LIMIT은 (doctorId, status) 행 수에 적용되어 의미가 다름 — KDoc 명시).
  - `countCancellationsByDate` 메서드를 별도로 추가하지 않는다. 취소 집계는 Service T5에서 `countByDateAndStatus(clinicId, dateRange, listOf(CANCELLED, NO_SHOW, RESCHEDULED, COMPLETED))` 호출로 처리한다.
  - Exposed 1.2 패턴: `val countExpr = Appointments.id.count()` alias 후 `row[countExpr]` 추출.
  - 모든 메서드 KDoc: "호출자는 transaction {} 내부에서 호출" 명시 + 영문 `## Behavior / Contract`.
- **DoD**: T1 PASS (GREEN); `./gradlew :appointment-core:test --tests AppointmentStatsRepositoryTest` PASS.

---

### T3: 응답 DTO 3종
- **complexity**: medium
- **files**:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/AppointmentStatsResponse.kt` (신규)
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/DoctorStatsResponse.kt` (신규)
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/CancellationStatsResponse.kt` (신규)
- **dependencies**: 없음
- **작업**:
  - 스펙 §3.2/§3.3/§3.4 데이터 모델 그대로 (`AppointmentStatsResponse`+`DailyAppointmentBucket`, `DoctorStatsResponse`+`DoctorBucket`, `CancellationStatsResponse`+`DailyCancellationBucket`).
  - 모든 data class: `: Serializable` + `companion object { private const val serialVersionUID = 1L }`.
  - 영문 KDoc + `@Schema` Swagger 어노테이션.
  - `totals`/`countsByStatus`는 `Map<String, Long>` — 키는 `AppointmentState.name`.
- **DoD**: `./gradlew :appointment-api:compileKotlin` PASS.

---

### T4: DashboardStatsService 통합 테스트 (RED)
- **complexity**: medium
- **files**:
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DashboardStatsServiceTest.kt` (신규)
- **dependencies**: T2, T3
- **작업**:
  - **[F4] 통합 테스트 접근법** — MockK + `transaction {}` 비호환 문제 해결을 위해 실제 H2 Repository 사용.
    - **[크로스 모듈 제약]** `appointment-core/src/test`의 `AbstractExposedTest`는 `appointment-core` 에 `java-test-fixtures` 플러그인이 없으므로 `appointment-api/src/test`에서 직접 상속 불가.
    - `DashboardStatsServiceTest`는 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)` + `@ActiveProfiles("test")`로 Spring 컨텍스트 (H2 + Flyway + Exposed 자동 설정)를 로드한다.
    - `@Autowired` 로 `DashboardStatsService` 주입; `@BeforeEach`에서 `transaction { SchemaUtils.create(Clinics, Doctors, Appointments); Appointments.deleteAll(); Doctors.deleteAll(); Clinics.deleteAll() }` 패턴 (기존 `ClinicControllerTest` 패턴과 동일).
    - Service 검증 로직만 MockK로 분리할 수 없으므로 Service + Repository 통합 단위 테스트로 작성 (Repository 동작은 T1/T2에서 이미 보장).
  - 시나리오:
    - `getAppointmentStats`: 정상 — bucket 조립/totals 계산 정확성.
    - **[F5] 빈 clinic**: `clinicId=999` (데이터 없음) → `data.buckets.isEmpty()`, `data.totals` 모두 0L, HTTP 200 (에러 아님).
    - `from > to` → `IllegalArgumentException("from must be on or before to")`.
    - 367일 범위 → `IllegalArgumentException("period exceeds 366 days")`.
    - `clinicId <= 0` → `IllegalArgumentException` (bluetape4k `requirePositive`).
    - `statuses = ["UNKNOWN"]` → `AppointmentState.fromName` IAE 전파 (Service 별도 try-catch 없음 확인).
    - 기본 기간(`from/to` null) → today-29..today 적용.
    - `getDoctorStats`: 의사 4명 × 상태 5종 — `completionRate` 분모 0 안전; **결과가 `totalAppointments` 내림차순**임을 단언 (Service sortedByDescending 책임 검증); `limit=0`/`limit=101` → IAE.
    - `getCancellationStats`: rate 계산 정확성, 분모 0 안전.
  - `assertFailsWith<IllegalArgumentException> { ... }` 패턴.
- **DoD**: Service 미존재로 컴파일 실패 (RED).

---

### T5: DashboardStatsService 구현 (GREEN)
- **complexity**: high
- **files**:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/DashboardStatsService.kt` (신규)
- **dependencies**: T4
- **작업**:
  - `@Service` 미부착 — ServiceConfig 등록만 사용 (T9에서 처리).
  - 생성자: `private val statsRepository: AppointmentStatsRepository`. `companion object : KLogging()`.
  - 상수: `DEFAULT_DAYS = 30`, `MAX_PERIOD_DAYS = 366L`, `ALLOWED_LIMIT = 1..100`.
  - 세 메서드 (`getAppointmentStats`, `getDoctorStats`, `getCancellationStats`):
    - 기본값 적용 (null → today, today-29).
    - `clinicId.requirePositive("clinicId")`, `require(!from.isAfter(to)) { "..." }`, `require(period <= MAX_PERIOD_DAYS) { "..." }`.
    - `getDoctorStats`만 `require(limit in ALLOWED_LIMIT) { "..." }`.
    - `statuses?.map { AppointmentState.fromName(it) }` — try-catch 없음 (GlobalExceptionHandler 위임).
    - `transaction { statsRepository.countByXxx(...) }` → Kotlin 변환 로직:
      - appointments: groupBy date → DailyAppointmentBucket + totals 누계.
      - doctors: `groupBy { it.doctorId }` → DoctorBucket (completed/cancelled/noShow 추출, total=sumOf, rate=completed/(c+ca+no), 분모0→0.0) → `sortedByDescending { it.totalAppointments }.take(limit)`.
      - cancellations: CANCELLED/NO_SHOW/RESCHEDULED/COMPLETED 포함 단일 `countByDateAndStatus` 호출 → 집계.
  - `runCatching` 금지.
- **DoD**: T4 PASS; `./gradlew :appointment-api:test --tests DashboardStatsServiceTest` PASS.

---

### T6: DashboardStatsController 단위 테스트 (RED)
- **complexity**: medium
- **files**:
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/DashboardStatsControllerTest.kt` (신규)
- **dependencies**: T3, T5, T8
- **작업**:
  - `@WebMvcTest(DashboardStatsController::class)` + `@MockkBean DashboardStatsService` + **`@Import(NoOpSecurityConfig::class)`** 필수.
    - `@WebMvcTest`는 `@Configuration` 클래스를 자동 스캔하지 않으므로 `@ActiveProfiles("test")` 단독으로는 `NoOpSecurityConfig` 로드 불가.
    - `@Import(NoOpSecurityConfig::class)` 로 명시 로드 → 모든 요청 permitAll → JWT 없이 MockMvc 테스트 가능.
  - 시나리오:
    - 200 — `/api/admin/stats/appointments?clinicId=1` → `success: true`, `data.totals` 키 존재.
    - **[F5] 200 — `?clinicId=999` (데이터 없음)** → `success: true`, `data.buckets` 빈 배열 (에러 응답 아님).
    - 200 — `?statuses=CONFIRMED&statuses=CANCELLED` 반복 파라미터 → Service에 `listOf("CONFIRMED","CANCELLED")` 전달 verify.
    - 200 — `/doctors?clinicId=1&limit=5` → DoctorStatsResponse JSON.
    - 200 — `/cancellations?clinicId=1` → `cancellationRate` double 직렬화.
    - **[F6] `?from=not-a-date → 400` 케이스는 이 태스크에서 제외** — T8 RED 단계에서 검증 (T8이 T6보다 먼저 작성되어야 해당 케이스가 통과됨).
    - 400 — Service IAE(`from > to`) → `ApiResponse.error("from must be on or before to")`.
    - 기본 기간 미지정 시 Service에 null 전달 verify.
- **DoD**: Controller 미존재로 컴파일 실패 (RED).

---

### T7: DashboardStatsController 구현 (GREEN)
- **complexity**: medium
- **files**:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/DashboardStatsController.kt` (신규)
- **dependencies**: T6
- **작업**:
  - `@RestController`, `@RequestMapping("/api/admin/stats")`, `@Tag(name = "Dashboard Stats")`.
  - 생성자: `private val dashboardStatsService: DashboardStatsService`.
  - 세 GET 엔드포인트:
    - `/appointments`: `clinicId: Long`, `@DateTimeFormat(iso=DATE) from: LocalDate?`, `to: LocalDate?`, `@RequestParam(required=false) statuses: List<String>?` — 반복 파라미터 자동 바인딩.
    - `/doctors`: + `@RequestParam(defaultValue="20") limit: Int`.
    - `/cancellations`: clinicId + from/to.
  - 모두 `ResponseEntity<ApiResponse<XxxResponse>>` — `ApiResponse.ok(...)`.
  - `@Operation` + `@ApiResponses(200/400/401/403)`.
  - `companion object : KLogging()` + debug 로그.
- **DoD**: T6 PASS (T8과 통합 후 400 케이스 포함 GREEN); IDE 진단 0.

---

### T8: GlobalExceptionHandler MethodArgumentTypeMismatchException 핸들러 추가
- **complexity**: low
- **files**:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt` (수정)
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandlerTypeMismatchTest.kt` (신규)
- **dependencies**: 없음
- **작업**:
  - **[F6] RED 단계**: `GlobalExceptionHandlerTypeMismatchTest` 작성 — `@WebMvcTest(DashboardStatsController::class)` + `@MockkBean DashboardStatsService`.
    - `GET /api/admin/stats/appointments?clinicId=1&from=not-a-date` → 400 + `{"success":false,"error":"Invalid parameter: from"}` 단언.
    - 컴파일은 되지만 핸들러 미존재로 실패 (RED 확인).
  - **GREEN 단계**: `@ExceptionHandler(MethodArgumentTypeMismatchException::class)` 추가 → 400 + `ApiResponse.error("Invalid parameter: ${ex.name}")`.
  - `log.warn(ex) { "Type mismatch: name=${ex.name}, requiredType=${ex.requiredType?.simpleName}" }`.
- **DoD**: `GlobalExceptionHandlerTypeMismatchTest` PASS (GREEN); T6의 400 케이스도 이 시점 이후 PASS.

---

### T9: ServiceConfig Bean 등록
- **complexity**: low
- **files**:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt` (수정)
- **dependencies**: T2, T5
- **작업**:
  - `@Bean fun appointmentStatsRepository(): AppointmentStatsRepository = AppointmentStatsRepository()`.
  - `@Bean fun dashboardStatsService(appointmentStatsRepository: AppointmentStatsRepository): DashboardStatsService = DashboardStatsService(appointmentStatsRepository)`.
- **DoD**: `@SpringBootTest` 컨텍스트 로드 PASS.

---

### T10: SecurityConfig 매처 추가 + 인증/인가 핸들러
- **complexity**: high
- **files**:
  - `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt` (수정)
- **dependencies**: T7
- **작업**:
  - **[F7] 의존성 수정**: T9(ServiceConfig)는 이 태스크의 전제 조건이 아님 — T7(Controller 구현)이 완료되면 SecurityConfig 매처 추가가 가능하다. T9는 별도로 진행.
  - **[F7] ObjectMapper 주입 방식**: 기존 `SecurityConfig`는 zero-arg 생성자 + `@Bean` 메서드 파라미터 패턴을 사용한다. 따라서 생성자에 ObjectMapper를 추가하지 말고, `filterChain(@Bean 메서드)` 파라미터로 `objectMapper: ObjectMapper` 를 받는다:
    ```kotlin
    @Bean
    fun filterChain(http: HttpSecurity, objectMapper: ObjectMapper): SecurityFilterChain { ... }
    ```
  - `authorizeHttpRequests` 블록: 기존 `permitAll` 다음, `GET /api/**` 매처 **앞**에 `.requestMatchers("/api/admin/**").hasRole(SchedulingRole.ADMIN)` 추가. 순서 주석 필수.
  - `.exceptionHandling { ... }` 블록:
    - `authenticationEntryPoint` → 401 + `objectMapper.writeValueAsString(ApiResponse.error<Nothing>("Unauthorized"))`.
    - `accessDeniedHandler` → 403 + `objectMapper.writeValueAsString(ApiResponse.error<Nothing>("Forbidden"))`.
  - `NoOpSecurityConfig` 변경 없음.
- **DoD**: 컴파일 PASS; T19 통합 테스트로 검증.

---

## Phase 2 — 프론트엔드

### T11: dashboard-stats 모델 타입 + export
- **complexity**: low
- **files**:
  - `frontend/appointment-frontend/src/app/core/models/dashboard-stats.model.ts` (신규)
  - `frontend/appointment-frontend/src/app/core/models/index.ts` (수정)
- **dependencies**: T3
- **작업**:
  - 백엔드 DTO와 정확히 일치하는 TypeScript 인터페이스:
    - `DailyAppointmentBucket`, `AppointmentStatsResponse`, `DoctorBucket`, `DoctorStatsResponse`, `DailyCancellationBucket`, `CancellationStatsResponse`.
  - `models/index.ts`에 `export * from './dashboard-stats.model';`.
- **DoD**: `npx tsc --noEmit` 타입 오류 0.

---

### T12: chart.js 의존성 추가
- **complexity**: low
- **files**:
  - `frontend/appointment-frontend/package.json` (수정)
- **dependencies**: 없음
- **작업**:
  - `"chart.js": "^4.5.0"` 추가 후 `npm install`.
  - `npm view chart.js peerDependencies` 확인.
  - `ng build` 호환 검증.
- **DoD**: `ng build` PASS; 0 peer-dep conflicts.

---

### T13: DashboardStatsService vitest + 구현 + export
- **complexity**: medium
- **files**:
  - `frontend/appointment-frontend/src/app/core/services/dashboard-stats.service.spec.ts` (신규)
  - `frontend/appointment-frontend/src/app/core/services/dashboard-stats.service.ts` (신규)
  - `frontend/appointment-frontend/src/app/core/services/index.ts` (수정)
- **dependencies**: T11
- **작업**:
  - **RED**: `provideHttpClient` + `provideHttpClientTesting` + `HttpTestingController`.
    - `loadAppointmentStats` — URL, params, **`r.params.getAll('statuses')` 반복 파라미터 검증**.
    - `loadDoctorStats`, `loadCancellationStats` — params 검증 + signal 갱신.
    - 403 → silent fallback (signal `null`), throw 없음.
  - **GREEN**: `@Injectable({ providedIn: 'root' })`, private signals (`_appointments`, `_doctors`, `_cancellations`, `loading`), 3개 async 메서드, 반복 파라미터 송신 (`params.append('statuses', s)` 반복).
  - `services/index.ts`에 export 추가.
- **DoD**: vitest PASS.

---

### T14: AppointmentTrendChartComponent (vitest + 구현)
- **complexity**: high
- **files**:
  - `frontend/appointment-frontend/src/app/features/management/dashboard/charts/appointment-trend-chart.component.spec.ts` (신규)
  - `frontend/appointment-frontend/src/app/features/management/dashboard/charts/appointment-trend-chart.component.ts` (신규)
- **dependencies**: T12, T13
- **작업**:
  - **RED**: Chart.js 모킹 (§7.2 패턴):
    ```ts
    const destroyFn = vi.fn(); const updateFn = vi.fn();
    vi.mock('chart.js', () => ({ Chart: vi.fn().mockImplementation(() => ({ destroy: destroyFn, update: updateFn, data: { labels: [], datasets: [] } })), registerables: [] }));
    ```
    시나리오: 데이터 주입 → Chart 생성자 호출; 데이터 변경 → `update()` 호출; `ngOnDestroy()` → `destroyFn` 호출.
  - **[F9] 라이프사이클 방식 확정 — `ngOnInit` 사용**:
    - `afterNextRender`는 TestBed에서 동기적으로 실행되지 않아 `chart?.destroy()` 단언이 실패한다.
    - 대신 `ngOnInit`에서 차트를 초기화하고, `TestBed.flushEffects()` + `fixture.destroy()`로 `ngOnDestroy` 정리를 검증한다.
  - **GREEN**: standalone, `input.required<AppointmentStatsResponse | null>()`, `@ViewChild('canvas')` ElementRef, `ngOnInit() { initChart(); }`, `effect(() => updateChart())`, `ngOnDestroy: chart?.destroy()`.
  - Chart.js `type: 'bar'`, stacked x/y, 상태별 색상 const map.
- **DoD**: vitest PASS; `ng build` PASS.

---

### T15: DoctorWorkloadChartComponent (vitest + 구현)
- **complexity**: high
- **files**:
  - `frontend/appointment-frontend/src/app/features/management/dashboard/charts/doctor-workload-chart.component.spec.ts` (신규)
  - `frontend/appointment-frontend/src/app/features/management/dashboard/charts/doctor-workload-chart.component.ts` (신규)
- **dependencies**: T14
- **작업**:
  - **[F9]** T14와 동일하게 `ngOnInit` 기반 라이프사이클 패턴 적용 (`afterNextRender` 금지).
  - inputs: `data = input.required<DoctorStatsResponse | null>()`, `doctorNameLookup = input<(id: number) => string>()`.
  - Chart.js `type: 'bar'` + `options: { indexAxis: 'y' }` (수평 막대).
  - datasets: totalAppointments (회색) + completed (녹색).
- **DoD**: vitest PASS.

---

### T16: CancellationTrendChartComponent (vitest + 구현)
- **complexity**: high
- **files**:
  - `frontend/appointment-frontend/src/app/features/management/dashboard/charts/cancellation-trend-chart.component.spec.ts` (신규)
  - `frontend/appointment-frontend/src/app/features/management/dashboard/charts/cancellation-trend-chart.component.ts` (신규)
- **dependencies**: T14
- **작업**:
  - **[F9]** T14와 동일하게 `ngOnInit` 기반 라이프사이클 패턴 적용 (`afterNextRender` 금지).
  - Chart.js `type: 'line'` 멀티시리즈: cancelled(빨강), noShow(주황), rescheduled(파랑).
  - KPI 카드: `cancellationRate`, `noShowRate` — Material `<mat-card>`, `(rate * 100).toFixed(1) + '%'`.
- **DoD**: vitest PASS.

---

### T17: DashboardChartsComponent 컨테이너 (vitest + 구현)
- **complexity**: medium
- **files**:
  - `frontend/appointment-frontend/src/app/features/management/dashboard/dashboard-charts.component.spec.ts` (신규)
  - `frontend/appointment-frontend/src/app/features/management/dashboard/dashboard-charts.component.ts` (신규)
- **dependencies**: T13, T14, T15, T16
- **작업**:
  - **RED**: `authService.isAdmin()=false` → 차트 3개 미렌더링; `isAdmin()=true` → 자식 3개 렌더링; clinicId 변경 → `loadXxx` 호출.
  - **GREEN**: `inject(AuthService)`, `inject(DashboardStatsService)`, `inject(DoctorService)`. `effect(() => { if (authService.isAdmin()) loadAll(); })`. template: `@if (authService.isAdmin()) { <section class="charts-grid"> ... </section> }`.
- **DoD**: vitest PASS — ADMIN 분기 양방향 검증.

---

### T18: ManagementDashboardComponent 통합 + 회귀
- **complexity**: medium
- **files**:
  - `frontend/appointment-frontend/src/app/features/management/dashboard/management-dashboard.component.ts` (수정)
  - `frontend/appointment-frontend/src/app/features/management/dashboard/management-dashboard.component.spec.ts` (신규 또는 기존 확장)
- **dependencies**: T17
- **작업**:
  - `imports`에 `DashboardChartsComponent` 추가.
  - **`authService.isAdmin()` 직접 사용** — 로컬 `isAdmin` computed 재선언 금지 (DRY, 스펙 §2.4).
  - template: 기존 카드 섹션 아래에 `@if (authService.isAdmin()) { <h2>운영 통계</h2> <app-dashboard-charts /> }` 추가.
  - 기존 `todayCount`/`pendingCount`/`doctorCount` 카드 **그대로 유지**.
  - 회귀 테스트: 기존 3개 카드 유지; ADMIN 시 `app-dashboard-charts` 존재; 비-ADMIN 시 부재.
  - `doctorCount.set(0)` 기존 버그 — 본 PR 범위 밖, 회귀 테스트만.
- **DoD**: vitest PASS; `ng build` PASS.

---

## Phase 3 — 교차/통합 테스트

### T19: SecurityConfig 통합 테스트 — 매처 순서 + envelope
- **complexity**: high
- **files**:
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfigDashboardTest.kt` (신규)
- **dependencies**: T7, T10
- **작업**:
  - **[F8] 구체적 보안 우회 전략**:
    - `SecurityTestConfig.kt` 신규 파일 불필요 — `@ActiveProfiles("security-test")` 지정만으로 프로파일 전환 충분.
      - `"security-test"` 프로파일 → `NoOpSecurityConfig`(`@Profile("dev","test")`) 비활성; 실제 `SecurityConfig`(`@Profile("!dev & !test")`) 활성.
    - 테스트 클래스: `@SpringBootTest(webEnvironment=MOCK)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("security-test")`.
    - **JWT 토큰 생성**: 이미 존재하는 `TestJwtProvider` (`io.bluetape4k.clinic.appointment.api.security.TestJwtProvider`) 사용.
      - `TestJwtProvider.adminToken()` → ROLE_ADMIN 토큰
      - `TestJwtProvider.staffToken()` → ROLE_STAFF 토큰
      - `TestJwtProvider.doctorToken()` → ROLE_DOCTOR 토큰
      - `application-security-test.properties`에 `jwt.secret = ${TestJwtProvider.secret}`, `jwt.issuer = ${TestJwtProvider.issuer}` 설정 필요 (JwtSecurityProperties 바인딩용).
    - `MockMvc` 요청 헤더: `Authorization: Bearer <token>`.
  - 시나리오 (스펙 §5.2):
    - ADMIN 토큰 → 200.
    - STAFF 토큰 → **403** + `{"success":false,"data":null,"error":"Forbidden"}` JSON 단언.
    - DOCTOR 토큰 → 403.
    - 토큰 없음 → **401** + `{"success":false,"data":null,"error":"Unauthorized"}` JSON 단언.
    - **매처 순서 회귀**: STAFF가 `GET /api/admin/stats/appointments` 호출 시 200이 아닌 403 — `/api/admin/**`이 `GET /api/**`보다 앞에 있음 검증.
- **DoD**: `./gradlew :appointment-api:test --tests SecurityConfigDashboardTest` PASS.

---

### T20: Controller 계약 400 매트릭스
- **complexity**: medium
- **files**:
  - `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/DashboardStatsControllerContractTest.kt` (신규)
- **dependencies**: T7, T8
- **작업**:
  - `@WebMvcTest(DashboardStatsController::class)` + `@MockkBean DashboardStatsService` + `@Import(NoOpSecurityConfig::class)` 필수.
  - **[T6와 동일한 제약]** `@WebMvcTest`는 `@Configuration` 클래스를 자동 스캔하지 않으므로 `@ActiveProfiles("test")` 단독으로는 `NoOpSecurityConfig` 로드 불가. `@Import(NoOpSecurityConfig::class)`로 명시 로드 → 모든 요청 `permitAll()` → JWT 없이 MockMvc 400 매트릭스 테스트 가능.
  - 시나리오:
    - `?from=2026-99-99` → 400 (MethodArgumentTypeMismatchException → T8).
    - `?from=2026-05-01&to=2026-04-01` → 400 + "from must be on or before to".
    - 367일 범위 → 400.
    - `?clinicId=-1` → 400.
    - `?limit=0` / `?limit=101` (doctors) → 400.
    - `?statuses=UNKNOWN_STATE` → 400 + "Unknown appointment status: UNKNOWN_STATE".
    - `clinicId` 미지정 → 400.
    - **반복 파라미터**: `?statuses=CONFIRMED&statuses=CANCELLED` → Service에 `listOf("CONFIRMED","CANCELLED")` verify.
  - 모든 400 응답이 `ApiResponse.error(...)` JSON 형태임을 단언.
- **DoD**: PASS.

---

## Phase 4 — 문서 + PR 준비

### T21: KDoc·JSDoc 영문 검증 및 보강
- **complexity**: low
- **files**:
  - `appointment-core/src/main/kotlin/.../repository/AppointmentStatsRepository.kt` (KDoc 보강)
  - `appointment-core/src/main/kotlin/.../repository/DoctorStatusCount.kt` 또는 동일 파일 내 (KDoc)
  - `appointment-api/src/main/kotlin/.../dto/AppointmentStatsResponse.kt` (KDoc + @Schema)
  - `appointment-api/src/main/kotlin/.../dto/DoctorStatsResponse.kt` (KDoc + @Schema)
  - `appointment-api/src/main/kotlin/.../dto/CancellationStatsResponse.kt` (KDoc + @Schema)
  - `appointment-api/src/main/kotlin/.../service/DashboardStatsService.kt` (KDoc)
  - `appointment-api/src/main/kotlin/.../controller/DashboardStatsController.kt` (KDoc + @Operation)
  - `frontend/.../services/dashboard-stats.service.ts` (JSDoc)
  - `frontend/.../charts/appointment-trend-chart.component.ts` (JSDoc)
  - `frontend/.../charts/doctor-workload-chart.component.ts` (JSDoc)
  - `frontend/.../charts/cancellation-trend-chart.component.ts` (JSDoc)
  - `frontend/.../dashboard-charts.component.ts` (JSDoc)
- **dependencies**: T18, T20
- **작업**:
  - **[F11]** 모든 신규 public 클래스/함수: 영문 one-line summary + `## Behavior / Contract` 섹션.
  - Kotlin IDE 진단 단계:
    1. `./gradlew :appointment-core:compileKotlin :appointment-api:compileKotlin` — 에러 0 확인.
    2. `ide_diagnostics` (또는 `./gradlew :appointment-api:build`) — unresolved deprecation 0 확인.
    3. `ide_optimize_imports` 실행 후 재확인.
  - TypeScript: `npx tsc --noEmit` — 타입 오류 0 확인.
- **DoD**: 모든 신규 public symbol 영문 KDoc/JSDoc 완료; Kotlin IDE 에러 0; `tsc --noEmit` PASS.

---

### T22: README 업데이트 (한/영 동시)
- **complexity**: low
- **files**:
  - `README.md` (수정 — 영문)
  - `README.ko.md` (수정 — 한국어)
- **dependencies**: T18
- **작업**:
  - **[F12] 두 파일 명시적 작업**:
    - `README.md`: 영문 섹션 추가 — "Dashboard Stats API" 섹션에 3개 엔드포인트 + ADMIN 역할 요건 + Mermaid 아키텍처 다이어그램 + curl 예시.
    - `README.ko.md`: 동일 내용을 한국어로 추가 — "대시보드 통계 API" 섹션.
    - 두 파일의 **섹션 순서, 헤딩 레벨, 다이어그램** 구조가 정렬됨을 단언.
  - Mermaid 다이어그램: Angular `DashboardChartsComponent` → HTTP → Spring Controller → Service → Repository → DB 흐름.
  - curl 예시 (`Authorization: Bearer <ADMIN_TOKEN>` 포함).
  - **구조 정렬 DoD**: `README.md`와 `README.ko.md`의 `##` 섹션 헤딩 목록이 1:1 대응 확인.
- **DoD**: 두 파일 모두 갱신; 섹션 구조 정렬 완료.

---

### T23: CHANGELOG + PR 본문 초안
- **complexity**: low
- **files**:
  - `CHANGELOG.md` (수정)
- **dependencies**: T22
- **작업**:
  - CHANGELOG 항목 (영문):
    ```
    ## [Unreleased]
    ### Added
    - Dashboard stats REST API: GET /api/admin/stats/{appointments,doctors,cancellations} (ADMIN only).
    - Angular dashboard charts: appointment trend (stacked bar), doctor workload (horizontal bar), cancellation trend (line + KPI).
    - MethodArgumentTypeMismatchException → 400 handler in GlobalExceptionHandler.
    - SecurityConfig /api/admin/** matcher + JSON ApiResponse envelope for 401/403.
    ```
  - PR 본문 초안: Summary + scope + ADR 요약 + test plan + `Closes #44 Closes #45`.
- **DoD**: CHANGELOG 갱신.

---

### T24: 전체 테스트 실행 + code-reviewer
- **complexity**: medium
- **files**: 없음 (검증 태스크)
- **dependencies**: T19, T20, T21, T22, T23
- **작업**:
  - `./gradlew :appointment-core:test --tests "*AppointmentStats*"` — PASS + 시간.
  - `./gradlew :appointment-api:test --tests "*Dashboard*" --tests "*GlobalException*" --tests "*SecurityConfigDashboard*"` — PASS + 시간.
  - `cd frontend/appointment-frontend && npm run test -- --run` 관련 스펙들 — PASS + count.
  - `ng build` — PASS.
  - `./gradlew :appointment-core:build :appointment-api:build` 회귀 안전망.
  - `oh-my-claudecode:code-reviewer` + `oh-my-claudecode:security-reviewer` — HIGH/CRITICAL 0.
- **DoD**: 모든 PASS; code-reviewer/security-reviewer HIGH+ 0.

---

### T25: docs/lessons 작성 + 커밋 (PR 생성 전 필수)
- **complexity**: low
- **files**:
  - `docs/lessons/2026-05-19-dashboard-stats.md` (신규)
- **dependencies**: T24
- **작업**:
  - **[F10] bluetape4k-workflow Step 7 필수 단계** — PR 생성 전 lessons 커밋.
  - 문서 내용:
    - 설계 결정 근거: `countCancellationsByDate` 삭제(dead code) + `countByDateAndStatus` 단일화 이유.
    - MockK + Exposed `transaction {}` 비호환 교훈 — Service 테스트를 `AbstractExposedTest` 통합 테스트로 전환한 이유.
    - 보안 프로파일 하네스 — `@ActiveProfiles("security-test")`로 `NoOpSecurityConfig` 우회 + 실제 `SecurityConfig` 활성화 패턴.
    - `afterNextRender` vs `ngOnInit` — TestBed 동기 실행 불가로 `ngOnInit` 선택.
    - SQL LIMIT 의미 차이 — `(doctorId, status)` 행 기준 LIMIT vs 의사 단위 상위 N; Kotlin 집계로 해결.
  - `git add docs/lessons/2026-05-19-dashboard-stats.md && git commit -m "docs: add lessons from dashboard stats implementation"`.
- **DoD**: lessons 파일 커밋됨; `git log --oneline -1` 확인.

---

### T26: PR 생성 (develop 대상)
- **complexity**: low
- **files**: 없음
- **dependencies**: T25
- **작업**:
  - 브랜치 `feat/issue-44-45-dashboard` → develop.
  - PR 제목: `feat: dashboard stats API + Angular charts (closes #44, closes #45)`.
  - PR 본문: T24 테스트 결과 + 보안 검증 + `Closes #44 Closes #45`.
- **DoD**: PR open; CI 통과.

---

## 의존성 그래프

```
T1 → T2 → T4 → T5 → T6 → T7
          T3 ────────────→ T6
          T8 ──────────────→ T6 (400 case RED doD)
T2, T5 → T9
T7 → T10                         [F7: T10 depends on T7, not T9]
T7, T10 → T19
T7, T8 → T20

T3 → T11 → T13
T12 → T14, T15, T16
T13, T14, T15, T16 → T17 → T18

T18 → T22 → T23
T19, T20, T21, T22, T23 → T24 → T25 → T26
T18, T20 → T21
```

---

## 검증 매트릭스

- [ ] T1–T2: AppointmentStatsRepository AbstractExposedTest + withTables 패턴 PASS (SQL LIMIT 부재 포함); countCancellationsByDate 없음 확인
- [ ] T3: DTO 3종 Serializable + serialVersionUID
- [ ] T4–T5: DashboardStatsService 통합 테스트 PASS (`@SpringBootTest(webEnvironment=NONE)` + `@ActiveProfiles("test")` + Spring 관리 H2); 빈 clinic 200 확인; sortedByDescending 검증 포함
- [ ] T6–T7: DashboardStatsController MockMvc PASS; 빈 clinic 200 확인; 반복 파라미터 동작 확인
- [ ] T8: GlobalExceptionHandlerTypeMismatchTest PASS; `?from=not-a-date → 400` + JSON envelope 확인
- [ ] T9: ServiceConfig Bean 등록, 컨텍스트 로드 PASS
- [ ] T10: SecurityConfig 매처 + ObjectMapper @Bean 메서드 파라미터 패턴; T7 의존 확인
- [ ] T11–T13: 프론트엔드 모델/서비스; 반복 파라미터 송신 확인
- [ ] T12: chart.js ^4.5.0, ng build PASS
- [ ] T14–T16: 차트 3종; **ngOnInit** 기반 라이프사이클; ngOnDestroy.destroy() 검증
- [ ] T17: DashboardCharts, authService.isAdmin() 분기 양방향
- [ ] T18: ManagementDashboard 회귀 + 로컬 isAdmin 재선언 없음
- [ ] T19: Security 통합 — `@ActiveProfiles("security-test")` 하네스; `TestJwtProvider` 재사용; `application-security-test.properties` jwt 설정; 매처 순서 + 401/403 envelope
- [ ] T20: Controller 400 매트릭스 + JSON envelope
- [ ] T21: 모든 신규 public API 영문 KDoc; Kotlin 컴파일 에러 0; tsc --noEmit PASS
- [ ] T22: README.md + README.ko.md 동시 갱신; 섹션 구조 1:1 정렬
- [ ] T23: CHANGELOG 영문
- [ ] T24: 모든 모듈 PASS, code-reviewer HIGH+ 0
- [ ] T25: docs/lessons/2026-05-19-dashboard-stats.md 커밋 완료
- [ ] T26: develop 대상 PR open, CI 통과

---

## 참조

- 스펙: `docs/superpowers/specs/2026-05-19-dashboard-stats-design.md`
- 기존 Repository: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
- 기존 Service: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt`
- 기존 Controller: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentController.kt`
- 기존 SecurityConfig: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- 기존 GlobalExceptionHandler: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`
- 기존 ServiceConfig: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- 기존 AuthService: `frontend/appointment-frontend/src/app/core/services/auth.service.ts`
- 기존 ManagementDashboard: `frontend/appointment-frontend/src/app/features/management/dashboard/management-dashboard.component.ts`

# 관리자 대시보드 집계 API + Angular 차트 UI 설계 명세

> Issue [#44](https://github.com/bluetape4k/clinic-appointment/issues/44) (백엔드 집계 API)
> Issue [#45](https://github.com/bluetape4k/clinic-appointment/issues/45) (Angular 차트 UI)
> 작성일: 2026-05-19
> 상태: 초안 (Step 1-S — Spec, 구현 전 critic 리뷰 대상)

---

## 1. 개요

### 1.1 목적

관리자(ADMIN)가 병원의 예약 운영 현황을 한 화면에서 파악할 수 있도록
**예약 집계 REST API**(`/api/admin/stats/**`)와 **Angular 대시보드 차트 UI**를 제공한다.

핵심 가치:

- 운영 KPI(예약 수, 의사 가동률, 취소 패턴)를 시각화하여 의사결정 가속화
- 기존 인덱스(`idx_appointments_clinic_date_status`, `idx_appointments_doctor_date`,
  `idx_appointments_date_status`)를 활용해 추가 인덱스 도입 없이 집계 성능 보장
- ADMIN 전용 보호 — 민감 운영 데이터를 일반 STAFF/DOCTOR 시야에서 격리

### 1.2 범위

| 포함 | 제외 |
|------|------|
| `GET /api/admin/stats/appointments` (일자별·상태별 집계) | 환자(PATIENT) 통계, 매출 통계 |
| `GET /api/admin/stats/doctors` (의사별 예약 수·완료율) | 의사 KPI 평가 시스템 |
| `GET /api/admin/stats/cancellations` (취소 추세·사유 집계) | 취소 사유 텍스트 마이닝 |
| `ManagementDashboardComponent` 차트 영역 신규 추가 | 차트 PDF/이미지 익스포트 |
| `AppointmentStatsRepository` (appointment-core) | DB materialized view, 캐싱 도입 |
| `DashboardStatsService` + `DashboardStatsController` (appointment-api) | OLAP, 시간대(timezone) 변환 |
| SecurityConfig `/api/admin/**` 매처 추가 | `@EnableMethodSecurity` 전환 |
| 백엔드 JUnit 5 + MockK + H2 통합 테스트 | Gatling 부하 테스트(추후 별도 이슈) |
| 프론트엔드 vitest 단위 테스트 | E2E (Playwright) |

### 1.3 비목표

- 실시간 스트리밍 통계(SSE/WebSocket) — 추후 별도 이슈
- 시간대(timezone) 변환 — 모든 집계는 병원 로컬 `LocalDate` 기준(naive)
- 데이터 영구 보존(웨어하우징) — 운영 DB 직접 집계만

---

## 2. 아키텍처 결정 사항 (ADR)

### 2.1 백엔드 레이어 — 결정 C: `appointment-core`에 `AppointmentStatsRepository` 신규 추가

리서치에서 제시된 옵션 1/2가 아닌 **제3의 선택**을 채택한다.

| 후보 | 채택 여부 | 이유 |
|------|----------|------|
| 옵션 1: `AppointmentRepository` 확장 | 불가 | 단일 책임 위반 — Repository가 비대화, 집계와 CRUD가 혼재 |
| 옵션 2: `appointment-api`에 `StatsRepository` 신규 | 불가 | 모듈 경계 위반 — `Appointments` 테이블은 `appointment-core`가 전담, API 계층은 어떤 테이블에도 직접 접근하지 않음 |
| **옵션 3: `appointment-core/repository/AppointmentStatsRepository` 신규 (채택)** | **채택** | • `Appointments` 접근은 모두 core에서 발생 (`AppointmentRepository.countOverlapping` 등 선례 일치)<br>• 집계 쿼리는 도메인 로직, API 관심사 아님<br>• `AppointmentRepository` 비대화 방지<br>• transaction 경계는 `appointment-api` 서비스 계층이 동일하게 유지 |

bluetape4k 패턴 정합: 핵심 도메인 테이블의 read는 core repository, write/orchestration은 api service.

### 2.2 보안 경계 — 결정 B: 경로 기반 매처 (`/api/admin/**`)

| 후보 | 채택 여부 | 이유 |
|------|----------|------|
| 옵션 1: `/api/admin/**` 경로 매처 | **채택** | • 현재 `SecurityConfig`가 모두 path-based로 통일됨<br>• `@EnableMethodSecurity` 미활성화 → 옵션 2는 추가 설정 필요<br>• 단일 위치에서 권한 정책 가시성 확보 |
| 옵션 2: `@PreAuthorize` | 불가 | 현재 코드베이스에 메서드 시큐리티 활성화 없음, 일관성 깨짐 |

**매처 순서(중요)**: Spring Security는 first-match-wins. 새 매처는 **반드시** 기존 `GET /api/**` 규칙 **앞**에 위치.

```
1. /swagger-ui/**, /v3/api-docs/**, /actuator/**  → permitAll
2. /api/admin/**                                  → hasRole(ADMIN)   [신규, GET보다 앞]
3. GET /api/**                                    → authenticated()
4. POST/PATCH/DELETE /api/**                      → hasAnyRole(ADMIN, STAFF)
5. anyRequest()                                   → authenticated()
```

### 2.3 차트 라이브러리 — 결정 C: Chart.js 직접 통합 (no Angular wrapper)

Angular 21.2.10은 **bleeding edge**이며 `ng2-charts`/`ngx-echarts`의 Angular 21 peer-dep 호환은 transcript 시점에서 검증 불가.

| 후보 | 채택 여부 | 이유 |
|------|----------|------|
| 옵션 1: Chart.js + `ng2-charts` | 보류 | peer-dep `^21` 지원 여부 불확실 |
| 옵션 2: Apache ECharts + `ngx-echarts` | 보류 | 번들 크기 큼(+700KB), 동일 peer-dep 리스크 |
| **옵션 3: Chart.js 단독 + 얇은 컴포넌트 래퍼 (채택)** | **채택** | • peer-dep 리스크 0<br>• Angular 21 standalone signal 패턴과 잘 맞음 (`afterNextRender` + `ElementRef`로 canvas 마운트)<br>• 번들 +180KB로 가장 가벼움<br>• Chart.js 4.x는 framework-agnostic |

**의존성 추가** (`frontend/appointment-frontend/package.json`):

```json
{
  "dependencies": {
    "chart.js": "^4.5.0"
  }
}
```

> 검증 절차(구현 단계): `npm view chart.js peerDependencies` 후 Angular 21 + TS 5.9 환경에서 `ng build` 통과 확인.

### 2.4 프론트엔드 컴포넌트 — 결정 A: 기존 `ManagementDashboardComponent` 확장

| 후보 | 채택 여부 | 이유 |
|------|----------|------|
| **옵션 1: 기존 컴포넌트 확장 (채택)** | **채택** | 기존 라우트(`/management`)/카드 레이아웃 보존, 차트 섹션만 추가 |
| 옵션 2: `/admin/dashboard` 신규 라우트 | 불가 | 라우트 중복, 기존 사용자(STAFF/DOCTOR) UX 파편화 |

**차트 영역 권한 분기 (Decision D)**: `/management` 라우트는 `ADMIN|STAFF|DOCTOR` 가드. 차트 데이터 API는 ADMIN 전용. → **STAFF/DOCTOR는 기존 카드만 보이고, 차트 섹션은 ADMIN일 때만 렌더링**한다(클라이언트 측 조건부 + 서버 403 안전망).

```typescript
// management-dashboard.component.ts
readonly isAdmin = computed(() => this.authService.roles().includes('ROLE_ADMIN'));
// template
@if (isAdmin()) { <app-dashboard-charts /> }
```

---

## 3. 백엔드 API 명세

### 3.1 공통 사항

- Base path: `/api/admin/stats`
- 인증: JWT (ADMIN 역할 필수, 그 외는 403)
- 응답 envelope: `ApiResponse<T>` (기존 패턴)
- 날짜 파라미터: `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)` (예: `2026-05-19`)
- 기간 미지정 시 기본값: **최근 30일** (`to = today`, `from = today.minusDays(29)`)
- 최대 조회 기간: **366일** (1년) — 초과 시 400 + `"period exceeds 366 days"` 메시지
- `from > to` 시 400 + `"from must be on or before to"` 메시지
- 시간대: clinic-naive `LocalDate`만 사용 (timezone 변환 없음)
- 상태 키는 `AppointmentState.name` 문자열 (sealed class 기반, enum 아님)

### 3.2 `GET /api/admin/stats/appointments`

**용도**: 일자별·상태별 예약 추세 (스택형 막대/라인 차트 데이터 소스).

**Query Parameters**:

| 이름 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `clinicId` | Long | yes | — | 병원 ID |
| `from` | LocalDate (ISO) | no | `today - 29` | 조회 시작일 |
| `to` | LocalDate (ISO) | no | `today` | 조회 종료일 |
| `statuses` | List\<String\> | no | 전체 | 필터링할 상태 (CSV) |

**응답 스키마**:

```kotlin
data class AppointmentStatsResponse(
    val clinicId: Long,
    val from: LocalDate,
    val to: LocalDate,
    val totals: Map<String, Long>,            // 상태별 총합: { "CONFIRMED": 120, "CANCELLED": 18, ... }
    val daily: List<DailyAppointmentBucket>,  // 일자별 시계열
) : Serializable

data class DailyAppointmentBucket(
    val date: LocalDate,
    val countsByStatus: Map<String, Long>,    // { "CONFIRMED": 5, "REQUESTED": 2 }
    val total: Long,
) : Serializable
```

**사용 인덱스**: `idx_appointments_clinic_date_status` (clinicId, appointmentDate, status)

**Exposed 쿼리 형태**:

```kotlin
Appointments
    .select(Appointments.appointmentDate, Appointments.status, Appointments.id.count())
    .where { (Appointments.clinicId eq clinicId) and (Appointments.appointmentDate.between(from, to)) }
    .groupBy(Appointments.appointmentDate, Appointments.status)
    .map { ... }
```

### 3.3 `GET /api/admin/stats/doctors`

**용도**: 의사별 예약 수 + 완료율 (수평 막대 차트).

**Query Parameters**:

| 이름 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `clinicId` | Long | yes | — | 병원 ID |
| `from` | LocalDate | no | `today - 29` | 조회 시작일 |
| `to` | LocalDate | no | `today` | 조회 종료일 |
| `limit` | Int | no | 20 | 상위 N명 (1..100) |

**응답 스키마**:

```kotlin
data class DoctorStatsResponse(
    val clinicId: Long,
    val from: LocalDate,
    val to: LocalDate,
    val doctors: List<DoctorBucket>,
) : Serializable

data class DoctorBucket(
    val doctorId: Long,
    val totalAppointments: Long,
    val completed: Long,
    val cancelled: Long,
    val noShow: Long,
    val completionRate: Double,    // completed / (completed + cancelled + noShow), 분모 0이면 0.0
) : Serializable
```

> 참고: `doctorName`은 본 API에서 반환하지 않는다 — 프론트엔드가 기존 `DoctorService` 캐시로 ID→이름 매핑.

**사용 인덱스**: `idx_appointments_doctor_date` (doctorId, appointmentDate)

> **P1 — 인덱스 효율 주의 (Step 2-R 피드백)**: 현재 인덱스는 `(doctorId, appointmentDate)` 컬럼 순서이며 `clinicId` 조건이 포함되지 않는다. 단일 병원 환경(이 예제 앱)에서는 테이블 전체가 하나의 병원에 속하므로 인덱스 효율이 충분하다. 다중 병원 환경으로 확장할 경우 `(clinicId, doctorId, appointmentDate)` 인덱스로 교체해야 `clinicId` 필터가 인덱스 스캔 내에서 처리된다. 이 변경은 Flyway 마이그레이션 별도 이슈로 남긴다.

### 3.4 `GET /api/admin/stats/cancellations`

**용도**: 취소·노쇼 추세 (일자별 라인 차트 + 비율 KPI).

**Query Parameters**:

| 이름 | 타입 | 필수 | 기본값 | 설명 |
|------|------|------|--------|------|
| `clinicId` | Long | yes | — | 병원 ID |
| `from` | LocalDate | no | `today - 29` | 조회 시작일 |
| `to` | LocalDate | no | `today` | 조회 종료일 |

**응답 스키마**:

```kotlin
data class CancellationStatsResponse(
    val clinicId: Long,
    val from: LocalDate,
    val to: LocalDate,
    val totalCancelled: Long,
    val totalNoShow: Long,
    val totalRescheduled: Long,
    val totalCompleted: Long,
    val cancellationRate: Double,   // CANCELLED / (CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED), 분모 0이면 0.0
    val noShowRate: Double,         // NO_SHOW / (CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED)
    val daily: List<DailyCancellationBucket>,
) : Serializable

data class DailyCancellationBucket(
    val date: LocalDate,
    val cancelled: Long,
    val noShow: Long,
    val rescheduled: Long,
) : Serializable
```

**분모 결정**: 완결된(CANCELLED + COMPLETED + NO_SHOW + RESCHEDULED) 예약만 분모. REQUESTED/CONFIRMED 등 진행 중 상태는 제외 → 노이즈 감소.

**사용 인덱스**: `idx_appointments_date_status` (appointmentDate, status) + clinicId 조건은 `idx_appointments_clinic_date_status`로 커버 가능. 실제 옵티마이저가 후자를 선택할 가능성이 높다.

### 3.5 에러 응답

| HTTP | 시나리오 | 응답 |
|------|---------|------|
| 400 | 파라미터 부재/형식 오류 | `ApiResponse.error("…")` |
| 401 | JWT 없음/만료 | `ApiResponse.error("Unauthorized")` |
| 403 | ADMIN 아님 | `ApiResponse.error("Forbidden")` |
| 500 | DB 오류 | `ApiResponse.error("Internal error")` |

---

## 4. 백엔드 구현 컴포넌트

### 4.1 신규 파일

| 경로 | 책임 | 비고 |
|------|------|------|
| `appointment-core/.../repository/AppointmentStatsRepository.kt` | 3개 집계 쿼리(`groupBy` + `count`) | `KLogging`, 모든 메서드는 호출자가 `transaction {}` 내부에서 호출 |
| `appointment-api/.../dto/AppointmentStatsResponse.kt` | 3.2 DTO | `Serializable`, `serialVersionUID` |
| `appointment-api/.../dto/DoctorStatsResponse.kt` | 3.3 DTO | 위 동일 |
| `appointment-api/.../dto/CancellationStatsResponse.kt` | 3.4 DTO | 위 동일 |
| `appointment-api/.../service/DashboardStatsService.kt` | 파라미터 검증(`requireXxx`) + `transaction {}` 경계 + Repository 호출 + 비율 계산 | bluetape4k `requireNotNull`/`require` 확장 사용 |
| `appointment-api/.../controller/DashboardStatsController.kt` | REST 엔드포인트 3개, OpenAPI 어노테이션 | `@RequestMapping("/api/admin/stats")` |

### 4.2 수정 파일

| 경로 | 변경 내용 |
|------|----------|
| `appointment-api/.../security/SecurityConfig.kt` | `/api/admin/**` → `hasRole(ADMIN)` 매처 추가 (GET 규칙 **앞**에) |
| `appointment-api/.../config/ServiceConfig.kt` | `AppointmentStatsRepository`, `DashboardStatsService` Bean 등록 |

### 4.3 인터페이스 스케치

```kotlin
// AppointmentStatsRepository.kt
class AppointmentStatsRepository {
    companion object : KLogging()

    /** 일자별·상태별 카운트. 호출자는 transaction 내부에서 호출. */
    fun countByDateAndStatus(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
        statuses: List<AppointmentState>? = null,
    ): List<Triple<LocalDate, AppointmentState, Long>>

    /** 의사별 상태별 카운트. */
    fun countByDoctorAndStatus(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
        limit: Int = 20,
    ): List<Triple<Long, AppointmentState, Long>>  // doctorId, status, count

    /** 일자별 취소/노쇼/재배정 카운트. */
    fun countCancellationsByDate(
        clinicId: Long,
        dateRange: ClosedRange<LocalDate>,
    ): List<Triple<LocalDate, AppointmentState, Long>>
}

// DashboardStatsService.kt
class DashboardStatsService(
    private val statsRepository: AppointmentStatsRepository,
) {
    fun getAppointmentStats(
        clinicId: Long,
        from: LocalDate,
        to: LocalDate,
        statuses: List<String>?,
    ): AppointmentStatsResponse {
        clinicId.requirePositive("clinicId")
        require(!from.isAfter(to)) { "from must be on or before to" }
        require(ChronoUnit.DAYS.between(from, to) <= 366) { "period exceeds 366 days" }

        // P1: AppointmentState.fromName()은 미지원 상태명에서 IllegalArgumentException 발생
        // (실제 API: `else -> throw IllegalArgumentException("Unknown appointment status: $name")`)
        // Service는 이를 잡아 ApiResponse.error() 400으로 변환
        val statusEnums = statuses?.map { AppointmentState.fromName(it) }
        // 호출부에서 try-catch로 IllegalArgumentException → 400 변환 처리
        return transaction {
            val rows = statsRepository.countByDateAndStatus(clinicId, from..to, statusEnums)
            // ... bucket 조립
        }
    }
    // 나머지 2개 메서드 유사
}
```

---

## 5. 보안 정책

### 5.1 적용 방식

- `SecurityConfig.securityFilterChain` 내 매처 추가 — 4.2 참조
- prod/staging 프로파일: ADMIN 토큰만 200, STAFF/DOCTOR 토큰은 **403 Forbidden**
- dev/test 프로파일: `NoOpSecurityConfig`가 모두 허용 → 통합 테스트는 별도 프로파일 또는 SecurityConfig 직접 import로 검증

### 5.2 테스트 시나리오

| 케이스 | 기대 결과 |
|--------|----------|
| ADMIN 토큰 + 유효 파라미터 | 200 OK |
| STAFF 토큰 | 403 Forbidden |
| DOCTOR 토큰 | 403 Forbidden |
| 토큰 없음 | 401 Unauthorized |
| ADMIN 토큰 + `from > to` | 400 |
| ADMIN 토큰 + 367일 범위 | 400 |
| ADMIN 토큰 + 빈 결과 병원 | 200, `totals = {}`, `daily = []` |

### 5.3 clinicId 소유권(IDOR) 정책

> **P1 — 명시적 정책 문서화 (Step 2-R 피드백, 실제 코드 검증 후 업데이트)**

**실제 확인된 사실**:

- `SchedulingUserPrincipal`은 `clinicId: Long?` 필드를 보유 — JWT 토큰에 clinicId 클레임이 존재한다.
- 그러나 기존 컨트롤러(`AppointmentController`, `RescheduleController` 등)는 `principal.clinicId` vs 파라미터 `clinicId`를 비교하지 않는다. `@RequestParam clinicId`를 그대로 서비스에 전달한다.

**채택 정책 (기존 패턴 일치)**:

이 대시보드 API도 기존 컨트롤러 패턴과 동일하게 `principal.clinicId` 소유권 검증을 수행하지 않는다.
ADMIN role 보유자가 어떤 clinicId든 통계 조회 가능. Path-based `hasRole(ADMIN)` 체크가 유일한 권한 경계다.

**기술 부채 인식**:

```kotlin
// 미래 다중 병원 환경으로 전환 시 추가 필요 (별도 이슈)
val principal = SecurityContextHolder.getContext().authentication.principal as SchedulingUserPrincipal
require(principal.clinicId == null || principal.clinicId == clinicId) {
    "Access to clinic $clinicId is not authorized"
}
```

**위험 수용 근거**: 기존 모든 API가 동일한 패턴을 따르고 있어 통일성 유지. 다중 병원 환경 전환 시 이 주석이 구현 위치 식별자 역할을 한다.

### 5.4 오류 전파 정책

> **P1 — Transaction/Service 오류 처리 경계 명세 (Step 2-R 피드백)**

| 오류 유형 | 발생 위치 | 변환 책임 | HTTP 응답 |
|----------|----------|----------|----------|
| `IllegalArgumentException` — `AppointmentState.fromName()` 미지원 상태명 | Service | **`GlobalExceptionHandler.handleIllegalArgument`** 자동 처리 → 400 | 400 |
| `IllegalArgumentException` — 기간 초과, from > to (Service `require(...)`) | Service | 위 동일, `GlobalExceptionHandler` 자동 처리 | 400 |
| DB 오류 (Transaction 내부) | Repository | `GlobalExceptionHandler.handleGeneral` → 500 | 500 |
| `ExposedSQLException` | Repository | 위 동일 | 500 |

**검증된 사실**: `GlobalExceptionHandler` (`appointment-api/.../config/GlobalExceptionHandler.kt`)는 이미 존재하며 `@ExceptionHandler(IllegalArgumentException::class)` → 400 변환을 처리한다. Service 계층에서 별도 try-catch 불필요.

**오류 메시지 보안**: `AppointmentState.fromName(name)`은 `"Unknown appointment status: $name"` 메시지를 던진다. 입력 상태명은 포함되나 내부 sealed class 서브클래스 전체 목록은 노출하지 않는다. 허용 상태명은 Swagger 스키마에만 문서화한다.

---

## 6. 프론트엔드 컴포넌트

### 6.1 신규 파일

| 경로 | 책임 |
|------|------|
| `frontend/appointment-frontend/src/app/core/services/dashboard-stats.service.ts` | 3개 API HTTP 클라이언트, `signal` 상태 |
| `frontend/appointment-frontend/src/app/core/models/dashboard-stats.model.ts` | 백엔드 DTO 미러 TypeScript 타입 |
| `frontend/appointment-frontend/src/app/features/management/dashboard/charts/appointment-trend-chart.component.ts` | 일자별 스택형 막대 차트 (Chart.js) |
| `frontend/appointment-frontend/src/app/features/management/dashboard/charts/doctor-workload-chart.component.ts` | 의사별 수평 막대 차트 |
| `frontend/appointment-frontend/src/app/features/management/dashboard/charts/cancellation-trend-chart.component.ts` | 취소율 라인 차트 + KPI 카드 |
| `frontend/appointment-frontend/src/app/features/management/dashboard/dashboard-charts.component.ts` | 위 3개를 묶는 컨테이너, ADMIN 가시성 분기 |
| 각 `.spec.ts` 파일 (vitest) | 컴포넌트/서비스 단위 테스트 |

### 6.2 수정 파일

| 경로 | 변경 |
|------|------|
| `frontend/appointment-frontend/src/app/features/management/dashboard/management-dashboard.component.ts` | `<app-dashboard-charts />` 섹션 추가, `isAdmin` computed signal |
| `frontend/appointment-frontend/src/app/core/services/index.ts` | `DashboardStatsService` export |
| `frontend/appointment-frontend/package.json` | `chart.js: ^4.5.0` 추가 |
| `frontend/appointment-frontend/src/app/core/models/index.ts` | dashboard-stats 모델 export |

### 6.3 차트 유형 매핑

| 차트 컴포넌트 | API | Chart.js type | 데이터 흐름 |
|--------------|-----|--------------|------------|
| `AppointmentTrendChart` | `/stats/appointments` | `bar` (stacked) | x: date, y: count, stack: status |
| `DoctorWorkloadChart` | `/stats/doctors` | `bar` (horizontal) | y: doctorName, x: total / completed |
| `CancellationTrendChart` | `/stats/cancellations` | `line` (multi-series) + KPI 카드 | x: date, y: cancelled / noShow / rescheduled |

### 6.4 데이터 흐름

```
Component ngOnInit (or afterNextRender)
  → DashboardStatsService.loadXxx(clinicId, from, to)
    → HttpClient GET /api/admin/stats/xxx
    → signal<XxxResponse | null> 갱신
  → effect/computed로 Chart.js 데이터 변환
  → ElementRef<HTMLCanvasElement>에 new Chart(...)
컴포넌트 destroy 시 chart.destroy() 호출
```

### 6.5 컴포넌트 라이프사이클 (Chart.js + signals)

- `afterNextRender(() => { ... })`에서 canvas 마운트 (Angular 21 SSR-safe)
- `effect(() => { rebuildChart(data()) })`로 signal 변화에 반응 — `untracked()`로 chart 인스턴스 메모리화
- `ngOnDestroy`에서 `chart?.destroy()`

---

## 7. 테스트 전략

### 7.1 백엔드 (JUnit 5 + MockK + H2)

| 레벨 | 대상 | 케이스 |
|------|------|--------|
| Repository (실제 H2) | `AppointmentStatsRepository` | 시드 데이터 → `groupBy` 결과 검증, 빈 결과, 날짜 경계, 상태 필터 |
| Service (MockK) | `DashboardStatsService` | 파라미터 검증, 비율 계산(분모 0 포함), 기본 날짜 범위, 미지원 상태명 → 400 |
| Controller (MockMvc) | `DashboardStatsController` | ADMIN 200, STAFF 403, DOCTOR 403, 토큰 없음 401, from>to 400, 기간 초과 400, JSON 직렬화 |
| SecurityConfig 통합 | 매처 순서 | ADMIN 200, STAFF 403, DOCTOR 403, anon 401, GET `/api/admin/...`이 GET `/api/**`보다 먼저 매칭 |

- H2: 기존 패턴 따름 (`SchemaUtils.createMissingTablesAndColumns`, `@BeforeEach deleteAll`)
- Testcontainers 미사용 (스펙 제약)

### 7.2 프론트엔드 (vitest)

| 대상 | 케이스 |
|------|--------|
| `DashboardStatsService` | 3개 메서드 HTTP 모킹, 성공/에러 응답 처리 |
| `AppointmentTrendChartComponent` | signal 변경 시 chart `update()` 호출 확인 (Chart.js 모킹) |
| `DashboardChartsComponent` | `isAdmin` false → 차트 미렌더링; true → 3개 차트 자식 컴포넌트 존재 |
| `ManagementDashboardComponent` (regression) | 기존 카드 섹션 유지 |

**Chart.js 모킹 전략** (P1 — `chart.destroy()` 검증 방법 명세):

```typescript
// vitest에서 Chart.js 모킹 — 생성자 + 인스턴스 메서드 스파이
const destroyFn = vi.fn();
const updateFn = vi.fn();
vi.mock('chart.js', () => ({
  Chart: vi.fn().mockImplementation(() => ({
    destroy: destroyFn,
    update: updateFn,
    data: { labels: [], datasets: [] },
  })),
  registerables: [],
}));

// ngOnDestroy 호출 후 destroy() 검증
it('컴포넌트 파괴 시 Chart 인스턴스를 destroy한다', () => {
  fixture.detectChanges(); // afterNextRender 트리거 (존재한다면 TestBed.flushEffects())
  component.ngOnDestroy();
  expect(destroyFn).toHaveBeenCalledOnce();
});
```

> `afterNextRender`는 TestBed 환경에서 자동 실행되지 않을 수 있다. 생성자나 `ngOnInit`으로 canvas 초기화를 이동하거나, `TestBed.flushEffects()`를 활용하는 두 가지 접근 모두 구현 단계에서 검증한다. 테스트가 안정적으로 통과하는 방식을 선택하되 명세를 벗어나지 않는다.

---

## 8. 위험 요소 + 완화 방안

| 위험 | 가능성 | 영향 | 완화 |
|------|-------|------|------|
| Angular 21 ↔ Chart.js 4 호환성 미검증 | 중 | 중 | Chart.js 단독(no Angular wrapper) 채택, 구현 단계에서 `ng build` 통과 확인 |
| `/api/admin/**` 매처 순서 오류로 우회 가능 | 저 | 고 | SecurityConfig 통합 테스트로 first-match-wins 명시 검증 |
| 대용량 병원에서 `groupBy` 풀스캔 | 중 | 중 | 기존 인덱스 활용, `to-from ≤ 366일` 강제, 추후 Materialized View는 별도 이슈 |
| `AppointmentState.fromName` 미지원 상태 입력 → 500 | 저 | 저 | Service에서 catch → 400 응답으로 변환 |
| Chart.js 인스턴스 누수 (컴포넌트 재마운트) | 저 | 저 | `ngOnDestroy`에서 `destroy()` + effect cleanup |
| 프론트 ADMIN 분기 우회 시 403 받지만 콘솔 에러 노이즈 | 저 | 저 | HttpInterceptor에서 `/api/admin/stats`에 한해 403을 silent fallback (signal `null` 유지) |
| `ManagementDashboardComponent`의 기존 `doctorCount.set(0)` 로직 — 비동기 결과 사용 안 함 (기존 버그) | 저 | 저 | 본 이슈 범위 밖, 별도 PR에서 수정. 본 스펙에서는 회귀 테스트만 추가 |
| 시간대 무시로 자정 부근 통계 오해 | 중 | 저 | 응답에 `from`/`to`(LocalDate, clinic-naive) 명시, README/Swagger에 표기 |
| 캐시 미적용 — 동일 ADMIN이 새로고침 반복 시 매번 집계 | 저 | 중 | 본 이슈에서는 캐시 없음(YAGNI). p95 측정 후 별도 이슈로 Spring Cache 적용 검토 |

---

## 9. 구현 순서 권장 (Plan 단계용 힌트)

1. `AppointmentStatsRepository` + H2 단위 테스트 (RED→GREEN)
2. DTO 3종 + `Serializable`/`serialVersionUID`
3. `DashboardStatsService` + MockK 단위 테스트
4. `DashboardStatsController` + MockMvc 통합 테스트
5. `SecurityConfig` 매처 추가 + 보안 통합 테스트
6. `ServiceConfig` Bean 등록 + Swagger 확인
7. 프론트엔드 `DashboardStatsService` + vitest
8. Chart.js 의존성 추가 + 3개 차트 컴포넌트 + vitest
9. `ManagementDashboardComponent` 통합 + 회귀 테스트
10. README 업데이트 (한/영), CHANGELOG 항목

---

## 10. Definition of Done (DoD)

### 10.1 백엔드

- [ ] `AppointmentStatsRepository` 구현 + H2 단위 테스트 (빈 결과/경계/필터 포함) 통과
- [ ] DTO 3종이 `Serializable` 구현 + `serialVersionUID = 1L` 보유
- [ ] `DashboardStatsService` 파라미터 검증 (bluetape4k `requireXxx`) + 비율 계산 (분모 0 안전) 단위 테스트 통과
- [ ] `DashboardStatsController` MockMvc 통합 테스트 200/400 통과
- [ ] SecurityConfig: ADMIN 200, STAFF/DOCTOR 403, anon 401 통합 테스트 통과
- [ ] 모든 Exposed 쿼리가 `transaction {}` 내부에서 실행됨 (코드 리뷰 확인)
- [ ] OpenAPI(Swagger) 페이지에서 3개 엔드포인트 가시 + 응답 스키마 정확
- [ ] `./gradlew :appointment-core:test :appointment-api:test` PASS, 소요 시간 보고
- [ ] IDE diagnostics: 에러 0, 미해결 deprecation 0
- [ ] 새/변경 public API에 영문 KDoc 작성

### 10.2 프론트엔드

- [ ] `package.json`에 `chart.js: ^4.5.0` 추가, `npm install` 후 `ng build` 통과
- [ ] `DashboardStatsService` vitest spec 통과 (3개 메서드, 에러 케이스 포함)
- [ ] 3개 차트 컴포넌트 + 컨테이너 컴포넌트 vitest spec 통과
- [ ] `ManagementDashboardComponent` 회귀 테스트 통과 (기존 카드 유지)
- [ ] ADMIN role일 때만 차트 영역 렌더링 — 단위 테스트로 검증
- [ ] Chart.js 인스턴스 `ngOnDestroy`에서 `destroy()` 호출 (메모리 누수 방지)
- [ ] `npm run test`, `npm run build` 모두 PASS

### 10.3 문서/PR

- [ ] `README.md` + `README.ko.md`에 대시보드 섹션 추가 (구조 정렬 유지)
- [ ] `CHANGELOG.md`에 항목 추가 (영문)
- [ ] PR 제목/본문 영문, 테스트 결과 및 보안 검증 명시
- [ ] `oh-my-claudecode:code-reviewer` 실행, HIGH/CRITICAL 해결 후 푸시
- [ ] worktree `.worktrees/feat/issue-44-45-dashboard/`에서 작업, develop 대상 PR 생성

---

## 12. Appendix — Step 2-R Iteration Log

### Round 1 (2026-05-19)

| Reviewer | P0 | P1 | P2 | P3 |
|----------|----|----|----|----|
| Phase 1: Architect | 0 | 2 | 2 | 0 |
| Phase 1: Security | 0 | 2 | 1 | 0 |
| Phase 1: Silent-Failure | 0 | 1 | 2 | 1 |
| Phase 1: Type-Design | 0 | 1 | 1 | 0 |
| **통합 합계** | **0** | **6** | **6** | **1** |

**Round 1 적용 항목** (spec 개정, 이 커밋):

1. [P1-Security] IDOR — clinicId 소유권 정책 명문화 → §5.3 신규 추가
2. [P1-Security/Arch] 오류 전파 경계 — Service 계층이 400 변환 담당 → §5.4 신규 추가
3. [P1-Test] DOCTOR 403 케이스 — §7.1 Controller 테스트 표에 명시
4. [P1-Test] chart.destroy() 검증 — §7.2 Chart.js 모킹 전략 섹션 추가
5. [P1-Performance] idx_appointments_doctor_date 멀티클리닉 갭 — §3.3 주의 노트 추가
6. [P1-Arch] AppointmentState.fromName() — §4.3 실제 API 확인 후 정확한 사용법 명세

### Round 2 목표

Claude Code 6-tier advisor + Phase 3 Codex 리뷰 후 P0/P1 = 0 달성 여부 확인.

---

## 11. 참조

- 기존 Repository 패턴: `appointment-core/.../repository/AppointmentRepository.kt`
- 기존 Controller 패턴: `appointment-api/.../controller/AppointmentController.kt`
- 기존 SecurityConfig: `appointment-api/.../security/SecurityConfig.kt`
- 기존 ApiResponse envelope: `appointment-api/.../dto/ApiResponse.kt`
- 기존 ManagementDashboardComponent: `frontend/appointment-frontend/src/app/features/management/dashboard/management-dashboard.component.ts`
- AppointmentState (sealed class): `appointment-core/.../statemachine/AppointmentState.kt`
- 기존 인덱스: `appointment-core/.../model/tables/Appointments.kt`
- 선례 Spec: `docs/superpowers/specs/2026-05-06-spring-cache-refactor-design.md`

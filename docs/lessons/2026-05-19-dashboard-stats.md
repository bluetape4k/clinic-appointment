# Dashboard Stats — 이슈 #44/#45 구현 회고

## 배경

이슈 #44: `GET /api/admin/stats/appointments|doctors|cancellations` 집계 API 구현
이슈 #45: Angular `/management/admin-dashboard` 라우트 + Chart.js 차트 구현

---

## 핵심 의사결정

### 1. AppointmentStatsRepository — Exposed groupBy/count 쿼리

`countByDateAndStatus` / `countByDoctorAndStatus` 두 메서드를 `AppointmentStatsRepository`에 추가.
- `groupBy(Appointments.appointmentDate, Appointments.status)` + `count()` 로 DB 단에서 집계 수행
- Kotlin List로 반환 후 서비스 레이어에서 버킷 조립 (date → DailyAppointmentBucket)
- SQL LIMIT 미사용: `(doctorId, status)` 행 단위가 아닌 의사 단위 top-N이 필요하므로 Kotlin `sortedByDescending + take(limit)` 사용

### 2. Exposed implicit receiver shadowing — 테스트 픽스

`insertAndGetId {}` 람다 내부에서 암시적 리시버가 Table 객체이므로 외부 프로퍼티 이름과 컬럼 이름이 동일할 때 섀도잉 발생.

문제 발현 패턴:
```kotlin
// NG: clinicId (파라미터) vs Doctors.clinicId (컬럼) 충돌
it[clinicId] = cId   // VALUES (SCHEDULING_DOCTORS.CLINIC_ID, ?)
```

해결 패턴 (명시적 테이블 참조):
```kotlin
it[Doctors.clinicId] = this@MyTest.clinicId   // VALUES (?, ?)
```

함수 파라미터와 컬럼명이 동일한 경우에도 동일한 섀도잉 발생:
```kotlin
// 함수 파라미터 status: AppointmentState가 Appointments.status Column을 섀도잉
it[status] = status           // 컴파일 오류: AppointmentState는 Column<...> 아님
it[Appointments.status] = status  // 정상
```

**교훈**: `insertAndGetId`, `insert`, `update` 람다 내부에서는 외부 프로퍼티/파라미터를 항상 명시적으로 한정하거나, 컬럼은 `it[Table.column]` 형식으로 접근.

### 3. 서비스 검증 로직 설계

`DashboardStatsService` 세 메서드 공통 가드:
- `clinicId.requirePositiveNumber("clinicId")` — 0 이하 → IAE
- `from ≤ to` — 위반 시 IAE
- 기간 ≤ 366일 — 위반 시 IAE
- `from/to null` → `to = today`, `from = today - 29` (30일 inclusive 기본값)

알 수 없는 `clinicId`는 HTTP 200 + 빈 응답 (에러 아님) — 클라이언트에서 "데이터 없음" 화면 처리.

### 4. Angular + Chart.js

- `chart.js@4.x` 직접 사용, ng2-charts 미사용 (Angular 21 호환성 단순화)
- 각 Chart 컴포넌트에서 `Chart.register(...registerables)` 호출
- `@Input() set stats(...)` → `updateChart()` 패턴으로 데이터 바인딩
- `ngOnDestroy()` 에서 반드시 `chart?.destroy()` 호출 (메모리 누수 방지)

---

## 수정된 버그

| 버그 | 원인 | 수정 |
|------|------|------|
| `VALUES (SCHEDULING_DOCTORS.CLINIC_ID, ?)` | Exposed implicit receiver shadowing | `it[Doctors.clinicId]` 명시적 참조 |
| 컴파일 오류 L100 `it[status] = status` | 함수 파라미터 status가 Column을 섀도잉 | `it[Appointments.status] = status` |

---

## 결과

- 백엔드 테스트: 117개 통과, 0 실패
  - `DashboardStatsServiceTest`: 13개
  - `DashboardStatsControllerTest`: 8개
- Angular 빌드: `ng build` 성공, `admin-dashboard-component` 청크 213 kB 생성

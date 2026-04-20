# AI 스케줄링 — Timefold Solver 설계

**모듈**: `appointment-solver`
**의존**: `appointment-core`

## 개요

복수 예약을 동시에 고려하여 전역 최적 배치를 수행하는 AI 스케줄러.
`SlotCalculationService`(단건 Greedy)와 역할을 분리하여 배치 최적화 전용으로 동작한다.

## Planning Variable

| Variable | 후보 범위 | 설명 |
|----------|----------|------|
| `doctorId` | 같은 clinicId, requiredProviderType 일치 의사 | 의사 배정 |
| `appointmentDate` | 지정 날짜 범위 (향후 7~30일) | 날짜 배정 |
| `startTime` | clinic.slotDurationMinutes 간격 이산 시간 목록 | 시작 시간 배정 |
| `endTime` | startTime + treatmentDuration (Shadow Variable) | 자동 계산 |

## Hard 제약조건 (위반 시 배치 불가)

| ID | 제약 | 설명 |
|----|------|------|
| H1 | `withinOperatingHours` | 예약 시간이 영업시간(isActive=true) 내에 있어야 함 |
| H2 | `withinDoctorSchedule` | 의사 근무 스케줄 내에 있어야 함 |
| H3 | `noDoctorAbsenceConflict` | 의사 부재(전일 또는 시간 구간)와 겹치지 않아야 함 |
| H4a | `noBreakTimeConflict` | 요일별 휴식시간과 겹치지 않아야 함 |
| H4b | `noDefaultBreakTimeConflict` | 기본 휴식시간과 겹치지 않아야 함 |
| H5 | `noClinicClosureConflict` | 임시휴진(전일 또는 부분)과 겹치지 않아야 함 |
| H6 | `noHolidayConflict` | clinic.openOnHolidays=false인 경우 공휴일 예약 불가 |
| H7 | `maxConcurrentPatientsPerDoctor` | 의사별 동시 환자 수 제한 (treatmentMax > doctorMax > clinicMax 우선) |
| H8 | `equipmentAvailability` | 장비 동시 사용 수가 quantity 이하 |
| H9 | `providerTypeMatch` | 의사 providerType이 진료의 requiredProviderType과 일치 |
| H10 | `doctorBelongsToClinic` | 배정된 의사가 해당 클리닉 소속 |
| H11 | `noEquipmentUnavailabilityConflict` | 장비 사용불가 구간(`EquipmentUnavailabilityFact`) 중 해당 장비 예약 금지 |

## Soft 제약조건 (최적화 목표)

| ID | 제약 | 가중치 | 설명 |
|----|------|--------|------|
| S1 | `doctorLoadBalance` | 100 | 같은 날짜에 의사 간 예약 수 분산 |
| S2 | `minimizeGaps` | 10 | 의사 하루 스케줄의 빈 시간 간격 최소화 |

## 주요 클래스

| 클래스 | 역할 |
|--------|------|
| `AppointmentPlanning` | Planning Entity — doctorId, appointmentDate, startTime이 결정 변수 |
| `ScheduleSolution` | Planning Solution — 예약 목록 + Problem Facts |
| `ClinicFact` / `DoctorFact` / `EquipmentFact` / `TreatmentFact` | Problem Facts |
| `EquipmentUnavailabilityFact` | Problem Fact — 장비 사용불가 구간 (H11 제약에서 참조) |
| `AppointmentConstraintProvider` | H1~H10, S1~S2 제약 등록 |
| `HardConstraints` | Hard 제약 구현 |
| `SoftConstraints` | Soft 제약 구현 |
| `SolverService` | Solver 실행 진입점 |
| `SolverConfig` | Timefold Solver 설정 |
| `SolutionConverter` | DB 레코드 ↔ Planning Domain 변환 |

## SlotCalculationService와의 역할 분리

| | SlotCalculationService | SolverService |
|--|------------------------|---------------|
| 용도 | 환자 대면 실시간 슬롯 조회 | 관리자 배치 최적화 |
| 처리 단위 | 단건 (의사, 날짜, 진료유형) | 복수 예약 동시 최적화 |
| 방식 | Greedy | Timefold Constraint Streams |
| 호출 시점 | API 요청 시 실시간 | 배치 작업 / 임시휴진 재배정 |

## 벤치마크

기준선(baseline) 정의 완료 (`BenchmarkTest.kt`). 상세 결과: [solver-benchmark-report.md](../solver-benchmark-report.md)

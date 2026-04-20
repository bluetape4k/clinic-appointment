# ERD (Entity Relationship Diagram)

## 전체 테이블 관계도

```mermaid
erDiagram
    Clinics {
        bigint id PK
        varchar name
        varchar timezone
        varchar locale
        int slotDurationMinutes
        int maxConcurrentPatients
        boolean openOnHolidays
    }

    Doctors {
        bigint id PK
        bigint clinicId FK
        varchar name
        varchar providerType
        int maxConcurrentPatients
    }

    TreatmentTypes {
        bigint id PK
        bigint clinicId FK
        varchar name
        int defaultDurationMinutes
        varchar requiredProviderType
        boolean requiresEquipment
        int maxConcurrentPatients
    }

    Equipments {
        bigint id PK
        bigint clinicId FK
        varchar name
        int usageDurationMinutes
        int quantity
    }

    Appointments {
        bigint id PK
        bigint clinicId FK
        bigint doctorId FK
        bigint treatmentTypeId FK
        bigint equipmentId FK "nullable"
        date appointmentDate
        time startTime
        time endTime
        varchar status
        instant createdAt
        instant updatedAt
    }

    OperatingHoursTable {
        bigint id PK
        bigint clinicId FK
        varchar dayOfWeek
        time openTime
        time closeTime
        boolean isActive
    }

    BreakTimes {
        bigint id PK
        bigint clinicId FK
        varchar dayOfWeek
        time startTime
        time endTime
    }

    ClinicDefaultBreakTimes {
        bigint id PK
        bigint clinicId FK
        time startTime
        time endTime
    }

    ClinicClosures {
        bigint id PK
        bigint clinicId FK
        date closureDate
        boolean isFullDay
        time startTime "nullable"
        time endTime "nullable"
    }

    DoctorSchedules {
        bigint id PK
        bigint doctorId FK
        varchar dayOfWeek
        time startTime
        time endTime
    }

    DoctorAbsences {
        bigint id PK
        bigint doctorId FK
        date absenceDate
        time startTime "nullable, null=전일"
        time endTime "nullable, null=전일"
    }

    TreatmentEquipments {
        bigint id PK
        bigint treatmentTypeId FK
        bigint equipmentId FK
    }

    EquipmentUnavailabilities {
        bigint id PK
        bigint equipmentId FK
        date startDate
        date endDate
        varchar recurrenceRule "nullable, iCal RRULE"
        json exceptions "nullable, 예외 날짜 목록"
        instant createdAt
    }

    Holidays {
        bigint id PK
        date holidayDate
        varchar name
        boolean recurring
    }

    AppointmentNotes {
        bigint id PK
        bigint appointmentId FK
        varchar note
        instant createdAt
    }

    ConsultationTopics {
        bigint id PK
        bigint appointmentId FK
        varchar topic
    }

    RescheduleCandidates {
        bigint id PK
        bigint clinicId FK
        bigint originalAppointmentId FK
        bigint newAppointmentId FK "nullable"
        varchar status
        instant createdAt
    }

    Clinics ||--o{ Doctors : "employs"
    Clinics ||--o{ TreatmentTypes : "offers"
    Clinics ||--o{ Equipments : "owns"
    Clinics ||--o{ OperatingHoursTable : "operating hours"
    Clinics ||--o{ BreakTimes : "break times"
    Clinics ||--o{ ClinicDefaultBreakTimes : "default breaks"
    Clinics ||--o{ ClinicClosures : "closures"
    Clinics ||--o{ Appointments : "receives"

    Doctors ||--o{ DoctorSchedules : "works"
    Doctors ||--o{ DoctorAbsences : "absent"
    Doctors ||--o{ Appointments : "treats"

    TreatmentTypes ||--o{ TreatmentEquipments : "requires"
    TreatmentTypes ||--o{ Appointments : "applied in"

    Equipments ||--o{ TreatmentEquipments : "used by"
    Equipments ||--o{ EquipmentUnavailabilities : "unavailable"
    Equipments |o--o{ Appointments : "used in"

    Appointments ||--o{ AppointmentNotes : "has"
    Appointments ||--o{ ConsultationTopics : "covers"
    Appointments ||--o{ RescheduleCandidates : "generates"
```

## 핵심 관계 요약

| 관계 | 카디널리티 | 설명 |
|------|-----------|------|
| Clinic → Doctor | 1:N | 한 병원에 여러 의사 |
| Clinic → TreatmentType | 1:N | 한 병원에 여러 진료유형 |
| Clinic → Equipment | 1:N | 한 병원에 여러 장비 |
| Doctor → Appointment | 1:N | 한 의사가 여러 예약 담당 |
| TreatmentType → TreatmentEquipment | 1:N | 진료유형이 여러 장비 필요 가능 |
| Equipment → EquipmentUnavailability | 1:N | 장비별 사용불가 구간 다수 등록 |
| Appointment → AppointmentNote | 1:N | 예약 메모 여러 개 |
| Appointment → RescheduleCandidate | 1:N | 재배정 이력 추적 |

## 컬럼 타입 규칙

| 필드 종류 | 타입 | 예시 |
|---------|------|------|
| 예약 날짜 | `LocalDate` | `2026-04-20` (클리닉 현지) |
| 예약 시간 | `LocalTime` | `09:00:00` (클리닉 현지) |
| 감사 타임스탬프 | `Instant` (UTC) | `created_at`, `updated_at` |
| 타임존 | `String` (ZoneId) | `"Asia/Seoul"` |
| 반복 규칙 | `String` (iCal RRULE) | `"FREQ=WEEKLY;BYDAY=MO,WE"` |

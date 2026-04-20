# 사용자 시나리오 (User Scenarios)

## 참여자

| 참여자 | 설명 |
|--------|------|
| `Patient` | 예약을 생성/취소하는 환자 |
| `Staff` | 체크인·상태 변경을 처리하는 병원 직원 |
| `Admin` | 임시휴진·재배정·장비 관리를 담당하는 관리자 |
| `Frontend` | Angular SPA |
| `API` | appointment-api (Spring Boot) |
| `Core` | appointment-core (도메인 서비스·리포지토리) |
| `Solver` | appointment-solver (Timefold) |
| `EventBus` | Spring ApplicationEventPublisher |
| `Notification` | appointment-notification |

---

## S1. 환자 예약 생성

```mermaid
sequenceDiagram
    actor Patient
    participant Frontend
    participant API
    participant Core
    participant DB as PostgreSQL
    participant EventBus
    participant Notification

    Patient->>Frontend: 의사/날짜 선택
    Frontend->>API: GET /api/slots?doctorId&date&treatmentTypeId
    API->>Core: SlotCalculationService.calculate()
    Core->>DB: 영업시간·스케줄·기존예약 조회
    DB-->>Core: 데이터 반환
    Core-->>API: List<SlotResponse>
    API-->>Frontend: 가용 슬롯 목록

    Patient->>Frontend: 슬롯 선택 후 예약 확인
    Frontend->>API: POST /api/appointments (JWT)
    API->>Core: AppointmentRepository.save()
    Core->>DB: INSERT appointments (status=REQUESTED)
    DB-->>Core: 저장 완료
    Core-->>API: AppointmentRecord
    API->>EventBus: publishEvent(Created)
    EventBus->>Notification: NotificationEventListener
    Notification-->>DB: 알림 이력 저장
    API-->>Frontend: AppointmentResponse (201 Created)
    Frontend-->>Patient: 예약 완료 확인
```

---

## S2. 예약 확정 → 체크인 → 진료 완료

```mermaid
sequenceDiagram
    actor Staff
    participant Frontend
    participant API
    participant Core
    participant DB as PostgreSQL
    participant EventBus

    Staff->>Frontend: 예약 목록 조회
    Frontend->>API: GET /api/appointments?from=&to=
    API->>Core: AppointmentRepository.findByDateRange()
    Core->>DB: SELECT
    DB-->>API: 예약 목록
    API-->>Frontend: List<AppointmentResponse>

    Staff->>Frontend: "확정" 버튼 클릭
    Frontend->>API: PATCH /api/appointments/{id}/status {event: Confirm}
    API->>Core: StateMachine.transition(REQUESTED → CONFIRMED)
    Core->>DB: UPDATE status=CONFIRMED
    API->>EventBus: publishEvent(StatusChanged)
    API-->>Frontend: 200 OK

    Staff->>Frontend: 환자 내원 확인 → "체크인"
    Frontend->>API: PATCH /api/appointments/{id}/status {event: CheckIn}
    API->>Core: StateMachine.transition(CONFIRMED → CHECKED_IN)
    Core->>DB: UPDATE status=CHECKED_IN
    API->>EventBus: publishEvent(StatusChanged)

    Staff->>Frontend: 진료 시작
    Frontend->>API: PATCH /api/appointments/{id}/status {event: StartTreatment}
    API->>Core: StateMachine.transition(CHECKED_IN → IN_PROGRESS)

    Staff->>Frontend: 진료 완료
    Frontend->>API: PATCH /api/appointments/{id}/status {event: Complete}
    API->>Core: StateMachine.transition(IN_PROGRESS → COMPLETED)
    Core->>DB: UPDATE status=COMPLETED
    API->>EventBus: publishEvent(StatusChanged)
    API-->>Frontend: 200 OK
```

---

## S3. 임시휴진 재배정 (Solver 활용)

```mermaid
sequenceDiagram
    actor Admin
    participant Frontend
    participant API
    participant Core
    participant Solver
    participant DB as PostgreSQL
    participant EventBus

    Admin->>Frontend: 임시휴진 날짜 등록
    Frontend->>API: POST /api/reschedule/closure {closureDate}
    API->>Core: ClosureRescheduleService.findAffected(closureDate)
    Core->>DB: SELECT appointments WHERE date=closureDate AND status IN (REQUESTED, CONFIRMED)
    DB-->>Core: 영향받는 예약 목록

    Core->>DB: UPDATE status=PENDING_RESCHEDULE (일괄)
    API->>EventBus: publishEvent(StatusChanged × N)

    API->>Solver: SolverService.solve(clinicId, appointmentIds, dateRange)
    Solver->>DB: Problem Facts 로드 (의사·스케줄·장비 등)
    Solver->>Solver: Timefold 최적화<br/>(H1~H11 Hard + S1~S2 Soft)
    Solver-->>API: SolverResult (appointmentId → Assignment)

    loop 각 재배정 예약
        API->>Core: AppointmentRepository.save(newAppointment)
        Core->>DB: INSERT new appointment (status=CONFIRMED)
        API->>Core: updateStatus(original → RESCHEDULED)
        Core->>DB: UPDATE original status=RESCHEDULED
        API->>EventBus: publishEvent(Rescheduled)
    end

    API-->>Frontend: 재배정 결과 요약
    Frontend-->>Admin: 재배정 완료 목록 표시
```

---

## S4. 장비 사용불가 등록 + 예약 충돌 확인

```mermaid
sequenceDiagram
    actor Admin
    participant Frontend
    participant API
    participant Core
    participant DB as PostgreSQL

    Admin->>Frontend: 장비 사용불가 기간 입력<br/>(recurrenceRule 포함 가능)
    Frontend->>API: POST /api/equipment-unavailability
    API->>Core: EquipmentUnavailabilityService.create()
    Core->>DB: SELECT overlapping unavailabilities
    DB-->>Core: 기존 기간 목록

    alt 충돌 있음
        Core-->>API: ConflictException
        API-->>Frontend: 409 Conflict + 충돌 기간 목록
        Frontend-->>Admin: 충돌 안내
    else 충돌 없음
        Core->>Core: UnavailabilityExpander.expand(RRULE)
        Core->>DB: INSERT EquipmentUnavailabilities
        DB-->>Core: 저장 완료
        API-->>Frontend: 201 Created
        Frontend-->>Admin: 등록 완료
    end

    Note over API,Core: 이후 SlotCalculationService가<br/>해당 기간 슬롯 제외
```

---

## S5. HA 알림 리마인더 발송 (스케줄러)

```mermaid
sequenceDiagram
    participant Scheduler as AppointmentReminderScheduler<br/>(1시간마다)
    participant Redis
    participant Core
    participant DB as PostgreSQL
    participant Channel as DummyNotificationChannel

    Scheduler->>Redis: SETNX leader-lock (bluetape4k-leader)
    alt 리더 획득 성공
        Redis-->>Scheduler: OK (이 인스턴스가 리더)
        Scheduler->>Core: findTomorrowConfirmed() + findTodayConfirmed()
        Core->>DB: SELECT appointments WHERE status=CONFIRMED AND date IN (tomorrow, today)
        DB-->>Core: 예약 목록
        Core-->>Scheduler: List<AppointmentRecord>
        loop 각 예약
            Scheduler->>Channel: sendReminder(appointment)
            Channel->>DB: INSERT notification_history (SUCCESS)
        end
    else 리더 획득 실패
        Redis-->>Scheduler: 다른 인스턴스가 리더
        Scheduler->>Scheduler: SKIP (중복 발송 방지)
    end
```

# 데이터 흐름 (Data Flow)

## 1. 예약 생성 흐름

```mermaid
flowchart TD
    FE["Angular Frontend"] -->|"POST /api/appointments"| API["AppointmentController"]
    API -->|"JWT 검증"| SEC["JwtAuthenticationFilter"]
    SEC --> API
    API -->|"SlotCalculationService.isAvailable()"| SLOT["슬롯 가용성 검증"]
    SLOT -->|"영업시간 / 의사 스케줄 / 기존 예약 충돌 확인"| CORE["appointment-core"]
    CORE --> DB[("PostgreSQL")]
    API -->|"AppointmentRepository.save()"| DB
    API -->|"publishEvent(Created)"| EVT["AppointmentEventPublisher"]
    EVT -->|"@EventListener"| LOG["AppointmentEventLogger → DB"]
    EVT -->|"@EventListener"| NOTIF["NotificationEventListener"]
    NOTIF -->|"DummyNotificationChannel"| HIST["NotificationHistory → DB"]

    style FE fill:#4A90D9,color:#fff
    style API fill:#7B68EE,color:#fff
    style DB fill:#336791,color:#fff
    style EVT fill:#E8A838,color:#fff
```

![예약 생성 데이터 흐름](assets/data-flow-01-appointment-create-ko.png)

[SVG](assets/data-flow-01-appointment-create-ko.svg) · [Mermaid source](assets/data-flow-01-appointment-create.mmd)

## 2. 슬롯 조회 흐름

```mermaid
flowchart LR
    FE["Frontend"] -->|"GET /api/slots\n?doctorId&date&treatmentTypeId"| API["SlotController"]
    API --> SVC["SlotCalculationService"]

    SVC --> OH["OperatingHours 조회"]
    SVC --> DS["DoctorSchedule 조회"]
    SVC --> DA["DoctorAbsence 확인"]
    SVC --> BT["BreakTime 확인"]
    SVC --> CC["ClinicClosure 확인"]
    SVC --> HOL["Holiday 확인"]
    SVC --> EQ["EquipmentUnavailability 확인"]
    SVC --> APT["기존 Appointment 조회\n(동시 환자 수 체크)"]

    OH & DS & DA & BT & CC & HOL & EQ & APT --> CALC["빈 슬롯 목록 계산\n(Greedy)"]
    CALC -->|"List<SlotResponse>"| FE

    style FE fill:#4A90D9,color:#fff
    style CALC fill:#27AE60,color:#fff
```

![슬롯 조회 데이터 흐름](assets/data-flow-02-slot-query-ko.png)

[SVG](assets/data-flow-02-slot-query-ko.svg) · [Mermaid source](assets/data-flow-02-slot-query.mmd)

## 3. 임시휴진 재배정 흐름

```mermaid
flowchart TD
    ADMIN["관리자"] -->|"POST /api/reschedule/closure\n{closureDate}"| API["RescheduleController"]
    API --> CRS["ClosureRescheduleService"]

    CRS -->|"closureDate 예약 조회"| AFFECTED["영향받는 예약\n(CONFIRMED, REQUESTED)"]
    AFFECTED --> MARK["상태 → PENDING_RESCHEDULE"]

    subgraph Solver["Timefold Solver (선택적)"]
        direction TB
        LOAD["DB 데이터 로드\n(SolutionConverter)"] --> PLAN["ScheduleSolution 구성\n(Planning Variables)"]
        PLAN --> OPT["제약 최적화\n(H1~H12, S1~S6)"]
        OPT --> RESULT["Assignment Map 반환"]
    end

    subgraph Greedy["Greedy 단건 재배정"]
        direction TB
        FIRST["첫 번째 가용 슬롯 탐색\n(SlotCalculationService)"] --> ASSIGN["새 예약 생성"]
    end

    MARK --> Solver
    MARK --> Greedy
    Solver -->|"대량 최적 배치"| SAVE["DB 저장\n(RESCHEDULED)"]
    Greedy -->|"단건 빠른 처리"| SAVE
    SAVE --> EVT["Rescheduled 이벤트 발행"]

    style ADMIN fill:#E74C3C,color:#fff
    style Solver fill:#9B59B6,color:#fff,stroke:#7D3C98
    style Greedy fill:#27AE60,color:#fff,stroke:#1E8449
```

![휴진 재배정 데이터 흐름](assets/data-flow-03-closure-reschedule-ko.png)

[SVG](assets/data-flow-03-closure-reschedule-ko.svg) · [Mermaid source](assets/data-flow-03-closure-reschedule.mmd)

## 4. 장비 사용불가 등록 흐름

```mermaid
flowchart TD
    ADMIN["관리자"] -->|"POST /api/equipment-unavailability"| API["EquipmentUnavailabilityController"]
    API --> SVC["EquipmentUnavailabilityService"]

    SVC -->|"findOverlapping()"| REPO["EquipmentUnavailabilityRepository"]
    REPO --> DB[("PostgreSQL")]

    REPO -->|"충돌 기존 기간 반환"| CONFLICT{"충돌 있음?"}
    CONFLICT -->|"Yes"| ERR["409 Conflict 반환"]
    CONFLICT -->|"No"| EXPAND["UnavailabilityExpander\n반복 규칙(RRULE) 전개"]
    EXPAND --> SAVE["DB 저장"]
    SAVE -->|"EquipmentUnavailabilityResponse"| ADMIN

    style ADMIN fill:#E74C3C,color:#fff
    style CONFLICT fill:#F39C12,color:#fff
    style ERR fill:#C0392B,color:#fff
```

![장비 사용 불가 데이터 흐름](assets/data-flow-04-equipment-unavailability-ko.png)

[SVG](assets/data-flow-04-equipment-unavailability-ko.svg) · [Mermaid source](assets/data-flow-04-equipment-unavailability.mmd)

## 5. 알림 이벤트 흐름

```mermaid
flowchart LR
    APT["예약 상태 변경\n(CRUD 완료 후)"] -->|"publishEvent()"| PUB["ApplicationEventPublisher\n(Spring)"]

    PUB -->|"@EventListener"| LOG["AppointmentEventLogger\n→ AppointmentEventLogs 테이블"]
    PUB -->|"@EventListener"| NOTIF["NotificationEventListener"]

    NOTIF --> CB["CircuitBreaker\n(Resilience4j)"]
    CB --> RETRY["Retry (3회)"]
    RETRY --> BH["Bulkhead (동시 10)"]
    BH --> CH["DummyNotificationChannel\n로그 + 이력 저장"]

    subgraph HA["HA 리마인더 스케줄러 (1h)"]
        LEADER{"Redis Leader?"}
        LEADER -->|"Yes"| REMIND["내일/오늘 CONFIRMED\n예약 리마인더 발송"]
        LEADER -->|"No (다른 노드)"| SKIP["SKIP"]
    end

    style PUB fill:#E8A838,color:#fff
    style HA fill:#16A085,color:#fff,stroke:#0E6655
```

![알림 이벤트 데이터 흐름](assets/data-flow-05-notification-events-ko.png)

[SVG](assets/data-flow-05-notification-events-ko.svg) · [Mermaid source](assets/data-flow-05-notification-events.mmd)

## 6. Solver 데이터 흐름

```mermaid
flowchart TD
    API["SolverService.solve()"] --> LOAD["SolutionConverter\nDB → Planning Domain"]

    subgraph Facts["Problem Facts (고정)"]
        direction LR
        F1["DoctorFact"] 
        F2["OperatingHoursFact"]
        F3["DoctorScheduleFact"]
        F4["DoctorAbsenceFact"]
        F5["ClinicClosureFact"]
        F6["HolidayFact"]
        F7["EquipmentFact"]
        F8["EquipmentUnavailabilityFact"]
    end

    subgraph Planning["Planning Entities (Solver 결정)"]
        PE["AppointmentPlanning\n- doctorId (변수)\n- appointmentDate (변수)\n- startTime (변수)\n[Pinned if CONFIRMED+]"]
    end

    LOAD --> Facts
    LOAD --> Planning

    Facts & Planning --> SOLVE["Timefold Solver\nHard 11개 + Soft 2개"]
    SOLVE --> RESULT["SolverResult\n(appointmentId → Assignment)"]
    RESULT --> CALLER["호출자가 DB 저장"]

    style Facts fill:#2980B9,color:#fff,stroke:#1A5276
    style Planning fill:#8E44AD,color:#fff,stroke:#6C3483
    style SOLVE fill:#D35400,color:#fff
```

![Solver 데이터 흐름](assets/data-flow-06-solver-data-ko.png)

[SVG](assets/data-flow-06-solver-data-ko.svg) · [Mermaid source](assets/data-flow-06-solver-data.mmd)

## 7. Scheduling Policy 관리 흐름

```mermaid
flowchart TD
    ADMIN["관리자"] -->|"POST /admin/.../scheduling-policies/drafts"| API["Tenant/Clinic SchedulingPolicyController"]
    API -->|"path tenant/clinic 검증"| SCOPE["TenantClinicAccessChecker"]
    API -->|"Gateway principal -> ActorContext"| ACTOR["ActorContextResolver"]
    API --> APP["SchedulingPolicyAdministrationService"]
    APP --> CMD["SchedulingPolicyCommandService"]
    CMD -->|"transaction + revision/generation CAS"| DB[("PostgreSQL")]
    CMD -->|"definition, approval, scope head"| DB

    ADMIN -->|"POST /{id}/preview"| PREVIEW["SchedulingPolicyPreviewService"]
    PREVIEW -->|"bounded scan + cursor"| IMPACT["SchedulingPolicyImpactRepository"]
    IMPACT --> DB
    PREVIEW -->|"200 completed or 202 durable job"| ADMIN
    ADMIN -->|"GET /preview-jobs/{jobId}"| POLL["Preview polling"]
    POLL --> DB

    ADMIN -->|"approve + activate/schedule"| CMD
    CMD -->|"durable activation command + idempotency"| DB
    WORKER["SchedulingPolicyWorker"] -->|"claim due command with DB lease"| DB
    WORKER -->|"activate, increment generation, outbox"| DB

    style ADMIN fill:#E74C3C,color:#fff
    style API fill:#7B68EE,color:#fff
    style DB fill:#336791,color:#fff
    style WORKER fill:#16A085,color:#fff
```

이 흐름은 예약 생성 경로를 직접 바꾸지 않는다. booking consumer flag는 foundation에
없으며, 예약 생성 서비스가 effective snapshot을 소비하는 단계는 후속 변경이다.

## 8. Scheduling Policy Effective Read 흐름

```mermaid
flowchart TD
    CALLER["예약/운영 caller"] -->|"GET .../effective?decisionAt&serviceAt"| API["SchedulingPolicyController"]
    API -->|"RFC 3339 instant 정규화"| TIME["decisionAt/serviceAt"]
    API --> SERVICE["EffectiveSchedulingPolicyService"]
    SERVICE -->|"generation read #1"| HEAD1["Scope heads"]
    SERVICE -->|"active tenant + clinic definitions"| DEFINITIONS["Policy definitions"]
    SERVICE -->|"compile + canonical hash"| SNAPSHOT["EffectiveSchedulingPolicy"]
    SERVICE -->|"generation read #2"| HEAD2["Scope heads"]
    HEAD1 --> CHECK{"same generation?"}
    HEAD2 --> CHECK
    CHECK -->|"yes"| RESPONSE["200 snapshotHash + payload"]
    CHECK -->|"no"| CONFLICT["409 POLICY_EFFECTIVE_READ_CONFLICT"]

    style CALLER fill:#4A90D9,color:#fff
    style SERVICE fill:#27AE60,color:#fff
    style CONFLICT fill:#C0392B,color:#fff
```

권위 저장소를 읽을 수 없으면 `503 POLICY_EFFECTIVE_READ_UNAVAILABLE`로 fail-closed 한다.
stale cache나 암묵적 기본값을 반환하지 않는다.

## 9. Scheduling Policy 운영 장애 재조정 흐름

```mermaid
flowchart TD
    OPS["공휴일 변경 / 의사 휴진 / 장비 고장 / partial fulfillment"] --> POLICY["DisruptionRecoveryPolicy"]
    POLICY -->|"automaticProposalEnabled"| PROPOSE["대체 일정 제안"]
    PROPOSE --> CONSENT["고객 동의 대기"]
    CONSENT -->|"동의"| APPLY["예약 변경 적용"]
    CONSENT -->|"거절/무응답"| KEEP["기존 확정 예약 보존"]

    style OPS fill:#E67E22,color:#fff
    style CONSENT fill:#9B59B6,color:#fff
    style KEEP fill:#27AE60,color:#fff
```

확정 예약 변경은 고객 동의 후 적용한다. 예약 서비스는 재예약 제안과 예약 상태만 다루며,
환불·보상·민원 처리는 외부 서비스의 event로 수렴한다.

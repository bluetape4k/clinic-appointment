# Issue #39 Tenant Query Isolation 설계

## 1. 목적

HTTP 경계에서 확인한 tenant authority가 scheduling, solver, reschedule, cache, Spring event, notification direct delivery까지 소실되지 않도록 한다. 모든 externally reachable 경로는 `clinicId` 단독 값이 아니라 `(tenantGroupId, clinicId)`를 권위 범위로 사용하며, tenant가 일치하지 않으면 조회·변경·비동기 전달 전에 fail-closed 한다.

이 설계는 ADR-14의 다음 계약을 구현한다.

- `tenantGroupId`는 내부 격리 authority다.
- `clinicId`와 child resource ID는 외부 입력 또는 비동기 경계에서 단독 권위를 갖지 않는다.
- HTTP adapter는 `TenantContext`를 즉시 명시적인 scope로 변환한다.
- core, background, virtual thread, event consumer는 thread-local context를 읽지 않고 scope를 인자로 받는다.

## 2. 현재 문제

현재 API는 `TenantClinicAccessChecker`로 clinic 소유권을 검증하지만 검증된 tenant를 하위 호출에 전달하지 않는다.

- `SlotQuery`는 `clinicId`만 보유한다.
- `SlotCalculationService`는 clinic을 전역 ID로 조회하고 `HolidayRepository.existsByDate(date)`를 호출한다.
- `SolverService`와 `ClosureRescheduleService`는 clinic 중심 조회만 수행한다.
- `DoctorRepository`, `EquipmentRepository`, `TreatmentTypeRepository`의 clinic 목록 cache key는 `clinicId`뿐이다.
- `AppointmentDomainEvent`와 `AppointmentEventLogs`에는 tenant가 없다.
- `NotificationEventListener`는 `clinicId`만 전달하고 `NotificationDirectOutboxDelivery`는 permit tenant key로 `0L`을 사용한다.

따라서 문제의 근본 원인은 predicate 문법이 아니라 authority가 서비스 경계에서 축소되는 것이다. Holiday 쿼리만 수정하거나 Exposed interceptor를 추가해도 cache와 비동기 경계는 보호되지 않는다.

## 3. 결정

### 3.1 공통 scope

`appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/service/TenantClinicScope.kt`에 다음 불변 값을 둔다. core를 API dependency로 사용하는 solver, event, notification 모듈도 같은 type을 재사용하며 별도 tenant-scope type을 복제하지 않는다.

```kotlin
data class TenantClinicScope(
    val tenantGroupId: Long,
    val clinicId: Long,
) : Serializable
```

두 ID는 양수여야 한다. 이 값은 인증 주체 자체가 아니라, 이미 HTTP authority 검증을 통과한 DB 격리 범위다.

`SlotQuery`는 `clinicId` 대신 `scope: TenantClinicScope`를 보유한다. slot, solver, closure reschedule의 public entry point도 동일 scope를 받는다. 기존 tenantless public overload는 남기지 않는다. 현재 caller는 이 repository의 source consumer이므로 의도적인 source-breaking migration으로 일괄 수정하고, 컴파일 실패를 이용해 누락 caller를 전수 탐지한다. Java serialization이나 외부 wire compatibility를 약속하지 않는다.

### 3.2 Repository와 cache

- `HolidayRepository.existsByDate`와 `findByDateRange`는 `tenantGroupId`를 필수 인자로 받는다.
- service 진입 transaction은 `ClinicRepository.findByIdAndTenant(scope.clinicId, scope.tenantGroupId)`로 clinic ownership을 먼저 고정한다.
- doctor, equipment, treatment type은 tenant-aware ID 조회 또는 tenant clinic subquery로 소유권을 검증한다.
- clinic 목록 cache API는 `(tenantGroupId, clinicId)`를 인자로 받고 cache key도 두 값을 포함한다. 현재 `NearCacheAdapter`가 `key.toString()`을 Redis key로 사용하므로 임의 data class 기본 문자열이나 SpEL 숫자 덧셈에 의존하지 않는다. 공통 factory는 부호 없는 10진수 두 값을 `${tenantGroupId}:${clinicId}` grammar로 직렬화한다. 예를 들어 `1:23`과 `12:3`은 충돌하지 않는다.
- repository 쿼리는 Exposed `transaction {}` 안에서만 실행한다.

같은 transaction 안에서 clinic ownership guard가 선행되고 child repository가 외부에 노출되지 않는 내부 helper라면 clinic ID 조건을 재사용할 수 있다. 반대로 public/cache API에서는 tenant를 생략할 수 없다.

### 3.3 Slot과 solver

`SlotCalculationService.findAvailableSlots(query)`는 다음 순서를 따른다.

1. `query.scope`의 tenant-clinic ownership 확인
2. tenant-scoped holiday 확인
3. clinic closure/operating hours/break time 조회
4. doctor와 treatment가 같은 tenant 및 clinic에 속하는지 확인
5. appointment/equipment 가용성 계산

ownership 또는 child resource 범위가 맞지 않으면 빈 결과를 반환한다. 이 조회 API는 타 tenant resource의 존재 여부를 드러내지 않는다.

Tenant 검증은 후보 슬롯, 장비, 예약, 의사 loop 안에서 별도 ownership query를 실행하지 않는다. 기존 clinic/resource 조회의 predicate를 tenant-aware하게 교체하며, 대표 fixture에서 `findAvailableSlots`의 DB round trip 수는 변경 전보다 증가하지 않아야 한다.

`SolverService`는 public solve entry point에서 `TenantClinicScope`를 받고, solver fact를 적재하기 전에 clinic ownership을 확인한다. holiday, doctor, appointment, treatment, equipment fact는 동일 scope로 제한한다. scope 밖 fact가 하나라도 섞인 solution은 허용하지 않는다.

Tenant predicate는 기존 fact list query에 합성한다. doctor별 schedule/absence를 적재하는 기존 N+1 구조는 이번 격리 변경의 재설계 범위가 아니지만 tenant guard가 doctor 수에 비례하는 새 query를 추가해서는 안 된다. 대표 doctor fixture에서 solver load의 query-count 증분은 0이어야 한다.

solution facts와 결과 변환용 original appointment map은 한 transaction에서 함께 snapshot한다. Solver 실행은 read-only 계산이며 DB에 결과를 직접 적용하지 않는다. 반환 결과에는 원본 appointment version을 유지하고, 후속 write caller는 optimistic version을 다시 확인해야 한다. concurrent mutation이 있으면 stale 결과를 적용하지 않는다.

### 3.4 Closure reschedule와 SSE

`ClosureRescheduleService.processClosureReschedule`와 `streamClosureReschedule`은 `TenantClinicScope`를 받는다. 영향 예약 조회와 상태 변경은 tenant-clinic guard 이후 수행한다. 각 후보 `SlotQuery`는 같은 scope를 재사용한다.

stream 경로는 모든 예약을 먼저 bulk transition하지 않는다. 예약 하나의 optimistic CAS transition, state history, slot 탐색, candidate 저장을 한 transaction에서 원자적으로 완료하고 commit 이후에만 progress를 전송한다. concurrent stream에서 CAS를 잃은 호출은 그 예약을 건너뛰며 candidate를 쓰지 않는다. transaction 이전 interruption은 ACTIVE를 유지하고, commit 이후 disconnect는 완료된 예약/candidate를 유지하되 아직 시작하지 않은 예약은 ACTIVE로 남긴다. 따라서 재호출이 안전하게 남은 ACTIVE 예약을 처리한다.

closure 시작 API뿐 아니라 candidate 조회, 수동 확정, 자동 확정도 `TenantClinicScope`를 사용한다. controller의 raw `RescheduleCandidates` query를 제거하고 tenant-scoped repository/service API로 이동한다. candidate는 original appointment를 tenant와 clinic으로 guard한 뒤에만 읽고, confirm/auto는 original appointment와 candidate doctor가 모두 같은 scope인지 검증한 후 mutation한다. tenantGroupId-only public overload는 남기지 않는다.

closure 경로의 tenant ownership 확인은 top-level 호출당 최대 1회 추가할 수 있다. 예약×검색일×후보 loop 안에는 tenant 전용 query를 추가하지 않는다. 기존 slot/후보 생성 알고리즘 자체의 bulk 최적화는 격리 변경과 분리한다.

API controller는 `TenantClinicAccessChecker`가 반환한 `tenantGroupId`와 path `clinicId`로 scope를 만들고 service에 전달한다. SSE controller가 virtual thread를 시작하기 전에 scope를 값으로 캡처하며, background thread에서 `TenantContext`를 다시 읽지 않는다. controller는 virtual-thread handle을 보관하고 emitter completion/error/timeout에서 interrupt한다. timeout은 무한대가 아닌 bounded configuration을 사용한다. interruption/cancellation은 current transaction을 rollback하고 permit/thread-local 자원을 해제한 뒤 종료한다.

## 4. Legacy event와 notification 경계

SHADOW/CANARY에서 direct-event route가 활성화될 수 있으므로 legacy Spring event를 제거하지 않는다.

- 모든 `AppointmentDomainEvent` subtype에 `tenantGroupId`를 필수 값으로 추가한다.
- API command가 이미 검증한 `tenantGroupId`를 event publisher까지 직접 전달한다. 내부 legacy entry point만 appointment-clinic ownership을 transaction에서 해소하며, event 발행만을 위한 중복 조회를 추가하지 않는다.
- `AppointmentEventLogs`와 `AppointmentEventLogRecord`에 `tenant_group_id`를 추가하고 event payload와 함께 저장한다.
- `NotificationEventListener`와 `NotificationDirectDeliveryPort.deliver`는 `tenantGroupId`를 전달한다.
- `NotificationDirectOutboxStore`와 `NotificationOutboxRepository.claimReadyForDirect`는 tenant를 필수 인자로 받고 SQL predicate에 tenant를 포함한다.
- direct outbox claim은 기존 durable row를 claim하되, 반환된 row의 tenant와 clinic이 event scope와 같은지 worker 호출 전에 다시 검증한다. mismatch는 provider side effect 없이 reject한다.
- rolling 호환을 위해 기존 direct lookup index는 유지하고, `idx_notification_outbox_tenant_direct_lookup`을 `(tenant_group_id, clinic_id, appointment_id, event_type, row_kind, status, available_at, next_retry_at, id)` 순서로 추가한다. 실제 `readyPredicate`와 `ORDER BY available_at, id`를 기준으로 dialect별 `EXPLAIN`을 확인한다. 구버전 node drain 전에는 기존 index를 제거하지 않는다.
- `NotificationDeliveryRouteGate`는 `(tenantGroupId, clinicId)` scope를 받으며, canary eligibility key도 같은 scope를 사용한다. `RolloutProperties`에 nested `canaryScopes[{tenantGroupId, clinicId}]`를 추가하고 paired README/YAML 예제에 migration을 기록한다. rolling 기간에는 구버전 node용 `canaryClinicIds`를 deprecated bridge로 함께 둘 수 있지만, 신버전 route 결정과 DB eligibility는 `canaryScopes`만 사용하며 두 설정의 clinic 집합이 다르면 startup을 거부한다. 구버전 node drain 뒤 별도 cleanup에서 clinic-only bridge를 제거한다.
- worker eligibility API와 `findReadyClinicKeys`도 bounded `Set<NotificationClinicKey>`를 받고 DB predicate에서 tenant-clinic tuple을 함께 제한한다.
- permit key는 실제 `(tenantGroupId, clinicId)`를 사용한다.
- synthetic tenant `0L`은 금지한다.

Modern durable notification outbox는 이미 tenant-aware하므로 schema나 envelope를 중복 변경하지 않는다. 이번 범위는 legacy `AppointmentDomainEvent`/event log/direct-event bridge의 누락만 보강한다.

Legacy `AppointmentDomainEvent`는 같은 Spring application process 안에서만 전달하는 `ApplicationEvent`이며 Java serialization이나 broker wire contract로 사용하지 않는다. `Serializable`은 기존 local type compatibility만 유지한다. 각 subtype은 `tenantGroupId > 0`과 `clinicId > 0`을 생성 시 검증하고, zero/missing scope를 신뢰된 DB 값으로 임의 보정하지 않는다. 향후 process 밖으로 전송하려면 별도 versioned envelope를 설계해야 하며 이번 변경에서 그런 transport를 만들지 않는다.

Event log JSON에는 `tenantGroupId`와 `clinicId`를 모두 기록한다. 이 payload는 audit 표현이며 replay authority가 아니다. row의 tenant/clinic column이 canonical scope이고, 향후 reader/replay를 추가할 경우 row/payload scope 일치 검증을 별도 필수 계약으로 둔다.

Legacy event log는 durable notification authority가 아닌 best-effort audit sink다. appointment transaction commit 이후 log insert가 실패해도 이미 성공한 API command를 실패로 뒤집거나 direct notification listener 실행을 막지 않는다. logger는 bounded error code/metric을 남기고 민감 payload나 raw exception을 기록하지 않는다. Durable delivery와 retry는 기존 tenant-aware notification outbox가 담당한다.

### 4.1 Additive migration

현재 dialect별 최신 migration은 V20이다. H2, PostgreSQL, MySQL에 동일 의미의 V21 migration을 추가한다.

1. `scheduling_appointment_event_logs.tenant_group_id` nullable column 추가
2. `scheduling_clinics`를 `clinic_id`로 join하여 기존 행 backfill
3. `scheduling_tenant_groups(id)`에 `ON DELETE RESTRICT` FK와 tenant-clinic 조회용 index 추가
4. 신규 `idx_notification_outbox_tenant_direct_lookup`을 `(tenant_group_id, clinic_id, appointment_id, event_type, row_kind, status, available_at, next_retry_at, id)` 순서로 추가하고 기존 index 유지

기존 event log가 clinic FK를 갖지 않으므로 V21에서 임의의 clinic FK 재설계는 하지 않는다. dispatch 전 read-only preflight는 모든 `clinic_id`가 정확히 한 clinic row와 non-null tenant owner로 해소되는지 확인하며, orphan이 있으면 Flyway를 시작하지 않는다. event payload는 replay authority가 아니므로 migration이 역사 JSON을 파싱하거나 재작성하지 않는다. tenant FK는 기존 V3~V6 제약과 동일한 `RESTRICT` 의미를 유지한다. V1~V20은 Flyway checksum 보존을 위해 수정하거나 재번호화하지 않는다. H2의 table/constraint 문법, PostgreSQL의 `UPDATE ... FROM`, MySQL의 InnoDB/index 관례를 유지하고 dialect별 integration test로 검증한다.

V21은 rolling-compatible해야 하므로 column을 nullable로 유지한다. 새 application writer는 tenant를 필수로 쓰고 zero/missing 값을 거부하지만, rollout 중 구버전 node의 insert를 DB constraint로 깨뜨리지 않는다. null-row count를 rollout metric으로 관찰하고, 구버전 writer가 모두 drain된 뒤 idempotent clinic join backfill을 다시 실행해 zero null을 증명한다. 그 이후에만 별도 승인·별도 release의 후속 migration으로 `NOT NULL`을 검토한다. V21과 hardening migration을 같은 artifact에 넣지 않는다.

신버전 `NotificationSchemaReadiness`는 Flyway V21, event-log tenant column, 신규 tenant direct index를 요구한다. 기존 index를 유지하므로 V21 적용 후 구버전 readiness와 code rollback도 계속 동작한다. rollback은 schema down migration을 실행하지 않고 notification route를 `PAUSED`로 전환한 뒤 이전 application으로 되돌린다.

V21은 event-log 전체 backfill과 index DDL이 필요한 maintenance migration이다. 배포 전 dialect별 row count, orphan count, join/update `EXPLAIN`, 예상 lock 방식을 기록하고 write traffic을 중단할 수 있는 maintenance window에서 실행한다. representative large fixture로 backfill과 index 사용을 확인하며, preflight 결과나 허용 maintenance window가 충족되지 않으면 dispatch하지 않는다. 자동으로 default tenant를 채우거나 실패 migration을 부분 우회하지 않는다.

`docs/runbooks/tenant-query-isolation.ko.md`에 V21 preflight SQL, H2/PostgreSQL/MySQL 실행 순서, MySQL partial-DDL schema-history 대조, pause/rollback, deprecated canary config 동시 운영, old-node drain, null-row 관찰, 후속 hardening hold를 기록한다.

운영 metric은 low-cardinality만 사용한다. 최소 관측은 event-log write failure counter(reason code), direct-event scope rejection counter(reason code), event-log null-tenant row gauge다. tenant/clinic/appointment ID를 metric tag나 raw error log에 넣지 않는다. schema/index/config mismatch는 readiness DOWN 사유로 노출한다.

## 5. 선택하지 않은 대안

### Exposed `StatementInterceptor`

선택하지 않는다. 숨은 `TenantContext` 의존성을 만들고, virtual thread와 event consumer에서 context 유실 시 fail-closed를 보장하지 못한다. 또한 cache key, event payload, permit key를 보호하지 않는다.

### Holiday API만 수정

선택하지 않는다. 가장 직접적인 누수는 막지만 solver fact, resource cache, closure background 작업, legacy event의 authority 축소가 남는다.

### Composite primary key 또는 tenant별 schema

선택하지 않는다. 전역 surrogate PK와 기존 FK를 대규모로 재작성하며 Issue #39의 query-boundary 목적보다 범위와 migration 위험이 크다.

### Tenantless compatibility overload

선택하지 않는다. 기존 caller가 조용히 우회할 수 있다. signature 변경으로 모든 caller를 컴파일 시점에 드러낸다.

## 6. 실패 모드와 대응

| 실패 모드 | 대응 |
|---|---|
| tenant A가 tenant B clinic/resource ID를 전달 | ownership guard에서 빈 결과 또는 기존 API 예외 계약으로 종료하고 데이터 존재를 노출하지 않음 |
| virtual thread에서 `TenantContext`가 비어 있음 | context를 읽지 않고 controller가 캡처한 immutable scope 사용 |
| 두 tenant가 같은 날짜에 서로 다른 holiday를 가짐 | 모든 holiday query에 `tenantGroupId` predicate 적용 |
| clinic 목록 cache entry가 다른 tenant 요청에 재사용됨 | cache API와 canonical key에 `(tenantGroupId, clinicId)` 포함하고 `key.toString()`의 우연한 형식에 의존하지 않음 |
| legacy event가 tenant 없이 발행됨 | 생성자 필수 값과 양수 검증으로 거부; rolling 호환을 위해 event log DB column은 V21에서 nullable 유지 |
| direct delivery가 synthetic tenant permit을 사용 | 실제 event tenant를 port와 permit registry까지 전달 |
| V21 preflight가 orphan event log를 발견 | Flyway dispatch 전 배포 중단; 임의 기본 tenant를 배정하지 않음 |
| reschedule 중 tenant-clinic mismatch | 영향 예약 상태 변경 전에 ownership 검증 |

동작별 fail-closed 결과는 다음과 같다.

- read/optimization은 scope 밖 데이터의 존재를 드러내지 않고 빈 결과 또는 기존 not-found 계약을 사용한다.
- reschedule/status 같은 write는 첫 변경 전에 scope를 검증하며 mismatch이면 mutation과 event 발행을 모두 하지 않는다. HTTP status 매핑은 #38 계약을 따른다.
- direct event는 zero/mismatch scope를 reject하고 claim, worker, provider 호출을 수행하지 않는다.
- migration preflight는 orphan 또는 owner 미해소 행을 발견하면 Flyway 시작 전에 중단하며 기본 tenant로 치환하지 않는다. MySQL DDL이 부분 적용된 경우 schema history와 실제 column/index를 대조한 recovery checklist 없이는 repair 또는 rerun하지 않는다.

## 7. 테스트 전략

### Repository

- 같은 날짜의 tenant A/B Holiday가 각 tenant 조회에만 보인다.
- wrong-tenant clinic, doctor, equipment, treatment ID는 조회되지 않는다.
- doctor/equipment/treatment 목록 cache key가 tenant와 clinic을 모두 구분한다.
- 두 tenant 반복 조회에서 cache hit/miss가 분리되고 기존 per-cache maximum/TTL 계약을 유지한다. 새 cache namespace나 무제한 entry를 만들지 않는다.

### Service

- A만 holiday인 날짜에 A slot은 없고 B slot은 생성된다.
- wrong-tenant doctor/treatment/equipment는 slot 계산 전에 거부된다.
- solver fact와 solution이 tenant scope를 벗어나지 않는다.
- closure process/stream은 다른 tenant 예약을 읽거나 변경하지 않는다.
- query-count 계측으로 slot 0, solver load 0, closure top-level 최대 1의 기존 대비 tenant query 증분을 고정한다.
- solver snapshot 이후 appointment version 변경 시 결과 적용이 거부된다.
- stream disconnect/interruption과 두 concurrent stream에서 완료 예약만 candidate를 가지며, 미시작 예약은 ACTIVE이고 중복 candidate가 없다.

### API와 background

- 검증된 tenant-clinic scope가 slot/reschedule service와 모든 candidate read/confirm/auto 경로로 전달된다.
- tenant A + clinic B 요청은 데이터가 노출되지 않는다.
- SSE virtual thread에서도 동일 scope가 유지된다.
- standalone `SolverService` public caller가 scope를 필수로 제공하며, 존재하지 않는 API controller/bean 계약을 새로 만들지 않는다.
- JWT parsing과 `401/403/404` 매트릭스는 #38 책임을 재구현하지 않는다.

### Event와 migration

- 모든 event subtype과 event log가 originating tenant를 유지한다.
- direct delivery port, typed canary scope, worker eligibility, route gate, store claim SQL predicate, claimed-row guard, permit key에 실제 tenant가 전달된다.
- zero/mismatch direct event가 claim/worker/provider side effect 없이 거부된다.
- event log JSON에 tenant와 clinic이 함께 기록되고 row scope와 일치한다.
- event-log insert 실패가 API command 결과를 뒤집지 않고 bounded metric을 남긴다.
- direct-route cancellation/interruption 후 permit이 해제되고 claimed row는 기존 lease-expiry recovery 계약으로 복구된다.
- H2, PostgreSQL, MySQL V21 migration을 순차 실행하고 nullable rolling compatibility/backfill/FK/index와 orphan preflight hold를 확인한다.
- representative large fixture의 migration `EXPLAIN`과 direct-claim index 사용을 보존한다.

## 8. 문서와 호환성

변경된 public 계약은 한국어 KDoc으로 설명한다. 다음 모듈의 `README.md`와 `README.ko.md`를 source-equivalent하게 갱신한다.

- `appointment-core`
- `appointment-solver`
- `appointment-event`
- `appointment-notification`
- `appointment-api`

| 문서 | 필수 caller 내용 |
|---|---|
| core | `TenantClinicScope`가 인증 객체가 아니라 검증 완료 DB authority임을 설명하고 `SlotQuery(scope = ...)` before/after 예제와 transaction 요구를 제공 |
| solver | standalone `optimize(scope, ...)`/`optimizeReschedule(scope, ...)` 예제와 read-only stale-result/version recheck 계약 제공 |
| event | local-only Spring event, positive tenant requirement, best-effort audit log, nullable rolling column의 의미 제공 |
| notification | `canary-clinic-ids`에서 nested `canary-scopes`로 가는 rolling YAML 예제, dual-config 일치 조건, pause/rollback 제공 |
| API | tenant verification 후 scope 생성, candidate read/confirm/auto, bounded SSE cancellation 결과를 설명 |

KDoc은 각 public parameter의 tenant/clinic 의미, mismatch 결과, transaction/cancellation 계약을 명시한다. 숫자 ID만 나열한 positional constructor 예제는 사용하지 않고 named argument로 scope 경계를 드러낸다.

root README에서 모듈 간 authority 흐름을 설명하는 부분을 바꾸는 경우 root `README.md`/`README.ko.md`도 함께 갱신한다. 구현과 무관한 README는 억지로 수정하지 않는다.

새 모듈이나 dependency는 추가하지 않는다. Flyway `scheduling_*` schema 이름은 변경하지 않는다.

## 9. 완료 조건

- 명시적 tenant scope가 repository, slot, solver, reschedule, event, notification 경계를 관통한다.
- tenantless Holiday/public scheduling API와 synthetic tenant key가 남지 않는다.
- cross-tenant negative test와 영향 모듈 build가 통과한다.
- 세 dialect migration이 순차 통과한다.
- 한·영 README 쌍, 한국어 KDoc, ADR/감사 기록이 구현과 일치한다.
- 독립 architecture, code-quality, test, security, performance, stability review에서 merge-blocking 지적이 없다.

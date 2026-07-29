# 방문 예약·확정 약속·상품 버전 전환 구현 계획

> **Agentic worker 필수 지침:** 각 Task를 순서대로 실행할 때
> `subagent-driven-development` 또는 `executing-plans`를 사용하고,
> 모든 Kotlin 변경에는 `bluetape-kotlin-patterns`, 모든 동작 변경에는
> `test-driven-development`를 적용한다.
>
> 상태: Task 7 Step 6-R `P0=0/P1=0` 완료. Task 8 구현 대기.

**목표:** 구매 당시 고정된 단일 상품 또는 패키지 실행 BOM을 여러 방문과 세부
진료로 전개하고, 고객 가예약·병원 승인·고객 동의·자원 점유를 원자적으로 결합한
확정 약속을 제공한다. 승인된 상품 version 전환과 외부 진료·환불 사실은 완료된
이력을 보존하면서 미래 항목만 새 Plan Revision과 제안으로 조정한다.

**아키텍처:** `appointment-core`가 versioned Plan, commitment, proposal, consent,
item, allocation과 순수 계산 규칙을 소유한다. `appointment-event`는 신뢰된 외부
event를 inbox/quarantine 경계에서 검증하고 동일 transaction의 Plan Revision과
outbox 변경으로 연결한다. `appointment-api`는 Gateway JWT에서 만든 기존
`ActorContext`만 신뢰해 고객·관리자 command를 분리하고, caller-owned Exposed
transaction에서 멱등성·CAS·자원 잠금·projection 갱신을 조정한다. Flyway V10은
H2/PostgreSQL/MySQL에 추가형 schema를 제공하며 Exposed schema 검사는 동등성
검증에만 사용한다.

**기술 스택:** Kotlin 2.3, Java 25, Spring Boot 4 MVC/Security/Actuator, Exposed
JDBC, Flyway, H2/PostgreSQL/MySQL, Jackson 3, JUnit 5, MockK, bluetape4k assertions
및 singleton Testcontainers launcher, Gatling.

**기준 설계:** [방문 예약·확정 약속·상품 버전 전환 설계](../specs/2026-07-29-issue-184-visit-commitment-design.md)

---

## 1. 납품 경계

### 포함

- 한 `Appointment` 아래 여러 `AppointmentItem`과 항목별 자원 점유
- 고객 요청 `PROPOSED` → 관리자 승인 → `CONFIRMED`
- 병원 제안과 관리자 직접 확정, 정확한 proposal에 결합된 고객 동의
- 새 제안 대기·거부·실패 중 기존 확정 proposal과 allocation 보호
- 반복형·복합형·N-of-M 실행 BOM과 방문 묶음 제약
- 상품 version 고정, 승인된 전환의 동일 Plan 새 Revision
- 진료 완료·부분 이행·환불 event에 따른 미래 항목 dirty-set 조정
- capacity bucket, 전담 자원, 초과 예약 상한의 다중 DB 공통 정합성
- command/event 멱등성, transactional outbox, quarantine/redrive
- Gateway 인증 기반 actor/scope 권한, stable error, OpenAPI
- V10 expand/shadow/allowlist rollout과 운영 metric

### 제외

| 제외 항목 | 경계 |
|---|---|
| 상품 정의·가격·계약·동의 획득 | 상품/구매 서비스 |
| 임상 완료 판정 | 진료/시술 서비스 |
| 환불 금액·보상·민원 해결 | 결제/CRM 서비스 |
| 추가 구매의 기존 Plan 병합 | 새 구매는 새 Plan |
| 교차 Plan 방문 묶기 | 후속 issue |
| 대량 장애 solver·대기목록·재확인 발송 | 후속 issue |
| Angular 상품 기획 화면 | 승인된 HTML은 설계 문서이며 frontend 납품이 아님 |
| GitHub Pages 공개 | 별도 구성 이후 |

### 변경 권한

이 계획은 `bluetape4k/clinic-appointment`, base `develop`, head
`feature/184-visit-commitment`의 구현·검증·PR 생성까지를 다룬다. merge는 최신
head의 CI와 7-R을 확인한 뒤 별도 승인을 받는다.

## 2. 파일 구조와 책임

### `appointment-core`

| 경로 | 책임 |
|---|---|
| `model/commitment/AppointmentCommitmentModel.kt` | commitment/proposal lifecycle, origin, immutable read model |
| `model/commitment/ConsentDecisionModel.kt` | proposal·상품 전환 동의 subject와 증빙 결합 |
| `model/commitment/AppointmentItemDraft.kt` | Plan treatment를 실제 방문에서 시도하는 item |
| `model/commitment/ResourceAllocationModel.kt` | practitioner/equipment/space 점유와 capacity 사용량 |
| `model/plan/AppointmentPlanRevisionModel.kt` | 동일 구매 Plan의 불변 revision과 활성 revision |
| `model/plan/PackageExecutionSnapshot.kt` | 전개된 BOM, provenance, dependency/grouping snapshot |
| `model/plan/ProductVersionMigration.kt` | `KEEP/REPLACE/SPLIT/MERGE/REMOVE/ADD` 전환표 |
| `model/operation/AppointmentOperationalException.kt` | 상담 handoff가 필요한 append-only 운영 예외 |
| `model/tables/AppointmentCommitments.kt` | appointment 1:1 commitment와 version CAS |
| `model/tables/Appointments.kt` | legacy/new 모델 표식과 미확정 v2 방문의 nullable 확정 projection |
| `model/tables/AppointmentProposals.kt` | append-only proposal revision과 hash |
| `model/tables/AppointmentItems.kt` | visit-item attempt와 Plan provenance |
| `model/tables/ResourceAllocations.kt` | 활성/해제 allocation과 covering index |
| `model/tables/ResourceCapacityBuckets.kt` | 공통 DB capacity 직렬화 row |
| `model/tables/TreatmentSpaces.kt` | 실제 진료실·수술실 식별자, capability, 수용량과 계산 단위 |
| `model/tables/ConsentDecisions.kt` | append-only consent evidence |
| `model/tables/AppointmentPlanRevisions.kt` | Plan revision header와 snapshot hash |
| `model/tables/PlanRevisionTreatments.kt` | revision별 treatment provenance, 시간과 자원 요구 |
| `model/tables/PlanRevisionDependencies.kt` | `BLOCKING/NON_BLOCKING` 실행 edge와 완료 기준 간격 |
| `model/tables/PlanRevisionGroupingConstraints.kt` | 방향 없는 같은 방문 필수·허용·분리 edge |
| `model/tables/AppointmentOperationalExceptions.kt` | 운영 예외 상태·원인·외부 결과 |
| `model/tables/AppointmentCommandIdempotencies.kt` | actor scope별 command 선점·결과 재생 |
| `repository/AppointmentCommitmentRepository.kt` | commitment/proposal/consent/item transaction primitive |
| `repository/AppointmentRepository.kt` | legacy mapper와 v2 nullable projection 조회·갱신 경계 |
| `repository/ResourceAllocationRepository.kt` | 정렬 잠금, overlap 재검증, bucket CAS, 교체 |
| `repository/TreatmentSpaceRepository.kt` | 병원 범위의 실제 공간과 capability 조회 |
| `repository/AppointmentPlanRevisionRepository.kt` | revision 저장·활성화·dirty-set 조회 |
| `repository/AppointmentOperationalExceptionRepository.kt` | 예외 append/ack/resolve |
| `service/PackageExecutionPlanner.kt` | 실행 BOM 검증·Plan treatment 변환 |
| `service/VisitGroupingPlanner.kt` | same/may/separate 제약과 항목별 시간을 방문 후보로 계산 |
| `service/ProposalHasher.kt` | proposal 구성의 canonical SHA-256 |
| `service/ProductVersionMigrationPlanner.kt` | BOM 전환표 검증과 미래 항목 새 revision 계산 |
| `service/PlanDirtySetResolver.kt` | 완료 사실 이후 `BLOCKING` 전이 경로의 증분 범위 |

### `appointment-event`

| 경로 | 책임 |
|---|---|
| `event/integration/PackageExecutionEvent.kt` | 허용된 schema의 실행 BOM DTO |
| `event/integration/ProductVersionMigrationApprovedEvent.kt` | 동의 증빙과 BOM 전환표 DTO |
| `event/integration/TreatmentFulfillmentEvent.kt` | 항목 완료·부분 이행·환불 취소 사실 DTO |
| `event/integration/VisitPlanningEventIngress.kt` | 1 MiB/depth 32/type allowlist/trust 검증 |
| `event/integration/VisitPlanningEventHandler.kt` | inbox/version/quarantine/Plan Revision transaction |
| `event/integration/VisitPlanningMetricsContract.kt` | 결과·gap·quarantine·migration metric |
| `event/commitment/AppointmentCommitmentEvents.kt` | proposal/confirm/change/exception outbox payload |

### `appointment-api`

| 경로 | 책임 |
|---|---|
| `commitment/AppointmentCommitmentCommand.kt` | actor 없는 application command와 stable result |
| `commitment/AppointmentCommitmentCommandService.kt` | 멱등성·CAS·allocation·outbox 원자 조정 |
| `commitment/AppointmentProposalService.kt` | Plan snapshot+정책+자원으로 bounded proposal 생성 |
| `commitment/AppointmentCommitmentQueryService.kt` | legacy/new 모델 구분과 scope-safe 조회 |
| `commitment/AppointmentCommitmentMetrics.kt` | latency/conflict/expiry/exception metric |
| `controller/PatientAppointmentRequestController.kt` | 고객 요청·proposal 수락/거부 |
| `controller/AdminAppointmentCommitmentController.kt` | 관리자 제안·승인·직접 확정·변경 제안 |
| `controller/AppointmentCommitmentQueryController.kt` | 고객·관리자 commitment 조회 |
| `dto/AppointmentCommitmentRequests.kt` | body에 actor/tenant/clinic/patient가 없는 command DTO |
| `dto/AppointmentCommitmentResponses.kt` | version/status/current proposal/reason code |
| `config/AppointmentCommitmentProperties.kt` | off-by-default, allowlist, ceiling, retry, expiry |
| `config/AppointmentCommitmentApiException.kt` | stable reason code와 HTTP status |
| `config/GlobalExceptionHandler.kt` | 개인정보 없는 v2 error mapping |
| `config/ServiceConfig.kt` | repository/service/consumer wiring |
| `config/DatabaseConfig.kt` | Flyway-off 테스트의 신규 Exposed table 등록 |

### schema, tests, docs

| 경로 | 책임 |
|---|---|
| `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V10__add_visit_commitment.sql` | 추가형 table/FK/index |
| `appointment-core/src/test/.../commitment/*Test.kt` | lifecycle/hash/grouping/migration 단위 테스트 |
| `appointment-core/src/test/.../repository/*Commitment*Test.kt` | H2 repository/rollback/concurrency |
| `appointment-event/src/test/.../integration/VisitPlanning*Test.kt` | trust/bounds/inbox/gap/quarantine/redrive |
| `appointment-api/src/test/.../commitment/*Test.kt` | application transaction/feature flag/metric |
| `appointment-api/src/test/.../controller/*Appointment*V2Test.kt` | 고객·관리자 HTTP/OpenAPI |
| `appointment-api/src/test/.../security/AppointmentCommitmentSecurityIntegrationTest.kt` | Gateway actor/scope 위조·우회 |
| `appointment-api/src/test/.../migration/*MigrationTest.kt` | 세 dialect V10 의미 검증 |
| `appointment-api/src/test/.../integration/VisitCommitmentDialectIntegrationTest.kt` | 실제 DB CAS/allocation parity |
| `appointment-api/src/gatling/java/.../VisitCommitmentSimulation.java` | 인기 자원 동시 확정과 proposal budget |
| `docs/api/visit-commitment.md` | caller 계약, 조건부 header, 예제 |
| `docs/runbooks/visit-commitment-rollout.md` | shadow/allowlist/rollback/redrive/alert |
| `README.md`, `README.ko.md` | 기능·flag·API 링크 동등성 |

## 3. 인수 기준 추적

| 설계 인수 기준 | 구현 Task와 증거 |
|---|---|
| 한 방문의 여러 Plan-linked item | Task 1, 2, 6 repository/API tests |
| 항목별 의료진·장비·진료 공간 점유 | Task 2, 6 allocation tests |
| 고객 요청은 가예약 | Task 6, 7 patient controller tests |
| 관리자 직접 확정의 정책·동의 | Task 6, 7 authorization/consent tests |
| 새 proposal 대기 중 기존 확정 보호 | Task 6 rollback tests |
| allocation과 confirmed proposal 원자 교체 | Task 2, 6 concurrency tests |
| 상품·정책·동의 snapshot | Task 1, 2 query assertions |
| 반복형 5회권 전개 | Task 1, 4 planner tests |
| 복합 패키지 관계 보존 | Task 1, 4 planner tests |
| N-of-M 선택 결과 보존 | Task 1, 4 planner tests |
| 구성 상품별 시간·자원 보존 | Task 1, 5 grouping tests |
| 방문 묶음·분리 제약 | Task 1, 5 grouping tests |
| 패키지 합계 시간이 항목 시간을 덮어쓰지 않음 | Task 1 grouping interval assertions |
| 구매 version 고정 | Task 4 event replay tests |
| 승인된 전환과 동일 Plan 새 revision | Task 8 migration tests |
| 완료 항목 보존·미래 항목 승계 | Task 8 dirty-set tests |
| 실제 일정 변경의 별도 예약 동의 | Task 6, 8 consent tests |
| 고객 거부 시 기존 예약과 운영 예외 | Task 6, 8 tests |
| `BLOCKING`만 전파 | Task 1, 8 graph tests |
| command/event 멱등성 | Task 2, 4, 6 race tests |
| 자원 충돌 없음 | Task 2, 6, 10 DB/Gatling proof |
| 계산 상한·latency | Task 5, 10 unit/performance proof |
| gap/poison/redrive | Task 4, 9 event tests/runbook |
| V10 shadow/rollback | Task 3, 9 migration/runbook |
| metric/alert/owner | Task 9 |
| H2/PostgreSQL/MySQL 동등성 | Task 3, 10 순차 실행 |
| legacy compatibility | Task 7 regression tests |
| 상세 한국어 KDoc | 모든 Task, Task 9 최종 audit |
| actor·가예약·승인·동의 OpenAPI | Task 7, 9 OpenAPI tests |

---

### Task 1: 핵심 계약과 순수 계산 규칙을 테스트로 고정

**복잡도:** XL
**의존성:** 승인된 설계
**쓰기 범위:** `appointment-core/model/{commitment,plan,operation}`,
`appointment-core/service`의 순수 함수와 단위 테스트

- [x] **RED:** `AppointmentCommitmentModelTest`,
  `PackageExecutionPlannerTest`, `VisitGroupingPlannerTest`,
  `ProductVersionMigrationPlannerTest`, `PlanDirtySetResolverTest`를 먼저 작성한다.
  반복 100회/전체 500개/edge 4,000개/slot 2,000개/proposal 20개 상한,
  cycle, N-of-M 부족, same/separate 충돌, 완료 항목 mapping 변경을 각각 실패시킨다.
- [x] 다음 최소 계약을 구현한다.

```kotlin
data class AppointmentProposalDraft(
    val appointmentId: Long,
    val revision: Long,
    val startsAt: Instant,
    val endsAt: Instant,
    val items: List<AppointmentItemDraft>,
    val allocations: List<ResourceAllocationDraft>,
    val policySnapshotId: Long,
    val supersedesProposalId: Long?,
)

data class PackageExecutionSnapshot(
    val packageProductId: String,
    val packageProductVersionId: String,
    val selectedComponentVersions: List<ComponentVersionRef>,
    val expandedTreatmentItems: List<ExecutionTreatment>,
    val executionDependencies: List<ExecutionDependency>,
    val visitGroupingConstraints: List<VisitGroupingConstraint>,
    val snapshotHash: String,
) : Serializable
```

- [x] `VisitGroupingPlanner`는 항목별 준비·진료·회복 구간을 보존하고,
  `MUST_SAME_VISIT` 연결요소를 먼저 묶은 뒤 `MUST_SEPARATE_VISIT`와 자원
  양립성을 검증한다. `MAY_SAME_VISIT`는 의미를 바꾸지 않는 최적화로만 사용한다.
- [x] `ProductVersionMigrationPlanner`는 모든 미완료 source가 정확히 한 번
  설명되는지 검증하고 완료 항목은 구 revision에 유지한다.
- [x] **GREEN:** 아래 명령의 대상 테스트가 모두 통과한다.

```bash
./gradlew :appointment-core:test --tests "*PackageExecutionPlannerTest" \
  --tests "*VisitGroupingPlannerTest" \
  --tests "*ProductVersionMigrationPlannerTest" \
  --tests "*PlanDirtySetResolverTest"
```

**예상:** 선택된 테스트 실패 0건.
**커밋:** `Model immutable visit and package execution contracts`

### Task 2: Exposed table과 caller-owned transaction primitive 구현

**복잡도:** XL
**의존성:** Task 1
**쓰기 범위:** 신규 core table/repository/record mapper와 repository tests

- [x] **RED:** `AppointmentCommitmentRepositoryTest`,
  `ResourceAllocationRepositoryTest`, `TreatmentSpaceRepositoryTest`,
  `AppointmentPlanRevisionRepositoryTest`,
  `AppointmentCommandIdempotencyRepositoryTest`에 unique/FK/CAS/rollback 경계를 쓴다.
- [x] repository 메서드는 `transaction {}`를 열지 않고 caller transaction을
  요구한다. insert/update receiver와 이름이 겹치는 값은 local 변수로 빼고
  deprecated `SqlExpressionBuilder.eq`를 사용하지 않는다.
- [x] 기존 `Appointments` row를 그대로 방문 identity로 사용하되 V10의
  `model_version=LEGACY|COMMITMENT_V2`로 경로를 분리한다. `COMMITMENT_V2`의
  미확정 row는 doctor/treatment/date/time projection이 `null`일 수 있고,
  확정 transaction만 정확한 proposal 값으로 채운다. legacy mapper와 date-range
  조회는 `LEGACY` 또는 완성된 v2 projection만 읽어 nullable 값을 강제 해제하지 않는다.
- [x] `ResourceAllocationRepository.replaceConfirmedAllocations(...)`는
  `tenant, clinic, resourceType, resourceId, bucketStartAt` 순으로 잠그고,
  교체 대상 allocation ID를 overlap 집계에서 제외한다.
- [x] `TreatmentSpaces`는 표시용 room type이 아니라 실제 점유 대상이다.
  `clinicId`, 안정적 space code, capability, nominal capacity,
  `bucketMinutes`를 저장하고 tenant/clinic 경계를 통과한 공간 참조를 거부한다.
- [x] command idempotency unique key는
  `(tenant_group_id, clinic_id, actor_scope_hash, idempotency_key)`이고 같은 key의
  다른 command hash를 거부한다.
- [x] 모든 table/record/public repository와 복잡한 속성에 한국어 KDoc을 작성한다.
  특히 `confirmedProposalId`, `proposalHash`, `planRevisionId`,
  `evidenceHash`, allocation status의 rollback 의미를 설명한다.
- [x] **GREEN:**

```bash
./gradlew :appointment-core:test --tests "*AppointmentCommitmentRepositoryTest" \
  --tests "*ResourceAllocationRepositoryTest" \
  --tests "*TreatmentSpaceRepositoryTest" \
  --tests "*AppointmentPlanRevisionRepositoryTest" \
  --tests "*AppointmentCommandIdempotencyRepositoryTest"
```

**예상:** H2 repository 테스트 실패 0건, transaction 밖 사용은 명시적으로 실패.
**커밋:** `Preserve commitment and allocation invariants transactionally`

### Task 3: Flyway V10 세 dialect 추가형 schema 확정

**복잡도:** XL
**의존성:** Task 2 table 계약
**쓰기 범위:** 세 V10 SQL, migration test/support, `DatabaseConfig`

- [x] **RED:** 세 migration test에 신규 table, FK, unique key, 아래 조회별 index,
  기존 row의 `model_version=LEGACY`, v2 nullable projection, Flyway checksum과
  Exposed column parity를 추가한다.
- [x] `V10__add_visit_commitment.sql`을 H2/PostgreSQL/MySQL 각각 작성한다.
  기존 `scheduling_*` 이름을 바꾸지 않고 nullable legacy FK와 추가형 table만
  도입한다. `scheduling_appointments`에는 default `LEGACY`인 `model_version`을
  추가하고 확정 projection column의 `NOT NULL`만 완화한다. 기존 row 값과
  legacy API의 non-null 입력 계약은 바꾸지 않는다. PostgreSQL 전용 최적화는
  공통 correctness를 대체하지 않는다.
- [x] Flyway를 운영 DDL 권위로 유지하고 `SchemaUtils`는 테스트의 누락 column
  탐지만 수행한다. 운영 시작 시 `SchemaUtils.create*`를 호출하지 않는다.
- [x] allocation overlap 조회는
  `(tenant_group_id, clinic_id, resource_type, resource_id, allocation_status, starts_at, ends_at)`,
  proposal current 조회는 `(commitment_id, revision DESC)`, audit 조회는
  `(tenant_group_id, clinic_id, aggregate_id, occurred_at DESC)` 순서를 기준으로
  dialect별 index DDL을 고정한다. migration test는 index column 순서를 단언한다.
- [x] Testcontainers는 기존 `Containers` singleton을 재사용하고 순차 실행한다.

```bash
./gradlew :appointment-api:test --tests "*FlywayMigrationTest"
./gradlew :appointment-api:test --tests "*FlywayPostgreSQLMigrationTest"
./gradlew :appointment-api:test --tests "*FlywayMySQLMigrationTest"
```

**예상:** H2 후 PostgreSQL 후 MySQL 순으로 V1→V10 및 clean migration 성공.
**검증:** H2, PostgreSQL, MySQL을 위 순서로 각각 실행해 기존 V9 row 보존,
`LEGACY` backfill, nullable v2 projection, FK·unique·index 순서, Flyway checksum,
Exposed 추가 DDL drift 없음과 빈 DB V1→V10 적용을 확인했다. 이어서
`:appointment-api:build`에서 281개 테스트가 통과했다.
**rollback:** table을 drop하지 않는다. feature flag를 끄고 legacy 경로로 복귀한다.
**커밋:** `Expand the schema for versioned visit commitments`

### Task 4: 실행 BOM event ingest와 최초 Plan Revision 생성

**복잡도:** XL
**의존성:** Task 1~3
**쓰기 범위:** `appointment-event` 신규 event/ingress/handler/tests,
기존 purchase handler의 호환 확장

- [x] **RED:** 반복형 5회권, 복합형, N-of-M, provenance 불일치, payload 1 MiB,
  depth 32 초과, unknown schema/type, replay, version gap, same-version 다른 hash를
  검증한다.
- [x] 기존 `TrustedSchedulingEventEnvelope`, `SchedulingEventTrustVerifier`,
  `SchedulingInboxEvents`, `SchedulingQuarantineRepository`를 재사용한다.
  class-name polymorphism과 default typing은 허용하지 않는다.
- [x] 단일 상품은 quantity 1 실행 snapshot으로 정규화해 패키지/단일 상품의
  이후 경로를 통합한다.
- [x] inbox, 최초 Plan Revision, treatment/dependency, outbox, idempotency 결과를
  한 transaction에 저장한다. `SHADOW`는 결과 차이만 측정하고 domain row를 쓰지 않는다.
- [x] event 로그에 payload·patient reference·동의 원문을 남기지 않는다.

```bash
./gradlew :appointment-event:test --tests "*VisitPlanningEventIngressTest" \
  --tests "*VisitPlanningEventHandlerTest"
```

**예상:** 신뢰 실패는 domain transaction 0회, replay는 row/outbox 증가 0건.
**검증:** ingress/handler 표적 테스트 17개, `:appointment-event:build`의 전체
77개 테스트와 Kover, core planner/revision repository 회귀 8개, H2 Flyway
회귀 1개가 통과했다. 전체 diff와 Kotlin 최종 체크리스트를 점검해 P0=0,
P1=0을 확인했고 `git diff --check`도 통과했다.
**커밋:** `Ingest immutable package execution snapshots safely`

### Task 5: bounded proposal 생성과 방문 후보 계산

**복잡도:** XL
**의존성:** Task 1~4
**쓰기 범위:** `AppointmentProposalService`, planner integration/tests

- [x] **RED:** 고객 희망일 우선, 희망일 미입력 시 상품 규칙 N일 이내, 실제 완료
  시점 기준 후속 회차, 부분 이행 재방문, 항목별 resource capability, 후보/기간
  상한과 stable reason code를 검증한다.
- [x] 현재 유효 policy와 resource capability를 요청 입력으로 받아 제안마다
  `policySnapshotId`와 canonical `proposalHash`를 고정한다.
- [x] dirty-set 재계산은 완료된 item, 기존 확정 proposal과 영향 없는 미래 item을
  변경하지 않는다.
- [x] 일반 Plan p95 1초/p99 3초, 최대 범위 p95 5초를 재현 가능한 integration
  timing test와 Gatling 시나리오 입력으로 남긴다. 고정 seed로 일반
  50 item/200 edge/90일과 최대 500 item/4,000 edge/365일 dataset을 만들고,
  warm-up 20회 뒤 측정 100회를 실행한다. percentile 표와 raw Gatling 결과를
  `appointment-api/build/reports/gatling/visit-commitment/`에 보존하며 표본 또는
  percentile이 누락되면 검증 실패로 처리한다.

```bash
./gradlew :appointment-api:test \
  --tests "*AppointmentProposalServiceTest" \
  --tests "*AppointmentProposalServicePerformanceTest" \
  --no-build-cache --rerun-tasks
./gradlew :appointment-api:gatlingRun \
  --simulation io.bluetape4k.clinic.appointment.api.VisitCommitmentProposalSimulation \
  --no-build-cache
./gradlew :appointment-api:build --no-build-cache --rerun-tasks
```

**예상:** 상한 초과는 부분 proposal 없이 `PLAN_LIMIT_EXCEEDED`.
**검증:** 표적 테스트 13개와 `:appointment-api:build`의 294개 테스트가
통과했고 기존 2개 pending 외 실패는 없다. 실제 Gatling HTTP 실행은
normal/maximum warm-up 각 20회와 측정 각 100회, 총 240 요청을 모두 성공했다.
4,000개 관계는 완료된 선행 항목에서 미래 방문으로 향하는 `BLOCKING` 간격
검사를 포함한다. Gatling 응답시간은 normal p95 2 ms, p99 3 ms, maximum
p95 6 ms였고, 동일 고정 dataset의 순수 계산 timing은 normal p95 0.876 ms/
p99 0.910 ms, maximum p95 5.631 ms였다. 실제 `simulation.log`, 400개 측정 sample의
`unit-timing.tsv`, percentile 표를
`appointment-api/build/reports/gatling/visit-commitment/`에 보존했다.
**커밋:** `Generate bounded proposals from future plan work`

### Task 6: commitment command와 원자적 자원 교체

**복잡도:** XL
**의존성:** Task 2, 5
**쓰기 범위:** API commitment command service/core repository integration/tests

- [x] **RED:** 고객 요청, 관리자 승인, 직접 확정, accept/decline, 변경 제안,
  만료, 중복 confirm, 서로 다른 proposal 동시 accept, 새 allocation 실패를 쓴다.
- [x] 하나의 transaction 안에서 다음 순서를 지킨다.

```text
idempotency 선점 → expected version 검증 → proposal/동의 재검증
→ 정렬된 자원 잠금·재검증 → 새 allocation 생성
→ confirmedProposalId CAS → 기존 allocation 해제
→ legacy projection 갱신 → 이력/outbox → idempotency 결과
```

- [x] DB deadlock/serialization failure만 최대 3회 backoff+jitter로 재시도하고
  expected version 충돌은 `VERSION_CONFLICT`로 즉시 반환한다.
- [x] 새 점유 실패, 고객 거부, proposal 만료 시 기존 confirmed proposal과
  allocation이 그대로 남는지 DB row로 단언한다.

```bash
./gradlew :appointment-api:test --tests "*AppointmentCommitmentCommandServiceTest" \
  --tests "*VisitCommitmentConcurrencyTest"
```

**예상:** 충돌하는 두 확정 중 최대 하나만 성공, 기존 예약 손실 0건.
**검증:** command service 22개, PostgreSQL 동시성 5개, core repository 대상
13개가 실패 없이 통과했다. H2→PostgreSQL→MySQL Flyway 검증은 각 1개씩
통과했고 `:appointment-api:build`는 323개 테스트, 실패 0, 오류 0, 기존
skipped 2개로 성공했다. Step 6-R의 독립 7-Tier 결과와 후속 경계는
`docs/review/2026-07-29-issue-184-task6-step-6r-code-review.md`에 기록한다.
**커밋:** `Confirm exact proposals without sacrificing existing bookings`

### Task 7: Gateway actor 기반 고객·관리자 API와 legacy 보호

**복잡도:** L
**의존성:** Task 6
**쓰기 범위:** v2 controller/DTO/error/security/OpenAPI tests, legacy guard

- [x] **RED:** body의 actor/tenant/clinic/patient 위조 필드 부재, patient subject
  일치, 관리자 clinic 범위, Gateway envelope 없음, 서비스 principal 불일치를 검증한다.
- [x] controller는 `ActorContextResolver`로만 actor를 얻는다. 고객과 관리자
  controller를 분리하고 `Idempotency-Key`, `If-None-Match: *`, `If-Match`를
  command에 전달한다.
- [x] `CommitmentCommandContext`, `DirectConfirmationPolicyDecision`,
  `ConfirmedAppointmentProjectionTarget`은 request body에서 받지 않는다. Gateway
  principal과 tenant·clinic 범위의 유효 정책 snapshot 및 자원 inventory를
  server-side resolver로 조회해 조립하고, body가 정책 방식·허용 증빙·약관 hash·
  담당자 mapping을 위조할 수 없음을 negative test로 고정한다.
- [x] `actorAuditRef`, 동의 `actorRef/evidenceAuthority/evidenceId`는 원문 개인정보나
  token이 아닌 제한된 opaque reference만 허용한다. 전역 증빙 ID를 유지한다면 원본
  authority가 tenant namespace를 포함한 추측 불가능 ID를 발행하도록 검증하고,
  중복은 raw unique violation이 아닌 안정적인 application 오류로 변환한다.
- [x] 다음 endpoint와 status를 그대로 구현한다.

| Actor | Method / path | 성공 |
|---|---|---:|
| 고객 | `POST /api/v2/appointment-requests` | 202 |
| 관리자 | `POST /api/v2/admin/appointments` | 201 |
| 관리자 | `POST /api/v2/appointments/{id}/approve` | 200 |
| 고객 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/accept` | 200 |
| 고객 | `POST /api/v2/appointments/{id}/proposals/{proposalId}/decline` | 200 |
| 관리자 | `POST /api/v2/appointments/{id}/confirm` | 200 |
| 관리자 | `POST /api/v2/appointments/{id}/change-proposals` | 202 |
| 고객·관리자 | `GET /api/v2/appointments/{id}/commitment` | 200 |

- [x] controller와 application service 경계를 다음 signature로 고정한다.
  `expectedVersion`은 `If-Match`, 생성 조건은 `If-None-Match`, 멱등성은
  `Idempotency-Key`에서만 만들며 request body에 복제하지 않는다.

```kotlin
fun requestAppointment(
    actor: ActorContext,
    idempotencyKey: String,
    createOnly: Boolean,
    request: CreateAppointmentRequestV2,
): AppointmentProposalResponse

fun approveProposal(
    actor: ActorContext,
    appointmentId: Long,
    expectedVersion: Long,
    idempotencyKey: String,
    request: ApproveProposalRequest,
): AppointmentCommitmentResponse

fun decideProposal(
    actor: ActorContext,
    appointmentId: Long,
    proposalId: Long,
    expectedVersion: Long,
    idempotencyKey: String,
    request: ProposalDecisionRequest,
): AppointmentCommitmentResponse
```

  나머지 service method는
  `directCreate(actor, idempotencyKey, createOnly, DirectCreateAppointmentRequest)`,
  `directConfirm(actor, appointmentId, expectedVersion, idempotencyKey, DirectConfirmRequest)`,
  `createChangeProposal(actor, appointmentId, expectedVersion, idempotencyKey, CreateChangeProposalRequest)`,
  `declineProposal(actor, appointmentId, proposalId, expectedVersion, idempotencyKey, DeclineProposalRequest)`,
  `query(actor, appointmentId)`로 고정한다. `approveProposal`은 병원 승인,
  `decideProposal`은 고객 accept에 사용한다. 각 mutation response의 `version`을
  `ETag`로 내보내고 다음 mutation의 `If-Match` 예제를 OpenAPI에 포함한다.

- [x] `AppointmentCommitmentApiException`은 최소
  `SCOPE_MISMATCH`, `SCOPE_FORBIDDEN`, `CONSENT_REQUIRED`,
  `PROPOSAL_EXPIRED`, `PROPOSAL_NOT_CURRENT`, `RESOURCE_CONFLICT`,
  `VERSION_CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `DIRECT_CONFIRM_NOT_ALLOWED`,
  `PLAN_LIMIT_EXCEEDED`, `PREDECESSOR_NOT_COMPLETED`,
  `NEW_APPOINTMENT_API_REQUIRED`를 고정 HTTP status/retryability/caller action에
  매핑한다. event 전용 code는 public parser 상세 없이 redacted 상태로만 노출한다.
- [x] 기존 `POST /appointments`는 legacy row만 만들며 commitment가 있는 row의
  legacy update/status는 `NEW_APPOINTMENT_API_REQUIRED`로 거부한다.
- [x] legacy repository의 단건·기간 조회는 `model_version`과 projection 완성
  조건을 적용해 미확정 v2 row를 legacy `AppointmentRecord`로 mapping하지 않는다.
  v2 조회는 commitment query model을 사용하고 기존 nullable column을 직접 노출하지 않는다.
- [x] OpenAPI에 actor, 가예약, 승인, 동의, 만료, 충돌 예제를 고정한다.

```bash
./gradlew :appointment-api:test --tests "*AppointmentRequestV2Test" \
  --tests "*AdminAppointmentV2Test" \
  --tests "*AppointmentCommitmentSecurityIntegrationTest" \
  --tests "*AppointmentControllerTest"
```

**검증:** 고객·관리자·보안·오류·legacy 표적 API 테스트 95개, 오류 해석
테스트 5개, core commitment·closure 표적 테스트 34개, 대형 패키지 item
statement-count 테스트 4개가 통과했다. `:appointment-api:build`는 전체 358개
중 356개 통과·기존 2개 skipped, `:appointment-core:build`는 451개 전부
통과했다. Step 6-R의 최종 7-Tier는
`docs/review/2026-07-29-issue-184-task7-step-6r-code-review.md`에 기록한다.
**커밋:** `Expose actor-scoped provisional and confirmation APIs`

### Task 8: 상품 version 전환·완료·부분 이행·환불 event

**복잡도:** XL
**의존성:** Task 4, 6
**쓰기 범위:** 외부 event DTO/handler, migration/dirty-set/exception tests

- [ ] **RED:** 유효 전환, 동의 hash 불일치, from-version 불일치, mapping
  누락/중복/cycle, 완료 항목 변경 시도, replay, 부분 이행, 장비 고장 잔여 item,
  환불 취소와 `BLOCKING/NON_BLOCKING` 전파를 검증한다.
- [ ] 유효 전환은 동일 Plan에 새 immutable revision을 추가해 즉시 활성화하되
  확정 appointment는 바꾸지 않는다. 일정 변경은 별도 proposal을 만든다.
- [ ] 고객이 새 일정을 거부하면 기존 예약을 유지하고
  `CUSTOMER_DECLINED_RESCHEDULE` 운영 예외와 CRM outbox를 추가한다.
- [ ] 격리된 migration은 활성 revision을 바꾸지 않고 redacted
  `ProductVersionMigrationRejected`를 발행한다.

```bash
./gradlew :appointment-event:test --tests "*ProductVersionMigrationHandlerTest" \
  --tests "*TreatmentFulfillmentHandlerTest"
./gradlew :appointment-core:test --tests "*PlanDirtySetResolverTest"
```

**예상:** 완료 항목 provenance 변경 0건, 독립 미래 항목은 계속 예약 가능.
**커밋:** `Revise only future work from authoritative external facts`

### Task 9: 운영 제어·보존·문서·KDoc 완성

**복잡도:** L
**의존성:** Task 3~8
**쓰기 범위:** properties/wiring/metrics/cleanup/docs/README

- [ ] `AppointmentCommitmentProperties`에 `OFF/SHADOW/WRITE`, clinic allowlist,
  ceiling, proposal TTL, retry를 immutable 설정으로 두고 기본은 `OFF`로 한다.
- [ ] low-cardinality metric을 구현한다: proposal latency/expiry, allocation
  conflict, outbox lag, quarantine count/age, migration rejection, operational
  exception ack latency. patient/product/event ID는 tag로 쓰지 않는다.
- [ ] inbox/idempotency 30일, delivered outbox 7일, resolved quarantine 90일
  정리를 구현하되 미전달·미해결·legal hold는 제외한다.
- [ ] fake `Clock`을 사용하는 `VisitCommitmentRetentionServiceTest`로 경계 직전/직후,
  legal hold, 미전달 outbox, 미해결 quarantine, poison record와 tenant별 batch
  상한을 검증한다. 삭제된 ID와 보존된 ID를 각각 단언한다.
- [ ] runbook에 shadow diff, allowlist 확대, alert 임계값, gap 복원,
  5회 poison 중단, 권한 있는 redrive, PostgreSQL backup/복구와 feature flag
  rollback을 적는다.
- [ ] runbook의 rollback 분기를 명확히 고정한다. `WRITE` 중 생성된
  `COMMITMENT_V2` row는 legacy row로 변환하거나 legacy API로 변경하지 않고,
  신규 유입만 `OFF`로 차단한 뒤 v2 query/mutation 경로를 유지한다. `SHADOW`
  consumer는 필요하면 유지해 gap을 관찰하고, schema/table은 삭제하지 않는다.
- [ ] 운영 소유권과 alert route를 표로 고정한다. 예약팀은 API/allocation/inbox/
  outbox 및 on-call, 상품·구매팀은 replay authority, CRM은 운영 예외 접수와
  15분 ACK SLA를 소유한다. outbox lag 5분, oldest quarantine 24시간,
  quarantine rate 1%, allocation conflict 기준선 3배, migration rejection 1건,
  CRM ACK 15분을 alert rule로 만들고 dashboard/runbook 링크를 테스트한다.
  redrive는 예약 운영 관리자만 승인하며 actor, reason, before/after status,
  original event/inbox key를 append-only audit에 남긴다.
- [ ] 신규 public/업무 규칙형 internal 선언의 한국어 KDoc을 전수 점검한다.
  `README.md`/`README.ko.md`, OpenAPI, `docs/api/visit-commitment.md`의 계약을 맞춘다.

```bash
./gradlew :appointment-api:test --tests "*AppointmentCommitmentPropertiesTest" \
  --tests "*AppointmentCommitmentMetricsTest" \
  --tests "*VisitCommitmentRetentionServiceTest" \
  --tests "*AppointmentCommitmentOpenApiTest"
git diff --check
```

**예상:** 설정/metric/OpenAPI 테스트와 locale parity 검사 통과.
**커밋:** `Make visit commitment rollout observable and reversible`

### Task 10: 세 DB·성능·전체 회귀 검증

**복잡도:** XL
**의존성:** Task 1~9
**쓰기 범위:** 테스트/성능 시나리오와 검증 문서만

- [ ] 작은 단위→module→다중 DB 순으로 실행한다. container 명령은 병렬화하지 않는다.

```bash
./gradlew :appointment-core:test
./gradlew :appointment-event:test
./gradlew :appointment-api:test
./gradlew :appointment-core:compileKotlin :appointment-event:compileKotlin :appointment-api:compileKotlin
./gradlew :appointment-api:test --tests "*VisitCommitmentDialectIntegrationTest"
./gradlew :appointment-api:gatlingRun-io.bluetape4k.clinic.appointment.api.VisitCommitmentSimulation
./gradlew :appointment-core:build :appointment-event:build :appointment-api:build
git diff --check
```

- [ ] PostgreSQL 기준으로 인기 자원 100개 동시 확정에서 중복 점유 0,
  미복구 deadlock 0, p95 2초 이하를 증명한다.
- [ ] 일반 Plan/최대 Plan proposal과 dirty-set 재계산의 p95/p99를 기록한다.
  목표를 못 맞추면 동기 범위를 임의 완화하지 말고 비동기 planning 설계로
  Step 2에 되돌아간다.
- [ ] PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`에서 allocation overlap,
  current proposal, Plan dirty-set 대표 조회가 의도한 index를 사용하고,
  10만 allocation seed에서 accidental full scan이 없는지 결과를
  `docs/review/2026-07-29-issue-184-performance-evidence.md`에 남긴다.
- [ ] Gatling은 전담 resource overlap, capacity bucket 사용량, 의료진+장비+공간
  다중 잠금, 같은 idempotency key replay를 한 혼합 부하에 포함한다.
- [ ] H2 성공만으로 완료를 주장하지 않고 PostgreSQL/MySQL 결과를 별도 기록한다.
- [ ] Kover는 module test 이후 report-only로 생성하고 누락 artifact를 성공으로
  처리하지 않는다.

**예상:** 세 DB 의미 동등, 전체 build 성공, known error 0.
**커밋:** `Prove visit commitments across supported database backends`

## 4. Step 3-P 사전 위험 예측

| 위험 | 조기 신호 | 계획상 완화 | rollback/rerun 지점 |
|---|---|---|---|
| 확정 교체 중 기존 예약 유실 | allocation은 해제됐지만 CAS 실패 | 새 점유→CAS→기존 해제를 한 transaction에서 수행하고 실패 row 단언 | Task 6 transaction test부터 재실행 |
| 다중 자원 deadlock | dialect test의 deadlock/serialization 증가 | canonical lock order와 제한된 3회 retry | Task 2 repository, Task 6 concurrency |
| capacity 이중 사용 | bucket usage와 활성 allocation 합 불일치 | bucket row lock/CAS 후 overlap 재검증 | 신규 API flag OFF, reconciliation |
| BOM 폭발·긴 latency | item/edge/candidate 상한 근접 | 조기 상한 검증, dirty-set, proposal 수 제한 | Task 5에서 비동기 설계로 재승인 |
| 잘못된 상품 전환 | completed provenance 변화·from-version 불일치 | authority/동의/mapping 전체 검증 후 단일 revision transaction | active revision 불변, quarantine/redrive |
| event 역직렬화 공격 | 큰 payload/depth/unknown subtype | mapping 전 bounds/allowlist 검증과 격리 | consumer OFF/SHADOW |
| Gateway 우회·scope 상승 | body identity 또는 unsigned header 사용 | 기존 JWT principal/ActorContext만 사용, 내부 ingress 분리 | v2 route OFF |
| legacy/new dual write drift | projection diff 증가 | new service만 projection 갱신, commitment row legacy write 차단 | legacy API 유지, 신규 row는 v2 전용 |
| migration rollback 오판 | V10 row가 생긴 뒤 table drop 요구 | expand-only, DDL 미삭제, flag rollback | PostgreSQL backup drill |
| 고카디널리티 metric/개인정보 | patient/event/product ID tag | tenant/clinic/result/reason만 tag, 민감 값 log 금지 | metric binding 제거 후 Task 9 재검증 |

## 5. 구현 중 공통 완료 규칙

- 모든 behavior task는 실패 테스트를 먼저 확인한 뒤 최소 구현으로 GREEN을 만든다.
- 각 Task는 지정 write scope 밖의 변경을 만들지 않는다. 공유 파일 충돌 시 멈춰
  leader가 순서를 재조정한다.
- production Exposed 호출은 caller-owned `transaction {}` 안에서만 수행한다.
- 신규 data class는 `Serializable`과 `serialVersionUID`를 갖고, 생성 경로가
  검증을 우회하지 못하게 한다.
- production `!!`, `println`, broad suspend `runCatching`, raw duplicate
  Testcontainers를 추가하지 않는다.
- operational component는 stable low-cardinality context로 lifecycle/failure를
  기록하며 token, patient reference, consent 원문, raw payload를 로그하지 않는다.
- public 및 복잡한 internal Kotlin 선언은 한국어 KDoc으로 불변조건, 실패 조건,
  상태 전이와 필요한 예제를 설명한다.
- README 두 locale, OpenAPI, API 문서와 코드 식별자를 최종 source 기준으로 맞춘다.
- module 추가·dependency 추가·settings/catalog 변경은 없다. 이 전제가 깨지면
  repository hazard와 dependency 승인 단계로 되돌아간다.
- 작업 항목, 테스트 또는 type이 결정되지 않은 상태는 Step 4 진입을 차단한다.

## 6. 최종 DoD

- [ ] 설계 인수 기준 29개가 Task와 자동화 증거에 모두 연결됨
- [ ] 단위·repository·API·보안·event·동시성·성능 테스트 통과
- [ ] H2→PostgreSQL→MySQL 순차 검증과 PostgreSQL 별도 증거
- [ ] migration expand/shadow/allowlist/rollback drill 통과
- [ ] 신규 Kotlin KDoc, README locale parity, OpenAPI, runbook 일치
- [ ] `git diff --check`, Kotlin diagnostics, module build 통과
- [ ] 구현 후 6-R P0=0/P1=0
- [ ] PR 후 7-R, CI, live review thread 통과
- [ ] merge 직전 최신 head에 대한 별도 사용자 승인

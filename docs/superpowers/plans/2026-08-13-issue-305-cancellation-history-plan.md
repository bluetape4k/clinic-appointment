# 환자 취소 이력 조회 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #305의 환자 전용 취소 이력 API와 Angular timeline을 V27/V28 데이터 호환성, tenant·patient 격리, keyset cursor/ETag, 접근성 계약까지 포함해 모듈별로 구현한다.

**Architecture:** `appointment-core`가 nullable V28 snapshot 필드와 환자 범위 조회 record를 소유하고, `appointment-api`가 tenant-only authorization, migration, cursor/reference/ETag codec, bounded retry와 HTTP 오류를 조립한다. `frontend/appointment-frontend`는 `{cursor, limit}`만 공개하는 typed client와 session-bound facade cache/state machine을 소유하며 서버의 label registry를 재사용한다. `angular.json`은 범위에서 제외한다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed v1, Flyway(H2/MySQL/PostgreSQL), JUnit 5/Kluent/MockK, Angular 22, TypeScript 6, Vitest/Angular unit-test builder.

---

## 작업 전 공통 규칙

- 기준 브랜치와 현재 설계 사양을 확인한다. `frontend/appointment-frontend/angular.json`은 읽기·수정·커밋하지 않는다.
- 모든 Exposed query/insert는 `transaction {}` 또는 service가 연 새 read-only transaction 안에서 실행한다.
- 기존 V27을 덮어쓰지 않고 V28을 추가한다. 세 dialect migration과 migration support test를 같은 변경에서 갱신한다.
- 공개 API에는 raw `appointmentId`, patient fingerprint, cursor plaintext, ETag/body cache 인자를 노출하지 않는다.
- 각 task는 먼저 해당 테스트만 작성하고 아래 명령으로 RED를 확인한 후 구현한다.
- 각 module task 완료 뒤 해당 module 명령을 실행하고 Lore commit protocol을 따른다.

## 실행 상태 요약

| 모듈/단계 | 상태 | 증거/남은 경계 |
|---|---|---|
| `appointment-core` schema/read model | 완료 | 신규 repository/schema targeted 2/2 통과; PostgreSQL Testcontainers는 Colima socket 오류로 미실행 |
| `appointment-api` migration/snapshot/codec/service/controller | 구현 완료·운영 gate 미완료 | V28 expand·V29 dialect index·V30 checkpoint/backfill runner와 writer fence 구현; 실제 dialect smoke/preflight artifact는 P1 |
| `frontend/appointment-frontend` portal/client/component | 구현 완료 | 39개 파일/266개 테스트, `npm run build` 성공 |
| 7-tier 및 `bluetape-kotlin-patterns` 검토 | 재검토 필요 | 구현 기준 V28 운영 migration P1로 BLOCK; targeted TypeScript 검토 포함 |
| 전체 모듈 suite | 관찰 | 병렬 실행은 702개 중 181개 실패했으나, 단일 스레드 재실행은 702개 중 2개만 Colima Docker socket mount 환경 오류로 실패 |
| protected backend/공유 registry/production 운영 gate | 대기 | HTTP smoke, EXPLAIN/성능, ACL·backup·canary·SLO 외부 증거 필요 |
| `frontend/appointment-frontend/angular.json` | 제외 | 이번 작업에서 읽기·수정·커밋하지 않음 |

## 파일 매핑

### `appointment-core`

- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentCancellationDetails.kt`
  - `fromCommitmentStatus` nullable enum column, `patientScopeFingerprint` nullable varchar column, patient-scope/occurredAt/id index를 추가한다.
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/dto/PatientCancellationHistoryRecords.kt`
  - API를 참조하지 않는 nullable snapshot/detail/page 내부 record와 8-row metadata ambiguity 정책을 정의한다.
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentRepository.kt`
  - tenant + patient fingerprint + keyset boundary를 한 query로 시작하는 page repository port를 추가한다.
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentCancellationHistoryRepository.kt`
  - 최대 3 select, `limit+1`, `occurredAt DESC, id DESC`, fixed metadata batch mapper를 구현한다.
- Modify: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/model/tables/TableSchemaTest.kt`
  - cancellation details table을 schema fixture에 포함한다.
- Create: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/repository/AppointmentCancellationHistoryRepositoryTest.kt`
  - tenant/patient 격리, keyset 경계, legacy null, sparse patient, metadata ambiguity/query bound를 검증한다.

### `appointment-api`

- Create: `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V28__add_patient_cancellation_history_scope.sql`, `V29__add_patient_cancellation_history_scope_index.sql`, `V30__add_patient_cancellation_history_backfill_checkpoint.sql`
  - nullable expand, dialect별 online index, 비식별 durable checkpoint를 단계별로 추가한다.
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentCancellationMigrationTestSupport.kt`
  - V27→V28 column/index/backfill preflight와 old-row null 계약을 검증한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/PatientCancellationHistoryDtos.kt`
  - patient response entry/page, sanitized error, nullable display fields와 label을 정의한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientCancellationHistoryService.kt`
  - actor/tenant/fingerprint 검증, cursor decode/anchor conflict, repository page, ETag, backend-only transient retry를 구현한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientHistoryCursorCodec.kt`
  - strict outer grammar, AES-GCM payload, issuedAt/TTL/bucket, key rotation과 shared registry port를 구현한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientHistoryReferenceCodec.kt`
  - domain-separated HMAC opaque appointmentRef를 구현한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientHistoryEtagCodec.kt`
  - length-prefixed UTF-8/NFC canonical bytes와 SHA-256 strong ETag를 구현한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientHistoryTokenRegistry.kt`
  - linearizable shared-store port, `putIfAbsent`, TTL/no-early-eviction, readiness/failure classification을 정의한다.
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/PatientCancellationHistoryController.kt`
  - `/api/{tenantCode}/patient/appointments/cancellation-history` route, `clinicId=null` actor resolve, 200/304/400/401/403/404/409/503/500 headers를 조립한다.
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/GlobalExceptionHandler.kt`, `SecurityConfig.kt`, `TenantContextFilter.kt`, `SecurityErrorResponseWriter.kt`
  - 새 route와 sanitized application/security envelope를 등록한다.
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandService.kt`
  - CAS 직전 status와 patient fingerprint snapshot을 V28 cancellation detail에 기록한다.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientHistoryCursorCodecTest.kt`
  - grammar, tamper, TTL, key rotation, registry lookup order, missing-entry/503, capacity/read failure를 검증한다.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/PatientCancellationHistoryServiceTest.kt`
  - tenant/patient negative, keyset, retry deadline, ETag/304, response-size, metric/log redaction을 검증한다.
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/PatientCancellationHistoryControllerTest.kt`
  - clinic-less PATIENT JWT, full HTTP error/header contract와 route-preserving readiness 503을 검증한다.
- Modify: existing migration/command/OpenAPI tests to include V28 and `fromCommitmentStatus`.

### `frontend/appointment-frontend`

- Modify: `src/app/core/api/portal-api.models.ts`
  - `PatientCancellationHistoryEntry/Page`, typed error union, public `PatientHistoryQuery`를 추가한다.
- Modify: `src/app/core/api/portal-api-client.ts`
  - public method는 `{cursor, limit}`만 받고 response generation/ETag header를 private response metadata로 전달한다.
- Create: `src/app/features/patient-portal/patient-cancellation-history.component.ts`
  - standalone `<ol>` timeline, loading/empty/error/load-more/exhausted/a11y contracts를 구현한다.
- Create: `src/app/features/patient-portal/patient-cancellation-history.component.spec.ts`
  - null fallback, Korean labels, role/live region, 320px keyboard/focus and UTC formatter를 검증한다.
- Modify: `src/app/features/patient-portal/appointment-commitment.facade.ts`
  - private tuple cache, `RequestEpoch`, state union, one-shot recovery, 401/tenant purge, stale response discard를 추가한다.
- Modify: `src/app/features/patient-portal/pages/patient-appointments-page.component.ts`
  - current appointment 아래 history component를 연결하고 tenant/session lifecycle을 전달한다.
- Modify: corresponding facade/page specs
  - A→B purge, logout/401 race, 304 cache misuse, retry result, duplicate append를 검증한다.

---

## Task 1: core schema/read model RED

**Files:** 위 `appointment-core` mapping의 table, record, repository, tests.

- [ ] Step 1: `AppointmentCancellationHistoryRepositoryTest`에 tenant/patient 격리와 keyset 경계 RED를 작성한다.

```kotlin
@Test
fun `patient scope와 boundary가 다른 취소 detail은 page에서 제외한다`() {
    val page = repository.findPage(
        tenantGroupId = tenantId,
        patientScopeFingerprint = PATIENT_A,
        boundary = CancellationHistoryBoundary(Instant.parse("2026-08-10T03:00:00Z"), 99L),
        limit = 2,
    )

    page.entries.map { it.detailId } shouldBe listOf(7L, 5L)
    page.hasNext shouldBe true
    page.entries.none { it.patientScopeFingerprint != PATIENT_A } shouldBe true
}
```

- [ ] Step 2: `./gradlew :appointment-core:test --tests '*AppointmentCancellationHistoryRepositoryTest'`를 실행해 새 type/table/repository 부재로 실패함을 확인한다.
- [ ] Step 3: table V28 nullable fields/index와 `PatientCancellationHistoryRecords`의 immutable record를 추가한다. query는 `tenant_group_id + patient_scope_fingerprint`를 선두로 하고 `(occurred_at,id)` keyset을 적용한다.
- [ ] Step 4: repository 구현에서 page select 1회, metadata batch select 1회, optional reference select 1회 이내로 제한하고 detail당 distinct metadata가 1개가 아니면 nullable로 반환한다.
- [ ] Step 5: core targeted test와 `./gradlew :appointment-core:compileKotlin`을 실행해 GREEN을 확인한다.
- [ ] Step 6: core 변경만 Lore commit한다.

## Task 2: API migration과 cancellation snapshot RED

**Files:** V28 세 dialect migration/support tests, `AppointmentCommitmentCommandService`, command tests.

- [ ] Step 1: migration test에 `from_commitment_status`, `patient_scope_fingerprint`, patient-scope index와 V27 null assertion을 추가한다.
- [ ] Step 2: `AppointmentCommitmentCommandServiceTest`에 CAS 직전 status/fingerprint insert RED를 추가한다.

```kotlin
detail[AppointmentCancellationDetails.fromCommitmentStatus] shouldBeEqualTo AppointmentCommitmentStatus.CONFIRMED
detail[AppointmentCancellationDetails.patientScopeFingerprint] shouldBeEqualTo PATIENT_FINGERPRINT
```

- [ ] Step 3: `./gradlew :appointment-api:test --tests '*AppointmentCancellationMigration*' --tests '*AppointmentCommitmentCommandServiceTest*'`를 실행해 V28 column/insert 부재 실패를 확인한다.
- [ ] Step 4: V28 SQL을 세 dialect에 추가하고 migration support를 V27→V28 target sequence로 갱신한다.
- [ ] Step 5: command transaction에서 CAS 직전 `commitment.status`와 appointment fingerprint를 snapshot하고 detail insert에만 기록한다. 기존 notification/audit payload는 변경하지 않는다.
- [ ] Step 6: migration/command targeted test와 `./gradlew :appointment-api:compileKotlin`을 실행한다.
- [ ] Step 7: API snapshot 변경만 Lore commit한다.

## Task 3: API codec/registry RED→GREEN

**Files:** cursor/reference/ETag codec, token registry, service tests.

- [ ] Step 1: codec test에 valid vector, segment/padding/length rejection, tamper, TTL, key rotation, deterministic same-boundary reuse를 추가한다.
- [ ] Step 2: registry failure test에 missing-entry(capacity/timeout/non-linearizable) `503 PATIENT_HISTORY_UNAVAILABLE`, readiness=false, no-partial-page assertion을 추가한다.
- [ ] Step 3: `./gradlew :appointment-api:test --tests '*PatientHistoryCursorCodecTest'`를 실행해 신규 symbols/codec 부재로 RED를 확인한다.
- [ ] Step 4: 발급 순서는 current page boundary→registry lookup→miss encryption→putIfAbsent, 검증 순서는 outer grammar→AES-GCM authenticated decrypt→payload bounds/bucket→registry key→constant-time compare로 구현한다.
- [ ] Step 5: registry entry는 raw subject/tenant/body를 저장하지 않고 TTL 전 eviction을 거부하며, readiness failure를 bounded metric으로 기록한다.
- [ ] Step 6: cursor/reference/ETag targeted tests와 `./gradlew :appointment-api:compileKotlin`을 실행한다.

## Task 4: patient history service/controller RED→GREEN

**Files:** DTO/service/controller/security/config/tests.

- [ ] Step 1: HTTP contract test에 clinic-less PATIENT 200, cross-patient 409/empty, invalid limit 400, malformed cursor 400, registry 503, 304 required headers를 추가한다.
- [ ] Step 2: `./gradlew :appointment-api:test --tests '*PatientCancellationHistoryControllerTest' --tests '*PatientCancellationHistoryServiceTest'`를 실행해 RED를 확인한다.
- [ ] Step 3: actor resolver를 `clinicId=null`로 호출하는 tenant-only route와 tenant group canonical identity/fingerprint ownership check를 구현한다.
- [ ] Step 4: service가 core page를 DTO로 매핑하고 labels/null fallback, ETag/304, 750ms backend-only transient retry, 256KiB deterministic error를 적용한다.
- [ ] Step 5: controller와 global/security path classifier를 route-preserving sanitized error/header contract로 연결한다.
- [ ] Step 6: API targeted tests, migration tests, `./gradlew :appointment-api:build`를 실행한다.

## Task 5: portal TypeScript RED→GREEN

**Files:** models/client/facade/page/component/specs.

- [ ] Step 1: facade/component specs에 state union, tenant A→B synchronous purge, logout/401 stale response, private cache misuse, one-shot 409/400 recovery RED를 추가한다.
- [ ] Step 2: `npm test -- --runInBand` 또는 repository Angular unit-test command로 targeted specs를 실행해 RED를 확인한다.
- [ ] Step 3: public query `{cursor, limit}`와 private `(RequestEpoch, sessionVersion, tenantIdentityGeneration, tenantCode, cursor, limit)` cache tuple을 구현한다.
- [ ] Step 4: component timeline과 nullable Korean fallback, `<time datetime>`, explicit Intl options, `aria-busy`, live region, `aria-disabled` activation guard/focus contract를 구현한다.
- [ ] Step 5: facade의 derived busy/error state, one-shot recovery, tenant/401 purge와 delayed result epoch check를 구현한다.
- [ ] Step 6: `npm test -- --watch=false`와 `npm run build`를 실행한다. `angular.json`은 변경하지 않는다.

## Task 6: 통합 검증과 문서/DoD

- [ ] Step 1: `./gradlew :appointment-core:test :appointment-api:test`를 module-scoped로 실행한다.
- [ ] Step 2: 세 dialect Flyway migration test와 protected backend가 가능한 환경에서 HTTP smoke를 실행한다. Docker/Colima 미가동이면 실패 원인을 기록하고 production gate는 `PENDING`으로 유지한다.
- [ ] Step 3: PostgreSQL EXPLAIN/query-count, cursor registry concurrency/restart/capacity, retry/ETag latency evidence를 artifact로 보존한다.
- [ ] Step 4: Korean README/KDoc/plan/review와 `git diff --check`, `bluetape-kotlin-patterns` checklist, targeted TypeScript review를 완료한다.
- [ ] Step 5: 변경 파일/테스트/미실행 production gates를 review 문서와 Issue #305에 반영한다. PR/merge는 별도 승인 없이는 실행하지 않는다.

## 구현 후 검증 결과

- API targeted Kotlin 검증: `:appointment-api:compileKotlin` 및 환자 이력 properties/controller/service/cursor codec 14개 테스트 통과.
- Portal 검증: `npm test -- --watch=false` 39개 파일/258개 테스트 통과, `npm run build` 성공.
- Core 신규 schema 검증: H2와 MySQL 통과. PostgreSQL Testcontainers는 Colima Docker socket mount 오류(`operation not supported`)로 실행하지 못함.
- 전체 suite 병렬 실행은 702개 중 181개 실패했으나, 동일 suite를 단일 스레드·parallel disabled로 재실행한 결과 702개 중 2개만 실패했다. 두 실패 모두 `TableSchemaTest`와 `WaitlistTableSchemaTest`의 Testcontainers Colima Docker socket mount 오류(`mkdir /Users/debop/.colima/default/docker.sock: operation not supported`)이며 신규 환자 이력 코드 실패 증거는 없다.
- Kotlin 패턴 보강 후 `KotlinProductionPatternComplianceTest` 7개와 cursor codec 6개를 포함한 13개 targeted 테스트가 통과했다. 새 테스트는 bluetape assertion helper를 사용하며 production `!!`가 없다.
- `git diff --check` 통과. `frontend/appointment-frontend/angular.json`은 변경하지 않았다.
- 7-tier/code-pattern review: 설계 검토 P0/P1/P2/P3=0 CLEAR; protected backend/shared registry/production ACL·backup·canary·SLO는 외부 증거 대기.

## 검증 명령 요약

```bash
./gradlew :appointment-core:test --tests '*AppointmentCancellationHistoryRepositoryTest'
./gradlew :appointment-api:test --tests '*PatientHistoryCursorCodecTest' --tests '*PatientCancellationHistoryServiceTest' --tests '*PatientCancellationHistoryControllerTest'
./gradlew :appointment-api:build
cd frontend/appointment-frontend && npm test -- --watch=false && npm run build
git diff --check
```

## 범위 종료 조건

구현·targeted module tests·frontend build/typecheck가 통과했지만 운영 migration과 activation의 merge gate는 `BLOCK`으로 유지한다. 전체 core suite의 기존 H2 격리 실패와 PostgreSQL Docker socket·lock timeout 장애는 검증 리스크로 남긴다. protected backend, 실제 PostgreSQL production schema, ACL/backup/canary/SLO는 증거가 없으므로 구현 결과와 분리해 `PENDING`으로 보고한다. `angular.json` 변경은 계속 별도 작업으로 남긴다.

## 현재 구현 재검토(2026-08-14)

위 종료 문구의 `DONE/CLEAR`는 설계·targeted code lane에 한정되며 merge 준비를 뜻하지 않는다.
`frontend/appointment-frontend/angular.json`은 계속 범위에서 제외한다.

- **P1 / BLOCK — 운영 migration과 activation의 실증 gate가 남아 있음:** V28은 expand-only,
  V29는 dialect별 online index, V30은 500행 durable checkpoint와 opt-in backfill runner,
  residual·scope readiness check 및 writer version fence를 코드에 반영했다. 그러나 실제
  MySQL/PostgreSQL smoke, preflight count artifact, shared writer provider의 모든 replica
  관찰, 60초 steady probe의 운영 alert 증거가 아직 없다. H2 회귀는 V28/V29/V30 migration
  1개와 readiness fence 1개가 통과했지만 production migration 안전성을 대체하지 않는다.
<!-- 과거 구현 판정은 최신 구현 판정으로 대체되었다.
  (historical details intentionally omitted)
- **CLOSED:** session/tenant delayed-response tuple, generation mismatch bounded bootstrap,
  취소 이력 전용 path classifier, registry deadline/DB connection 분리, metadata ambiguity,
  load-more stale cursor recovery.
- **최신 evidence:** API compile 및 환자 이력 targeted 13개, core repository targeted,
  Angular 39개 파일/266개 테스트 통과, `npm run build` 성공, `git diff --check` 통과.
  Angular 전체 설정 파일은 범위에서 제외되어 변경되지 않았다.
- **현재 구현 판정:** P0=0, P1=1, P2=0, P3=0, **BLOCK**. 실제 MySQL/PostgreSQL
  migration smoke, protected HTTP/shared registry readiness, EXPLAIN·성능, production
  ACL/backup/canary/SLO는 별도 `PENDING`이다.

## 최신 잔여 게이트 실행 갱신(2026-08-15)

이전 문서의 protected HTTP, readiness/registry, PostgreSQL EXPLAIN 미실행 표기는 최신
실행으로 갱신한다. 상세 명령과 환경은
`docs/benchmarks/issue-305-remaining-gates/2026-08-15/evidence.ko.md`에 고정했다.

- **CLOSED (로컬):** `PatientCancellationHistoryHttpSecurityIntegrationTest` 1/1.
  anonymous 401, staff 403, patient의 sanitized 503 경계와 correlation/retry header를
  Spring Boot `RANDOM_PORT` 컨텍스트에서 확인했다.
- **CLOSED (로컬):** `PatientHistoryReadinessTest` 2/2. 60초 probe cache가 registry
  장애를 fail-closed로 전환하고, scheduled probe 후 ready로 복구하는 흐름을 H2 DB와
  실제 readiness registry로 검증했다.
- **CLOSED (로컬):** `PatientHistoryCursorCodecTest` 8/8. bounded registry capacity
  초과를 거부하고 5분 TTL 만료 entry를 회수하는 계약을 검증했다.
- **CLOSED (로컬):** `PatientCancellationHistoryQueryPlanTest` 1/1. 실제 PostgreSQL
  production-schema fixture에서 `idx_cancellation_detail_patient_scope_time`을 사용하고
  history relation의 `Seq Scan`이 없음을 EXPLAIN JSON으로 확인했다. commitment 4,000행
  hash join의 `Seq Scan`은 history table 판정과 분리했다.
- 위 네 gate의 단일 실행 합계는 **12/12 통과(실패 0, 오류 0, skip 0)**다.

### 최신 DoD 경계

- **CLOSED:** 로컬 HTTP/security, 60초 readiness probe, bounded registry capacity/TTL,
  PostgreSQL keyset EXPLAIN.
- **PENDING:** production MySQL endpoint/HTTP smoke와 credential 기반 preflight,
  모든 writer replica version fence 및 60초 alert, shared external registry
  restart/capacity, production ACL/backup/canary/SLO/rollback.
- **N/A:** D8 본격 운영(현재 본격운영을 하지 않음), D9 운영 인력(1인 개발자).

따라서 구현 판정은 **P0=0, P1=1, P2=0, P3=0, PENDING/BLOCK**이다. 로컬 증거를
production PASS로 승격하지 않으며, production gate가 남아 있는 동안 merge-ready로
판정하지 않는다.

## 현재 실행 갱신(2026-08-15)

### 완료된 migration smoke와 원인 수정

이전 기록의 PostgreSQL 미실행 판정은 현재 실행 결과로 대체한다. 테스트는 저장소의
`Containers.Postgres`(`PostgreSQLServer.Launcher.postgres`)와 `Containers.MySql8` singleton을
사용했으며, `@Testcontainers`나 별도 컨테이너 launcher를 추가하지 않았다. Colima Docker
context를 명시하고 Gradle daemon을 끈 동일한 조건에서 세 dialect를 순차 실행했다.

| 대상 | 명령의 선택자 | 결과 |
|---|---|---|
| PostgreSQL | `FlywayPostgreSQLMigrationTest` | 8/8 통과, 14.2초 |
| MySQL 8 | `FlywayMySQLMigrationTest` | 컨테이너 migration 8/8 통과, 운영 endpoint 1건은 자격증명 부재로 의도적 skip |
| H2 | `FlywayMigrationTest` | 8/8 통과, 4.4초 |

첫 PostgreSQL V28→V30 실행에서는 Flyway의 transactional advisory lock connection이
`idle in transaction` 상태로 `pg_namespace`를 조회하는 동안 V29
`CREATE INDEX CONCURRENTLY`가 virtualxid lock을 기다렸고, 30초 후 SQLSTATE `57014`
statement timeout으로 실패했다. V29의 `executeInTransaction=false`만으로는 Flyway
transactional lock을 해제하지 못한다.

운영 PostgreSQL profile에는 `spring.flyway.postgresql.transactional-lock: false`를
사용하고, migration contract helper에도 `flyway.postgresql.transactional.lock=false`를
같이 적용했다. V13 profile migration support는 최신 V29까지 진행하지 않도록
`target("13")`을 명시했다. 수정 후 PostgreSQL 전체 8개 migration test와 V28→V30
계약 test가 재실행되어 통과했다.

### 남은 DoD

- [x] bluetape4k singleton 기반 H2/PostgreSQL/MySQL V28→V30 migration smoke
- [x] PostgreSQL transactional lock 원인 재현과 설정 수정, 전체 migration class 재검증
- [x] legacy V27 row의 nullable snapshot 및 V30 checkpoint 계약 재검증
- [ ] 실제 production MySQL endpoint/HTTP smoke와 운영 자격증명 기반 preflight artifact
- [ ] 모든 writer replica의 version fence, 60초 steady probe/alert, PostgreSQL EXPLAIN·성능
- [ ] shared registry readiness/restart/capacity, ACL·backup·canary·SLO 증거

따라서 로컬 dialect migration gate는 해소되었지만 production activation gate는 여전히
`PENDING`이며, Issue #305를 merge-ready 또는 운영 활성화 완료로 판정하지 않는다.
-->

- **CLOSED:** session/tenant delayed-response tuple, generation mismatch bounded bootstrap,
  취소 이력 전용 path classifier, registry deadline/DB connection 분리, metadata ambiguity,
  load-more stale cursor recovery.
- **최신 evidence:** API compile 및 환자 이력 targeted 13개, V28/V29/V30 H2·schema·properties
  5개와 writer fence 1개, core repository targeted, Angular 39개 파일/266개 테스트 통과,
  `npm run build` 성공, `git diff --check` 통과. Angular 전체 설정 파일은 범위에서 제외되어
  변경되지 않았다.
- **현재 구현 판정:** P0=0, P1=1, P2=0, P3=0, **BLOCK**. 실제 MySQL/PostgreSQL
  migration smoke, protected HTTP/shared registry readiness, EXPLAIN·성능, production
  ACL/backup/canary/SLO는 별도 `PENDING`이다.

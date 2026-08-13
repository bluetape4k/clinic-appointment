# Issue #34 환자 예약 약속 포털 구현 계획

> **작업자 참고:** 이 계획은 `docs/superpowers/specs/2026-08-12-issue-34-patient-commitment-design.md`의 계약을 구현한다. 각 단계는 실패 테스트를 먼저 추가하고 모듈 범위로 검증한다.

**목표:** 환자 포털에서 tenant-scoped 예약 약속을 요청·조회·동의·취소하고, 관리자·직원 취소 설명을 감사 기록과 환자 알림에 안전하게 전달한다.

**구조:** commitment cancel route는 하나로 유지하고 controller/security/application에서 PATIENT와 ADMIN/STAFF를 분기한다. 취소 detail은 같은 DB transaction에 별도 bounded snapshot으로 저장하고, notification event는 schema v2 producer와 v1/v2 decoder를 제공한다. Angular 포털은 기존 facade/client에 cancel command와 상태 stepper를 추가한다.

**기술 스택:** Kotlin 2.3, Spring Boot 4, Exposed v1, Flyway(H2/PostgreSQL/MySQL), JUnit 5/Kluent/MockK, Angular 22, TypeScript 6, Vitest, Playwright.

구현 전 `$test-driven-development`, `$bluetape-kotlin-patterns`, Angular
standalone component convention을 다시 읽고 각 task의 RED→GREEN 순서에
적용한다. 고복잡도 보안·DB·serialization 변경의 위험 register는
`docs/superpowers/risk/2026-08-12-issue-34-patient-commitment-risk-register.ko.md`
에 고정한다.

---

## 파일 및 책임 지도

- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/commitment/AppointmentCommitmentRequests.kt`: 취소 body의 code/detail validation
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentController.kt`: 공통 cancel route의 actor role 분기와 OpenAPI
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`: cancel route ADMIN/STAFF/PATIENT matrix
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationService.kt`: request→command 변환과 idempotency hash 입력
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentApplicationService.kt`: cancel operator/patient application contract
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentCommitmentAccessResolver.kt`: cancel 전용 STAFF scope와 patient ownership 재검증
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentCommitmentHttpSupport.kt`: PATIENT/operator cancel actor helper
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/DatabaseConfig.kt`: dev/test SchemaInit에 cancellation detail table 등록
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommands.kt`: 내부 bounded detail command
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/commitment/CancellationReasonRegistry.kt`: API·event·notification이 함께 의존하는 폐쇄 reason code registry와 canonical hash codec
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandService.kt`: cancellation snapshot, audit/outbox/notification 원자 기록
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentMetrics.kt`: cancel 전용 timer/result/replay/lock contention metric
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentCancellationDetails.kt`: Exposed cancellation detail table
- `appointment-api/src/main/resources/db/migration/{h2,postgresql,mysql}/V27__add_appointment_cancellation_details.sql`: additive schema
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationTemplateParameters.kt`: typed cancellation detail
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContracts.kt`: schema version compatibility and bounded text contract
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxCodec.kt`: v1/v2 dual-read and v2 encode
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriter.kt`: cancellation detail 전달과 template version
- `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`: v2-producer flag를 writer schema 선택과 activation gate에 연결
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateRenderer.kt`: cancellation detail text/HTML render와 escape
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateCatalog.kt`: cancellation template v2 채널별 readiness
- `appointment-notification/src/test`: worker/renderer/template readiness와 reminder lease 경합 통합 테스트
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationSchemaReadiness.kt`: codec/template/channel contract readiness
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProperties.kt`: default-off v2 producer flag와 허용 schema policy
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`: property binding, worker gate, readiness/invalid-config wiring
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxMetrics.kt`: schema/decode/template readiness bounded metrics
- `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxAlertPolicy.kt`: activation/rollback alert threshold와 지속 시간
- `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/PatientAppointmentCancelPostgresSimulation.kt`: PostgreSQL cancel load model와 assertions
- `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/PatientAppointmentCancelPostgresFixture.kt`: Testcontainers fixture/고정 dataset/metric report
- `appointment-api/src/gatling/resources/benchmarks/issue-34/{baseline,candidate}.json`: 동일 환경 pre/post evidence artifact
- `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationCodecBacklogBenchmarkTest.kt`: 실제 v1/v2 codec backlog benchmark
- `frontend/appointment-frontend/src/app/core/api/portal-api.models.ts`: cancel request/response model
- `frontend/appointment-frontend/src/app/core/api/portal-api-client.ts`: ETag/idempotent cancel call
- `frontend/appointment-frontend/src/app/features/patient-portal/appointment-commitment.facade.ts`: cancel state transition and stale refresh
- `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-appointments-page.component.ts`: request/accept/decline/cancel screen
- `frontend/appointment-frontend/src/app/features/patient-portal/components/appointment-card.component.ts`: status stepper, product/session summary, terminal action
- matching `src/test`/`src/app/**/*.spec.ts`/`e2e/patient-portal.spec.ts`: contract and interaction evidence

---

### Task 1: 취소 요청 검증과 권한 실패 테스트 고정

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/dto/commitment/AppointmentCommitmentRequests.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentController.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/security/SecurityConfig.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AdminAppointmentV2Test.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentSecurityIntegrationTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/controller/AppointmentRequestV2Test.kt`

- [x] `CancelAppointmentRequest(reasonCode, reasonDetail?)`의 실패 테스트를 먼저 추가한다. blank/code pattern, 501자, ISO control, patient가 detail을 보낸 경우를 각각 검증한다.
- [x] `./gradlew :appointment-api:test --tests '*AdminAppointmentV2Test*' --tests '*AppointmentCommitmentSecurityIntegrationTest*'`를 실행해 새 테스트가 실패하는지 확인한다.
- [x] DTO에 `reasonDetail` bounded validation을 추가하고, cancel controller가 `PATIENT`는 detail 없는 request만, `ADMIN`/`STAFF`는 code+detail을 허용하도록 actor branch를 구현한다.
- [x] cancel 경로 전용 matcher를 `ADMIN`/`STAFF`/`PATIENT`로 바꾸고 나머지 admin mutation은 `ADMIN` 전용으로 유지한다. controller와 access resolver도 같은 operator matrix를 재검증한다.
- [x] OpenAPI description과 오류 matrix를 갱신하고 targeted tests가 통과하는지 확인한다.

검증 명령:

```bash
./gradlew :appointment-api:test --tests '*AdminAppointmentV2Test*' --tests '*AppointmentCommitmentSecurityIntegrationTest*' --tests '*AppointmentRequestV2Test*'
```

### Task 2: 취소 detail table과 migration 추가

**Files:**
- Create: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/model/tables/AppointmentCancellationDetails.kt`
- Create: `appointment-api/src/main/resources/db/migration/h2/V27__add_appointment_cancellation_details.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V27__add_appointment_cancellation_details.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V27__add_appointment_cancellation_details.sql`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/AppointmentCancellationMigrationTestSupport.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayPostgreSQLMigrationTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/FlywayMySQLMigrationTest.kt`

- [x] H2/PostgreSQL/MySQL에서 table, tenant/clinic/appointment/commitment/proposal FK, one-row terminal constraint, aggregate index를 확인하는 migration test를 작성한다.
- [x] `AppointmentCancellationDetails : LongIdTable`을 기존 table naming/transaction 규칙으로 만든다. detail 원문은 bounded `varchar`, actor identity는 role와 hash만 둔다.
- [x] V27은 기존 row를 변경하지 않는 additive DDL로 작성한다. vendor별 identity, timestamp, charset은 V26 convention을 따른다.
- [x] migration support가 clean V1→V27과 V26→V27 각각에서 table/index/FK를 검증하도록 연결한다.

검증 명령:

```bash
./gradlew :appointment-api:test --tests '*FlywayMigrationTest*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*FlywayMySQLMigrationTest*'
```

### Task 3: command와 audit/outbox 원자성 구현

**Files:**
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommands.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationService.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandService.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/commitment/AppointmentCommitmentCommandServiceTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DefaultAppointmentCommitmentApplicationServiceTest.kt`

- [x] patient/admin cancel command가 detail을 전달하고 command hash에 registry가 정의한 canonical part가 포함되는 실패 테스트를 추가한다.
- [x] `CancelAppointmentCommand.reasonDetail: String?`에 동일한 bounded validation을 적용한다. patient branch는 null을 강제한다.
- [x] controller를 우회한 application/command 직접 호출에서도 `ADMIN`/`STAFF`만 detail을 허용하고 `PATIENT` detail은 거부하는 부정 테스트를 추가한다.
- [x] detail은 `cancel-v1\\0` prefix 뒤에 `reasonCode`와 nullable `reasonDetail`을 각각 unsigned 32-bit big-endian UTF-8 byte length + bytes로 직렬화하는 단일 length-prefixed canonical codec으로 hash한다. null은 `0xffffffff` length로 표현하고 Unicode는 입력 code point를 그대로 UTF-8로 인코딩하며 normalization/delimiter join을 사용하지 않는다. 이 형식의 Unicode·null·delimiter replay mismatch 테스트를 추가한다.
- [x] `appointment-core`의 `CancellationReasonRegistry`를 단일 source of truth로 만들고 현재 허용 code(`CUSTOMER_REQUEST`, `REFUND`, `EQUIPMENT_FAILURE`, `CLINIC_REQUEST`)를 명시한다. API DTO/command, event codec, notification writer, OpenAPI enum, frontend catalog는 이 core contract에서 파생·검증하며 미등록 code 테스트를 추가한다. 모듈 의존 순환을 만들기 위해 API registry를 event가 참조하는 방식은 허용하지 않는다.
- [x] cancellation transaction에서 `AppointmentCancellationDetails.insert`를 상태 전환 직후, audit/scheduling outbox/notification 전에 실행한다. commitment 하나당 duplicate row가 생기면 command를 실패시킨다.
- [x] `AppointmentAuditEvents.payloadHash`와 scheduling outbox JSON에는 raw detail을 로그로 출력하지 않고 command hash/detail snapshot 규칙을 적용한다.
- [ ] success, duplicate idempotent replay, stale ETag, invalid transition, transaction rollback에서 detail/audit/outbox row 수를 검증한다.
- [ ] reminder outbox가 `PENDING`/`RETRY_WAIT`/`PROCESSING`인 상태와 lease expiry/recovery scanner가 cancel command와 경합할 때, cancel commit은 reminder를 `SUPPRESSED`로 만들고 late completion은 lease fence에서 거부하는지 검증한다. command rollback이면 기존 reminder가 보존되어야 한다.
- [ ] provider 호출이 이미 시작된 뒤 취소가 발생해 delivery result가 불명확한 경우의 운영 상태와 환자 노출 정책을 risk register에 고정한다.

검증 명령:

```bash
./gradlew :appointment-api:test --tests '*AppointmentCommitmentCommandServiceTest*' --tests '*DefaultAppointmentCommitmentApplicationServiceTest*'
```

### Task 4: notification schema v2와 writer 전달

**Files:**
- Modify: `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/commitment/CancellationReasonRegistry.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationTemplateParameters.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContracts.kt`
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxCodec.kt`
- Create: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxContractRegistry.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriter.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProperties.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationProducerSchemaReadiness.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateCatalog.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateRenderer.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxWorker.kt`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxCodecTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/notification/AppointmentNotificationWriterTest.kt`
- Test: `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/commitment/CancellationReasonRegistryTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfigurationTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationTemplateRendererTest.kt`
- Test: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentMessagingAutoConfigurationWiringTest.kt`
- Test: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxEndToEndTest.kt`

- [x] v1 code-only JSON decode와 v2 code+detail encode/decode, unknown field rejection, control-character rejection, null detail rendering equivalence 테스트를 먼저 작성한다.
- [x] `eventType + slot + templateKey + templateVersion + parameterType` 폐쇄형 조합을 검증하고 v1/v2 교차 조합 위조 payload를 거부한다.
- [x] codec envelope, claimed outbox row metadata, renderer catalog 반환값을 같은 registry identity로 검증해 tuple 위조·row/payload 불일치를 provider 호출 전에 재시도 가능한 계약 오류로 닫는다.
- [x] `AppointmentCancelledParameters.cancellationReasonDetail`을 bounded optional field로 추가한다. existing v1 payload는 missing field를 null로 읽는다.
- [x] envelope init은 supported versions `{1, 2}`를 허용하고 writer current version은 `2`로 올린다. decoder는 v1/v2만 허용한다.
- [x] cancellation template만 version `2`, 나머지는 version `1`로 enqueue하도록 writer를 수정한다. legacy cancellation writer와 commitment writer 양쪽을 검증한다.
- [x] v1 producer가 cancellation detail을 조용히 버리지 않도록 fail-closed `NotificationContractException`을 던지고 transaction/outbox row를 남기지 않는 회귀 테스트를 고정한다.
- [x] notification contract KDoc와 기존 “free text 금지” 문구를 새 patient-facing bounded detail 정책으로 정확히 바꾼다.

검증 명령:

```bash
./gradlew :appointment-core:test :appointment-event:test :appointment-notification:test :appointment-api:test --tests '*CancellationReasonRegistryTest*' --tests '*NotificationOutboxCodecTest*' --tests '*AppointmentNotificationWriterTest*' --tests '*NotificationAutoConfigurationTest*' --tests '*AppointmentMessagingAutoConfigurationWiringTest*'
```

- [x] 실제 `appointment-notification` renderer/catalog에서 모든 활성 channel의 cancellation template v2 존재, detail text/HTML escape, null-detail v1 동등성을 검증한다. template readiness가 확인되지 않으면 v2 producer를 활성화하지 않는다.
- [ ] consumer-first rollout을 고정한다: decoder `{1,2}` + writer `1` 배포 후 모든 worker replica readiness/codec matrix 확인, 그 다음 feature flag로 writer `2`를 활성화한다. rollback은 writer `1` 전환 뒤 `schema_version=2`의 `PENDING`/`PROCESSING`/`RETRY_WAIT`가 lease+retry 최대 구간 동안 0임을 확인해야 dual-reader 제거를 허용한다.
- [ ] 실제 feature flag/property, readiness endpoint/health indicator, 모든 replica 확인 명령과 timeout 산식을 문서화한다. `schema_version=2` backlog 0 SQL, decode failure/template readiness metric·alert를 operator runbook과 테스트 fixture에 고정한다. compile-time `CURRENT_SCHEMA_VERSION`만 바꾸는 staged rollout은 허용하지 않는다.
- [x] activation과 rollback checklist를 분리한다. 현재 `@ConfigurationProperties(prefix = "clinic.notification")`에 `v2-producer`를 추가해 default-off `clinic.notification.v2-producer`로 통일하고 binding/invalid-config fail-fast test를 둔다. 모든 worker 동일 build/codec readiness와 active channel별 template readiness 후에만 flag를 켠다. rollback은 flag off, v2 `PENDING`/`PROCESSING`/`RETRY_WAIT`와 `EXHAUSTED` 0 또는 승인된 reconciliation, dual-reader 보존 기간을 요구한다.
- [x] `NotificationAutoConfiguration`의 `@EnableConfigurationProperties(NotificationProperties::class)` binding, default-off `clinic.notification.v2-producer`, invalid-config fail-fast, worker gate와 readiness health indicator를 실제 bean graph에 연결하고 context test로 검증한다. 모든 worker 동일 build/codec readiness와 active channel별 template readiness 후에만 flag를 켠다.
- [x] `ServiceConfig.appointmentNotificationWriter`가 동일 flag와 readiness gate를 받아 cancellation만 schema v2로 선택하고, flag off/ready 실패에서는 기존 schema v1을 유지하는지 context/bean contract test로 검증한다. `NotificationAutoConfiguration`의 worker/readiness/metrics/alert bean wiring과 함께 실제 activation 경계를 증명한다.
- [x] reminder recovery는 cancellation 외 이벤트이므로 readiness flag와 무관하게 schema v1로 materialize하고, recovery bean에서 producer readiness를 주입하지 않아 writer의 cancellation-only v2 정책과 분리한다.
- [ ] outbox에 schema version column이 없으므로 vendor별 JSON extraction 또는 indexed projection의 실행 가능한 query, 예상 출력, timeout, redrive/suppression 절차를 runbook과 test fixture에 고정한다.
- [ ] codec failure, schema-version backlog, template readiness를 bounded metric/alert로 분리하고 activation/rollback threshold, 평가·해제 지속 시간, dashboard query와 담당자 행동을 고정한다.

### Task 5: Angular API client와 facade cancel 경로

**Files:**
- Modify: `frontend/appointment-frontend/src/app/core/api/portal-api.models.ts`
- Modify: `frontend/appointment-frontend/src/app/core/api/portal-api-client.ts`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/appointment-commitment.facade.ts`
- Test: `frontend/appointment-frontend/src/app/core/api/portal-api-client.spec.ts`
- Test: `frontend/appointment-frontend/src/app/features/patient-portal/appointment-commitment.facade.spec.ts`

- [x] 최신 ETag와 `Idempotency-Key`를 보내는 `cancelAppointment(appointmentId, body, key, etag)` 실패 테스트를 작성한다.
- [x] `CancelAppointmentRequest` frontend model은 patient code만 전송하도록 하고 detail을 public portal model에 노출하지 않는다.
- [x] facade `cancelAppointment`는 busy guard, `submitting` 상태, 성공 `CANCELLED` response 적용, `412` 최신 조회, 오류 상태 mapping을 구현한다.
- [x] 같은 intent key의 빠른 중복 클릭이 두 HTTP request를 만들지 않는 테스트를 추가한다.
- [x] transport timeout/503와 명시적 terminal rejection 전까지 같은 intent key를 유지하고, `412` 재조회 후 사용자가 새 취소를 확인할 때만 새 key를 발급하는 facade/browser 테스트를 추가한다.
- [x] `412` refresh는 appointment별 single-flight로 coalesce하고 stale response를 무시한다. refresh 완료 뒤 확인 dialog를 다시 열고 focus를 복귀시키며, 자동 mutation retry는 금지한다.
- [x] request/accept/decline/cancel 전체 mutation에 같은 `412` 정책을 적용한다. refresh 뒤 사용자가 명시적으로 재확인할 때 새 intent key를 발급하고, timeout/503에서만 기존 key를 재사용한다. deterministic page key는 random/session-scoped key와 입력 fingerprint로 교체해 새 입력에서 회전한다.
- [ ] 생성 `appointment-requests`는 별도 precondition 계약을 구현한다. 같은 key의 최초 proposal replay 또는 appointment/commitment 참조+ETag가 있는 `412/409`만 복구 가능으로 처리하고, 참조 없는 충돌은 자동 재시도하지 않는다. 새 key는 사용자가 새 예약 요청을 명시적으로 시작할 때만 발급한다.
- [x] appointment별 refresh single-flight와 stale response 무시를 구현하고, 연속 `412` 충돌에서 GET request-count가 1회인지 browser test로 고정한다.

검증 명령:

```bash
cd frontend/appointment-frontend
npx ng test --watch=false --include='src/app/core/api/portal-api-client.spec.ts' --include='src/app/features/patient-portal/appointment-commitment.facade.spec.ts' --include='src/app/features/patient-portal/pages/patient-appointments-page.component.spec.ts'
```

### Task 6: Portal status stepper와 cancel interaction

별도 상세 route가 없는 범위에서 동작하지 않는 `예약 상세 보기` dead button은
제거하거나 현재 카드 상세로 연결하고, 실제 navigation 없는 버튼이 남지 않는지
검증한다.

**Files:**
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/components/appointment-card.component.ts`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-appointments-page.component.ts`
- Modify: `frontend/appointment-frontend/src/app/features/patient-portal/appointment-summary.ts`
- Modify: matching SCSS/template files if extracted from inline component styles
- Test: `frontend/appointment-frontend/src/app/features/patient-portal/components/appointment-card.component.spec.ts`
- Test: `frontend/appointment-frontend/src/app/features/patient-portal/pages/patient-appointments-page.component.spec.ts`
- Test: `frontend/appointment-frontend/e2e/patient-portal.spec.ts`

- [x] API status를 명시적 step ID로 매핑하고, network 요청 중 local `REQUESTED`를 표시한다. `CANCELLED`/`EXPIRED`는 proposal로 되돌아가지 않는 terminal state인지, product title/session label, keyboard focus와 `aria-current`를 검증하는 component test를 작성한다.
- [x] `PROPOSED`/`HELD`/`CONFIRMED`에서만 cancel button을 보이고, `EXPIRED`/`CANCELLED`에서는 숨긴다.
- [x] cancel confirmation UI는 patient에게 code 선택과 확인만 제공하고, 확인 뒤 facade를 호출하며 busy 중 버튼을 잠근다.
- [x] request→proposal→accept/decline→cancel 상태 흐름과 stale `412` refresh를 Playwright browser contract에 추가한다.
- [x] 새 세션 복구에서 `404`만 새 예약 폼을 허용하고 인증·권한·네트워크 오류는 예약 폼을 열지 않은 명시적 retry 상태로 표시한다.
- [ ] 보호된 backend harness를 기본 증거로 사용한다. 실제 backend GET/POST header·body·응답, ETag/412, 권한, 상태 전이, outbox 결과와 브라우저 trace/screenshot/request-count를 같은 테스트 run artifact로 보존한다. fixture는 실제 backend contract/state fixture에서만 생성하며 fixture-only 증거는 인정하지 않는다.
- [ ] 첫 생성, 동일 key replay, 생성 충돌(`412/409`)에서 appointment 참조 유무와 새 key 발급 여부를 보호된 backend + Playwright contract로 검증한다.
- [ ] 320px layout, screen reader labels, focus-visible outline, dialog focus trap/복귀, 상태 announcement, 401/403/409/410/412/422/428/503 message를 검증한다.
- [x] 환자 portal guard가 `PatientAuthService.isPatient()`를 요구하고 ADMIN/STAFF/DOCTOR session의 진입을 거부하는 unit/E2E를 추가한다.
- [x] root facade의 logout/login reset, tenant별 sessionStorage 참조 삭제, session generation 기반 stale response 폐기와 늦은 HTTP 응답 회귀 테스트를 고정한다.
- [x] cancel code catalog의 안정적인 한국어 label/options와 기본 선택을 정의하고 raw business code가 환자에게 노출되지 않는지 검증한다.

검증 명령:

```bash
cd frontend/appointment-frontend
npm test -- --watch=false
npm run build
npm run test:e2e -- --project=chromium
```

### Task 7: API/OpenAPI/docs와 회귀 검증

**Files:**
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/security/AppointmentCommitmentOpenApiTest.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentCommitmentExceptionResolutionTest.kt`
- Modify: `docs/superpowers/INDEX.md`
- Modify: `frontend/appointment-frontend/README.ko.md`
- Modify: `frontend/appointment-frontend/README.md` (기술 identifier/명령 외에는 repo-local Korean policy에 맞춰 정리)
- Create: `docs/reviews/2026-08-12-issue-34-implementation-review.ko.md`
- Create: `.github/workflows/issue-34-performance-gate.yml` (benchmark artifact/comparator merge blocker)
- Create: `scripts/compare-issue34-benchmark.sh`
- Modify: issue #34 comment/body through `gh`

- [x] OpenAPI가 cancel body의 code/detail schema, ADMIN/STAFF/PATIENT role description, 400/403/409/412 response를 포함하는지 고정한다.
- [x] exception path classifier와 security matrix가 `/api/{tenantCode}/appointments/{id}/cancel`을 계속 commitment route로 판단하는지 검증한다.
- [ ] 설계/계획 링크와 #305 제외 범위를 `docs/superpowers/INDEX.md` 및 issue #34 comment에 반영한다.
- [x] cancel 완료 response와 화면에 audit/history/reasonDetail 금지 필드가 섞이지 않는 negative contract를 고정하고 별도 detail route를 추가하지 않는다.
- [x] cancel end-to-end timer를 proposal latency와 분리하고 `result`/`replay` tag, lock-wait/retry 신호를 검증하는 metric contract test를 추가한다.
- [ ] PostgreSQL warm-up 30초·측정 5분·고정 dataset 100개·동시성(동일 10/상이 20)을 사용해 cancel p95/p99와 error/lock-wait를 기준선과 비교한다. p95 10% 또는 p99 15% 회귀 시 PR 준비를 중단한다.
- [x] notification v1/v2 mixed backlog는 합성 비용 모델과 분리된 `NotificationCodecBacklogBenchmarkTest`에서 실제 `NotificationOutboxEvents` row를 읽고 discriminator에 따라 strict decode한 뒤 terminal 상태로 drain한다. 예외 fallback은 허용하지 않는다.
- [ ] 성능 lane은 기존 합성 Gatling 비용 모델을 근거로 사용하지 않는다. `appointment-api/src/gatling`에 PostgreSQL Testcontainers/singleton fixture, cancel success/replay/412/경합 시나리오, warm-up 30초·측정 5분·dataset 100·동시성 10/20 load model, baseline/after JSON artifact와 lock-wait query를 추가하고 `./gradlew :appointment-api:gatlingRun`으로 실행한다. fixture/simulation 구현과 wiring smoke는 완료했지만 full gate 실행·기준선 비교는 남아 있다.
- [x] `appointment-event`의 실제 codec backlog harness는 legacy-heavy/current-heavy 혼합 비율, 500자 detail, warm-up/측정 window, throughput/p95/p99/decode failure/drain-time artifact를 지원한다. 다만 동일 환경 3회 baseline/candidate 실행과 회귀 gate 판정은 별도 검증으로 남긴다.
- [ ] PostgreSQL simulation은 patient/admin success 50%, idempotent replay 20%, expected `412` conflict 20%, expected retry exhaustion 10% arrival mix를 고정하고 동일 appointment 10·상이 appointment 20 동시성을 생성한다. expected conflict/exhaustion은 scenario success로 판정하고, unexpected HTTP 5xx/timeout과 비의도 exhaustion만 error/retry threshold 분모에 포함한다. pre-change baseline과 candidate는 동일 machine/container image/dataset/seed로 각각 3회 측정하며 median과 분산을 report에 저장한다.
- [ ] benchmark assertion은 p95 상대 10%·p99 상대 15% 초과 또는 절대 p95 500ms·p99 1s 초과, error rate 1% 초과, retry exhaustion 0.1% 초과, lock-wait p95 50ms 초과에서 non-zero exit한다. report에는 CPU/JDK/PostgreSQL/container image/seed를 포함한다.
- [ ] codec backlog benchmark는 legacy-heavy 80/20과 current-heavy 20/80 payload mix, 각 10,000건, 500자 detail, warm-up 30초·측정 5분을 고정하고 throughput/p95/p99/decode failure/drain-time 및 동일 절대/상대 회귀 상한을 assertion한다. synthetic fairness harness와 실제 codec benchmark를 분리한다.
- [ ] `issue34.mode`는 report의 provenance metadata일 뿐 실행 경로를 바꾸지 않는다. baseline/candidate는 동일 코드의 mode만 바꿔 생성하지 말고, 승인된 pre-change 구현 또는 보존된 baseline artifact와 현재 candidate를 동일 환경에서 비교한다.
- [x] `./gradlew :appointment-notification:test`를 포함해 renderer/worker readiness와 rollback compatibility를 검증한다.
- [ ] tenant·clinic·patient가 서로 다른 실제 fixture로 IDOR 취소를 실행해 403/비존재형 오류와 state/audit/outbox/idempotency 무변경을 검증한다.
- [ ] detail이 DB/outbox에 저장되는 경계를 보존기간·ACL·DLQ/backup/provider log redaction 정책과 함께 문서화하고, PHI/PII 패턴 차단 또는 고정 안내문 mapping을 선택해 테스트한다.
- [x] `git diff --check`, Kotlin 7-tier review, frontend lint/build/test, backend module tests를 실행한다.
- [ ] 최종 검증에 `./gradlew :appointment-api:gatlingRun`과 notification mixed-schema benchmark 명령을 포함하고, 두 성능 artifact가 없으면 PR 준비 상태를 `PENDING`으로 유지한다.
- [ ] 최종 검증 명령은 `./gradlew :appointment-api:gatlingRun --simulation io.bluetape4k.clinic.appointment.api.PatientAppointmentCancelPostgresSimulation -Dissue34.baseline=... -Dissue34.candidate=... -Dissue34.sourceCommit=<measured-commit>`, `./gradlew :appointment-event:test --tests '*NotificationCodecBacklogBenchmarkTest*' -Dissue34.codec.benchmark=true -Dissue34.codec.mode=... -Dissue34.codec.mix=... -Dissue34.codec.run=... -Dissue34.sourceCommit=<measured-commit>`, `scripts/compare-issue34-benchmark.sh baseline.json candidate.json`, `node scripts/compare-issue34-codec-benchmark.mjs baseline-codec-dir candidate-codec-dir`으로 고정한다. report에 source commit을 기록하고 comparator는 baseline/candidate가 동일하거나 `unknown`인 provenance를 거부한다. CI job `issue34-performance-gate`가 report artifact를 업로드하고 comparator 실패를 merge blocker로 반환한다.
- [ ] 결과와 미검증 항목을 한국어 implementation review에 기록하고, P0/P1이 없을 때만 PR 준비 상태로 표시한다.

최종 검증 명령:

```bash
git diff --check
./gradlew :appointment-event:test :appointment-api:test :appointment-notification:test
./gradlew :appointment-api:gatlingRun --simulation io.bluetape4k.clinic.appointment.api.PatientAppointmentCancelPostgresSimulation -Dissue34.baseline=appointment-api/src/gatling/resources/benchmarks/issue-34/baseline.json -Dissue34.candidate=appointment-api/src/gatling/resources/benchmarks/issue-34/candidate.json -Dissue34.sourceCommit=<measured-commit>
./gradlew :appointment-event:test --tests '*NotificationCodecBacklogBenchmarkTest*' -Dissue34.codec.benchmark=true -Dissue34.codec.mode=baseline -Dissue34.codec.mix=legacy-heavy -Dissue34.codec.run=1 -Dissue34.sourceCommit=<measured-commit>
scripts/compare-issue34-benchmark.sh appointment-api/src/gatling/resources/benchmarks/issue-34/baseline.json appointment-api/src/gatling/resources/benchmarks/issue-34/candidate.json
node scripts/compare-issue34-codec-benchmark.mjs codec-baseline-dir codec-candidate-dir
cd frontend/appointment-frontend && npm test -- --watch=false && npm run build
```

## Plan self-review

- spec의 API, actor matrix, detail table, notification v2, status stepper, #305 분리,
  rollback과 검증 기준을 Task 1~7에 매핑했다.
- code 단계에는 DTO/command/codec/client/facade의 구체적 파일과 테스트 명령을
  적었고, “나중에 추가” 같은 placeholder를 두지 않았다.
- schema version은 event module의 `CURRENT_SCHEMA_VERSION=2`와 decoder v1/v2
  dual-read로 일관되며, cancellation template version만 2로 올리는 규칙을
  writer task에 반영했다.
- operator(ADMIN/STAFF)와 PATIENT의 detail 입력 경계를 matcher·controller·access
  resolver·application/command에 동일하게 매핑했다. 폐쇄형 notification 조합,
  canonical hash, 실제 IDOR fixture, cancel 전용 latency/경합 및 mixed-schema
  backlog 성능 게이트를 검증 task에 추가했다.
- producer/consumer rollout은 consumer-first와 writer feature flag로 분리했고,
  template catalog readiness·schema backlog 0 stop query·reminder lease fence
  경합·provider delivery unknown 정책을 명시했다.

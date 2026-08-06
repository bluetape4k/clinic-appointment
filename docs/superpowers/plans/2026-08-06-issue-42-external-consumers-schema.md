# Issue #42 External Consumers and Schema Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kafka 4 알림·통계 consumer가 JSON Schema 호환성, tenant-aware inbox dedup, retry/quarantine/replay 계약을 지키며 Issue #42 이벤트를 안전하게 처리하게 한다.

**Architecture:** `appointment-messaging`은 strict envelope decode, schema readiness, manual-ack consumer runtime, 복합키 inbox와 metadata-only quarantine을 소유한다. `appointment-notification`과 `appointment-api`는 각각 독립 group의 handler adapter만 제공하고 기존 durable notification outbox와 stats projection을 재사용한다.

**Tech Stack:** Kotlin 2.3, Spring Boot 4, Spring Kafka 4, Jackson 3, Exposed JDBC, Flyway V23 (H2/MySQL/PostgreSQL), JUnit 5/MockK, bluetape4k singleton Kafka launcher, kotlinx-benchmark/PostgreSQL.

---

## 변경 파일 지도

| 영역 | 파일 | 책임 |
|---|---|---|
| messaging contract | `appointment-messaging/src/main/kotlin/.../AppointmentConsumerContracts.kt` | consumer identity, provenance, result/failure/replay contract |
| messaging config | `AppointmentConsumerBindingProperties.kt`, `AppointmentConsumerProperties.kt` | immutable consumer/schema/retry 설정 |
| messaging schema | `src/main/resources/schemas/appointment-event-envelope-v1.schema.json`, `AppointmentSchemaRegistry.kt` | JSON Schema source와 local/HTTP compatibility readiness |
| messaging inbox | `AppointmentConsumerInboxTable.kt`, `AppointmentConsumerInboxStore.kt` | 복합 dedup key, 상태 전이, provenance, cleanup |
| messaging runtime | `AppointmentConsumerRuntime.kt`, `AppointmentKafkaConsumerConfiguration.kt` | strict dispatch, manual ack factory, retry/quarantine boundary |
| notification adapter | `appointment-notification/.../NotificationAppointmentEventConsumer.kt` | confirmation/reminder event를 durable notification outbox로 연결 |
| stats adapter | `appointment-api/.../AppointmentStatsProjection*` | tenant/date/status projection과 dashboard fallback |
| migration | `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V23__add_appointment_consumer_projection.sql` | inbox/quarantine/audit/projection schema |
| benchmark/docs | `benchmark/appointment-messaging-benchmark`, module README/runbook | PostgreSQL dedup p95와 운영 재현 계약 |

---

### Task 1: Consumer contract와 schema registry RED/GREEN

**Files:**
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentSchemaRegistryTest.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerContracts.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentSchemaRegistry.kt`
- Create: `appointment-messaging/src/main/resources/schemas/appointment-event-envelope-v1.schema.json`
- Modify: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentEventEnvelopeCodecTest.kt`

- [ ] **Step 1: Write the failing tests**

  `AppointmentSchemaRegistryTest`는 schemaVersion 1을 허용하고 0/2를 거부하며,
  resource의 required/allow-list field가 `AppointmentEventEnvelopeCodec`의 JSON keys와
  일치하는지 확인한다. `HttpAppointmentSchemaRegistry` 테스트는 fake `HttpClient`가
  `BACKWARD_TRANSITIVE` 응답을 주면 ready, 다른 compatibility/HTTP failure면 not-ready를
  반환하는지 검증한다. contract 테스트는 consumer/stream/event id 길이와 blank 값 거부,
  replay 요청의 dry-run/승인자 필수 조건을 고정한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-messaging:test --tests '*AppointmentSchemaRegistryTest'`를 실행해
  아직 없는 contract/registry symbol 때문에 실패하는 것을 확인한다.

- [ ] **Step 3: Implement the minimum**

  `AppointmentSchemaRegistry`는 `validate(schemaVersion)`와 `readiness()`만 노출한다.
  `StaticAppointmentSchemaRegistry`는 현재 schemaVersion만 허용한다. HTTP 구현은
  `java.net.http.HttpClient`로 `/config/{subject}`를 bounded timeout에 조회하고
  response의 compatibility를 exact-match한다. `AppointmentConsumer*` value class는
  `require`로 bounded identifier와 positive tenant/clinic을 검증한다. schema resource는
  기존 envelope의 required fields와 payload discriminator를 명시하고
  `additionalProperties=false`를 사용한다.

- [ ] **Step 4: Run GREEN**

  같은 targeted test와 기존 `AppointmentEventEnvelopeCodecTest`를 실행한다. expected는
  모든 test PASS, raw JSON 출력 0건이다.

- [ ] **Step 5: Commit**

  `git add appointment-messaging/src/.../AppointmentConsumer* appointment-messaging/src/.../AppointmentSchemaRegistry.kt appointment-messaging/src/main/resources/schemas appointment-messaging/src/test`
  후 `Define JSON schema and consumer identity contracts` Lore commit을 만든다.

### Task 2: Inbox/quarantine table과 Exposed store

**Files:**
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerInboxStoreTest.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerInboxTable.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerInboxStore.kt`
- Create: `appointment-api/src/main/resources/db/migration/h2/V23__add_appointment_consumer_projection.sql`
- Create: `appointment-api/src/main/resources/db/migration/mysql/V23__add_appointment_consumer_projection.sql`
- Create: `appointment-api/src/main/resources/db/migration/postgresql/V23__add_appointment_consumer_projection.sql`

- [ ] **Step 1: Write failing H2 tests**

  `SchemaUtils.createMissingTablesAndColumns`로 inbox table을 만들고 insert-if-absent가
  NEW/DUPLICATE를 반환하는지, 다른 consumer/stream은 같은 eventId를 독립 처리하는지,
  processed/retry/quarantine 전이가 provenance와 hash만 남기는지, cleanup이 active
  processing row를 보존하는지 테스트한다. 모든 호출은 `transaction(db)` 안에서 실행한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-messaging:test --tests '*AppointmentConsumerInboxStoreTest'`를
  실행해 table/store 미구현 실패를 확인한다.

- [ ] **Step 3: Implement minimum Exposed store**

  복합 PK는 `(logical_consumer_id, logical_stream_id, event_id)`로 두고 topic/partition/
  offset/schemaVersion/tenant/clinic/payloadSha256/status/attempt/receivedAt/
  processedAt/failureCode를 저장한다. `begin()`은 insert 실패를 duplicate로 판별하고,
  `markProcessed`, `markRetry`, `markQuarantined`, `cleanupProcessed`는 현재 status와
  event id를 조건으로 하는 fenced update를 사용한다. `transaction(database)`를 명시해
  Spring이 주입한 Exposed `Database`를 사용하며 raw payload column은 만들지 않는다.

- [ ] **Step 4: Add V23 migrations and migration assertions**

  세 dialect에 `scheduling_appointment_consumer_inbox`,
  `scheduling_appointment_consumer_quarantine`, `scheduling_appointment_stats_projection`,
  `scheduling_appointment_consumer_replay_audit`와 status/tenant/date index를 추가한다.
  기존 `scheduling_*` 이름을 유지하고 auto-create SQL은 추가하지 않는다.

- [ ] **Step 5: Run GREEN and migration checks**

  `./gradlew :appointment-messaging:test :appointment-api:test --tests '*Migration*'`와
  store targeted test를 실행한다. H2가 pass하고 API Flyway resource가 세 dialect 모두
  동일한 column 의미를 가지는지 `diff -u`/SQL parser check로 확인한다.

- [ ] **Step 6: Commit**

  `Persist tenant-scoped consumer inbox and quarantine metadata` Lore commit을 만든다.

### Task 3: Consumer runtime와 Spring Kafka 4 manual-ack wiring

**Files:**
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerRuntimeTest.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerRuntime.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentKafkaConsumerConfiguration.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerBindingProperties.kt`
- Modify: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingBindingProperties.kt`
- Modify: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingAutoConfiguration.kt`
- Modify: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingReadinessValidator.kt`

- [ ] **Step 1: Write failing runtime tests**

  fake inbox/handler/registry로 정상 처리의 순서를 `decode -> schema -> begin -> handler
  -> processed -> ack`로 확인한다. duplicate는 handler를 호출하지 않고 ack하며,
  schema/scope failure는 metadata-only quarantine 후 ack한다. transient handler
  failure는 retryable result를 반환하고 processed/ack를 생략하며, attempt exhaustion은
  quarantine/ack로 끝난다. record key가 canonical partition key와 다르면
  `PARTITION_KEY_MISMATCH`를 확인한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-messaging:test --tests '*AppointmentConsumerRuntimeTest'`를
  실행해 runtime symbols가 없어 실패하는지 확인한다.

- [ ] **Step 3: Implement runtime**

  `AppointmentConsumerRuntime.consume(record, ack, handler)`는 `ConsumerRecord<String,
  String>` provenance를 bounded metadata로 만들고 codec/schema/scope를 먼저 검증한다.
  `begin`이 NEW일 때만 handler를 실행하며 성공 직후 processed를 기록하고 ack를 호출한다.
  `AppointmentConsumerRetryableException`만 container error handler로 전파하고,
  schema/scope/unknown event/attempt exhaustion은 quarantine publisher를 호출한 뒤 ack한다.
  quarantine body에는 eventId hash와 failure code만 포함한다. log/metric에는 eventId 원문,
  tenant/clinic ID, payload를 넣지 않는다.

- [ ] **Step 4: Add Spring container configuration**

  `@ConditionalOnClass(name = ["org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory"])`
  와 `@ConditionalOnBean(ConsumerFactory::class)`를 사용해 manual `AckMode` factory를
  등록한다. topic auto-create는 false로 고정하고 configured allow-list 밖의 topic은
  시작을 거부한다. 각 app adapter가 group/consumer identity를 명시할 수 있도록
  `appointment.messaging.consumer.*` properties를 constructor-bound로 제공한다.

- [ ] **Step 5: Run GREEN**

  runtime/auto-configuration tests와 `./gradlew :appointment-messaging:test`를 실행한다.
  ContextRunner에서 Kafka/DataSource class가 없을 때 consumer beans가 생기지 않는지,
  presence 시 readiness bean이 생성되는지 확인한다.

- [ ] **Step 6: Commit**

  `Add Kafka 4 manual-ack consumer runtime` Lore commit을 만든다.

### Task 4: Notification consumer adapter

**Files:**
- Modify: `appointment-notification/build.gradle.kts`
- Create: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAppointmentEventConsumer.kt`
- Create: `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAppointmentEventConsumerTest.kt`
- Modify: `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationAutoConfiguration.kt`

- [ ] **Step 1: Write failing tests**

  CREATED/CANCELLED/RESCHEDULED와 CONFIRMED status event가
  `NotificationDirectDeliveryPort`에 올바른 type/scope/id로 전달되는지, CONFIRMED 이외
  status는 무시되는지, port 외 provider/channel을 직접 호출하지 않는지 테스트한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-notification:test --tests '*NotificationAppointmentEventConsumerTest'`
  를 실행해 adapter 미구현 실패를 확인한다.

- [ ] **Step 3: Implement adapter and auto-config**

  notification module에 `implementation(project(":appointment-messaging"))`를 추가하고,
  `NotificationAppointmentEventConsumer`가 `AppointmentConsumerHandler`를 구현한다.
  event mapping은 기존 `NotificationEventType`만 사용하며, `NotificationDirectDeliveryPort`
  가 소유한 durable outbox/worker/fencing 경계를 넘지 않는다. `@ConditionalOnProperty`와
  `@ConditionalOnBean(NotificationDirectDeliveryPort::class)`로 opt-in bean을 만들고,
  Kafka listener는 `appointment-notification-v1` group과 `appointment-events` stream을
  사용한다.

- [ ] **Step 4: Run GREEN and module tests**

  targeted test 후 `./gradlew :appointment-notification:test`를 실행한다.

- [ ] **Step 5: Commit**

  `Consume appointment events through the durable notification outbox` Lore commit을 만든다.

### Task 5: Statistics projection consumer와 dashboard fallback

**Files:**
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/stats/AppointmentStatsProjectionTable.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/stats/AppointmentStatsProjectionRepository.kt`
- Create: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/stats/AppointmentStatsProjectionConsumer.kt`
- Create: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/stats/AppointmentStatsProjectionConsumerTest.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/config/ServiceConfig.kt`
- Modify: `appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/DashboardStatsService.kt`
- Modify: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/service/DashboardStatsServiceTest.kt`

- [ ] **Step 1: Write failing projection tests**

  같은 tenant/clinic/date/status row의 event version을 monotonic하게 upsert하고 낮은
  version/duplicate를 무시하는지, 다른 tenant/clinic이 섞이지 않는지, dashboard가
  projection row가 있는 범위에서 projection을 사용하고 empty projection에서는 기존
  repository로 fallback하는지 고정한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-api:test --tests '*AppointmentStatsProjection*' --tests '*DashboardStatsServiceTest'`
  로 실패를 확인한다.

- [ ] **Step 3: Implement projection and handler**

  projection primary key는 `(tenant_group_id, clinic_id, event_date, status)`이며
  `last_event_version`보다 낮은 event는 update하지 않는다. event date는
  `occurredAt`의 UTC date로 고정한다. 모든 repository method는 `transaction {}` 내부에서
  실행하고, handler는 envelope scope를 다시 검증한다. `DashboardStatsService`는
  optional projection repository를 받아 non-empty projection을 우선 사용하고 기존
  `AppointmentStatsRepository`를 fallback으로 보존한다. service 공개 API와 tenant
  ownership guard는 변경하지 않는다.

- [ ] **Step 4: Wire Spring beans and Kafka group**

  `ServiceConfig`에 `@ConditionalOnBean(Database::class)` projection repository와
  `@ConditionalOnProperty("appointment.messaging.consumer.statistics.enabled")` handler를
  등록한다. statistics group은 `appointment-statistics-v1`로 고정한다.

- [ ] **Step 5: Run GREEN**

  targeted tests와 `./gradlew :appointment-api:test`를 실행한다. projection SQL과
  existing dashboard contract가 모두 pass해야 한다.

- [ ] **Step 6: Commit**

  `Project appointment statistics from tenant-scoped events` Lore commit을 만든다.

### Task 6: Retry/quarantine/replay operations

**Files:**
- Create: `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentReplayServiceTest.kt`
- Create: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentReplayService.kt`
- Modify: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerInboxStore.kt`
- Modify: `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentConsumerRuntime.kt`
- Create: `docs/operations/appointment-consumer-replay-runbook.md`

- [ ] **Step 1: Write failing tests**

  replay request가 승인자/consumer/scope/offset와 dry-run flag 없이는 거부되고,
  dry-run은 handler를 호출하지 않고 audit row만 기록하며, approved execution은
  별도 replay group identity를 사용하고 operations group offset을 변경하지 않는지
  검증한다. quarantine/cleanup은 raw value를 반환하지 않아야 한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-messaging:test --tests '*AppointmentReplayServiceTest'`를
  실행한다.

- [ ] **Step 3: Implement bounded replay service**

  `AppointmentReplayRequest`를 검증하고 audit repository에 request hash, approver,
  range, dry-run/result만 기록한다. dry-run은 schema/scope/inbox 상태만 평가한다.
  execution은 `appointment-<consumer>-replay-<auditId>` logical group으로 dispatch하며,
  original group의 offset rewind API를 제공하지 않는다. cleanup은 processed/quarantined
  age와 batch size를 bounded property로 받고 processing 상태를 제외한다.

- [ ] **Step 4: Run GREEN and review runbook**

  targeted test와 `git diff --check`를 실행한다. runbook에는 승인 순서, dry-run 출력,
  quarantine metadata, rollback이 offset rewind/topic delete가 아님을 Korean prose로
  기록한다.

- [ ] **Step 5: Commit**

  `Add audited dry-run replay and bounded quarantine cleanup` Lore commit을 만든다.

### Task 7: Kafka singleton integration, PostgreSQL benchmark, docs

**Files:**
- Create/Modify: `appointment-messaging/src/test/kotlin/.../AppointmentKafkaConsumerIntegrationTest.kt`
- Modify: `benchmark/appointment-messaging-benchmark/src/...`
- Modify: `appointment-messaging/README.md`, `appointment-messaging/README.ko.md`
- Modify: `appointment-notification/README.md`, `appointment-notification/README.ko.md` (if present)
- Modify: `README.md`, `README.ko.md` only for source-equivalent backend links/table rows

- [ ] **Step 1: Write failing integration/benchmark assertions**

  singleton Kafka 4 launcher로 one event/duplicate event의 handler invocation count와
  manual ack 결과를 확인한다. benchmark source는 PostgreSQL inbox rows 10k/100k와
  duplicate lookup p95/cleanup batch를 측정하고 warm-up/iterations/DB URL redaction을
  assertion/documentation으로 고정한다.

- [ ] **Step 2: Run RED**

  `./gradlew :appointment-messaging:test --tests '*AppointmentKafkaConsumerIntegrationTest'`
  와 `./gradlew :benchmark:appointment-messaging-benchmark:...`의 실제 task name을
  `tasks --all`로 확인한 뒤 실행해 미구현 실패를 기록한다.

- [ ] **Step 3: Implement integration and benchmark**

  `@Testcontainers`는 사용하지 않고 repository singleton launcher와 기존 PostgreSQL
  benchmark harness를 재사용한다. README는 exact command, environment, row count,
  p50/p95, caveat를 함께 적으며 synthetic tenant IDs만 사용한다. 새로운 PNG/chart는
  만들지 않고 기존 measured table/benchmark artifact를 source-equivalent EN/KO로
  유지한다.

- [ ] **Step 4: Run GREEN**

  messaging/notification/api targeted tests, integration test, benchmark, `git diff --check`
  를 실행한다. PostgreSQL이 unavailable이면 benchmark 결과를 PASS로 표시하지 않고
  PENDING evidence로 남긴다.

- [ ] **Step 5: Commit**

  `Document and measure Kafka consumer deduplication` Lore commit을 만든다.

### Task 8: Full verification, PR, CI, merge-ready closeout

**Files:**
- Modify: `TODO.md` issue #42/schema checklist rows
- Create: `docs/superpowers/lessons/2026-08-06-issue-42-external-consumers-schema.md`

- [ ] **Step 1: Run full verification**

  `./gradlew :appointment-messaging:test :appointment-notification:test :appointment-api:test`
  와 필요한 `build`, `git diff --check`, migration/static checks를 실행한다. 모든
  production Exposed query가 transaction boundary를 갖고 `!!`, blocking event-loop,
  raw PII logging이 없는지 `rg`로 확인한다.

- [ ] **Step 2: Independent review lenses**

  workflow/security/SRE/performance/Kotlin/API review lens를 각각 기록하고, blocker가
  있으면 먼저 수정 후 재검증한다. review evidence에는 파일/line과 재현 명령을 넣는다.

- [ ] **Step 3: Write lesson and update project tracking**

  Korean lesson에는 JSON Schema 선택, composite dedup key, metadata-only quarantine,
  PostgreSQL benchmark 결과와 미검증 항목을 기록한다. `TODO.md`는 구현된 #42 rows만
  체크한다.

- [ ] **Step 4: Commit, create English PR, and verify metadata**

  Lore commit으로 lesson/tracking을 저장하고 `gh pr create --base develop --head
  feat/issue-42-external-consumers-schema`를 실행한다. PR body는 Issue #42를 link하고
  assignee/label/milestone을 issue와 맞추며 마지막 section은 `## DoD Status`로 둔다.

- [ ] **Step 5: CI and merge-ready report**

  live PR body, checks, review threads, exact head SHA, worktree/root dirty state를
  재확인한다. CI failure는 `gh-fix-ci` 절차로 고치고, fresh exact-head merge approval
  전에는 merge하지 않는다.

- [ ] **Step 6: Approved merge and local sync**

  fresh approval 후 PR을 merge하고, root `develop`을 fetch/ff-only sync한다. 사용자가
  소유한 기존 root `README.ko.md` 변경은 보존한다. feature worktree/branch를 삭제하고
  `git worktree list`, `git status`, `git rev-parse`로 root/remote parity와 정리를
  증명한다.

## Plan self-review

- Spec coverage: schema, consumer runtime, notification, statistics, failure/replay,
  migration, benchmark, docs, PR/merge를 Task 1–8에 각각 배치했다.
- Placeholder scan: 미완성 토큰과 미정 표현이 없다. benchmark task의 `tasks --all`는
  repository-specific task name을 확인하기 위한 명령이며 구현 공백이 아니다.
- Type consistency: 모든 adapter는 `AppointmentConsumerHandler`, store는
  `AppointmentConsumerInboxStore`, schema는 `AppointmentSchemaRegistry`, 두 group은
  `appointment-notification-v1`/`appointment-statistics-v1`로 고정했다.

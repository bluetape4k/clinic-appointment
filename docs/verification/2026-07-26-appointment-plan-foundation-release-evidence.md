# 예약 플랜 기반 릴리스 검증 증거

## 검증 기준

- 구현 기준: `8007294542c060866101369a620c162c513f6c3c`
- 문서 기준: 위 구현 커밋을 설명하는 후속 증거 커밋
- 검증 일자: 2026-07-27 KST
- 범위: catalog snapshot, plan aggregate, V8, catalog sync, purchase convergence,
  clinic-operator plan read
- 운영 배포 상태: 수행하지 않음
- 운영 consumer `WRITE`: 차단 유지

## 실행 증거

| 항목 | 명령/증거 | 결과 |
|------|-----------|------|
| H2 legacy 보존 | `FlywayMigrationTest` | PASS, V8 전후 legacy appointment 비교 |
| PostgreSQL legacy 보존 | `FlywayPostgreSQLMigrationTest` | PASS |
| MySQL 8 legacy 보존 | `FlywayMySQLMigrationTest` | PASS |
| backfill | 설계상 기존 plan/item projection 없음 | N/A, 후속 item model 단계로 명시 |
| scoped OFF/SHADOW | `PlanFoundationFeatureControlResolverTest`, `PlanFoundationPropertiesValidatorTest`, `PurchaseCompletedHandlerTest` | PASS, exact tenant/clinic override만 SHADOW/enable되고 다른 scope는 OFF/disabled; SHADOW에서 inbox/plan/outbox 0건 |
| test-only WRITE | `PurchaseCompletedDialectIntegrationTest` | PASS, H2/PostgreSQL/MySQL |
| schema 호환 | handler current/previous schema test | PASS, schema 1·2를 current command로 정규화 |
| duplicate/out-of-order/gap | handler와 dialect integration tests | PASS, attempt 5에서 quarantine |
| 동시성 | barrier 기반 same-event/same-purchase race | PASS, dialect별 plan/outbox 각 1건 |
| catalog 동시 수신 | barrier 기반 same-version/same-payload 및 same-version/different-payload race | PASS, 각각 `CREATED + UNCHANGED`, `CREATED + VERSION_CONFLICT`로 수렴 |
| 구매→plan 성능 | `PurchasePlanPerformanceIntegrationTest`, PostgreSQL/MySQL 각 warm-up + 독립 transaction 10회, 매 sample 전체 transaction SQL capture | PASS, 모든 sample이 정확히 18 statements 및 동일 class 상한 통과; terminal rejection 조회를 포함해 bounded SQL shape 유지 |
| canonical catalog payload | `CatalogProductVersionPayloadBoundTest` | PASS, 최대 compact graph의 실제 Jackson 3 API payload 194,876 bytes / 한도 262,144 bytes |
| scoped read 실행계획 | `AppointmentPlanReadExplainIntegrationTest`, PostgreSQL/MySQL, 100,000 plan/inbox/outbox·20 partition·20 dependency plan | PASS, `uq_plan_source_purchase`, `idx_treatment_dependency_plan`, `idx_inbox_status_replay_after_received`, `idx_outbox_status_next_attempt`, `idx_outbox_status_created_at` 자연 선택; MySQL rows 1/1,000/1,000/1/1,000, PostgreSQL bounded Limit/Bitmap Index Scan |
| FK cleanup index | V8 migration tests + representative fixture cleanup | PASS, `idx_outbox_plan_id`, `idx_treatment_dependency_successor`를 H2/PostgreSQL/MySQL에 고정; PostgreSQL cleanup 116,489ms → 16,953ms |
| 환자 참조 격리 | `PatientReferenceProtectorTest`, redrive test | PASS, 동일 tenant에서는 안정적 fingerprint, tenant가 다르면 다른 fingerprint |
| OpenAPI·오류 계약 | controller enabled/disabled tests | PASS, path discoverable, sanitized 400/404/500 |
| tenant/clinic 보안 | JWT security integration tests | PASS, operator role + tenant + exact clinic |
| 변경 모듈 전체 빌드 | `:appointment-core:build`, `:appointment-event:build`, `:appointment-api:build`, 모두 `--no-build-cache`로 순차 실행 | PASS, core 302 / event 53 / API 178 tests, failures 0, API 외부 DB profile 전용 2 skipped 포함 |
| 독립 2-R/3-R/7-Tier | 각 6개 독립 lens와 main integration | PASS, 모든 gate `P0=0 P1=0`; 상세는 review evidence 참조 |

## 재현 가능한 성능·실행계획 증거

모든 명령은 구현 커밋 `8007294542c060866101369a620c162c513f6c3c`
내용과 동일한 코드에서 순차 실행했다. 테스트 결과 XML은 동일 Gradle test
task의 다음 실행이 덮어쓰므로, 이 절에 dialect별 원시 핵심 출력을 함께
보존한다.

| KST 시각 | 검증 | 정확한 명령 | 결과/생성 경로 |
|---|---|---|---|
| 2026-07-27 08:01 | core 전체 빌드 | `./gradlew :appointment-core:build --no-build-cache` | `BUILD SUCCESSFUL in 48s`; `appointment-core/build/reports/tests/test/index.html` |
| 2026-07-27 08:02 | event 전체 빌드 | `./gradlew :appointment-event:build --no-build-cache` | `BUILD SUCCESSFUL in 5s`; `appointment-event/build/reports/tests/test/index.html` |
| 2026-07-27 10:40 | API 전체 빌드 | `./gradlew :appointment-api:build --no-daemon --no-build-cache` | `178 tests`, `0 failures`, `2 skipped`, `BUILD SUCCESSFUL in 28s`; `appointment-api/build/reports/tests/test/index.html` |
| 2026-07-27 08:03 | PostgreSQL 성능 | `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest' -Dspring.profiles.active=test,test-postgresql --no-build-cache` | `1 passing`, `BUILD SUCCESSFUL in 30s`; `appointment-api/build/test-results/test/TEST-io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest.xml` |
| 2026-07-27 08:04 | MySQL 성능 | `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.integration.PurchasePlanPerformanceIntegrationTest' -Dspring.profiles.active=test,test-mysql --no-build-cache` | `1 passing`, `BUILD SUCCESSFUL in 1m 22s`; 같은 XML 경로 |
| 2026-07-27 08:06 | PostgreSQL EXPLAIN | `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest' -Dspring.profiles.active=test,test-postgresql --no-build-cache` | `1 passing`, `BUILD SUCCESSFUL in 1m 45s`; `appointment-api/build/test-results/test/TEST-io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest.xml` |
| 2026-07-27 08:08 | MySQL EXPLAIN | `./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.integration.AppointmentPlanReadExplainIntegrationTest' -Dspring.profiles.active=test,test-mysql --no-build-cache` | `1 passing`, `BUILD SUCCESSFUL in 1m 31s`; 같은 XML 경로 |

성능 원시 표본:

```text
POSTGRESQL typical samplesMs=[26, 25, 23, 22, 18, 17, 17, 15, 14, 12] p95Ms=26
POSTGRESQL maximum samplesMs=[1287, 1337, 1325, 1277, 1347, 1349, 1278, 1276, 1340, 1309] p95Ms=1349
MYSQL typical samplesMs=[15, 14, 14, 15, 13, 14, 15, 17, 16, 15] p95Ms=17
MYSQL maximum samplesMs=[797, 758, 728, 724, 698, 722, 709, 705, 725, 709] p95Ms=797
```

각 dialect의 20개 measured sample은 모두 다음 SQL 모양을 만족했다.

```text
total=18 inboxReads=2 planReads=2 catalogReads=2 untrustedRejectionReads=1 treatmentWrites=1 dependencyWrites=1
```

PostgreSQL 원시 EXPLAIN 핵심:

```text
fixture plans=100000 partitions=20 dependencyPlans=20 seedMs=72553 analyzeMs=4571
uq_plan_source_purchase estimatedRows=1 Index Scan
idx_treatment_dependency_plan estimatedRows=1000 Bitmap Index Scan
idx_inbox_status_replay_after_received estimatedRows=25 Limit; underlying rows=1013
idx_outbox_status_next_attempt estimatedRows=1 Limit / Index Scan
idx_outbox_status_created_at estimatedRows=25 Limit; underlying rows=1027
cleanupMs=16994
```

MySQL 원시 EXPLAIN 핵심:

```text
fixture plans=100000 partitions=20 dependencyPlans=20 seedMs=8424 analyzeMs=75
uq_plan_source_purchase rows=1 type=const
idx_treatment_dependency_plan rows=1000 type=ref
idx_inbox_status_replay_after_received rows=1000 type=range
idx_outbox_status_next_attempt rows=1 type=range
idx_outbox_status_created_at rows=1000 type=ref
cleanupMs=9707
```

MySQL EXPLAIN 테스트의 assertion과 `BUILD SUCCESSFUL` 이후 Spring context 종료
과정에서 bluetape4k near-cache의 `CLIENT TRACKING` 해제 명령이 Redis
Testcontainer 종료 순서와 겹쳐 1분 timeout 경고가 한 번 발생했다. 이는
EXPLAIN/DB 쿼리 실패나 운영 Redis 장애 증거가 아니라 test JVM shutdown
sequencing 경고다. 테스트 결과와 인덱스 assertion은 모두 그 전에
성공했으며, 운영 `WRITE` 차단 상태에도 영향이 없다.

## 아직 충족하지 않은 운영 게이트

다음 항목은 foundation 코드의 배포 전제라기보다 운영 `WRITE`를 여는 전제입니다.
이번 로컬 구현에서는 실행 증거를 만들지 않았으므로 PASS로 기록하지 않습니다.

| 게이트 | 상태 | 다음 증거 |
|------|------|----------|
| outbox backlog/oldest-age metric | BLOCKED | transport 구현과 ack 상태 모델 |
| trust failure, 80%, 95% backpressure alert smoke | BLOCKED | 운영 metric/alert wiring |
| outbox publish/ack/retry/DLQ | BLOCKED | 후속 transport 계획과 장애 주입 결과 |

따라서 `purchase-consumer-mode=WRITE`의 운영 활성화는 금지됩니다. 현재
`PlanFoundationPropertiesValidator`도 transport capability가 없으면 이 설정을
거부합니다.

## 롤백 리허설

자동화된 테스트에서 `catalog-sync-enabled=false`와 `plan-read-enabled=false`가
각각 `404 FEATURE_DISABLED`를 반환하고 OpenAPI 경로는 유지됨을 확인했습니다.
`purchase-consumer-mode=OFF/SHADOW`는 plan/outbox를 만들지 않습니다. 기존
plan/inbox/outbox history를 삭제하는 롤백 코드는 없습니다.

## 문서 parity

영문/국문 pair:

- `README.md` / `README.ko.md`
- `appointment-core/README.md` / `appointment-core/README.ko.md`
- `appointment-event/README.md` / `appointment-event/README.ko.md`
- `appointment-api/README.md` / `appointment-api/README.ko.md`

각 pair는 plan boundary, 기능 범위, endpoint/property 이름, 운영 `WRITE` 차단을
동일하게 설명합니다.

# 예약 플랜 기반 릴리스 검증 증거

## 검증 기준

- 구현 기준: `4ef35cd3d3e99bbea8dfebdf18f8a654f07bbe22` 이후의 최종 작업 트리
  (이 문서를 포함한 최종 커밋은 아래 증거를 모두 통과한 뒤 생성)
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
| OFF/SHADOW | `PlanFoundationPropertiesTest`, `PurchaseCompletedHandlerTest` | PASS, SHADOW에서 inbox/plan/outbox 0건 |
| test-only WRITE | `PurchaseCompletedDialectIntegrationTest` | PASS, H2/PostgreSQL/MySQL |
| schema 호환 | handler current/previous schema test | PASS, schema 1·2를 current command로 정규화 |
| duplicate/out-of-order/gap | handler와 dialect integration tests | PASS, attempt 5에서 quarantine |
| 동시성 | barrier 기반 same-event/same-purchase race | PASS, dialect별 plan/outbox 각 1건 |
| catalog 동시 수신 | barrier 기반 same-version/same-payload 및 same-version/different-payload race | PASS, 각각 `CREATED + UNCHANGED`, `CREATED + VERSION_CONFLICT`로 수렴 |
| 환자 참조 격리 | `PatientReferenceProtectorTest`, redrive test | PASS, 동일 tenant에서는 안정적 fingerprint, tenant가 다르면 다른 fingerprint |
| OpenAPI·오류 계약 | controller enabled/disabled tests | PASS, path discoverable, sanitized 400/404/500 |
| tenant/clinic 보안 | JWT security integration tests | PASS, operator role + tenant + exact clinic |
| 전체 모듈 테스트 재실행 | 모듈별 `test --rerun-tasks --no-daemon` 순차 실행 | PASS, core 289 / event 21 / solver 61 / notification 44 / API 164 |
| 전체 빌드·정적 분석 | 5개 모듈 `build` + root `detekt` | PASS |
| 독립 코드 리뷰 | foundation final review | PASS, `P0=0 P1=0 P2=0` |

## 아직 충족하지 않은 운영 게이트

다음 항목은 foundation 코드의 배포 전제라기보다 운영 `WRITE`를 여는 전제입니다.
이번 로컬 구현에서는 실행 증거를 만들지 않았으므로 PASS로 기록하지 않습니다.

| 게이트 | 상태 | 다음 증거 |
|------|------|----------|
| 2,000 treatment / 10,000 edge PostgreSQL·MySQL p95 < 30초 | BLOCKED | 전용 fixture와 반복 benchmark artifact |
| scoped purchase/dependency/inbox/outbox `EXPLAIN` | BLOCKED | 대표 row count를 적재한 두 dialect 실행계획 |
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

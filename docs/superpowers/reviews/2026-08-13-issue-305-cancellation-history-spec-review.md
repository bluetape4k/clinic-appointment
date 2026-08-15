# Issue #305 취소 이력 조회 설계 검토

## 검토 범위

- 저장소: `bluetape4k/clinic-appointment`
- 기준 코드: `444e5cfa23634352093a2eff8e1b2d2da85c5163`
- 설계 사양: `docs/superpowers/specs/2026-08-13-issue-305-cancellation-history-design.md`
- 검토 기준: Type A 6각(Security, Performance, Stability, API/User, Operations)과 7-tier
  review, `bluetape-kotlin-patterns`, 한국어 문서 SPW-01..05
- `frontend/appointment-frontend/angular.json`은 독립 변경으로 이번 범위에서 제외했다.

## 최종 설계 판정

최신 working-copy 사양은 cursor registry의 암호화 검증 순서와 장애 경계를 반영했다.
인증된 미만료 cursor의 `missing_entry`는 client 400이 아니라 `503
PATIENT_HISTORY_UNAVAILABLE`로 처리하고, `missing_entry` metric, readiness=false, endpoint
flag-off, alert, partial-page 금지, rollout/DoD gate를 함께 요구한다.

| 검토 lane | P0 | P1 | P2 | P3 | 상태 |
|---|---:|---:|---:|---:|---|
| Security | 0 | 0 | 0 | 0 | ACCEPT (설계) |
| Performance | 0 | 0 | 0 | 0 | ACCEPT (설계) |
| Stability | 0 | 0 | 0 | 0 | ACCEPT (설계) |
| API / User / UX | 0 | 0 | 0 | 0 | ACCEPT (설계) |
| Operations | 0 | 0 | 0 | 0 | ACCEPT (설계) |
| 통합 | 0 | 0 | 0 | 0 | ACCEPT (설계) |

통합 설계 결과는 **P0=0, P1=0, P2=0, P3=0, CLEAR**다. 주요 종료 근거는 다음과
같다.

- cursor는 outer grammar → AES-GCM authenticated decrypt/tag/AAD → payload bounds와
  `issuedAtBucket` → registry key 재계산 → constant-time token/issuedAt 비교 순서를 따른다.
- malformed·변조·만료·unknown/retired key·tag 오류는 400으로, registry timeout·unavailable·
  capacity·linearizability·authenticated unexpired `missing_entry`는 503으로 분리한다.
- registry failure는 bounded metric과 readiness에 반영하고, missing entry/readiness false는
  endpoint flag-off와 alert를 유발한다. partial page나 임의 새 token을 반환하지 않는다.
- tenant A→B 전환은 `RequestEpoch++`, `tenantIdentityGeneration=null`, 동기 cache/state purge,
  no-cache bootstrap으로 고정한다. 401도 purge 후 navigation하며 delayed response는 tuple로 폐기한다.
- public TypeScript query에는 identity/cache를 노출하지 않고 private tuple-keyed cache만 허용한다.
- V27 legacy와 V28 nullable additive migration, patient-scope index, bounded retry, ETag/304,
  nullable UI field와 접근성 fallback을 모듈별 DoD에 연결했다.

## 검증 증거

- 사양 read-back: 785줄, SHA-256 `6aa63b3cd299eade55ef29fa532e3e67e35b1713071170338a2532ae7179d320`.
- `git diff --no-index --check /dev/null docs/superpowers/specs/2026-08-13-issue-305-cancellation-history-design.md`: whitespace 오류 없음.
- workflow `verify`: run `20260813T122143Z-b06b97df`, sequence 26, receipt checksum
  `eee404c4c25bad0e0026602c0796f3146941eb19d23082441e10c6c5f8857554`.
- workflow `mutation-check`: 승인된 7개 모듈 범위에서 통과.
- SPW-01..05: 사양에 각 체크 결과와 traceability를 기록했다.

## 경계와 미실행 항목

`CLOSED`는 설계 수준 판정이며 구현 테스트 통과를 의미하지 않는다. 실제 구현 전 RED 테스트,
세 dialect Flyway smoke, protected backend E2E, PostgreSQL EXPLAIN/성능, production ACL·backup·
canary·SLO 증거는 아직 생성되지 않았다. production gate는 명시적으로 `PENDING`이다.

## 구현 후 코드 검토 및 검증 갱신

구현 검토에서 다음 경계를 추가로 확인했다.

- API가 활성화될 때 외부 `PatientHistoryTokenRegistry`와
  `PatientHistoryTenantIdentityGenerationProvider`가 없으면 startup을 중단한다.
- controller의 200/304 응답은 provider가 반환한 `X-Tenant-Identity-Generation`을
  동일 grammar로 검증한 뒤 전송한다.
- cursor payload validation 오류는 raw `IllegalArgumentException`으로 노출하지 않고
  `PATIENT_HISTORY_PAYLOAD_INVALID`로 정규화한다.
- portal logout과 history 401 처리에서 private history cache, entries, cursor와
  request epoch를 먼저 비우고 늦은 응답을 적용하지 않는다.
- `angular.json`은 이번 변경의 파일 목록과 검증 범위에서 제외했다.

검증 결과:

- `:appointment-api:compileKotlin` 및 환자 이력 targeted 14개 테스트 통과.
- `npm test -- --watch=false`: 39개 파일/266개 테스트 통과.
- `npm run build` 성공.
- core 신규 schema targeted 테스트는 H2/MySQL 통과, PostgreSQL은 Colima Docker
  socket mount 오류로 미실행.
- 전체 core/api 테스트는 기존 병렬 H2 table-isolation 오류가 포함되어 702개 중
  181개 실패했지만, 단일 스레드·parallel disabled 재실행에서는 702개 중 2개만
  Testcontainers Colima Docker socket mount 오류(`operation not supported`)로 실패했다.
  두 실패는 `TableSchemaTest`와 `WaitlistTableSchemaTest`이며 신규 환자 이력 코드 실패 증거는 없다.
- Kotlin 패턴 보강 후 `KotlinProductionPatternComplianceTest` 7개와 cursor codec 6개를
  포함한 13개 targeted 테스트가 통과했다. 새 production source에는 `!!`가 없고,
  새 테스트는 `io.bluetape4k.assertions.assertFailsWith`를 사용한다.
- `git diff --check` 통과. `angular.json`은 변경 목록에 없다.

설계 기준 종합 판정은 **P0=0, P1=0, P2=0, P3=0, CLEAR**다. 실제 구현의
protected HTTP, shared registry readiness, PostgreSQL EXPLAIN/성능, production
ACL·backup·canary·SLO는 외부 환경 증거가 없어 `PENDING`이며, 구현 merge gate는 아래
최신 재검토의 `P1/BLOCK`을 따른다.

## 최신 구현 재검토 판정(2026-08-14)

위 `CLEAR`는 설계 사양에 대한 판정이며 구현 merge gate가 아니다. 현재 working copy의
7-tier 재검토는 **P0=0, P1=1, P2=0, P3=0, BLOCK**이다. `angular.json`은 읽기·수정·커밋하지 않았다.

### P1 — 운영 migration과 activation의 실증 gate가 남아 있음

V28은 expand-only nullable column, V29는 PostgreSQL `CREATE INDEX CONCURRENTLY`/MySQL
online DDL/H2 index, V30은 migration version·dialect·last detail PK를 저장하는 durable
checkpoint를 사용한다. opt-in backfill runner는 transaction당 500행 이하 keyset과
scope 일치 조건을 적용하고, readiness는 residual·scope mismatch·shared writer version
fence를 모두 fail-closed로 확인한다. PostgreSQL profile에는 transactional advisory lock을
session-level로 바꾸는 `spring.flyway.postgresql.transactional-lock=false`도 연결했다.

다만 실제 MySQL/PostgreSQL migration smoke, dialect별 preflight count/lock-timeout artifact,
모든 writer replica의 version provider, 60초 steady probe alert 및 protected endpoint smoke가
아직 없다. 따라서 H2 V28/V29/V30 migration 1개, schema/properties 4개, writer fence 1개가
통과했어도 운영 migration merge gate는 **P1/BLOCK**으로 유지한다.

### 닫힌 구현 finding

- session/tenant tuple 및 `sessionFor()` 지연 응답 폐기
- generation mismatch의 epoch당 1회 unconditional first-page recovery
- patient history 전용 path classifier와 sanitized security/global error envelope
- shared registry deadline 및 DB connection과의 분리
- metadata ambiguity의 detail-level null fallback/metric
- load-more 400/409 stale cursor의 bounded recovery

### Fresh 검증과 미실행 gate

- `:appointment-api:compileKotlin :appointment-api:compileTestKotlin`: 성공
- 환자 이력 API targeted: 13개 통과
- `:appointment-core:test --tests '*AppointmentCancellationHistoryRepositoryTest'`: 성공
- Angular: 39개 파일/266개 테스트 통과
- `npm run build`: 성공
- `git diff --check`: 통과. `angular.json`은 변경하지 않았다.
- 실제 MySQL/PostgreSQL migration smoke, protected HTTP/shared registry readiness,
  PostgreSQL EXPLAIN·성능, production ACL·backup·canary·SLO: `PENDING`

따라서 현재 변경은 **merge-ready가 아니며**, V28 운영 migration 구현과 dialect별 증거가
추가되기 전까지 Issue #305를 `BLOCK`으로 유지한다.

## 최신 실행 재검토(2026-08-15)

이전 재검토의 “실제 MySQL/PostgreSQL migration smoke 미실행” 항목은 해소되었다. 저장소가
이미 제공하는 `bluetape4k-testcontainers` singleton launcher를 그대로 사용해 PostgreSQL,
MySQL 8, H2를 순차 실행했다.

| 검증 | 결과 |
|---|---|
| `FlywayPostgreSQLMigrationTest` | 8/8 통과 |
| `FlywayMySQLMigrationTest` | migration 8/8 통과, production endpoint 1건 의도적 skip |
| `FlywayMigrationTest` | 8/8 통과 |

PostgreSQL 첫 실행의 V29 실패는 코드가 아닌 Flyway transactional advisory lock과
`CREATE INDEX CONCURRENTLY`의 상호작용으로 재현됐다. lock connection의
`idle in transaction SELECT COUNT(*) FROM pg_namespace`와 concurrent index connection의
`wait_event=Lock:virtualxid`를 확인했고, V29의 30초 statement timeout에서 SQLSTATE
`57014`가 발생했다. production profile 및 PostgreSQL migration test configuration에
`transactional-lock=false`를 적용한 뒤 전체 PostgreSQL class를 재실행해 8/8 통과했다.

이에 따라 migration 관련 판정은 `P1/BLOCK`에서 **CLOSED**로 낮춘다. 다만 다음 운영
증거는 아직 없으므로 통합 판정은 **P0=0, P1=1, P2=0, P3=0, PENDING**이다.

- 실제 production MySQL 연결의 preflight/HTTP smoke와 자격증명 기반 endpoint 증거
- 모든 writer replica의 version fence 및 60초 steady probe/alert artifact
- shared registry readiness/restart/capacity, PostgreSQL EXPLAIN·성능
- production ACL·backup·canary·SLO 및 rollback rehearsal

따라서 이번 실행은 “세 dialect migration이 로컬 singleton 환경에서 통과했다”는 증거이며,
운영 활성화나 merge 승인을 의미하지 않는다.

## 최신 구현 게이트 검토 갱신(2026-08-15)

`docs/benchmarks/issue-305-remaining-gates/2026-08-15/evidence.ko.md`의 재현 가능한
로컬 증거를 반영한다.

### 로컬 gate 판정

- protected HTTP/security 경계: **PASS 1/1** — anonymous 401, staff 403, patient
  sanitized 503와 correlation/retry header를 실제 servlet stack에서 확인했다.
- readiness/운영 registry 계약: **PASS 2/2 + 8/8** — 60초 probe cache의 fail-closed/
  복구와 bounded registry의 capacity/TTL reclaim을 확인했다.
- PostgreSQL keyset 성능 구조: **PASS 1/1** — 실제 production-schema fixture의
  `EXPLAIN (FORMAT JSON)`에서 `idx_cancellation_detail_patient_scope_time`을 사용하고
  history relation full table scan이 없었다. commitment relation의 4,000행 hash join
  `Seq Scan`은 history access-path 실패로 분류하지 않는다.
- 위 최신 실행의 합계는 **12/12 통과, 실패 0, 오류 0, skip 0**이다.

### 잔여 운영 판정

로컬 검증으로 해당 구현 위험을 낮췄지만 다음은 외부 운영 증거가 없어 여전히
`PENDING`이다.

1. production MySQL credential 기반 preflight와 protected HTTP smoke
2. 모든 writer replica version fence 및 60초 steady probe/alert
3. shared external registry restart/capacity/readiness
4. production ACL, backup/restore, canary, SLO와 rollback rehearsal

D8은 본격운영을 하지 않았으므로 `N/A`, D9는 1인 개발자이므로 `N/A`로 처리한다.
최종 구현 검토는 **P0=0, P1=1, P2=0, P3=0, PENDING/BLOCK**이며, production
증거가 추가되기 전에는 merge-ready 또는 운영 활성화 완료로 판정하지 않는다.

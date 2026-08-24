# Issue #204 notification outbox canary 검증 기록

## 판정

고정 seed의 1,000건 notification outbox를 PostgreSQL·Redis·Kafka singleton
launcher와 deterministic provider stub으로 실행했습니다. `SHADOW → ACTIVE_SIMULATED →
ACTIVE_WORKER_STOPPED → PAUSED → SHADOW → ACTIVE_SIMULATED_RESTARTED` 순서를 테스트
코드에 고정했고, `PAUSED` 중 provider 호출이 없으며 대기 queue가 보존되는지 확인했습니다.
최종 lifecycle은 1,000건 `SENT`, retry 1건, 만료 lease fencing 1건이며, 모든 row가
terminal 상태로 닫혔습니다. provider idempotency replay 1건, `notificationOutboxHealth`
`UP`, ready backlog 0, oldest ready age 0초, provider throughput 측정값
`33.74730021598272/s`도 같은 실행에서 수집했습니다.

이번 결과는 **production-like container-backed simulation PASS / production SLO 및 실제
rollout 증거 아님**으로 판정합니다. 실제 provider credential·수신자·운영 트래픽과
staging의 24시간 처리량은 이 실행 범위에 없습니다. 따라서 기본 production route와
Issue #204의 외부 rollout HOLD 경계는 변경하지 않습니다.

## 기준과 범위

| 항목 | 값 |
|---|---|
| Issue | [#204](https://github.com/bluetape4k/clinic-appointment/issues/204) |
| 기준 ref | `develop` / `b2af9b4f20782c9f5e5773a73c9f3217a6eaf1fa` |
| branch | `chore/issue-204-notification-canary` |
| 실행 테스트 | `NotificationOutboxCanarySimulationIntegrationTest` |
| 검증기 테스트 | `NotificationOutboxCanaryEvidenceValidatorTest` (2건) |
| 고정 seed | `issue-204-seed-v1` |
| workload | bounded logical notifications `1,000` |
| 개인정보 | report에 member ID, destination, rendered body, raw payload, credential를 기록하지 않음 |

## 실행 증거

```text
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.config.NotificationOutboxCanaryEvidenceValidatorTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.config.NotificationOutboxCanarySimulationIntegrationTest' \
  --no-daemon --no-build-cache --rerun-tasks --max-workers=1 --console=plain
```

결과: XML 기준 validator 2건과 container-backed simulation 1건이 모두
`failures=0`, `errors=0`이며 `BUILD SUCCESSFUL`입니다. simulation은 29.6초,
Gradle task는 1분 13초에 완료했고 report assertion은 `27/27`입니다. wildcard 재실행에서
Gradle `EOFException`이 발생한 뒤 exact-class 재실행으로 안정적인 결과를 확인했으므로
wildcard 결과는 최종 증거로 사용하지 않았습니다.

| 영역 | 확인 결과 |
|---|---|
| PostgreSQL | `postgres:18-alpine`, Flyway current migration `30` |
| Redis | `redis:8.8`, namespace `issue-204:canary`, exact key cleanup 성공 |
| Kafka | unique topic round-trip, committed-end offset lag `0` |
| Rollout gate | `SHADOW`/`PAUSED` provider 호출 `0`, worker stop/restart 후 bounded drain |
| Retry | provider retry 1건, retry wait backdate 후 재처리 |
| Fencing | 만료 lease recovery 1건, `LEASE_LOST` attempt 1건 |
| Idempotency | accepted result `1,000`, replayed request `1`, duplicate accepted result `0` |
| Observation | critical alert `0`, claim failure `0`, ready backlog `0`, oldest ready age `0`초 |
| Health | `notificationOutboxHealth=UP`, readiness/liveness detail만 노출 |
| Redaction | raw payload/secret/destination/terminal row violation `0` |

전체 비식별 결과는 [production-like-report.json](../benchmarks/issue-204-notification-canary/2026-08-24/production-like-report.json)에
보존했습니다. 이 보고서는 실행 시각·로컬 컨테이너 이미지·고정 seed·threshold만 포함하며
provider payload나 실제 수신자 정보를 포함하지 않습니다.

## 변경 범위

- `appointment-api` test source에 canary simulation과 report validator를 추가했습니다.
- test resource에 validator contract fixture를 추가했습니다.
- production `src/main`, Flyway migration, dependency, route configuration은 변경하지
  않았습니다.
- chart/diagram은 이번 산출물이 benchmark 시각화가 아니라 고정 workload의 redacted
  contract report이므로 추가하지 않았습니다.

## Issue #204 DoD 경계

| 항목 | 상태 | 근거 |
|---|---|---|
| local production-like container simulation | **PASS** | exact-class 3개 테스트 XML `failures=0`, `errors=0`, 보고서와 test source read-back |
| claim/retry/fencing/idempotency/rollback 계약 | **PASS** | lifecycle 및 threshold가 모두 통과 |
| staging DDL/query-plan 및 실제 provider 처리량 | **PENDING** | 현재 권한과 실행 범위에 staging/provider가 없음 |
| 실제 24시간·1,000건 CANARY와 owner 승인 | **PENDING** | 실제 운영 cohort와 승인 기록이 없음 |
| `ACTIVE` 안정화 및 transitional route 제거 | **PENDING** | simulation은 production route를 변경하지 않음 |

`PENDING` 항목이 해결되기 전에는 이 문서의 PASS를 production rollout 승인으로
해석하지 않습니다.

## 재현 및 다음 작업

다음 작업자는 동일 branch에서 위 Gradle 명령을 먼저 실행하고, generated report의
threshold와 redaction을 재검증해야 합니다. 운영 전환을 별도 진행할 경우 staging 기준
데이터의 DDL lock/query plan, provider timeout/throughput, 첫 cohort의 24시간 승인,
stabilization window를 Issue #204에 첨부한 뒤 별도 rollout 변경으로 검토합니다.

## Inline review 결과

사용자 지시에 따라 응답하지 않은 독립 review lane 대신 현재 diff를 기준으로 inline
review를 수행했습니다.

| 심각도 | 결과 | 검토 및 조치 |
|---|---:|---|
| P0 | 0 | 데이터 손실·보안·즉시 중단 결함 없음 |
| P1 | 0 | 상수로 기록하던 rollback/redaction/alert/claim 값을 실제 관측값으로 전환했고, provider replay·health·throughput을 추가 검증했습니다. Redis/Kafka cleanup은 primary failure를 보존하도록 수정했으며, drain round off-by-one과 validator mutation false-positive도 고쳤습니다. |
| P2 | 5 | provider connect/read/request timeout·unknown·crypto-key failure matrix, V14/DDL lock/query plan의 명시 read-back, suppression reason fixture, legacy `scheduling_notification_history` reference 결정, staging 24시간/provider evidence는 이 local simulation 범위 밖입니다. 모두 production rollout 전 PENDING으로 기록합니다. |
| P3 | 0 | 불필요한 범위 확장이나 문서-only 미해결 finding 없음 |

P2 항목이 해결되기 전에는 이 문서의 PASS를 production rollout 승인으로 해석하지
않습니다. 다음 단계는 Issue #204에 해당 외부 증거를 첨부하고, pre-PR gate에서 최신
head·CI·리뷰를 다시 확인하는 것입니다.

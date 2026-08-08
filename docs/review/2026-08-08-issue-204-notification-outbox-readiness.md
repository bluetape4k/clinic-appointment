# Issue #204 알림 outbox readiness 기록

## 판정

현재 `develop` 기준 notification outbox의 로컬 readiness 계약은 통과했습니다. 다만
Issue #204의 운영 완료 조건인 staging DDL 측정, 실제 provider 처리량, 병원 1곳의
24시간·1,000건 CANARY, `ACTIVE` 안정화는 이 저장소와 현재 실행 권한에서 관측할 수
없습니다. 따라서 이번 기록의 최종 판정은 **로컬 readiness PASS / 외부 rollout HOLD**이며,
Issue #204를 닫거나 production route를 `ACTIVE`로 바꾸지 않습니다.

## 기록 범위와 기준

| 항목 | 값 |
|---|---|
| Issue | [#204](https://github.com/bluetape4k/clinic-appointment/issues/204) |
| 기준 ref | `develop` / `452117abd478758f257ef726858ec0d04faa4703` |
| 작업 branch | `maintenance/issue-204-readiness-pr` (isolated worktree) |
| workflow | Type E maintenance; production behavior 변경 없음 |
| 기록 시각 | `2026-08-08` (UTC 기준) |
| 개인정보 | member, clinic, appointment, provider payload를 기록하지 않음 |

Issue #204는 원래 V14 notification migration과 첫 CANARY를 추적하기 위해 열렸습니다.
현재 저장소는 V21 tenant query isolation을 notification readiness의 최소 schema gate로
사용하고, appointment messaging 영역은 V22 relay lease와 V23 consumer inbox까지
진행되어 있습니다. V22/V23을 notification worker의 별도 readiness 조건으로 승격하지
않고, 배포 baseline을 구분해 기록합니다.

## 현행 계약 대조

| 계약 | 현재 기준 | 결과 |
|---|---|---|
| Notification schema readiness | `NotificationSchemaReadiness.REQUIRED_FLYWAY_VERSION = 21`; event-log tenant backfill, 필수 table/index, active crypto key 확인 | PASS (단위 계약 테스트) |
| Route exclusivity | `SHADOW`, one-clinic `CANARY`, `ACTIVE`, `PAUSED`가 provider route를 상호 배타적으로 선택 | PASS (단위 계약 테스트) |
| Provider timeout | channel-specific timeout이 없을 때만 global `worker.provider-timeout`을 사용하고, 실제 decorated channel bean에 적용 | PASS (Spring auto-configuration/provider 테스트) |
| Health/alert boundary | readiness와 liveness를 분리하고, alert/metric label에 식별자를 넣지 않음 | PASS (health/alert 테스트) |
| Current migration inventory | H2/PostgreSQL/MySQL에 V14–V23 migration 파일 존재; V22/V23은 appointment messaging baseline | PASS (정적 inventory) |

## 재현 가능한 로컬 검증

notification 모듈에서 Issue #204와 직접 연결된 route, readiness, timeout, health, alert,
provider contract를 선택해 실행했습니다.

```text
./gradlew :appointment-notification:test \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationDeliveryRouteGateTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationPropertiesTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationAutoConfigurationTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationSchemaReadinessTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationOutboxHealthIndicatorTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationOutboxAlertPolicyTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.NotificationProviderContractTest' \
  --tests 'io.bluetape4k.clinic.appointment.notification.ResilientNotificationChannelTest' \
  --no-build-cache
```

결과는 `46 passing`, `BUILD SUCCESSFUL`입니다. 테스트가 확인한 핵심 경계는 다음과
같습니다.

- old schema, tenant orphan/null row, missing claim index, missing active key에서 worker를
  fail-closed한다.
- `SHADOW`·`CANARY`·`ACTIVE`·`PAUSED` route가 provider 중복 경로를 만들지 않는다.
- channel timeout override가 실제 channel bean에 전달되고 lease보다 짧은 timeout으로
  멈춘 provider 호출을 종료한다.
- cancellation은 provider failure로 변환하지 않고 전파한다.
- health detail은 안정적인 code/count만 반환하며 raw 식별자를 노출하지 않는다.
- `DELIVERY_RESULT_UNKNOWN`, exhausted, oldest-age, lease-recovery alert의 발화·해제
  조건과 허용 label을 고정한다.

### 정적 schema inventory

다음 migration 계열을 세 dialect에서 확인했습니다.

- `appointment-api/src/main/resources/db/migration/h2/V14__...` ~ `V23__...`
- `appointment-api/src/main/resources/db/migration/postgresql/V14__...` ~ `V23__...`
- `appointment-api/src/main/resources/db/migration/mysql/V14__...` ~ `V23__...`

이 결과는 파일과 로컬 계약의 존재를 확인할 뿐, production snapshot의 실제 DDL lock,
row 수, query plan 또는 provider 처리량을 증명하지 않습니다.

## Issue #204 DoD 상태

| DoD 항목 | 상태 | 이번 실행의 조치 |
|---|---|---|
| Staging DDL lock/query-plan evidence | **PENDING** | production-shaped staging 권한과 snapshot이 없어 측정하지 않음 |
| First-clinic 24h / 1,000 logical notifications | **PENDING** | 실제 provider와 clinic allowlist가 없어 실행하지 않음 |
| Canary exit criteria owner review | **PENDING** | notification platform/clinic operations 승인 기록 없음 |
| Allowlist expansion approvals/timestamps | **PENDING** | 외부 rollout 기록 없음 |
| `ACTIVE` stabilization window | **PENDING** | 현재 route 변경 없음; 기본 `SHADOW` 유지 |
| Transitional event-listener removal PR | **PENDING** | CANARY와 `ACTIVE` 안정화 전에는 제거하지 않음 |
| Legacy `scheduling_notification_history` decision | **PENDING** | retention/reference evidence가 없어 보존 |

운영 전환은 다음 수치가 모두 실제 관측 자료로 첨부될 때까지 보류합니다.
`DELIVERY_RESULT_UNKNOWN=0`, duplicate provider outcome `0`, critical alert `0`,
oldest ready age `<5m`, suppression reason 설명 가능, executor saturation·lease recovery
급증 원인 설명 가능, backlog/throughput이 [운영 런북](../runbooks/notification-outbox-operations.md)
threshold 이내입니다.

## 남은 조치

1. staging에서 V14 notification delta와 현행 V21 tenant preflight를 production-shaped
   snapshot에 적용하고 row/orphan/null 수, DDL lock wait, rollback 판단, `EXPLAIN` 결과를
   보존합니다.
2. provider-native connect/read/request timeout과 실제 처리량을 측정하고, local timeout
   contract와 비교합니다.
3. 첫 clinic의 24시간·1,000건 CANARY evidence와 owner approval을 Issue #204에 첨부한
   뒤에만 allowlist를 확대합니다.
4. 모든 cohort의 exit criteria와 stabilization window가 통과한 후에만 `ACTIVE`와
   transitional listener 제거를 별도 변경으로 검토합니다.

이번 변경에는 production Kotlin, migration, configuration, workflow, GitHub issue 또는
PR을 수정하지 않았습니다. 새 P0/P1/P2/P3 finding은 없으며, 이번 실행의 finding count는
`P0=0 / P1=0 / P2=0 / P3=0`입니다.

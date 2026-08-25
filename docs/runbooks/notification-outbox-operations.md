# 알림 outbox 운영 런북

이 문서는 내구성 알림 outbox의 배포, 카나리 전환, 장애 대응, 재알림, 암호화 키
교체, 스키마 변경을 다룹니다. 코드가 배포되었다고 운영 전환까지 끝난 것은 아닙니다.
기본 모드는 `SHADOW`이며, 실제 카나리와 전체 전환은 별도 운영 이슈에서 증거를
남기며 진행합니다. 현재 추적 이슈는 #204입니다.

## 1. 운영 원칙

- 예약 명령은 모든 rollout 모드에서 알림 outbox를 계속 저장합니다.
- provider를 호출할 수 있는 경로는 병원별로 하나뿐입니다.
- `PAUSED`는 provider 호출만 멈춥니다. enqueue, lease 복구, 종료 데이터 최소화,
  retention은 계속 실행합니다.
- 연락처·언어·동의는 발송 직전에 회원 시스템에서 조회하며 영속화하지 않습니다.
- 종료된 행을 되살리지 않습니다. 필요한 경우 현재 예약과 회원 상태를 다시 읽어
  새 generation의 알림을 만듭니다.

## 2. 배포 전 점검

다음 조건을 모두 확인합니다.

- H2, PostgreSQL, MySQL의 V21 migration에 event-log tenant column, outbox·attempt table과
  tenant direct lookup index가 있다.
- V21 preflight에서 event-log 전체 row 수, clinic orphan 수, tenant null 수, join/update
  `EXPLAIN`, 예상 DDL lock을 기록했다. orphan 또는 허용 maintenance window 부재 시
  migration dispatch를 보류한다.
- readiness가 schema, claim/recovery query, active idempotency key를 통과한다.
- `clinic.notification.rollout.mode=SHADOW`이고
  `clinic.notification.rollout.canary-scopes`와 deprecated
  `canary-clinic-ids`가 비어 있다.
- 회원 directory, template catalog, provider adapter가 실제 운영 구현으로 연결되어
  있다. 필수 adapter가 없으면 503으로 실패하도록 유지한다.
- provider adapter의 connect/read/request timeout이 적용 대상
  `channels.<채널 유형 소문자>.provider-timeout` 이하이다. 채널별 값이 없으면
  `worker.provider-timeout`을 기준으로 삼는다.
  worker의 future 취소만으로 interrupt를 무시하는 SDK 호출을 강제 종료할 수 없다.
- 대시보드에서 pending 수, oldest active age, attempt·retry·suppression·exhaustion,
  lease recovery를 볼 수 있다.
- `worker.poll-interval` 주기로 dispatcher가 실행되고, `observation.poll-interval`
  주기로 최대 `observation.limit`개의 ready backlog 관측 데이터를 갱신한다. Actuator `health`의
  `notificationOutboxHealth` 구성 요소가 readiness와 degraded 정보를 반환한다.
- leader health는 기본적으로 비활성화되어 있으며, Redis leader elector를 사용하는 환경에서
  `clinic.notification.leader-health.enabled=true`로 활성화하면
  `notificationLeaderHealth` 구성 요소가 추가된다.
- leader health는 운영 관측 신호이며 scheduler 실행, outbox claim, DB fence의 권위를
  대체하지 않는다. backend 상태 조회 실패는 `DOWN`, leader 부재·lease 임박·최근 획득
  실패는 `DEGRADED`로 표시한다. health 세부 정보에는 tenant·request·node·payload
  식별자를 포함하지 않는다.
- 공용 metric과 alert label에 tenant·clinic·member·appointment·outbox 식별자가
  포함되지 않는다.

### 2.1 schema readiness 진단 code

`NotificationSchemaReadiness`가 `DOWN`을 반환하면 원본 SQL, 예외 메시지, secret,
식별자를 health detail이나 로그에 남기지 않고 bounded 진단만 보존합니다. 운영자는
`operation`, `target`, `code`, `errorClass`, `retryable`을 사용해 다음 순서로 대응합니다.

| code | 의미 | `retryable` | 첫 대응 |
|---|---|---:|---|
| `SCHEMA_TABLE_MISSING` | 필수 table을 읽을 수 없음 | 아니오 | 대상 DB의 migration history와 table metadata를 대조하고 V21 적용 상태를 확인 |
| `SCHEMA_COLUMN_MISSING` | event-log tenant column을 읽을 수 없음 | 아니오 | V21 column migration과 실제 column 권한을 확인 |
| `SCHEMA_FLYWAY_UNAVAILABLE` | Flyway history를 읽을 수 없음 | 아니오 | `flyway_schema_history` 접근 권한과 연결 상태를 확인 |
| `SCHEMA_VERSION_TOO_OLD` | 요구 버전 V21 미만 | 아니오 | migration을 적용할 maintenance window와 DDL lock을 확인 |
| `SCHEMA_INDEX_MISSING` | claim/recovery 필수 index가 없음 | 아니오 | dialect별 index 이름·실행 계획을 확인한 뒤 additive migration을 재실행 |
| `SCHEMA_TENANT_DATA_INCONSISTENT` | tenant null/orphan/mismatch row가 남음 | 아니오 | backfill 결과와 clinic join을 확인하고 임의 tenant를 채우지 않음 |
| `SCHEMA_PERMISSION_DENIED` | metadata/query 권한 거부 | 아니오 | DB role grants를 확인하고 자격 증명·secret을 로그에 복사하지 않음 |
| `SCHEMA_METADATA_TIMEOUT` | schema preflight timeout | 예 | DB 부하와 lock wait를 확인하고 안정화 후 bounded retry |
| `SCHEMA_CONNECTION_FAILURE` | DB 연결 실패 | 예 | connection pool·네트워크·DB 상태를 확인하고 readiness가 회복될 때까지 대기 |
| `SCHEMA_METADATA_UNAVAILABLE` | 분류할 수 없는 metadata 실패 | 예 | `errorClass`와 operation/target만으로 원인을 분류하고 원문은 DB 로그에서 제한적으로 확인 |
| `KEY_RING_INVALID` | active key-ring 설정이 유효하지 않음 | 아니오 | 외부 secret reference와 key overlap을 확인하고 새 key를 임의로 생성하지 않음 |

`retryable=true`라도 worker를 강제로 재시작해 반복 폭주시키지 않습니다. readiness
endpoint가 `DOWN`인 동안 새 worker traffic을 차단하고, 원인 조치 후 다음 poll에서
같은 code가 사라졌는지 확인합니다. `target`은 table/column 논리 이름만 포함하며
tenant·clinic·member·appointment ID와 SQL 문장을 포함하지 않아야 합니다.

이번 변경에서 확보한 성능 증거는 로컬 container 환경의 H2/PostgreSQL/MySQL
통합 테스트와 20,000개 outbox 부하 시뮬레이션입니다. 이는 운영 DB의 DDL lock
시간이나 실제 provider 처리량을 증명하지 않습니다. 운영 전환 전에 staging에서
테이블·index 생성 시간, lock wait, 실행 계획, backlog 처리량을 다시 측정하고
배포 기록에 첨부해야 합니다.

## 3. leader readiness 관측

leader 상태를 readiness 그룹에서 함께 관측해야 하는 환경은 다음처럼 명시적으로
활성화합니다.

```yaml
clinic:
  notification:
    leader-health:
      enabled: true
      failure-window: 5m
      lease-risk-window: 30s
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,notificationOutboxHealth,notificationLeaderHealth
```

`notificationLeaderHealth`는 정상 lease를 확인하면 `UP`, leader가 없거나 lease가
임박했거나 최근 leader 획득 실패가 있으면 `DEGRADED`, leader backend 상태를 읽을 수
없으면 `DOWN`을 반환합니다. 성공한 획득은 현재 실패 window를 비우지만 마지막 실패
시각은 보존하므로 운영자가 회복 이력을 확인할 수 있습니다. 두 window는 양수여야
하며 실패 기록은 제한된 개수만 메모리에 보존합니다.

이 health 결과는 읽기 전용 운영 신호입니다. scheduler 실행 여부와 outbox claim,
DB fence의 최종 권위는 기존 경로에 남아 있으며 leader health 상태가 그 동작을
차단하지 않습니다.

## 4. 단계별 전환

### 4.1 `SHADOW`

```yaml
clinic:
  notification:
    rollout:
      mode: SHADOW
      canary-scopes: []
      canary-clinic-ids: []
```

백그라운드 worker는 provider 행을 선점하지 않습니다. 전환기 Spring event 경로가
정확히 같은 outbox 행을 조건부 선점하고 동일한 회원 조회·template·provider 절차를
실행합니다. direct executor가 포화되면 예약 event thread가 provider I/O를 대신하지
않고 작업을 거절하며, 이미 커밋된 outbox 행은 pending으로 남습니다. 포화 로그가
반복되면 `worker.global-concurrency`와 `worker.batch-size`를 provider 용량 안에서
조정하거나 즉시 `PAUSED`로 전환합니다. 다음을 확인합니다.

- 논리 알림 한 건당 provider 결과가 하나다.
- outbox와 attempt에 이름·연락처·본문·원본 오류가 없다.
- 종료 시 회원 ID·예약 ID·parameter가 제거된다.
- oldest active age가 5분 미만이고 suppression reason을 설명할 수 있다.

### 4.2 `CANARY`

첫 병원 한 곳만 허용 목록에 넣습니다.

```yaml
clinic:
  notification:
    rollout:
      mode: CANARY
      canary-scopes:
        - tenant-group-id: 1
          clinic-id: 23
      # Deprecated bridge for old nodes; clinic set must match canary-scopes.
      canary-clinic-ids: [23]
```

허용 목록 병원은 worker가 발송하고, 나머지 병원은 전환기 event 경로를 유지합니다.
다음 조건을 모두 만족할 때만 다음 단계로 이동합니다.

- 관측 시간: 최소 24시간
- 관측량: 최소 1,000개 논리 알림
- `DELIVERY_RESULT_UNKNOWN`: 0건
- 동일 논리 알림의 중복 provider 결과: 0건
- critical alert: 0건
- oldest active row age: 5분 미만
- 모든 suppression reason이 현재 회원 상태·동의·template 규칙으로 설명 가능

알림 플랫폼 담당자와 해당 병원 운영 담당자가 증거를 함께 확인하고 다음 병원
묶음을 승인합니다. 승인·측정·확대 시각을 후속 이슈에 기록합니다.

### 4.3 `ACTIVE`

모든 병원이 같은 카나리 기준을 통과한 뒤에만 `ACTIVE`로 바꿉니다.

```yaml
clinic:
  notification:
    rollout:
      mode: ACTIVE
      canary-scopes: []
      canary-clinic-ids: []
```

worker만 provider를 호출합니다. 안정 구간을 확인한 뒤 별도 변경으로 전환기
listener를 제거합니다. 기존 `scheduling_notification_history` 물리 테이블도 보존
정책과 참조 여부를 확인한 후 별도의 additive migration으로 제거합니다.

### 4.4 중단과 롤백

- 카나리 이상이 worker 경로에 한정되면 `SHADOW`로 되돌립니다.
- 중복 가능성, provider 결과 불명, 키 장애가 있으면 `PAUSED`로 전환합니다.
- 이미 선점한 행은 fencing된 생명주기로 종료하게 둡니다.
- `PENDING`, `RETRY_WAIT` backlog는 삭제하지 않습니다. 경로가 복구되면 다시
  처리합니다.
- 새 outbox enqueue를 끄거나 개인정보를 저장하던 legacy 발송 경로를 되살리지
  않습니다.
- additive schema를 즉시 삭제하지 않습니다.
- V21 rollback은 schema-down을 실행하지 않습니다. 먼저 route를 `PAUSED`로 전환하고
  이전 application으로 되돌립니다. 구버전 node drain 뒤 event-log null row를 다시
  backfill해 0임을 증명하고, `NOT NULL` hardening은 별도 release 승인으로만 진행합니다.

### 4.5 V21 preflight와 partial DDL recovery

배포 전 각 dialect에서 다음 read-only 결과를 저장합니다.

```sql
SELECT COUNT(*) AS event_log_rows,
       SUM(CASE WHEN clinic.id IS NULL THEN 1 ELSE 0 END) AS orphan_rows,
       SUM(CASE WHEN event_log.tenant_group_id IS NULL THEN 1 ELSE 0 END) AS null_tenant_rows
FROM scheduling_appointment_event_logs event_log
LEFT JOIN scheduling_clinics clinic ON clinic.id = event_log.clinic_id;
```

`EXPLAIN`으로 clinic join backfill과 tenant-leading direct claim index 사용을 확인하고,
DDL lock 대기와 maintenance window를 기록합니다. PostgreSQL/H2는 migration history와
index/constraint metadata를 대조하고, MySQL은 각 `ALTER`/`CREATE INDEX` 결과와
`flyway_schema_history`를 대조합니다. MySQL에서 partial DDL이 발생하면 route를
`PAUSED`로 유지하고 schema history와 실제 metadata를 먼저 복구·검증한 뒤 같은 V21을
재실행할 수 있는지 판단합니다. 임의 default tenant를 채우거나 schema-down으로
되돌리지 않습니다.

## 5. 관측 지표와 경보

주요 metric은 다음과 같습니다.

- `clinic.notification.outbox.pending`
- `clinic.notification.outbox.oldest.age`
- `clinic.notification.delivery.attempts`
- `clinic.notification.delivery.latency`
- `clinic.notification.delivery.retries`
- `clinic.notification.delivery.suppressed`
- `clinic.notification.delivery.exhausted`
- `clinic.notification.delivery.lease.recovered`

`pending`과 `oldest age`는 미래 발송 예정 행을 제외하고 현재 DB 시각에 발송 가능한
backlog만 셉니다. gauge는 metric scrape에서 테이블을 다시 읽지 않고, worker poll과
분리된 scheduler가 기본 10초마다 최대 10,001건만 읽어 갱신한 관측 결과를 반환합니다.
Actuator liveness detail의 `backlogCapped=true`이면 실제 backlog가 관측 상한 이상이라는
뜻이며 10,000건 경보를 해제하면 안 됩니다.

공용 metric label은 `channel`, `event_type`, `outcome`, `reason_code`만 허용합니다.
alert label은 `channel`, `event_type`, `outcome`, `provider_category`만 허용합니다.
병원별 분석이 필요하면 tenant·clinic 권한을 확인하는 제한된 DB dashboard에서
filter로 조회하고 metric label로 승격하지 않습니다.

| 신호 | 발화 조건 | 해제 조건 | 첫 대응 |
|---|---|---|---|
| oldest active age | `>30m` 5분 지속: critical, `>5m` 10분 지속: warning | `<5m` 10분 지속 | claim 실패, DB 지연, provider circuit 확인 |
| exhausted | 최근 5분 `>=10`: critical, `>=1`: ticket | 0건 15분 지속 | failure code와 provider 범주 확인 |
| provider failure ratio | 최소 100회 시도, 5분 지속, `>=50%`: critical, `>=20%`: warning | `<5%` 15분 지속 | provider 상태 확인, 필요 시 `PAUSED` |
| delivery result unknown | 최근 5분 `>=5`: critical, `>=1`: warning | 0건이며 원인 확인 완료 | 자동 재발송 금지, provider 결과 대조 |
| lease recovery ratio | 최소 100회 발송, `>5%` 10분 지속: warning | `<1%` 15분 지속 | timeout, pod 종료, DB 시간 차이 확인 |
| pending backlog | 10,000건 초과이며 10분 증가: warning | 15분 감소 | 병원별 공정성, DB·회원·provider 용량 확인 |
| key revoke/lookup failure | 즉시 critical | 자동 해제 없음 | Security·Notification 공동 대응, enqueue/readiness 503 |

## 6. 수동 재알림

엔드포인트:

```text
POST /api/{tenantCode}/clinics/{clinicId}/notifications/re-notify
```

플랫폼 서비스 주체는 `SYSTEM` 역할, service assurance,
`SCOPE_notification:renotify`, 정확한 clinic membership을 가져야 합니다. 요청에는
플랫폼 승인 참조와 독립된 병원 담당자의 MFA 승인 참조가 모두 필요합니다. 병원
승인자는 실행자와 달라야 하며 `ADMIN` 또는 `STAFF` 역할과 정확한 병원 범위를
가져야 합니다.

1. 최대 100개 appointment ID와 재사용 가능한 generation을 정합니다.
2. `dryRun=true`로 현재 예약, 회원 mapping, 동의, 연락처, template 적합성을 다시
   평가합니다.
3. 개인정보 없는 accepted/skipped 수와 reason을 검토합니다.
4. 승인 참조와 같은 generation을 유지한 채 `dryRun=false`로 실행합니다.
5. 중단되면 아직 enqueue하지 않은 항목만 같은 generation으로 재개합니다.

`SENT`, `DELIVERY_RESULT_UNKNOWN`, suppression된 항목은 기본적으로 제외합니다.
종료 행의 상태를 되돌리거나 삭제된 연락처를 복구하지 않습니다. 실행 감사에는
generation, 실행·승인 참조, scope, 단계, 시각, 결과 수만 남기고 예약 ID 원문은
남기지 않습니다.

## 7. 회원 ID 전환과 리마인더 누락

legacy 예약의 회원 ID 누락은 기본 `ENFORCE`입니다. 이행이 필요한 병원만 담당자와
만료 시각이 있는 `OBSERVE` 예외를 사용합니다. 만료된 예외는 자동으로 전역
`ENFORCE`로 돌아갑니다. `MEMBER_DIRECTORY_UNAVAILABLE`은 개인정보를 대신 입력하는
방식으로 우회하지 않고 회원 시스템을 복구한 뒤 같은 멱등 요청을 재시도합니다.

리마인더 scanner 중단 시에는 다음처럼 처리합니다.

- 애플리케이션 준비 직후 보정 runner가 실행되고 이후
  `clinic.notification.worker.reminder-recovery-interval` 간격으로 반복
- DB 조회 한 번은 `worker.batch-size`, 실행 한 번은
  `worker.reminder-recovery-max-candidates-per-run` 이하로 제한
- `clinic_notification_reminder_checkpoint`의 `run_id`와 마지막 완료 예약 ID에서 재개
- `worker.enabled=false`이면 scanner, scheduler, 주기 runner를 모두 구성하지 않음
- catch-up window 안: 동일한 예약 version·reminder slot 키로 누락 outbox 생성
- 아직 due 전인 slot: 미래 `availableAt`의 `PENDING` outbox로 미리 기록
- catch-up window 밖: 늦게 발송하지 않고
  `SUPPRESSED(REMINDER_WINDOW_MISSED)` 기록
- 예약 변경: 이전 version 리마인더는
  `SUPPRESSED(APPOINTMENT_CHANGED)`로 끝내고 새 version을 생성
- `clinic.notification.reminder.recovery{result=enqueued|suppressed|already_exists|not_yet_due}`와
  비식별 집계 로그로 보정량 확인

`already_exists`가 반복해서 높아도 중복 발송을 의미하지는 않습니다. outbox unique key가
같은 논리 알림을 한 행으로 수렴시킨 결과입니다. 반대로 `suppressed`가 급증하면 장애 시간이
`worker.catch-up-window`를 넘었는지 먼저 확인합니다. metric과 로그에는 tenant, clinic,
appointment, member 식별자를 넣지 않습니다.

## 8. HMAC 키 교체와 긴급 폐기

`clinic.notification.crypto`에는 key material이 아니라 외부 secret reference만
설정합니다. 지원 scheme은 `vault:`, `aws-secretsmanager:`,
`gcp-secretmanager:`, `azure-keyvault:`, `env:`, `file:`입니다.

```yaml
clinic:
  notification:
    crypto:
      active:
        key-id: notification-2026-q3
        secret-reference: vault:secret/notification/2026-q3
        activated-at: 2026-08-01T00:00:00Z
        expires-at: 2026-11-01T00:00:00Z
      previous:
        key-id: notification-2026-q2
        secret-reference: vault:secret/notification/2026-q2
        activated-at: 2026-05-01T00:00:00Z
        expires-at: 2026-09-05T00:00:00Z
      maximum-previous-overlap: 35d
```

Security 담당자가 90일 주기로 교체합니다. 이전 키는 최대 재시도 72시간과 종료 행
최대 보존 30일을 포함하도록 최대 35일 겹쳐 유지합니다. 긴급 폐기나 key lookup
실패 시 새 enqueue와 readiness를 503으로 내리고, Security와 Notification
on-call이 중복 가능성을 확인한 뒤 새 active key를 배포합니다. 이 경보는 자동으로
해제하지 않습니다.

## 9. DB 마이그레이션

1. 배포 전 각 DB에서 V14를 별도 staging 복제본에 적용해 DDL lock 시간과
   application timeout을 측정합니다.
2. PostgreSQL은 운영 제약에 맞는 concurrent index 절차가 필요한지 확인합니다.
   MySQL은 online DDL 지원과 metadata lock을 확인합니다.
3. `idx_notification_outbox_ready_clinic_cursor`,
   `idx_notification_outbox_ready_within_clinic`,
   `idx_notification_outbox_direct_lookup`,
   `idx_notification_outbox_reminder_suppression`, terminal retention index의 실제
   실행 계획을 확인합니다.
4. 새 binary 배포 전후로 schema readiness와 outbox enqueue를 확인합니다.
5. 실패해도 새 table을 즉시 삭제하지 않습니다. 이전 binary가 무시할 수 있는지,
   queued row와 retention을 보존할 수 있는지 먼저 확인합니다.

로컬 검증 수치는 운영 승인 자료가 아닙니다. staging 측정값, 적용 시각, 영향 받은
행 수, lock wait, rollback 판단을 후속 rollout 이슈에 첨부해야 합니다.

## 10. 종료 확인

- rollout 모드와 clinic allowlist가 의도한 값이다.
- 동일 논리 알림의 중복과 `DELIVERY_RESULT_UNKNOWN`이 없다.
- oldest active age와 pending 추세가 정상 구간이다.
- outbox·attempt·로그·metric에 연락처·본문·원본 오류가 없다.
- suppression, exhausted, lease recovery를 안정적인 코드로 설명할 수 있다.
- 카나리 확대 또는 롤백 승인이 운영 이슈에 기록되어 있다.

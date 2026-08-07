# 프로필 변경 예약 재평가 운영 런북

이 런북은 CRM 프로필의 중요한 변경 이후 `PROPOSED`와 `HELD` 예약을 제한된
범위에서 재평가하는 절차를 다룹니다. `CONFIRMED` 예약은 자동으로 변경하지
않습니다. 환자 프로필과 현재 scheduling assessment는 CRM이 소유하며,
예약 서비스에는 opaque reference, fingerprint, hash, queue 상태, 결과 분류,
최소 감사 정보만 저장합니다.

<a id="ownership"></a>
## 담당 영역과 안전 경계

| 영역 | 담당 | 첫 조치 |
|---|---|---|
| CRM 프로필, 중요 변경 판정, assessment projection | CRM 팀 | 원본 revision을 바로잡거나 assessment endpoint 복구 |
| 이벤트 신뢰, inbox, quarantine | 연동 당직자 | 신뢰할 수 없는 유입을 중단하고 암호화 증거 보존 |
| 병원 간 공정 dispatch, 예약 transaction, outbox | 예약 당직자 | mutation을 멈추고 backlog와 현재 예약 상태 확인 |
| 수동 redrive 승인 | 예약 운영 관리자 | preview 후 정확한 bounded 범위 승인 |
| 개인정보 사고 | 보안·개인정보 당직자 | 소비 중단, 증거 보존, 접근 제한, 조사 조율 |

운영 endpoint는 `/actuator/profileReevaluation`입니다. `SecurityConfig`는
`ADMIN` role과 `SCOPE_profile-reevaluation:operate` capability를 모두 요구합니다.
일반 예약 API가 아니므로 `/api/v2/**`로 노출하지 않습니다. 모든 write request에는
`tenantGroupId`와 `clinicId`가 있어야 하며, 해당 clinic은 인증 주체의 allowlist에
포함되어야 합니다.

<a id="rollout"></a>
## 단계적 전환: 비활성에서 전체 option B까지

지원하는 순서는 다음과 같습니다.

1. schema, query-plan, 개인정보, alert 검사를 진행하는 동안
   `appointment.profile-reevaluation.enabled=false`와
   `appointment.profile-reevaluation.mutation-mode=DISABLED`를 유지합니다.
2. `enabled=true`, mode `DRY_RUN`으로 바꾸고
   `appointment.profile-reevaluation.clinic-allowlist`에 clinic 하나를 추가합니다.
3. dry-run parity가 안정적이고 quarantine이 반복되지 않으며
   `HELD`/`PROPOSED` p95 target을 충족할 때만 계속 진행합니다.
4. mode를 `APPLY_PROPOSED`로 바꾸고
   `appointment.profile-reevaluation.proposed-target` 주기 하나를 관찰합니다.
5. mode를 `APPLY_PROPOSED_AND_HELD`로 바꾸고 clinic allowlist를 확장하기 전에
   `appointment.profile-reevaluation.held-target` 주기 하나를 관찰합니다.
6. clinic을 작은 묶음으로 추가합니다. failure, lease-expiry,
   assessment-saturation 신호가 중단 조건을 넘으면 즉시 해당 clinic을 제거합니다.

clinic allowlist가 비어 있으면 적격 clinic이 없다는 뜻입니다. 전체 clinic을
허용한다는 의미가 아닙니다. 측정된 capacity review가 변경을 뒷받침하지 않는 한
다음 platform 기본값을 유지합니다.

- `appointment.profile-reevaluation.held-target=5m`
- `appointment.profile-reevaluation.proposed-target=30m`
- `appointment.profile-reevaluation.global-concurrency=8`
- `appointment.profile-reevaluation.per-clinic-concurrency=2`
- `appointment.profile-reevaluation.auto-redrive-max=2`
- `appointment.profile-reevaluation.auto-redrive-cooldown=30m`

이 기본값에서 한 tick은 indexed `HELD` existence check를 최대 48회 수행합니다.
clinic queue 8개, ready state 2개와 expired-lease 경로 1개, 각 경로 후보 2개를
계산한 값입니다. 환자 backlog 크기는 이 상한을 늘리지 않습니다. database와 CRM
load test 및 최신 PostgreSQL/MySQL query-plan 증거 없이 두 concurrency 설정을
최대 64까지 올리지 않습니다.

각 병원은 scheduling policy에서 `HELD`와 `PROPOSED` target을 별도로 override할
수 있습니다. 적용 target은 clinic, tenant, platform 순서로 결정하며, 이미 생성한
job을 이후의 더 느린 target 때문에 뒤로 미루지 않습니다.

모든 rollout 변경 전후에 현재 operational snapshot을 읽습니다.

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/actuator/profileReevaluation
```

계속 진행할 수 있는 정상 기준은 다음과 같습니다.

- 활성화 중에는 `drainState`가 `ACTIVE`이고 rollback 후 `DRAINED`가 됩니다.
- `consecutiveAssessmentFailures=0`, `leaseRenewalFailures=0`입니다.
- oldest backlog가 effective target보다 작게 유지됩니다.
- 관찰 구간 동안 `clinic.profile.reevaluation.dryrun.parity{result="different"}`가
  증가하지 않습니다.
- `CONFIRMED` mutation count와 duplicate active allocation count가 0으로 유지됩니다.

critical alert가 발생하거나 개인정보 증거가 의심되거나 설명할 수 없는 parity
차이가 지속되면 rollout을 멈추고 `DRY_RUN` 또는 `DISABLED`로 전환합니다.

<a id="slo-burn"></a>
## SLO 소진

target은 queue 목표이며 모든 job의 완료를 보장하지 않습니다. `HELD`와
`PROPOSED`를 `clinic.profile.reevaluation.fair.wait`와
`clinic.profile.reevaluation.processing.duration`으로 나누어 평가합니다.

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(clinic_profile_reevaluation_fair_wait_seconds_bucket{priority_class="held_present"}[10m])
  )
)
```

이 histogram은 job마다 event 발생부터 최초 claim까지의 대기 시간을 한 번
기록하며, 닫힌 값인 `priority_class` tag만 사용합니다. p95가 effective target보다
낮고 대기만 하는 clinic이 없을 때 계속 진행합니다.
dispatcher는 tick 사이에 clinic keyset cursor를 이어 가고 끝에서 처음으로
돌아갑니다. 따라서 due time이 같아도 낮은 clinic ID만 반복 선택하지 않습니다.
선택한 clinic마다 `LIMIT 1` keyset 조회 하나와 범위가 제한된 `PENDING`,
`RETRY_WAIT`, expired-lease 조회만 실행합니다. 설정한 global concurrency 상한이
64이므로 환자 backlog 크기와 무관하게 poll query 수도 제한됩니다.
10분 동안 target의 80%를 소진하면 allowlist 확장을 멈춥니다. 10분 동안 100%를
넘으면 `DRY_RUN`으로 되돌리고 job을 보존한 채 database, worker, assessment
latency를 확인한 뒤 재개합니다.

<a id="oldest-job"></a>
## 가장 오래된 job과 backlog

health endpoint가 `oldestBacklogAgeSeconds`를 보고합니다. database query는
제한된 운영 범위를 확인할 때 사용하며 fingerprint나 assessment reference를
ticket에 복사하지 않습니다.

기본 alert 계약에서 사용하는 `health_profile_reevaluation_oldest_backlog_age_seconds`
series는 애플리케이션 native meter가 아닙니다. 배포 환경이
`/actuator/health/profileReevaluation` aggregate detail을 해당 이름의 series로
변환할 때만 이 rule을 설치합니다.

```sql
SELECT id, tenant_group_id, clinic_id, target_revision, status,
       priority_class, due_at, next_attempt_at, attempt_count,
       redrive_count, last_failure_code
FROM scheduling_profile_reevaluation_jobs
WHERE status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
ORDER BY due_at, id
LIMIT 100;
```

가장 오래된 job이 target의 80%에 도달하면 확장을 멈춥니다. target에 도달하거나
세 번 연속 polling window 동안 backlog가 증가하면 mutation을 비활성화합니다.
row는 보존하며 cursor나 attempt count를 초기화하지 않습니다.

<a id="failed-jobs"></a>
## 실패 job

```sql
SELECT id, tenant_group_id, clinic_id, target_revision, attempt_count,
       redrive_count, redrive_generation, last_failure_code, updated_at
FROM scheduling_profile_reevaluation_jobs
WHERE status = 'FAILED'
ORDER BY updated_at, id
LIMIT 100;
```

redrive 전에 실패 유형을 분류합니다. authentication, trust, tenant/clinic scope,
schema, privacy failure는 terminal 조사 경로입니다. 일시적인 CRM 또는 database
failure는 dependency가 정상이고 cooldown이 지났으며 preview가 의도한 bounded
scope를 반환할 때만 redrive할 수 있습니다.

worker failure는 제한된 diagnostic code를 남깁니다. `PROCESSING_DATABASE_FAILED`는
retry할 수 있습니다. `PROCESSING_CONTRACT_FAILED`, `PROCESSING_STATE_FAILED`,
`PROCESSING_UNEXPECTED_FAILED`는 terminal이므로 code 또는 data-contract를
조사해야 합니다. log에는 job ID, revision, failure code, exception type만 남기며
exception message나 profile data는 포함하지 않습니다.

<a id="lease-expiry"></a>
## Lease 만료

정상 상태에서 `clinic.profile.reevaluation.operational{result="lease_lost"}`는
0이어야 합니다. 한 건이라도 발생하면 process restart, GC pause, DB clock,
transaction duration을 확인합니다. 10분 동안 3건을 넘으면 critical입니다.
`appointment.profile-reevaluation.mutation-mode=DISABLED`로 설정하고
`drainState=DRAINED`가 될 때까지 기다린 뒤 만료된 owner를 조사합니다. SQL로
lease를 연장하거나 owner를 변경하지 않습니다.

<a id="assessment-saturation"></a>
## Assessment 포화와 CRM dependency

`clinic.profile.assessment.inflight`,
`clinic.profile.assessment.requests{result="saturated"}`,
`clinic.profile.reevaluation.assessment.latency`를 함께 확인합니다. 5분 동안
포화가 이어지면 allowlist 확장을 멈춥니다. 포화가 지속되거나 연속 5회 실패하면
health가 degraded가 되므로 `DRY_RUN` 또는 `DISABLED`가 필요합니다.

client는 HTTPS, 고정 host allowlist, public address, redirect 없음, bounded response
byte, 엄격한 assessment schema만 허용합니다. 처리량을 회복하기 위해 이 제어를
우회하지 않습니다.

<a id="quarantine"></a>
## 반복 quarantine

같은 reason code의 quarantine이 반복되면 ingress가 안전하지 않다는 뜻입니다.
consumer 경로를 비활성화하고 암호화 envelope와 append-only audit를 보존한 뒤
producer, signature, issuer, audience, payload hash, schema version, replay window,
tenant/clinic scope를 비교합니다. quarantine된 profile event를 failed-job redrive
endpoint로 해제하지 않습니다.

metadata 또는 payload 계약을 초과하는 envelope는 resource amplification을 막기
위해 canonicalization과 encryption 전에 거부합니다. quarantine에 기록하지 않고
bounded ingress metric과 transport log로 producer를 조사하며 oversized body를
보존하지 않습니다.

<a id="redrive"></a>
## 제한된 failed-job redrive

필수 tenant와 clinic scope로 먼저 preview합니다. 복구 승인이 정확히 하나의
profile revision을 대상으로 하면 revision을 함께 지정합니다.

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/actuator/profileReevaluation \
  -d '{"action":"PREVIEW","reason":"CRM dependency restored","idempotencyKey":"reeval-preview-20260730-01","tenantGroupId":1,"clinicId":101,"targetRevision":42,"limit":50}'
```

모든 preview row가 승인한 scope와 일치할 때만 실행합니다.

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/actuator/profileReevaluation \
  -d '{"action":"EXECUTE","reason":"CRM dependency restored","idempotencyKey":"reeval-execute-20260730-01","tenantGroupId":1,"clinicId":101,"targetRevision":42,"limit":50}'
```

audit actor는 인증된 admin token에서 도출하며 request body로 덮어쓸 수 없습니다.
이 endpoint는 tenant 전체 또는 여러 clinic에 걸친 redrive를 지원하지 않습니다.

service는 새로운 lineage attempt를 만들며 failed row를 다시 쓰지 않습니다. 하나의
service process 안에서 같은 idempotency key를 사용하면 동일한 request에 대해서만
정확히 같은 response를 replay합니다. process가 재시작되면 replay cache는 비어
있습니다. persisted lineage/CAS는 여전히 중복 attempt를 막지만, 반복 command가
기존 job list 대신 `created=0`을 반환할 수 있습니다. 새 preview와 운영자 판단이
필요한지 결정하기 전에 failed row와 `redrive_of_job_id` successor를 확인합니다.
자동 redrive는 `appointment.profile-reevaluation.auto-redrive-max`로 제한하며
설정한 한도에 도달하면 멈춥니다.

<a id="privacy-incident"></a>
## 개인정보 사고

event, table, log, metric, health detail, outbox, ticket에서 raw profile data,
진료 상세, feature, score, explanation, 역변환 가능한 환자 식별자가 발견되면
다음 순서로 대응합니다.

1. mode를 `DISABLED`로 설정하고 영향을 받은 clinic을 allowlist에서 제거한 뒤
   profile event consumer를 중단합니다.
2. 원본 database, log, 암호화 quarantine, deployment, configuration 증거를
   법무·보안 접근 통제 아래 보존합니다.
3. 해당 값을 chat, issue, dashboard label, redrive reason에 붙여 넣지 않습니다.
4. security/privacy on-call과 CRM owner에게 알리고 금지된 값이 처음 경계를
   넘은 지점을 확인합니다.
5. containment, 삭제/보존 결정, 필요한 secret/key rotation, 개인정보 integration
   test 통과, 서면 사고 승인을 모두 마친 뒤에만 재개합니다.

예약팀은 persistence boundary를 조사합니다. profile 정정과 assessment 내용은
계속 CRM 팀이 책임집니다.

<a id="rollback"></a>
## 롤백과 불변 조건 확인

rollback은 새 mutation을 멈추며 이미 완료된 유효한 reservation transaction을
되돌리지 않습니다.

1. `mutation-mode=DISABLED` 또는 `enabled=false`로 설정합니다.
2. clinic을 `clinic-allowlist`에서 제거합니다.
3. health snapshot이 `drainState=DRAINED`, `activeLeases=0`을 보고할 때까지
   기다립니다.
4. `CONFIRMED` reservation이 변경되지 않았고, 기존 유효한 `HELD` allocation이
   자체 atomic replacement transaction을 성공적으로 완료한 경우가 아니면
   계속 active인지 확인합니다.
5. `scheduling_profile_reevaluation_jobs`, outcome, inbox, quarantine, outbox,
   audit row를 보존합니다. V13 schema를 내리거나 failed job을 삭제하지 않습니다.
6. `DRY_RUN`과 가장 작은 clinic allowlist로 재개합니다.

`mutation-mode=DISABLED`이면 dispatcher가 automatic redrive 또는 job claim 전에
반환합니다. 따라서 queue가 drain되는 동안 rollback이 새 redrive lineage를
만들지 않습니다.

```sql
SELECT commitment_status, COUNT(*) AS outcome_count
FROM scheduling_appointment_commitments
GROUP BY commitment_status
ORDER BY commitment_status;
```

이 read-only snapshot과 active allocation uniqueness 증거를 rollout 직전 자료와
비교합니다. 설명할 수 없는 `CONFIRMED` 변경 또는 기존 `HELD` allocation 손실은
release blocker이자 사고입니다.

<a id="unsupported"></a>
## 지원하지 않는 동작

- `CONFIRMED` reservation의 자동 수정, 취소, 대체는 지원하지 않습니다.
- raw profile, objective feature 값, score, explanation, correction detail,
  CRM response body의 영속화는 지원하지 않습니다.
- 5분과 30분 값은 p95 queue 목표이며, 모든 개별 job이 해당 시간 안에 완료된다는
  보장이 아닙니다.
- automatic retry/redrive limit을 소진한 뒤의 unattended redrive는 지원하지 않습니다.
- bilingual 운영 계약에서 보존하는 exact token은 `raw profile`, `feature`, `score`,
  `explanation`, `5 minutes`, `30 minutes`, `unattended redrive`입니다.
- 직접 SQL status 재작성, cursor reset, lease-owner 수정, redrive selector를
  “all failed”로 확장하는 동작은 지원하지 않습니다.

## Metric 목록

모든 label은 low-cardinality enum입니다. tenant, clinic, patient, appointment,
event, correlation identifier는 label로 사용할 수 없습니다.

- `clinic.profile.reevaluation.events`
- `clinic.profile.reevaluation.jobs`
- `clinic.profile.reevaluation.outcomes`
- `clinic.profile.reevaluation.fair.wait`
- `clinic.profile.reevaluation.processing.duration`
- `clinic.profile.reevaluation.assessment.latency`
- `clinic.profile.reevaluation.operational`
- `clinic.profile.reevaluation.dryrun.parity`
- `clinic.profile.assessment.inflight`
- `clinic.profile.assessment.requests`

operational result는 `defer`와 `retry`를 구분합니다. `defer`는 runtime gate 또는
bounded tick이 원래 유효한 작업을 뒤로 미뤘다는 뜻이고, `retry`는 기술적 처리
실패가 retry policy를 사용했다는 뜻입니다.

배포 가능한 기본 alert는 [`profile-reevaluation-alerts.yml`](profile-reevaluation-alerts.yml)에
있습니다.

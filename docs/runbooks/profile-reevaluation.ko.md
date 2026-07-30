# 프로필 변경 예약 재평가 운영 런북

이 런북은 CRM에서 예약 판단에 영향을 주는 프로필 변경이 발생했을 때
`PROPOSED`와 `HELD` 예약만 제한된 범위에서 재평가하는 절차를 다룬다.
`CONFIRMED` 예약은 자동으로 바꾸지 않는다. 환자 프로필과 최신 예약 판단 자료는
CRM이 관리한다. 예약서비스에는 opaque reference, fingerprint, hash, queue 상태,
결과 분류와 최소 감사 정보만 남긴다.

<a id="ownership"></a>
## 담당 영역과 안전 경계

| 영역 | 담당 | 첫 조치 |
|---|---|---|
| CRM 프로필, 중요 변경 판정, assessment projection | CRM 팀 | 원본 revision을 바로잡거나 assessment endpoint를 복구 |
| 이벤트 신뢰 검증, inbox, quarantine | 연동 당직자 | 신뢰할 수 없는 유입을 중단하고 암호화 증거 보존 |
| 병원 간 공정 dispatch, 예약 transaction, outbox | 예약 당직자 | mutation을 멈추고 backlog와 현재 예약 상태 확인 |
| 수동 redrive 승인 | 예약 운영 관리자 | preview로 정확한 범위를 확인한 뒤 승인 |
| 개인정보 사고 | 보안·개인정보 당직자 | 소비 중단, 증거 보존, 접근 제한, 조사 조율 |

운영 endpoint는 `/actuator/profileReevaluation`이다. `SecurityConfig`에서
`ADMIN` 역할과 `SCOPE_profile-reevaluation:operate` 권한을 모두 요구한다. 일반
예약 API가 아니며 `/api/v2/**`로 노출하면 안 된다. 쓰기 요청에는
`tenantGroupId`와 `clinicId`가 반드시 있어야 하며, 해당 병원은 인증 주체의 허용
목록에 포함되어야 한다.

<a id="rollout"></a>
## 단계적 적용: 비활성에서 선택안 B까지

다음 순서만 지원한다.

1. schema, query plan, 개인정보와 alert 검증이 끝날 때까지
   `appointment.profile-reevaluation.enabled=false`,
   `appointment.profile-reevaluation.mutation-mode=DISABLED`를 유지한다.
2. `enabled=true`, mode `DRY_RUN`으로 바꾸고
   `appointment.profile-reevaluation.clinic-allowlist`에 병원 한 곳만 넣는다.
3. dry-run parity가 안정적이고 quarantine이 반복되지 않으며
   `HELD`·`PROPOSED` p95 목표를 만족할 때만 다음 단계로 간다.
4. mode를 `APPLY_PROPOSED`로 바꾸고
   `appointment.profile-reevaluation.proposed-target` 한 주기 이상 관찰한다.
5. mode를 `APPLY_PROPOSED_AND_HELD`로 바꾸고
   `appointment.profile-reevaluation.held-target` 한 주기 이상 관찰한 뒤
   병원 허용 목록을 넓힌다.
6. 병원을 작은 묶음으로 추가한다. 해당 병원의 실패, lease 만료,
   assessment 포화가 중단 기준을 넘으면 즉시 허용 목록에서 뺀다.

병원 허용 목록이 비어 있으면 적용 대상이 없다는 뜻이다. 전체 병원 허용으로
해석하지 않는다. 측정된 수용량 검토 없이 다음 플랫폼 기본값을 바꾸지 않는다.

- `appointment.profile-reevaluation.held-target=5m`
- `appointment.profile-reevaluation.proposed-target=30m`
- `appointment.profile-reevaluation.auto-redrive-max=2`
- `appointment.profile-reevaluation.auto-redrive-cooldown=30m`

각 병원은 예약 정책에서 `HELD`와 `PROPOSED` 목표 시간을 별도로 override할 수
있다. 적용값은 병원, tenant, 플랫폼 순서로 찾는다. 이미 만든 작업은 나중에 더
느슨한 목표가 생겨도 처리 시각을 뒤로 미루지 않는다.

배포 설정을 바꾸기 전과 후에 운영 상태를 읽는다.

```bash
curl --fail-with-body \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  http://localhost:8080/actuator/profileReevaluation
```

계속 진행할 수 있는 정상 기준은 다음과 같다.

- 활성 상태에서는 `drainState=ACTIVE`, rollback 뒤에는 `DRAINED`다.
- `consecutiveAssessmentFailures=0`, `leaseRenewalFailures=0`이다.
- 가장 오래된 backlog가 유효 목표 시간보다 짧다.
- 관찰 구간 동안 `clinic.profile.reevaluation.dryrun.parity{result="different"}`
  값이 늘지 않는다.
- `CONFIRMED` 변경과 중복 active allocation이 모두 0건이다.

critical alert가 발생하거나 개인정보 노출이 의심되거나 설명할 수 없는 parity
차이가 이어지면 배포 확대를 멈추고 `DRY_RUN` 또는 `DISABLED`로 낮춘다.

<a id="slo-burn"></a>
## SLO 소진

설정한 시간은 queue 목표이지 모든 작업의 완료 보장이 아니다. `HELD`와
`PROPOSED`를 분리해 `clinic.profile.reevaluation.fair.wait`,
`clinic.profile.reevaluation.processing.duration`으로 확인한다.

```promql
histogram_quantile(
  0.95,
  sum by (le) (
    rate(clinic_profile_reevaluation_fair_wait_seconds_bucket{priority_class="held_present"}[10m])
  )
)
```

이 histogram은 이벤트 발생부터 최초 선점까지의 대기 시간을 작업당 한 번
기록하며, 닫힌 값인 `priority_class` tag만 사용한다. p95가 유효 목표보다 낮고
대기만 하는 병원이 없을 때만 진행한다. dispatcher는 tick 사이에 병원 keyset
cursor를 이어 가고 마지막 병원 뒤에서 처음으로 돌아간다. 따라서 처리 목표 시각이
같아도 작은 clinic ID만 반복해서 선택하지 않는다. 선택한 병원마다 `LIMIT 1`
keyset 조회와 범위가 제한된 `PENDING`, `RETRY_WAIT`, 만료 lease 조회만 실행한다.
전역 동시성 상한이 64이므로 환자 backlog 크기와 무관하게 poll당 조회 횟수도
제한된다. 10분 동안 목표 시간의 80%를 쓰면 허용 목록 확대를 멈춘다. 10분 동안
100%를 넘으면 `DRY_RUN`으로 내리고 작업은 보존한 채 DB, worker, assessment
지연을 확인한다.

<a id="oldest-job"></a>
## 가장 오래된 작업과 backlog

health endpoint의 `oldestBacklogAgeSeconds`로 전체 상태를 본다. 아래 DB 조회는
제한된 운영 범위를 찾을 때만 사용한다. fingerprint나 assessment reference를
티켓에 옮기지 않는다.

기본 alert 계약에서 사용하는
`health_profile_reevaluation_oldest_backlog_age_seconds`는 애플리케이션이 직접
내보내는 meter가 아니다. 배포 환경에서
`/actuator/health/profileReevaluation`의 집계 detail을 해당 시계열로 변환하는
adapter를 구성한 경우에만 이 규칙을 설치한다.

```sql
SELECT id, tenant_group_id, clinic_id, target_revision, status,
       priority_class, due_at, next_attempt_at, attempt_count,
       redrive_count, last_failure_code
FROM scheduling_profile_reevaluation_jobs
WHERE status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
ORDER BY due_at, id
LIMIT 100;
```

가장 오래된 작업이 목표 시간의 80%에 도달하면 확대를 멈춘다. 목표를 넘거나
세 번의 polling 구간 동안 backlog가 계속 늘면 mutation을 중단한다. row는
보존하며 cursor나 attempt count를 초기화하지 않는다.

<a id="failed-jobs"></a>
## 실패 작업

```sql
SELECT id, tenant_group_id, clinic_id, target_revision, attempt_count,
       redrive_count, redrive_generation, last_failure_code, updated_at
FROM scheduling_profile_reevaluation_jobs
WHERE status = 'FAILED'
ORDER BY updated_at, id
LIMIT 100;
```

redrive 전에 실패 유형을 정한다. 인증, 신뢰, tenant/clinic 범위, schema,
개인정보 오류는 자동 복구 대상이 아니라 조사 대상이다. 일시적인 CRM 또는 DB
오류는 의존 시스템이 정상이고 cooldown이 지났으며 preview가 승인한 범위와
일치할 때만 redrive한다.

worker 실패는 길이가 제한된 진단 코드로 남긴다. `PROCESSING_DATABASE_FAILED`는
재시도할 수 있다. `PROCESSING_CONTRACT_FAILED`, `PROCESSING_STATE_FAILED`,
`PROCESSING_UNEXPECTED_FAILED`는 최종 실패이므로 코드나 데이터 계약을 조사해야
한다. 로그에는 job ID, revision, 실패 코드, 예외 타입만 남기며 예외 메시지나
프로필 데이터는 기록하지 않는다.

<a id="lease-expiry"></a>
## Lease 만료

정상 상태에서는 `clinic.profile.reevaluation.operational{result="lease_lost"}`가
0이어야 한다. 한 건이라도 발생하면 process 재시작, GC pause, DB 시각, transaction
시간을 확인한다. 10분에 3건을 넘으면 critical이다.
`appointment.profile-reevaluation.mutation-mode=DISABLED`로 내리고
`drainState=DRAINED`가 될 때까지 기다린 뒤 만료된 owner를 조사한다. SQL로 lease
시간을 늘리거나 owner를 바꾸지 않는다.

<a id="assessment-saturation"></a>
## Assessment 포화와 CRM 의존성

`clinic.profile.assessment.inflight`,
`clinic.profile.assessment.requests{result="saturated"}`,
`clinic.profile.reevaluation.assessment.latency`를 함께 본다. 5분 동안 포화가
이어지면 허용 목록을 넓히지 않는다. 계속 포화되거나 연속 실패가 5회에 도달하면
health가 degraded 상태가 되므로 `DRY_RUN` 또는 `DISABLED`로 낮춘다.

client는 HTTPS, 고정 host allowlist, public address, redirect 금지, 응답 byte
상한과 엄격한 assessment schema를 강제한다. 처리량을 회복한다는 이유로 이
검증을 우회하지 않는다.

<a id="quarantine"></a>
## 반복 quarantine

같은 reason code의 quarantine이 반복되면 안전하지 않은 유입으로 본다. consumer를
중단하고 암호화 envelope와 append-only audit를 보존한다. producer, signature,
issuer, audience, payload hash, schema version, replay window, tenant/clinic
범위를 확인한다. 격리된 프로필 이벤트를 실패 작업 redrive endpoint로 풀지 않는다.

metadata 또는 payload 계약 상한을 넘은 envelope는 자원 증폭을 막기 위해 canonical
변환과 암호화 전에 거절하며 quarantine에 저장하지 않는다. 원문을 보존하지 말고 길이가
제한된 유입 metric과 transport log로 producer를 조사한다.

<a id="redrive"></a>
## 제한된 실패 작업 redrive

tenant와 clinic 범위는 반드시 지정해 먼저 preview한다. 복구 승인이 특정 profile
revision만 대상으로 한다면 revision도 함께 지정한다.

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/actuator/profileReevaluation \
  -d '{"action":"PREVIEW","reason":"CRM dependency restored","idempotencyKey":"reeval-preview-20260730-01","tenantGroupId":1,"clinicId":101,"targetRevision":42,"limit":50}'
```

preview의 모든 row가 승인 범위와 일치할 때만 실행한다.

```bash
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8080/actuator/profileReevaluation \
  -d '{"action":"EXECUTE","reason":"CRM dependency restored","idempotencyKey":"reeval-execute-20260730-01","tenantGroupId":1,"clinicId":101,"targetRevision":42,"limit":50}'
```

감사 주체는 인증된 관리자 token에서 가져오며 요청 본문으로 바꿀 수 없다.
tenant 전체 또는 여러 병원을 한 번에 redrive하는 기능은 이 endpoint에서 지원하지
않는다.

서비스는 실패 row를 고치지 않고 lineage가 이어지는 새 attempt를 만든다. 동일 프로세스에서
같은 요청에 같은 idempotency key를 사용하면 이전 응답을 그대로
반환한다. 프로세스가 재시작되면 응답 cache는 사라진다. 저장된 lineage와 CAS는 attempt
중복 생성을 계속 막지만, 같은 명령이 이전 작업 목록 대신 `created=0`을 반환할 수 있다.
새 preview와 운영자 판단이 필요한지 결정하기 전에 실패 row와
`redrive_of_job_id` successor를 확인한다. 자동 redrive는
`appointment.profile-reevaluation.auto-redrive-max`까지만 실행하고 설정한 횟수를
소진하면 멈춘다.

<a id="privacy-incident"></a>
## 개인정보 사고

이벤트, table, log, metric, health detail, outbox 또는 티켓에서 프로필 원문,
진료 상세, 특징, 점수, 설명, 원문 보정 내용이나 역변환 가능한 환자 식별자가
발견되면 다음 순서로 대응한다.

1. mode를 `DISABLED`로 내리고 영향받은 병원을 허용 목록에서 빼며 프로필 이벤트
   consumer를 중단한다.
2. 원본 DB, log, 암호화 quarantine, 배포와 설정 증거를 법무·보안 접근 통제 아래
   보존한다.
3. 해당 값을 채팅, Issue, dashboard label, redrive 사유에 붙여 넣지 않는다.
4. 보안·개인정보 당직자와 CRM 담당자에게 알리고 금지된 값이 처음 경계를 넘은
   지점을 찾는다.
5. 격리, 삭제·보존 결정, 필요한 secret/key 교체, 개인정보 통합 테스트 통과와
   사고 종료 승인을 모두 확인한 뒤 재개한다.

예약팀은 예약서비스의 저장 경계를 조사한다. 프로필 정정과 assessment 내용은
계속 CRM 팀이 책임진다.

<a id="rollback"></a>
## Rollback과 불변 조건 확인

rollback은 새 mutation을 멈추는 절차다. 이미 정상적으로 끝난 예약 transaction을
일괄 되돌리지 않는다.

1. `mutation-mode=DISABLED` 또는 `enabled=false`로 바꾼다.
2. `clinic-allowlist`에서 병원을 제거한다.
3. health 상태가 `drainState=DRAINED`, `activeLeases=0`이 될 때까지 기다린다.
4. `CONFIRMED` 예약이 한 건도 바뀌지 않았고, 기존의 유효한 `HELD` allocation이
   자체 원자 교체 transaction이 성공한 경우 외에는 유지되는지 확인한다.
5. `scheduling_profile_reevaluation_jobs`, outcome, inbox, quarantine, outbox,
   audit row를 보존한다. V13 schema를 내리거나 실패 작업을 삭제하지 않는다.
6. 가장 작은 병원 허용 목록과 `DRY_RUN`으로 재개한다.

`mutation-mode=DISABLED`이면 dispatcher는 자동 redrive나 작업 선점 전에
반환한다. 따라서 queue가 비워지는 동안 rollback 때문에 새 redrive 계보가
생기지 않는다.

```sql
SELECT commitment_status, COUNT(*) AS outcome_count
FROM scheduling_appointment_commitments
GROUP BY commitment_status
ORDER BY commitment_status;
```

배포 직전의 read-only snapshot과 active allocation uniqueness 증거를 비교한다.
설명할 수 없는 `CONFIRMED` 변경이나 기존 `HELD` allocation 손실은 release
blocker이자 사고다.

<a id="unsupported"></a>
## 지원하지 않는 동작

- `CONFIRMED` 예약을 자동으로 변경·취소·교체하지 않는다.
- 프로필 원문, 객관적 특징 값, 점수, 설명, 보정 내용, CRM 응답 본문을 저장하지
  않는다.
- 5분과 30분은 p95 queue 목표다. 모든 개별 작업이 그 시간 안에 끝난다고
  보장하지 않는다.
- 자동 retry/redrive 한도를 소진한 뒤 무인 redrive를 계속하지 않는다.
- SQL로 상태를 바꾸거나 cursor를 초기화하거나 lease owner를 수정하거나
  redrive 범위를 “모든 실패”로 넓히지 않는다.

## Metric 목록

모든 label은 낮은 cardinality의 닫힌 enum만 사용한다. tenant, clinic, patient,
appointment, event, correlation ID를 label에 넣지 않는다.

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

운영 결과의 `defer`와 `retry`는 의미가 다르다. `defer`는 runtime gate나 tick
상한 때문에 정상 작업을 미룬 경우이고, `retry`는 기술 실패로 재시도 정책을
사용한 경우다.

배포 가능한 기본 alert는
[`profile-reevaluation-alerts.yml`](profile-reevaluation-alerts.yml)에 있다.

# 스케줄링 정책 활성화 운영 런북

이 런북은 scheduling-policy preview worker와 activation worker를 운영할 때 사용하는
진단, 완화, 복구 절차를 정리한다. 정책 row를 직접 고치지 말고 API 명령과 replay/retire
경로를 사용한다.

## 단계적 전환 체크리스트

모든 flag는 기본값이 `false`다. 운영자는 다음 순서로만 켠다.

1. `scheduling.policy.shadow-compile-enabled`
2. `scheduling.policy.effective-read-enabled`
3. `scheduling.policy.admin-write-enabled`
4. `scheduling.policy.preview-worker-enabled`
5. `scheduling.policy.scheduled-activation-enabled`

후행 flag가 선행 flag 없이 켜지면 애플리케이션 시작이 실패한다. 이 foundation PR에는
booking consumer flag가 없다.

## 활성화 시점 계약

```text
60s lateness warning -> inspect lease/DB clock/head conflict
5m/deadline critical -> command MISSED, prior active preserved
recovery -> create manual replay or retire incompatible draft
forbidden -> direct DB edits or terminal-row rewrites
```

기본값은 다음과 같다.

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `scheduling.policy.activation-lateness-warning` | `PT60S` | 예정 시각 대비 지연 경고 |
| `scheduling.policy.activation-missed-after` | `PT5M` | prior active를 보존하고 `MISSED` 처리 |
| `scheduling.policy.worker-lease` | `PT30S` | DB 시각 기준 owner lease |
| `scheduling.policy.max-activation-claims-per-tick` | `25` | 한 tick의 activation claim 상한 |
| `scheduling.policy.max-preview-jobs-per-tick` | `10` | 한 tick의 preview claim 상한 |
| `scheduling.policy.preview-job-deadline` | `PT5M` | durable preview hard deadline |

## Alert 매트릭스

| Alert | 기본 임계값 | 첫 진단 metric/query | 담당자 작업 | Degradation / escalation | Recovery |
|---|---:|---|---|---|---|
| Outbox oldest pending | oldest pending age > 60s | `scheduling_outbox_events`의 `MIN(created_at)` pending query; occurrence 보조 신호는 `clinic.scheduling.policy.outbox{result="oldest_pending_age"}` | publisher 상태와 DB lock 확인 | 5m 이상이면 발행 지연을 변경 공지로 격상 | publisher 재기동 후 failed row는 outbox redrive |
| Outbox failed | failed row > 0 | `scheduling_outbox_events` failed count query; occurrence 보조 신호는 `clinic.scheduling.policy.outbox{result="failed"}` | error classification 확인 | 반복 실패면 downstream event consumer 격리 | 원인 제거 후 outbox redrive |
| Preview deadline | `preview-job-deadline` 초과 | `clinic.scheduling.policy.preview{result="deadline"}`와 `scheduling_policy_preview_jobs` terminal state | scan cursor, page size, DB plan 확인 | 같은 scope 반복이면 admin preview 접수 제한 | draft 수정 없이 새 preview 제출 |
| Preview stale ratio | stale / total 상승 | `clinic.scheduling.policy.preview{result="stale"}` | 활성화/retire와 preview 동시성 확인 | admin write window를 좁히거나 retry 안내 | 최신 revision/generation으로 preview 재실행 |
| Activation conflict ratio | conflict 또는 retry 급증 | worker: `clinic.scheduling.policy.activation{result="retry"}` / admin: `clinic.scheduling.policy.administration{operation="activate",result="rejected"}` | lease owner, head revision, idempotency conflict 확인 | 60s 이후 warning, 5m 이후 missed | fresh idempotency key로 replay 또는 draft retire |
| Generation-read failure | conflict/unavailable 증가 | `clinic.scheduling.policy.effective.read` | 권위 저장소, transaction retry, DB clock 확인 | read consumer는 fail-closed | 저장소 복구 후 caller retry |
| Aggregate-null count | count > 0 | `clinic.scheduling.policy.aggregate.null` | V10 migration join/projection 확인 | 새 writer 배포 중단 | aggregate-null query가 0이 될 때까지 rollback 또는 backfill |
| Dual-write parity | mismatch > 0 | `clinic.scheduling.policy.dual.write.parity{result="mismatched"}` | legacy/new column writer version 확인 | V10 cutover hold | 모든 writer version dual-write 확인 후 재검증 |
| Admin facade rejection | rejected 증가 | `clinic.scheduling.policy.administration{result="rejected"}` | `operation`, `scope_type`별 오류 registry 확인 | 특정 operation 과다 시 rollout 중단 | 원인별 payload/권한/preview evidence 수정 |

Meter tag는 낮은 cardinality만 허용한다. tenant ID, clinic ID, actor ID, token,
payload hash, correlation ID를 metric label에 넣지 않는다.
`clinic.scheduling.policy.outbox`는 발생 횟수 counter이므로 현재 backlog age나 row count의
권위값으로 해석하지 않는다. 현재 상태 판정은 위 SQL query를 사용한다.

## 초기 진단

### Outbox backlog

```sql
SELECT MIN(created_at) AS oldest_pending_created_at
FROM scheduling_outbox_events
WHERE status = 'PENDING';

SELECT COUNT(*) AS failed_count
FROM scheduling_outbox_events
WHERE status = 'FAILED';
```

DB 현재 시각과 `oldest_pending_created_at`의 차이가 60초를 넘으면 publisher와 lock을
확인한다. 5분 이상이면 변경 전파 지연으로 격상한다.

### 예정된 activation 지연

```sql
SELECT id, status, effective_from, next_attempt_at, lease_owner, lease_until, attempt
FROM scheduling_policy_activation_commands
WHERE status IN ('PENDING', 'CLAIMED', 'RETRY_WAIT')
ORDER BY next_attempt_at, id
LIMIT 25;
```

확인 순서:

1. `lease_until`이 DB 현재 시각보다 오래 남아 있는지 확인한다.
2. `next_attempt_at`이 계속 뒤로 밀리는지 확인한다.
3. 같은 scope의 head revision과 expected revision/generation 충돌을 확인한다.
4. 60초 이상 늦으면 warning으로 기록하고, 5분 이상 늦으면 `MISSED` 처리 여부를 확인한다.

### Preview backlog

```sql
SELECT id, status, next_attempt_at, lease_owner, lease_until, scanned_count, affected_count, last_error_code
FROM scheduling_policy_preview_jobs
WHERE status IN ('PENDING', 'RUNNING')
ORDER BY next_attempt_at, id
LIMIT 25;
```

`PENDING` 또는 `RUNNING` polling은 `Retry-After`를 존중해야 한다. 같은 job을 너무
빠르게 polling하면 `POLICY_PREVIEW_LIMITED`와 HTTP `429`가 발생한다.

### Effective read fail-closed

effective read는 generation을 두 번 읽는다. 두 read 사이에 활성 정책이 바뀌면 snapshot을
반환하지 않고 `POLICY_EFFECTIVE_READ_CONFLICT`를 반환한다. 권위 저장소가 읽히지 않으면
`POLICY_EFFECTIVE_READ_UNAVAILABLE`과 HTTP `503`을 반환한다.

## 복구 작업

| 상황 | 허용된 복구 | 금지된 복구 |
|---|---|---|
| `MISSED` activation command | `/activation-commands/{commandId}/replay`에 fresh `Idempotency-Key` 사용 | 기존 terminal row를 SQL로 `PENDING`으로 되돌리기 |
| incompatible draft | `/{id}/retire`로 이력 보존 retire | payload JSON 직접 수정 |
| stale preview | 최신 revision/generation으로 preview 재실행 | stale job의 evidence token 재사용 |
| idempotency conflict | 의도 확인 후 새 key 사용 | 기존 key hash나 fingerprint 수정 |
| aggregate-null | writer/version 점검, 필요 시 backfill 후 재검증 | null row를 임의 aggregate로 채우기 |

## V10 Readiness gate

V10 전환 전 운영자는 아래 세 가지를 모두 확인한다.

1. aggregate-null 검증 query가 0을 반환한다.
2. legacy column과 new aggregate column의 parity가 맞는다.
3. 선언한 관측 기간 동안 모든 writer version이 legacy/new dual-write를 수행한다.

예시 검증 query:

```sql
SELECT COUNT(*) AS aggregate_null_count
FROM scheduling_outbox_events
WHERE aggregate_type IS NULL OR aggregate_id IS NULL;
```

parity query는 V10 migration의 실제 legacy/new column 쌍을 기준으로 작성한다. 이
foundation 문서는 V9/V10 split을 고정하지만 V10 schema 이름을 선점하지 않는다.

## 운영자 규칙

- 확정 예약 변경은 고객 동의 후 적용한다. 운영 장애 복구는 새 제안을 만들고 기존 확정
  예약을 조용히 덮어쓰지 않는다.
- 정책 활성화 transaction은 command claim owner와 DB lease가 소유한다.
- 직접 DB update로 terminal row를 rewrite하지 않는다.
- raw idempotency key, actor claim, JWT, payload 원문을 로그나 metric label에 넣지 않는다.
- replay는 새 command를 만들며 원본 missed command를 감사 이력으로 남긴다.

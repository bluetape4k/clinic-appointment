# 예약 신뢰성 운영 런북

이 런북은 병원별로 `booking.reliability`를 관찰하고 승격·롤백하는 절차입니다.
[정책 기준](../booking-reliability-policy.md)이 규범이며, [API 계약](../api/booking-reliability.md)이
HTTP 형식을 정의합니다.

## 배포 전 확인

1. Flyway V17이 네 테이블과 명명된 unique/index 계약을 생성했는지 확인합니다.
2. schema readiness가 `ready=true`인지 확인합니다. false이면 worker를 시작하거나
   `ENFORCE`를 활성화하지 않습니다.
3. tenant와 clinic의 policy version/hash, lookback, threshold, cooling-off를 기록합니다.
   threshold의 `DISABLE`은 해당 threshold에만 적용합니다.
4. denylist scan을 실행합니다. decision, event, metric, log에 이름, 전화번호, 이메일,
   자유 텍스트, raw payload가 나타나서는 안 됩니다.
5. 정확한 clinic 범위의 operator token으로 preview, audit, override, clear를 실행합니다.

## 단계적 전환

| 단계 | 설정 | 기대 동작 | 승격 조건 |
|---|---|---|---|
| OFF | `booking.reliability.mode=OFF` | 기존 예약 동작, gate 호출 없음 | additive migration과 smoke check 통과 |
| SHADOW | `mode=SHADOW`와 clinic allowlist | decision 저장·관찰, 예약 차단 없음 | 24시간과 1,000건 중 더 늦은 조건 충족 |
| ENFORCE | `mode=ENFORCE`와 작은 allowlist | restriction/stale/unavailable에 fail-closed | canary evidence 전체 통과 |

작은 clinic allowlist로 시작해 점진적으로 확장합니다. 다음 조건 중 하나라도
발생하면 allowlist를 비우고 `SHADOW`로 되돌립니다.

- p95 latency >250ms 또는 p99 >500ms
- duplicate decision, unavailable backlog, raw-PII finding 중 하나라도 발생
- attribution-missing ratio ≥1%
- lease loss 증가 또는 `DEAD_LETTER` job 발생
- 기존 `CONFIRMED` appointment에서 state/resource mutation 관찰

## 장애 대응

### Decision unavailable 또는 stale

`BOOKING_DECISION_UNAVAILABLE`은 DB/policy readiness 또는 persistence failure를 뜻합니다.
health와 correlation ID를 확인하고 worker를 멈춘 뒤 mode를 `SHADOW`/`OFF`로 낮춥니다.
같은 intent는 bounded backoff로 재시도합니다. `BOOKING_DECISION_STALE`이면 현재
decision을 다시 읽고 `decisionId`와 `evaluationDigest`를 모두 갱신합니다.

### Worker backlog

`booking_reliability_reevaluation_jobs`에서 clinic별 `RETRY_WAIT`, `DEAD_LETTER`,
`PAUSED`, 오래된 `RUNNING` row를 확인합니다. 다른 lease가 소유한 row는 수정하지
말고 만료와 fencing을 기다립니다. retry는 최대 시도 횟수와 delay 상한을 따르며
cancellation을 retry로 바꾸지 않습니다. 승인된 idempotency key와 bounded batch를
사용할 때만 `DEAD_LETTER`/quarantine row를 redrive합니다. 조사 전에 job을 pause하고,
원인을 기록했으며 같은 durable cursor를 안전하게 replay할 수 있을 때만 resume합니다.

### 잘못된 attribution

`UNKNOWN` event는 환자 책임으로 계산하지 않습니다. 원천 appointment/operations
system에서 책임 분류를 수정하고 새 `sourceVersion`을 발행합니다. 기존 event와
decision은 유지하고 새 digest와 audit trail을 보존합니다.

### 개인정보 노출

발견 즉시 영향을 받은 clinic을 `OFF`로 낮추고 log/metric/response/row를 격리합니다.
PII를 decision에 수동으로 복사하지 않습니다. 회원관리 보안 사고 절차를 따르고,
이 저장소에는 bounded actor, correlation, opaque reference만 남깁니다.

## 직원 override와 clear

1. 현재 decision을 읽습니다.
2. `Idempotency-Key`, `decisionId`, `evaluationDigest`를 고정합니다.
3. allowlist된 `MANUAL_OVERRIDE` 또는 `MANUAL_CLEAR` reason을 사용합니다.
4. 409 stale response이면 다시 읽고, 다른 payload에 같은 key를 재사용하지 않습니다.
5. audit에서 actor, effective/expiry, result digest를 확인합니다.

override는 이미 존재하는 `CONFIRMED` appointment를 소급해 변경하지 않습니다. 검토한
새 offer/commitment에 대한 bounded decision일 뿐입니다.

## 보존과 롤백

모듈은 각 batch 전에 `retentionClass`와 legal hold를 확인하는 bounded retention
executor 계약을 제공합니다. legal hold가 있으면 삭제하지 않습니다. 기본 executor는
의도적으로 no-op이며, 운영에서는 승인된 삭제 또는 가명화와 audit evidence를 수행하는
tenant/clinic 범위 executor를 주입해야 합니다. V17은 additive이므로 rollback은
애플리케이션을 `OFF`/`SHADOW`로 낮추며 이미 적용한 migration을 다시 쓰지 않습니다.

## 증거 기록

배포마다 [canary evidence template](booking-reliability-canary-evidence-template.md)을 작성합니다.
policy version/hash, mode, allowlist, decision volume, latency, backlog, attribution-missing
ratio, PII scan, rollback, correlation ID를 기록합니다.

# 예약 신뢰성 운영 런북

이 런북은 `booking.reliability`를 병원 단위로 관찰하고 승격·롤백하는 절차다.
정책의 기준은 [기준 문서](../booking-reliability-policy.ko.md), API 형식은
[API 계약](../api/booking-reliability.md)을 따른다.

## 배포 전 확인

1. Flyway V17이 네 테이블과 명시된 unique/index를 생성했는지 확인한다.
2. schema readiness가 `ready=true`인지 확인한다. readiness가 false이면 worker를 시작하지
   않고 `ENFORCE`를 사용하지 않는다.
3. tenant baseline과 clinic override의 policy version/hash, lookback, threshold,
   cooling-off를 기록한다. `DISABLE`은 해당 threshold만 비활성화한다.
4. decision/이벤트/metric/로그에 이름, 전화번호, 이메일, 자유 텍스트가 없는지 denylist scan한다.
5. 정확한 clinic membership과 `booking-reliability:*` scope를 가진 운영자 토큰으로 preview,
   audit, override, clear를 각각 dry-run한다.

## 단계적 전환

| 단계 | 설정 | 기대 동작 | 승격 조건 |
|---|---|---|---|
| OFF | `booking.reliability.mode=OFF` | 예약 기존 동작, gate 미호출 | additive migration과 code smoke 통과 |
| SHADOW | `mode=SHADOW`, clinic allowlist | decision 저장·관측, 예약 차단 없음 | 24시간과 1,000건 중 늦은 조건 충족 |
| ENFORCE | `mode=ENFORCE`, 작은 allowlist | 제한/stale/unavailable fail-closed | canary evidence 모든 항목 통과 |

승격은 clinic allowlist를 작은 값으로 시작해 단계적으로 확장한다. 아래 조건 중 하나라도
실패하면 즉시 allowlist를 비우고 `SHADOW`로 내린다.

- p95 latency > 250ms 또는 p99 > 500ms
- duplicate decision, unavailable backlog, raw PII finding > 0
- attribution 누락 비율 ≥ 1%
- worker lease loss가 증가하거나 `DEAD_LETTER` job이 발생
- 기존 `CONFIRMED` 예약의 상태/자원 변경 증거가 발견

## 장애 대응

### Decision unavailable/stale

`BOOKING_DECISION_UNAVAILABLE`은 DB/policy readiness 또는 저장 장애다. 먼저 health와
correlation ID로 범위를 확인한 뒤 worker를 멈추고 `SHADOW`/`OFF`로 낮춘다. 같은 intent의
재시도는 bounded backoff를 사용한다. `BOOKING_DECISION_STALE`은 현재 decision을 다시
조회한 뒤 `evaluationDigest`와 `decisionId`를 갱신한다.

### Worker backlog

`booking_reliability_reevaluation_jobs`의 `RETRY_WAIT`, `DEAD_LETTER`, `PAUSED`, 오래된 `RUNNING`을
clinic별로 확인한다. lease owner가 다르면 row를 직접 수정하지 말고 lease 만료를 기다린다.
retry는 최대 시도 횟수와 delay 상한을 넘지 않으며, cancellation은 retry로 변환하지 않는다.
`DEAD_LETTER`/quarantine row는 원인 코드와 policy version을 확인한 후 승인된 idempotency key로
bounded redrive한다. 조사 중에는 job을 `PAUSED`로 전환하고, 원인을 기록한 뒤 같은 durable
cursor를 안전하게 재생할 수 있을 때만 `resume`한다.

### 잘못된 attribution

`UNKNOWN` 사건은 고객 책임으로 계산하지 않는다. 원천 예약/운영 시스템의 책임 분류를
수정하고 새 `sourceVersion` event를 발행한다. 기존 event와 decision은 삭제하지 않으며,
재평가 결과의 digest와 audit trail을 보존한다.

### 개인정보 노출

발견 즉시 해당 clinic을 `OFF`로 내리고 로그·metric·response·DB row를 격리한다. raw PII를
복사하거나 수동으로 decision에 붙이지 않는다. 회원관리시스템의 보안 사고 절차와 함께 처리하고,
이 저장소에는 bounded actor/correlation/reference만 남긴다.

## 직원 override/clear

1. 현재 decision을 조회한다.
2. `Idempotency-Key`, `decisionId`, `evaluationDigest`를 고정한다.
3. 사유는 allowlist `MANUAL_OVERRIDE` 또는 `MANUAL_CLEAR`를 사용한다.
4. 409 stale이면 재조회하고, 같은 key에 다른 payload를 재사용하지 않는다.
5. audit에서 actor, effective/expiry, result digest를 확인한다.

Override는 이미 `CONFIRMED`인 예약을 소급 변경하지 않는다. 제한된 신규 제안에 대해 직원이
검토를 완료했음을 표현하는 bounded 판정일 뿐이다.

## 보존과 롤백

모듈은 `retentionClass`와 법적 보류(legal hold)를 먼저 확인하는 bounded retention executor
계약을 제공한다. hold가 있으면 삭제하지 않는다. 기본 executor는 의도적으로 no-op이므로,
운영 배포에서는 승인된 삭제·가명화와 감사 증적을 수행하는 tenant/clinic 범위 executor를
주입해야 한다. V17은 additive migration이므로 롤백은 코드를 `OFF`/`SHADOW`로 낮추는 방식으로
수행하고 이미 적용한 migration을 재작성하지 않는다.

## 증거 기록

배포마다 [카나리 증거 템플릿](booking-reliability-canary-evidence-template.md)을 작성하고,
policy version/hash, mode, allowlist, decision 수, latency, backlog, attribution 누락,
PII scan, rollback 여부를 correlation ID와 함께 보관한다.

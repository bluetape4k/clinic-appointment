# Issue #170 대기 목록 코어 설계 검토 — Step 2-R 재검토

검토 대상: `docs/superpowers/specs/2026-08-01-issue-170-waitlist-core-design.md`

운영 산출물: `docs/runbooks/waitlist-core.md`

검토 기준 커밋: `b041179` (`feat/issue-170-waitlist-core`)

검토 기준: Type-A Step 2-R의 성능·안정성·보안·운영/Ops·개발자/API·사용자/호출자 6개 관점과 main-session 통합 검토. 모든 관점은 해당 커밋의 읽기 전용 설계를 대상으로 실행했다. 성능·보안·운영/Ops는 독립 native lane, 안정성·개발자/API·사용자/호출자는 main-session 독립 검토로 수행했다.

## 검토 근거

- GNO의 Issue #170 기록: 당일 빈자리 회복, offer lifecycle, replacement 경계, appointment DB 권위, 개인정보 비복제.
- GNO의 reservation control-plane 선례: PostgreSQL durable authority, resource lock과 hold/offer/waitlist/outbox의 같은 transaction, bounded sweeper, deterministic owner/idempotency.
- 현재 `ResourceAllocationRepository`: `ResourceCapacityBuckets` mutex와 half-open overlap/capacity 검증, `ResourceAllocations` active 상태.
- 현재 `BookingReliabilityRepository`: tenant·clinic·member scope의 latest decision과 `forUpdate` 경계. 설계는 이 단건 API를 후보 page batch port로 확장하되 transaction 안의 외부 호출을 금지한다.
- 현재 V17 migration: V18 additive migration, 기존 `scheduling_*` 이름 유지.

## 관점별 결과

| 관점 | 결과 | 핵심 확인 | 다음 검증 |
|---|---|---|---|
| 성능 | PASS — P0=0, P1=0, P2=0, P3=0 | candidate 인덱스가 `priority_rank DESC, waiting_since ASC, id ASC` 방향을 명시하고, migration metadata assertion·PostgreSQL/MySQL `EXPLAIN`·full scan/filesort 부재를 요구한다. page당 decision batch 1회와 page/후보/2초 budget, 100회 contention·p95 증거가 고정돼 있다(설계 §8.1, §9.2–§9.3, §12–§13). | Step 4-P에서 실제 migration/query plan, round-trip 수, JDBC pool·p95를 실행한다. |
| 안정성 | PASS — P0=0, P1=0, P2=0, P3=0 | durable hold가 `ResourceAllocationRepository` active occupancy에 포함되고, resource mutex → hold → offer → entry 순서, CAS/rollback, active vacancy key 재사용, reconcile의 offer·entry·hold·history 동시 terminal 전이가 고정돼 있다(설계 §4, §8.2.1, §9.4, §10, §10.2, §13). | Step 4-P에서 claim/offer/reconcile/withdraw race와 deadlock·재시작 증거를 실행한다. |
| 보안 | PASS — P0=0, P1=0, P2=0, P3=0 | offer와 hold 모두 tenant·clinic·member immutable scope를 갖고 세 row equality를 검증한다. actor는 opaque ID 또는 domain-separated HMAC, correlation ID는 bounded charset과 PII/log-injection 거부, SQL은 parameterized Exposed DSL로 제한된다(설계 §6, §8.2.1, §8.3, §10.2, §13). | Step 4-P에서 wrong-member hold replay/consume/reconcile, actor·correlation malicious input, PII redaction을 실행한다. |
| 운영/Ops | PASS — P0=0, P1=0, P2=0, P3=0 | V18 readiness, flag-off → clinic allowlist rollout, durable rollback, bounded recovery/backlog, metric/health 기준과 HMAC key rotation·stuck hold·slot conflict triage가 설계와 `docs/runbooks/waitlist-core.md`에 함께 있다. | 구현 후 실제 health/metric wiring과 운영 rehearsal을 실행한다. |
| 개발자/API | PASS — P0=0, P1=0, P2=1, P3=0 | `selectAndOffer`, `claim`, `release`, `reconcileWaitlistHolds`의 core port, stable result/error, caller-owned transaction과 adapter 책임이 명시돼 있다(설계 §10.1). `bluetape4k-states` compile/API probe와 exact Kotlin type mapping은 구현 계획 gate로 남긴다. | A-04 계획에서 compile probe, typed records, repository method signatures를 고정한다. |
| 사용자/호출자 | PASS — P0=0, P1=0, P2=1, P3=0 | 이름·전화번호는 core에 복제하지 않고 member ID와 hold ID만 handoff하며, `ACCEPTED`가 appointment 완료가 아님과 stable conflict/retry 의미를 명시한다. API caller별 예시와 후속 idempotency는 후속 adapter 범위다. | A-04 계획에서 caller action matrix와 replacement command idempotency를 연결한다. |

## 이전 blocker의 종결 증거

| 이전 finding | 종결 근거 |
|---|---|
| claim 이후 capacity가 사라질 수 있는 P0 | `WaitlistCapacityHolds`를 추가하고 `OFFERED`/`ACCEPTED`를 active occupancy로 계산한다. `ACCEPTED` 전이와 hold 확정을 같은 transaction으로 묶고 replacement가 hold를 소비하도록 했다(설계 §4, §8.2.1, §10). |
| offer 상태 권위 불명확 P1 | `WaitlistOffers.status`를 concrete offer의 권위 상태로 고정하고 entry summary와 같은 transaction에서 갱신한다. 불일치는 `OfferStateConflict` backlog로 보수적으로 처리한다(설계 §7.1). |
| offer/hold scope 누락 P1 | offer와 hold에 tenant·clinic·member snapshot을 두고 offer·entry·hold equality를 insert/update/reconcile/consume에서 확인한다. wrong-member negative test가 검증 기준에 포함됐다(설계 §6.1, §8.2.1, §13). |
| reliability decision N+1 P1 | 후보 page마다 batch provider를 한 번 호출하고 후보별 단건 조회를 금지한다. round-trip 수가 page 수와 일치하는지 검증한다(설계 §9.2, §13). |
| keyset/index 방향 P1 | 일반·의사 지정 인덱스에 `priority_rank DESC, waiting_since ASC, id ASC`를 명시하고 metadata assertion과 `EXPLAIN`을 요구한다(설계 §8.1, §9.3, §13). |
| vacancy 재사용 불가 P1 | immutable `vacancy_key`와 active 상태에서만 값이 있는 nullable `active_vacancy_key` unique 규칙으로 terminal 이후 재사용을 보장한다(설계 §8.2, §12). |
| offer 생성 경쟁 P1 | resource mutex, 후보 re-read, offer·hold·entry·history 동시 commit, bounded retry와 stable conflict mapping을 고정했다(설계 §9.4). |
| 만료 backlog/rollback P1 | 최대 100건(설정 상한 500건) bounded reconcile, rollback 재시도, flag-off 시 durable row 보존, 운영 triage를 고정했다(설계 §10.2, §13, runbook). |

## 통합 보류 항목

현재 설계 blocker는 없다. 다음 항목은 구현·계획 단계의 명시적 후속으로 남기며 Step 2-R을 막지 않는다.

1. `bluetape4k-states` artifact/API 실제 compile probe와 compatibility facade 동등성 테스트.
2. `WaitlistCapacityHolds`, repository batch decision port, migration/index metadata의 실제 Kotlin/Exposed 구현.
3. caller action matrix, replacement appointment의 hold consume/idempotency, HTTP/notification adapter.
4. 실제 H2/PostgreSQL/MySQL schema·EXPLAIN·100회 contention·recovery restart rehearsal.

각 항목은 A-04 구현 계획과 Step 4-P 검증 작업에 연결한다. 새 개인정보 저장, API 공개, scheduler/알림, replacement 생성은 이번 코어의 범위를 넓히지 않는다.

## 통합 판정

- 최신 통합 결과: `PASS`
- P0: `0`
- P1: `0`
- P2: `2` (개발자/API 1, 사용자/호출자 1 — 계획/후속 adapter로 명시적 defer)
- P3: `0`
- 구현/plan gate: Step 2-R 통과, A-04 계획 작성으로 진행 가능

이 문서는 설계 검토 결과다. 구현 source와 migration은 아직 변경하지 않았으며, 다음 gate는 계획 작성 및 계획 자체의 6개 관점 검토다.

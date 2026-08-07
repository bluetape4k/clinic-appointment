# Appointment Plan 워크플로 복구 리뷰

## 범위

- Workflow run: `20260726T192612Z-7a7c4518`
- Type: A — Full Feature
- Base: `c4f0dff1deccce37b2cf1345f321aceb140bbb40`
- Reviewed branch: `design/appointment-plan-scheduling`
- Initial reviewed commit: `b37901bac768c391bf7ae9d7e3178224dd3b76e3`
- Delivery authority: local repair와 commit만 허용하며 push, PR, merge, 운영
  `WRITE` 활성화는 제외

## 증거 정정

원본 문서는 독립적으로 귀속할 수 있는 reviewer output과 최종 artifact에 대한
최신 검증을 보존하지 않은 채 아래 리뷰 표를 완료된 gate 증거로 제시했습니다.
해당 완료 주장은 무효이며 Step 2-R, Step 3-R 또는 최종 7-Tier gate 승인에
사용해서는 안 됩니다.

아래 표는 과거 복구 맥락으로만 유지합니다. 독립된 여섯 reviewer가 현재
artifact의 정확한 finding을 보고하고 main session이 모든 P0/P1을 닫은 뒤의
권위 있는 재실행 결과는 **Verified Step 2-R/3-R Rerun**에 기록합니다. 최종
7-Tier 증거는 구현 검증 후 별도로 기록합니다.

## Step 2-R 초기 리뷰

독립된 여섯 개의 read-only lens가 정확한 Markdown specification을 리뷰했습니다.

| Lens | P0 | P1 | P2 | 초기 판정 |
|---|---:|---:|---:|---|
| Performance | 0 | 3 | 3 | FAIL |
| Stability | 0 | 6 | 2 | FAIL |
| Security | 0 | 3 | 1 | FAIL |
| Operator/Ops | 0 | 5 | 4 | FAIL |
| Developer/API | 0 | 5 | 2 | FAIL |
| User/caller | 0 | 5 | 4 | FAIL |
| Total before deduplication | 0 | 27 | 16 | FAIL |

main-session integration은 blocking finding을 다음 복구 그룹으로 중복 제거했습니다.

1. Foundation slice와 장기 roadmap의 경계
2. tenant/clinic/source-authority를 포함한 catalog·purchase identity
3. canonical `EXPIRED` lifecycle
4. policy cache 경계, activation CAS, stale-result fencing
5. saga retry, cancellation, repair, orphan cleanup
6. disruption/solver result fencing과 품질 하한
7. trust material rotation/revocation과 event quarantine
8. privileged dry-run redrive와 tamper-evident audit
9. Foundation event schema와 PHI 분류
10. 정확한 Foundation HTTP/error/authorization 계약
11. deferred API를 위한 caller-safe booking/consent/misuse 계약
12. rollback-safe legacy projection과 online backfill 계약
13. slot-query, cache, solver, queue, migration benchmark 계약
14. feature flag, kill switch, alert routing, backpressure 순서
15. 운영 enablement threshold와 immutable release 증거
16. reliability profile 설명, 정정, appeal
17. 정확한 Foundation acceptance command와 multi-dialect 순서
18. standalone HTML current-slice, risk, normative-source 명확성

Markdown specification과 standalone HTML은 main session에서 복구했습니다. 이
수정은 승인된 model과 운영 계약을 명확히 할 뿐, 구현된 Foundation slice를
확장하지 않습니다.

## Step 2-R 재리뷰

영향을 받은 모든 lens가 정확히 복구된 artifact를 다시 리뷰했습니다.
developer/API 재리뷰에서 catalog event dedupe key에 tenant/clinic/source
authority가 여전히 빠진 추가 P1 하나를 발견했습니다. main integration은
`PurchaseCompleted`가 `catalogSourceAuthority` 없이는 authority-qualified
catalog를 선택할 수 없다는 점도 발견했습니다. 두 계약을 복구하고 해당 lens를
다시 실행했습니다.

| Lens | 최종 P0 | 최종 P1 | 최종 P2 | 최종 P3 | 판정 |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | PASS |
| Stability | 0 | 0 | 0 | 0 | PASS |
| Security | 0 | 0 | 0 | 0 | PASS |
| Operator/Ops | 0 | 0 | 0 | 0 | PASS |
| Developer/API | 0 | 0 | 0 | 0 | PASS |
| User/caller | 0 | 0 | 0 | 0 | PASS |

재리뷰에서는 현재 artifact에서 복구할 수 있는 non-blocking finding도 닫았습니다.

- quarantine retention and legal-hold ownership;
- `EXPIRED` in the lifecycle conflict matrix;
- HTML backfill timing, terminal-state accessibility, and first-column sizing;
- external `kid` to internal `keyId` mapping;
- catalog event and purchase catalog-source authority.

Step 2-R 통합 결과: **P0=0, P1=0**.

## 필수 downstream blocker

이는 해결되지 않은 specification 모순이 아니라 구현/plan 일관성 결함입니다.

1. Exposed와 모든 V8 dialect migration은 catalog uniqueness와 exact/latest
   lookup predicate에 `sourceAuthority`를 추가해야 합니다.
2. Exposed와 모든 V8 dialect migration은 tenant와 clinic 범위로 purchase
   uniqueness를 제한해야 합니다.
3. `PurchaseCompletedEvent`와 plan snapshot persistence는
   `catalogSourceAuthority`를 전달해야 합니다.
4. Repository, handler, race, security, migration, benchmark, `EXPLAIN` test는
   H2, PostgreSQL, MySQL에서 수정된 identity를 증명해야 합니다.

## Step 3-R 초기 리뷰

독립된 여섯 개의 read-only lens가 Step 2-R에서 수렴한 specification을 기준으로
실행 가능한 Markdown plan을 리뷰했습니다.

| Lens | P0 | P1 | P2 | P3 | 초기 판정 |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 1 | 1 | 0 | FAIL |
| Stability | 0 | 1 | 0 | 0 | FAIL |
| Security | 0 | 2 | 0 | 0 | FAIL |
| Operator/Ops | 0 | 1 | 2 | 0 | FAIL |
| Developer/API | 0 | 1 | 2 | 1 | FAIL |
| User/caller | 0 | 0 | 1 | 0 | PASS with P2 |
| Total before deduplication | 0 | 6 | 6 | 1 | FAIL |

main-session integration은 다음 그룹을 복구했습니다.

1. executable PostgreSQL/MySQL purchase-expansion performance and `EXPLAIN`
   tasks, fixtures, thresholds, commands, and release evidence;
2. explicit security, configuration, migration, dialect, and final acceptance
   commands;
3. disallowed algorithm, unknown/revoked `kid`, key-pin mismatch, and external
   `kid` to internal `keyId` mapping tests;
4. authority-qualified catalog and purchase identities across model, table,
   repository, event, factory, response, and all V8 dialects;
5. immutable encrypted quarantine storage, append-only audit, retention, legal
   hold, metric label/cardinality budget, and alert ownership;
6. source-authority timeout/circuit-open convergence plus invalid
   retry/backoff/jitter/window configuration rejection;
7. `ACTIVE`/`RETIRED` catalog projection lifecycle;
8. explicit RED ordering for index-plan assertions and reproducible final
   security/stability evidence;
9. standalone HTML parse, relative-link, anchor, desktop, and mobile smoke.

Operator/Ops 재리뷰에서 추가 P1 하나를 발견했습니다. specification은 5분
이내 확인과 즉시 consumer block/quarantine을 요구하지만, critical
trust/signature/scope incident에 15분 acknowledgement를 사용하고 있었습니다.
운영 계약, runbook task, release-evidence 계약에서 plan을 복구한 뒤 다시
리뷰했습니다.

## Step 3-R 재리뷰

| Lens | 최종 P0 | 최종 P1 | 최종 P2 | 최종 P3 | 판정 |
|---|---:|---:|---:|---:|---|
| Performance | 0 | 0 | 0 | 0 | PASS |
| Stability | 0 | 0 | 0 | 0 | PASS |
| Security | 0 | 0 | 0 | 0 | PASS |
| Operator/Ops | 0 | 0 | 0 | 0 | PASS |
| Developer/API | 0 | 0 | 0 | 0 | PASS |
| User/caller | 0 | 0 | 0 | 1 | PASS |

남은 user/caller P3는 이후 Task 7에서 문구를 개선할 항목입니다. runbook은
일반적인 authority/version 표현 대신 `sourcePurchaseAuthority + sourcePurchaseId`와
`catalogSourceAuthority + productId + catalogVersion`을 명시해야 합니다.

Step 3-R 통합 결과: **P0=0, P1=0**.

## 구현에서 발견한 Step 3-R 보완

실행 가능한 performance fixture에서 초기 Step 3-R 재리뷰가 놓친 두 가지
모순이 드러났습니다.

1. 승인된 `2,000 treatment / 10,000 edge` Foundation fixture는 도달할 수
   없었습니다. 명시적인 catalog dependency 하나가 persisted treatment edge
   하나를 만들고 catalog 계약은 explicit dependency를 최대 1,000개까지
   허용합니다. 따라서 도달 가능한 persisted 최대값은 2,000 treatment와
   1,000 dependency row입니다. validator는 implicit repeat-order edge 1,980개를
   추가한 뒤 graph edge를 최대 2,980개까지 별도로 검사합니다.
2. `uq_plan_source_purchase`와 `idx_plan_scope_purchase`가 같은 column을
   index하고 있었습니다. PostgreSQL은 둘 중 하나를 선택할 수 있어 named-index
   증명이 비결정적이고 불필요한 write 비용도 발생했습니다. non-unique 중복을
   Exposed, 세 V8 dialect, migration 기대값에서 제거했습니다.

256 KiB validator는 wire payload의 일부가 아닌 diagnostic property path와 array
index도 계산하고 있었습니다. 집중 RED test에서 compact maximum graph가
거부되는 것을 확인했습니다. estimator는 이제 escaping을 포함한 보수적인
JSON-shaped representation을 계산하며 byte limit을 완화하지 않고 해당 fixture를
허용합니다.

독립된 보완 리뷰에서는 다음과 같은 실행 증명 공백도 발견했습니다.

3. EXPLAIN test가 compile되지 않았고 120-row fixture가 승인된
   100,000-row/20-partition 계약을 충족하지 못했습니다.
4. dependency selectivity가 자연스러운 PostgreSQL index 선택에 충분하지
   않았고 outbox oldest-age 경로도 없었습니다.
5. performance test가 마지막 측정 sample에 대해서만 SQL 상한을 확인했으며
   초기 inbox read 후에 capture를 시작했습니다.
6. estimator에 실제 canonical API payload byte 증명이 없었습니다.

복구된 gate는 이제 plan, inbox, outbox row 각 100,000개, tenant/clinic partition
20개, 각각 2,000 treatment/1,000 persisted dependency를 가진 dependency-bearing
plan 20개를 사용합니다. retry와 pending queue row는 대표적인 1% 분포로
유지합니다. PostgreSQL과 MySQL 모두 optimizer forcing 없이 명명된 다섯 index를
선택합니다. 측정하는 모든 purchase sample은 전체 transaction을 수집하고 동일한
제한된 17-statement 형태를 증명합니다. Jackson 3은 변경하지 않은 262,144-byte
limit 아래인 194,876-byte의 최대 compact API request를 직렬화합니다.

대표 cleanup에서는 누락된 reverse-FK index도 드러났습니다. V8과 Exposed
definition에는 이제 `idx_outbox_plan_id`와 `idx_treatment_dependency_successor`가
포함되며, PostgreSQL fixture cleanup은 116,489 ms에서 16,953 ms로 줄었습니다.

영향을 받은 두 lens는 보완된 실행 증명을 독립적으로 다시 리뷰했습니다.

| 보완 lens | P0 | P1 | 증거 복구 전 P2 | 최종 P2 | P3 | 판정 |
|---|---:|---:|---:|---:|---:|---|
| Performance/stability | 0 | 0 | 2 | 0 | 1 watch item | PASS |
| Developer/API | 0 | 0 | 0 | 0 | 1 stale-number finding | PASS |

performance reviewer는 statement class에는 상한이 있지만 전체 SQL count는
출력만 하고 있었고, release record에 정확한 command, timestamp, implementation
SHA, report path, raw sample이 없다는 점을 발견했습니다. 이제 test는 모든 측정
transaction에 대해 정확히 18 statement를 assert하고 release record는 요청된
재현 증거를 보존합니다. 남은 P3 watch item은 near-limit에서 문자열이 많은
catalog payload에 대한 adversarial case입니다. Foundation 최대 compact payload
자체는 실제 Jackson 3 serialization으로 증명했습니다.

developer/API reviewer는 100,000-row fixture, 다섯 개의 natural index path,
완전한 transaction capture, canonical payload proof를 독립적으로 확인했습니다.
release record의 PostgreSQL p95 값만 오래된 것으로 확인했습니다. 해당 값을
최종 post-assertion 재실행 결과로 교체했습니다.
PostgreSQL `26 ms / 1,349 ms`, MySQL `17 ms / 797 ms`.

Step 3-R 보완 통합 결과: **P0=0, P1=0, P2=0**.

## 권위 있는 Step 2-R 및 Step 3-R 증거

복구 작업에서는 현재 artifact를 대상으로 두 review gate를 다시 실행했고,
각 gate에 독립적인 여섯 read-only lens를 적용했습니다. 각 reviewer는 severity별
정확한 finding을 보고했으며, main-session integration은 blocking finding을
복구하고 영향을 받은 lens를 재리뷰에 보냈습니다.

| Gate | Performance | Stability | Security | Operator/Ops | Developer/API | User/caller | 통합 결과 |
|---|---|---|---|---|---|---|---|
| Step 2-R specification | PASS | PASS | PASS | PASS | PASS | PASS | `P0=0 P1=0` |
| Step 3-R executable plan | PASS | PASS | PASS | PASS | PASS | PASS | `P0=0 P1=0` |

이 결과가 권위 있는 2-R/3-R 결과입니다. 위의 과거 표는 artifact가 어떻게
변경되었는지를 설명할 뿐, 그 자체로 완료 증거가 되지 않습니다.

## 최종 7-Tier 구현 리뷰

독립적인 여섯 구현 lens가 현재 diff를 리뷰했습니다. main-session integration이
일곱 번째 tier로서 중복 제거, 복구, 재리뷰, 최신 검증을 담당했습니다.

| Tier | 최종 P0 | 최종 P1 | 최종 P2 | 최종 P3 | 판정 |
|---|---:|---:|---:|---:|---|
| Performance/runtime | 0 | 0 | 2 | 2 | PASS |
| Architecture/stability | 0 | 0 | 2 | 0 | PASS |
| Security/privacy | 0 | 0 | 1 | 2 | PASS |
| Operator/SRE | 0 | 0 | 3 | 1 | PASS |
| Developer/API | 0 | 0 | 2 | 0 | PASS |
| User/operator | 0 | 0 | 0 | 0 | PASS |
| Main integration | 0 | 0 | 0 | 0 | PASS |

이 최종 gate에서 발견하고 닫은 blocking finding은 다음과 같습니다.

1. catalog v7/v8 out-of-order synchronization now serializes per clinic and
   rechecks exact/latest versions under lock;
2. quarantine release/legal-hold transitions now use compare-and-set semantics,
   preventing stale release from overwriting concurrent expiry;
3. catalog and plan success responses now expose concrete OpenAPI envelope
   schemas, pinned by `/v3/api-docs` tests;
4. the recovery runbook now distinguishes terminal untrusted-event rejection
   evidence from releasable quarantine and uses schema-valid bounded selectors;
5. English/Korean API docs가 이제 exact-scope SaaS feature override와 운영
   audit/readback gate를 설명합니다.

production transport와 이후 API slice에는 non-blocking P2/P3 항목이 남아
있습니다. typed outbox payload 계약, source-version DB hardening, 예외를
던지지 않는 metric, broker malformed-message DLQ proof, 전체 transport
readiness 의미, 더 좁은 error-envelope schema가 해당합니다. 어느 항목도 운영
`WRITE`를 허용하지 않습니다.

최종 7-Tier 통합 결과: **PASS; P0=0, P1=0**.

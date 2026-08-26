# Issue #311 대기목록 fenced scheduler 최종 7-Tier 검토

## DoD Status

`PENDING` — 로컬 구현과 검증은 완료했지만, 원격 PR/CI read-back과 exact head에 묶인
fresh merge approval 전이다. 이 검토에서는 merge나 auto-merge를 수행하지 않는다.

- Issue: [#311](https://github.com/bluetape4k/clinic-appointment/issues/311)
- Repository: `bluetape4k/clinic-appointment`
- Base: `origin/develop` / `1859b5cb3ae68c25e918236b0923d74d845e6726`
- Head branch: `feat/issue-311-waitlist-fencing`
- Current implementation head: `0100b9ed`
- Scope: `appointment-core` DB fence와 `appointment-api` typed Lettuce fenced scheduler,
  V31 migration, 회귀 테스트, API/runbook/lesson/checklist

## 1. 검토 범위와 acceptance traceability

| Issue 조건 | 구현·근거 | 판정 |
|---|---|---|
| 실제 Redis/DB 경계와 authority 확정 | `WaitlistFencedLeaderLease`, `WaitlistFencedDeliveryScheduling`, `WaitlistDeliveryRepository.claimFenced`, API/runbook | PASS |
| `LettuceFencedLock`과 DB strict-greater 결합 | `WaitlistFencingToken`, V31 `fence_epoch`/`fence_sequence`, exact terminal predicate | PASS |
| fixed lease·timeout·ambiguous·close·rollback 문서화 | `WaitlistDeliveryProperties`, typed outcome, readiness/fail-closed, API/runbook | PASS |
| stale owner·wrong release·expiry takeover·cancellation·close·redaction 회귀 | core/API unit, PostgreSQL contention, Redis 8.8 singleton integration | PASS |
| API module과 Redis 테스트 통과 | 579 core, 900 API(3 skipped), Redis 대상 회귀 GREEN | PASS |

## 2. 변경 모듈과 재사용 경계

### `appointment-core`

- `WaitlistVacancyJobs`에 additive V31 fence columns를 추가했다.
- `WaitlistFencingToken`은 DB sentinel `(0, 0)`을 저장 표현으로 허용하되,
  `claimFenced` 입력은 `isRedisIssued()`의 양수 epoch/sequence만 허용한다.
- `WaitlistDeliveryRepository.claimFenced`는 strict-greater token으로 claim하고,
  terminal mutation은 동일 token exact-match와 기존 owner/version/lease fence를 함께
  검증한다. 모든 Exposed 호출은 기존 caller-owned `transaction {}` 경계를 재사용한다.
- 테스트는 `bluetape4k-assertions`, 기존 singleton DB launcher와 PostgreSQL concurrency
  fixture를 사용하며 `@Testcontainers`를 도입하지 않았다.

### `appointment-api`

- 이미 의존 중인 `io.github.bluetape4k:bluetape4k-lettuce:1.12.1`의
  `LettuceFencedLock`, `LockConfig`, `FencedLockConfig`, typed result를 얇은
  `WaitlistFencedLockOperations` adapter로 재사용한다.
- owner reference는 `Base58.randomString(8)`이며 native owner/request/handle/token/key는
  API·로그·metric 경계로 노출하지 않는다. `KLogging`과 bounded outcome metric을
  기존 facade에 확장했다.
- `WaitlistFencedSchedulingConfiguration`은 `enabled=true`, Redis/DataSource, V31
  readiness, typed dispatcher와 recovery port가 모두 있을 때만 scheduler를 조립한다.
  production dispatcher port가 아직 외부 adapter로 제공되지 않은 예제 상태에서는
  no-op/fake bean을 만들지 않고 fail-closed한다. 기존 Boolean scheduler와
  reminder `LeaderGroupElector` 경계는 변경하지 않았다.

## 3. 7-Tier 판정

### Tier 1 — 신뢰성/정합성

- 명확한 `Acquired`/`Reentered`만 safety 작업과 typed dispatch를 시작한다.
- `Ambiguous`는 동일 owner/request로 한 번 reconcile하며, handle이 없으면 DB mutation을
  하지 않는다.
- DB claim은 저장 token보다 엄격히 큰 값만 허용하고 terminal write는 exact-match다.
- V31 readiness 실패는 `WaitlistFencingReadinessException` cause chain을 보존하며
  startup을 조용히 통과하지 않는다.
- 판정: `PASS`, P0=0, P1=0.

### Tier 2 — 성능/동시성

- fixed `jobLease`에 비해 `tickBudget`을 짧게 검증하고, 각 mutating port 전
  `System.nanoTime()` 기반 elapsed를 확인해 다음 작업을 차단한다.
- `AtomicBoolean` in-flight gate와 실제 2-thread latch 테스트로 중복 tick을 제한한다.
- Micrometer 유한 enum 상태는 생성 시 pre-register하여 tick마다 builder/register하지
  않는다.
- PostgreSQL 동시 claim은 두 contender 중 한 winner만 허용한다.
- 독립 성능 검토에서 제기된 5개 P1/P2 항목을 모두 수정하고 테스트로 고정했다.
- 판정: `PASS`, open P0/P1/P2/P3=0.

### Tier 3 — 보안/정보 노출

- `WaitlistLeaseHandle.toString()`과 `WaitlistFencingToken.toString()`은 모두
  `redacted`만 표시한다.
- Base58 8자리 owner는 opaque reference이며 native identity는 adapter 내부에서만
  유지한다.
- metric tag는 enum 기반 `outcome`, `mode`, `source`만 허용하고 owner/request/token,
  Redis key, tenant/member/entry/offer ID를 넣지 않는다.
- release가 ambiguous/unknown이면 pending handle을 보존하고 최대 2회 native 시도 후
  종료하므로, 첫 unknown에서 active handle을 잃지 않는다.
- 독립 보안 검토의 identity redaction·양수 token provenance·bounded retry 항목을 모두
  반영했다.
- 판정: `PASS`, open P0/P1/P2/P3=0.

### Tier 4 — 운영/관측/복구

- acquire outcome, scheduler duration, ownership loss와 bounded 작업 count를 기존
  `WaitlistDeliveryMetrics`로 관측한다.
- 로그는 outcome과 exception class만 기록하며 raw key/owner/request/token을 기록하지
  않는다.
- `enabled=false`는 new dispatch를 끄고 expiry/suppression/hold recovery semantics는
  보존하는 rollback 경계를 문서화했다.
- Redis 8.8 singleton에서 lease expiry takeover, stale release, ambiguous reconcile,
  metric redaction을 실제로 검증했다.
- 판정: `PASS`, open P0/P1/P2/P3=0.

### Tier 5 — 개발자/API/호출자

- `WaitlistFencedVacancyDispatcher`가 `WaitlistLeaseHandle`을 받아 token을 전달하도록
  typed contract를 좁혔다.
- `WaitlistLeaseAttempt`, `WaitlistLeaseRelease`, `WaitlistFencedDeliveryTickResult`로
  실패 분류와 결과를 Boolean보다 명확히 표현한다.
- 기존 `WaitlistLeaderLease`와 Boolean runner는 호환 경계로 남기고, 새 scheduler가
  legacy lease와 이중 획득하지 않게 분리했다.
- 새 public-facing KDoc와 운영 문서는 한국어로 작성했고 technical identifier는 보존했다.
- 판정: `PASS`, open P0/P1/P2/P3=0.

### Tier 6 — 사용자/업무 흐름

- lease를 얻지 못하거나 DB fence가 거절되면 vacancy dispatch를 시작하지 않아 stale
  worker가 사용자 offer를 중복 생성하지 않는다.
- expiry → suppression → hold reconcile → dispatch 순서를 유지하고, global-off와
  clinic-disabled에서도 안전 작업을 수행한다.
- tick budget 초과 시 다음 mutation을 생략하고 결과에 `budgetExceeded`를 남긴다.
- 판정: `PASS`, open P0/P1/P2/P3=0.

### Tier 7 — 유지보수/아키텍처

- 기존 domain 정책·candidate/offer state machine·reminder authority를 건드리지 않고
  typed adapter, repository fence, conditional configuration으로 책임을 분리했다.
- 새 dependency나 Redis script를 추가하지 않고 bluetape4k Lettuce/Base58/KLogging와
  기존 Exposed/metrics/test fixture를 재사용했다.
- 첫 잘못된 12-component workflow 시도는 mutation 전에 취소하고 corrected run으로
  복구했으며, lesson/checklist에 원인과 guard를 남겼다.
- 판정: `PASS`, open P0/P1/P2/P3=0.

## 4. Kotlin·bluetape4k 최종 체크

| 항목 | 증거 | 판정 |
|---|---|---|
| KT-FIN-01 caller/doc 영향 | core repository/table, API runner/config, migration, API/runbook | PASS |
| KT-FIN-02 bluetape 재사용 | `LettuceFencedLock`, `Base58`, `KLogging`, `bluetape4k-assertions`, singleton launchers | PASS |
| KT-FIN-03 production `!!` 금지 | 변경 production 범위에서 `!!` 없음 | PASS |
| KT-FIN-04 suspend `runCatching` 금지 | 새 `runCatching`은 모두 동기 adapter/runner 경계 | PASS |
| KT-FIN-05 resource/lifecycle | fixed lease, pending release retry, close idempotency, connection ownership | PASS |
| KT-FIN-06 Exposed 경계 | caller-owned `transaction {}`, strict predicate와 migration contract | PASS |
| KT-FIN-07 Spring/test 규칙 | conditional fail-closed, no `@Testcontainers`, singleton Redis/DB | PASS |
| KT-FIN-08 named behavior tests | claim, expiry takeover, ambiguous, close, budget, concurrency, redaction | PASS |
| KT-FIN-09 Korean KDoc/docs | API/runbook/lesson/checklist 및 변경 KDoc | PASS |
| KT-FIN-10 diagnostics/fallback | readiness cause chain, typed outcomes, bounded retry, logs | PASS |
| KT-FIN-11 fresh verification | full module test/build와 static/document audit | PASS |

## 5. SPW-01..SPW-05와 증거

- SPW-01 source ledger: Issue #311, current code, Gradle dependency, local
  `bluetape4k-lettuce:1.12.1` API와 기존 문서를 대조했다.
- SPW-02 plan/spec: exact files, signatures, SQL, RED→GREEN, rollback과 AC-01..AC-08가
  `docs/superpowers/specs/`, `plans/`, `reviews/`에 있다.
- SPW-03 Korean naturalness: `audit-korean-terms.mjs`가 API/runbook/lesson/checklist 및
  최종 review 변경을 통과했고 `git diff --check`도 통과했다.
- SPW-04 traceability: design/plan review의 여섯 관점과 이번 7-Tier 통합 판정을
  성능·보안 보정 commit 및 테스트에 연결했다.
- SPW-05 read-back: migration V31, derived Redis key, positive external token,
  `tickBudget < jobLease`, bounded release retry, redaction이 코드·테스트·문서에서
  같은 계약으로 확인된다.

## 6. 검증 결과

```text
:appointment-core:test       SUCCESS: Executed 579 tests in 34.7s
:appointment-api:test        SUCCESS: Executed 900 tests in 3m 18s (3 skipped)
:appointment-core:build
:appointment-api:build       BUILD SUCCESSFUL in 5s
git diff --check              PASS
Korean terminology audit      PASS
```

기존 환경 경고인 Netty restricted native access와 Exposed test API deprecation은
이번 변경에서 발생한 오류가 아니며 build/test를 실패시키지 않았다. 원격 PR CI는
PR 생성 후 exact head로 다시 확인해야 한다.

## 7. 최종 판정과 잔여 게이트

- 최종 findings: `P0=0`, `P1=0`, `P2=0`, `P3=0`.
- production dispatcher의 구체 구현은 현재 예제의 외부 adapter port 범위 밖이다. 따라서
  typed port가 없으면 scheduler를 활성화하지 않는 fail-closed가 의도된 계약이며,
  이를 위해 no-op/fake를 추가하지 않았다. 이는 open finding이 아니라 명시된 scope guard다.
- 로컬 DoD는 완료했지만 PR live metadata/checks/reviews와 fresh exact-head merge approval은
  아직 남아 있다. 그 전까지 최종 상태는 `PENDING`이다.

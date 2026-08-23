# Issue #311 waitlist scheduler fencing 실행 계획

## 목표

Issue #311의 현재 저장소 증거를 기준으로, 기존 DB `owner/version/leaseVersion`
fence를 약화하지 않으면서 `LettuceFencedLock` 도입 보류 경계를 운영 문서와
GitHub 추적 항목에 고정한다. Redis token을 DB strict-greater predicate까지
전달할 production 경로가 확인되기 전에는 Kotlin 코드, DB schema, lock wiring을
변경하지 않는다.

설계 승인 문서:
[`2026-08-23-issue-311-waitlist-fencing-design.md`](../specs/2026-08-23-issue-311-waitlist-fencing-design.md)

## 작업 경계

### 변경 대상

| 경로 | 변경 목적 |
|---|---|
| `docs/api/waitlist-delivery.md` | DB fence가 최종 권위이며 Redis fenced lock은 token propagation 전까지 보류임을 API/운영 계약에 명시 |
| `docs/runbooks/waitlist-delivery.md` | 보류 기간의 rollout, 장애, ambiguous 결과, redaction 운영 절차를 고정 |
| `docs/lessons/2026-08-23-issue-311-waitlist-fencing.md` | 조사 증거, 대안 기각, 재개 조건, 다음 이슈를 durable lesson으로 기록 |
| `docs/superpowers/specs/2026-08-23-issue-311-waitlist-fencing-design.md` | 승인된 설계를 단일 decision source로 유지; 필요한 경우 실행 중 발견한 사실만 보정 |

### 변경하지 않는 대상

- `WaitlistLeaderLease` Boolean API
- `WaitlistDeliverySchedulingRunner` 순서와 release semantics
- `WaitlistDeliveryRepository`의 claim/terminal CAS predicate
- `WaitlistVacancyJobs` 모델 및 V19 migration
- `LeaderGroupElector` reminder recovery
- Redis key namespace, connection lifecycle, 신규 dependency

## 선행 조건과 안전 경계

1. 실행 worktree는 `.worktrees/issue-311-waitlist-fencing`이고 branch는
   `feat/issue-311-waitlist-fencing`이다. root `develop`의 기존 dirty 파일과 다른
   worktree를 변경하지 않는다.
2. 각 mutation 전 `bluetape-flow.py mutation-check`를 실행해 현재 run, target,
   session uniqueness를 재확인한다.
3. 문서 작업 중 실제 production runner/wiring 또는 token propagation이 발견되면
   즉시 계획을 멈추고 설계 재승인을 받는다. 승인 없이 schema·API·lock을 부분
   전환하지 않는다.
4. GitHub Issue/PR mutation 전 현재 repository `AGENTS.md` 계층, duplicate,
   label, milestone, assignee를 다시 읽고 live read-back한다.

## 단계별 실행

### 1단계: 운영 계약 문서 보강

`docs/api/waitlist-delivery.md`의 롤아웃 인접 영역에 다음을 추가한다.

- terminal write authority는 DB claim fence라는 선언
- Redis scheduler lease는 advisory gate라는 선언
- `LettuceFencedLock`은 strict-greater DB propagation이 준비될 때까지 연결하지
  않는다는 보류 상태
- owner/request/token/key/actor/payload 원문을 metric/log·decision/audit sample·provider
  evidence에 기록하지 않는 redaction 규칙과, actor는 `SYSTEM` 또는 full keyed HMAC,
  evidence correlation은 일반 HTTP trace와 분리한 서버 생성 random/keyed opaque 값만
  사용한다는 계약. 현재 일반 waitlist audit의 caller correlation과 비키드·truncated
  actor는 이 계약을 충족하지 않으므로 fenced path 활성화 전 별도 보정·회귀 검증이
  필요하다.
- tick p95/p99와 `jobLease >= worst-case tick + safety margin` invariant
- Redis backend error/ambiguous용 bounded backoff+jitter 또는 circuit breaker와 retry budget
- 고정 category 기반 scheduler/acquisition latency와 ownership-loss metric
- 보류 중 rollback은 dispatch를 멈추되 expiry/suppression/reconcile은 유지한다는
  recovery semantics

`docs/runbooks/waitlist-delivery.md`에는 다음 운영 절차를 보강한다.

- tick 시작 acquisition failure와 DB stale-owner 거부를 현재 관측 가능한 bounded
  outcome으로 기록하고, post-acquire ownership loss/backend failure는 fenced path
  활성화 후 typed result·ownership-loss metric acceptance로 분류
- `Ambiguous`/unknown 결과를 mutation 재시도로 승격하지 않는 규칙
- fixed lease/watchdog, close, cancellation, task leak 계약이 확인되기 전에는
  fenced lock을 활성화하지 않는 hold check
- 운영 증거에 raw identity를 남기지 않고 category·latency·bounded count만 남기는
  규칙
- acquisition failure, post-acquire ownership loss, contention, backend failure를
  구분하고 retry storm을 막는 bounded retry budget

### 2단계: lesson 기록

`docs/lessons/2026-08-23-issue-311-waitlist-fencing.md`에 다음을 Korean으로
기록한다.

- 현재 `WaitlistLeaderLease`와 scheduler wiring의 실제 조사 결과
- `WaitlistDeliveryRepository.terminalUpdate`의 owner/version/expiry predicate
- Lettuce 공식 계약의 `bootstrapFencing`, strict-greater, fixed lease,
  ambiguous reconcile, close semantics
- adapter-only와 즉시 schema 확장 대안을 기각한 이유
- production 도입 재개 조건 전체와 필수 회귀 시나리오
- Issue #311 및 후속 Issue의 관계와 현재 보류 상태

### 3단계: 문서·현재 코드 계약 검증

문서 변경 후 다음 순서로 검증한다.

```bash
git diff --check
./gradlew :appointment-api:test \
  --tests 'io.bluetape4k.clinic.appointment.api.waitlist.WaitlistDeliverySchedulingTest' \
  --tests 'io.bluetape4k.clinic.appointment.api.integration.WaitlistDeliveryRecoveryDrillTest'
./gradlew :appointment-core:test \
  --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryRepositoryTest' \
  --tests 'io.bluetape4k.clinic.appointment.waitlist.WaitlistDeliveryPostgreSqlContentionTest'
```

`WaitlistDeliveryRecoveryDrillTest`가 사용하는 PostgreSQL/Redis singleton 환경
조건을 확인한다. container가 실패하면 skipped를 성공으로 취급하지 않고 원인을
진단한다. 이번 범위에 Kotlin 또는 schema diff가 생기지 않는지 `git diff --stat`
와 `git diff --name-only`로 확인한다.

### 4단계: six-perspective review

문서 diff와 검증 결과를 다음 여섯 관점으로 독립 검토한다. 검토자는 보류 결정을
뒤집을 수 있는 근거와 누락된 위험만 보고하며, 근거 없는 refactor를 제안하지
않는다.

1. architecture: advisory Redis와 DB business authority의 경계, 재개 조건
2. security: raw owner/request/token/key redaction, secret·PII 노출
3. performance: bounded lease/watchdog, schema/index 비용, retry 폭주
4. SRE: rollback, ambiguous/unknown outcome, health·alert·operability
5. library/API: `LettuceFencedLock` lifecycle, typed result, `close`/reconcile 계약
6. Kotlin/test: 기존 포트·테스트 계약 보존, Exposed transaction 경계, 검증 누락

리뷰 중 실제 구현 필요성이 확인되면 문서-only 작업을 중단하고 새 설계/계획
승인을 받는다.

### 5단계: GitHub 추적 항목 정리

문서와 테스트가 green인 뒤 live GitHub를 다시 조회한다.

1. Issue #311의 현재 body, labels, milestone, assignee, open/closed 상태를
   읽는다.
2. Issue #311의 현재 범위와 같은 production runner/wiring, typed result, token
   propagation, migration, production-like test 구현을 별도 Issue가 중복 소유하지
   않는지 검색한다.
3. 동일 범위의 새 Issue는 만들지 않는다. #311이 재개 tracker로 계속 소유한다.
4. #311이 소유하지 않는 독립 prerequisite가 실제로 발견될 때만 Korean structured
   body, `debop` assignee, labels/milestone을 적용해 별도 Issue를 만들고 양방향
   링크한다.
5. Issue #311에는 설계 문서와 lesson URL을 Korean comment로 남기고, 현재는
   `LettuceFencedLock` 도입 보류 상태임을 명시한다. #311은 실제 fenced
   implementation이 완료되기 전까지 닫지 않는다.
6. Issue mutation 후 title/body/labels/milestone/assignee/links를 live read-back한다.

### 6단계: commit, PR, CI

문서 diff와 Issue read-back이 green이면 Korean Lore commit으로 저장한다.

Commit intent 예시:

```text
DB fencing 경계를 보존하며 waitlist fenced lock 도입 보류를 기록한다

Constraint: Redis fencing token을 DB strict-greater predicate까지 전달하는 production 경로가 없다
Rejected: Boolean 포트에 fenced lock만 덧붙이는 부분 전환 | 실질적인 stale write 보호를 강화하지 못함
Confidence: high
Scope-risk: narrow
Directive: token propagation과 전체 terminal mutation call site가 확인되기 전에는 lock을 production path에 연결하지 않는다
Tested: diff check와 waitlist scheduler/recovery/repository 회귀 테스트
Not-tested: 신규 Redis fenced lock integration은 production wiring 부재로 보류
```

PR body는 Korean으로 작성하고 다음을 포함한다.

- `Refs #311`; 독립 prerequisite Issue를 실제 생성한 경우에만 해당 링크 추가
- 보류 결정과 이유
- 변경 파일 목록
- 테스트 명령과 결과
- `## DoD Status` 섹션
- 새 dependency/schema/production lock wiring을 추가하지 않았다는 범위 확인

PR 생성 후에는 exact head, status checks, review threads, mergeability, body와
`## DoD Status`를 live read-back한다. merge는 별도 fresh approval 전에는 실행하지
않는다.

### 7단계: merge 이후 local sync와 정리

사용자가 merge를 승인하고 CI가 green일 때만 다음을 실행한다.

1. PR exact head와 CI/review/mergeability를 다시 확인한다.
2. merge 결과가 `MERGED`인지 확인한다.
3. root `develop`을 fast-forward로 `origin/develop`에 동기화한다.
4. 영향을 받은 문서와 targeted test를 다시 검증한다.
5. `git worktree list --porcelain`과 status로 dirty/active worktree를 먼저
   확인한 뒤, 이 작업의 clean worktree와 feature branch만 non-forced 방식으로
   정리한다. root의 `angular.json`, `.superpowers/`, `.workflow-inputs/`와 다른
   worktree는 보존한다.

## DoD

- [ ] 승인된 설계 문서와 실행 계획이 worktree에 존재한다.
- [ ] API 문서, runbook, lesson이 Redis advisory/DB authority와 hold 조건을
  일관되게 설명한다.
- [ ] Kotlin/API/schema/dependency diff가 없거나, 새 diff가 생기면 별도 승인된
  범위로 재분류된다.
- [ ] `git diff --check`가 통과한다.
- [ ] API scheduler/recovery targeted tests가 통과한다.
- [ ] core repository/contention targeted tests가 통과한다.
- [ ] lease duration budget, Redis failure backoff/retry budget, fixed-cardinality
  latency/ownership-loss metric 계약이 문서와 후속 acceptance에 포함된다.
- [ ] six-perspective review에서 P0/P1 blocker가 없다.
- [ ] Issue #311의 보류 comment와 문서 링크가 live read-back된다. 독립 prerequisite
  Issue를 만든 경우에만 duplicate check와 양방향 링크를 추가로 확인한다.
- [ ] Korean Lore commit과 PR body `## DoD Status`가 확인된다.
- [ ] merge 승인 후 CI, merge, develop sync, worktree cleanup이 증거와 함께
  완료된다.

## 중단 조건

- 실제 production runner 또는 token propagation 경로가 발견됨
- 기존 Issue/epic과 후속 Issue scope가 충돌함
- targeted test 또는 Docker/Redis/PostgreSQL 환경 실패 원인이 문서 변경으로
  설명되지 않음
- review에서 DB business authority가 약화되거나 raw identity 노출이 확인됨
- PR exact-head CI/review/mergeability 증거가 stale하거나 불완전함

위 조건에서는 작업을 `BLOCKED` 또는 `PENDING`으로 남기고, 변경 범위를 임의로
확장하지 않는다.

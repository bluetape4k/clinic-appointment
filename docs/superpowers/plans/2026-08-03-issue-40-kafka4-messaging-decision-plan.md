# Issue #40 Kafka4 메시징 결정 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` (recommended)
> or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`)
> syntax for tracking.

**Goal:** 승인된 Kafka4-only 메시징 결정을 ADR과 backlog 문서에 일관되게 반영하고 Issue #40을 production code 변경 없이 완료한다.

**Architecture:** `docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md`를 상세 권위로 유지하고 `docs/requirements/architecture.md`에는 압축된 ADR-13을 추가한다. `TODO.md`와 `docs/requirements/README.md`는 선택 완료와 후속 #41/#42의 미구현 상태를 구분해 표현한다.

**Tech Stack:** Markdown, Git, GitHub CLI, repository documentation checks

---

## 1. 실행 경계와 파일 책임

| 파일 | 책임 | 변경 종류 |
|---|---|---|
| `docs/requirements/architecture.md` | Kafka4-only 선택, outbox/전달/호환성 경계를 ADR-13으로 요약 | 수정 |
| `TODO.md` | #40 결정 완료와 #41/#42 후속 상태를 backlog에 반영 | 수정 |
| `docs/requirements/README.md` | 미구현 메시징 항목의 선택 broker를 Kafka4로 구체화 | 수정 |
| `docs/review/2026-08-03-issue-40-kafka4-messaging-decision-plan-review.md` | Step 3-R 여섯 관점과 main integration 증거 | 생성 |

다음 파일과 영역은 변경하지 않는다.

- 모든 `*.kt`, `*.kts`, `*.sql`, `application*.yml`
- `settings.gradle.kts`, version catalog, module dependency
- `appointment-messaging` module과 Kafka producer/consumer runtime
- #41/#42의 GitHub issue body
- user-scope Codex/OMX 설정과 `~/.codex/agents`

## 2. Spec 수용 기준 추적

| Spec 수용 기준 | 실행 task | 검증 |
|---|---|---|
| Kafka4만 지원 | Task 1, Task 2 | ADR/TODO/requirements 검색 결과 |
| `bluetape4k-kafka4` + Spring Kafka 4 + Jackson 3 + governed catalog 권위 | Task 1 | ADR exact phrase 검사 |
| DB outbox authority, 전역 exactly-once 금지 | Task 1 | ADR boundary 검사 |
| partition/envelope/at-least-once/idempotency | Task 1 | ADR contract 검사 |
| failure/security/observability/replay/rollback | Task 1 | ADR operational summary 검사 |
| #41/#42 성능·복구 수치와 재현 명령 차단 gate | Pre-execution gate, Task 1 | ADR 후속 검증 gate와 3-R 추적 |
| relay fencing/backpressure와 broker 입력 상한 | Pre-execution gate, Task 1 | ADR 후속 책임과 exact spec link 검사 |
| #41 producer와 #42 consumer/schema 책임 분리 | Task 1, Task 2 | 세 문서의 issue link와 문구 검사 |
| Kafka3/RabbitMQ/broker-neutral 기각 | Task 1 | ADR rejected alternatives 검사 |
| production source 변경 없음 | Task 3 | changed-path allowlist 검사 |
| Step 2-R P0=0/P1=0 | 완료됨 | spec review artifact와 commit `e9041d1` |

## Pre-execution gate: Step 3-R plan review

**Dependencies:** 이 plan self-review PASS

**Write scope:** `docs/review/2026-08-03-issue-40-kafka4-messaging-decision-plan-review.md`, 필요 시 이 plan

- [ ] **Gate 1: 여섯 독립 plan 관점을 실행한다**

Performance, stability, security, operator/Ops, developer/API, user/caller가 exact plan을
읽기 전용으로 검토한다. 각 lane은 P0/P1/P2/P3, exact evidence와 required edit를 반환한다.

Expected: main session이 결과를 통합하고 P0/P1을 plan에 수정한다. P2/P3는 수정하거나
근거를 적어 후속 이슈에 연결한다.

- [ ] **Gate 2: spec-to-task와 실행 순서를 검증한다**

다음을 모두 확인한다.

- spec의 12개 수용 기준마다 task와 command가 있다.
- Task 2는 ADR 결정이 존재한 뒤 실행된다.
- Task 3 검증은 모든 repository mutation 뒤 실행된다.
- production code, module, dependency, migration 구현 task가 없다.
- public README/KDoc/diagram/CHANGELOG는 behavior 변경이 없어 N/A다.
- user-scope OMX model drift는 별도 운영면이며 repository 변경에 포함되지 않는다.

- [ ] **Gate 3: 3-R 증적 문서를 작성하고 재검토한다**

Expected: review artifact가 초기 finding, 수정, 최종 여섯 관점, main integration과
`P0=0`, `P1=0`을 기록한다. blocker가 남으면 Task 1을 시작하지 않는다.

- [ ] **Gate 4: 승인된 spec, plan과 2-R/3-R 증적을 Lore commit으로 고정한다**

Expected: plan과 plan-review artifact가 commit되고 exact local SHA를 기록한다. 이후
Task 1의 문서 구현은 이 commit을 parent로 사용한다.

## Task 1: ADR-13에 Kafka4-only 메시징 권위 기록

**Complexity:** Medium

**Dependencies:** 승인된 spec commit `e9041d1`, Step 3-R PASS, Gate 4 plan commit

**Write scope:** `docs/requirements/architecture.md`

**Files:**

- Modify: `docs/requirements/architecture.md`
- Reference: `docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md`

- [ ] **Step 1: 기존 ADR 번호와 문서 끝을 확인한다**

Run:

```bash
rg -n '^### ADR-' docs/requirements/architecture.md
tail -80 docs/requirements/architecture.md
```

Expected: 마지막 번호가 `ADR-12`이며 추가할 위치가 문서 끝임을 확인한다.

- [ ] **Step 2: 다음 의미를 가진 ADR-13을 추가한다**

ADR-13은 다음 구조와 계약을 그대로 포함한다.

```markdown
### ADR-13: 외부 메시징 — Kafka4 전용 outbox relay

**결정**: 외부 broker 기반 메시징은 `bluetape4k-kafka4`, Spring Kafka 4,
Jackson 3 조합만 지원한다. DB가 aggregate와 outbox의 transaction authority이며,
별도 relay가 commit된 outbox를 Kafka4에 발행한다. 버전은 bluetape4k governed catalog를
따르며 clinic-appointment가 Kafka client, Spring Kafka 또는 Jackson 버전을 독립적으로
override하지 않는다.

**전달 계약**:

- end-to-end at-least-once와 stable event ID 기반 producer/consumer 멱등성을 사용한다.
- aggregate scope를 partition key로 사용해 같은 aggregate의 순서를 보존한다.
- partition 증설은 단일 hot aggregate 해결책이 아니며, 기존 key remap에 대비한 producer
  pause/relay hold, drain/checkpoint 또는 새 topic migration과 ordering 증명 없이 실행하지 않는다.
- envelope는 `eventId`, `eventType`, `schemaVersion`, UTC `occurredAt`,
  tenant/clinic/aggregate scope, correlation/causation ID와 bounded payload를 가진다.
- DB와 Kafka를 하나의 전역 exactly-once transaction으로 표현하지 않는다.
- unsafe typing, FQN type header, 기본 tombstone/null payload와 raw PHI DLT 복제를 금지한다.

**보안·운영 gate**:

- broker credential을 저장소에 커밋하지 않고 producer/consumer principal을 필요한
  topic/action과 application scope로 제한한다.
- patient/PII 식별자를 key, metric label, log 또는 raw payload 출력에 넣지 않는다.
- replay는 별도 group, 승인된 scope/offset, dry-run과 audit을 요구하며 운영 group
  offset을 되감지 않는다. rollback도 offset rewind나 topic 삭제로 event를 숨기지 않는다.
- application topic auto-create를 금지하고 startup/readiness에서 authn/authz, topic/config,
  serializer/envelope 호환성을 확인한다.

**후속 검증 gate**: #41/#42는 구현 전에 burst와 지속 부하, publish-to-ack p95/p99,
consumer lag catch-up, oldest-age, broker outage recovery, partition skew, heap/thread 상한과
재현 명령을 수치화한다. relay lease/fencing·bounded backpressure와 record/header/depth
상한, partition-change ordering migration도 해당 spec과 테스트의 차단 기준이다.

**후속 책임**:

| 이슈 | 책임 |
|---|---|
| #41 | `appointment-messaging`, producer envelope/partition key, 세 dialect outbox lease/fencing migration, bounded relay와 readiness |
| #42 | consumer idempotency/offset, Schema Registry compatibility, retry/DLT/quarantine와 승인된 replay |

**기각**: Kafka3는 Spring Boot 3/Jackson 2 line이라 기각한다. RabbitMQ는 replay와
schema evolution 요구 및 bluetape4k runtime 지원이 약해 기각한다. broker-neutral
abstraction은 Kafka partition/offset/replay 의미를 숨기는 YAGNI이므로 도입하지 않는다.

**근거**: 상세 failure mode, 보안·운영 계약과 검증 gate는
`docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md`를 따른다.
```

Expected: ADR은 상세 spec을 중복하지 않으면서 runtime, 권위, 전달 의미, 후속 책임과
기각 대안을 모두 보존한다.

- [ ] **Step 3: ADR의 핵심 계약을 정적으로 확인한다**

Run:

```bash
rg -n 'ADR-13|bluetape4k-kafka4|governed catalog|override|at-least-once|schemaVersion|exactly-once|#41|#42|Kafka3|RabbitMQ|broker-neutral|credential|principal|PII|PHI|metric label|replay|dry-run|offset|auto-create|p95|p99|consumer lag|oldest-age|partition skew|heap/thread|재현 명령|partition 증설|key remap|drain/checkpoint|ordering 증명' docs/requirements/architecture.md
```

Expected: 모든 계약이 ADR-13 안에서 한 번 이상 확인되고 Kafka3/RabbitMQ를 지원한다고
해석할 표현이 없다.

**Rollback point:** 이 task가 실패하면 ADR-13 hunk만 되돌리고 Task 2를 시작하지 않는다.

## Task 2: Backlog 문서를 선택 완료 상태와 맞춘다

**Complexity:** Small

**Dependencies:** Task 1 PASS

**Write scope:** `TODO.md`, `docs/requirements/README.md`

**Files:**

- Modify: `TODO.md`
- Modify: `docs/requirements/README.md`

- [ ] **Step 1: 기존 broker 표현을 확인한다**

Run:

```bash
rg -n 'Kafka|RabbitMQ|메시지 큐|appointment-messaging|#40|#41|#42' TODO.md docs/requirements/README.md
```

Expected: `TODO.md`는 #40을 미결정으로 표시하고 requirements README는
`Kafka/RabbitMQ`를 미구현 메시징 이름으로 사용한다.

- [ ] **Step 2: TODO의 메시징 설명과 #40 상태를 결정에 맞춘다**

섹션 설명과 #40 행을 다음 의미로 교체한다.

```markdown
현재 Spring `ApplicationEvent`로 동기 처리 중. 대용량/외부 시스템 연동은 DB outbox와
Kafka4 비동기 메시징으로 확장한다.
```

```markdown
- ✅ Kafka4 도입 결정 (`bluetape4k-kafka4`, Spring Kafka 4, Jackson 3) — [#40](https://github.com/bluetape4k/clinic-appointment/issues/40)
```

#41, #42와 실제 producer/consumer 항목은 `⬜` 상태를 유지한다. 선택 완료를 구현 완료로
확대하지 않는다. schema backlog는 `이벤트 schema/version compatibility와 Schema
Registry 정책`으로 바꿔 Avro를 이미 선택한 것처럼 표현하지 않는다.

- [ ] **Step 3: requirements README의 미구현 항목을 Kafka4로 구체화한다**

다음 의미로 해당 table row를 교체한다.

```markdown
| **메시지 큐 (Kafka4 비동기)** | `appointment-messaging` (신규) | LOW | broker 결정 #40 완료, 구현 #41/#42 — TODO 섹션 9.3 |
```

Expected: README는 messaging implementation이 여전히 미구현임을 명확히 유지한다.

- [ ] **Step 4: 변경한 backlog 문서의 갱신일을 맞춘다**

`TODO.md`의 `최종 점검일`과 `docs/requirements/README.md`의 `최종 갱신` 날짜를
`2026-08-03`으로 바꾼다. requirements README의 기존 버전 표시는 그대로 보존한다.

- [ ] **Step 5: 세 문서의 상태 일관성을 확인한다**

Run:

```bash
rg -n 'Kafka4|Kafka3|RabbitMQ|#40|#41|#42' docs/requirements/architecture.md TODO.md docs/requirements/README.md
```

Expected: Kafka4 선택은 완료, #41/#42 구현은 미완료, Kafka3/RabbitMQ는 ADR의 기각
대안으로만 나타나며 backlog 날짜가 `2026-08-03`으로 일치한다.

**Rollback point:** 상태가 모순되면 두 backlog 파일만 Task 2 시작점으로 되돌리고 ADR은
보존한 채 문구를 다시 맞춘다.

## Task 3: 문서 범위와 품질을 검증한다

**Complexity:** Small

**Dependencies:** Task 1, Task 2 PASS

**Write scope:** 없음

- [ ] **Step 1: 허용된 변경 경로만 존재하는지 확인한다**

Run:

```bash
issue40_changed_paths="$(
  git diff --name-only origin/develop &&
  git ls-files --others --exclude-standard
)" || exit 1
printf '%s\n' "$issue40_changed_paths" | sort -u
test -n "$issue40_changed_paths"
issue40_allowed_path_regex='^(TODO\.md|docs/requirements/(README\.md|architecture\.md)|docs/review/2026-08-03-issue-40-kafka4-messaging-decision-(spec-review|plan-review|final-review)\.md|docs/superpowers/(specs/2026-08-03-issue-40-kafka4-messaging-decision-design|plans/2026-08-03-issue-40-kafka4-messaging-decision-plan)\.md|docs/lessons/2026-08-03-issue-40-kafka4-messaging-decision\.md)$'
if printf '%s\n' "$issue40_changed_paths" | rg -v "$issue40_allowed_path_regex"; then
  exit 1
else
  test "$?" -eq 1
fi
git status --short
```

Expected: exact allowlist의 spec/plan/review/lesson과 Task 1/2 문서만 존재한다. 다른
Markdown, Java/Kotlin, Gradle, SQL, YAML, XML, properties 또는 module path는 모두 실패한다.

- [ ] **Step 2: Markdown whitespace와 placeholder를 검사한다**

Run:

```bash
git diff --check origin/develop
if rg -n 'T[B]D|FIX[M]E|implement la[t]er|미[정]|추후 결[정]' \
  docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md \
  docs/superpowers/plans/2026-08-03-issue-40-kafka4-messaging-decision-plan.md \
  docs/review/2026-08-03-issue-40-kafka4-messaging-decision-spec-review.md \
  docs/review/2026-08-03-issue-40-kafka4-messaging-decision-plan-review.md \
  docs/requirements/architecture.md TODO.md docs/requirements/README.md; then
  exit 1
else
  test "$?" -eq 1
fi
```

Expected: `git diff --check origin/develop`는 commit된 변경까지 포함해 출력 없이 성공하고
불완전 placeholder가 없다.

- [ ] **Step 3: 문서 링크와 GitHub issue를 확인한다**

Run:

```bash
test -f docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md
gh issue view 40 --json state,url,title
gh issue view 41 --json state,url,title
gh issue view 42 --json state,url,title
```

Expected: spec path가 존재하고 #40/#41/#42 링크가 live issue를 가리킨다.

- [ ] **Step 4: production build N/A를 증명한다**

Run:

```bash
issue40_changed_paths="$(
  git diff --name-only origin/develop &&
  git ls-files --others --exclude-standard
)" || exit 1
test -n "$issue40_changed_paths"
```

Expected: Step 1의 exact allowlist가 모든 비허용 경로를 차단했다. production source/config/
migration 파일이 없으므로 Gradle test/build, Testcontainers, Kotlin pattern 검사는 N/A다.
baseline `./gradlew help --no-daemon --no-configuration-cache` PASS는 worktree 생성 때 이미
확인했다.

## Task 4: 최종 diff의 pre-PR proof를 만들고 커밋한다

**Complexity:** Small

**Dependencies:** Task 1, Task 2, Task 3 PASS

**Write scope:** Task 1/2 문서 세 파일,
`docs/review/2026-08-03-issue-40-kafka4-messaging-decision-final-review.md`,
`docs/lessons/2026-08-03-issue-40-kafka4-messaging-decision.md`

- [ ] **Step 1: Task 3 검증을 exact final diff에서 다시 실행한다**

Expected: changed-path allowlist, whitespace, placeholder, live issue link가 모두 PASS다.

- [ ] **Step 2: final six-lens 문서 review와 lesson gate를 수행한다**

Expected: 최종 diff가 P0=0/P1=0이다. 새로운 failure/recovery/operational learning은 이미
spec과 2-R artifact에 보존되므로 별도 lesson은 N/A 후보이며, 최종 diff를 읽은 뒤 네
absence category를 근거로 판정한다.

- [ ] **Step 3: 생성된 final review와 lesson을 포함해 exact diff를 다시 검사한다**

Run:

```bash
test -f docs/review/2026-08-03-issue-40-kafka4-messaging-decision-final-review.md
test -f docs/lessons/2026-08-03-issue-40-kafka4-messaging-decision.md
git add \
  TODO.md \
  docs/requirements/README.md \
  docs/requirements/architecture.md \
  docs/review/2026-08-03-issue-40-kafka4-messaging-decision-spec-review.md \
  docs/review/2026-08-03-issue-40-kafka4-messaging-decision-plan-review.md \
  docs/review/2026-08-03-issue-40-kafka4-messaging-decision-final-review.md \
  docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md \
  docs/superpowers/plans/2026-08-03-issue-40-kafka4-messaging-decision-plan.md \
  docs/lessons/2026-08-03-issue-40-kafka4-messaging-decision.md
git diff --cached --check
git diff --check origin/develop
if rg -n 'T[B]D|FIX[M]E|implement la[t]er|미[정]|추후 결[정]' \
  docs/review/2026-08-03-issue-40-kafka4-messaging-decision-final-review.md \
  docs/lessons/2026-08-03-issue-40-kafka4-messaging-decision.md; then
  exit 1
else
  test "$?" -eq 1
fi
```

Expected: 두 artifact가 존재하며 untracked였던 파일까지 stage된 exact diff의 whitespace,
branch 전체 whitespace와 placeholder 검사가 모두 PASS다.

- [ ] **Step 4: Lore commit을 만든다**

Commit intent:

```text
Keep messaging follow-up work on one Kafka4 contract
```

Expected: Constraint, Rejected, Confidence, Scope-risk, Directive, Tested, Not-tested trailers를
포함하고 exact local head SHA를 기록한다.

## Task 5: PR, CI, merge-ready와 승인 후 closeout

**Complexity:** Medium

**Dependencies:** Task 4 PASS, P0=0/P1=0

**External side effects:** branch push, PR create/update, merge, remote branch cleanup

- [ ] **Step 1: branch를 push하고 exact remote head를 확인한다**

Run:

```bash
git push -u origin feat/issue-40-messaging-decision
git rev-parse HEAD
git ls-remote --heads origin feat/issue-40-messaging-decision
```

Expected: local과 remote head SHA가 같다.

- [ ] **Step 2: English PR을 만들고 live metadata를 확인한다**

PR은 `bluetape4k/clinic-appointment`, base `develop`, head
`feat/issue-40-messaging-decision`를 사용한다. assignee `debop`, Issue #40의
`enhancement` label을 맞추며 body에 `Closes #40`을 포함하고 마지막 `##` section을
`## DoD Status`로 둔다.

Expected: live PR body, assignee, label, base/head와 linked Issue #40이 정확하다.

- [ ] **Step 3: exact-head CI와 review를 통과한다**

Run:

```bash
gh pr checks <PR_NUMBER> --watch
gh pr view <PR_NUMBER> --json headRefOid,statusCheckRollup,reviews,reviewDecision,mergeStateStatus
```

Expected: required CI가 성공하고 unresolved blocker와 P0/P1이 없다.

- [ ] **Step 4: merge-ready report 뒤 exact action set의 fresh approval을 기다린다**

Expected: exact PR/head, merge method, CI, review, lesson, checklist count와 함께 root
`develop` sync, 이 worktree path 제거, local feature branch 삭제, remote feature branch
삭제 의도를 사용자에게 보고한다. 이 보고 뒤 해당 전체 action set에 대한 fresh
approval만 CG-16과 cleanup 권한을 충족한다.

- [ ] **Step 5: 승인된 exact PR head를 merge하고 live 상태를 확인한다**

Expected: 승인된 merge method로 PR이 `MERGED`이고 merge SHA가 확인되며 Issue #40이
`CLOSED`다. auto-merge는 사용하지 않는다.

- [ ] **Step 6: root checkout을 merge SHA로 동기화한다**

Expected: root checkout의 기존 dirty state가 없거나 안전하게 보존되고 `develop`과
`origin/develop`이 merge SHA에서 일치한다.

- [ ] **Step 7: 승인 범위의 Issue #40 worktree와 branch만 정리한다**

Expected: fresh approval에 열거된
`/Users/debop/work/bluetape4k/clinic-appointment/.worktrees/issue-40-messaging-decision`,
local `feat/issue-40-messaging-decision`, remote 동일 branch만 대상으로 삼는다. merge 및
clean 상태를 다시 읽은 뒤 제거하고 `git worktree list`, local/remote branch 조회로
post-action 상태를 확인한다. 다른 worktree나 branch는 삭제하지 않는다.

## 3. Risk prediction

| 위험 | Signal | 완화 | Rollback/rerun |
|---|---|---|---|
| 선택 완료를 구현 완료로 오표기 | #41/#42 또는 messaging row가 완료로 읽힘 | #40만 완료 표시, #41/#42는 명시적 미구현 | Task 2 두 파일 rollback 후 상태 검색 재실행 |
| ADR이 상세 spec과 drift | Kafka4/version/outbox/delivery 문구 불일치 | ADR은 압축 요약, 상세 권위 링크 고정 | ADR hunk rollback 후 spec traceability 재검토 |
| production 파일이 섞임 | changed-path allowlist에 source/config 등장 | Task 3에서 fail-closed | 허용 문서만 보존하고 관련 task 전체 재검증 |
| subagent model 설정 drift | live agent TOML, OMX mapping과 `omx doctor` 결과 불일치 | 검토 때 read-only로 실제 model surface를 확인하고 review artifact에 공개 | repository를 수정하지 않고 user-scope 운영 작업으로 분리 |
| PR metadata drift | live body/assignee/label/head 불일치 | PR 생성 직후와 CI 후 재조회 | live metadata 수정 후 gate 재실행 |

## 4. 계획 self-review

- Spec coverage: 12개 수용 기준 모두 Task 1~4와 concrete command에 매핑됨
- Placeholder scan: 금지된 미완성 표식 없음
- Type consistency: `schemaVersion`, Kafka4-only, #41 producer/#42 consumer 책임이 spec과 일치
- Ordering: spec 승인/2-R -> plan/3-R -> ADR/backlog -> validation/review -> PR/CI -> fresh merge approval
- Conditional N/A: Kotlin/TDD/Gradle/Testcontainers는 changed-path allowlist로 production 변경이 없을 때만 N/A

### Step 3 plan draft DoD: PASS

계획은 Issue #40의 문서 결정만 구현하며 후속 runtime 작업을 #41/#42로 남긴다. Step 3-R
여섯 관점이 P0=0/P1=0으로 수렴하고 plan commit이 만들어지기 전에는 Task 1 문서 변경을
시작하지 않는다.

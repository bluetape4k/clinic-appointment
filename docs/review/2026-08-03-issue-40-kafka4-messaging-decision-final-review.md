# Issue #40 Kafka4 메시징 결정 최종 검토

## 검토 범위

- 기준: `origin/develop`부터 현재 Issue #40 branch diff 전체
- 대상: Kafka4 spec/plan, ADR-13, TODO, requirements README, 2-R/3-R 증적과 lesson
- 변경 경로: exact allowlist의 Markdown 9개
- 비범위: Kotlin, Gradle, SQL, YAML, runtime/module 구현

여섯 독립 관점은 같은 exact diff를 읽기 전용으로 검토했다. main session은 finding을
정규화하고 P0/P1을 수정한 뒤 영향받은 관점을 재실행했다.

## 모델과 guidance 증거

`~/.codex/agents/*.toml`은 현재 GPT-5.6 계열을 사용한다. 별도 Codex 프로세스가 같은
worktree에서 leader/verifier `gpt-5.6-luna`, autonomy directive와 변경된
`~/.codex/hooks.json`, `~/.codex/hooks/test_native_subagent_watchdog.py`를 읽었다.
`omx doctor`는 18 PASS, warning/failure 0건이었다.

## 관점별 수렴

| 관점 | 초기 판정 | 수정 또는 근거 | 최신 판정 |
|---|---:|---|---:|
| Performance | P1=1 | partition 증설의 same-key remap을 명시하고 pause/hold, drain/checkpoint 또는 topic migration, offset 전환과 ordering 증명을 차단 gate로 추가 | PASS, P0=0/P1=0 |
| Stability | P1=2, 재검토 P1=1 | extension blacklist를 exact allowlist로 교체하고 staged/branch whitespace 검사와 artifact 생성 순서를 수정 | PASS, P0=0/P1=0 |
| Security | PASS | least privilege, secret, bounded input, PHI, replay/offset/rollback 경계 확인 | PASS, P0=0/P1=0 |
| Operator/Ops | PASS | readiness/runbook, exact-head closeout, no-match/경로 검증 실행 가능성 확인 | PASS, P0=0/P1=0 |
| Developer/API | P1=1 | partition을 dedup unique key에서 제거하고 stable logical consumer/stream identity와 eventId를 사용하며 topic/partition/offset은 provenance로 분리 | PASS, P0=0/P1=0 |
| User/caller | PASS | #40 결정 완료와 #41/#42 구현 미완료, 날짜·링크·RabbitMQ/Avro 모순 부재 확인 | PASS, P0=0/P1=0 |

## Main-session 통합

| 항목 | 판정 | 근거 |
|---|---|---|
| 결정 일관성 | PASS | Kafka4-only, governed catalog, DB outbox authority와 전역 exactly-once 금지가 spec/ADR에서 일치한다. |
| ordering 안전성 | PASS | partition/key 변경은 irreversible migration이며 ordering proof 없이는 실행하지 않는다. |
| cross-partition 멱등성 | PASS | dedup identity는 partition과 분리되어 republish, partition 증설, topic migration에서도 동일 event side effect를 차단한다. |
| 범위 통제 | PASS | anchored exact allowlist가 허용되지 않은 Java fixture를 검출한다. |
| 검증 무결성 | PASS | `git diff --check origin/develop`가 commit된 변경을 포함하고 negative `rg`의 exit 상태를 구분한다. |
| 후속 책임 | PASS | #41은 module/producer/relay, #42는 consumer/schema/DLT/replay를 소유한다. |
| lesson | PASS | shell false-PASS와 partition remap 교훈을 `docs/lessons/2026-08-03-issue-40-kafka4-messaging-decision.md`에 보존했다. |

## 현재 집계

| Priority | 수량 |
|---|---:|
| P0 | 0 |
| P1 | 0 |
| P2 | 0 |
| P3 | 0 |

### Step 6-R verdict: PASS

최종 9개 경로는 anchored allowlist를 통과하고 simulated Java fixture를 거부했다.
`git diff --cached --check`, `git diff --check origin/develop`와 9개 파일 placeholder 검사가
모두 PASS이며 여섯 관점과 main integration의 P0/P1 blocker가 없다.

# Issue #40 Kafka4 메시징 결정 명세 2-R 검토

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-03-issue-40-kafka4-messaging-decision-design.md`
- 기준 branch: `origin/develop` (`5ed18733adac4e57090267b485766644709f3324`)
- 승인된 결정: Kafka4만 지원하며 Kafka3, RabbitMQ, broker-neutral abstraction은 제외
- 변경 범위: 설계 문서만 검토하며 Kotlin, Gradle, module, Flyway, runtime 설정은 변경하지 않음

공식 Kafka/Spring Kafka/Schema Registry 문서와 로컬
`bluetape4k-projects/infra/kafka4` 지원을 근거로 exact spec을 여섯 독립 관점에서
검토했다. main session은 finding의 중복과 심각도를 정규화하고 모든 P0/P1 수정 뒤
영향받은 관점을 다시 실행했다.

## 검토 실행 제약

`~/.codex/.omx-config.json`은 review role을 `gpt-5.6-luna`로 지정하지만, 현재 설치된
`~/.codex/agents/*.toml`과 native-agent runtime metadata는 `gpt-5.5`로 남아 있었다.
`omx doctor`는 이를 `stale install; run omx setup --force`로 진단했다. 이번 검토는
워크플로가 요구한 canonical role(`code-reviewer`, `verifier`, `writer`)과 독립 lens를
사용했지만, 실행 모델을 5.6으로 확인할 수 없었다. 이 모델 설정 drift는 제품 spec의
finding과 분리해 공개하며 user-scope 설정을 이 저장소에서 임의로 수정하지 않았다.

## 초기 finding과 수정

| 관점 | 초기 결과 | 반영한 수정 | 최종 결과 |
|---|---:|---|---:|
| Performance | P1=2, P2=2 | bounded relay/backpressure, hot-partition 정책, 입력 크기·codec guard, 수치화된 성능 gate | P0=0, P1=0 |
| Stability | P1=1, P2=2, P3=1 | owner-token lease/fencing, stale claim, rollback·replay·retention 계약 | P0=0, P1=0 |
| Security | P1=1 | record/header/depth/identifier 상한, fail-closed mapping, bounded quarantine | P0=0, P1=0 |
| Operator/Ops | P1=2, P2=3 | readiness/health, config ownership, dashboard/alert, replay·topic migration runbook | P0=0, P1=0 |
| Developer/API | P1=2, P2=3, P3=1 | #41 producer codec와 migration 책임, tenant key form, `schemaVersion`, metric cardinality, whitespace | P0=0, P1=0 |
| User/caller | P1=1, P2=3, P3=1 | field ownership, positive/negative example, consumer protocol, replay side-effect mode | P0=0, P1=0 |

## 최종 여섯 관점 판정

| 관점 | 판정 | 최종 근거 |
|---|---|---|
| Performance | PASS | #41/#42가 burst, p95/p99, lag catch-up, backlog age, outage recovery, skew, heap/thread와 serializer smoke를 구현 전에 수치화한다. |
| Stability | PASS | relay claim은 owner token과 DB-time lease로 fenced되며 crash/cancellation 뒤 stale claim만 회수한다. |
| Security | PASS | unsafe typing/FQN header/null payload를 금지하고 모든 broker 입력을 bounded fail-closed 경계에서 검사한다. |
| Operator/Ops | PASS | auto-create 금지, config drift/readiness, dashboard/alert owner, replay와 irreversible partition change runbook이 정의됐다. |
| Developer/API | PASS | 기존 nullable clinic scope와 `schemaVersion` 계약을 보존하며 #41/#42 writer/reader 책임과 세 dialect migration 소유권이 명확하다. |
| User/caller | PASS | envelope 생성 권위, caller 예제, dedup ledger에서 offset commit까지의 처리 순서와 replay mode가 명시됐다. |

## Main-session 통합 검토

| 항목 | 판정 | 근거 |
|---|---|---|
| 결정 일관성 | PASS | Kafka4-only와 `bluetape4k-kafka4`/Spring Kafka 4/Jackson 3 line이 전 문서에서 일치한다. |
| DB·broker 권위 | PASS | DB outbox가 transaction authority이며 전역 exactly-once를 주장하지 않는다. |
| 구현 가능성 | PASS | #41이 producer, relay와 migration을 소유하고 #42가 consumer/schema/DLT/replay를 소유한다. |
| 실패·운영 | PASS | claim crash, duplicate, poison event, outage, rollback, replay와 config migration이 검증 가능한 계약이다. |
| 보안·개인정보 | PASS | tenant scope, least privilege, bounded input, PHI-free metric/log/DLT 원칙이 유지된다. |
| 문서 범위 | PASS | Issue #40에는 production code나 dependency 변경이 없고 후속 구현은 #41/#42로 분리된다. |

## 최종 집계

| Priority | 수량 | 처리 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 초기 finding 모두 spec에 반영 |
| P3 | 0 | 초기 finding 모두 spec에 반영 |

### Step 2-R verdict: PASS

exact spec은 Kafka4-only runtime, durable outbox, at-least-once, partition/envelope,
consumer idempotency, 보안·운영 계약과 #41/#42 책임을 구현 가능한 수준으로 고정했다.
P0/P1 blocker가 없으므로 written-spec 사용자 검토 gate로 진행할 수 있다.

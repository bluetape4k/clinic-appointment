# Issue #41 Step 2-R 명세 리뷰 통합 기록

## 검토 범위와 기준

- 대상: 승인된 Issue #41 설계 명세와 구현 계획, 현재 `SchedulingOutboxEvents`, `AppointmentService`, `RescheduleController`, `ClosureRescheduleService`, Flyway harness, Gradle catalog/CI를 대조했다.
- 관점: performance, stability/SRE, security, operator readiness, developer/API usability, caller/user behavior.
- 비교 기준: 승인된 Issue #40 Kafka4 messaging contract와 현재 repository 계약.
- 리뷰 lane: `spec-performance-41`, `spec-stability-41`, `spec-security-41`, `spec-operator-41`, `spec-developer-41`, `spec-user-41` (모두 read-only, 변경 경로 없음).
- 원본 리뷰 결과: P0=0, P1=공통 7건 이상, P2 다수. 원본 결과는 각 lane receipt와 본 run evidence에 보존했다.

## 관점별 결과와 수정

| 관점 | 차단 조건 | 명세/계획 반영 |
|---|---|---|
| 성능 | 정량 수용값 부재, legacy row scan, N+1 claim, broker outage backpressure, lease budget 불변식 | 20,000-row 고정 seed benchmark, p95/p99/oldest-age/lock-wait/heap/thread/skew 기준, discriminator-leading index, atomic batch CAS/SKIP LOCKED, bounded queue·fairness·pause, lease budget inequality를 고정했다. |
| 안정성/SRE | claim race, lineage 누락, readiness/actionability, shutdown/cancellation, mixed-version migration | two-relay race와 DB-clock fencing, correlation/causation fail-fast, broker component readiness, 10초 bounded drain/cancel, stale lease recovery, V21→V22/rollback matrix를 계획에 넣었다. |
| 보안 | writer scope 위조, broker authn/authz·secret·auto-create, unsafe Jackson type/tombstone | 동일 transaction tenant/clinic/replacement proof와 negative test, TLS/SASL/ACL/secret-manager/`allow.auto.create.topics=false`, fixed DTO/no default typing/FQN header/null/duplicate/trailing/size limits, topic/key allow-list와 redaction을 고정했다. |
| 운영 | dashboard/alert/runbook/owner, rollout hold/drain/rollback, real broker drift | `enabled|paused|held`, alert threshold/severity/owner/escalation/rollback 기준, canary·drain·audit, singleton Kafka/Embedded KRaft integration, serializer/metadata/authz/topic self-check를 추가했다. |
| 개발자/API | raw String ID/reason, JVM 호환성, 실제 migration helper/CI/module catalog 누락 | typed context/ID/reason/sealed payload, 명시적 JVM overload/delegation, `AppointmentMessagingMigrationTestSupport`, test resources, CI/Kover/nightly/path filters, root README catalog와 KDoc를 계획했다. |
| caller/user | HTTP correlation 단절, 성공 의미/실패 mapping, partial stream, aggregate ordering, idempotency/privacy | 모든 mutation/reschedule에 typed context 전파, `2xx=durable intent`/Kafka ack 아님, broker outage PENDING, pre-commit stable `503`/`Retry-After`, local listener 격리, legacy-only partial-stream 명시, same-aggregate serialization과 replay/privacy matrix를 고정했다. |

## 경계 결정

현재 `SchedulingOutboxEvents` KDoc은 generic legacy command-driven row의 `causation_event_id=null`을 보존한다. 새 appointment writer의 envelope는 상위 Issue #40 규칙을 따른다: root command는 자체 correlation을 root causation으로 표현하고, non-root command/event 결과만 실제 upstream event ID를 causation으로 기록한다. 따라서 기존 legacy row를 backfill하거나 의미를 재해석하지 않고, V22 writer KDoc와 테스트에서 두 계약을 분리한다.

Issue #41의 stream은 현재 `AppointmentService`와 최종 `RescheduleController` 경로만 포함한다. commitment-v2 controller와 closure의 중간 `PENDING_RESCHEDULE` 전이는 이 issue에서 제외하며, README와 consumer contract에 partial-stream임을 명시한다.

동일 aggregate 순서는 partition key만으로 추정하지 않는다. store가 이전 동일 aggregate row가 terminal이 되기 전 후속 row를 claim하지 않도록 하여 producer ordering을 보장하고, version 기반 stale-event 방어 테스트를 함께 둔다.

## 통합 판정

- P0: 0.
- 명세/계획의 원본 P1: 모두 수정 반영했으며, 수정된 문서 기준의 미해결 P1: 0 (구현 후 fresh verification 필요).
- P2: retention policy, full-stream 확장, consumer dedup/schema registry는 명시적으로 후속 범위 또는 구현 검증 항목으로 남겼다.
- 사용자 승인: Step 2-R 수정본은 2026-08-05 사용자 `승인`으로 재승인되었다.

**Gate: 수정 명세 재승인 완료. Step 3-R 계획 리뷰로 진행한다.**

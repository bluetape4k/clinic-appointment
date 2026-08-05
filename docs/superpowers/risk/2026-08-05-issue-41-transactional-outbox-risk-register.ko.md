# Issue #41 transactional outbox 위험 등록부

| 위험 | 조기 신호 | 완화·검증 | rollback/rerun 지점 |
|---|---|---|---|
| tenant/clinic scope가 섞인 durable event | cross-tenant negative test 실패, mismatch count 증가 | writer 동일 transaction proof, H2/PostgreSQL/MySQL mismatch test, no partial commit | writer feature flag hold; V22 column은 유지하고 row 생성 경로만 이전 구현으로 복귀 |
| 두 relay의 중복 claim/lease churn | overlap event ID, lease-lost 증가, lock-wait p95 초과 | atomic batch CAS/SKIP LOCKED, same-aggregate serialization, two-relay race와 fake-clock boundary test | relay `held`, stale lease recovery 후 재실행 |
| retry backoff/attempt exhaustion 우회 | `next_attempt_at` 미래 row의 재claim, attempt count가 max를 초과, poison row 무한 재시도 | 선택과 CAS UPDATE 양쪽의 due/version predicate, claim 시 attempt 원자 증가, exhaustion `FAILED`, retry-vs-claim race와 bounded retry test | 해당 topic/aggregate hold, poison row를 bounded redrive/failed로 전환 후 lease recovery |
| same aggregate 순서 역전 | 동일 key의 후속 event가 predecessor terminal 전에 전달, version stale 증가 | predecessor non-terminal guard, batch당 aggregate 하나, delayed publisher race와 EXPLAIN/index 검증 | aggregate key hold, pending rows drain 후 순차 redrive |
| broker auth/topic/serializer drift | metadata/ACL/self-check 실패, readiness not-ready | typed fail-fast config, TLS/SASL, minimal ACL, auto-create=false, fixed codec negative tests와 real Kafka integration | writer config invalid이면 startup fail-fast; valid config broker outage만 pause/degraded |
| broker outage가 API/DB pool을 압박 | in-flight 상한 도달, API latency/oldest-age 경보 | maxInFlight=32, clinic fairness=4, 3회 timeout 후 30초 pause, jitter/catch-up rate limit, IO dispatcher 분리 | relay pause/hold, bounded drain 후 lease expiry recovery |
| lease가 send budget보다 짧음 | stale terminal update, duplicate resend, lease churn | startup inequality `lease >= send + retry + terminal DB + safety`, 30/5/10/3/10s acceptance profile | configuration fail-fast; no live lease extension without fresh design review |
| migration/rollback 혼합 버전 불일치 | V21→V22 index/nullable assertion 실패, old writer partial DDL | target-21→22 helper, mixed-version/partial-DDL/rollback matrix, additive-only columns | V22 유지, writer 이전 버전 복귀, relay held와 migration lock/runbook evidence |
| HTTP outcome가 Kafka ack로 오해됨 | broker outage에서 5xx 또는 duplicate row, local listener 5xx | OpenAPI/README outcome table, `2xx=durable intent`, PENDING preservation, stable 503/Retry-After, listener isolation test | API writer 경로 hold; outbox row/eventId는 보존 |
| privacy/lineage 위반 | raw payload/reason/credential가 log/metric/quarantine에 노출 | typed allow-list, payload hash+bounded failure code, untrusted correlation, redaction tests | relay held, evidence scrub/retention 절차 실행; raw payload 재기록 금지 |
| 운영 신호·런북 불일치 | alert trigger/clear가 없거나 owner/escalation 미지정, held/redrive 절차 재현 불가 | `docs/runbooks/appointment-messaging-operations.md`와 alert rules의 수치·clear window·owner·rollback link validator | rollout hold; alert/runbook parity 수정 전에는 relay enable 금지 |

## 재실행 규칙

1. 모든 실패는 먼저 bounded evidence와 stable code로 기록하고 raw payload를 복사하지 않는다.
2. writer/DB migration 실패는 caller transaction을 rollback하고, broker outage는 row를 보존한 채 relay만 pause한다.
3. relay 재실행은 같은 `eventId`와 lease fencing을 사용하며 새 event ID를 생성하지 않는다.
4. benchmark 기준 초과 또는 P0/P1 재발 시 rollout을 중지하고 해당 task의 red test부터 재실행한다.

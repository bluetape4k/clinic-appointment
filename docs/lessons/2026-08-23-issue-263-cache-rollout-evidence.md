# Issue #263 캐시 rollout evidence lesson

## 범위

의존성 1.4.0 캐시 namespace 전환의 운영 유사 검증을 테스트 전용 하네스로 고정했다.
새 production 코드는 변경하지 않고 `PostgreSQLServer.Launcher`, `Containers.Redis`가 위임하는
`Redis88Launcher.redis`(Redis 8.8), `KafkaServer.Launcher` singleton만 사용한다. 실제 운영 자격 증명·트래픽·배포 telemetry는
사용하지 않으므로 report의 `deploymentSloEvidence`는 `false`다.

## 구현 결과

| 영역 | 결과 |
| --- | --- |
| PostgreSQL schema | `postgres:18-alpine`, Flyway migration `30`까지 30개 적용 |
| PostgreSQL lock wait | `pg_locks` blocked-state 확인을 포함한 advisory-lock probe `53.563041 ms` |
| Redis cache | `redis:8.8`, 세 v3 exact-key assertion 9/9 통과, v2 namespace 보존 |
| Cache workload | hit 3, miss 3, decode error 1을 redacted report에 기록 |
| Kafka broker | `confluentinc/cp-kafka:7.5.2`, `lagMetric=committed-end-offset-zero-backlog`, lag `0` record, round-trip `0.44486 s` |
| Rollback | 관찰된 traffic drain·worker restart·v2 warm-up·v3 preservation 모두 `true`, `PASS`, `19.115083 ms` |
| Validator | 새 optional threshold(`rollbackDurationMs`, cache hit/miss/decode error)를 포함한 local 검증 통과 |

## 재현 명령

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.config.CacheRolloutEvidenceIntegrationTest" \
  --no-daemon --no-configuration-cache --rerun-tasks

scripts/verify-cache-rollout-evidence.sh \
  appointment-api/build/reports/cache-rollout/issue-263/production-like-report.json \
  --require-production-like \
  --thresholds docs/benchmarks/issue-263-cache-rollout-evidence/2026-08-23/thresholds.json
```

검증 결과는 `캐시 rollout evidence 검증 통과: local`이다. 실행 report는
[production-like-report.json](../benchmarks/issue-263-cache-rollout-evidence/2026-08-23/production-like-report.json)으로
보존했다.

broker의 `lagSeconds`는 `lagMetric=committed-end-offset-zero-backlog`로 고정한
zero-backlog 지표이며 producer→consumer round-trip 시간이 아니다. round-trip 시간은 별도
`roundTripSeconds`로 기록하고, rollback report의 lifecycle flag는 실제 client 종료·재생성과
각 namespace read-back을 통과한 경우에만 `PASS`가 된다.

`./gradlew :appointment-api:test --no-daemon --no-configuration-cache` 전체 실행은
`840 tests / 837 passing / 3 skipped / 0 failed`로 통과했다. Fory async codegen이
pool slot별로 mixed serializer 상태를 남길 수 있어, 이 test harness는 실제 production
steady-state인 generated serializer를 모든 pool slot에 먼저 설치한 뒤 canary를 실행한다.
그 결과 기존 `NearCacheForyCompatibilityTest`와의 조합도 `5 passing`으로 고정했다.

## 운영 경계

이 report는 Colima에서 실행한 repository-level production-like 증거다. 인증된 production
Redis TLS/ACL, 실제 PostgreSQL·broker SLO, 실제 traffic drain/restart와 rollback 결과를
대체하지 않는다. `--require-live`는 production 환경과 `deploymentSloEvidence=true`가
필요한 별도 gate로 유지한다. v2 namespace는 live observation window가 끝날 때까지
삭제하지 않는다.

## 후속 조치

1. canary 담당자가 production/staging 환경의 동일 필드 report와 승인된 threshold를 별도로 수집한다.
2. live report를 확보하면 `--require-live`로 재검증하고 이 local report와 섞지 않는다.

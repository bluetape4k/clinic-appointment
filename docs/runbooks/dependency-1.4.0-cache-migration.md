# bluetape4k-dependencies 1.4.0 캐시 보안·namespace 전환 runbook

## 목적

현재 binary의 Fory/LZ4 payload는 `v2` namespace에 남겨 rollback reader로 보존한다. 새
binary는 등록 강제·depth/graph memory bound를 가진 codec으로 `v3`에만 읽고 쓴다. Spring
Cache의 논리 이름은 유지하고 Redis remote prefix만 다음처럼 분리한다.

| 논리 cache 이름 | rollback binary prefix | 새 binary prefix |
| --- | --- | --- |
| `clinic-doctors` | `clinic-doctors-v2:*` | `clinic-doctors-v3:*` |
| `clinic-equipments` | `clinic-equipments-v2:*` | `clinic-equipments-v3:*` |
| `clinic-treatment-types` | `clinic-treatment-types-v2:*` | `clinic-treatment-types-v3:*` |

`v2` payload를 새 codec으로 역방향 decode할 수 있다는 가정은 하지 않는다. v2는 전체
rollout과 observation window가 끝날 때까지 삭제하지 않는다. 운영 gate는 다음 validator로
JSON evidence의 형식과 live 조건을 확인한다.

```bash
scripts/verify-cache-rollout-evidence.sh <report.json|-> [--require-live] [--require-production-like] [--thresholds <thresholds.json>]
```

Issue #263의 repository-level production-like 고정 창은 다음 테스트와 validator로
재현한다. 테스트는 `PostgreSQLServer.Launcher`, `Containers.Redis`가 위임하는
`Redis88Launcher.redis`(Redis 8.8), `KafkaServer.Launcher` singleton을 사용하고 report에
운영 자격 증명·raw payload를 남기지 않는다.

```bash
./gradlew :appointment-api:test \
  --tests "io.bluetape4k.clinic.appointment.api.config.CacheRolloutEvidenceIntegrationTest" \
  --no-daemon --no-configuration-cache --rerun-tasks

scripts/verify-cache-rollout-evidence.sh \
  appointment-api/build/reports/cache-rollout/issue-263/production-like-report.json \
  --require-production-like \
  --thresholds appointment-api/src/test/resources/cache/issue-263/production-like-thresholds.json
```

threshold 파일은 PostgreSQL `lockWaitMs`, broker committed/end offset 기반 zero-backlog
`lagSeconds`(`lagMetric=committed-end-offset-zero-backlog`)와
`consumerLagRecords`, rollback `durationMs`의 상한과 cache `hits`·`misses`의 최소값,
`decodeErrors`의 최대값을 검증한다. `evidenceMode=production-like` report는 image,
migration, 실행 순서, cleanup ownership, rollback lifecycle flag, assertion/test count도
필수로 검증한다.
local report는 `evidenceMode=production-like`와 `deploymentSloEvidence=false`를 함께
기록하며, 실제 운영 SLO 증거로 승격하지 않는다.

## 사전 준비

1. 운영 Redis URI가 `rediss://username:password@host:6380` 형식이고 API 설정
   `scheduling.cache.redis.require-tls=true`인지 확인한다. password는 채팅·로그·evidence에
   기록하지 않는다.
2. 각 primary shard에서 다음 prefix를 `SCAN ... COUNT 500`으로 관찰한다.
   `clinic-doctors-v2:*`, `clinic-equipments-v2:*`, `clinic-treatment-types-v2:*`.
   새 배포 전에 v2를 삭제하지 않는다.
3. Redis `INFO stats`의 `keyspace_hits`, `keyspace_misses`, v2 key count와 애플리케이션
   decode-error count를 저장한다.
4. PostgreSQL lock-wait, broker lag, cache hit/miss, rollback 계획을 evidence 생성기에
   연결한다. local benchmark report의 `deploymentSloEvidence=false`는 이 절차의 live
   근거가 아니다.
5. `deploymentSloEvidence=false`인 local/staging 점검은 다음처럼 형식만 확인할 수 있다.

   ```bash
   scripts/verify-cache-rollout-evidence.sh local-report.json
   ```

## Canary와 점진 배포

1. 새 binary 한 pod만 canary로 배포한다. 기존 pod는 v2 writer로 유지한다.
2. canary에서 세 cache의 독립 client read/write round-trip을 수행하고 raw key가
   `clinic-*-v3:<logical-key>`에만 생성되는지 확인한다. v1 및 v2 key를 새 writer가 만들면
   즉시 canary를 drain한다.
3. Fory unknown-class/depth/graph memory decode error가 0인지 확인한다. cache adapter가
   miss로 fallback하더라도 decode error count를 별도 evidence에 기록한다.
4. Redis TLS/ACL 연결, v3 key 증가, hit/miss delta, PostgreSQL lock-wait, broker lag를
   report에 기록한다.
5. production report는 `--require-live`로 검증한다. 이때 production environment,
   `deploymentSloEvidence=true`, Redis TLS/ACL=true, `rollback.result=PASS`가 모두 필요하다.

   ```bash
   scripts/verify-cache-rollout-evidence.sh production-report.json --require-live --thresholds production-thresholds.json
   ```

6. validator와 canary 수치가 승인된 범위이면 새 binary를 점진 배포한다. v2 namespace는
   rollback window 동안 보존한다.

## Rollback

1. 새 binary traffic을 중단하고 readiness가 0이 될 때까지 drain한다. 새 v3 writer가 더
   이상 실행 중이 아닌지 deployment 상태와 로그로 확인한다.
2. rollback binary pod를 모두 재기동해 process-local L1(Caffeine) payload를 비운다. Redis
   key만 지워서는 기존 10분 L1 entry가 무효화되지 않는다.
3. rollback binary를 배포하고 v2 namespace의 exact key만 `SCAN ... COUNT 500`으로 확인한다.
   v2를 삭제하지 말고 warm-up read/write를 수행한다.
4. v3는 즉시 삭제하지 않는다. 조사에 필요한 payload와 decode/error evidence를 보존한다.
5. rollback 결과, pod 재기동, v2 warm-up, Redis TLS/ACL 상태를 report에 기록하고
   `rollback.result=PASS`가 된 뒤에만 복구 완료로 표시한다.
6. rollback 중 v2 payload 오류가 발생하면 traffic을 계속 drain하고 v3/v2를 임의로 섞지
   않는다. 원인을 해결한 뒤 canary 절차를 다시 수행한다.

## 전환 완료 후 namespace 정리

1. 전체 v3 rollout 성공, TTL 최대 1시간, 승인된 observation window가 모두 지날 때까지
   v2를 보존한다.
2. 각 primary shard에서 v2 prefix를 cursor 기반 `SCAN ... COUNT 500`으로 순회한다.
3. 각 cursor 응답의 exact key만 제한된 batch로 나누어 `UNLINK key1 key2 ...`한다.
4. cursor가 `0`이 되고 세 v2 prefix count가 0인지 확인한다.
5. 최종 report에서 v3 hit/miss와 애플리케이션 오류율을 확인한다. live SLO 값이나 실제
   rollback 결과가 없으면 구현이 통과했어도 운영 상태는 `PENDING`이다.

## 안전 규칙

- `KEYS`, `FLUSHALL`, `FLUSHDB`를 사용하지 않는다.
- glob pattern을 `DEL` 또는 `UNLINK` 인자로 직접 전달하지 않는다.
- cache name에 tenant별 동적 suffix나 `:`를 추가하지 않는다.
- rollback 과정에서 database schema-down이나 무관한 cache namespace 삭제를 수행하지 않는다.
- Redis password, JWT, raw payload를 로그·README·evidence에 기록하지 않는다.
- `deploymentSloEvidence=false`인 local benchmark를 production SLO로 표현하지 않는다.

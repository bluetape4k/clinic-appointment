# Issue #369 Redis admission benchmark lesson

## 배경

notification outbox admission coordinator는 전역 cap과 clinic별 cap을 Redis key로
조정한다. Issue #369는 clinic cardinality와 ID churn이 admission queue, backpressure,
lease recovery에 미치는 영향을 수치로 확인하되 production semaphore 의미론은 건드리지
않는 characterization 작업이다.

## 결정

- Redis image는 기존 테스트 계약인 `redis:8.8`로 고정했다.
- internal coordinator 타입을 직접 호출해야 하므로 공용 `kotlinx-benchmark`가 아닌
  `appointment-notification` test source의 JavaExec harness를 사용했다.
- 전역 cap 8, clinic별 cap 2, cardinality 10/100/1,000, churn 0%/50%/100%,
  cold/warm 조합을 고정해 재현 가능한 18개 시나리오를 만들었다.
- warmup 시간과 workload 시간을 분리하고, summary percentile은
  `maxScenarioPercentile`로 보수적으로 집계했다.
- p99 local target은 250ms이며, 결과가 배포 SLO 증거로 오해되지 않도록
  `deploymentSloEvidence=false`를 유지했다.

## 구현 결과

- `RedisNotificationAdmissionBenchmark.kt`가 두 Redis coordinator의 admission,
  queueing, acquire, reconcile, renew latency와 key cardinality를 JSON으로 남긴다.
- `redisAdmissionBenchmarkSmoke`와 `redisAdmissionBenchmark` Gradle task가 각각
  PR smoke와 전체 18개 시나리오를 제공한다.
- `validate-redis-notification-admission-benchmark.mjs`가 schema, percentile 순서,
  operation 합계, warmup/workload 시간, recovery 상태, p99 target을 검증한다.
- production coordinator와 semaphore/fencing 설정은 변경하지 않았다.

## 검증 결과

full report의 worst-scenario aggregate는 admission p50/p95/p99
`7.104/137.150/138.923ms`였고 p99 target `250ms`를 통과했다. lease recovery는
`reacquired`였으며 재획득 latency는 `2.441ms`였다. smoke 3회 반복도 p99
`136.900–139.070ms`로 모두 `within-target`이었다.

반면 warm cardinality `1,000`은 준비 시간이 `1.588–1.701s`로 증가했고 Redis key가
churn 0%/50%/100%에서 `5,010/5,210/5,410`까지 남았다. 이는 production bug나
leak의 증명이 아니라 TTL, idle eviction, cleanup 책임을 후속 검증할 구조 신호다.

재현 명령과 18개 시나리오 표는 [Issue #369 benchmark analysis](../benchmarks/issue-369-redis-admission-benchmark/analysis.ko.md)에
기록했다.

## 다음 작업 경계

warm cardinality key lifecycle과 준비 비용만 좁은 후속 이슈로 분리한다. 장기 실행
retention과 local threshold를 확인하기 전에는 production semaphore/fencing,
rollout, 운영 SLO 판정을 변경하지 않는다.

## 문서 검수

| 항목 | 결과 |
|---|---|
| 문제·범위·독자 | PASS |
| 결정·제외 범위 | PASS |
| raw JSON·명령 연결 | PASS |
| 검증·위험·다음 경계 | PASS |

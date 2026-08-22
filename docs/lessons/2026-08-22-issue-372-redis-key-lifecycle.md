# Issue #372 Redis key lifecycle 측정 교훈

## 관측

전용 lifecycle harness는 Redis `8.8`에서 `workload-end`, `long-run`,
`after-coordinator-close`, `after-retention-window` 네 단계를 같은 namespace에서
반복 snapshot했다. warm cardinality `1,000`의 churn `0/50/100%`에서 key count는
각각 `5,005/5,205/5,405`로 증가했지만, 2개 long-run round와 2,500ms retention
창에서 stage 간 변화는 없었다. 모든 관측 key의 `PTTL`은 `-1`이었다.

## 교훈

- in-process `MAX_IDLE_ENTRIES=256`과 Redis prefix retention은 다른 책임이다.
- client/coordinator close가 Redis metadata 삭제를 의미하지 않으므로, normal shutdown
  retention과 long-run cleanup을 별도 stage로 측정해야 한다.
- expirable cleanup이 lazy/bounded라면 “기다렸는데 key가 안 줄었다”는 사실만으로 leak을
  단정할 수 없다. 후속 실험은 실제 command churn, 활성 lease, memory/DBSIZE를 포함해야
  한다.
- benchmark의 절대 key count는 harness가 생성한 semaphore 종류에 따라 달라진다. 기존
  report와 비교할 때는 source path와 생성 primitive를 먼저 고정해야 한다.

## 적용 규칙

다음 Redis lifecycle 이슈에서는 운영 코드 변경 전에 네 stage snapshot, PTTL bucket,
key kind 합계, normal shutdown과 retention window의 분리를 raw JSON과 chart에 함께
남긴다. `deploymentSloEvidence=false`인 로컬 characterization을 운영 SLO나 leak
판정으로 승격하지 않는다.

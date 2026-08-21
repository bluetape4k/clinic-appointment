# Issue #369 Redis notification outbox admission benchmark 분석

## 목적과 범위

Issue #369의 목적은 notification outbox admission 경로에서 clinic cardinality와
clinic ID churn이 Redis 기반 전역·병원별 제한, queueing, lease recovery에 미치는
특성을 측정하는 것이다. 이 문서는 Redis `8.8`의 로컬 characterization 결과를
정리한다.

이번 작업은 production semaphore, fencing, rollout flag, 운영 SLO를 변경하지 않는다.
`appointment-notification`의 test source에 있는 internal coordinator 타입을 직접 검증해야
하므로 `kotlinx-benchmark` 공용 모듈이 아니라 module-scoped `JavaExec` task로 격리했다.
보고서의 `deploymentSloEvidence=false`는 이 결과가 배포 SLO 증명이 아님을 명시한다.

## 근거와 재현

| 항목 | 근거 |
|---|---|
| GitHub 이슈 | [Issue #369](https://github.com/bluetape4k/clinic-appointment/issues/369) |
| 생산 의미론 | `appointment-notification/src/main/kotlin/io/bluetape4k/clinic/appointment/notification/NotificationOutboxConcurrencyCoordinator.kt` |
| Redis 고정 계약 | `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/Redis88Launcher.kt` |
| 측정 harness | `appointment-notification/src/test/kotlin/io/bluetape4k/clinic/appointment/notification/RedisNotificationAdmissionBenchmark.kt` |
| Gradle 진입점 | `appointment-notification/build.gradle.kts`의 `redisAdmissionBenchmarkSmoke`, `redisAdmissionBenchmark` |
| JSON 계약 검증 | `scripts/validate-redis-notification-admission-benchmark.mjs` |
| 전체 raw report | [main.json](main.json) |
| smoke baseline | [baseline/run-1.json](baseline/run-1.json), [run-2.json](baseline/run-2.json), [run-3.json](baseline/run-3.json) |

실행 명령은 다음과 같다. Gradle 의존 서비스는 기존 bluetape4k singleton launcher를
통해 Redis `8.8` 컨테이너로 기동하며, `--no-build-cache`로 측정 명령을 실행했다.

```bash
./gradlew :appointment-notification:redisAdmissionBenchmarkSmoke --no-build-cache
./gradlew :appointment-notification:redisAdmissionBenchmark --no-build-cache
node scripts/validate-redis-notification-admission-benchmark.mjs \
  --input appointment-notification/build/reports/redis-admission/main/redis-notification-admission.json \
  --target-p99-ms 250
```

실행 환경은 Java toolchain `21.0.12.1`, Gradle launcher Java `25.0.4`, macOS
`aarch64`, Colima Docker `28.4.0`, Redis image `redis:8.8`이다. 이 환경 범위는
운영 배포 전체를 대표하지 않는다.

## 측정 계약

- 시나리오당 80개 operation, 동시성 16개를 사용한다.
- 전역 admission cap은 8, clinic별 cap은 2로 고정한다.
- clinic cardinality는 10, 100, 1,000, churn은 0%, 50%, 100%를 사용한다.
- 각 cardinality/churn 조합을 `cold`와 `warm` cache mode로 한 번씩 실행해 18개
  시나리오를 만든다.
- `warmupMillis`는 clinic key 준비 시간이고 `workloadElapsedMillis`는 준비 이후
  workload 시간이다. steady-state 처리량은 후자를 분모로 사용한다.
- summary percentile은 모든 raw sample을 다시 합친 값이 아니라 시나리오별 percentile의
  보수적 최댓값인 `maxScenarioPercentile`이다.
- 모든 시나리오의 operation 합계는 성공과 backpressure의 합으로 검증한다.

## 전체 결과

아래 표의 latency 단위는 ms, warmup/workload 단위는 ms, 처리량 단위는
successful operation/sec이다. `key`는 시나리오 종료 직후 Redis key 수다.

| mode | cardinality | churn | warmup | workload | p50 | p95 | p99 | throughput | backpressure | unique clinics | key |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| cold | 10 | 0% | 0.000 | 162.200 | 6.510 | 137.150 | 138.923 | 493.219 | 11 | 10 | 60 |
| warm | 10 | 0% | 27.326 | 160.721 | 6.526 | 103.696 | 108.233 | 497.757 | 14 | 10 | 60 |
| cold | 10 | 50% | 0.000 | 121.463 | 5.425 | 108.788 | 108.912 | 658.638 | 10 | 50 | 260 |
| warm | 10 | 50% | 21.715 | 119.657 | 5.203 | 107.399 | 107.594 | 668.578 | 11 | 50 | 260 |
| cold | 10 | 100% | 0.000 | 109.127 | 7.104 | 108.687 | 109.011 | 733.091 | 8 | 80 | 410 |
| warm | 10 | 100% | 20.855 | 108.328 | 6.416 | 108.076 | 108.247 | 738.501 | 8 | 80 | 460 |
| cold | 100 | 0% | 0.000 | 108.305 | 6.175 | 108.022 | 108.153 | 738.657 | 8 | 80 | 410 |
| warm | 100 | 0% | 174.652 | 106.446 | 4.736 | 106.237 | 106.312 | 751.558 | 8 | 80 | 510 |
| cold | 100 | 50% | 0.000 | 108.307 | 6.005 | 108.052 | 108.127 | 738.642 | 8 | 80 | 410 |
| warm | 100 | 50% | 192.488 | 107.110 | 4.840 | 106.858 | 106.934 | 746.899 | 8 | 80 | 710 |
| cold | 100 | 100% | 0.000 | 106.969 | 5.927 | 106.795 | 106.859 | 747.880 | 8 | 80 | 410 |
| warm | 100 | 100% | 177.844 | 107.027 | 5.792 | 106.803 | 106.933 | 747.478 | 8 | 80 | 910 |
| cold | 1,000 | 0% | 0.000 | 106.817 | 5.738 | 106.589 | 106.663 | 748.944 | 8 | 80 | 410 |
| warm | 1,000 | 0% | 1,701.328 | 105.679 | 5.244 | 105.468 | 105.534 | 757.010 | 8 | 80 | 5,010 |
| cold | 1,000 | 50% | 0.000 | 106.927 | 5.607 | 106.725 | 106.799 | 748.176 | 8 | 80 | 410 |
| warm | 1,000 | 50% | 1,588.024 | 105.975 | 5.269 | 105.785 | 105.836 | 754.893 | 8 | 80 | 5,210 |
| cold | 1,000 | 100% | 0.000 | 106.775 | 5.324 | 106.631 | 106.679 | 749.242 | 8 | 80 | 410 |
| warm | 1,000 | 100% | 1,634.146 | 106.081 | 5.067 | 105.893 | 105.994 | 754.143 | 8 | 80 | 5,410 |

## 차트

![Issue #369 Redis admission benchmark 차트](charts/issue-369-redis-admission-chart-ko.png)

차트의 원자료는 이 문서의 표와 동일한 `main.json`이다. 상단은 admission
latency p50/p95/p99와 local p99 target `250ms`를 비교하고, 하단은 warm
시나리오의 cardinality별 준비 시간과 시나리오 종료 직후 Redis key count를
churn별로 비교한다. 차트는 다음 명령으로 SVG를 재생성한 뒤 CairoSVG로 PNG를
렌더링한다.

```bash
node scripts/generate-issue369-redis-admission-chart.mjs \
  --input docs/benchmarks/issue-369-redis-admission-benchmark/main.json \
  --output docs/benchmarks/issue-369-redis-admission-benchmark/charts/issue-369-redis-admission-chart-ko.svg
cairosvg docs/benchmarks/issue-369-redis-admission-benchmark/charts/issue-369-redis-admission-chart-ko.svg \
  -o docs/benchmarks/issue-369-redis-admission-benchmark/charts/issue-369-redis-admission-chart-ko.png -s 2
```

summary의 보수적 worst-scenario aggregate는 다음과 같다.

| 지표 | p50 | p95 | p99 |
|---|---:|---:|---:|
| admission latency | 7.104 | 137.150 | 138.923 |
| queueing latency | 5.104 | 135.150 | 136.923 |
| acquire | 0.317 | 0.899 | 0.990 |
| reconcile | 0.295 | 0.662 | 2.340 |
| renew | 0.301 | 0.597 | 2.150 |

전체 측정은 1,440 admission sample, 성공 1,282건, backpressure 158건을
기록했다. end-to-end elapsed 기준 처리량은 `121.968 ops/s`이고, warmup을 제외한
steady-state 처리량은 `621.151 ops/s`다. 두 값을 섞지 않도록 report에 함께 보존했다.

lease recovery는 lease `1,000ms` 만료 뒤 `reacquired` 되었고, 재획득 latency는
`2.441ms`였다. parser는 모든 percentile 순서, operation 합계, warmup/workload
시간, recovery 상태와 p99 기준을 검증했다.

## Smoke baseline 반복

동일한 smoke workload를 세 번 반복하고 매 회 JSON parser를 통과시켰다.

| 반복 | admission p99 (ms) | steady throughput (ops/s) | backpressure | lease recovery |
|---:|---:|---:|---:|---|
| 1 | 139.070 | 134.966 | 32 | reacquired |
| 2 | 136.900 | 129.944 | 33 | reacquired |
| 3 | 137.219 | 130.013 | 33 | reacquired |

p99 범위는 `136.900–139.070ms`로 local target `250ms` 아래였고, 세 번 모두
`targetStatus=within-target`이었다. 이 반복은 성능 최적화 후보를 생성하기 위한
것이 아니라 benchmark parser와 baseline 변동 폭을 확인하기 위한 것이다.

## 해석과 결정

1. **p99 기준 통과**: full summary p99 `138.923ms`는 이 작업의 local target
   `250ms`보다 낮다. 현재 결과만으로 threshold breach나 production bug를
   주장하지 않는다.
2. **Redis primitive 비용은 낮음**: acquire/reconcile/renew p99가 각각
   `0.990/2.340/2.150ms`로 admission p99보다 작다. 관측된 꼬리는 주로
   admission queueing과 backpressure 경로에 있다.
3. **warm cardinality 구조 신호**: cardinality `1,000`의 warm 준비 시간이
   `1.588–1.701s`로 cardinality `100`의 `0.175–0.192s`보다 크고, key 수는
   churn `0%/50%/100%`에서 `5,010/5,210/5,410`으로 증가했다. cold 시나리오의
   key 수 `410`과 비교하면 clinic key lifecycle 또는 준비 비용을 별도 검증할
   근거가 된다.
4. **처리량 분모 고정**: warmup을 포함한 end-to-end 수치는 준비 비용의 영향을
   받으므로 지속 workload 비교에는 `steadyStateThroughputOpsPerSecond`를
   사용한다. 이것은 warmup을 숨기는 보정이 아니라 두 구간을 분리해 함께 보고하는
   계약이다.

따라서 이번 Issue #369는 characterization-only로 종료할 수 있다. production
semaphore/fencing 변경이나 rollout/SLO 판정은 하지 않는다. 다만 warm cardinality
1,000에서 반복되는 준비 시간과 key 증가 신호는 TTL, idle eviction, cleanup 계약과
장기 key retention을 확인하는 좁은 후속 이슈로 분리한다.

## 증명되지 않은 항목과 위험

- 한 대의 macOS/Colima 환경과 Redis `8.8`만 측정했으므로 운영 성능 일반화가 아니다.
- key count 증가는 현재 harness가 만든 key lifecycle의 관측값이며, leak 또는
  실제 장기 retention으로 단정하지 않는다.
- 80 operations/scenario는 warm 준비 비용의 재현 신호를 얻기 위한 범위이지
  장기 안정성·용량·배포 SLO 검증이 아니다.
- backpressure는 고정 cap과 2ms action workload의 결과이며, 운영 트래픽 비율을
  의미하지 않는다.

## 후속 조치 경계

후속 이슈는 다음만 검증한다.

- clinic admission key의 TTL, idle eviction, 명시적 cleanup 책임과 key prefix를
  장기 실행에서 확인한다.
- cardinality/churn 상한과 warmup 비용의 local threshold를 정하고 재현한다.
- threshold를 넘는 production-facing 증거가 나오기 전에는 semaphore/fencing
  구현을 변경하지 않는다.

## 문서 검수

| 항목 | 결과 |
|---|---|
| SPW-01 문제·범위·독자 명시 | PASS |
| SPW-02 결과 표와 raw JSON 연결 | PASS |
| SPW-03 용어·명령 일관성 | PASS |
| SPW-04 결정·제외 범위 기록 | PASS |
| SPW-05 검증 결과와 위험 명시 | PASS |

report의 원본 값은 [main.json](main.json)과 smoke baseline 링크에서 확인할 수
있으며, parser 출력의 `targetStatus=within-target`과
`deploymentSloEvidence=false`를 함께 확인해야 한다.

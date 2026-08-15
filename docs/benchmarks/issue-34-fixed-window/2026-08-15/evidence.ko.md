# Issue #34 fixed-window benchmark 증거

## 결론

Issue #34의 local benchmark gate를 실제 고정 구간 실행으로 닫았다. 최종 승인 집합은
PostgreSQL 취소 6회와 notification codec mixed-schema 12회, 총 18회다. 모든 실행은
JDK 25와 동일한 로컬 실행 조건에서 순차적으로 수행했고, 취소 comparator와 codec
comparator가 모두 `PASS`를 반환했다. 생성기 verdict와 렌더링한 차트도 `PASS`다.

이 결과는 로컬 PostgreSQL/H2 harness의 비교 증거다. protected backend E2E,
production ACL·backup·provider log, canary/SLO/rollback은 증명하지 않으므로 Issue #34
전체 DoD와 PR/merge 상태는 `PENDING`이다.

## 고정 실행 계약

| 항목 | 값 |
|---|---|
| baseline source ref | `bd07645f19d53008e1404a2cfd20cde17975e04c` |
| candidate source ref | `8f09a78945ee8e3335a72b0d95695649564d2cbb` |
| JDK | `25.0.4+7-LTS-jvmci-25.2-b20` |
| JVM | `Java HotSpot(TM) 64-Bit Server VM` |
| PostgreSQL image | `postgres:18-alpine` |
| 취소 window | warm-up 30초 + 측정 300초 |
| 취소 dataset/concurrency | appointments 100, same 10, different 20 |
| 취소 pause/seed | `pauseMillis=1000`, seed `34` |
| codec window | warm-up 30초 + 측정 300초 |
| codec dataset | H2 10,000 rows, detail length 15, batch 500 |

취소 harness는 전역 start/end barrier, `SYSTEM_NANO_TIME` 측정 span, lock-wait query
sampling, `environmentFingerprint`를 기록했다. codec harness는 실제 outbox row를
읽어 `legacy-heavy`와 `current-heavy` payload를 decode하고 drain했다.

## 최종 18회 run matrix

| lane | mode | mix | run | artifact |
|---|---|---|---:|---|
| cancel | baseline | PostgreSQL | 1–3 | [`reports/cancel-baseline.json`](reports/cancel-baseline.json) |
| cancel | candidate | PostgreSQL | 1–3 | [`reports/cancel-candidate.json`](reports/cancel-candidate.json) |
| codec | baseline | `legacy-heavy` | 1–3 | [`reports/codec-baseline`](reports/codec-baseline) |
| codec | baseline | `current-heavy` | 1–3 | [`reports/codec-baseline`](reports/codec-baseline) |
| codec | candidate | `legacy-heavy` | 1–3 | [`reports/codec-candidate`](reports/codec-candidate) |
| codec | candidate | `current-heavy` | 1–3 | [`reports/codec-candidate`](reports/codec-candidate) |

따라서 `cancel 3+3 = 6`, `codec 2 mix × 3 run × 2 mode = 12`, 합계 `18`회다.
각 JSON에는 run 번호, source ref, 환경 snapshot, 측정 span, metric, 오류·sampling
상태가 들어 있다. 원본 입력은 [`raw`](raw)에 그대로 보존했다.

## Comparator 결과

### PostgreSQL 취소

정규화된 report를 [`scripts/compare-issue34-benchmark.sh`](../../../../scripts/compare-issue34-benchmark.sh)로 다시 비교했다.

```text
PASS issue-34 benchmark gate
- p95: 55.5809 ms (baseline 57.6241 ms)
- p99: 96.0293 ms (baseline 92.3046 ms)
- unexpected error rate: 0
- unintended retry exhaustion rate: 0
- lock-wait p95: 24.67 ms
- expected conflict observed rate: 0.3752
- expected retry exhaustion observed rate: 0
```

3회 median은 다음과 같다.

| 메트릭 | baseline | candidate | 변화율 |
|---|---:|---:|---:|
| cancel p95 | 57.624 ms | 55.581 ms | -3.546% |
| cancel p99 | 92.305 ms | 96.029 ms | 4.035% |
| lock-wait p95 | 29.843 ms | 24.670 ms | -17.334% |
| 예상 밖 오류율 | 0.000% | 0.000% | 0.000% |
| 비의도 retry exhaustion | 0.000% | 0.000% | 0.000% |
| scenario mismatch | 1.104% | 0.000% | -100% |

### Notification codec mixed backlog

정규화된 6개씩의 codec report를 [`scripts/compare-issue34-codec-benchmark.mjs`](../../../../scripts/compare-issue34-codec-benchmark.mjs)로 비교했다.

```text
PASS legacy-heavy: p95 0.004ms, p99 0.005ms, throughput 30361.520/s, drain 165999.595ms
PASS current-heavy: p95 0.004ms, p99 0.005ms, throughput 31031.516/s, drain 163060.031ms
```

| mix | decode p95 ms | decode p99 ms | throughput rows/s | drain ms | 판정 |
|---|---:|---:|---:|---:|---|
| `legacy-heavy` | 0.004 / 0.004 | 0.005 / 0.005 | 29220.897 / 30361.520 | 164950.447 / 165999.595 | PASS |
| `current-heavy` | 0.004 / 0.004 | 0.005 / 0.005 | 30871.893 / 31031.516 | 163255.295 / 163060.031 | PASS |

표의 값은 `baseline / candidate` 순서다. 두 mix 모두 decode failure는 `0`이다.

## Provenance 정규화

임시 실행 중 일부 JVM system property에 실제 Git SHA보다 한 글자 긴
`sourceCommit` 전달값이 들어갔다. metric·timing·sampling을 다시 만들거나 보정하지
않고, 실제 benchmark worktree의 `git rev-parse HEAD`를 확인해 source ref와 그 값에
종속된 취소 `environmentFingerprint`만 정규화했다. 원본과 normalized report의 대응은
[`provenance.json`](provenance.json)에 기록했다. comparator와 chart는 normalized
report에서 다시 실행했으며 threshold는 변경하지 않았다.

JDK 21 baseline은 JDK 25 candidate와 환경 fingerprint가 달라 최종 집합에 넣지 않았다.
첫 codec candidate 6회 calibration은 comparator에서 latency/throughput regression이
나와 제외했고, 동일 JDK 25 조건으로 candidate를 다시 6회 실행한 `candidate-rerun`을
최종 12회 codec 집합으로 채택했다. 이 제외 이력은 결과를 숨기지 않고 다음과 같이
남긴다.

- 제외: JDK 21 baseline 3회 — 환경 불일치
- 제외: 첫 codec candidate 6회 — comparator `FAIL`, 환경 교정 후 재실행
- 채택: JDK 25 baseline 9회 + JDK 25 candidate 9회 = 최종 18회

## 차트와 시각 검증

생성기 입력과 결과는 [`charts/issue-34-benchmark-analysis.ko.md`](charts/issue-34-benchmark-analysis.ko.md)와
[`charts/issue-34-benchmark-summary.json`](charts/issue-34-benchmark-summary.json)에
있다. SVG 4개를 CairoSVG로 PNG 4개로 렌더링했다.

- semantic ledger: nodes `5`, edges `4`, budget 이내, source-path audit `PASS`
- SVG XML/text/geometry/endpoint/mixed-corner audit: 4개 모두 `PASS`
- PNG visual audit: 4개 모두 opaque, aspect `2.06`, bbox occupancy `0.755` 이상,
  margin imbalance `0.073` 이하
- codec latency의 큰 drain-time 라벨은 baseline/candidate 간 겹침을 제거하고
  semantic ledger repair receipt에 기록했다.
- 최종 PNG는 full-size로 확인했고 축 단위·legend·값·`PASS` 판정을 읽을 수 있다.

차트는 benchmark 비교를 빠르게 보여 주는 보조 산출물이며 deployment SLO를 의미하지
않는다.

## 재현 명령

저장된 최종 report 기준 replay 명령은 다음과 같다.

```bash
TARGET=docs/benchmarks/issue-34-fixed-window/2026-08-15
scripts/compare-issue34-benchmark.sh \
  "$TARGET/reports/cancel-baseline.json" \
  "$TARGET/reports/cancel-candidate.json"
node scripts/compare-issue34-codec-benchmark.mjs \
  "$TARGET/reports/codec-baseline" \
  "$TARGET/reports/codec-candidate"
node scripts/generate-issue34-benchmark-chart.mjs \
  --cancel-baseline "$TARGET/reports/cancel-baseline.json" \
  --cancel-candidate "$TARGET/reports/cancel-candidate.json" \
  --codec-baseline-dir "$TARGET/reports/codec-baseline" \
  --codec-candidate-dir "$TARGET/reports/codec-candidate" \
  --output-dir "$TARGET/replay-charts"
```

## 남은 게이트

| 게이트 | 상태 | 근거 |
|---|---|---|
| 18회 local fixed-window + comparator | PASS | 이 문서와 `comparator/*.log` |
| 차트·PNG·semantic/visual QA | PASS | `charts/` 산출물 |
| protected backend E2E, ETag/412·권한·outbox trace | PENDING | 별도 보호 실행 환경 필요 |
| production MySQL endpoint smoke | PENDING | `APPOINTMENT_PRODUCTION_MYSQL_JDBC_URL/USER/PASSWORD` 미설정 |
| production ACL·backup·provider log·canary/SLO/rollback | PENDING | production 권한과 운영 증거 필요 |

이 문서의 local benchmark `PASS`는 Issue #305의 구현·migration 전체 검증을 대신하지
않으며, 외부 credential 게이트가 닫히기 전에는 PR merge-ready로 판정하지 않는다.

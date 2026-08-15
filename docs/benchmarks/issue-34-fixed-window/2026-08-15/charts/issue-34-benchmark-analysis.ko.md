# Issue #34 benchmark 결과 분석

> 이 문서는 생성기에 전달한 실제 3회 baseline/candidate artifact에서 계산했다. benchmark 근거이며 배포 SLO가 아니다.

## 판정: PASS

- 배포 SLO 증거: `false`
- 취소 sourceCommit: baseline `bd07645f19d53008e1404a2cfd20cde17975e04c` → candidate `8f09a78945ee8e3335a72b0d95695649564d2cbb`
- codec sourceCommit: legacy-heavy `bd07645f19d53008e1404a2cfd20cde17975e04c` → `8f09a78945ee8e3335a72b0d95695649564d2cbb`, current-heavy도 같은 provenance를 사용한다.
- 취소 측정 환경: `postgres:18-alpine`, concurrency `10/20`, `pauseMillis=1000ms`, 측정 clock `SYSTEM_NANO_TIME`.
- 모든 결과는 각 mode의 3회 측정 median이다.

## PostgreSQL 환자 예약 취소

| 메트릭 | baseline | candidate | 변화율 |
|---|---:|---:|---:|
| cancel p95 (ms) | 57.624 | 55.581 | -3.546% |
| cancel p99 (ms) | 92.305 | 96.029 | 4.035% |
| lock-wait p95 (ms) | 29.843 | 24.670 | -17.334% |
| 예상 412 비율 | 37.237% | 37.523% | 0.768% |
| 예상 retry exhaustion 비율 | 0.000% | 0.000% | 0.000% |
| 예상 밖 오류율 | 0.000% | 0.000% | 0.000% |
| 비의도 retry exhaustion 비율 | 0.000% | 0.000% | 0.000% |

### lock-wait 표본 신뢰도

| mode | run별 측정 span | run별 warm-up 요청 | run별 측정 요청 | run별 성공 query 수 | run별 실패 수 | 판정 |
|---|---|---|---|---|---|---|
| baseline | run1=301012ms, run2=300999ms, run3=301016ms | run1=870, run2=870, run3=870 | run1=8747, run2=8396, run3=8787 | run1=9243, run2=8246, run3=9661 | run1=0, run2=0, run3=0 | PASS |
| candidate | run1=301028ms, run2=300823ms, run3=300886ms | run1=870, run2=844, run3=870 | run1=8760, run2=8760, run3=8760 | run1=9291, run2=9465, run3=9553 | run1=0, run2=0, run3=0 | PASS |

취소 gate는 p95 상대 10%, p99 상대 15%, 절대 p95 500ms, p99 1초,
예상 밖 오류율 1%, 비의도 retry exhaustion 0.1%, lock-wait p95 50ms,
scenario mismatch 0을 기준으로 판정한다.

## Notification codec mixed backlog

| mix | decode p95 ms (baseline / candidate) | decode p99 ms (baseline / candidate) | throughput rows/s (baseline / candidate) | drain ms (baseline / candidate) | 판정 |
|---|---:|---:|---:|---:|---|
| legacy-heavy | 0.004 / 0.004 | 0.005 / 0.005 | 29220.897 / 30361.520 | 164950.447 / 165999.595 | PASS |
| current-heavy | 0.004 / 0.004 | 0.005 / 0.005 | 30871.893 / 31031.516 | 163255.295 / 163060.031 | PASS |

codec gate는 decode p95/p99 절대 상한 500ms/1초, 상대 회귀 10%/15%,
throughput 10% 이상 감소 금지, drain time 10% 이상 증가 금지, decode failure 0을 사용한다.

## Gate 상세

- 모든 상대·절대·오류율 gate 통과

## 해석 규칙

- `expectedConflictRate`와 `expectedRetryExhaustionRate`는 고정 arrival mix의 의도한 결과다. 오류율과 retry exhaustion gate의 분모에서 제외한다.
- 모든 취소 run은 30개 virtual user가 전역 start barrier를 통과한 뒤 측정 요청과 lock-wait sampling을 시작하고, 전역 end barrier에서 함께 닫아야 한다.
- 매 run은 report 환경의 전체 snapshot과 SHA-256 `environmentFingerprint`를 보존하고, `pauseMillis`를 포함해 같아야 한다. 측정 span은 `SYSTEM_NANO_TIME` 기준으로 설정한 window의 95%-105% 범위여야 한다.
- 모든 취소 run은 lock-wait query를 한 번 이상 성공해야 하고 실패 수가 0이어야 한다. warm-up query나 조회 실패를 `0 ms`로 해석하지 않는다.
- `sourceCommit`이 없거나 `unknown`이거나 baseline/candidate가 같으면 생성기는 결과를 만들지 않는다.
- 입력 artifact가 없거나 3회·환경·dataset 계약을 만족하지 않으면 이 문서 대신 실행이 실패해야 한다. 현재 저장소의 실측 결과가 없을 때는 이 문서의 템플릿 상태를 유지한다.
- 결과는 로컬 PostgreSQL/H2 harness의 비교 근거이며 보호된 backend E2E, 운영 rollout readiness, production SLO를 증명하지 않는다.

## 재현 명령

```bash
node scripts/generate-issue34-benchmark-chart.mjs \
  --cancel-baseline <cancel-baseline.json> \
  --cancel-candidate <cancel-candidate.json> \
  --codec-baseline-dir <codec-baseline-dir> \
  --codec-candidate-dir <codec-candidate-dir> \
  --output-dir <output-dir>

scripts/compare-issue34-benchmark.sh <cancel-baseline.json> <cancel-candidate.json>
node scripts/compare-issue34-codec-benchmark.mjs <codec-baseline-dir> <codec-candidate-dir>
```

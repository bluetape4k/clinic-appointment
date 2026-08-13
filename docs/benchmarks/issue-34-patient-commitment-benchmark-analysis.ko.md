# Issue #34 benchmark 결과 분석

## 현재 상태

`PENDING` — 현재 브랜치에는 Issue #34의 유효한 PostgreSQL 취소
baseline/candidate 3회 결과와 notification codec mixed-schema 3회 결과가
없다. 따라서 실측 수치를 차트로 만들거나 성능 통과를 주장하지 않는다.

현재 확인한 것은 다음 범위다.

- `PatientAppointmentCancelPostgresFixture`는 PostgreSQL Testcontainers와
  실제 command 경로를 사용하고, 100개 dataset·동일 appointment 10·상이
  appointment 20·30초 warm-up·5분 측정·3회 artifact 계약을 구현한다.
- `NotificationCodecBacklogBenchmarkTest`는 실제 `NotificationOutboxEvents`
  row를 읽어 v1/v2 codec을 decode하고 terminal 상태로 drain하는 harness를
  제공한다.
- 로컬 smoke와 PR 일반 CI는 통과했지만, pre-change source와 candidate의
  고정 환경 3회 비교 artifact는 아직 없다.

## 차트와 분석 생성기

실측 artifact가 준비되면 아래 명령으로 같은 입력에서 SVG 차트, 한국어
분석 문서, machine-readable summary를 함께 만든다.

```bash
node scripts/generate-issue34-benchmark-chart.mjs \
  --cancel-baseline <cancel-baseline.json> \
  --cancel-candidate <cancel-candidate.json> \
  --codec-baseline-dir <codec-baseline-dir> \
  --codec-candidate-dir <codec-candidate-dir> \
  --output-dir <output-dir>
```

생성기 입력은 다음 조건을 모두 만족해야 한다.

| 입력 | 고정 계약 |
|---|---|
| 취소 baseline/candidate | `issue-34-patient-appointment-cancel`, 각 3회(`run` 1·2·3), dataset 100, warm-up 30초, 측정 300초, concurrency 10/20, 요청 간 pause 1,000ms |
| 취소 provenance | report와 매 run의 전체 환경 snapshot·SHA-256 `environmentFingerprint`·`sourceCommit` 필수·일치, `unknown` 금지, baseline/candidate 서로 다른 commit |
| 취소 측정 경계 | 30개 virtual user의 전역 start/end barrier, 매 run `warmupRequests > 0`, `requests > 0`, `System.nanoTime()`으로 잰 측정 span은 설정 window의 95%-105% |
| 취소 lock-wait | start barrier에서 sampler를 열고 end barrier에서 timeout 안에 닫은 뒤 종료 시각을 기록, 매 run `lockWaitSampleQueries > 0`, `lockWaitSampleFailures = 0` |
| codec baseline/candidate | `issue-34-notification-codec-backlog`, `legacy-heavy`·`current-heavy` 각각 3회 |
| codec dataset | 각 report 10,000 rows, 등록 detail 15자, batch 500, warm-up 30초, 측정 300초 |
| codec metrics | decoded rows, latency sample, throughput, p95/p99, drain time을 모두 기록하고 p99 ≥ p95 |

입력이 누락되거나 환경·provenance가 어긋나면 생성기는 출력하지 않고
실패한다. 같은 코드 경로에서 `issue34.mode`만 바꿔 만든 두 결과는
pre-change baseline으로 인정하지 않는다.

## 증거 계약 교훈

Gatling scenario 시간과 fixture 내부 deadline을 따로 시작하면 fixture 초기화나
virtual user 시작 지연만큼 warm-up 표본이 측정 구간에 섞일 수 있다. 따라서 각
virtual user가 warm-up을 마친 뒤 전역 barrier에서 측정을 함께 시작하고, 모두가
측정을 마친 뒤 두 번째 barrier에서 lock-wait sampler와 측정 구간을 함께 닫는다.

또한 report 최상위 환경만 비교하면 같은 commit에서 실행한 smoke·다른 JDK·다른
PostgreSQL image의 run이 하나의 artifact에 섞여도 숨겨질 수 있다. 매 run에 전체
환경 snapshot과 fingerprint, 요청 간 pause, 실제 측정 시작·종료·span을 기록하고,
append·comparator·chart generator 세 경계에서 동일성을 각각 검증한다. 측정 span은
시스템 시각 보정의 영향을 받지 않도록 `System.nanoTime()`으로 계산하고, epoch 시각은
감사·추적용으로만 보존한다. lock-wait query와 sampler 종료 대기도 timeout으로 제한해
DB stall이 benchmark 종료를 무기한 막지 않게 한다.

## 생성되는 결과

`<output-dir>`에는 다음 파일이 생긴다.

- `issue-34-patient-appointment-cancel-latency-ko.svg`
  - cancel p95, cancel p99, lock-wait p95를 baseline/candidate로 비교한다.
- `issue-34-patient-appointment-cancel-safety-ko.svg`
  - 예상 412·예상 retry exhaustion과 예상 밖 오류·비의도 exhaustion·scenario
    mismatch를 비율로 비교한다.
- `issue-34-notification-codec-latency-ko.svg`
  - 두 mixed-schema profile의 decode p95/p99와 drain time을 비교한다.
- `issue-34-notification-codec-throughput-ko.svg`
  - 두 profile의 초당 decoded row 처리량을 비교한다.
- `issue-34-benchmark-analysis.ko.md`
  - 각 3회 결과의 median, 변화율, gate 판정, sourceCommit, 해석 규칙을
    기록한다.
- `issue-34-benchmark-summary.json`
  - 차트와 분석이 사용한 동일 median·gate 계산 결과를 보존한다.

차트는 SVG → PNG 파이프라인을 사용한다. 실제 artifact가 생긴 뒤 CairoSVG로
PNG를 렌더링하고, SVG/PNG pair·텍스트·캔버스 검사를 통과한 파일만
README나 review page에 노출한다. 생성된 차트의 캡션에는 benchmark 근거이며
배포 SLO가 아니라는 경계를 유지한다.

## 판정 기준

취소 lane은 p95 상대 10%, p99 상대 15%, 절대 p95 500ms, p99 1초,
예상 밖 오류율 1%, 비의도 retry exhaustion 0.1%, lock-wait p95 50ms,
scenario mismatch 0을 사용한다.

codec lane은 decode p95/p99 절대 500ms/1초, p95/p99 상대 10%/15%,
throughput 10% 이상 감소 금지, drain time 10% 이상 증가 금지, decode
failure 0을 사용한다.

`expectedConflictRate`와 `expectedRetryExhaustionRate`는 고정 arrival mix의
의도한 시나리오 결과다. 이 값은 오류율과 비의도 exhaustion gate의 분모에
포함하지 않는다.

## 현재 증거와 다음 단계

| 항목 | 현재 증거 | 상태 |
|---|---|---|
| 취소 fixture·report writer | `appointment-api/src/gatling/kotlin/io/bluetape4k/clinic/appointment/api/commitment/PatientAppointmentCancelPostgresFixture.kt` | 구현·smoke PASS |
| codec backlog harness | `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationCodecBacklogBenchmarkTest.kt` | 구현·smoke PASS |
| 비교 gate | `scripts/compare-issue34-benchmark.sh`, `scripts/compare-issue34-codec-benchmark.mjs` | 계약·테스트 PASS |
| 차트·분석 생성기 | `scripts/generate-issue34-benchmark-chart.mjs` | 합성 fixture 테스트 PASS |
| 고정 window 실측 | baseline/candidate 각 3회, 동일 환경·서로 다른 sourceCommit | 미실행 |
| 보호 backend E2E·운영 rollout | 실제 backend와 운영 환경 | 미실행 |

고정 window artifact가 확보되기 전까지 Issue #34의 성능·운영 DoD는
`PENDING`으로 유지한다. 이 문서는 결과를 대신하지 않으며, 결과가 생기면
생성기 출력의 source-of-truth와 함께 갱신한다.

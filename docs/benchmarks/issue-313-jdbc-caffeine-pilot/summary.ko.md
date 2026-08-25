# Issue #313 JDBC Caffeine 정책 캐시 파일럿 결과

## 질문과 범위

기존 `EffectivePolicyCache`를 bluetape4k-exposed JDBC Caffeine 정책 기준 데이터 경로로
대체할 근거가 있는지 확인하기 위해 H2 root JDBC transaction characterization을
실행했다. 비교 대상은 기존 메모리 캐시와 test-only 후보이며, 운영 Spring wiring,
운영 DB, 멀티노드 일관성은 이번 실행에 포함하지 않았다.

## 측정 조건

- benchmark family: `io.bluetape4k.clinic.appointment.api.config.JdbcCaffeineEffectivePolicyPilotBenchmark`
- warm-up 5회, 측정 20회, H2, Java `21.0.12.1` 환경
- latency는 `ns/op`, allocation은 `bytes/op`이며 모두 낮을수록 좋다.
- 로그 스케일 chart는 p50 막대와 p95 라벨을 함께 표시한다.
- `productionSloEvidence=false`, `rawPayloadIncluded=false`

| 프로필 | 기존 p50 / p95 (ns) | 후보 p50 / p95 (ns) | 기존 allocation (bytes) | 후보 allocation (bytes) |
|---|---:|---:|---:|---:|
| `hot-hit` | 416 / 5,083 | 3,667 / 14,750 | 48 | 104 |
| `cold-fill` | 1,709 / 4,750 | 132,708 / 213,916 | 112 | 8,424 |
| `invalidation` | 9,875 / 77,167 | 87,708 / 258,792 | 2,208 | 7,416 |
| `cold-start` | 1,541 / 4,666 | 247,625 / 329,834 | 616 | 25,592 |

## 판정

**HOLD — 운영 캐시 교체를 승인하지 않는다.** 후보 latency p50은 네 프로필 모두
기존 경로보다 높았고, 특히 transaction stage/commit과 cold-start에서 allocation도
크게 증가했다. 이 수치는 H2 단일 JVM의 반복 측정이므로 운영 PostgreSQL latency,
멀티노드 fence, 실제 SLO를 추정하지 않는다.

따라서 이번 Issue #313은 commit-only publication, rollback discard, generation
conflict, clinic local fence, tenant/clinic key 격리, one-shot miss 및 pilot off
경로를 검증한 **계약 파일럿**으로 종료한다. 운영 도입은 PostgreSQL 대표성,
멀티노드 경합, 장애/재시도와 SLO를 별도 증거로 확보한 뒤 다시 판단한다.

## 재현과 산출물

```bash
./gradlew :appointment-api:jdbcCaffeineEffectivePolicyPilotBenchmark --no-daemon
node scripts/generate-issue313-jdbc-caffeine-chart.mjs \
  --input appointment-api/build/reports/issue-313/jdbc-caffeine-pilot.json \
  --output docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.svg \
  --semantic-output docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.semantic.json \
  --data-output docs/benchmarks/issue-313-jdbc-caffeine-pilot/chart.data.json
```

- 원시 반복 결과: `benchmark.json`
- chart 입력 정규화: `chart.data.json`
- SVG 및 PNG: `chart.svg`, `chart.png`
- semantic ledger: `chart.semantic.json`
- 출처와 검증 receipt: `provenance.json`

차트는 `sourceCommit=311befc378aa467b6331b2a78f962101d115b11b`의 실행 결과를
사용하며, 검증된 semantic ledger는 node 8개·comparison edge 4개로 chart 복잡도
예산 안에 있다.

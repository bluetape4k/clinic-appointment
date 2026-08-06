# Issue #41 PostgreSQL `kotlinx-benchmark` lesson

## 결정

H2 수동 측정은 SQL 계약 회귀에는 충분하지만 production PostgreSQL dialect와 실제
Flyway schema, Hikari `DataSource` wiring의 비용을 보여주지 못한다. 따라서 benchmark
전용 nested Gradle module을 만들고 `kotlinx-benchmark` JSON을 단일 원천으로 삼았다.

## 배운 점

1. `appointment-api` Spring Boot plain jar만 의존하면 production Flyway resources가
   benchmark runtime classpath에 안정적으로 오지 않는다. benchmark module에서
   `appointment-api/src/main/resources`를 명시적인 resource source로 추가해야
   V1–V22가 실제로 검증된다.
2. local arm64 Docker에서 amd64 PostgreSQL image를 emulation하면 JDBC batch seed가
   과도하게 느려질 수 있다. 20,000건 synthetic backlog는 PostgreSQL
   `generate_series` 한 번으로 seed하고, measured invocation에는 reset을 넣지 않아
   fixture 비용이 throughput에 섞이지 않게 했다.
3. benchmark raw JSON의 timestamp output path는 커밋할 수 없지만 provenance에서는
   잃으면 안 된다. collector는 `--config`와 일치하는 directory만 고른 뒤 실제
   timestamp 경로를 `sourceFile`에 보존하고, chart/README용 stable 경로는
   `sourceFilePattern`으로 분리한다. main/smoke 혼합 output을 고르는 회귀 test를
   함께 둔다.
4. root `subprojects`의 Kover 자동 적용은 benchmark generated JMH class를 제품
   coverage에 섞을 수 있다. benchmark module을 Kover plugin/aggregate에서 명시적으로
   제외해야 CI coverage 의미가 보존된다.
5. percentile 숫자만 문서화하면 throughput과 latency를 혼동할 수 있다. chart와
   README에 `ops/ms`, p50/p95/p99, PostgreSQL image, seed, row 수, 그리고
   `deploymentSloEvidence=false` 경계를 함께 표시했다.
6. GitHub-hosted `ubuntu-latest`에는 `xmllint`와 `identify`가 보장되지 않았다.
   artifact 생성은 성공했지만 두 검증 단계가 차례로 exit 127로 실패했으므로,
   OS package 설치 대신 Python 표준 `xml.etree.ElementTree`와 CairoSVG가 함께
   설치하는 Pillow로 SVG well-formedness와 PNG decode/size를 검증한다. 같은
   검증을 PR smoke와 nightly full에 유지한다.

## 재현 명령

```bash
./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:mainBenchmark --no-daemon --console=plain
node scripts/collect-appointment-messaging-benchmark.mjs \
  --input-dir benchmark/appointment-messaging-benchmark/build/reports/benchmarks \
  --output build/reports/appointment-messaging-postgresql/benchmark.json --config smoke
node scripts/validate-appointment-messaging-benchmark.mjs \
  --input build/reports/appointment-messaging-postgresql/benchmark.json
```

차트 생성은 validator 성공 이후에만 수행하며, CI는 생성된 JSON/SVG/PNG를 14일(PR
smoke) 또는 30일(nightly full) artifact로 보존한다.

## 후속 작업

- hosted runner의 PR smoke와 nightly full artifact를 실제로 수집한다.
- 운영 rollout 전에는 PostgreSQL native architecture에서 lock-wait, multi-worker
  contention, connection pool saturation, Kafka catch-up을 별도 측정한다.
- benchmark 수치를 deployment SLO로 승격하려면 환경별 threshold와 historical
  regression policy를 별도 승인 문서로 만든다.

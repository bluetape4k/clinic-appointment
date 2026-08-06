# Issue #41 PostgreSQL `kotlinx-benchmark` 설계

## 목표

Issue #41에서 병합한 appointment outbox claim 경로의 성능 근거를 H2 수동 측정에
머물지 않고 PostgreSQL 실측으로 재현한다. 결과는 `kotlinx-benchmark` JSON,
문서 표, 한·영 chart, CI/nightly artifact로 연결하며, 배포 SLO로 과장하지 않는다.

## 범위와 현재 근거

- 기존 `appointment-messaging` H2 query-plan/performance 테스트는 SQL 계약과
  bounded claim 동작을 보호한다.
- 현재 누락된 근거는 PostgreSQL dialect, production Flyway schema, Hikari
  `DataSource` wiring, JMH 기반 percentile 결과, 재현 가능한 chart와 CI artifact다.
- Issue #41과 PR #227은 이미 병합되었으므로 이 작업은 `Related to #41`인 후속
  개선으로 기록한다. 기존 issue를 되돌리거나 H2 계약을 삭제하지 않는다.

## 아키텍처

```text
PostgreSQLServer.Launcher.postgres
        │ singleton container
        ▼
HikariDataSource ── Flyway(classpath:db/migration/postgresql)
        │
        ▼
Exposed Database.connect(dataSource)
        │ transaction { JdbcAppointmentOutboxStore.claim(...) }
        ▼
kotlinx-benchmark JSON ── validator ── docs baseline ── EN/KO SVG + PNG
```

새 Gradle 모듈 `benchmark/appointment-messaging-benchmark`은 production 모듈과
분리한다. 모듈 경계를 넘는 benchmark 전용 코드가 production test source set에
섞이지 않으며, root Kover plugin/aggregate에서도 제외해 CI coverage 집계에
포함하지 않는다. benchmark는
`implementation(project(":appointment-messaging"))`으로 실제 store를 호출하고,
`appointment-api`의 production Flyway resource를 classpath에서 읽는다.

`JdbcAppointmentOutboxStore` 생성자는 이미 공개 class의 실제 사용 경로를
benchmark가 재현할 수 있도록 public으로 연다. test-fixtures 변형을 추가하는
대안은 Gradle variant와 CI 복잡도를 늘리므로 채택하지 않는다.

## 데이터와 측정 계약

- PostgreSQL image와 credentials는 bluetape4k singleton launcher가 제공한다.
- Hikari pool은 benchmark state가 소유하고 teardown에서 닫는다. benchmark 본문은
  `Database.connect(dataSource)`로 연결된 Exposed database transaction만 호출한다.
- Flyway는 `classpath:db/migration/postgresql` 전체를 실행한다. 별도 축약 schema나
  Exposed `SchemaUtils.create`로 production migration을 대체하지 않는다.
- seed는 fixed seed `41`, tenant `1`, clinic `31`, mixed legacy/appointment backlog
  `20_000`건을 사용한다. payload는 redacted synthetic data만 포함한다.
- 각 benchmark fork가 isolated schema를 새로 만들고 20,000건 backlog를 한 번
  seed한다. 측정 invocation에서 전체 backlog를 복원하지 않아 measured operation에
  reset 비용을 섞지 않으며, 설정된 유한 iteration이 backlog를 고갈하지 않도록 한다.
- 기본 `main` configuration은 full 측정, `smoke` configuration은 CI pull-request
  확인용 단축 측정이다. warmup/iteration/time unit은 Gradle configuration에
  명시하며 benchmark annotation으로 숨기지 않는다.
- 결과는 `reportFormat = "json"`으로 만들고 validator가 benchmark name,
  PostgreSQL marker, row count, score, p50/p95/p99 존재 여부와 양수 값을 검증한다.
  percentile이 JMH raw output에 없는 경우 성공으로 처리하지 않는다.
- collector는 `--config`와 일치하는 raw output directory만 선택하고, 실제
  timestamp 경로를 `sourceFile`에 보존하며 문서용 stable 경로는
  `sourceFilePattern`으로 분리한다. main/smoke raw output을 섞어 보고하거나
  존재하지 않는 경로를 provenance로 남기지 않는다.

## Chart와 문서

검증된 JSON에서 deterministic Node script가 English/Korean SVG를 생성한다. 각
SVG의 원천은 동일한 baseline JSON이며, PNG를 README에 표시하는 authoritative
reader-facing artifact로 둔다. chart에는 PostgreSQL, row count, seed, 단위, p50/
p95/p99와 “benchmark evidence, not deployment SLO” 경계를 명시한다.

- `docs/benchmarks/appointment-messaging-postgresql-baseline.json`: 커밋하는
  재현 가능한 baseline metadata와 수치.
- `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-en.svg`
  및 `.png`: English.
- `docs/images/readme-charts/appointment-messaging-postgresql-benchmark-01-ko.svg`
  및 `.png`: Korean.
- root 및 `appointment-messaging` README는 locale에 맞는 chart와 실행 명령을
  동등하게 가리킨다.

## CI/nightly 계약

- path filter는 benchmark module, chart generator, baseline/docs 변경을 messaging
  lane에 포함한다.
- pull-request CI는 Docker singleton을 사용하는 smoke task를 실행하고 JSON,
  validation summary, generated chart를 artifact로 업로드한다.
- nightly는 같은 module의 full task를 별도 serialized job으로 실행하며 artifact
  보존 기간과 실패를 nightly status에 연결한다.
- benchmark job은 Kover coverage aggregation에 포함하지 않는다. benchmark
  변동성은 CI gate에서 SLO threshold로 판정하지 않고, 실행·JSON schema·양수
  percentile·artifact 생성만 gate로 삼는다.

## 실패 모드와 복구

| 위험 | 대응 | 복구 |
|---|---|---|
| Docker/registry unavailable | smoke/full task 전 사전 조건과 명확한 로그 | benchmark job만 재실행; 코드/문서 merge gate와 분리 |
| migration drift | production migration classpath와 Flyway schema history 검증 | migration을 복제하지 말고 원본 resource 의존성을 수정 |
| pool/DB leak | state teardown에서 Hikari와 Exposed connection 정리 | failed fork 종료 후 singleton shutdown hook 확인 |
| benchmark output schema drift | validator가 파일을 선택하지 못하면 실패 | plugin task/output 경로를 최신 공식 문서에 맞춰 조정 |
| noisy percentile | fixed seed/row count와 full/nightly 분리 | 수치는 baseline 근거로만 보고, 배포 SLO 판정은 하지 않음 |
| chart drift | baseline JSON에서만 생성, XML/PNG geometry audit | SVG를 손으로 수정하지 않고 generator 재실행 |

롤백은 새 benchmark module, catalog entry, settings 등록, workflow path/job,
문서/chart를 되돌리는 것으로 한정한다. production outbox schema와 기존 H2
계약 테스트에는 rollback 영향이 없다.

## 승인 기준

1. `:appointment-messaging-benchmark:mainSmokeBenchmark`와 full task 이름이
   Gradle task listing에 실제로 존재한다.
2. Docker PostgreSQL에서 Flyway V1–V22를 적용하고 20,000건 seed 후 실제
   `JdbcAppointmentOutboxStore.claim`이 성공한다.
3. `kotlinx-benchmark` JSON validator가 benchmark 식별자와 score/p50/p95/p99를
   검증하고 stable baseline을 만든다.
4. EN/KO README와 SVG/PNG가 source-equivalent이며 XML/PNG/chart audit가 통과한다.
5. PR smoke와 nightly full artifact workflow가 actionlint/YAML 검사와 실제
   module task 실행 경로를 갖는다.
6. 기존 `appointment-messaging` targeted test와 compile/build가 회귀 없이
   통과한다.

## 공식 참고

- Kotlinx Benchmark README: <https://github.com/Kotlin/kotlinx-benchmark>
- JVM setup: <https://github.com/Kotlin/kotlinx-benchmark/blob/master/docs/kotlin-jvm-project-setup.md>
- Tasks: <https://github.com/Kotlin/kotlinx-benchmark/blob/master/docs/tasks-overview.md>
- Configuration: <https://github.com/Kotlin/kotlinx-benchmark/blob/master/docs/configuration-options.md>

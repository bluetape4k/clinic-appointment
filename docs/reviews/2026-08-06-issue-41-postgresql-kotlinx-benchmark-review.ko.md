# Issue #41 PostgreSQL `kotlinx-benchmark` 리뷰

## 범위

Issue #41 후속으로 추가한 PostgreSQL production schema benchmark, report
validator, EN/KO chart, README, CI smoke/nightly lane을 현재 feature worktree에서
검토했다. 기존 `appointment-messaging` H2 계약과 Kafka 4 outbox 구현은 변경하지
않고 targeted regression으로 재검증했다.

## 리뷰 렌즈와 결과

| 렌즈 | P0 | P1 | P2 | P3 | 결과 |
|---|---:|---:|---:|---:|---|
| correctness/API | 0 | 0 | 0 | 0 | public store constructor 외 계약 변화 없음; 실제 `claim` 호출 확인 |
| security/data | 0 | 0 | 0 | 0 | synthetic payload만 seed; credential·tenant/clinic 식별자를 chart/report에 기록하지 않음 |
| performance | 0 | 0 | 1 | 0 | local ARM64에서 amd64 PostgreSQL emulation noise; benchmark는 deployment SLO가 아님 |
| operability/CI | 0 | 0 | 1 | 0 | nightly full은 정적 workflow 검증만 완료; hosted runner 실 dispatch는 별도 증거 |
| docs/API | 0 | 0 | 0 | 0 | README/README.ko 명령·수치·chart 링크 source-equivalent |
| integration/build | 0 | 0 | 0 | 0 | 22개 Flyway migration, Exposed/Hikari wiring, Gradle task가 실제 동작 |

종합 결과: **P0=0, P1=0, P2=2, P3=0**. merge를 막는 correctness/security 결함은
발견하지 않았다.

## 검증 증거

- `./gradlew :appointment-messaging-benchmark:tasks --all`에서
  `mainBenchmark`와 `mainSmokeBenchmark` 확인.
- `./gradlew :appointment-messaging-benchmark:mainSmokeBenchmark` 성공.
  Docker PostgreSQL `postgres:18-alpine`에서 Flyway V1–V22를 적용하고 20,000건
  backlog를 seed한 뒤 실제 `JdbcAppointmentOutboxStore.claim`을 호출했다.
- `./gradlew :appointment-messaging-benchmark:mainBenchmark` 성공. 커밋한
  [baseline JSON](../benchmarks/appointment-messaging-postgresql-baseline.json)은
  이 full 실행의 raw JSON에서 수집했다. main 수치는 `p50=0.0017830891`,
  `p95=0.0018149702`, `p99=0.0018149702 ops/ms`다.
- `./gradlew :appointment-messaging-benchmark:test --tests '*BenchmarkReportContractTest'`
  2/2 통과; positive production report와 malformed/H2 report rejection을 확인했다.
- `./gradlew :appointment-messaging-benchmark:build` 성공. root Kover aggregate
  dry-run에서 `:appointment-messaging-benchmark:koverXmlReport`가 제외됨을 확인했다.
- `./gradlew :appointment-messaging:test` 66/66 통과.
- collector → validator → chart generator 실행 성공. JSON은 PostgreSQL marker,
  fixed seed `41`, rows `20,000`, score/p50/p95/p99, `deploymentSloEvidence=false`를
  보존한다.
- EN/KO SVG는 `xmllint`, PNG는 `identify`(1280x560), geometry audit는 각각
  `geometry_failures=0`; baseline JSON과 SVG 값 대조도 통과했다.
- `.github/workflows/ci.yml`, `.github/workflows/nightly.yml`는 `actionlint` 통과.
- `gitleaks detect --source . --redact --no-git --config .gitleaks.toml` 통과.
- `git diff --check` 통과.

## 남은 위험과 경계

1. 현재 개발 장비는 arm64 Docker에서 amd64 `postgres:18-alpine`을 emulation한다.
   따라서 local percentile은 재현성 근거이지 하드웨어 간 성능 보장이 아니다.
2. 결과는 bounded claim throughput만 측정한다. 운영 배포 SLO, lock-wait,
   connection saturation, Kafka catch-up, heap/thread, multi-worker contention은
   별도 운영 benchmark와 rollout gate가 필요하다.
3. nightly workflow는 actionlint와 local smoke/full equivalent로 정적·기능 경로를
   검증했지만 GitHub-hosted runner에서 실제 scheduled dispatch가 실행된 증거는
   PR CI 이후 수집해야 한다.
4. CI는 chart PNG 생성에 격리 Python venv와 CairoSVG 설치를 사용한다. registry 또는
   PyPI 장애 시 benchmark job만 실패하도록 artifact 경계를 유지한다.

## 판정

현재 코드 변경은 구현/문서/CI 범위에서 **READY FOR PR CI**다. P2 위험은 문서와
`deploymentSloEvidence=false` 계약으로 명시했으며, hosted CI/nightly 결과가 없는
상태에서 배포 성능 보장을 주장하지 않는다.

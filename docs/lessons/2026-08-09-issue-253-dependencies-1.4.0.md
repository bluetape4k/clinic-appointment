# Issue #253 의존성 1.4.0 전환 검증 lesson

## 결론

`bluetape4k-dependencies` 1.4.0 전환과 Timefold 2.4.0 단일 BOM 해석은 모듈별 검증을
통과했다. Redis 캐시는 Spring 논리 이름을 유지하면서 remote namespace를 `-v2`로 분리했고,
Kafka·Exposed·solver·benchmark 경로도 목표 버전으로 컴파일·실행됐다.

초기 전체 aggregate에서 `ProfileReevaluationConcurrencyIntegrationTest`의 lease 재선점
시나리오가 고정 `Thread.sleep(1_200)` 타이밍에 의존해 1건 실패했다. 저장소 코어 테스트와
동일하게 첫 claim 뒤 `leaseExpiresAt = Instant.EPOCH`을 DB transaction에서 명시하도록
통합 테스트를 안정화했고, 수정 후 대상 5건과 전체 API aggregate를 다시 통과시켰다.
범위 정리 전 aggregate는 704건 통과, 3건 skip이었지만 그 안에 분리된 #256·#257 테스트가
포함되어 있었다. 두 동작 변경을 최종 diff에서 제거한 뒤 현재 브랜치에서 다시 실행한 API
aggregate는 702건 통과, 3건 skip이며 실패·오류는 0건이다. PostgreSQL scheduling policy와
security 대상 회귀 10건 및 범위 정리 후 API 보안·정책 targeted 9건도 통과했다.

## resolved graph

| 좌표 | 기준선(`e790793a`) | 전환 후 | 근거 |
| --- | --- | --- | --- |
| `io.github.bluetape4k:bluetape4k-dependencies` | 1.3.1 | 1.4.0 | `gradle/libs.versions.toml:16` |
| `ai.timefold.solver:timefold-solver-core` | 2.2.0 | 2.4.0 | BOM `dependencyInsight` |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.0.3 | 3.1.0 | BOM `dependencyInsight` |
| `org.jetbrains.exposed:exposed-core` | 1.3.0 | 1.4.0 | BOM `dependencyInsight` |
| `org.jetbrains.exposed.plugin` | 1.3.1 | 1.4.0 | version catalog plugin entry |
| `org.apache.fory:fory-core`/`fory-kotlin` | 1.1.0/1.3.0 | 1.5.0/1.5.0 | BOM `dependencyInsight` |
| `io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce` | 0.4.0 | 0.5.0 | BOM `dependencyInsight` |
| `org.apache.kafka:kafka-clients` | 4.2.1 | 4.2.1 | BOM `dependencyInsight` |

별도 Timefold BOM, 직접 Timefold 버전, 직접 Springdoc 버전은 제거했다. Spring Boot·Kotlin·
Coroutines BOM은 서로 다른 좌표군의 권한이므로 유지했고, Maven BOM이 Gradle plugin 버전을
관리하지 못하는 Exposed plugin은 `1.4.0`을 명시했다. `bash scripts/verify-dependency-1.4.0.sh`
가 위 좌표와 금지된 이전 버전의 exact selected header를 검증해 통과했다.

## Redis cache wire와 복구 계약

- 기준 fixture는 `appointment-api/src/test/resources/cache/issue-253/`의 의사·장비·진료 유형
  3종이며, provenance에 기준 SHA, Fory 좌표, codec과 SHA-256을 고정했다.
- `NearCacheFixtureIntegrityTest`가 provenance와 파일 hash를 확인했고, cache suite는
  `SUCCESS: Executed 29 tests`였다.
- 임시 diagnostic에서 1.3.1 fixture 3종을 1.4.0 runtime이 모두 DTO로 복원했다
  (`SUCCESS: Executed 1 tests`). 반대로 새 payload를 1.3.1 classpath로 읽는 검증은
  수행하지 않았고, Fory 임의 버전의 역방향 binary compatibility를 계약으로 삼지 않는다.
- `NearCacheWireCompatibilityTest`는 서로 다른 Redis client/cache instance로 쓰고 읽으며
  `clinic-doctors-v2`, `clinic-equipments-v2`, `clinic-treatment-types-v2` raw key만 생성되고
  v1 key는 생성되지 않는지 확인한다.
- 따라서 논리 Spring cache 이름은 유지하고 remote namespace만 분리했다. 배포 전 v2 targeted
  `SCAN`/`UNLINK`, rollback 전 v1 targeted clear, TTL 관찰 절차는
  `docs/runbooks/dependency-1.4.0-cache-migration.md`에 고정했다.

## Solver 성능

동일 dataset·seed·time limit로 `:appointment-solver:test` 68건과 `BenchmarkTest`를 두 번
실행했다. baseline은 설계 문서의 기준값이며 production SLO가 아니다.

| 시나리오 | 기준 score/time | 1차 | 2차 |
| --- | --- | --- | --- |
| 소규모 | `0hard/0soft`, 5,027 ms | `0hard/0soft`, 5.0 s | `0hard/0soft`, 5.0 s |
| 중규모 | `0hard/-500soft`, 8,075 ms | `0hard/-500soft`, 8.2 s | `0hard/-500soft`, 8.2 s |
| 대규모 | `0hard/-2000soft`, 15,922 ms | `0hard/-2000soft`, 15.7 s | `0hard/-2000soft`, 15.7 s |

반복 25% 회귀는 관찰되지 않았다. README의 실제 selector는
`*solver.benchmark.BenchmarkTest`, 결과 경로는 `local/benchmark/`로 정정했다.

## 모듈별 검증

| 모듈/경로 | 실행 결과 |
| --- | --- |
| `appointment-core` | `:test` 684건 통과; `generateMigrations` 성공, migration 11건 생성 후 작업 산출물은 제거 |
| `appointment-event` | `:test` 187건 통과; `generateMigrations` 성공, migration 5건 생성 후 작업 산출물은 제거 |
| `appointment-solver` | `:test` 68건 통과; benchmark 3건 × 2회 통과 |
| `appointment-notification` | `:test` 133건 통과 |
| `appointment-messaging` | `:test` 113건 통과; Kafka 통합 대상 3건 통과 |
| `appointment-api` cache | cache/fixture/adapter 통합 29건 통과 |
| `appointment-api` PostgreSQL 대상 | scheduling dialect/security 10건 통과 |
| `appointment-messaging-benchmark` | `:test` 3건 통과; PostgreSQL smoke와 report collector/validator 통과 |

messaging benchmark smoke는 PostgreSQL 18.4 container에서 Flyway 25 migrations를 적용했고,
outbox `claimBatch` report는 `0.001673884714216692 ops/ms`(p50/p95/p99 동일),
`deploymentSloEvidence=false`로 수집됐다. consumer contention/lookup와 cleanup 측정도 raw
JMH report에 포함됐다. benchmark module의 API 역의존 제거는 #250 범위이므로 수행하지 않았다.

모든 non-frontend build task의 `--dry-run`은 132개 `:appointment-*` task를 확인했고
`:frontend:` 누수는 없었다. `build -x test --refresh-dependencies` aggregate는 2분 43초에
성공했으며, root `detekt`는 `NO-SOURCE` 성공이었다.

## 전체 API aggregate와 후속 조치

초기 전체 실행에서는 `ProfileReevaluationConcurrencyIntegrationTest.kt:366`의
`만료된 lease만 다른 worker가 같은 작업을 다시 선점한다()`가 고정 sleep 경계에서
`NoSuchElementException`으로 실패했다. 이 테스트는 코어 repository 테스트의 명시적 만료
fixture 패턴으로 바꾸어 wall-clock과 suite load에 의존하지 않게 했다.

| 검증 | 결과 |
| --- | --- |
| 수정 후 `*ProfileReevaluationConcurrencyIntegrationTest` | 5건 통과, 59초 |
| 범위 정리 후 `:appointment-api:test --rerun-tasks` fresh aggregate | `SUCCESS: Executed 702 tests in 4m 51s (3 skipped)`, `BUILD SUCCESSFUL in 5m 38s`; XML failures/errors 0/0 |
| 범위 정리 후 API 보안·정책 targeted | 9건 통과, `BUILD SUCCESSFUL` |

따라서 현재 로컬 검증 gate는 green이다. 다만 production Redis/PostgreSQL, GitHub CI,
push/PR/merge는 아직 실행하지 않았으므로 원격 전달 상태는 `PENDING`으로 유지한다.

## 보안과 운영 범위

`gh api repos/bluetape4k/clinic-appointment/dependabot/alerts` 기준 열린 경고는 npm
`ip-address` 3건과 `@hono/node-server` 1건뿐이며 모두 frontend manifest 범위다. 이를 근거로
전체 취약점이 없다고 주장하지 않으며, JVM dependency 전환과 무관한 것으로 분리했다.

production Redis, production PostgreSQL, GitHub CI, push/PR/merge는 실행하지 않았다. live GitHub
기준 #254(Leader/Micrometer), #255(bounded-wait conformance), #256(durable replay), #257(security
response isolation)는 모두 OPEN인 독립 이슈이며 이 PR은 해당 동작을 변경하지 않는다. 사용자의
현재 승인은 구현·검증 범위이며, 원격 변경과 merge 승인은 별도 게이트다.

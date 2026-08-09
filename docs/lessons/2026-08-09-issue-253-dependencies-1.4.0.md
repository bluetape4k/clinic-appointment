# Issue #253 의존성 1.4.0 전환 검증 lesson

## 결론

`bluetape4k-dependencies` 1.4.0 전환과 Timefold 2.4.0 단일 BOM 해석은 모듈별 검증을
통과했다. Redis 캐시는 Spring 논리 이름을 유지하면서 remote namespace를 `-v2`로 분리했고,
Kafka·Exposed·solver·benchmark 경로도 목표 버전으로 컴파일·실행됐다.

다만 `:appointment-api:test` 전체 704건에서 `ProfileReevaluationConcurrencyIntegrationTest`의
lease 재선점 시나리오가 1건 실패했다. 해당 클래스만 같은 환경에서 3회 재실행하면 모두
통과하므로 이번 의존성 전환의 직접 원인으로 단정하지 않았지만, 전체 aggregate가 green이
아니므로 이 delivery lane은 `PENDING`이다. PostgreSQL scheduling policy와 security 대상
회귀 10건은 통과했으며, #256·#257 수정 커밋을 이 worktree에 통합해 재검증했다.

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

전체 `:appointment-api:test --rerun-tasks`는 704건 중 1건 실패, 3건 skip이었다.
실패는 `ProfileReevaluationConcurrencyIntegrationTest.kt:366`의
`만료된 lease만 다른 worker가 같은 작업을 다시 선점한다()`에서 `List.single()`이 빈 목록을
받은 `NoSuchElementException`이다. 동일 클래스만 별도 실행한 3회는 각각 5건 통과했다.
따라서 #253 diff가 만든 dependency graph failure로 분류하지 않고, 전체 suite의 실행 순서·
격리 결함 후보로 남긴다. 이 항목을 별도 이슈로 추적하고 aggregate 재실행이 green인지 확인한
뒤에만 PR readiness를 갱신한다.

## 보안과 운영 범위

`gh api repos/bluetape4k/clinic-appointment/dependabot/alerts` 기준 열린 경고는 npm
`ip-address` 3건과 `@hono/node-server` 1건뿐이며 모두 frontend manifest 범위다. 이를 근거로
전체 취약점이 없다고 주장하지 않으며, JVM dependency 전환과 무관한 것으로 분리했다.

production Redis, production PostgreSQL, GitHub CI, push/PR/merge는 실행하지 않았다. 사용자의
현재 승인은 구현·검증 범위이며, 원격 변경과 merge 승인은 별도 게이트다.

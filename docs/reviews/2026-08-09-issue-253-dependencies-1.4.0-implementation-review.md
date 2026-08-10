# Issue #253 구현 7-tier review

## 검토 대상과 판정

대상은 `codex/issue-253-dependencies-1.4.0`의 `origin/develop...HEAD` 최종 diff이며,
`bluetape-kotlin-patterns`의 null safety·불변 fixture·singleton container·resource close·
예외 경계 규칙을 함께 적용했다. 범위를 벗어난 #256 durable replay와 #257 security context
격리 수정은 병합 전 추적성을 위해 최종 diff에서 제거했다.

| 최종 게이트 | 판정 |
| --- | --- |
| 의존성 diff 자체 P0 | 0 |
| 의존성 diff 자체 P1 | 0 |
| 전체 API aggregate | `PASS`: 범위 정리 후 702건 통과, 3건 skip; failures/errors 0/0 |
| production/CI/PR | 미실행 |

초기 aggregate의 lease 재선점 실패는 dependency coordinate나 production 경로가 아니라
고정 sleep에 의존한 테스트 fixture 문제로 확인했다. 첫 claim 뒤 `leaseExpiresAt`을 DB에서
과거로 바꾸도록 수정한 뒤 대상 테스트와 aggregate가 통과했다. 이후 #256·#257 동작 변경을
최종 diff에서 제거하고 API aggregate를 다시 실행해 702건 통과·3건 skip, failures/errors 0/0을
확인했으며, API 보안·정책 targeted 9건도 다시 통과했다.

## 1-tier 성능

- **확인:** `appointment-solver` 68 tests와 `BenchmarkTest` 3건을 같은 dataset/seed로 2회 실행했다.
- **증거:** 소 5.0 s, 중 8.2 s, 대 15.7 s가 두 실행에서 동일했고 baseline 대비 반복 25%
  악화가 없다. score는 각각 `0hard/0soft`, `0hard/-500soft`, `0hard/-2000soft`다.
- **확인:** messaging PostgreSQL smoke가 JMH report와 collector/validator를 통과했다.
- **리스크:** messaging smoke는 production SLO 증거가 아니며 report의 `deploymentSloEvidence`는
  `false`다.

## 2-tier 안정성

- `appointment-core` 684, `appointment-event` 187, `appointment-notification` 133,
  `appointment-messaging` 113 tests가 통과했다.
- Exposed `generateMigrations`는 core 11건/event 5건을 생성할 수 있었고, 생성 SQL은 이
  dependency lane의 소유 산출물이 아니므로 제거했다.
- cache suite 29건은 fixture integrity, adapter, 서로 다른 Redis client round-trip을 통과했다.
- 1.3.1 fixture 3종을 1.4.0 runtime으로 복원하는 임시 diagnostic도 1건 통과했다. 새
  payload를 구 classpath로 읽는 역방향 검증은 하지 않았고, namespace 분리로 그 경로를
  운영 계약에서 제거했다.
- `ProfileReevaluationConcurrencyIntegrationTest`의 lease 만료 fixture는 고정 sleep 대신
  `ProfileReevaluationJobs.update`로 `Instant.EPOCH`을 기록해 DB 시간과 suite load에 대한
  타이밍 의존을 제거했다. 수정 후 해당 클래스 5건과 범위 정리 후 API aggregate 702건이
  통과했다.

## 3-tier 보안

- `CacheConfig`는 외부 입력을 codec에 전달하는 새 API를 만들지 않고 기존 DTO와 singleton
  Redis launcher만 사용한다.
- Redis raw-key 테스트는 v2 exact key만 허용하고 v1 key 생성을 금지한다.
- runbook은 `KEYS`/`FLUSHALL`/glob `DEL`을 금지하고 cursor `SCAN` + exact-key `UNLINK`만
  허용한다.
- Dependabot 열린 경고 4건은 npm frontend manifest뿐이다. 이것을 JVM 전체 무취약성으로
  확대 해석하지 않았다.
- JWT request-end context cleanup은 #257의 독립 작업으로 분리했으며, 이 dependency PR은
  해당 production 동작을 변경하지 않는다. 범위 정리 후 JWT filter·정책 targeted 9건이 통과했다.

## 4-tier 운영·복구

- 논리 Spring cache 이름(`clinic-doctors`, `clinic-equipments`, `clinic-treatment-types`)은
  유지하고 remote prefix만 `-v2`로 바꿔 호출자 계약을 보존했다.
- 배포 전 v2 clear, rollback 전 v1 clear, TTL 관찰 후 v1 정리 순서가
  `docs/runbooks/dependency-1.4.0-cache-migration.md`에 있다. rollback에서 v1을 비우는 것은
  stale v1 payload 재노출을 막기 위한 것이다.
- `redisClient`, cache, raw connection과 명시적 cleanup action은 Kotlin `finally` 경계에서
  역순으로 닫고 cleanup 실패를 원래 예외에 suppressed로 붙인다.
- production Redis/rollback drill은 실행하지 않았으므로 운영 readiness는 local contract
  수준이다.

## 5-tier 개발자/API와 의존성 권한

- `gradle/libs.versions.toml:16,28,58`에서 BOM 1.4.0과 Exposed plugin 1.4.0을 명시한다.
- `build.gradle.kts:177-183`은 bluetape4k/Spring Boot/Kotlin/Coroutines BOM만 import한다.
  별도 Timefold BOM과 Springdoc/Timefold 직접 version은 제거했다.
- `scripts/verify-dependency-1.4.0.sh`는 각 `group:artifact`의 exact selected header와 금지
  이전 버전을 별도 출력 파일에서 검사한다.
- README selector는 실제 `BenchmarkTest`와 `local/benchmark/` 경로를 가리킨다.

## 6-tier 사용자·호출자 계약

- cache manager가 등록하는 logical name은 변경하지 않아 API service의 `@Cacheable` 호출자와
  Spring Cache key 계약을 보존한다.
- remote v2 분리는 rolling deployment에서 새 Fory payload를 구 binary가 읽는 경로를
  차단한다. Fory 임의 버전의 reverse decode 성공은 보장된다고 주장하지 않는다.
- solver README의 실행 명령은 존재하지 않는 class selector를 제거하고 실제 benchmark class를
  사용한다.
- Exposed identifier 대소문자 차이는 1.4.0 schema metadata의 case-normalized assertion으로
  좁게 보정했으며 schema/table 이름을 변경하지 않았다.

## 7-tier 통합·테스트·검증

- `bash scripts/verify-dependency-1.4.0.sh`: 목표 graph contract PASS.
- non-frontend build dry-run: 132 module task lines, `FRONTEND_TASK_LEAK=false`.
- non-frontend `build -x test --refresh-dependencies`: 2m43s, `BUILD SUCCESSFUL`.
- Kafka integration 대상 3건, PostgreSQL scheduling dialect/security 10건, messaging benchmark
  report test 3건 및 Node collector test 2건 통과.
- `git diff --check origin/develop...HEAD`: PASS; 금지된 2.2.0/3.0.3 alias 검색 결과 없음.
- root `detekt`: `NO-SOURCE`, `BUILD SUCCESSFUL`.
- 범위 정리 후 `:appointment-api:test --rerun-tasks`: `SUCCESS: Executed 702 tests in 4m 51s
  (3 skipped)`, `BUILD SUCCESSFUL in 5m 38s`; XML aggregate failures/errors `0/0`.
- 독립 code review: 최신 `origin/develop...HEAD` 기준 P0/P1/P2 `0/0/0`, 판정 `PASS`.
- Issue #254(Leader/Micrometer)와 #255(bounded-wait conformance)는 live GitHub에서 OPEN이고,
  #253 diff에는 해당 observability/API conformance 변경이 없다. #256 durable replay와 #257
  security 응답 격리도 각각 OPEN인 독립 이슈이며 이 PR의 최종 diff에서 제외했다.
- PR head/CI/review thread는 push 권한과 별도 승인 전이므로 확인하지 않았다.

## 결론과 unchecked 항목

의존성 좌표 정렬, cache namespace 경계, solver 성능, Exposed/Kafka/benchmark 통합과 수정 후
전체 API aggregate에서 P0/P1 diff blocker는 발견하지 않았다. production·CI·원격 전달 게이트가
아직 실행되지 않았으므로 이 구현은 `PENDING`이다.

- [x] `ProfileReevaluationConcurrencyIntegrationTest` lease 만료 fixture의 wall-clock 의존 제거
- [ ] PR 생성 후 exact head, required CI, unresolved review thread 확인
- [ ] production Redis/PostgreSQL SLO와 rollback drill
- [ ] 별도 승인 후 push/PR/merge

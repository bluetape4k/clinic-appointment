# Issue #308 PostgreSQL 단일 코어 계약 구현 계획

> 구현 전제: Type-A Slice A 설계와 계획을 사용자가 승인했다. 이 문서는
> `chore/issue-308-postgresql-core-contract` worktree에서만 실행한다.

## 목표

`appointment-core`, `appointment-event`, `appointment-messaging`의 production SQL 경로를 PostgreSQL로 단일화하고, H2를 순수 단위·배선 fixture로 한정한다. API datasource/profile/Flyway/migration과 README는 Slice B의 책임으로 남긴다.

## 순서와 공통 검증

1. RED test와 source inventory를 먼저 고정한다.
2. production branch를 최소 diff로 제거한다.
3. 모듈별 compile → H2 unit → PostgreSQL Testcontainers test를 순서대로 실행한다.
4. `git diff --check`, Kotlin final checklist, review/lesson을 통과한 뒤 commit/PR 단계로 이동한다.

모든 Testcontainers 명령은 순차 실행한다. macOS에서는 관리된 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`를 상속하고, 실패 시 Colima/Docker 상태를 먼저 분류한다. 테스트를 skip하거나 retry-only PASS로 처리하지 않는다.

## 파일 소유권과 작업 항목

### Task 1 — RED 기준선과 지원 범위 inventory

**파일**

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/TestDB.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/Containers.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt`
- 새 테스트가 필요하면 동일한 core/event/messaging test source 아래에 추가한다.

**작업**

- 현재 `TestDB` entry와 launcher를 표로 고정하고, MySQL/MariaDB/Cockroach/PostgreSQL R2DBC 변형이 더 이상 지원되지 않아야 한다는 failing assertion을 먼저 작성한다.
- waitlist strategy test에서 PostgreSQL은 `LOCKED_SELECTION`, `FOR UPDATE`, `SET LOCAL lock_timeout = '2s'`를 검증하고 H2/MySQL strategy assertion을 제거할 예정임을 RED 상태로 기록한다.
- `rg`로 Slice A production source의 `h2`, `mysql`, `mariadb`, `cockroach`, `currentDialect` 분기 목록을 저장한다.

**검증**

```bash
./gradlew :appointment-core:test --tests '*WaitlistDeliveryRepositoryTest*' --no-build-cache
```

기존 test가 통과하면 전략 제거를 검증하지 못하는 것이므로 assertion을 먼저 강화해 실패하는지 확인한다.

### Task 2 — TestDB와 PostgreSQL singleton 정리

**파일**

- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/TestDB.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/Containers.kt`
- `appointment-core/build.gradle.kts`
- 필요 시 `WithTables.kt`와 TestDB 참조 test

**작업**

- enum을 `H2`, `H2_COMMITMENT`, `POSTGRESQL`로 축소하고 기본 enabled set을 `H2, POSTGRESQL`로 바꾼다.
- `ALL_H2`, `ALL_POSTGRES`, `ALL` 등 남는 companion set만 유지하고 MySQL/MariaDB/Cockroach 전용 set은 삭제한다.
- PostgreSQL URL은 `PostgreSQLServer.Launcher.postgres`를 사용하고 UTC session setup을 유지한다.
- MySQL/MariaDB/Cockroach driver/container test dependency와 참조를 core에서 제거한다. API가 아직 사용하는 catalog alias는 Slice B 전까지 남긴다.
- H2 fixture 설명을 “빠른 순수 단위·배선 테스트”로 고치고 migration/lock/dialect claim을 제거한다.

**검증**

```bash
./gradlew :appointment-core:test --tests '*TestDB*' --tests '*TableSchemaTest*' --no-build-cache
./gradlew :appointment-core:compileTestKotlin --no-build-cache
```

### Task 3 — Core production dialect branch 단일화

**파일**

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyRepository.kt`

**작업**

- H2 선조회·예외 무시 경쟁 경로를 제거하고 `insertIgnore`를 사용한다.
- resource mutex는 PostgreSQL `insertIgnore` 후 공통 `.forUpdate()`를 사용한다. MySQL `NO_WAIT` 분기와 MySQL error code는 제거하고 PostgreSQL lock unavailable 상태만 분류한다.
- 기존 public repository method, transaction 경계, validation, 반환 상태는 바꾸지 않는다.
- production source에 `isH2Dialect`와 vendor 선택 분기가 남지 않았는지 확인한다.

**검증**

```bash
./gradlew :appointment-core:test --tests '*ProfileReevaluationRepositoryTest*' --tests '*ResourceAllocationRepositoryTest*' --tests '*SchedulingPolicyRepositoryTest*' --no-build-cache
```

H2 테스트가 SQL capability를 요구해 실패하면 production branch를 복원하지 않고 해당 테스트를 PostgreSQL parameter/source로 옮긴다.

### Task 4 — Waitlist claim을 PostgreSQL strategy로 단일화

**파일**

- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/waitlist/WaitlistDeliveryRepositoryTest.kt`
- 관련 waitlist contention/claim tests

**작업**

- `VacancyClaimDialect`에서 H2/MySQL을 제거하고 PostgreSQL만 남긴다.
- `VacancyClaimStrategies.current()`와 `forDialectName()`은 PostgreSQL 전략만 반환한다. `forDialectName()`에 PostgreSQL alias가 아닌 값이 들어오면 명확한 `IllegalArgumentException`을 던진다. claim/lock을 호출하는 기존 H2 fixture는 `TestDB.POSTGRESQL`로 옮기고, H2는 lock을 호출하지 않는 unit/wiring test에만 남긴다.
- MySQL session variable lock timeout, MySQL error code, H2 version-update strategy를 삭제한다.
- retry와 duplicate classifier에서 MySQL SQL state/error code를 삭제하고 PostgreSQL `40001`, `40P01`, `23505`와 authority 이름을 유지한다.
- test는 PostgreSQL strategy의 SQL/timeout contract와 unsupported backend 부재만 검증한다.

**검증**

```bash
./gradlew :appointment-core:test --tests '*WaitlistDeliveryRepositoryTest*' --tests '*Waitlist*Claim*' --no-build-cache
```

그 다음 Docker가 활성화된 상태에서 PostgreSQL singleton을 사용하는 waitlist contention/unique test를 단독 실행한다.

### Task 5 — Event와 messaging production branch 정리

**파일**

- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt`
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt`
- `appointment-event/build.gradle.kts`
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt`
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStoreTest.kt`
- `appointment-messaging/src/test/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentMessagingReadinessValidatorTest.kt`는 H2 wiring 범위만 유지한다.

**작업**

- scheduling event cast를 PostgreSQL `VARCHAR`로 단일화한다.
- notification upsert key는 PostgreSQL unique key를 항상 사용하도록 바꾸고 MySQL/MariaDB dialect import를 삭제한다.
- outbox claim lock option은 PostgreSQL `SKIP_LOCKED`만 사용한다. `currentDialect`, MySQL/MariaDB imports와 H2 skip comment를 삭제한다.
- 소스 참조가 없는 `r2dbc-h2` test dependency를 제거한다. JDBC H2 unit fixture와 PostgreSQL launcher test는 유지한다.

**검증**

```bash
./gradlew :appointment-event:test --tests '*SchedulingEventRepositoryTest*' --tests '*NotificationOutboxRepositoryTest*' --no-build-cache
./gradlew :appointment-messaging:test --tests '*AppointmentOutboxStoreTest*' --tests '*AppointmentOutboxRelayTest*' --no-build-cache
```

### Task 6 — PostgreSQL Testcontainers contract evidence

**파일**

- 기존 PostgreSQL fixture가 있는 `appointment-core`, `appointment-event`, `appointment-messaging` test source
- 필요한 경우 새 `*PostgreSQLIntegrationTest.kt`를 각 모듈에 추가한다.

**작업**

- raw `GenericContainer`를 만들지 않고 `PostgreSQLServer.Launcher.postgres`를 사용한다.
- lock/unique/outbox claim 증거가 H2 test 이름이나 H2 plan으로 오인되지 않도록 test class/KDoc를 PostgreSQL 명칭으로 고정한다.
- 핵심 경로는 waitlist claim 하나 성공, duplicate idempotency 하나만 권위 row를 남김, outbox claim이 `SKIP_LOCKED`로 predecessor/lease fence를 유지함을 검증한다.
- Testcontainers test는 순차 실행하며, container failure는 test skip이 아니라 blocker evidence로 남긴다.

**검증**

```bash
colima status
docker context show
docker info
./gradlew :appointment-core:test --tests '*PostgreSQL*' --no-build-cache --no-parallel
./gradlew :appointment-event:test --tests '*PostgreSQL*' --no-build-cache --no-parallel
./gradlew :appointment-messaging:test --tests '*PostgreSQL*' --no-build-cache --no-parallel
```

### Task 7 — 모듈 compile·정적 검증과 계약 grep

**작업**

- 변경된 Kotlin 파일에 `!!`, suspend `runCatching`, swallowed cancellation, blocking event-loop 호출을 새로 추가하지 않았는지 확인한다.
- Exposed transaction 경계와 deprecated import를 검토한다.
- Slice A production source에 `MySQL`, `MariaDB`, `Cockroach`, `H2_DIALECT`, `isH2Dialect`, `currentDialect`가 남지 않았는지 확인한다. test source의 H2는 허용하되 migration/locking/dialect claim을 찾으면 수정한다.

**검증**

```bash
./gradlew :appointment-core:compileKotlin :appointment-event:compileKotlin :appointment-messaging:compileKotlin --no-build-cache
./gradlew :appointment-core:test :appointment-event:test :appointment-messaging:test --no-build-cache --no-parallel
git diff --check
```

### Task 8 — 문서·review·lesson과 commit/PR handoff

**파일**

- `docs/superpowers/specs/2026-08-17-issue-308-postgresql-core-contract-design.md`
- `docs/superpowers/plans/2026-08-17-issue-308-postgresql-core-contract-plan.md`
- `docs/review/2026-08-17-issue-308-postgresql-core-contract-step-3r-plan-review.md`
- `docs/lessons/2026-08-17-issue-308-postgresql-core-contract.md`

**작업**

- plan review에서 spec-to-task mapping, test order, rollback, H2 N/A, PostgreSQL Testcontainers stability를 확인한다. P0/P1은 0이어야 한다.
- lesson에는 H2를 무조건 삭제하지 않고 주장을 분리한 결정, PostgreSQL singleton 증거, 남은 Slice B/API 범위와 운영 배포 N/A를 기록한다.
- Lore commit protocol을 사용한 Korean commit을 만들고, branch를 push해 PR을 생성한다. PR body는 Issue #308 링크, Slice A 경계, 검증 command/result, `## DoD Status`와 `N/A`를 포함한다.
- PR CI가 성공한 뒤에만 fresh merge approval을 요청한다. merge는 승인 후 rebase 방식으로 수행하고, root `develop`을 원격과 동기화한 뒤 worktree/branch를 정리한다.

**검증**

```bash
git diff --check
git status --short --branch
git log -1 --format=fuller
gh pr view <number> --json headRefName,baseRefName,statusCheckRollup,mergeStateStatus,body
```

## 계획 검토 결과

- **성능:** PostgreSQL Testcontainers lock/claim test는 순차 실행하며, 불필요한 DB matrix를 제거해 실행량을 줄인다. 별도 benchmark 수치는 이 계약 변경의 acceptance가 아니므로 N/A다.
- **안정성:** singleton launcher, no-parallel, clean test, Colima 상태 확인을 고정한다. lock timeout SQL 복원 누락을 제거한다.
- **보안:** MySQL session variable과 raw dialect 분기를 제거하고, 기존 opaque ID/parameterized SQL을 유지한다. 새로운 인증 경계는 없다.
- **운영:** API/Flyway/배포는 Slice B 또는 별도 운영 범위다. 이 Slice는 source/test contract 증거만 제출한다.
- **개발자/API:** public repository signature와 domain 결과는 유지하고, unsupported `-PuseDB` 값만 의도적으로 제거한다.
- **호출자:** README는 Slice B에서 갱신한다. Slice A PR body에는 API 문서가 아직 PostgreSQL default를 설명하지 않는다는 사실을 명시한다.

판정: `P0=0`, `P1=0`. 구현 중 source evidence가 계획과 달라지면 해당 task를 중단하고 plan을 먼저 갱신한다.

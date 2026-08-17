# Issue #308 PostgreSQL 단일 코어 계약 설계

상태: 설계 승인 완료
작성일: 2026-08-17
대상: `clinic-appointment` Slice A (`appointment-core`, `appointment-event`, `appointment-messaging`)
관련 이슈: [#308](https://github.com/bluetape4k/clinic-appointment/issues/308)
기준 커밋: `260a40973f5df30baa5209a2e0e99155bf8e812f`

## 1. 결정 요약

Issue #308의 첫 번째 구현 슬라이스는 코어·이벤트·메시징 모듈이 PostgreSQL을 유일한 production SQL 계약으로 사용하도록 정리한다. H2는 빠른 순수 단위·배선 테스트에만 남기며, migration·locking·SQL dialect 동등성 증거를 제공하는 테스트에는 사용하지 않는다.

다음 네 가지 결정을 적용한다.

1. `appointment-core`의 `TestDB`를 `H2`, `H2_COMMITMENT`, `POSTGRESQL`로 축소한다. 기본 matrix는 `H2, POSTGRESQL`이며 `-PuseFastDB=true`는 H2만 실행한다. MySQL, MariaDB, CockroachDB, PostgreSQL R2DBC 변형과 해당 launcher를 제거한다.
2. production repository의 H2/MySQL 경쟁·lock 분기를 제거하고 Exposed의 공통 insert/`FOR UPDATE` 경로를 PostgreSQL 계약으로 사용한다. H2 fixture가 남더라도 production 코드가 H2 semantics를 별도 보장한다고 문서화하지 않는다.
3. waitlist claim과 messaging outbox claim은 PostgreSQL `FOR UPDATE`/`SKIP LOCKED` 경계를 사용한다. MySQL lock timeout session 변수와 MariaDB fallback은 삭제하고, command duplicate 및 contention 분류는 PostgreSQL SQL state를 기준으로 유지한다.
4. `appointment-event`의 MySQL cast/upsert 분기와 `appointment-messaging`의 MySQL/MariaDB dialect import를 제거한다. `r2dbc-h2`는 소스 사용이 없어 test dependency에서 제거한다. migration generator의 H2 URL은 build-time schema generation 도구이며 runtime datasource·Flyway 계약과 분리해 이번 슬라이스에서는 유지한다.

Slice A는 API datasource/profile/Flyway/migration directory와 README를 변경하지 않는다. API 변경은 Slice A가 merge된 `develop`에서 별도 Slice B로 수행한다.

## 2. 문제와 근거

현재 저장소는 실제 운영 계약과 테스트 계약이 여러 DB로 분산되어 있다.

| 근거 | 현재 상태 | 설계 결정 |
|---|---|---|
| `appointment-core/src/test/.../TestDB.kt` | H2 변형, MySQL/MariaDB, PostgreSQL R2DBC, CockroachDB가 모두 등록되고 기본값이 `H2, POSTGRESQL, MYSQL_V8`이다. | `H2`, `H2_COMMITMENT`, `POSTGRESQL`만 유지하고 기본값을 `H2, POSTGRESQL`로 고정한다. |
| `appointment-core/src/test/.../Containers.kt` | MariaDB, MySQL 5/8, CockroachDB launcher를 직접 시작한다. | PostgreSQL singleton launcher만 유지한다. |
| `ProfileReevaluationRepository.kt`, `ResourceAllocationRepository.kt`, `SchedulingPolicyRepository.kt` | H2에서 선조회 후 삽입하고, 다른 DB에서는 `insertIgnore`를 사용하는 경쟁 분기가 있다. | `insertIgnore`와 공통 lock 경로를 모든 production 호출에 적용한다. |
| `WaitlistDeliveryRepository.kt` | H2 version update, PostgreSQL lock, MySQL session lock timeout 전략을 모두 노출한다. | PostgreSQL locked selection 전략만 production 전략으로 남긴다. |
| `SchedulingEventRepository.kt`, `NotificationOutboxRepository.kt` | MySQL cast/upsert 키 분기가 존재한다. | PostgreSQL 표현과 idempotency unique key를 단일 경로로 사용한다. |
| `AppointmentOutboxStore.kt` | PostgreSQL/MySQL/MariaDB별 `ForUpdateOption`을 선택한다. | PostgreSQL `SKIP_LOCKED`만 사용한다. |
| `appointment-event/build.gradle.kts` | 소스 사용이 없는 `r2dbc-h2` test dependency가 남아 있다. | dependency를 제거하고 H2 JDBC unit fixture만 유지한다. |

이 근거는 현재 worktree의 source와 build 파일에서 확인했다. 외부 운영 배포, canary, SLO, 실제 고객 트래픽 증거는 이 예제 서비스의 현재 acceptance 범위가 아니며 이 설계의 완료 조건으로 사용하지 않는다.

## 3. 목표와 비목표

### 목표

- 코어·이벤트·메시징 production 경로의 supported database를 PostgreSQL 하나로 명확히 한다.
- 실제 동시성·unique·outbox claim 검증은 PostgreSQL Testcontainers singleton launcher로 수행할 수 있는 구조를 유지한다.
- H2가 남는 테스트가 migration, lock, SQL dialect 동등성 증거로 오인되지 않도록 이름·주석·matrix를 정리한다.
- MySQL/MariaDB/Cockroach 전용 dependency, launcher, SQL 분기를 Slice A 범위에서 제거한다.
- 기존 repository public API, 상태·lease fence·idempotency 의미를 바꾸지 않는다.

### 비목표

- `appointment-api`의 default datasource, Spring profile, Flyway migration directory, API integration base, README 수정은 Slice B에서 한다.
- MySQL 데이터 이관, backward compatibility, 외부 운영 배포·canary·SLO 증거는 다루지 않는다.
- H2를 모든 테스트에서 제거하지 않는다. H2는 network/container 없이 실행 가능한 pure unit 또는 Spring wiring fixture로 남긴다.
- 새로운 database abstraction이나 dependency를 추가하지 않는다.

## 4. 생산·테스트 계약

### 4.1 Production 계약

production repository는 현재 transaction이 PostgreSQL에 연결되어 있다는 전제만 가진다. `TransactionManager.current().db.dialect.name`으로 H2/MySQL/MariaDB를 선택하는 코드를 추가하지 않으며, 이미 존재하는 전용 분기는 삭제한다.

- 최초 head/mutex/snapshot 삽입은 `insertIgnore`로 처리한다.
- row lock은 Exposed 공통 `.forUpdate()` 또는 PostgreSQL `SKIP_LOCKED` 옵션을 사용한다.
- lock contention 재시도는 PostgreSQL serializable/deadlock SQL state(`40001`, `40P01`)와 PostgreSQL lock timeout을 기준으로 한다.
- command idempotency unique violation은 PostgreSQL SQL state `23505`와 authority 이름으로만 판정한다.
- PostgreSQL `CAST(... AS VARCHAR)`, composite idempotency key, `SET LOCAL lock_timeout` 외의 vendor SQL은 지원 계약에 포함하지 않는다.

### 4.2 Test 계약

| 테스트 종류 | 허용 DB | 주장할 수 있는 내용 |
|---|---|---|
| 순수 model/validation/wiring | H2 | 상태, 입력 검증, bean wiring, 일반 repository 흐름 |
| Exposed SQL dialect·unique·lock·outbox claim | PostgreSQL singleton launcher | production SQL과 동시성 계약 |
| migration/Flyway | Slice B의 PostgreSQL migration test | 실제 PostgreSQL schema와 version history |
| MySQL/MariaDB/Cockroach matrix | 없음 | 지원하지 않으므로 test task와 dependency를 만들지 않음 |

H2 테스트 파일의 이름과 KDoc에서 `dialect`, `locking`, `migration`, `performance evidence`라는 표현을 사용하지 않는다. 기존 H2 concurrency/performance test가 실제 DB lock이나 SQL plan을 주장하면 PostgreSQL fixture로 옮기거나, 순수 알고리즘 fixture임을 명시해 범위를 낮춘다.

## 5. 변경 경계와 파일 소유권

### 수정 대상

- `appointment-core/build.gradle.kts`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/TestDB.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/Containers.kt`
- `appointment-core/src/test/kotlin/io/bluetape4k/clinic/appointment/test/WithTables.kt`의 DB 이름 판정이 필요한 경우
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ProfileReevaluationRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/ResourceAllocationRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/SchedulingPolicyRepository.kt`
- `appointment-core/src/main/kotlin/io/bluetape4k/clinic/appointment/repository/waitlist/WaitlistDeliveryRepository.kt`
- `appointment-event/build.gradle.kts`
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventRepository.kt`
- `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/notification/NotificationOutboxRepository.kt`
- `appointment-messaging/src/main/kotlin/io/bluetape4k/clinic/appointment/messaging/AppointmentOutboxStore.kt`
- 해당 production contract를 직접 검증하는 core/event/messaging test 및 fixture

### 제외 대상

- `appointment-api/**`, `appointment-notification/**`, `appointment-solver/**`의 runtime/profile/migration 변경
- `gradle/libs.versions.toml`의 alias 삭제. Slice B가 API의 MySQL/Flyway/Testcontainers 의존성을 제거한 뒤 미사용 alias를 함께 정리한다.
- `.github/workflows/**` 변경. PR #349에서 CI matrix는 이미 PostgreSQL 단일 실행으로 정리되었고, 이번 슬라이스는 source/test contract를 맞춘다.

## 6. 실패 모드와 복구

| 실패 모드 | 탐지 증거 | 대응 |
|---|---|---|
| H2에서 `insertIgnore` 또는 PostgreSQL lock option이 실행되지 않음 | targeted H2 test의 실제 SQL 예외 | production 분기를 되살리지 않고 해당 테스트를 non-dialect fixture로 낮추거나 PostgreSQL test로 이동한다. |
| PostgreSQL singleton launcher 또는 Docker context 불안정 | launcher 로그, connection 오류, `colima status`/`docker info` | 재시도만으로 PASS 처리하지 않고 인프라 오류로 분류한다. healthy Colima를 재시작하지 않는다. |
| MySQL 전용 test가 컴파일을 깨뜨림 | `rg`와 Gradle compile 오류 | Slice A 소유 범위의 test를 PostgreSQL assertion으로 교체하거나 삭제하고, API Slice B 대상은 문서에 남긴다. |
| Exposed dialect API 변경으로 `ForUpdateOption` compile 실패 | module compile 출력 | 현재 Exposed source/API에 맞춰 PostgreSQL 옵션만 수정하고, 임시 vendor fallback은 추가하지 않는다. |
| 테스트 fixture가 production dialect 증거를 과장함 | KDoc/test 이름 review | H2 fixture의 주장을 순수 unit/wiring으로 명시하고 PostgreSQL test를 추가한다. |

실패 시 transaction rollback과 worktree 상태를 보존한다. 이미 삭제한 파일은 Git history에서 복구할 수 있으며, branch를 재생성하지 않고 수정 commit을 추가한다.

## 7. 호환성과 롤백

- repository public method signature와 domain state/lease/idempotency 결과는 유지한다.
- 제거되는 것은 지원하지 않기로 한 backend branch와 test-only launcher뿐이다. MySQL/MariaDB/Cockroach를 지정하는 `-PuseDB` 값은 더 이상 유효하지 않으며, 이는 Issue #308의 의도된 breaking test contract다.
- rollback이 필요하면 Slice A branch를 merge하지 않고 PR을 닫는다. merge 후에는 각 slice의 revert commit으로 PostgreSQL 단일 contract 변경을 되돌릴 수 있다.
- Slice B는 Slice A merge 후 현재 `develop`에서 API profile/Flyway/migration을 PostgreSQL로 맞춘다. 두 slice 사이에 API migration directory를 삭제하지 않는다.

## 8. 승인 기준과 완료 조건

### 승인 기준

- [x] 사용자 승인: 현재 대화의 `승인`으로 Type-A Slice A 설계·계획을 승인했다.
- [x] worktree/branch: `chore/issue-308-postgresql-core-contract`가 `origin/develop`에서 분기되었다.
- [x] 설계 검토: 성능·안정성·보안·운영·개발자/API·호출자 관점에서 P0/P1이 없는지 확인한다.

### 완료 조건

- [ ] core/event/messaging compile과 targeted test가 통과한다.
- [ ] H2/MySQL/MariaDB/Cockroach production branch와 Slice A 전용 dependency/launcher가 제거된다.
- [ ] PostgreSQL Testcontainers singleton을 사용하는 lock/unique/outbox evidence가 통과한다.
- [ ] `git diff --check`가 통과하고 변경 경계 밖 파일이 없다.
- [ ] Korean lesson/review와 PR body에 실제 검증 결과·N/A·남은 Slice B 범위를 기록한다.

운영 배포·canary·SLO 증거는 이 example service 기준으로 N/A이며, 이를 이유로 Slice A를 막지 않는다.

## 9. 문서 품질 gate

- **SPW-01 PASS** — 독자(코어·이벤트·메시징 유지보수자), 목적(Issue #308 Slice A), 기준 commit, 실제 source/build 경로, exact issue URL과 미확정 범위를 고정했다.
- **SPW-02 PASS** — 경계, production/test contract, failure mode, compatibility, acceptance/DoD와 rollback을 포함했다.
- **SPW-03 PASS** — 한국어 기술 문체와 고정 용어(`PostgreSQL`, `H2`, `Testcontainers`, `lock`, `outbox`, `migration`)를 사용하고 code/API/command token을 보존했다. `references/korean-naturalness-checklist.md`의 KO-01~KO-06을 적용했다.
- **SPW-04 PASS** — `TestDB.kt`, `Containers.kt`, 네 core repository, event 두 repository, messaging outbox, 두 build 파일의 현재 구현과 주장 범위를 대조했다.
- **SPW-05 PASS** — 제목·표·목록·code span·링크를 다시 읽었고, Slice B와 운영 증거 N/A를 명시했다.

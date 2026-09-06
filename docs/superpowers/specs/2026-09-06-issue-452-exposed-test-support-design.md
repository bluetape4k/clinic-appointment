# Issue #452 Exposed JDBC 테스트 지원 위임 설계

## 승인된 범위

Issue [#452](https://github.com/bluetape4k/clinic-appointment/issues/452)의
승인된 계약을 구현 기준으로 삼는다. 이 저장소는 예제 애플리케이션이므로
중앙 `bluetape4k-dependencies` catalog를 기본으로 사용하되, 아직 중앙 BOM에
없는 테스트 지원 artifact는 이 저장소의 version catalog에서 재정의한다.

이번 변경의 목적은 `appointment-core` 테스트 공통 fixture가 직접 복제하고 있는
Exposed JDBC 연결 수명주기를 provider의 `JdbcTestDbFixture`에 위임하는 것이다.
애플리케이션의 테스트 DB 선택과 도메인 테이블 정리 규칙은 이 저장소의 계약이므로
provider의 legacy `TestDB` enum으로 바꾸지 않는다.

## 현재 문제와 근거

`appointment-core/src/test/.../WithDb.kt`는 세마포어, shutdown hook, 기본/임시
`Database` 생성, transaction interceptor, `currentTestDB` 보존을 직접 구현한다.
이 구현은 provider의 동일한 generic lifecycle과 중복되어 Exposed 변경 시 두 구현을
동시에 수정해야 한다.

provider PR [#822](https://github.com/bluetape4k/bluetape4k-exposed/pull/822)은
`JdbcTestDbFixture`, `jdbcTestDbFixture`, `withDb(fixture, ...)`를 제공한다.
현재 provider `origin/develop`의 다음 소스를 API 기준으로 사용한다.

- `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/JdbcTestDbFixture.kt`
- `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/JdbcFixtureExecution.kt`
- `exposed/jdbc-tests/src/main/kotlin/io/bluetape4k/exposed/tests/WithDB.kt`

다만 2026-09-06에 확인한 공개 `2.1.0-SNAPSHOT` 최신 timestamp
`2.1.0-20260905.212855-5`에는 위 adapter class가 없다. 따라서 구현 검증은
provider 저장소의 정확한 `origin/develop` archive에서 해당 모듈을 빌드한 뒤,
생성된 class directory를 임시 Gradle init script로 test classpath에 추가한다.
실제 PR에서는 향후 provider publish 결과를 별도 확인한다.

## 결정

### 1. 로컬 TestDB를 유지하고 provider fixture를 내부 adapter로 사용

`TestDB` enum의 `H2`, `H2_COMMITMENT`, `POSTGRESQL`, 연결 URL, driver,
`beforeConnection`, `afterConnection`, `afterTestFinished`, `dbConfig`는 그대로
유지한다. 특히 `H2_COMMITMENT`는 방문 commitment 테스트가 별도 H2 schema를
사용하는 도메인 계약이므로 provider legacy enum으로 대체하지 않는다.

각 enum 값은 다음 형태의 fixture를 lazy하게 가진다.

```kotlin
internal val fixture: JdbcTestDbFixture<TestDB> by lazy {
    jdbcTestDbFixture(
        key = this,
        createDatabase = { configure -> connect(configure) },
        onShutdown = afterTestFinished,
    )
}

val db: Database?
    get() = fixture.database
```

provider가 담당할 generic lifecycle은 다음과 같다.

- fixture별 공정 semaphore와 중첩 실행 방지
- 최초 database 초기화와 shutdown hook 등록
- configure가 있을 때 임시 database 생성과 transaction 종료 후 정리
- transaction `maxAttempts = 1` 및 provider JDBC interceptor
- baseline database 복원과 예외 시 suppressed exception 처리

로컬 `withDb`는 provider의 `withDb(testDB.fixture, configure, statement)`를
호출하는 얇은 호환 adapter만 둔다. 따라서 테스트 호출부의
`withDb(TestDB.X)` 계약은 보존하면서 generic lifecycle 코드는 제거한다.
저장소 전체 검색 결과 `currentTestDB`의 외부 사용처가 없으므로 로컬 transaction
scope와 interceptor를 함께 제거하고 provider의 `currentJdbcTestDbFixture`를
adapter 검증에 사용한다.

### 2. 로컬 도메인 정리는 보존

`withTables`의 다음 동작은 변경하지 않는다.

- `withTenantGroups`를 통한 tenant parent 자동 포함과 default tenant seed
- H2에서만 referential integrity를 일시 해제
- FK child-first `clearTestRows`와 H2 공유 waitlist child 목록
- schema 생성, statement 후 commit, finally 정리와 복구 transaction
- PostgreSQL에서는 정상적인 FK 제약을 유지

정리 복구 경로가 참조하는 `testDB.db`는 provider fixture의 현재 database
getter를 사용한다. 이 getter는 테스트 전용 source set에만 존재한다.

### 3. 의존성 경계

`gradle/libs.versions.toml`에 provider test-support version과
`io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests` alias를 추가하고,
`appointment-core/build.gradle.kts`에는 `testImplementation`만 추가한다.
`api`, `implementation`, runtime configuration에는 새 artifact를 넣지 않는다.
중앙 BOM의 기존 child alias 이름과 coordinate를 유지해 catalog 의미를
재정의하지 않는다.

## 거부한 대안

| 대안 | 거부 이유 |
| --- | --- |
| provider legacy `TestDB` enum 직접 사용 | 로컬 `H2_COMMITMENT` schema와 `POSTGRESQL` launcher 및 tenant 계약을 잃는다. |
| provider lifecycle을 로컬 `WithDb.kt`에 계속 복제 | PR #822의 generic lifecycle과 다시 drift한다. |
| `withTables`까지 provider cleanup helper로 교체 | waitlist child 순서, H2 referential integrity, tenant seed는 이 예제의 도메인 계약이다. |
| 중앙 BOM publish를 먼저 기다린 뒤 코드 변경 | 예제 저장소의 명시적 local override 정책과 맞지 않고 구현 검증을 지연시킨다. |

## 실패·예외 계약

다음 상황에서 기존 의미를 유지하거나 더 명확하게 실패해야 한다.

1. provider fixture가 동일 key의 중첩 실행을 거부하면 예외를 호출자에게 전달한다.
2. `configure`가 임시 database를 만들면 statement 종료 후 임시 연결을 닫고 baseline을 복원한다.
3. statement 또는 cleanup이 실패하면 cleanup 예외를 원래 예외의 suppressed 예외로
   남기고, 기존 `withTables`의 복구 transaction을 시도한다.
4. H2 cleanup 중 referential integrity 복원이 실패해도 `finally` 경계를 유지한다.
5. `afterTestFinished`는 provider shutdown hook에서 한 번 호출되며 테스트 종료 시
   등록 상태와 database 참조가 stale하게 남지 않아야 한다.

## 완료 기준

- [ ] `bluetape4k-exposed-jdbc-tests`가 `appointment-core` test classpath에만 있다.
- [ ] `TestDB`의 H2/H2_COMMITMENT/PostgreSQL 계약과 `withTables` domain cleanup이 보존된다.
- [ ] generic lifecycle의 로컬 semaphore/shutdown/configure/interceptor 복제가 제거된다.
- [ ] provider `origin/develop` exact source classpath에서 test compile과 H2
  targeted test가 통과한다.
- [ ] lockfile와 dependency verification metadata가 실제 resolved graph와 일치한다.
- [ ] runtime/production graph와 publication POM에 test-support artifact가 없다.
- [ ] PostgreSQL/Testcontainers 검증은 단독 실행으로 남기고, 공개 SNAPSHOT blocker를
  PR 보고에 명시한다.

## 검증 근거와 범위 제한

공개 SNAPSHOT metadata:
<https://central.sonatype.com/repository/maven-snapshots/io/github/bluetape4k/exposed/bluetape4k-exposed-jdbc-tests/2.1.0-SNAPSHOT/maven-metadata.xml>

SNAPSHOT JAR를 직접 확인한 결과 `JdbcTestDbFixture`, `JdbcFixtureExecution`,
`JdbcFixtureCleanup`이 없었다. 이 결과는 코드 설계 결함이 아니라 provider
publish 시점 차이이며, CI가 `2.1.0-SNAPSHOT`을 resolve하는 동안에는 PENDING
blocker다. provider가 PR #822 이후 artifact를 publish하면 동일한 non-heavy 명령과
PostgreSQL 단독 명령을 재실행한다.

## 문서 품질 기록

- `SPW-01`: 문제, 범위, 완료 기준을 명시했다.
- `SPW-02`: provider source/API와 공개 SNAPSHOT의 차이를 구분했다.
- `SPW-03`: 대안과 실패 경로를 표로 기록했다.
- `SPW-04`: 정확한 좌표, URL, 명령 토큰을 보존한다.
- `SPW-05`: 구현 후 한국어 용어 audit과 `git diff --check`를 실행한다.

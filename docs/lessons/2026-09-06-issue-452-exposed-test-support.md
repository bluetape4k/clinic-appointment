# Issue #452 Exposed JDBC 테스트 지원 위임 교훈

## 결정

예제 저장소의 테스트 지원 dependency는 중앙 catalog의 기본값을 따르되,
provider API가 중앙 release에 아직 포함되지 않았으면 repository-local version
override를 사용할 수 있다. 이번 변경은
`io.github.bluetape4k.exposed:bluetape4k-exposed-jdbc-tests:2.1.0-SNAPSHOT`을
`testImplementation`으로만 선언하고, 로컬 `TestDB`를 provider
`JdbcTestDbFixture`에 연결한다.

provider legacy `TestDB`를 직접 사용하지 않은 이유는 이 예제의
`H2_COMMITMENT` 별도 schema, PostgreSQL singleton launcher, tenant와 H2 cleanup
계약을 보존해야 하기 때문이다. provider에는 generic lifecycle만 위임하고
`withTables`의 도메인 정리는 계속 이 저장소가 소유한다.

저장소 전체 검색에서 로컬 `currentTestDB`의 외부 사용처가 없음을 확인했다.
따라서 해당 transaction scope와 전용 interceptor는 유지하지 않고 provider의
`currentJdbcTestDbFixture`로 adapter 위임 경계를 검증한다.

## 검증에서 얻은 사실

공개 SNAPSHOT metadata의 최신 artifact는
`2.1.0-20260905.212855-5`였고, 직접 받은 JAR에는
`JdbcTestDbFixture`, `JdbcFixtureExecution`, `JdbcFixtureCleanup`이 없었다.
따라서 Maven Central에 의존한 compile 검증을 성공으로 기록하면 안 된다.
provider `origin/develop` archive에서 빌드한 exact class directory를 임시 Gradle
init script로 test classpath에 추가한 검증이 PR 코드의 미래 API 호환성을
증명한다. 실제 CI는 provider publish 이후 재확인해야 한다.

이 exact source classpath로 adapter targeted test, `appointment-core` H2 전체 테스트,
PostgreSQL 전체 테스트와 Detekt가 통과했다. provider 저장소에서도 fixture 수명주기,
동시성, 실패 주입, cleanup을 다루는 30개 테스트가 통과했다.

## 재발 방지

- 의존성 alias를 추가할 때 `testImplementation`과 runtime/production graph를
  모두 확인한다.
- SNAPSHOT API 변경은 metadata timestamp와 JAR contents를 함께 확인한다.
- provider checkout의 stale working-tree HEAD를 사용하지 말고, 검증 대상 commit을
  `git archive origin/develop`로 고정한다.
- composite substitution은 provider 전체 graph와 소비자 lock을 함께 바꿀 수 있다.
  공개 SNAPSHOT graph를 유지한 채 exact API class만 검증 classpath에 추가한다.
- generic fixture lifecycle과 domain cleanup을 한 번에 교체하지 않는다. 전자는
  provider API가 소유하고, 후자는 예제의 H2/FK/tenant 계약으로 남긴다.
- PostgreSQL/Testcontainers 검증은 다른 작업과 동시 실행하지 않고 단독 명령으로
  남긴다.

## 문서 품질 기록

- `SPW-01`: 결정과 배경을 Issue #452 및 provider PR #822에 연결했다.
- `SPW-02`: 공개 artifact의 부재를 성공 근거로 오인하지 않도록 기록했다.
- `SPW-03`: legacy enum 직접 사용과 중앙 release 선행 대안을 거부한 이유를 남겼다.
- `SPW-04`: coordinate, timestamp, source archive 명령을 그대로 보존했다.
- `SPW-05`: 한국어 용어 audit과 diff 검사를 완료 조건으로 포함했다.

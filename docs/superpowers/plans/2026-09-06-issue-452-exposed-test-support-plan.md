# Issue #452 Exposed JDBC 테스트 지원 위임 실행 계획

이 계획은 승인된 Issue #452 설계를 실행하기 위한 순서다. provider의 공개
`2.1.0-SNAPSHOT` JAR가 PR #822 이전 상태이므로, 로컬 GREEN 검증에는
`bluetape4k-exposed` `origin/develop`의 exact archive에서 provider 모듈을 빌드하고,
그 class directory를 임시 Gradle init script로 test classpath에 추가한다.
provider publish나 외부 CI dispatch는 이 작업 범위에 포함하지 않는다.

## 작업 1: TDD 계약 고정

1. `TestDBFixtureAdapterTest`를 추가해 `withDb(TestDB.H2)` 안에서
   provider의 `currentJdbcTestDbFixture`, `TestDB.H2.fixture.database`,
   `TestDB.H2.db`가 같은 fixture database를 가리키는 계약을 표현한다.
2. 구현 전 현재 branch에서 테스트를 실행해 `fixture` accessor/provider alias가
   없다는 RED compile failure를 기록한다.
3. 구현 후 같은 테스트를 exact provider source classpath와 `-PuseFastDB=true`로
   재실행해 GREEN을 확인한다.

## 작업 2: test-scope dependency 추가

1. `gradle/libs.versions.toml`의 project-local versions에
   `bluetape4k-exposed-test-support = "2.1.0-SNAPSHOT"`을 추가한다.
2. 같은 파일에 기존 중앙 catalog child alias와 동일한 coordinate로
   `exposed-jdbc-tests` library alias를 추가한다.
3. `appointment-core/build.gradle.kts`에 `testImplementation(libs.exposed.jdbc.tests)`만
   추가한다.
4. runtime classpath와 production source set에 새 dependency가 유입되지 않는지
   configuration별 graph로 확인한다.

## 작업 3: generic lifecycle을 provider에 위임

1. `TestDB.kt`에 `JdbcTestDbFixture<TestDB>` lazy adapter를 추가하고 `db`를
   fixture database getter로 바꾼다.
2. 기존 `WithDb.kt`의 로컬 semaphore, shutdown hook, temporary config,
   `CurrentTestDBInterceptor`를 삭제한다.
3. 동일 파일 또는 별도 test helper에서 provider `withDb`를 import alias로 호출하고,
   기존 statement 인자를 유지한다. 외부 사용처가 없는 로컬 `currentTestDB` scope는
   provider fixture scope로 대체한다.
4. `WithTables.kt`는 domain cleanup만 유지하고 `testDB.db` getter와 함께 컴파일되는지
   확인한다. H2_COMMITMENT, tenant seed, child-first cleanup은 변경하지 않는다.

## 작업 4: RED/GREEN 및 non-heavy 검증

provider source를 임시 디렉터리에 정확히 추출한다.

```bash
provider_tmp=$(mktemp -d /tmp/clinic-appointment-452-provider.XXXXXX)
git -C /Users/debop/work/bluetape4k/bluetape4k-exposed archive origin/develop | tar -x -C "$provider_tmp"
```

archive에서 provider artifact와 class directory를 만들고, 다음 형태의 임시 init
script로 `appointment-core` test classpath에 연결한다.

```bash
./gradlew -p "$provider_tmp" :bluetape4k-exposed-jdbc-tests:jar \
  --dependency-verification lenient --no-daemon --no-configuration-cache

cat > /tmp/clinic-452-exact-source.init.gradle.kts <<EOF
allprojects {
    if (path == ":appointment-core") {
        dependencies {
            add("testImplementation", files("$provider_tmp/exposed/jdbc-tests/build/classes/kotlin/main"))
        }
    }
}
EOF

./gradlew :appointment-core:compileTestKotlin \
  --init-script /tmp/clinic-452-exact-source.init.gradle.kts \
  --no-daemon --no-configuration-cache

./gradlew :appointment-core:test \
  --tests "io.bluetape4k.clinic.appointment.test.TestDBFixtureAdapterTest" \
  -PuseFastDB=true --init-script /tmp/clinic-452-exact-source.init.gradle.kts \
  --no-daemon --no-configuration-cache --rerun-tasks

./gradlew :appointment-core:test \
  -PuseFastDB=true --init-script /tmp/clinic-452-exact-source.init.gradle.kts \
  --no-daemon --no-configuration-cache --rerun-tasks
```

composite substitution은 provider 전체 graph와 소비자 lock이 충돌할 수 있으므로
이 검증에는 사용하지 않는다. provider checkout의 working-tree HEAD도 사용하지 않는다.
검증 후에는 exact한 `provider_tmp`와 임시 init script만 제거한다.

## 작업 5: lock과 verification metadata

1. 공개 SNAPSHOT graph를 기준으로
   `./gradlew :appointment-core:dependencies --configuration testCompileClasspath
   --write-locks ...`를 실행한다.
2. 변경된 `appointment-core/gradle.lockfile`에 `bluetape4k-exposed-jdbc-tests`가
   test 구성으로만 기록되고, 기존 Exposed local override가 유지되는지 확인한다.
3. 공개 SNAPSHOT이 갱신된 뒤 실제 repository artifact의 SHA-256을
   `gradle/verification-metadata.xml`에 추가한다. 현재 stale SNAPSHOT을 위해
   광범위한 unrelated metadata를 선반영하지 않는다.
4. `scripts/verify-dependency-contract.sh`와 `git diff --check`를 실행한다.

## 작업 6: leakage 및 publication 경계

다음 graph를 저장하고 새 test-support coordinate가 없는지 확인한다.

```bash
./gradlew :appointment-core:dependencies --configuration runtimeClasspath \
  --no-daemon --no-configuration-cache
./gradlew :appointment-core:dependencies --configuration testRuntimeClasspath \
  --no-daemon --no-configuration-cache
```

필요하면 publication POM 생성/검사 task를 실행해 `testImplementation` artifact가
POM의 runtime 또는 compile dependency로 누출되지 않는다는 증거를 남긴다.

## 작업 7: 문서와 최종 검토

1. 구현 결과와 공개 SNAPSHOT blocker를
   `docs/lessons/2026-09-06-issue-452-exposed-test-support.md`에 기록한다.
2. 세 문서에 `bluetape-writer` SPW-01..05 점검을 적용하고
   `audit-korean-terms.mjs`를 실행한다.
3. `git diff --check`, `git status --short`, 변경 diff를 검토한다.
4. PostgreSQL/Testcontainers는 동시 실행하지 않는다. 부모 agent가 다른 issue
   작업을 멈춘 뒤 아래 명령을 단독으로 실행해야 한다.

```bash
./gradlew :appointment-core:test \
  -PuseDB=POSTGRESQL --init-script /tmp/clinic-452-exact-source.init.gradle.kts \
  --no-daemon --no-configuration-cache --rerun-tasks
```

PostgreSQL 단독 검증은 위 명령으로 실행하고 결과를 PR에 기록한다.

## 완료 판단

- 구현/문서/lock/verification metadata diff가 Issue #452 범위 안에 있다.
- targeted test와 H2 non-heavy test가 GREEN이다.
- runtime leakage가 없고, 의존성 계약 스크립트가 통과한다.
- PostgreSQL 단독 검증은 GREEN이며 provider publish 결과만 PENDING으로 명시되어 있다.
- commit, push, PR 생성은 부모 agent가 수행한다.

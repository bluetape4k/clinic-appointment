# Issue #361 — 전역 dependency locking과 verification metadata 교훈

## 배경

`clinic-appointment`의 Gradle dependency resolution을 선언 시점의 선택에만
맡기지 않고, 실제로 resolve 가능한 configuration 전체를 재현 가능한 lockfile과
SHA-256 verification metadata로 고정했다. 대상은 root, 모든 subproject,
`buildSrc`, benchmark, frontend Gradle project와 root consumer fixture configuration이다.
Redis 7.2/8.8 이미지 매트릭스와 추가 dependency upgrade는 이번 범위에 포함하지
않았다.

## 결정

- root `build.gradle.kts`와 `buildSrc/build.gradle.kts`에
  `lockAllConfigurations()`와 `LockMode.STRICT`를 적용했다.
- `gradle.properties`를 `org.gradle.dependency.verification=strict`로 전환하고,
  root `verifyDependencyGovernance`가 각 project의 `canBeResolved` configuration을
  project 경계 안에서 resolve하도록 구성했다. Gradle 9에서 root task가 다른 project
  configuration을 직접 resolve하면 unsafe cross-project resolution이 되므로 per-project
  task와 root aggregator를 사용했다.
- root·`settings-gradle.lockfile`·`buildSrc`·각 Gradle project의 lockfile 12개와
  `gradle/verification-metadata.xml`을 생성했다. metadata는 6,890행,
  SHA-256 artifact entry 1,655개이며 local path, `SNAPSHOT`, `mavenLocal`,
  `central-snapshots` 흔적이 없다.
- `scripts/verify-dependency-locking.sh`는 write flag 없이 strict governance와
  API/notification runtime·test `dependencyInsight`를 실행하고, root notification
  compile-only fixture의 문서화된 `lettuce-core:7.5.2.RELEASE` 예외도 확인한다.
  expected lockfile inventory는 실제 find 결과와 비교하며 Gradle 실패 시 원문 로그를
  그대로 출력한다.
- CI build job은 기존 version contract 다음에 locking contract를 실행하며,
  `permissions: contents: read`, 모든 checkout의 `persist-credentials: false`,
  20분 job timeout을 사용한다.

## 예상 밖의 실패와 원인

1. 초기 helper는 `gradle/verification-metadata.xml`이 없어 RED가 됐다. 생성 명령을
   CI나 읽기 helper에 섞지 않고, 명시적 clean-cache 갱신 명령으로만 metadata를 만들었다.
2. 모든 project configuration을 root task 하나에서 resolve하려 하자 Gradle 9.7이
   `:appointment-api:annotationProcessor`의 unsafe cross-project resolution을 거부했다.
   project별 task로 바꿔 configuration ownership을 지켰다.
3. locking 적용 뒤 CI compile-only build가
   `core selected <missing>, expected apiElements`로 실패했다. locking이 fixture
   configuration root에 잠긴 외부 모듈도 노출해 기존 `singleOrNull()`이 producer를
   찾지 못한 것이 원인이었다. `ProjectComponentIdentifier.projectPath`로 producer를
   선택하도록 보정했으며, locking 범위를 줄이지 않았다.
4. helper가 `dependencyInsight`의 stdout/stderr를 command substitution으로 버려
   실패 원인을 숨길 수 있었다. 기존 dependency contract helper와 동일하게 임시 로그를
   보존하고 실패 시 원문을 출력하도록 수정했다. lock entry 하나를 임시로 제거한
   회귀 검증에서 `Resolved ... is not part of the dependency lock state`가 CI-visible
   output에 남는 것을 확인한 뒤 원상 복구했다.

## 검증 증거

검증 대상은 현재 feature branch의 locking hardening·CI 보완·검증 helper·운영 문서
변경을 모두 포함한 최신 상태이며, 각 명령은 해당 worktree에서 새로 실행했다.

| 영역 | 명령 | 결과 |
|---|---|---|
| 기존 dependency 계약 | `bash scripts/verify-dependency-1.4.0.sh` | PASS |
| strict locking 계약 | `bash scripts/verify-dependency-locking.sh` | PASS, 5개 dependencyInsight |
| clean cache governance | `GRADLE_USER_HOME=$(mktemp -d) ./gradlew ... verifyDependencyGovernance` | PASS, 2분 37초 |
| clean cache helper | 같은 임시 `GRADLE_USER_HOME`의 helper | PASS, 29초 |
| standalone clean cache helper | 별도 `GRADLE_USER_HOME=$(mktemp -d)`의 helper | PASS, 2분 11초 |
| 모듈 테스트 | core 560, notification 162, solver 98, API 824(3 skip), messaging 125, benchmark 4 | 모두 BUILD SUCCESSFUL |
| CI compile-only | `./gradlew build -x test -x :frontend:appointment-frontend:build --parallel --refresh-dependencies --no-daemon` | PASS, 1분 41초 |
| 정적 분석 | `./gradlew detekt --parallel --no-daemon` | PASS, 1초 |
| consumer fixture regression | `./gradlew assertModuleConsumerFixtureApiVariants --no-configuration-cache --no-parallel` | PASS |
| 구조/문서 | `xmllint`, `actionlint`, `bash -n`, `git diff --check`, Korean terminology audit | 모두 PASS |

## 잔여 위험과 후속 조치

- PGP signature provenance는 trusted key 운영 주체가 합의되지 않아 이번 범위에서
  제외했다. owner는 `debop`이며 `1.4.0` release train 종료 전 재검토한다. SHA-256은
  출처 진위의 대체가 아니다.
- `settings.gradle.kts`의 Foojay plugin은 `pluginManagement` 선언만 있고 실제 적용되지
  않아 settings lockfile에는 `empty=incomingCatalogForLibs0`만 있다. 실제 적용 시
  settings plugin resolution lock·verification 증거를 같은 PR에 추가한다.
- clean-cache governance는 2분 37초, 같은 cache를 이어 쓴 helper는 29초였고, 별도
  clean cache에서 helper만 실행한 측정은 2분 11초였다. Issue #361에 성능 임계치가
  승인되지 않았으므로 20분 CI timeout과 현재 측정값을 기준으로 모듈 증가·cache miss
  시 재평가한다.
- root notification API consumer fixture는 compile-only root-BOM 경계에서
  `lettuce-core:7.5.2.RELEASE`를 선택한다. 실제 notification runtime/test의
  `7.6.0.RELEASE` 계약과 분리된 예외이며 helper가 양쪽 선택을 고정 검증한다. fixture가
  외부 소비자 계약을 더 정확히 대표해야 할 때 버전 정렬을 재검토한다.
- `buildSrc`는 strict lockfile과 root build lifecycle의 plugin/compile resolution으로
  보호되지만, root custom governance task가 buildSrc의 모든 resolvable configuration을
  직접 열거하지는 않는다. 새 buildSrc configuration을 추가하기 전 전용 검증 task와
  동일 metadata 경계를 설계한다.
- Redis 7.2/8.8 image matrix는 Redis 8 단일 launcher 계약과 분리된 후속 이슈로 남긴다.

## 미래 guard

새 dependency 또는 plugin을 추가할 때는 선언만 바꾸지 말고 clean `GRADLE_USER_HOME`에서
lockfile·verification metadata를 함께 생성한 뒤, 실제 configuration의
`dependencyInsight`, 공급처·checksum, diff, rollback을 리뷰한다. CI에서는 generated
파일을 쓰지 않으며, lock/metadata 누락과 checksum 불일치를 원문 오류와 함께 실패시킨다.

# Issue #361 — 전역 Gradle dependency locking과 verification metadata 설계

## 결정 상태

`SPEC REVIEW PENDING`

이 문서는 [Issue #361](https://github.com/bluetape4k/clinic-appointment/issues/361)의
구현 경계를 고정한다. 사용자가 승인한 A안인 Gradle 네이티브 전역 정책을 적용하며,
spec 검토가 끝나기 전에는 구현 파일을 수정하지 않는다.

## 문제와 목표

현재 저장소는 `gradle.lockfile`과 `gradle/verification-metadata.xml`을 관리하지
않으며 `gradle.properties`의
`org.gradle.dependency.verification=lenient`를 사용한다. 따라서
`bluetape4k-dependencies:1.4.0`과 `lettuce-core:7.6.0.RELEASE`를
`dependencyInsight`로 확인하더라도 모든 모듈·plugin·benchmark 경로의 선택 버전과
artifact 무결성을 저장소 기준으로 고정하지 못한다.

이번 변경의 목표는 다음과 같다.

- 모든 Gradle 프로젝트의 실제로 resolve 가능한 configuration에 dependency locking을
  적용하고 lockfile을 저장소에 커밋한다.
- root, subproject, `buildSrc`, plugin artifact를 포함하는 단일 verification metadata를
  SHA-256 기준으로 관리하고 로컬·CI에서 strict mode를 사용한다.
- `runtimeClasspath`, `testRuntimeClasspath`, API의 `gatling`, benchmark와 현재
  custom fixture configuration을 실제로 resolve하는 검증 task를 제공한다.
- 기존 `scripts/verify-dependency-1.4.0.sh`의 버전 계약과 API·notification의
  `lettuce-core` runtime/test `dependencyInsight` 계약을 lock/verification 검사와
  함께 CI에서 실행한다.
- lockfile·verification metadata 갱신, 리뷰·승인, 롤백 절차를 한국어 문서로 남긴다.

## 범위

### 포함

- `build.gradle.kts`의 전역 dependency locking 정책과 모든 resolvable configuration을
  순회하는 검증 task
- `buildSrc/build.gradle.kts`의 동일한 locking 정책
- `gradle.properties`의 strict dependency verification 설정
- 프로젝트별 `gradle.lockfile`과 root `gradle/verification-metadata.xml` 생성·검토
- `scripts/verify-dependency-locking.sh`와 `.github/workflows/ci.yml`의 필수 검증 단계
- `docs/maintenance/dependency-locking.md`의 운영 절차
- 테스트·benchmark·plugin resolution을 포함한 clean-cache 검증 기록

### 제외

- Redis 7.2/8.8 이미지 매트릭스 도입
- `lettuce-core`, Spring, BOM 또는 기타 외부 dependency의 버전 업그레이드
- API 기능, domain model, runtime 동작 변경
- unrelated dependency refresh·cleanup
- verification 예외를 늘리거나 `--write-locks`를 CI에서 실행하는 우회

## 현재 코드 근거

- `gradle.properties`는 현재 `org.gradle.dependency.verification=lenient`를 설정한다.
- `build.gradle.kts`의 `allprojects`는 `mavenCentral()`과 `central-snapshots` repository를 등록하고
  changing module cache를 조정하지만 dependency locking은 활성화하지 않는다.
- root build에는
  `appointmentCoreConsumerFixtureClasspath`,
  `appointmentMessagingConsumerFixtureClasspath`,
  `appointmentNotificationConsumerFixtureClasspath` custom configuration이 있다.
- `appointment-api/build.gradle.kts`에는 kotlinx-coroutines 버전을 조정하는
  `resolutionStrategy.eachDependency`가 있다. locking은 이 resolution rule과 공존해야
  하며 선택 결과를 변경하지 않는다.
- `scripts/verify-dependency-1.4.0.sh`는 solver, API, core, notification, messaging,
  benchmark의 `dependencyInsight`와 API·notification의 runtime/test `lettuce-core`
  선택 버전을 이미 검증한다.
- CI `build` job은 dependency contract script와 `./gradlew build -x test
  -x :frontend:appointment-frontend:build --parallel --refresh-dependencies`를
  실행한다. 새 검증은 dependency contract 뒤, build 앞에서 실패를 조기에 알린다.
- baseline으로 `./gradlew projects --no-daemon --console=plain`과
  `./gradlew :appointment-core:test --no-daemon --console=plain`은 성공했다.

## 선택한 설계

### 1. 프로젝트 dependency locking

root `build.gradle.kts`의 `allprojects`에 다음 정책을 적용한다.

```kotlin
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}
```

정확한 Gradle Kotlin DSL import와 현재 wrapper의 API는 구현 전에 compile task로
확인한다. `lockAllConfigurations()`가 다루는 범위에 포함되지 않는 custom configuration이
발견되면 해당 configuration을 task의 명시적 대상에 추가하고 임의의 resolution rule
우회는 만들지 않는다.

`buildSrc`는 별도 Gradle build이므로 `buildSrc/build.gradle.kts`에도 같은 locking
정책을 둔다. main build와 `buildSrc`의 lockfile은 각 build가 소유하는 위치에 생성한다.
verification metadata는 Gradle의 전역 범위에 맞춰 root의 단일 파일을 사용한다.

lockfile은 다음 위치에 생성되며 실제 생성 결과를 기준으로 누락된 JVM project를
추가한다.

- root `gradle.lockfile` (root configuration이 외부 dependency를 resolve할 때)
- 각 JVM subproject의 `<project>/gradle.lockfile`
- `buildSrc/gradle.lockfile`

`frontend/appointment-frontend`의 npm lockfile은 이 Issue의 Gradle lock 범위가 아니며
수정하지 않는다.

### 2. 전체 configuration 검증 task

root에 `verifyDependencyGovernance` task를 추가한다. task는 다음 순서로 동작한다.

1. root와 모든 subproject에서 `canBeResolved == true`인 configuration을 수집한다.
2. configuration 이름과 project path를 안정적인 순서로 정렬한다.
3. 각 configuration의 `incoming.resolutionResult` 또는 동등한 lazy resolution API를
   실제로 호출해 graph를 resolve한다.
4. lock mode가 strict가 아니거나 expected lockfile이 없는 project가 있으면 명확한
   project/configuration 목록과 함께 실패한다.
5. resolution 중 missing lock entry 또는 missing verification metadata가 발생하면
   Gradle의 원래 실패를 보존하고 `--write-locks`/verification write flag를 제안하되
   task 자체는 파일을 변경하지 않는다.

이 task는 `runtimeClasspath`, `testRuntimeClasspath`, `gatling`, benchmark 전용
configuration과 세 custom fixture configuration을 포함해야 한다. `canBeResolved == false`
인 declarable configuration은 resolve하지 않지만, lockfile에 나타난 의존성의
존재 여부는 Gradle strict locking에 맡긴다. task가 configuration을 새로 생성하거나
dependency version을 덮어쓰지 않는다.

### 3. dependency verification

`gradle.properties`의 설정을 다음과 같이 strict로 고정한다.

```properties
org.gradle.dependency.verification=strict
```

root `gradle/verification-metadata.xml`은 현재 build가 실제로 다운로드하는 artifact와
metadata에 대해 `sha256`만 기록한다. PGP signature 검증은 key provenance와 운영
keyring을 별도 합의하지 않았으므로 이번 범위에 추가하지 않는다.

생성·갱신은 다음 명령을 사용한다.

```bash
./gradlew --no-daemon --console=plain --write-verification-metadata sha256 +
  verifyDependencyGovernance
```

생성 결과에는 project dependency뿐 아니라 plugin과 `buildSrc` resolution이 포함되는지
확인한다. generated metadata에 local artifact, SNAPSHOT, machine-specific path가
들어가면 커밋하지 않고 원인을 분리한다.

### 4. 갱신·검증 helper와 CI

새 `scripts/verify-dependency-locking.sh`는 repository root를 기준으로 실행하며
다음 규칙을 지킨다.

- `set -euo pipefail`, `--no-daemon`, `--console=plain`을 사용한다.
- `--write-locks` 또는 `--write-verification-metadata`를 사용하지 않는다.
- `./gradlew verifyDependencyGovernance`를 실행한다.
- API와 notification 각각에 대해 `lettuce-core`의
  `runtimeClasspath`와 `testRuntimeClasspath` `dependencyInsight`를 실행하고
  `7.6.0.RELEASE` 선택과 `7.5.2.RELEASE` 비선택을 확인한다.
- 기존 `scripts/verify-dependency-1.4.0.sh`를 대체하지 않는다. CI의 동일 build job에서
  기존 version contract, 새 lock/verification contract, 일반 build 순서를 유지한다.

`.github/workflows/ci.yml`의 `build` job에는 새 script를 dependency contract 다음에
추가한다. 다른 module job의 dependency 설치나 Docker/Testcontainers lifecycle은
변경하지 않는다. `gradle/**`, `build.gradle.kts`, `buildSrc/**`, 검증 script 변경은
현재 path filter로 build job을 활성화해야 한다.

### 5. 운영 문서

`docs/maintenance/dependency-locking.md`는 다음 절차를 기록한다.

1. 의도한 dependency 변경과 Issue/PR 근거를 먼저 작성한다.
2. clean cache에서 `--write-locks`와 `--write-verification-metadata sha256`를 별도
   실행한다.
3. lockfile diff, metadata diff, `dependencyInsight`, license/공급처를 리뷰한다.
4. 담당자가 승인한 뒤에만 generated 파일을 커밋한다.
5. CI는 strict read-only 검증만 수행한다.
6. 잘못된 갱신은 generated 파일을 이전 commit으로 되돌리고 dependency 선언 변경도
   함께 되돌린다. metadata를 수동으로 비우거나 verification을 lenient로 낮추지 않는다.

문서에는 `buildSrc`, plugin, benchmark, custom fixture configuration을 빠뜨리지 않는
검증 checklist를 포함한다.

## 검증 계약

### 로컬 검증

다음 명령을 순차 실행한다.

```bash
./gradlew projects --no-daemon --console=plain
./gradlew verifyDependencyGovernance --no-daemon --console=plain
./gradlew :appointment-core:test --no-daemon --console=plain
./gradlew :appointment-notification:test --no-daemon --console=plain
./gradlew :appointment-api:test --no-daemon --console=plain
./gradlew :appointment-solver:test --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:test --no-daemon --console=plain
./scripts/verify-dependency-1.4.0.sh
./scripts/verify-dependency-locking.sh
```

생성 직후에는 daemon/cache 영향을 배제하기 위해 clean cache 환경에서 같은 검증을
한 번 더 실행한다. Testcontainers가 필요한 테스트는 저장소의 singleton launcher와
macOS의 관리된 Docker socket 설정을 그대로 사용한다.

### CI 검증

- `build` job에서 두 dependency contract script와 일반 build가 성공한다.
- `ci-status`가 기존 core/solver/API/notification/messaging/benchmark/flyway/coverage/
  frontend job을 모두 기다린다.
- actionlint 또는 저장소의 workflow 검사 helper가 `.github/workflows/ci.yml` 변경을
  통과한다.
- `git diff --check`가 통과하고 generated XML/lockfile에 whitespace·임시 경로가 없다.

## 실패와 롤백

- 새 dependency가 lockfile에 없으면 strict locking이 실패한다. 선언을 되돌리거나
  의도한 갱신 절차로 lockfile을 다시 생성한다.
- verification metadata가 없거나 checksum이 다르면 strict verification이 실패한다.
  artifact를 신뢰 목록에 수동 추가하지 말고 공급처와 의도한 checksum을 확인한다.
- custom configuration이 resolve되지 않으면 `verifyDependencyGovernance`가 configuration
  이름을 출력해야 하며, 해당 configuration을 숨기는 예외를 추가하지 않는다.
- Gradle DSL/API 호환성 문제로 정책을 적용할 수 없으면 구현을 중단하고 wrapper 버전과
  공식 API 근거를 갱신한다. 이번 Issue에서 Gradle wrapper 업그레이드는 하지 않는다.
- 롤백은 정책 코드, strict property, helper/CI 단계, 문서, generated lock/metadata를
  하나의 커밋 단위로 되돌릴 수 있어야 한다. dependency 버전 자체는 롤백 대상이 아니다.

## 채택하지 않은 대안

| 대안 | 제외 이유 |
| --- | --- |
| `buildSrc` convention plugin으로 모든 정책 추상화 | `buildSrc` bootstrap과 plugin resolution 순서가 복잡해지고, 이번 변경에 필요한 정책보다 코드 표면이 커진다. |
| CI에서만 `--write-locks`/verification 명령 실행 | 로컬·IDE·benchmark 실행에 전역 strict 계약이 남지 않고 CI가 저장소를 변경할 수 있다. |
| `lettuce-core`·Spring/BOM 추가 업그레이드 | Issue #361의 locking/verification 목적과 무관한 dependency refresh다. |
| Redis 7.2/8.8 matrix 도입 | Issue #360에서 Redis 8 단일 launcher 검증으로 확정했고 별도 후속 범위다. |

## 설계 관점 검토

| 관점 | 결과 | 검토 근거 |
| --- | --- | --- |
| 성능 | PASS | 검증 task는 CI/명시적 local command에서만 모든 resolvable configuration을 순회하며 runtime 코드 경로를 추가하지 않는다. |
| 안정성 | PASS | strict lock/verification 누락을 fail-closed하고, CI에서는 generated file을 쓰지 않는다. |
| 보안 | PASS | 외부 artifact checksum을 고정하고 verification을 lenient로 낮추는 우회를 금지한다. PGP는 key 운영 설계가 없어 이번 범위에서 제외한다. |
| 운영 | PASS | 갱신·리뷰·승인·롤백 순서를 문서화하고 clean-cache 검증을 요구한다. |
| 개발자/API | PASS | 기존 dependencyInsight 계약과 Gradle CLI를 유지하며 production API와 dependency version은 바꾸지 않는다. |
| 사용자/호출자 | PASS | 애플리케이션 기능·응답·Redis matrix에는 영향을 주지 않고 build 실패 원인을 configuration/artifact 단위로 출력한다. |

통합 결과: P0=0, P1=0. 추가 설계가 필요한 PGP signature provenance와 Redis image
matrix는 각각 이번 범위의 명시적 제외로 남긴다.

## 완료 조건

- [ ] root와 `buildSrc`에 strict dependency locking이 적용된다.
- [ ] 실제 resolve 가능한 project configuration을 모두 검증하는 task가 동작한다.
- [ ] project별 `gradle.lockfile`과 root `gradle/verification-metadata.xml`이 생성되고
  clean-cache strict 검증을 통과한다.
- [ ] `org.gradle.dependency.verification=strict`가 local·CI에서 적용된다.
- [ ] 기존 `bluetape4k-dependencies:1.4.0`와 `lettuce-core:7.6.0.RELEASE`
  `dependencyInsight` 계약이 유지된다.
- [ ] cache, notification, solver, API, messaging benchmark 검증 경로가 실행된다.
- [ ] 갱신·리뷰·승인·롤백 문서와 CI read-only 검사가 추가된다.
- [ ] Redis 7.2/8.8 matrix, unrelated dependency upgrade, API 기능 변경이 없다.

## 문서 검수 기록

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| SPW-01 | PASS | Issue #361, 현재 `gradle.properties`, root build, CI, 기존 dependency contract script의 사실을 기준으로 작성했다. |
| SPW-02 | PASS | 목적, 범위, 파일 책임, 실패·롤백, 검증·DoD를 구체적인 동작으로 적었다. |
| SPW-03 | PASS | 한국어 문장을 직접 서술형으로 작성하고 Gradle DSL, 경로, 명령, configuration 이름은 원문을 보존했다. |
| SPW-04 | PASS | `dependency locking`, `verification metadata`, `lockfile`, `dependencyInsight` 용어를 문서 전체에서 일관되게 사용했다. |
| SPW-05 | PASS | 제목, 표, 코드 블록, 링크, 체크리스트를 다시 읽어 구조와 독자 표면을 확인했다. |

# Gradle dependency locking과 verification metadata 운영

이 저장소는 Gradle project별 `gradle.lockfile`과 root의
`gradle/verification-metadata.xml`을 기준 데이터로 사용한다. `buildSrc`와 plugin
resolution도 이 계약의 대상이다. `gradle.properties`는
`org.gradle.dependency.verification=strict`를 사용하므로 lock entry나 checksum이
없으면 빌드가 실패한다.

Gradle의 [dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)과
[dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
공식 동작을 따르며, CI는 generated 파일을 변경하지 않는다.

## 읽기 검증

일반 개발과 CI에서는 다음 명령만 사용한다.

```bash
./gradlew verifyDependencyGovernance \
  --no-daemon --no-configuration-cache --no-parallel --console=plain
bash scripts/verify-dependency-locking.sh
```

`verifyDependencyGovernance`는 root와 각 subproject의 `canBeResolved == true`
configuration을 자기 project 경계 안에서 resolve한다. 다음 경로를 포함한다.

- `runtimeClasspath`와 `testRuntimeClasspath`
- API의 `gatling` 계열 configuration
- messaging benchmark의 `benchmarkGenerator.resolver`와 benchmark classpath
- root의 consumer fixture configuration
- `buildSrc`와 plugin resolution

읽기 검증 명령에는 `--write-locks`나 `--write-verification-metadata`를 넣지 않는다.
누락된 lock entry나 checksum은 실패 원인으로 남겨야 한다.

## 의도한 dependency 갱신

dependency 선언을 바꾸기 전에 Issue와 선택 이유를 작성하고, 변경 범위가
`bluetape4k-dependencies:1.4.0` 및 `lettuce-core:7.6.0.RELEASE` 계약을 유지하는지 확인한다.

1. 기존 Gradle daemon을 사용하지 않도록 한다.
2. clean Gradle user home에서 generated 파일을 만든다.
3. lockfile과 verification metadata diff를 확인한다.
4. 영향을 받은 configuration의 `dependencyInsight`와 artifact 공급처·checksum을 리뷰한다.
5. 담당자가 승인한 뒤 dependency 선언과 generated 파일을 같은 PR에 커밋한다.

```bash
clean_gradle_user_home="$(mktemp -d "${TMPDIR:-/tmp}/clinic-appointment-gradle.XXXXXX")"
trap 'rm -rf -- "$clean_gradle_user_home"' EXIT
GRADLE_USER_HOME="$clean_gradle_user_home" ./gradlew \
  --no-daemon --no-configuration-cache --no-parallel --console=plain \
  --refresh-dependencies \
  --write-locks \
  --write-verification-metadata sha256 \
  verifyDependencyGovernance
```

생성 명령은 root `gradle.lockfile`, `settings-gradle.lockfile`, 각 Gradle project의
`gradle.lockfile`, `buildSrc/gradle.lockfile`, root
`gradle/verification-metadata.xml`을 갱신할 수 있다. frontend의 npm lockfile과
`package-lock.json`은 이 절차의 대상이 아니다.

## 리뷰와 승인

PR에는 다음 증거를 함께 남긴다.

```bash
find . -type f \( -name gradle.lockfile -o -name settings-gradle.lockfile \) \
  -not -path './.gradle/*' -print | sort
xmllint --noout gradle/verification-metadata.xml
git diff --check
./scripts/verify-dependency-1.4.0.sh
bash scripts/verify-dependency-locking.sh
```

리뷰자는 다음을 확인한다.

- lockfile에 의도하지 않은 version, repository, 빈 configuration이 추가되지 않았다.
- verification metadata에 local path, machine-specific path, 승인하지 않은 artifact가 없다.
- `verify-metadata=true`, `verify-signatures=false`의 범위가 의도와 일치한다.
- root, `settings-gradle.lockfile`, `buildSrc`, benchmark, frontend Gradle project를 포함한
  예상 lockfile inventory가 모두 존재한다.
- `dependencyInsight`가 API·notification의 runtime/test configuration에서
  `lettuce-core:7.6.0.RELEASE`를 선택한다.
- CI workflow에 write flag, `continue-on-error`, lenient fallback이 없고,
  `permissions: contents: read`, `persist-credentials: false`, build timeout이 적용된다.

PGP signature 검증은 별도 key provenance와 운영 keyring을 합의하기 전까지 추가하지
않는다. SHA-256을 PGP 검증의 대체 설명으로 사용하지 않는다.

## 잔여 위험과 재검토 조건

- **artifact provenance (P2):** 현재 metadata는 SHA-256 무결성만 검증하고 PGP 서명을
  검증하지 않는다. 담당자는 `debop`이며, trusted key 소유·교체·폐기 절차를 합의하기
  전까지의 임시 수용이다. `1.4.0` release train 종료 전에 PGP 도입 여부를 재검토한다.
- **settings plugin resolution (P3):** `foojay-resolver-convention`은
  `pluginManagement`에 선언만 되어 실제 적용되지 않으므로 현재 metadata에 artifact가
  없다. 실제 plugin을 적용할 때 settings resolution의 lock·verification 증거를 같은 PR에
  추가한다.
- **clean-cache 시간 기준선 (P2):** Issue #361에서는 별도 성능 임계치를 승인하지 않았다.
  현재 clean `GRADLE_USER_HOME`에서 governance는 2분 4초, helper는 4초였으며, CI build
  job은 20분 timeout으로 제한한다. 모듈 증가나 CI cache miss로 이 범위를 넘으면
  inventory·검증 분리를 재검토한다.

## 롤백

잘못된 dependency 갱신은 dependency 선언, 관련 lockfile, verification metadata를
이전 commit으로 함께 되돌린다. 이후 읽기 검증을 다시 실행한다.

```bash
git revert <dependency-change-commit>
./gradlew verifyDependencyGovernance \
  --no-daemon --no-configuration-cache --no-parallel --console=plain
bash scripts/verify-dependency-locking.sh
```

verification을 `lenient`로 바꾸거나 broad trusted-artifact 예외를 추가해 실패를
숨기지 않는다. rollback 후에도 기존 `bluetape4k-dependencies:1.4.0`과
`lettuce-core:7.6.0.RELEASE` 계약이 유지되는지 `dependencyInsight`로 확인한다.

## 적용 범위 checklist

- [ ] root와 모든 Gradle subproject
- [ ] `buildSrc`와 plugin resolution
- [ ] `runtimeClasspath`와 `testRuntimeClasspath`
- [ ] API `gatling` configuration
- [ ] messaging benchmark configuration
- [ ] root consumer fixture configuration
- [ ] `bluetape4k-dependencies:1.4.0` dependencyInsight
- [ ] `lettuce-core:7.6.0.RELEASE` runtime/test dependencyInsight
- [ ] frontend npm lockfile은 변경하지 않음

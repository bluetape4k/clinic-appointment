# Issue #361 — 전역 Gradle dependency locking과 verification metadata 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모든 Gradle dependency resolution을 lockfile과 SHA-256 verification metadata로 고정하고, 로컬·CI에서 strict read-only 검증을 강제한다.

**Architecture:** root `build.gradle.kts`와 `buildSrc/build.gradle.kts`에 Gradle 네이티브 locking 정책을 적용한다. root의 `verifyDependencyGovernance` task가 모든 `canBeResolved` configuration을 실제로 resolve하며, 별도 shell helper가 이 task와 기존 버전 `dependencyInsight` 계약을 함께 실행한다. generated lockfile·verification metadata는 명시적인 갱신 명령으로만 생성하고 CI에는 쓰기 권한을 주지 않는다.

**Tech Stack:** Gradle 9.7.0, Kotlin DSL, `gradle.lockfile`, `gradle/verification-metadata.xml`, Bash, GitHub Actions, 기존 Kotlin/JUnit 5 모듈 테스트.

---

## Task 1: 의존성 거버넌스 검증 helper를 먼저 작성한다 (RED)

**Files:**
- Create: `scripts/verify-dependency-locking.sh`

- [ ] **Step 1: Write the failing verification helper**

`set -euo pipefail`과 repository-root 계산을 사용하고, 생성 플래그 없이 strict 검증만 수행하도록 다음 내용으로 작성한다.

```bash
#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly REPOSITORY_ROOT

fail() {
    echo "[FAIL] $*" >&2
    exit 1
}

assert_selected_lettuce_version() {
    local module="$1"
    local configuration="$2"
    local label="$3"
    local output

    echo "[CHECK] $label: $module:$configuration"
    output="$(
        cd -- "$REPOSITORY_ROOT"
        ./gradlew "$module:dependencyInsight" \
            --dependency io.lettuce:lettuce-core \
            --configuration "$configuration" \
            --no-daemon \
            --no-configuration-cache \
            --console=plain
    )" || fail "$label dependencyInsight failed"

    grep -Eq '^io[.]lettuce:lettuce-core:7[.]6[.]0[.]RELEASE( \(selected by rule\))?$' <<<"$output" \
        || fail "$label did not select io.lettuce:lettuce-core:7.6.0.RELEASE"
    ! grep -Eq '^io[.]lettuce:lettuce-core:7[.]5[.]2[.]RELEASE( \(selected by rule\))?$' <<<"$output" \
        || fail "$label still exposes forbidden selected version 7.5.2.RELEASE"
}

[[ -f "$REPOSITORY_ROOT/gradle/verification-metadata.xml" ]] \
    || fail "gradle/verification-metadata.xml is missing"
grep -qx 'org.gradle.dependency.verification=strict' "$REPOSITORY_ROOT/gradle.properties" \
    || fail "gradle.properties must set org.gradle.dependency.verification=strict"

(
    cd -- "$REPOSITORY_ROOT"
    ./gradlew verifyDependencyGovernance \
        --no-daemon \
        --no-configuration-cache \
        --console=plain
) || fail "verifyDependencyGovernance failed"

assert_selected_lettuce_version :appointment-api runtimeClasspath api-runtime
assert_selected_lettuce_version :appointment-api testRuntimeClasspath api-test-runtime
assert_selected_lettuce_version :appointment-notification runtimeClasspath notification-runtime
assert_selected_lettuce_version :appointment-notification testRuntimeClasspath notification-test-runtime

echo "[PASS] Gradle dependency locking and verification contract"
```

- [ ] **Step 2: Run the helper to verify the RED failure**

Run:

```bash
bash scripts/verify-dependency-locking.sh
```

Expected: `FAIL` because `gradle/verification-metadata.xml` and
`verifyDependencyGovernance` do not exist yet. Do not add a fallback that disables
verification or writes generated files.

## Task 2: 전역 locking 정책과 configuration resolve task를 추가한다

**Files:**
- Modify: `build.gradle.kts:1-28` (Gradle API import)
- Modify: `build.gradle.kts:690-710` (all-project policy)
- Modify: `build.gradle.kts` after the `subprojects` configuration block and before the Kover aggregation block
- Modify: `buildSrc/build.gradle.kts:1-20`

- [ ] **Step 1: Add the strict locking API import**

`build.gradle.kts`의 기존 Gradle import에 다음을 추가한다.

```kotlin
import org.gradle.api.artifacts.dsl.LockMode
```

`buildSrc/build.gradle.kts`에서도 같은 `LockMode`를 import한다.

- [ ] **Step 2: Enable locking for every project**

기존 `allprojects { repositories { ... } configurations.all { ... } }` 블록 안에 다음을 추가한다.

```kotlin
    dependencyLocking {
        lockAllConfigurations()
        lockMode = LockMode.STRICT
    }
```

`buildSrc/build.gradle.kts`에는 repository와 plugin 선언 뒤에 다음을 추가한다.

```kotlin
import org.gradle.api.artifacts.dsl.LockMode

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}
```

기존 `mavenCentral()`, `central-snapshots`, changing-module cache 정책과 dependency
resolution rule은 삭제하거나 변경하지 않는다.

- [ ] **Step 3: Register the all-configuration verification task**

root build의 subproject configuration이 끝난 뒤 다음 task를 등록한다.

```kotlin
val verifyDependencyGovernance = tasks.register("verifyDependencyGovernance") {
    group = "verification"
    description = "Resolves every resolvable project configuration under strict locking and verification."
    notCompatibleWithConfigurationCache("Configuration inventory is intentionally resolved at task execution time.")

    doLast {
        val configurationsToResolve = allprojects
            .flatMap { project ->
                project.configurations
                    .filter(Configuration::isCanBeResolved)
                    .map { configuration -> project.path to configuration }
            }
            .sortedWith(compareBy({ it.first }, { it.second.name }))

        require(configurationsToResolve.isNotEmpty()) {
            "No resolvable Gradle configurations were discovered."
        }

        configurationsToResolve.forEach { (projectPath, configuration) ->
            logger.lifecycle("Resolving $projectPath:${configuration.name}")
            configuration.resolve()
        }
    }
}
```

The task must include `runtimeClasspath`, `testRuntimeClasspath`, API `gatling`, benchmark
configurations, and the three root fixture configurations because all are resolvable. It must
not create a new configuration, mutate dependency versions, or catch and hide Gradle locking
or verification exceptions. `notCompatibleWithConfigurationCache` is intentional; the helper
passes `--no-configuration-cache` so the inventory is fresh on every run.

- [ ] **Step 4: Run the task and verify the next expected RED failure**

Run:

```bash
./gradlew verifyDependencyGovernance --no-daemon --no-configuration-cache --console=plain
```

Expected: configuration discovery starts, then strict locking or verification fails because
the generated files and strict property are not complete. The failure must name the missing
lock entry or verification metadata rather than a Kotlin compilation error.

## Task 3: strict verification property와 generated 산출물을 만든다

**Files:**
- Modify: `gradle.properties:11`
- Create: `gradle.lockfile` files generated in the root and each participating Gradle project
- Create: `buildSrc/gradle.lockfile` if the `buildSrc` build resolves external configurations
- Create: `gradle/verification-metadata.xml`

- [ ] **Step 1: Change the verification mode to strict**

Replace:

```properties
org.gradle.dependency.verification=lenient
```

with:

```properties
org.gradle.dependency.verification=strict
```

Do not add an environment-dependent exception or a second property with a different value.

- [ ] **Step 2: Bootstrap lockfiles and SHA-256 metadata in one explicit write operation**

Run from the repository root:

```bash
./gradlew \
  --no-daemon \
  --no-configuration-cache \
  --console=plain \
  --refresh-dependencies \
  --write-locks \
  --write-verification-metadata sha256 \
  verifyDependencyGovernance
```

Expected: Gradle resolves every discovered configuration, writes project-owned lockfiles and
`gradle/verification-metadata.xml`, and exits with `BUILD SUCCESSFUL`. If generation fails on a
changing or local artifact, stop and classify that artifact; do not mark it trusted by a broad
regex and do not lower verification mode.

- [ ] **Step 3: Inspect generated artifacts before committing them**

Run:

```bash
find . -type f -name gradle.lockfile -not -path './.gradle/*' -not -path './frontend/*' -print | sort
xmllint --noout gradle/verification-metadata.xml
git diff --check
git status --short
```

Expected: only Gradle project lockfiles, `buildSrc/gradle.lockfile` when applicable, and the
single root verification XML are new; frontend npm state is unchanged; XML parsing and whitespace
checks pass. Review the generated diff for machine-specific paths, SNAPSHOT coordinates, or
unexpected repositories and remove the affected generated entry only after reproducing the
cause with the relevant configuration.

- [ ] **Step 4: Run the helper for the first GREEN result**

Run:

```bash
bash scripts/verify-dependency-locking.sh
```

Expected: `[PASS] Gradle dependency locking and verification contract`, strict task resolution,
and four `lettuce-core:7.6.0.RELEASE` dependencyInsight checks.

- [ ] **Step 5: Commit the policy and generated artifacts**

```bash
git add build.gradle.kts buildSrc/build.gradle.kts gradle.properties \
  gradle/verification-metadata.xml \
  ':(glob)**/gradle.lockfile' \
  scripts/verify-dependency-locking.sh
git commit -m "빌드 의존성 고정과 무결성 검증을 강제한다" -m "Issue #361의 strict locking과 SHA-256 verification metadata를 실제 Gradle configuration에 적용한다.\n\nConstraint: CI는 generated artifact를 쓰지 않고 기존 1.4.0 dependencyInsight 계약을 유지해야 한다.\nRejected: lenient verification과 CI 전용 검사는 전역 재현성을 보장하지 못하므로 제외했다.\nConfidence: high\nScope-risk: moderate\nDirective: dependency 변경은 문서화된 write 명령과 lock/metadata diff 리뷰를 거친다.\nTested: verifyDependencyGovernance 및 verify-dependency-locking.sh 통과\nNot-tested: CI runner와 전체 모듈 통합 테스트는 다음 검증 단계에서 실행한다."
```

## Task 4: CI에서 read-only dependency contract를 필수화한다

**Files:**
- Modify: `.github/workflows/ci.yml` immediately after `Verify bluetape4k-dependencies 1.4.0 contract`

- [ ] **Step 1: Add the dedicated CI step**

```yaml
      - name: Verify Gradle dependency locking and verification
        run: bash scripts/verify-dependency-locking.sh
        env:
          GRADLE_OPTS: "-Dorg.gradle.daemon=false"
```

The existing 1.4.0 contract step remains immediately before it. Do not add `--write-locks`,
`--write-verification-metadata`, `continue-on-error`, or a second dependency cache policy.

- [ ] **Step 2: Validate workflow syntax and path coverage**

Run:

```bash
if command -v actionlint >/dev/null 2>&1; then
  actionlint .github/workflows/ci.yml
else
  echo "actionlint is not installed; record this as a verification gap"
fi
git diff --check
```

Expected: actionlint reports no error when available. The existing `changes` filters already
include `buildSrc/**`, `gradle/**`, `build.gradle.kts`, and `settings.gradle.kts`; confirm the
new script is covered by the build job's non-filtered required path behavior or add the exact
`scripts/verify-dependency-locking.sh` path to each affected filter without broadening unrelated
module triggers.

- [ ] **Step 3: Commit the CI contract**

```bash
git add .github/workflows/ci.yml
git commit -m "CI에서 의존성 고정 계약을 검증한다" -m "빌드 전에 strict locking, verification metadata, lettuce dependencyInsight를 read-only로 검사한다.\n\nConstraint: CI job graph와 기존 dependency contract 순서를 유지해야 한다.\nRejected: generated artifact를 CI에서 갱신하는 방식은 재현성과 reviewability를 훼손하므로 제외했다.\nConfidence: high\nScope-risk: narrow\nDirective: dependency 갱신은 개발자 로컬 write 절차로만 수행한다.\nTested: actionlint 또는 대체 workflow 검사, git diff --check\nNot-tested: GitHub runner의 실제 job은 PR CI에서 확인한다."
```

## Task 5: 갱신·승인·롤백 운영 문서를 추가한다

**Files:**
- Create: `docs/maintenance/dependency-locking.md`

- [ ] **Step 1: Write the Korean maintenance guide**

문서에는 다음 명령과 경계를 그대로 포함한다.

```markdown
# Gradle dependency locking과 verification metadata 운영

## 읽기 검증

```bash
./gradlew verifyDependencyGovernance --no-daemon --no-configuration-cache --console=plain
bash scripts/verify-dependency-locking.sh
```

CI와 일반 build에서는 생성 플래그를 사용하지 않는다. 누락된 lock entry나 checksum은
빌드를 실패시킨다.

## 의도한 dependency 갱신

1. dependency 변경 Issue와 선택 이유를 먼저 작성한다.
2. clean Gradle user home에서 다음 명령을 실행한다.

```bash
./gradlew --no-daemon --no-configuration-cache --console=plain \
  --refresh-dependencies --write-locks \
  --write-verification-metadata sha256 verifyDependencyGovernance
```

3. 모든 `gradle.lockfile`과 `gradle/verification-metadata.xml` diff를 확인한다.
4. 해당 configuration의 `dependencyInsight`와 공급처·checksum을 리뷰한다.
5. 담당자 승인이 끝난 뒤 선언 변경과 generated 파일을 같은 PR에 커밋한다.

## 롤백

잘못된 갱신은 dependency 선언, lockfile, verification metadata를 함께 이전 commit으로
되돌린다. verification을 `lenient`로 바꾸거나 broad trusted-artifact 예외를 추가해
실패를 숨기지 않는다.

## 적용 범위 checklist

- [ ] root와 모든 JVM subproject
- [ ] `buildSrc`와 plugin resolution
- [ ] `runtimeClasspath`와 `testRuntimeClasspath`
- [ ] API `gatling`, messaging benchmark
- [ ] root consumer fixture configuration
- [ ] `bluetape4k-dependencies:1.4.0`와 `lettuce-core:7.6.0.RELEASE` dependencyInsight
```

The final document must use Korean prose while preserving Gradle paths, commands, and
configuration identifiers exactly.

- [ ] **Step 2: Run document checks**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/maintenance/dependency-locking.md
```

Expected: no whitespace error and zero terminology findings. Repair each contextual finding
locally; do not apply global replacements.

- [ ] **Step 3: Commit the operating guide**

```bash
git add docs/maintenance/dependency-locking.md
git commit -m "의존성 고정 갱신 절차를 문서화한다" -m "lockfile과 verification metadata를 안전하게 갱신·리뷰·롤백하는 저장소 운영 계약을 남긴다.\n\nConstraint: CI는 strict read-only이고 generated artifact 승인은 PR에서 이뤄져야 한다.\nRejected: verification 예외를 운영 절차로 허용하는 방식은 제외했다.\nConfidence: high\nScope-risk: narrow\nDirective: dependency 변경자는 lock/metadata diff와 dependencyInsight 증거를 함께 제출한다.\nTested: git diff --check; audit-korean-terms.mjs\nNot-tested: 문서 링크의 외부 렌더링은 PR preview에서 확인한다."
```

## Task 6: 모듈별 기능·benchmark와 clean-cache 검증을 수행한다

**Files:**
- No source changes expected; only test reports and local temporary Gradle cache are produced.

- [ ] **Step 1: Run the governance and dependency contracts sequentially**

```bash
./gradlew projects --no-daemon --console=plain
./gradlew verifyDependencyGovernance --no-daemon --no-configuration-cache --console=plain
./scripts/verify-dependency-1.4.0.sh
bash scripts/verify-dependency-locking.sh
```

Expected: all commands exit `0`; the existing version contract still selects the pinned
versions and the new task reports every resolvable configuration.

- [ ] **Step 2: Run required module tests one at a time**

```bash
./gradlew :appointment-core:test --no-daemon --console=plain
./gradlew :appointment-notification:test --no-daemon --console=plain
./gradlew :appointment-solver:test --no-daemon --console=plain
./gradlew :appointment-api:test --no-daemon --console=plain
./gradlew :appointment-messaging:test --no-daemon --console=plain
./gradlew :appointment-messaging-benchmark:test --no-daemon --console=plain
```

Expected: each module reports `BUILD SUCCESSFUL` with zero failed tests. Testcontainers
modules use the repository's singleton launchers and inherited Docker socket configuration;
do not replace them with `@Testcontainers` or raw containers.

- [ ] **Step 3: Repeat the governance path with an isolated Gradle user home**

```bash
clean_gradle_user_home="$(mktemp -d "${TMPDIR:-/tmp}/clinic-appointment-gradle.XXXXXX")"
trap 'rm -rf -- "$clean_gradle_user_home"' EXIT
GRADLE_USER_HOME="$clean_gradle_user_home" ./gradlew \
  --no-daemon --no-configuration-cache --console=plain \
  verifyDependencyGovernance
GRADLE_USER_HOME="$clean_gradle_user_home" bash scripts/verify-dependency-locking.sh
```

Expected: the clean-cache run succeeds using only committed lockfiles and verification metadata;
no `--write-*` flag is needed. If network access or a missing checksum fails, record the exact
artifact and do not accept a lenient fallback.

- [ ] **Step 4: Run compile, static, and diff checks**

```bash
./gradlew build -x test -x :frontend:appointment-frontend:build --no-daemon --console=plain
./gradlew detekt --no-daemon --console=plain
git diff --check
```

Expected: compile-only build, Detekt, and whitespace checks pass. Existing warnings are recorded
without changing unrelated deprecation or Exposed cleanup scope.

## Task 7: six-perspective review와 lesson을 남긴다

**Files:**
- Create: `docs/lessons/2026-08-20-issue-361-dependency-locking.md`
- Review: all changed files and generated lock/verification artifacts

- [ ] **Step 1: Review the diff through six required perspectives**

Record a table with `성능`, `안정성`, `보안`, `운영`, `개발자/API`, `사용자/호출자`.

- 성능: governance task가 명시적 검증에서만 모든 resolvable configuration을 resolve하고
  production runtime path나 Gradle normal build task를 추가하지 않는지 확인한다.
- 안정성: strict lock/verification 실패가 숨겨지지 않고 generated file이 CI에서 바뀌지
  않는지 확인한다.
- 보안: SHA-256 metadata가 외부 artifact와 plugin을 포함하고 broad trust/lenient 우회가
  없는지 확인한다.
- 운영: update/review/approval/rollback 명령과 clean-cache 증거가 문서와 CI에 일치하는지
  확인한다.
- 개발자/API: 기존 dependencyInsight, module configuration, buildSrc/plugin 범위를
  유지하고 production API와 dependency version을 바꾸지 않았는지 확인한다.
- 사용자/호출자: runtime 기능, Redis 단일 검증, frontend npm 상태에 영향이 없는지 확인한다.

Expected: P0=0, P1=0. Any blocker is fixed before continuing; no speculative refactor is added.

- [ ] **Step 2: Write the Korean lesson from fresh evidence**

The lesson must record the chosen native policy, the exact generated artifact count/paths,
strict verification behavior, clean-cache result, CI result, rejected alternatives, and any
remaining follow-up such as Redis 7.2/8.8 matrix. Preserve command output summaries and commit
SHAs; do not claim a CI result until the PR check is green.

- [ ] **Step 3: Run the final Korean document audit**

```bash
git diff --check
node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs \
  docs/maintenance/dependency-locking.md \
  docs/lessons/2026-08-20-issue-361-dependency-locking.md
```

Expected: zero findings and no stale version or scope claims.

## Task 8: pre-PR evidence and handoff

**Files:**
- Review only: all implementation files, generated artifacts, lesson, and Issue #361 metadata.

- [ ] **Step 1: Re-read the approved spec and DoD line by line**

Confirm that every spec bullet maps to a changed file or a fresh verification result. Explicitly
confirm no Redis matrix, unrelated dependency upgrade, API feature, or frontend npm lockfile was
added.

- [ ] **Step 2: Capture repository and helper evidence**

```bash
git status --short
git diff --stat origin/develop...HEAD
git diff --check origin/develop...HEAD
git log -1 --format='%H%n%s'
gh issue view 361 --json number,title,state,assignees,labels,milestone,url
```

Expected: only intended files are changed, worktree contains no generated build output, and
Issue #361 metadata remains `OPEN`, assigned to `debop`, with `dependencies`, `maintenance`,
`test`, and milestone `1.4.0`.

- [ ] **Step 3: Complete the Bluetape workflow evidence before PR creation**

Record successful helper `check-result`, component evidence, completion check, and final
workflow state. Re-read PR-body requirements and Issue/PR metadata parity before creating a
Korean PR. Stop at a merge-ready PR and request fresh merge approval; do not merge this Issue
automatically.

## Plan self-review

- **Spec coverage:** Tasks 2–3 cover global locking, strict verification, generated artifacts,
  buildSrc, and all resolvable configurations; Tasks 1 and 4 cover helper/CI; Task 5 covers
  update/review/rollback; Tasks 6–8 cover module, clean-cache, review, lesson, and handoff DoD.
- **Placeholder scan:** No step relies on `TODO`, `TBD`, “appropriate handling”, or an unnamed
  future file; commands and file paths are explicit.
- **Type/API consistency:** The task is named `verifyDependencyGovernance` in the root build,
  helper, generation commands, and CI. Lock mode is `LockMode.STRICT`, verification property is
  `org.gradle.dependency.verification=strict`, and all four lettuce checks use the same target
  version `7.6.0.RELEASE`.


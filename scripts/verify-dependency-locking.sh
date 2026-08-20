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
            --no-parallel \
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
        --no-parallel \
        --console=plain
) || fail "verifyDependencyGovernance failed"

assert_selected_lettuce_version :appointment-api runtimeClasspath api-runtime
assert_selected_lettuce_version :appointment-api testRuntimeClasspath api-test-runtime
assert_selected_lettuce_version :appointment-notification runtimeClasspath notification-runtime
assert_selected_lettuce_version :appointment-notification testRuntimeClasspath notification-test-runtime

echo "[PASS] Gradle dependency locking and verification contract"

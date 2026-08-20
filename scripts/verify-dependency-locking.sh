#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly REPOSITORY_ROOT
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/clinic-appointment-dependency-locking.XXXXXX")"
readonly TEMP_DIR

cleanup() {
    rm -rf -- "$TEMP_DIR"
}

trap cleanup EXIT

fail() {
    echo "[FAIL] $*" >&2
    exit 1
}

assert_selected_lettuce_version() {
    local module="$1"
    local configuration="$2"
    local label="$3"
    local output_file="$TEMP_DIR/$label.txt"

    echo "[CHECK] $label: $module:$configuration"
    if ! (
        cd -- "$REPOSITORY_ROOT"
        ./gradlew "$module:dependencyInsight" \
            --dependency io.lettuce:lettuce-core \
            --configuration "$configuration" \
            --no-daemon \
            --no-configuration-cache \
            --no-parallel \
            --console=plain
    ) >"$output_file" 2>&1; then
        cat "$output_file" >&2
        fail "$label dependencyInsight failed"
    fi

    if ! grep -Eq '^io[.]lettuce:lettuce-core:7[.]6[.]0[.]RELEASE( \(selected by rule\))?$' "$output_file"; then
        cat "$output_file" >&2
        fail "$label did not select io.lettuce:lettuce-core:7.6.0.RELEASE"
    fi
    if grep -Eq '^io[.]lettuce:lettuce-core:7[.]5[.]2[.]RELEASE( \(selected by rule\))?$' "$output_file"; then
        cat "$output_file" >&2
        fail "$label still exposes forbidden selected version 7.5.2.RELEASE"
    fi
}

EXPECTED_LOCKFILES=(
    gradle.lockfile
    settings-gradle.lockfile
    buildSrc/gradle.lockfile
    appointment-api/gradle.lockfile
    appointment-core/gradle.lockfile
    appointment-event/gradle.lockfile
    appointment-messaging/gradle.lockfile
    appointment-notification/gradle.lockfile
    appointment-solver/gradle.lockfile
    benchmark/appointment-messaging-benchmark/gradle.lockfile
    frontend/gradle.lockfile
    frontend/appointment-frontend/gradle.lockfile
)

for lockfile in "${EXPECTED_LOCKFILES[@]}"; do
    [[ -f "$REPOSITORY_ROOT/$lockfile" ]] || fail "$lockfile is missing"
done
[[ -f "$REPOSITORY_ROOT/gradle/verification-metadata.xml" ]] \
    || fail "gradle/verification-metadata.xml is missing"
grep -qx 'org.gradle.dependency.verification=strict' "$REPOSITORY_ROOT/gradle.properties" \
    || fail "gradle.properties must set org.gradle.dependency.verification=strict"

GOVERNANCE_OUTPUT_FILE="$TEMP_DIR/verifyDependencyGovernance.txt"
if ! (
    cd -- "$REPOSITORY_ROOT"
    ./gradlew verifyDependencyGovernance \
        --no-daemon \
        --no-configuration-cache \
        --no-parallel \
        --console=plain
) >"$GOVERNANCE_OUTPUT_FILE" 2>&1; then
    cat "$GOVERNANCE_OUTPUT_FILE" >&2
    fail "verifyDependencyGovernance failed"
fi

assert_selected_lettuce_version :appointment-api runtimeClasspath api-runtime
assert_selected_lettuce_version :appointment-api testRuntimeClasspath api-test-runtime
assert_selected_lettuce_version :appointment-notification runtimeClasspath notification-runtime
assert_selected_lettuce_version :appointment-notification testRuntimeClasspath notification-test-runtime

echo "[PASS] Gradle dependency locking and verification contract"

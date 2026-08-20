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

assert_selected_notification_fixture_lettuce_version() {
    local configuration="appointmentNotificationConsumerFixtureClasspath"
    local output_file="$TEMP_DIR/notification-fixture-lettuce.txt"

    echo "[CHECK] notification-fixture: :$configuration"
    if ! (
        cd -- "$REPOSITORY_ROOT"
        ./gradlew :dependencyInsight \
            --dependency io.lettuce:lettuce-core \
            --configuration "$configuration" \
            --no-daemon \
            --no-configuration-cache \
            --no-parallel \
            --console=plain
    ) >"$output_file" 2>&1; then
        cat "$output_file" >&2
        fail "notification fixture dependencyInsight failed"
    fi

    if ! grep -Eq '^io[.]lettuce:lettuce-core:7[.]5[.]2[.]RELEASE$' "$output_file"; then
        cat "$output_file" >&2
        fail "notification fixture must retain its documented root-BOM selection of 7.5.2.RELEASE"
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

EXPECTED_LOCKFILES_FILE="$TEMP_DIR/expected-lockfiles.txt"
ACTUAL_LOCKFILES_FILE="$TEMP_DIR/actual-lockfiles.txt"
printf '%s\n' "${EXPECTED_LOCKFILES[@]}" | sort >"$EXPECTED_LOCKFILES_FILE"
(
    cd -- "$REPOSITORY_ROOT"
    find . -type f \( -name gradle.lockfile -o -name settings-gradle.lockfile \) \
        -not -path './.gradle/*' \
        -not -path './.git/*' \
        -print | sed 's#^./##' | sort
) >"$ACTUAL_LOCKFILES_FILE"
if ! diff -u "$EXPECTED_LOCKFILES_FILE" "$ACTUAL_LOCKFILES_FILE"; then
    fail "lockfile inventory differs from the expected Gradle project set"
fi

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
assert_selected_notification_fixture_lettuce_version

echo "[PASS] Gradle dependency locking and verification contract"

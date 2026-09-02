#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
REPOSITORY_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly REPOSITORY_ROOT
CATALOG_FILE="$REPOSITORY_ROOT/gradle/libs.versions.toml"
readonly CATALOG_FILE
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/issue253-dependency.XXXXXX")"
readonly TEMP_DIR

cleanup() {
    rm -rf -- "$TEMP_DIR"
}

trap cleanup EXIT

fail() {
    echo "[FAIL] $*" >&2
    exit 1
}

escape_regex() {
    printf '%s' "$1" | sed 's/[][\\.^$*+?(){}|]/\\&/g'
}

assert_selected_version() {
    local label="$1"
    local module="$2"
    local coordinate="$3"
    local target_version="$4"
    shift 4
    local output_file="$TEMP_DIR/$label.txt"
    local coordinate_pattern
    local target_pattern
    local selected_header_regex
    local forbidden_version
    local forbidden_pattern
    local forbidden_header_regex
    local configuration="${DEPENDENCY_INSIGHT_CONFIGURATION:-runtimeClasspath}"

    echo "[CHECK] $coordinate -> $target_version"
    if ! (
        cd -- "$REPOSITORY_ROOT"
        ./gradlew "$module:dependencyInsight" \
            --dependency "$coordinate" \
            --configuration "$configuration" \
            --no-daemon \
            --console=plain
    ) >"$output_file" 2>&1; then
        cat "$output_file" >&2
        fail "$label dependencyInsight failed"
    fi

    coordinate_pattern="$(escape_regex "$coordinate")"
    target_pattern="$(escape_regex "$target_version")"
    selected_header_regex="^${coordinate_pattern}:${target_pattern}( \\(selected by rule\\))?$"
    if ! grep -Eq "$selected_header_regex" "$output_file"; then
        cat "$output_file" >&2
        fail "$label did not select $coordinate:$target_version"
    fi

    for forbidden_version in "$@"; do
        forbidden_pattern="$(escape_regex "$forbidden_version")"
        forbidden_header_regex="^${coordinate_pattern}:${forbidden_pattern}( \\(selected by rule\\))?$"
        if grep -Eq "$forbidden_header_regex" "$output_file"; then
            cat "$output_file" >&2
            fail "$label still exposes forbidden selected version $coordinate:$forbidden_version"
        fi
    done
}

assert_selected_version_for_configuration() {
    local configuration="$1"
    shift
    DEPENDENCY_INSIGHT_CONFIGURATION="$configuration" assert_selected_version "$@"
}

assert_catalog_exposed_version() {
    local expected_line='exposed = "1.4.0"'
    local exposed_line

    exposed_line="$(awk '
        /^\[versions\]$/ { in_versions = 1; next }
        /^\[/ { in_versions = 0 }
        in_versions && /^exposed = / { print; found++ }
        END { if (found != 1) exit 1 }
    ' "$CATALOG_FILE")" || fail "version catalog has no unique Exposed plugin version entry"

    [[ "$exposed_line" == "$expected_line" ]] ||
        fail "version catalog Exposed plugin entry is '$exposed_line', expected '$expected_line'"
}

assert_catalog_exposed_version

assert_selected_version \
    timefold-core \
    :appointment-solver \
    ai.timefold.solver:timefold-solver-core \
    2.4.0 \
    2.2.0
assert_selected_version \
    springdoc-webmvc \
    :appointment-api \
    org.springdoc:springdoc-openapi-starter-webmvc-ui \
    3.1.0 \
    3.0.3
assert_selected_version \
    exposed-core \
    :appointment-core \
    org.jetbrains.exposed:exposed-core \
    1.4.0 \
    1.3.0
assert_selected_version \
    fory-core \
    :appointment-api \
    org.apache.fory:fory-core \
    1.6.0 \
    1.5.0
assert_selected_version \
    fory-kotlin \
    :appointment-api \
    org.apache.fory:fory-kotlin \
    1.6.0 \
    1.5.0
assert_selected_version \
    leader-redis-lettuce \
    :appointment-notification \
    io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce \
    1.0.0-20260824.195548-7 \
    0.5.0
assert_selected_version \
    kafka-clients-messaging \
    :appointment-messaging \
    org.apache.kafka:kafka-clients \
    4.2.1
assert_selected_version \
    kafka-clients-benchmark \
    :appointment-messaging-benchmark \
    org.apache.kafka:kafka-clients \
    4.2.1

assert_selected_version \
    lettuce-core-api-runtime \
    :appointment-api \
    io.lettuce:lettuce-core \
    7.6.0.RELEASE \
    7.5.2.RELEASE
assert_selected_version_for_configuration \
    testRuntimeClasspath \
    lettuce-core-api-test-runtime \
    :appointment-api \
    io.lettuce:lettuce-core \
    7.6.0.RELEASE \
    7.5.2.RELEASE
assert_selected_version \
    lettuce-core-notification-runtime \
    :appointment-notification \
    io.lettuce:lettuce-core \
    7.6.0.RELEASE \
    7.5.2.RELEASE
assert_selected_version_for_configuration \
    testRuntimeClasspath \
    lettuce-core-notification-test-runtime \
    :appointment-notification \
    io.lettuce:lettuce-core \
    7.6.0.RELEASE \
    7.5.2.RELEASE

echo "[PASS] bluetape4k dependency contract"

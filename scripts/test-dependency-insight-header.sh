#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# 실제 계약 스크립트의 순수 함수만 읽어 Gradle 없이 출력 경계를 검증한다.
eval "$(sed -n '/^escape_regex()/,/^assert_selected_version()/p' \
    "$SCRIPT_DIR/verify-dependency-contract.sh" | sed '$d')"

assert_header() {
    local expected="$1" coordinate="$2" version="$3" header="$4"
    local pattern actual=false
    pattern="$(selected_version_header_regex "$coordinate" "$version")"
    if printf '%s\n' "$header" | grep -Eq "$pattern"; then
        actual=true
    fi
    if [[ "$actual" != "$expected" ]]; then
        printf '[FAIL] expected=%s actual=%s header=%s\n' "$expected" "$actual" "$header" >&2
        exit 1
    fi
}

assert_header true org.apache.fory:fory-core 1.7.1 'org.apache.fory:fory-core:1.7.1'
assert_header true org.apache.fory:fory-core 1.7.1 'org.apache.fory:fory-core:1.7.1 (selected by rule)'
assert_header false org.apache.fory:fory-core 1.7.1 'org.apache.fory:fory-core:1.6.0'
assert_header false org.apache.fory:fory-core 1.7.1 'org.apache.fory:fory-core:1.7.1:20260907.131326-7'
assert_header false org.apache.fory:fory-core 1.7.1 'org.apache.fory:fory-core:1x7x1'
assert_header true io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce 1.1.0-SNAPSHOT \
    'io.github.bluetape4k.leader:bluetape4k-leader-redis-lettuce:1.1.0-SNAPSHOT:20260907.131326-7'
assert_header true example:module 1.1.0-SNAPSHOT 'example:module:1.1.0-SNAPSHOT'
assert_header true example:module 1.1.0-SNAPSHOT 'example:module:1.1.0-SNAPSHOT:20260907.131326-7 (selected by rule)'
assert_header false example:module 1.1.0-SNAPSHOT 'example:module:1.0.0-SNAPSHOT:20260907.131326-7'
assert_header false example:module 1.1.0-SNAPSHOT 'example:module:1.1.0-SNAPSHOT:invalid'
assert_header false example:module 1.1.0-SNAPSHOT 'example:module:1.1.0-SNAPSHOT -> 1.2.0-SNAPSHOT'
assert_header false example:module 1.1.0-SNAPSHOT 'other:module:1.1.0-SNAPSHOT'
printf '[PASS] dependencyInsight selected header: 12 cases\n'

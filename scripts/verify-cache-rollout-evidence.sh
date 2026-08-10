#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "사용법: $0 <report.json|-> [--require-live] [--thresholds <thresholds.json>]" >&2
}

if (($# < 1)); then
    usage
    exit 2
fi

report_path=$1
shift
require_live=false
thresholds_path=""

while (($# > 0)); do
    case "$1" in
        --require-live)
            require_live=true
            shift
            ;;
        --thresholds)
            if (($# < 2)); then
                usage
                exit 2
            fi
            thresholds_path=$2
            shift 2
            ;;
        *)
            usage
            exit 2
            ;;
    esac
done

report_json=""
if [[ "$report_path" == "-" ]]; then
    report_json="$(< /dev/stdin)"
fi

REPORT_JSON="$report_json" node --input-type=module - "$report_path" "$require_live" "$thresholds_path" <<'NODE'
import fs from "node:fs";

const [reportPath, requireLiveArg, thresholdsPath] = process.argv.slice(2);
const requireLive = requireLiveArg === "true";

const fail = (message) => {
  console.error(`캐시 rollout evidence 검증 실패: ${message}`);
  process.exit(1);
};

const readJson = (path, stdinValue) => {
  const raw = path === "-" ? stdinValue : fs.readFileSync(path, "utf8");
  try {
    return JSON.parse(raw);
  } catch {
    fail("JSON 형식이 아닙니다");
  }
};

const report = readJson(reportPath, process.env.REPORT_JSON ?? "");
const isRecord = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const requireRecord = (value, name) => {
  if (!isRecord(value)) fail(`${name} object가 필요합니다`);
  return value;
};
const requireValue = (record, name) => {
  if (!(name in record) || record[name] === null || record[name] === undefined) {
    fail(`${name} 필드가 필요합니다`);
  }
  return record[name];
};
const requireBoolean = (record, name) => {
  const value = requireValue(record, name);
  if (typeof value !== "boolean") fail(`${name}은 boolean이어야 합니다`);
  return value;
};
const requireNonNegativeNumber = (record, name, integer = false) => {
  const value = requireValue(record, name);
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0 || (integer && !Number.isInteger(value))) {
    fail(`${name}은 음수가 아닌 수여야 합니다`);
  }
  return value;
};
const requireString = (record, name) => {
  const value = requireValue(record, name);
  if (typeof value !== "string" || value.trim().length === 0) fail(`${name}은 비어 있지 않은 문자열이어야 합니다`);
  return value;
};

const root = requireRecord(report, "report");
if (requireValue(root, "schemaVersion") !== 1) fail("지원하지 않는 schemaVersion입니다");
const environment = requireString(root, "environment");
if (!["local", "staging", "production"].includes(environment)) fail("environment 값이 올바르지 않습니다");
const capturedAt = requireString(root, "capturedAt");
if (Number.isNaN(Date.parse(capturedAt))) fail("capturedAt이 ISO timestamp가 아닙니다");
const deploymentSloEvidence = requireBoolean(root, "deploymentSloEvidence");

const redis = requireRecord(requireValue(root, "redis"), "redis");
requireBoolean(redis, "tls");
requireBoolean(redis, "acl");
if (requireString(redis, "namespace") !== "v3") fail("redis.namespace는 v3이어야 합니다");
if (requireString(redis, "rollbackNamespace") !== "v2") fail("redis.rollbackNamespace는 v2이어야 합니다");

const postgres = requireRecord(requireValue(root, "postgres"), "postgres");
const lockWaitMs = requireNonNegativeNumber(postgres, "lockWaitMs");
const broker = requireRecord(requireValue(root, "broker"), "broker");
const lagSeconds = requireNonNegativeNumber(broker, "lagSeconds");
const cache = requireRecord(requireValue(root, "cache"), "cache");
requireNonNegativeNumber(cache, "hits", true);
requireNonNegativeNumber(cache, "misses", true);
const rollback = requireRecord(requireValue(root, "rollback"), "rollback");
const rollbackResult = requireString(rollback, "result");
if (!["PASS", "FAIL", "NOT_RUN"].includes(rollbackResult)) fail("rollback.result 값이 올바르지 않습니다");

if (thresholdsPath) {
  const thresholds = requireRecord(readJson(thresholdsPath, ""), "thresholds");
  const lockWaitLimit = requireNonNegativeNumber(thresholds, "postgresLockWaitMs");
  const lagLimit = requireNonNegativeNumber(thresholds, "brokerLagSeconds");
  if (lockWaitMs > lockWaitLimit) fail("PostgreSQL lock-wait threshold를 초과했습니다");
  if (lagSeconds > lagLimit) fail("broker lag threshold를 초과했습니다");
}

if (requireLive) {
  if (!deploymentSloEvidence) fail("live 검증에는 deploymentSloEvidence=true가 필요합니다");
  if (environment !== "production") fail("live 검증에는 production environment가 필요합니다");
  if (!redis.tls || !redis.acl) fail("live 검증에는 Redis TLS와 ACL evidence가 필요합니다");
  if (rollbackResult !== "PASS") fail("live 검증에는 rollback.result=PASS가 필요합니다");
}

console.log(`캐시 rollout evidence 검증 통과: ${environment}`);
NODE

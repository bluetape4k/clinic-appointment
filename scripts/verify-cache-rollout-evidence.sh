#!/usr/bin/env bash
set -euo pipefail

usage() {
    echo "사용법: $0 <report.json|-> [--require-live] [--require-production-like] [--thresholds <thresholds.json>]" >&2
}

if (($# < 1)); then
    usage
    exit 2
fi

report_path=$1
shift
require_live=false
require_production_like=false
thresholds_path=""

while (($# > 0)); do
    case "$1" in
        --require-live)
            require_live=true
            shift
            ;;
        --require-production-like)
            require_production_like=true
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

REPORT_JSON="$report_json" node --input-type=module - "$report_path" "$require_live" "$thresholds_path" "$require_production_like" <<'NODE'
import fs from "node:fs";

const [reportPath, requireLiveArg, thresholdsPath, requireProductionLikeArg] = process.argv.slice(2);
const requireLive = requireLiveArg === "true";
const requireProductionLike = requireProductionLikeArg === "true";

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
const evidenceMode = "evidenceMode" in root ? requireString(root, "evidenceMode") : undefined;
if (evidenceMode !== undefined && evidenceMode !== "production-like") fail("evidenceMode는 production-like여야 합니다");
if (requireProductionLike && evidenceMode !== "production-like") {
  fail("production-like 검증에는 evidenceMode=production-like가 필요합니다");
}

const redis = requireRecord(requireValue(root, "redis"), "redis");
requireBoolean(redis, "tls");
requireBoolean(redis, "acl");
if (requireString(redis, "namespace") !== "v3") fail("redis.namespace는 v3이어야 합니다");
if (requireString(redis, "rollbackNamespace") !== "v2") fail("redis.rollbackNamespace는 v2이어야 합니다");

const postgres = requireRecord(requireValue(root, "postgres"), "postgres");
const lockWaitMs = requireNonNegativeNumber(postgres, "lockWaitMs");
const broker = requireRecord(requireValue(root, "broker"), "broker");
const lagSeconds = requireNonNegativeNumber(broker, "lagSeconds");
const consumerLagRecords = "consumerLagRecords" in broker
  ? requireNonNegativeNumber(broker, "consumerLagRecords", true)
  : undefined;
const cache = requireRecord(requireValue(root, "cache"), "cache");
const cacheHits = requireNonNegativeNumber(cache, "hits", true);
const cacheMisses = requireNonNegativeNumber(cache, "misses", true);
const cacheDecodeErrors = "decodeErrors" in cache
  ? requireNonNegativeNumber(cache, "decodeErrors", true)
  : undefined;
const rollback = requireRecord(requireValue(root, "rollback"), "rollback");
const rollbackResult = requireString(rollback, "result");
if (!["PASS", "FAIL", "NOT_RUN"].includes(rollbackResult)) fail("rollback.result 값이 올바르지 않습니다");
const rollbackDurationMs = "durationMs" in rollback
  ? requireNonNegativeNumber(rollback, "durationMs")
  : undefined;

if (evidenceMode === "production-like") {
  if (deploymentSloEvidence) fail("production-like evidence는 deploymentSloEvidence=false여야 합니다");

  const configuration = requireRecord(requireValue(root, "configuration"), "configuration");
  if (requireBoolean(configuration, "flywayTransactionalLock")) {
    fail("production-like configuration은 Flyway transactional lock=false여야 합니다");
  }
  if (requireString(configuration, "redisScheme") !== "redis") fail("production-like Redis scheme은 redis여야 합니다");
  requireBoolean(configuration, "redisTls");
  requireBoolean(configuration, "redisAcl");
  if (requireString(configuration, "writerNamespace") !== "v3") fail("writerNamespace는 v3이어야 합니다");
  if (requireString(configuration, "rollbackNamespace") !== "v2") fail("rollbackNamespace는 v2여야 합니다");

  const execution = requireRecord(requireValue(root, "execution"), "execution");
  const sequence = requireValue(execution, "sequence");
  const expectedSequence = [
    "postgres-migration-and-lock-wait",
    "redis-v3-fixed-window",
    "redis-rollback-drain-restart-v2-warmup",
    "kafka-round-trip-and-offset-lag",
    "redacted-report-write",
  ];
  if (JSON.stringify(sequence) !== JSON.stringify(expectedSequence)) {
    fail("execution.sequence가 실제 production-like 실행 순서와 일치하지 않습니다");
  }
  requireString(execution, "cleanupOwnership");

  if (requireString(redis, "image") !== "redis:8.8") fail("production-like Redis image은 redis:8.8이어야 합니다");
  const v3KeyAssertions = requireNonNegativeNumber(redis, "v3KeyAssertions", true);
  const v3KeyAssertionsPassed = requireNonNegativeNumber(redis, "v3KeyAssertionsPassed", true);
  if (v3KeyAssertions < 1 || v3KeyAssertionsPassed !== v3KeyAssertions) {
    fail("Redis v3 exact-key assertion count가 올바르지 않습니다");
  }

  requireString(postgres, "image");
  if (requireString(postgres, "migration") !== "30") fail("PostgreSQL migration 30 evidence가 필요합니다");
  if (requireNonNegativeNumber(postgres, "appliedMigrationCount", true) < 30) {
    fail("PostgreSQL applied migration count가 부족합니다");
  }
  if (requireString(postgres, "lockProbe") !== "advisory-lock") fail("lockProbe는 advisory-lock이어야 합니다");
  if (requireNonNegativeNumber(postgres, "lockHoldMs") <= 0) fail("lockHoldMs는 양수여야 합니다");

  requireString(broker, "image");
  if (requireString(broker, "lagMetric") !== "committed-end-offset-zero-backlog") {
    fail("production-like broker lagMetric은 committed-end-offset-zero-backlog여야 합니다");
  }
  if (consumerLagRecords === undefined) fail("production-like broker consumerLagRecords가 필요합니다");
  requireNonNegativeNumber(broker, "roundTripSeconds");
  if (consumerLagRecords !== 0 || lagSeconds !== 0) {
    fail("production-like broker는 committed/end offset zero-backlog이어야 합니다");
  }
  if (requireNonNegativeNumber(broker, "recordsProduced", true) < 1 ||
      requireNonNegativeNumber(broker, "recordsConsumed", true) < 1) {
    fail("broker production-like record count가 필요합니다");
  }

  if (cacheDecodeErrors === undefined) fail("production-like cache.decodeErrors가 필요합니다");
  const rollbackFlags = ["trafficDrained", "workerRestarted", "v2Warmup", "v3Preserved"]
    .map((name) => requireBoolean(rollback, name));
  if (rollbackResult !== "PASS" || rollbackDurationMs === undefined || rollbackFlags.some((flag) => !flag)) {
    fail("production-like rollback은 모든 lifecycle 단계가 PASS여야 합니다");
  }

  const assertionTotal = requireNonNegativeNumber(requireRecord(requireValue(root, "assertions"), "assertions"), "total", true);
  const assertionPassed = requireNonNegativeNumber(requireRecord(root.assertions, "assertions"), "passed", true);
  if (assertionTotal < 1 || assertionPassed !== assertionTotal) fail("assertions count가 올바르지 않습니다");
  const test = requireRecord(requireValue(root, "test"), "test");
  requireString(test, "className");
  if (requireNonNegativeNumber(test, "testCount", true) < 1) fail("testCount가 필요합니다");
}

if (thresholdsPath) {
  const thresholds = requireRecord(readJson(thresholdsPath, ""), "thresholds");
  const lockWaitLimit = requireNonNegativeNumber(thresholds, "postgresLockWaitMs");
  const lagLimit = requireNonNegativeNumber(thresholds, "brokerLagSeconds");
  if (lockWaitMs > lockWaitLimit) fail("PostgreSQL lock-wait threshold를 초과했습니다");
  if (lagSeconds > lagLimit) fail("broker lag threshold를 초과했습니다");
  if ("brokerLagRecordsMax" in thresholds) {
    const lagRecordLimit = requireNonNegativeNumber(thresholds, "brokerLagRecordsMax");
    if (consumerLagRecords === undefined) fail("broker.consumerLagRecords가 필요합니다");
    if (consumerLagRecords > lagRecordLimit) fail("broker consumer lag threshold를 초과했습니다");
  }
  if ("rollbackDurationMs" in thresholds) {
    const rollbackDurationLimit = requireNonNegativeNumber(thresholds, "rollbackDurationMs");
    if (rollbackDurationMs === undefined) fail("rollback.durationMs가 필요합니다");
    if (rollbackDurationMs > rollbackDurationLimit) fail("rollback duration threshold를 초과했습니다");
  }
  if ("cacheHitCountMin" in thresholds) {
    const cacheHitMinimum = requireNonNegativeNumber(thresholds, "cacheHitCountMin");
    if (cacheHits < cacheHitMinimum) fail("cache hit minimum threshold에 미달했습니다");
  }
  if ("cacheMissCountMin" in thresholds) {
    const cacheMissMinimum = requireNonNegativeNumber(thresholds, "cacheMissCountMin");
    if (cacheMisses < cacheMissMinimum) fail("cache miss minimum threshold에 미달했습니다");
  }
  if ("cacheDecodeErrorsMax" in thresholds) {
    const cacheDecodeErrorMaximum = requireNonNegativeNumber(thresholds, "cacheDecodeErrorsMax");
    if (cacheDecodeErrors === undefined) fail("cache.decodeErrors가 필요합니다");
    if (cacheDecodeErrors > cacheDecodeErrorMaximum) fail("cache decode-error threshold를 초과했습니다");
  }
}

if (requireLive) {
  if (!deploymentSloEvidence) fail("live 검증에는 deploymentSloEvidence=true가 필요합니다");
  if (environment !== "production") fail("live 검증에는 production environment가 필요합니다");
  if (!redis.tls || !redis.acl) fail("live 검증에는 Redis TLS와 ACL evidence가 필요합니다");
  if (rollbackResult !== "PASS") fail("live 검증에는 rollback.result=PASS가 필요합니다");
}

console.log(`캐시 rollout evidence 검증 통과: ${environment}`);
NODE

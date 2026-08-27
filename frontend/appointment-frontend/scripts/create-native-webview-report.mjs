import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const REPORT_FIELDS = Object.freeze([
  'platform',
  'commit',
  'toolchain',
  'commands',
  'result',
  'generatedAt',
]);
const FORBIDDEN_TERMS = Object.freeze([
  'token',
  'password',
  'secret',
  'raw_output',
  'prompt',
  'fencing',
]);

function rejectUnless(condition, message) {
  if (!condition) throw new Error(message);
}

function containsForbiddenContent(value) {
  const serialized = JSON.stringify(value).toLowerCase();
  return FORBIDDEN_TERMS.some((term) => serialized.includes(term));
}

function parseJsonEnv(name, fallback) {
  const value = process.env[name];
  if (!value) return fallback;
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${name} must contain valid JSON`);
  }
}

function currentCommit() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
}

export function createNativeWebViewReport(input) {
  rejectUnless(
    input && typeof input === 'object' && !Array.isArray(input),
    'report input must be an object',
  );
  const unknownFields = Object.keys(input).filter((field) => !REPORT_FIELDS.includes(field));
  rejectUnless(
    unknownFields.length === 0,
    `unsupported report field: ${unknownFields[0] ?? 'unknown'}`,
  );
  rejectUnless(
    REPORT_FIELDS.every((field) => field in input),
    'report is missing required field',
  );
  rejectUnless(
    input.platform === 'android' || input.platform === 'ios',
    'platform must be android or ios',
  );
  rejectUnless(
    typeof input.commit === 'string' && /^[0-9a-f]{40}$/u.test(input.commit),
    'commit must be a 40-character lowercase SHA-1',
  );
  rejectUnless(
    input.toolchain && typeof input.toolchain === 'object' && !Array.isArray(input.toolchain),
    'toolchain must be an object',
  );
  rejectUnless(Object.keys(input.toolchain).length <= 16, 'toolchain has too many fields');
  rejectUnless(
    Object.entries(input.toolchain).every(
      ([key, value]) =>
        /^[a-z][a-z0-9_.-]{0,63}$/u.test(key) && typeof value === 'string' && value.length <= 200,
    ),
    'toolchain fields must be bounded strings',
  );
  rejectUnless(
    Array.isArray(input.commands) && input.commands.length > 0 && input.commands.length <= 32,
    'commands must be a non-empty bounded list',
  );
  rejectUnless(
    input.commands.every(
      (command) => typeof command === 'string' && command.length > 0 && command.length <= 128,
    ),
    'commands must contain bounded strings',
  );
  rejectUnless(
    input.result === 'passed' || input.result === 'failed',
    'result must be passed or failed',
  );
  rejectUnless(
    typeof input.generatedAt === 'string' && !Number.isNaN(Date.parse(input.generatedAt)),
    'generatedAt must be an ISO timestamp',
  );
  rejectUnless(!containsForbiddenContent(input), 'forbidden report content');

  return {
    schemaVersion: 1,
    generatedAt: input.generatedAt,
    platform: input.platform,
    commit: input.commit,
    toolchain: { ...input.toolchain },
    commands: [...input.commands],
    result: input.result,
  };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const report = createNativeWebViewReport({
    platform: process.env.NATIVE_PLATFORM,
    commit: process.env.NATIVE_COMMIT ?? currentCommit(),
    toolchain: parseJsonEnv('NATIVE_TOOLCHAIN_JSON', {
      runner: process.env.RUNNER_OS ?? process.platform,
    }),
    commands: parseJsonEnv('NATIVE_COMMANDS_JSON', ['cap:sync']),
    result: process.env.NATIVE_RESULT ?? 'passed',
    generatedAt: process.env.NATIVE_GENERATED_AT ?? new Date().toISOString(),
  });
  const outputPath = process.env.NATIVE_REPORT_PATH;
  if (outputPath)
    writeFileSync(outputPath, `${JSON.stringify(report, null, 2)}\n`, {
      encoding: 'utf8',
      flag: 'wx',
    });
  else console.log(JSON.stringify(report, null, 2));
}

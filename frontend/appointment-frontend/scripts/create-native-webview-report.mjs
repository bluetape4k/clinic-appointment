import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const REPORT_FIELDS = Object.freeze([
  'schemaVersion',
  'platform',
  'commit',
  'toolchain',
  'device',
  'commands',
  'interactions',
  'artifacts',
  'result',
  'generatedAt',
]);
const V2_FIELDS = Object.freeze(['device', 'interactions', 'artifacts']);
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

function validateDevice(device) {
  rejectUnless(
    device && typeof device === 'object' && !Array.isArray(device),
    'device must be an object',
  );
  const unknownFields = Object.keys(device).filter(
    (field) => !['profile', 'viewport', 'orientations'].includes(field),
  );
  rejectUnless(unknownFields.length === 0, `unsupported device field: ${unknownFields[0] ?? 'unknown'}`);
  rejectUnless(
    typeof device.profile === 'string' && /^[a-zA-Z0-9_.-]{1,80}$/u.test(device.profile),
    'device profile must be a bounded identifier',
  );
  rejectUnless(
    typeof device.viewport === 'string' && /^\d{2,5}x\d{2,5}$/u.test(device.viewport),
    'device viewport must use WIDTHxHEIGHT',
  );
  rejectUnless(
    Array.isArray(device.orientations) &&
      device.orientations.length > 0 &&
      device.orientations.length <= 4 &&
      new Set(device.orientations).size === device.orientations.length &&
      device.orientations.every((orientation) => orientation === 'portrait' || orientation === 'landscape'),
    'device orientations must be unique portrait/landscape values',
  );
}

function validateInteractions(interactions) {
  rejectUnless(
    Array.isArray(interactions) && interactions.length > 0 && interactions.length <= 16,
    'interactions must be a non-empty bounded list',
  );
  interactions.forEach((interaction) => {
    rejectUnless(
      interaction && typeof interaction === 'object' && !Array.isArray(interaction),
      'interaction must be an object',
    );
    const unknownFields = Object.keys(interaction).filter((field) => !['name', 'result'].includes(field));
    rejectUnless(
      unknownFields.length === 0,
      `unsupported interaction field: ${unknownFields[0] ?? 'unknown'}`,
    );
    rejectUnless(
      typeof interaction.name === 'string' && /^[a-z][a-z0-9-]{0,63}$/u.test(interaction.name),
      'interaction name must be a bounded identifier',
    );
    rejectUnless(
      interaction.result === 'passed' || interaction.result === 'failed',
      'interaction result must be passed or failed',
    );
  });
}

function validateArtifacts(artifacts) {
  rejectUnless(
    Array.isArray(artifacts) && artifacts.length > 0 && artifacts.length <= 32,
    'artifacts must be a non-empty bounded list',
  );
  artifacts.forEach((artifact) => {
    rejectUnless(
      typeof artifact === 'string' &&
        artifact.length > 0 &&
        artifact.length <= 256 &&
        /^[a-zA-Z0-9][a-zA-Z0-9._/-]*$/u.test(artifact) &&
        !artifact.includes('\\') &&
        !artifact.startsWith('/') &&
        !artifact.split('/').some((part) => part === '.' || part === '..'),
      'artifact must be a safe repository-relative path',
    );
  });
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
  const schemaVersion = input.schemaVersion ?? 1;
  rejectUnless(schemaVersion === 1 || schemaVersion === 2, 'schemaVersion must be 1 or 2');
  rejectUnless(
    ['platform', 'commit', 'toolchain', 'commands', 'result', 'generatedAt'].every(
      (field) => field in input,
    ),
    'report is missing required field',
  );
  const hasV2Field = V2_FIELDS.some((field) => field in input);
  rejectUnless(
    schemaVersion === 2 ? V2_FIELDS.every((field) => field in input) : !hasV2Field,
    schemaVersion === 2
      ? 'schemaVersion 2 requires device, interactions, and artifacts'
      : 'schemaVersion 1 cannot contain native UI evidence fields',
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
  if (schemaVersion === 2) {
    validateDevice(input.device);
    validateInteractions(input.interactions);
    validateArtifacts(input.artifacts);
  }
  rejectUnless(!containsForbiddenContent(input), 'forbidden report content');

  const report = {
    schemaVersion,
    generatedAt: input.generatedAt,
    platform: input.platform,
    commit: input.commit,
    toolchain: { ...input.toolchain },
    commands: [...input.commands],
    result: input.result,
  };
  if (schemaVersion === 2) {
    report.device = {
      profile: input.device.profile,
      viewport: input.device.viewport,
      orientations: [...input.device.orientations],
    };
    report.interactions = input.interactions.map(({ name, result }) => ({ name, result }));
    report.artifacts = [...input.artifacts];
  }
  return report;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const schemaVersion = Number(process.env.NATIVE_SCHEMA_VERSION ?? '1');
  const report = createNativeWebViewReport({
    schemaVersion,
    platform: process.env.NATIVE_PLATFORM,
    commit: process.env.NATIVE_COMMIT ?? currentCommit(),
    toolchain: parseJsonEnv('NATIVE_TOOLCHAIN_JSON', {
      runner: process.env.RUNNER_OS ?? process.platform,
    }),
    commands: parseJsonEnv('NATIVE_COMMANDS_JSON', ['cap:sync']),
    ...(schemaVersion === 2
      ? {
          device: parseJsonEnv('NATIVE_DEVICE_JSON', {
            profile: process.env.NATIVE_DEVICE_PROFILE ?? 'unknown',
            viewport: process.env.NATIVE_DEVICE_VIEWPORT ?? '1x1',
            orientations: ['portrait'],
          }),
          interactions: parseJsonEnv('NATIVE_INTERACTIONS_JSON', [
            { name: 'native-ui', result: process.env.NATIVE_RESULT ?? 'passed' },
          ]),
          artifacts: parseJsonEnv('NATIVE_ARTIFACTS_JSON', ['artifacts/native-webview-report.json']),
        }
      : {}),
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

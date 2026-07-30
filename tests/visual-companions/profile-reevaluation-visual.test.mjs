import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { inflateSync } from 'node:zlib';

const ROOT = process.cwd();
const SPEC_DIR = path.join(ROOT, 'docs/superpowers/specs');
const BASE_NAME = '2026-07-30-profile-change-reservation-reevaluation';
const SOURCE = path.join(SPEC_DIR, `${BASE_NAME}-design.md`);
const HTML = {
  en: path.join(SPEC_DIR, `${BASE_NAME}.html`),
  ko: path.join(SPEC_DIR, `${BASE_NAME}.ko.html`),
};
const RUNBOOK = {
  en: path.join(ROOT, 'docs/runbooks/profile-reevaluation.md'),
  ko: path.join(ROOT, 'docs/runbooks/profile-reevaluation.ko.md'),
};
const README = {
  rootEn: path.join(ROOT, 'README.md'),
  rootKo: path.join(ROOT, 'README.ko.md'),
  apiEn: path.join(ROOT, 'appointment-api/README.md'),
  apiKo: path.join(ROOT, 'appointment-api/README.ko.md'),
  eventEn: path.join(ROOT, 'appointment-event/README.md'),
  eventKo: path.join(ROOT, 'appointment-event/README.ko.md'),
};
const ALERTS = path.join(ROOT, 'docs/runbooks/profile-reevaluation-alerts.yml');
const MANIFEST = path.join(ROOT, 'docs/visual-companions/manifest.json');
const PNG = Object.fromEntries(
  ['en', 'ko'].flatMap((locale) =>
    ['light', 'dark'].map((theme) => [
      `${locale}.${theme}`,
      path.join(SPEC_DIR, `${BASE_NAME}.${locale}.${theme}.png`),
    ]),
  ),
);

const FLOW_NODES = [
  'crm-event',
  'trust-merge',
  'fair-dispatch',
  'assessment',
  'state-decision',
  'audit-observe',
  'retry-lease',
  'privacy-stop',
];
const CONFIG_KEYS = [
  'appointment.profile-reevaluation.enabled',
  'appointment.profile-reevaluation.mutation-mode',
  'appointment.profile-reevaluation.clinic-allowlist',
  'appointment.profile-reevaluation.held-target',
  'appointment.profile-reevaluation.proposed-target',
  'appointment.profile-reevaluation.auto-redrive-max',
  'appointment.profile-reevaluation.auto-redrive-cooldown',
];
const METRIC_NAMES = [
  'clinic.profile.reevaluation.events',
  'clinic.profile.reevaluation.jobs',
  'clinic.profile.reevaluation.outcomes',
  'clinic.profile.reevaluation.fair.wait',
  'clinic.profile.reevaluation.processing.duration',
  'clinic.profile.reevaluation.assessment.latency',
  'clinic.profile.reevaluation.operational',
  'clinic.profile.reevaluation.dryrun.parity',
  'clinic.profile.assessment.inflight',
  'clinic.profile.assessment.requests',
];
const RUNBOOK_ANCHORS = [
  'ownership',
  'rollout',
  'slo-burn',
  'oldest-job',
  'failed-jobs',
  'lease-expiry',
  'assessment-saturation',
  'quarantine',
  'redrive',
  'privacy-incident',
  'rollback',
  'unsupported',
];

async function text(file) {
  return readFile(file, 'utf8');
}

function values(pattern, content) {
  return [...content.matchAll(pattern)].map((match) => match[1]);
}

function codeBlocks(content) {
  return values(/```(?:bash|sql|promql)\n([\s\S]*?)```/g, content).map((block) =>
    block.trim(),
  );
}

function parseAlertRules(content) {
  const rules = [];
  let current = null;
  let section = null;
  let multilineKey = null;

  for (const line of content.split(/\r?\n/)) {
    const alert = /^\s*-\s+alert:\s*(\S+)\s*$/.exec(line);
    if (alert) {
      current = { alert: alert[1], labels: {}, annotations: {} };
      rules.push(current);
      section = null;
      multilineKey = null;
      continue;
    }
    if (!current) continue;

    const sectionMatch = /^\s{8}(labels|annotations):\s*$/.exec(line);
    if (sectionMatch) {
      section = sectionMatch[1];
      multilineKey = null;
      continue;
    }
    const top = /^\s{8}(expr|for):\s*(.*?)\s*$/.exec(line);
    if (top) {
      section = null;
      const [, key, raw] = top;
      if (raw === '>-' || raw === '|-') {
        current[key] = '';
        multilineKey = key;
      } else {
        current[key] = raw.replace(/^['"]|['"]$/g, '');
        multilineKey = null;
      }
      continue;
    }
    const nested = /^\s{10}([a-z_]+):\s*(.*?)\s*$/.exec(line);
    if (nested && section) {
      current[section][nested[1]] = nested[2].replace(/^['"]|['"]$/g, '');
      continue;
    }
    if (multilineKey && /^\s{10}\S/.test(line)) {
      current[multilineKey] += `${line.trim()} `;
    }
  }
  return rules;
}

function decodePng(buffer) {
  const signature = buffer.subarray(0, 8).toString('hex');
  assert.equal(signature, '89504e470d0a1a0a', 'invalid PNG signature');

  let offset = 8;
  let width;
  let height;
  let bitDepth;
  let colorType;
  const idat = [];
  while (offset < buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.subarray(offset + 4, offset + 8).toString('ascii');
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }

  assert.equal(bitDepth, 8, 'only 8-bit PNG captures are supported');
  assert.ok([2, 6].includes(colorType), `unsupported PNG color type ${colorType}`);
  const channels = colorType === 6 ? 4 : 3;
  const stride = width * channels;
  const raw = inflateSync(Buffer.concat(idat));
  const pixels = Buffer.alloc(stride * height);
  let sourceOffset = 0;

  function paeth(a, b, c) {
    const p = a + b - c;
    const pa = Math.abs(p - a);
    const pb = Math.abs(p - b);
    const pc = Math.abs(p - c);
    return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
  }

  for (let y = 0; y < height; y += 1) {
    const filter = raw[sourceOffset++];
    const rowOffset = y * stride;
    const previousOffset = rowOffset - stride;
    for (let x = 0; x < stride; x += 1) {
      const rawValue = raw[sourceOffset++];
      const left = x >= channels ? pixels[rowOffset + x - channels] : 0;
      const up = y > 0 ? pixels[previousOffset + x] : 0;
      const upLeft =
        y > 0 && x >= channels ? pixels[previousOffset + x - channels] : 0;
      const predictor =
        filter === 0
          ? 0
          : filter === 1
            ? left
            : filter === 2
              ? up
              : filter === 3
                ? Math.floor((left + up) / 2)
                : filter === 4
                  ? paeth(left, up, upLeft)
                  : assert.fail(`unsupported PNG filter ${filter}`);
      pixels[rowOffset + x] = (rawValue + predictor) & 0xff;
    }
  }

  let opaquePixels = 0;
  let luminance = 0;
  for (let index = 0; index < pixels.length; index += channels) {
    const alpha = channels === 4 ? pixels[index + 3] : 255;
    if (alpha > 0) {
      opaquePixels += 1;
      luminance +=
        0.2126 * pixels[index] +
        0.7152 * pixels[index + 1] +
        0.0722 * pixels[index + 2];
    }
  }
  return {
    width,
    height,
    opaquePixels,
    meanLuminance: luminance / opaquePixels,
  };
}

test('runbooks preserve one operational contract in Korean and English', async () => {
  const [en, ko] = await Promise.all([text(RUNBOOK.en), text(RUNBOOK.ko)]);

  for (const anchor of RUNBOOK_ANCHORS) {
    assert.match(en, new RegExp(`<a id="${anchor}"></a>`), `en #${anchor}`);
    assert.match(ko, new RegExp(`<a id="${anchor}"></a>`), `ko #${anchor}`);
  }
  for (const token of [...CONFIG_KEYS, ...METRIC_NAMES]) {
    assert.ok(en.includes(token), `English runbook must contain ${token}`);
    assert.ok(ko.includes(token), `Korean runbook must contain ${token}`);
  }
  for (const token of [
    'DISABLED',
    'DRY_RUN',
    'APPLY_PROPOSED',
    'APPLY_PROPOSED_AND_HELD',
    'CONFIRMED',
    'scheduling_profile_reevaluation_jobs',
    '/actuator/profileReevaluation',
  ]) {
    assert.ok(en.includes(token), `English runbook must contain ${token}`);
    assert.ok(ko.includes(token), `Korean runbook must contain ${token}`);
  }
  assert.deepEqual(codeBlocks(en), codeBlocks(ko));

  const unsupportedEn = en.slice(en.indexOf('<a id="unsupported"></a>'));
  const unsupportedKo = ko.slice(ko.indexOf('<a id="unsupported"></a>'));
  for (const term of [
    'CONFIRMED',
    'raw profile',
    'feature',
    'score',
    'explanation',
    '5 minutes',
    '30 minutes',
    'unattended redrive',
  ]) {
    assert.ok(unsupportedEn.toLowerCase().includes(term.toLowerCase()), `en ${term}`);
  }
  for (const term of [
    'CONFIRMED',
    '프로필 원문',
    '특징',
    '점수',
    '설명',
    '5분',
    '30분',
    '무인 redrive',
  ]) {
    assert.ok(unsupportedKo.includes(term), `ko ${term}`);
  }
});

test('alert rules pin every operational failure to a runbook anchor', async () => {
  const content = await text(ALERTS);
  const rules = parseAlertRules(content);
  const expected = new Map([
    ['ProfileReevaluationSloBurn', 'slo-burn'],
    ['ProfileReevaluationOldestJob', 'oldest-job'],
    ['ProfileReevaluationFailedJobs', 'failed-jobs'],
    ['ProfileReevaluationLeaseExpiry', 'lease-expiry'],
    ['ProfileReevaluationAssessmentSaturation', 'assessment-saturation'],
    ['ProfileReevaluationQuarantineRepeated', 'quarantine'],
  ]);

  assert.equal(rules.length, expected.size);
  for (const rule of rules) {
    const anchor = expected.get(rule.alert);
    assert.ok(anchor, `unexpected alert ${rule.alert}`);
    assert.ok(rule.expr?.trim(), `${rule.alert} expr`);
    assert.match(rule.for ?? '', /^\d+[smhd]$/, `${rule.alert} for`);
    assert.ok(['warning', 'critical'].includes(rule.labels.severity));
    assert.equal(
      rule.annotations.runbook_url,
      `profile-reevaluation.md#${anchor}`,
    );
  }
});

test('README surfaces keep locale-equivalent links and operational identifiers', async () => {
  const documents = await Promise.all(
    Object.values(README).map(async (file) => [file, await text(file)]),
  );
  for (const [file, content] of documents) {
    assert.match(content, /<a id="profile-reevaluation"><\/a>/, file);
    assert.ok(content.includes('profile-reevaluation'), file);
    assert.ok(content.includes('CONFIRMED'), file);
  }

  const [rootEn, rootKo] = documents.slice(0, 2).map(([, content]) => content);
  for (const root of [rootEn, rootKo]) {
    assert.match(root, /<picture>[\s\S]*prefers-color-scheme:\s*dark[\s\S]*<\/picture>/);
    assert.ok(root.includes(`${BASE_NAME}.`));
    assert.ok(root.includes(`${BASE_NAME}-design.md`));
  }
});

test('HTML companions share flow nodes, explicit themes, and source provenance', async () => {
  const [source, en, ko] = await Promise.all([
    text(SOURCE),
    text(HTML.en),
    text(HTML.ko),
  ]);
  const enNodes = values(/data-flow-node="([^"]+)"/g, en);
  const koNodes = values(/data-flow-node="([^"]+)"/g, ko);

  assert.deepEqual(enNodes, FLOW_NODES);
  assert.deepEqual(koNodes, FLOW_NODES);
  assert.deepEqual(enNodes, koNodes);
  for (const [locale, content] of [
    ['en', en],
    ['ko', ko],
  ]) {
    assert.match(content, new RegExp(`<html[^>]+lang="${locale}"`));
    assert.match(content, /prefers-color-scheme:\s*dark/);
    assert.match(content, /:root\[data-theme="light"\]/);
    assert.match(content, /:root\[data-theme="dark"\]/);
    assert.match(content, /data-status="approved"/);
    assert.match(content, /data-baseline="[0-9a-f]{40}"/);
    assert.ok(content.includes(`${BASE_NAME}-design.md`));
  }
  assert.ok(source.includes(`${BASE_NAME}.html`));
  assert.ok(source.includes(`${BASE_NAME}.ko.html`));
});

test('manifest publishes the approved bilingual workflow companion', async () => {
  const manifest = JSON.parse(await text(MANIFEST));
  const document = manifest.documents.find(
    (candidate) => candidate.id === 'profile-change-reservation-reevaluation',
  );
  assert.ok(document);
  assert.equal(document.status, 'approved');
  assert.equal(document.public, true);
  assert.equal(document.presentation.mode, 'simulation');
  assert.equal(document.presentation.defaultView, 'simulation');
  assert.deepEqual(document.presentation.views, ['simulation']);
  assert.equal(document.locales.en.html, `docs/superpowers/specs/${BASE_NAME}.html`);
  assert.equal(document.locales.ko.html, `docs/superpowers/specs/${BASE_NAME}.ko.html`);
});

test('PNG fallbacks are 1440x900, opaque, and visibly theme-specific', async () => {
  const captures = {};
  for (const [key, file] of Object.entries(PNG)) {
    captures[key] = decodePng(await readFile(file));
    assert.equal(captures[key].width, 1440, key);
    assert.equal(captures[key].height, 900, key);
    assert.equal(captures[key].opaquePixels, 1440 * 900, key);
  }
  for (const locale of ['en', 'ko']) {
    assert.ok(
      captures[`${locale}.light`].meanLuminance -
        captures[`${locale}.dark`].meanLuminance >
        40,
      `${locale} light/dark luminance difference`,
    );
  }
});

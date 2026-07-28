import assert from 'node:assert/strict';
import test from 'node:test';

import {
  validateManifest,
  validatePresentation,
  validateRepositoryRelativePath,
} from '../../scripts/visual-companions/contract.mjs';

function validDocument(overrides = {}) {
  return {
    id: 'appointment-plan-and-capacity',
    source: 'docs/superpowers/specs/appointment.md',
    status: 'approved',
    public: true,
    presentation: {
      mode: 'hybrid',
      defaultView: 'simulation',
      views: ['simulation', 'history'],
    },
    locales: {
      en: {
        title: 'Appointment Plan and Capacity',
        html: 'docs/superpowers/specs/appointment.en.html',
      },
      ko: {
        title: '예약 계획과 수용량',
        html: 'docs/superpowers/specs/appointment.html',
      },
    },
    ...overrides,
  };
}

function validManifest(documents = [validDocument()]) {
  return {
    schemaVersion: 1,
    repository: 'bluetape4k/clinic-appointment',
    documents,
  };
}

test('accepts a valid hybrid publication manifest', () => {
  const manifest = validManifest();
  assert.equal(validateManifest(manifest), manifest);
});

test('rejects duplicate document ids', () => {
  assert.throws(
    () => validateManifest(validManifest([validDocument(), validDocument()])),
    /documents\[1\]\.id.*duplicate/,
  );
});

test('rejects unknown top-level manifest keys', () => {
  assert.throws(
    () => validateManifest({ ...validManifest(), generatedAt: 'now' }),
    /manifest\.generatedAt.*unknown/,
  );
});

test('rejects an unknown presentation mode', () => {
  assert.throws(
    () =>
      validatePresentation({
        mode: 'timeline',
        defaultView: 'history',
        views: ['history'],
      }),
    /presentation\.mode.*timeline/,
  );
});

test('requires the exact view set for single-view modes', () => {
  assert.throws(
    () =>
      validatePresentation({
        mode: 'history',
        defaultView: 'history',
        views: ['history', 'simulation'],
      }),
    /presentation\.views.*history/,
  );
  assert.throws(
    () =>
      validatePresentation({
        mode: 'simulation',
        defaultView: 'simulation',
        views: ['history'],
      }),
    /presentation\.views.*simulation/,
  );
});

test('requires both unique views for hybrid mode', () => {
  assert.throws(
    () =>
      validatePresentation({
        mode: 'hybrid',
        defaultView: 'simulation',
        views: ['simulation'],
      }),
    /presentation\.views.*hybrid/,
  );
  assert.throws(
    () =>
      validatePresentation({
        mode: 'hybrid',
        defaultView: 'simulation',
        views: ['simulation', 'simulation'],
      }),
    /presentation\.views.*duplicate/,
  );
});

test('requires defaultView to be one of views', () => {
  assert.throws(
    () =>
      validatePresentation({
        mode: 'hybrid',
        defaultView: 'overview',
        views: ['simulation', 'history'],
      }),
    /presentation\.defaultView.*overview/,
  );
});

test('requires both English and Korean locale entries', () => {
  const document = validDocument({
    locales: {
      en: validDocument().locales.en,
    },
  });
  assert.throws(
    () => validateManifest(validManifest([document])),
    /documents\[0\]\.locales\.ko.*required/,
  );
});

test('rejects absolute, parent-traversal, and non-normalized paths', () => {
  for (const candidate of [
    '/tmp/appointment.html',
    '../appointment.html',
    'docs/../appointment.html',
    'docs//appointment.html',
  ]) {
    assert.throws(
      () => validateRepositoryRelativePath(candidate, 'candidate'),
      /candidate.*repository-relative/,
    );
  }
});

test('rejects a manifest for another repository', () => {
  assert.throws(
    () =>
      validateManifest({
        ...validManifest(),
        repository: 'bluetape4k/another-repository',
      }),
    /manifest\.repository.*clinic-appointment/,
  );
});

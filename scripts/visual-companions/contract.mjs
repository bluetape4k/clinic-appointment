import path from 'node:path';

const MANIFEST_KEYS = new Set(['schemaVersion', 'repository', 'documents']);
const DOCUMENT_KEYS = new Set([
  'id',
  'source',
  'status',
  'public',
  'presentation',
  'locales',
]);
const PRESENTATION_KEYS = new Set(['mode', 'defaultView', 'views']);
const LOCALE_KEYS = new Set(['title', 'html']);
const LOCALES = ['en', 'ko'];
const MODES = new Set(['history', 'simulation', 'hybrid']);
const VIEWS = new Set(['history', 'simulation']);
const STATUSES = new Set(['draft', 'approved', 'implemented', 'superseded']);

function assertObject(value, field) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${field} must be an object`);
  }
}

function rejectUnknownKeys(value, allowed, field) {
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) {
      throw new Error(`${field}.${key} is unknown`);
    }
  }
}

function assertNonEmptyString(value, field) {
  if (typeof value !== 'string' || value.trim() === '') {
    throw new Error(`${field} must be a non-empty string`);
  }
}

export function validateRepositoryRelativePath(value, field) {
  assertNonEmptyString(value, field);
  const normalized = path.posix.normalize(value);
  if (
    path.posix.isAbsolute(value) ||
    value.includes('\\') ||
    value.includes('//') ||
    normalized !== value ||
    value === '..' ||
    value.startsWith('../')
  ) {
    throw new Error(`${field} must be a normalized repository-relative path`);
  }
  return value;
}

export function validatePresentation(presentation, field = 'presentation') {
  assertObject(presentation, field);
  rejectUnknownKeys(presentation, PRESENTATION_KEYS, field);

  const { mode, defaultView, views } = presentation;
  if (!MODES.has(mode)) {
    throw new Error(`${field}.mode has unsupported value ${String(mode)}`);
  }
  if (!Array.isArray(views) || views.length === 0) {
    throw new Error(`${field}.views must be a non-empty array`);
  }
  for (const view of views) {
    if (!VIEWS.has(view)) {
      throw new Error(`${field}.views has unsupported value ${String(view)}`);
    }
  }
  if (new Set(views).size !== views.length) {
    throw new Error(`${field}.views contains a duplicate`);
  }
  const expectedViews =
    mode === 'hybrid' ? ['simulation', 'history'] : [mode];
  if (
    views.length !== expectedViews.length ||
    expectedViews.some((view) => !views.includes(view))
  ) {
    throw new Error(
      `${field}.views must match the ${mode} presentation contract`,
    );
  }
  if (!views.includes(defaultView)) {
    throw new Error(
      `${field}.defaultView ${String(defaultView)} must be included in views`,
    );
  }
  if (mode !== 'hybrid' && defaultView !== mode) {
    throw new Error(`${field}.defaultView must be ${mode}`);
  }

  return presentation;
}

function validateLocale(locale, field) {
  assertObject(locale, field);
  rejectUnknownKeys(locale, LOCALE_KEYS, field);
  assertNonEmptyString(locale.title, `${field}.title`);
  validateRepositoryRelativePath(locale.html, `${field}.html`);
}

function validateDocument(document, index) {
  const field = `documents[${index}]`;
  assertObject(document, field);
  rejectUnknownKeys(document, DOCUMENT_KEYS, field);

  assertNonEmptyString(document.id, `${field}.id`);
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(document.id)) {
    throw new Error(`${field}.id must be a lowercase kebab-case identifier`);
  }
  validateRepositoryRelativePath(document.source, `${field}.source`);
  if (!STATUSES.has(document.status)) {
    throw new Error(`${field}.status has unsupported value ${document.status}`);
  }
  if (typeof document.public !== 'boolean') {
    throw new Error(`${field}.public must be a boolean`);
  }
  validatePresentation(document.presentation, `${field}.presentation`);

  assertObject(document.locales, `${field}.locales`);
  rejectUnknownKeys(document.locales, new Set(LOCALES), `${field}.locales`);
  for (const locale of LOCALES) {
    if (!(locale in document.locales)) {
      throw new Error(`${field}.locales.${locale} is required`);
    }
    validateLocale(document.locales[locale], `${field}.locales.${locale}`);
  }
}

export function validateManifest(manifest) {
  assertObject(manifest, 'manifest');
  rejectUnknownKeys(manifest, MANIFEST_KEYS, 'manifest');

  if (manifest.schemaVersion !== 1) {
    throw new Error('manifest.schemaVersion must be 1');
  }
  if (manifest.repository !== 'bluetape4k/clinic-appointment') {
    throw new Error(
      'manifest.repository must be bluetape4k/clinic-appointment',
    );
  }
  if (!Array.isArray(manifest.documents) || manifest.documents.length === 0) {
    throw new Error('manifest.documents must be a non-empty array');
  }

  const ids = new Set();
  manifest.documents.forEach((document, index) => {
    validateDocument(document, index);
    if (ids.has(document.id)) {
      throw new Error(`documents[${index}].id is duplicate: ${document.id}`);
    }
    ids.add(document.id);
  });

  return manifest;
}

import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { validateMobileBundle } from './validate-mobile-bundle.mjs';

async function createFixture({ missing, invalidReference, withoutMarker, initialBytes } = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'clinic-mobile-bundle-'));
  const browser = path.join(root, 'dist/appointment-frontend/browser');
  await fs.mkdir(browser, { recursive: true });
  await fs.mkdir(path.join(root, 'src/app/features/calendar'), { recursive: true });
  await fs.mkdir(path.join(root, 'src/app/features/management'), { recursive: true });
  await fs.writeFile(
    path.join(root, 'src/app/app.routes.ts'),
    `
    { path: 'calendar', loadChildren: () => import('./features/calendar/calendar.routes') }
    { path: 'appointments', loadChildren: () => import('./features/appointments/appointments.routes') }
    { path: 'portal', loadChildren: () => import('./features/patient-portal/patient-portal.routes') }
    { path: 'management', loadChildren: () => import('./features/management/management.routes') }
  `,
  );
  await fs.writeFile(
    path.join(root, 'src/app/features/calendar/calendar.routes.ts'),
    'loadComponent: () => import("./calendar-view")',
  );
  await fs.writeFile(
    path.join(root, 'src/app/features/management/management.routes.ts'),
    'loadComponent: () => import("./dashboard")',
  );
  await fs.writeFile(
    path.join(root, 'angular.json'),
    JSON.stringify({
      projects: {
        'appointment-frontend': {
          architect: {
            build: {
              configurations: {
                production: {
                  budgets: [
                    { type: 'initial', maximumError: '1MB' },
                    { type: 'anyComponentStyle', maximumError: '8kB' },
                  ],
                },
              },
            },
          },
        },
      },
    }),
  );

  const markerChunks = {
    'chunk-calendar.js': 'CALENDAR_ROUTES',
    'chunk-appointments.js': 'APPOINTMENT_ROUTES',
    'chunk-portal.js': 'PATIENT_PORTAL_ROUTES',
    'chunk-management.js': 'MANAGEMENT_ROUTES',
  };
  for (const [file, marker] of Object.entries(markerChunks)) {
    if (file !== withoutMarker && file !== missing)
      await fs.writeFile(path.join(browser, file), `export const route = '${marker}'`);
  }
  await fs.writeFile(path.join(browser, 'chunk-shared.js'), 'export const shared = true');
  await fs.writeFile(path.join(browser, 'styles.css'), 'body{}');
  const mainPadding = 'x'.repeat(Math.max(0, (initialBytes ?? 1024) - 500));
  await fs.writeFile(
    path.join(browser, 'main-test.js'),
    `import './chunk-shared.js';${mainPadding}`,
  );
  const missingReference = missing ? `<link rel="modulepreload" href="${missing}">` : '';
  const invalidReferenceMarkup = invalidReference
    ? `<link rel="modulepreload" href="${invalidReference}">`
    : '';
  await fs.writeFile(
    path.join(browser, 'index.html'),
    `
    ${missingReference}
    ${invalidReferenceMarkup}
    <link rel="modulepreload" href="chunk-shared.js">
    <link rel="stylesheet" href="styles.css">
    <script src="main-test.js" type="module"></script>
  `,
  );
  return root;
}

test('valid bundle exposes every lazy route and local index asset', async () => {
  const root = await createFixture();
  const report = validateMobileBundle({ root });
  assert.equal(report.ok, true);
  assert.deepEqual(report.lazyRoutes.sort(), ['appointments', 'calendar', 'management', 'portal']);
  assert.deepEqual(report.referencedAssets, ['main-test.js', 'chunk-shared.js', 'styles.css']);
});

test('missing index reference fails closed', async () => {
  const root = await createFixture({ missing: 'chunk-calendar.js' });
  assert.throws(() => validateMobileBundle({ root }), /missing local asset/);
});

test('path traversal and query references fail closed', async () => {
  const root = await createFixture({ invalidReference: '../chunk-shared.js?cache=1' });
  assert.throws(
    () => validateMobileBundle({ root }),
    /asset reference must be a local path without query|asset reference escapes/,
  );
});

test('route marker and initial budget drift fail closed', async () => {
  const root = await createFixture({
    withoutMarker: 'chunk-management.js',
    initialBytes: 1_100_000,
  });
  assert.throws(() => validateMobileBundle({ root }), /lazy route marker|initial budget/);
});

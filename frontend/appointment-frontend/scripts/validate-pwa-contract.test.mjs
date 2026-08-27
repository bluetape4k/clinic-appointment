import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { PwaContractError, validatePwaContract } from './validate-pwa-contract.mjs';

const EXPECTED_DATA_URL = '/api/public/master-data/**';

async function createFixture({ forbiddenDataGroup = false, missingManifest = false } = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'clinic-pwa-contract-'));
  const browser = path.join(root, 'dist/appointment-frontend/browser');
  await fs.mkdir(path.join(browser, 'icons'), { recursive: true });
  await fs.mkdir(path.join(root, 'src/app/core/interceptors'), { recursive: true });
  await fs.mkdir(path.join(root, 'src/app'), { recursive: true });
  await fs.writeFile(
    path.join(root, 'package.json'),
    JSON.stringify({
      dependencies: { '@angular/service-worker': '^22.1.3' },
      devDependencies: { '@angular/pwa': '^22.1.5' },
    }),
  );
  await fs.writeFile(
    path.join(root, 'angular.json'),
    JSON.stringify({
      projects: {
        'appointment-frontend': {
          architect: { build: { options: { serviceWorker: 'ngsw-config.json' } } },
        },
      },
    }),
  );
  await fs.writeFile(
    path.join(root, 'ngsw-config.json'),
    JSON.stringify({
      assetGroups: [
        {
          name: 'app-shell',
          installMode: 'prefetch',
          updateMode: 'prefetch',
          resources: { files: ['/index.html', '/*.js', '/*.css', '/manifest.webmanifest'] },
        },
      ],
      dataGroups: [
        {
          name: 'public-master-data',
          urls: [forbiddenDataGroup ? '/api/tenant/**' : EXPECTED_DATA_URL],
          cacheConfig: { strategy: 'freshness' },
        },
      ],
      navigationUrls: ['/**', '!/api/**'],
    }),
  );
  await fs.writeFile(
    path.join(root, 'src/app/app.config.ts'),
    "provideServiceWorker('ngsw-worker.js'); withInterceptors([pwaNetworkInterceptor, authInterceptor]);",
  );
  await fs.writeFile(path.join(root, 'src/app/app.html'), '<aside data-pwa-status></aside>');
  await fs.writeFile(
    path.join(root, 'src/app/core/interceptors/pwa-network.interceptor.ts'),
    'ngsw-bypass Cache-Control no-store OFFLINE_MUTATION',
  );
  await fs.writeFile(
    path.join(browser, 'index.html'),
    '<link rel="manifest" href="manifest.webmanifest"><meta name="theme-color" content="#1d4ed8">',
  );
  if (!missingManifest)
    await fs.writeFile(
      path.join(browser, 'manifest.webmanifest'),
      JSON.stringify({
        name: 'Clinic',
        short_name: 'Clinic',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        theme_color: '#1d4ed8',
        icons: [{ src: 'icons/icon.svg' }],
      }),
    );
  await fs.writeFile(path.join(browser, 'ngsw-worker.js'), 'worker');
  await fs.writeFile(path.join(browser, 'icons/icon.svg'), '<svg/>');
  await fs.writeFile(
    path.join(browser, 'ngsw.json'),
    JSON.stringify({
      assetGroups: [
        { name: 'app-shell', urls: ['/index.html', '/manifest.webmanifest', '/icons/icon.svg'] },
      ],
      dataGroups: [
        { name: 'public-master-data', patterns: ['\\/api\\/public\\/master-data\\/.*'] },
      ],
      navigationUrls: [{ positive: false, regex: '^\\/api\\/.*$' }],
    }),
  );
  return root;
}

test('valid PWA production output satisfies the cache boundary', async () => {
  const root = await createFixture();
  const report = validatePwaContract({ root });
  assert.equal(report.ok, true);
  assert.equal(report.dataGroups, 1);
});

test('authenticated data group fails closed', async () => {
  const root = await createFixture({ forbiddenDataGroup: true });
  assert.throws(() => validatePwaContract({ root }), PwaContractError);
});

test('missing installability metadata fails closed', async () => {
  const root = await createFixture({ missingManifest: true });
  assert.throws(() => validatePwaContract({ root }), /missing PWA file:.*manifest/);
});

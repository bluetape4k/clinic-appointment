import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, '..');
const EXPECTED_DATA_URL = '/api/public/master-data/**';
const FORBIDDEN_PATH = /(?:auth|tenant|patient|appointment|admin)/iu;

export class PwaContractError extends Error {
  constructor(report) {
    super(`PWA contract failed: ${report.failures.join('; ')}`);
    this.name = 'PwaContractError';
    this.report = report;
  }
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function readText(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

function requireFile(root, relativePath, failures) {
  const filePath = path.join(root, relativePath);
  if (!fs.existsSync(filePath)) {
    failures.push(`missing PWA file: ${relativePath}`);
    return null;
  }
  return filePath;
}

function isNegativeApiNavigation(url) {
  if (typeof url === 'string') return url === '!/api/**';
  return url?.positive === false && url.regex === '^\\/api\\/.*$';
}

function sourceFailures(root, failures) {
  const packagePath = requireFile(root, 'package.json', failures);
  const angularPath = requireFile(root, 'angular.json', failures);
  const configPath = requireFile(root, 'ngsw-config.json', failures);
  const appConfigPath = requireFile(root, 'src/app/app.config.ts', failures);
  const interceptorPath = requireFile(
    root,
    'src/app/core/interceptors/pwa-network.interceptor.ts',
    failures,
  );
  const appTemplatePath = requireFile(root, 'src/app/app.html', failures);

  if (packagePath) {
    const packageJson = readJson(packagePath);
    if (!packageJson.dependencies?.['@angular/service-worker'])
      failures.push('@angular/service-worker must be a runtime dependency');
    if (!packageJson.devDependencies?.['@angular/pwa'])
      failures.push('@angular/pwa must be a development dependency');
  }

  if (angularPath) {
    const angularJson = readJson(angularPath);
    const serviceWorker =
      angularJson.projects?.['appointment-frontend']?.architect?.build?.options?.serviceWorker;
    if (serviceWorker !== 'ngsw-config.json')
      failures.push('angular build must use ngsw-config.json');
  }

  if (configPath) {
    const config = readJson(configPath);
    const shell = config.assetGroups?.find((group) => group.name === 'app-shell');
    if (!shell || shell.installMode !== 'prefetch' || shell.updateMode !== 'prefetch')
      failures.push('app-shell must prefetch and version static assets');
    const shellFiles = shell?.resources?.files ?? [];
    for (const required of ['/index.html', '/*.js', '/*.css', '/manifest.webmanifest']) {
      if (!shellFiles.includes(required)) failures.push(`app-shell is missing ${required}`);
    }

    const dataGroups = config.dataGroups ?? [];
    if (dataGroups.length !== 1 || dataGroups[0].name !== 'public-master-data')
      failures.push('only public-master-data may be a PWA data group');
    const dataGroup = dataGroups[0];
    if (!dataGroup?.urls?.includes(EXPECTED_DATA_URL))
      failures.push(`data group must allow only ${EXPECTED_DATA_URL}`);
    if (dataGroup?.urls?.some((url) => FORBIDDEN_PATH.test(url)))
      failures.push('data group contains a forbidden authenticated path');
    if (dataGroup?.cacheConfig?.strategy !== 'freshness')
      failures.push('public master data must use bounded freshness caching');

    const navigationUrls = config.navigationUrls ?? [];
    if (!navigationUrls.includes('/**') || !navigationUrls.some(isNegativeApiNavigation))
      failures.push('navigation must exclude /api/** from app-shell fallback');
  }

  if (appConfigPath) {
    const appConfig = readText(appConfigPath);
    if (!appConfig.includes("provideServiceWorker('ngsw-worker.js'"))
      failures.push('app config must register ngsw-worker.js');
    if (!appConfig.includes('pwaNetworkInterceptor, authInterceptor'))
      failures.push('PWA network interceptor must run before auth interceptor');
  }
  if (interceptorPath) {
    const interceptor = readText(interceptorPath);
    for (const marker of ['ngsw-bypass', 'Cache-Control', 'no-store', 'OFFLINE_MUTATION']) {
      if (!interceptor.includes(marker)) failures.push(`PWA interceptor is missing ${marker}`);
    }
  }
  if (appTemplatePath && !readText(appTemplatePath).includes('data-pwa-status'))
    failures.push('app shell must render the PWA status region');
}

function generatedFailures(distDir, failures) {
  const indexPath = requireFile(distDir, 'index.html', failures);
  const manifestPath = requireFile(distDir, 'manifest.webmanifest', failures);
  const ngswPath = requireFile(distDir, 'ngsw.json', failures);
  requireFile(distDir, 'ngsw-worker.js', failures);

  if (indexPath) {
    const index = readText(indexPath);
    if (!/<link\s+rel=["']manifest["']\s+href=["']manifest\.webmanifest["']/iu.test(index))
      failures.push('production index must link manifest.webmanifest');
    if (!index.includes('theme-color')) failures.push('production index must include theme-color');
  }

  if (manifestPath) {
    const manifest = readJson(manifestPath);
    for (const key of ['name', 'short_name', 'start_url', 'scope', 'display', 'theme_color']) {
      if (!manifest[key]) failures.push(`manifest is missing ${key}`);
    }
    if (manifest.display !== 'standalone') failures.push('manifest display must be standalone');
    if (!Array.isArray(manifest.icons) || manifest.icons.length === 0)
      failures.push('manifest must contain an install icon');
  }

  if (ngswPath) {
    const ngsw = readJson(ngswPath);
    const dataGroups = ngsw.dataGroups ?? [];
    if (dataGroups.length !== 1 || dataGroups[0].name !== 'public-master-data')
      failures.push('generated ngsw.json contains an unexpected data group');
    const generatedPattern = dataGroups[0]?.patterns ?? [];
    if (!generatedPattern.some((pattern) => /api.*public.*master-data/u.test(pattern)))
      failures.push('generated ngsw.json is missing public master-data pattern');
    if (generatedPattern.some((pattern) => FORBIDDEN_PATH.test(pattern)))
      failures.push('generated ngsw.json contains a forbidden authenticated pattern');
    const navigationUrls = ngsw.navigationUrls ?? [];
    if (!navigationUrls.some(isNegativeApiNavigation))
      failures.push('generated ngsw.json must exclude /api/** navigation');
    const shellUrls = ngsw.assetGroups?.find((group) => group.name === 'app-shell')?.urls ?? [];
    for (const required of ['/index.html', '/manifest.webmanifest', '/icons/icon.svg']) {
      if (!shellUrls.includes(required))
        failures.push(`generated app-shell is missing ${required}`);
    }
  }
}

export function validatePwaContract({
  root = DEFAULT_PROJECT_ROOT,
  distDir = path.join(root, 'dist/appointment-frontend/browser'),
} = {}) {
  const failures = [];
  sourceFailures(root, failures);
  if (!fs.existsSync(distDir))
    failures.push(`missing WebView directory: ${path.relative(root, distDir)}`);
  else generatedFailures(distDir, failures);

  const report = {
    ok: failures.length === 0,
    distDir: path.relative(root, distDir),
    shellAssets: 0,
    dataGroups: 0,
    failures,
  };
  if (fs.existsSync(path.join(distDir, 'ngsw.json'))) {
    const ngsw = readJson(path.join(distDir, 'ngsw.json'));
    report.shellAssets =
      ngsw.assetGroups?.find((group) => group.name === 'app-shell')?.urls?.length ?? 0;
    report.dataGroups = ngsw.dataGroups?.length ?? 0;
  }
  if (!report.ok) throw new PwaContractError(report);
  return report;
}

function main() {
  try {
    console.log(JSON.stringify(validatePwaContract(), null, 2));
  } catch (error) {
    if (error instanceof PwaContractError) console.error(JSON.stringify(error.report, null, 2));
    else console.error(error.message);
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

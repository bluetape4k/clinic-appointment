import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, '..');

const ROOT_ROUTES = [
  {
    name: 'calendar',
    source: 'src/app/app.routes.ts',
    importPath: "import('./features/calendar/calendar.routes')",
    marker: 'CALENDAR_ROUTES',
  },
  {
    name: 'appointments',
    source: 'src/app/app.routes.ts',
    importPath: "import('./features/appointments/appointments.routes')",
    marker: 'APPOINTMENT_ROUTES',
  },
  {
    name: 'portal',
    source: 'src/app/app.routes.ts',
    importPath: "import('./features/patient-portal/patient-portal.routes')",
    marker: 'PATIENT_PORTAL_ROUTES',
  },
  {
    name: 'management',
    source: 'src/app/app.routes.ts',
    importPath: "import('./features/management/management.routes')",
    marker: 'MANAGEMENT_ROUTES',
  },
];

const CHILD_ROUTES = [
  'src/app/features/calendar/calendar.routes.ts',
  'src/app/features/management/management.routes.ts',
];

export class MobileBundleContractError extends Error {
  constructor(report) {
    super(`Mobile bundle contract failed: ${report.failures.join('; ')}`);
    this.name = 'MobileBundleContractError';
    this.report = report;
  }
}

function readJson(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function readText(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

function parseBudgetBytes(value) {
  const match = String(value ?? '')
    .trim()
    .match(/^(\d+(?:\.\d+)?)\s*(B|KB|MB|GB)?$/i);
  if (!match) return null;
  const multiplier = { B: 1, KB: 1000, MB: 1000 ** 2, GB: 1000 ** 3 };
  return Math.round(Number(match[1]) * (multiplier[match[2]?.toUpperCase()] ?? 1));
}

function extractAttribute(tag, attribute) {
  const match = tag.match(new RegExp(`\\b${attribute}\\s*=\\s*["']([^"']+)["']`, 'i'));
  return match?.[1];
}

function extractIndexAssets(indexHtml) {
  const assets = [];
  for (const match of indexHtml.matchAll(/<script\b[^>]*>/gi)) {
    const src = extractAttribute(match[0], 'src');
    if (src) assets.push({ kind: 'script', reference: src });
  }
  for (const match of indexHtml.matchAll(/<link\b[^>]*>/gi)) {
    const rel = extractAttribute(match[0], 'rel')?.toLowerCase();
    const href = extractAttribute(match[0], 'href');
    if (!href) continue;
    if (rel?.split(/\s+/u).includes('modulepreload'))
      assets.push({ kind: 'modulepreload', reference: href });
    if (rel?.split(/\s+/u).includes('stylesheet'))
      assets.push({ kind: 'stylesheet', reference: href });
    if (rel?.split(/\s+/u).includes('icon')) assets.push({ kind: 'icon', reference: href });
  }
  return assets;
}

function resolveLocalAsset(distDir, reference) {
  if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/iu.test(reference)) {
    throw new Error(`absolute asset reference is not allowed: ${reference}`);
  }
  let decoded;
  try {
    decoded = decodeURIComponent(reference);
  } catch {
    throw new Error(`invalid asset reference encoding: ${reference}`);
  }
  if (/[?#]/u.test(decoded) || decoded.includes('\\')) {
    throw new Error(`asset reference must be a local path without query: ${reference}`);
  }
  const relative = decoded.replace(/^\/+|^(?:\.\/)+/gu, '');
  if (!relative || relative.split('/').includes('..')) {
    throw new Error(`asset reference escapes the WebView directory: ${reference}`);
  }
  const absolute = path.resolve(distDir, relative);
  const relativeToDist = path.relative(distDir, absolute);
  if (relativeToDist.startsWith(`..${path.sep}`) || path.isAbsolute(relativeToDist)) {
    throw new Error(`asset reference escapes the WebView directory: ${reference}`);
  }
  return { absolute, relative: relativeToDist.split(path.sep).join('/') };
}

function productionBudgets(angularConfig) {
  const build = angularConfig.projects?.['appointment-frontend']?.architect?.build;
  const budgets = build?.configurations?.production?.budgets;
  if (!Array.isArray(budgets)) throw new Error('production budgets are missing');
  const initial = budgets.find((budget) => budget.type === 'initial');
  const componentStyle = budgets.find((budget) => budget.type === 'anyComponentStyle');
  if (!initial?.maximumError || !componentStyle?.maximumError) {
    throw new Error('initial and anyComponentStyle budgets are required');
  }
  const initialBytes = parseBudgetBytes(initial.maximumError);
  const componentStyleBytes = parseBudgetBytes(componentStyle.maximumError);
  if (!initialBytes || !componentStyleBytes)
    throw new Error('budget maximumError uses an unsupported unit');
  return { initialBytes, componentStyleBytes };
}

function routeSourceFailures(projectRoot) {
  const failures = [];
  const appRoutesPath = path.join(projectRoot, ROOT_ROUTES[0].source);
  if (!fs.existsSync(appRoutesPath)) return [`missing route source: ${ROOT_ROUTES[0].source}`];
  const appRoutes = readText(appRoutesPath);
  for (const route of ROOT_ROUTES) {
    if (!appRoutes.includes(`path: '${route.name}'`))
      failures.push(`route source is missing ${route.name}`);
    if (!appRoutes.includes(route.importPath))
      failures.push(`route source is not lazy: ${route.name}`);
  }
  for (const relativePath of CHILD_ROUTES) {
    const sourcePath = path.join(projectRoot, relativePath);
    if (!fs.existsSync(sourcePath)) {
      failures.push(`missing child route source: ${relativePath}`);
      continue;
    }
    const source = readText(sourcePath);
    if (!source.includes('loadComponent') || !source.includes('import(')) {
      failures.push(`child route is not lazy: ${relativePath}`);
    }
  }
  return failures;
}

export function validateMobileBundle({
  root = DEFAULT_PROJECT_ROOT,
  distDir = path.join(root, 'dist/appointment-frontend/browser'),
  angularConfig = path.join(root, 'angular.json'),
} = {}) {
  const failures = routeSourceFailures(root);
  let index = 'index.html';
  let references = [];
  let initialBytes = 0;
  let initialBudgetBytes = null;
  let componentStyleBudgetBytes = null;
  let lazyRoutes = [];
  const routeChunks = {};

  if (!fs.existsSync(distDir))
    failures.push(`missing WebView directory: ${path.relative(root, distDir)}`);
  if (!fs.existsSync(angularConfig)) failures.push('missing angular.json');

  if (fs.existsSync(distDir)) {
    const indexPath = path.join(distDir, index);
    if (!fs.existsSync(indexPath)) {
      failures.push('missing WebView index.html');
    } else {
      const indexHtml = readText(indexPath);
      references = extractIndexAssets(indexHtml);
      const initialReferences = references.filter((asset) => asset.kind !== 'icon');
      const resolvedReferences = new Map();
      for (const asset of initialReferences) {
        try {
          const resolved = resolveLocalAsset(distDir, asset.reference);
          resolvedReferences.set(resolved.relative, resolved.absolute);
          if (!fs.existsSync(resolved.absolute))
            failures.push(`missing local asset: ${resolved.relative}`);
        } catch (error) {
          failures.push(error.message);
        }
      }
      for (const [relative, absolute] of resolvedReferences) {
        if (fs.existsSync(absolute)) initialBytes += fs.statSync(absolute).size;
        if (!relative.endsWith('.js') && !relative.endsWith('.css'))
          failures.push(`initial reference is not a bundle asset: ${relative}`);
      }

      const mainAsset = references.find(
        (asset) => asset.kind === 'script' && path.basename(asset.reference).startsWith('main-'),
      );
      if (!mainAsset) failures.push('index.html does not reference the main module');
      const jsFiles = fs.readdirSync(distDir).filter((file) => file.endsWith('.js'));
      for (const route of ROOT_ROUTES) {
        const matches = jsFiles.filter(
          (file) =>
            file !== path.basename(mainAsset?.reference ?? '') &&
            readText(path.join(distDir, file)).includes(route.marker),
        );
        if (matches.length === 0) failures.push(`lazy route marker is missing: ${route.name}`);
        else {
          routeChunks[route.name] = matches;
          lazyRoutes.push(route.name);
        }
      }
    }
  }

  if (fs.existsSync(angularConfig)) {
    try {
      const budgets = productionBudgets(readJson(angularConfig));
      initialBudgetBytes = budgets.initialBytes;
      componentStyleBudgetBytes = budgets.componentStyleBytes;
      if (initialBytes > initialBudgetBytes)
        failures.push(`initial budget exceeded: ${initialBytes} > ${initialBudgetBytes}`);
    } catch (error) {
      failures.push(error.message);
    }
  }

  const report = {
    ok: failures.length === 0,
    index,
    initialBytes,
    initialBudgetBytes,
    componentStyleBudgetBytes,
    lazyRoutes,
    routeChunks,
    referencedAssets: references.map((asset) => asset.reference),
    failures,
  };
  if (!report.ok) throw new MobileBundleContractError(report);
  return report;
}

function main() {
  try {
    console.log(JSON.stringify(validateMobileBundle(), null, 2));
  } catch (error) {
    if (error instanceof MobileBundleContractError) {
      console.error(JSON.stringify(error.report, null, 2));
    } else {
      console.error(error.message);
    }
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

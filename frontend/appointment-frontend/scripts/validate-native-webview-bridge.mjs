import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_PROJECT_ROOT = path.resolve(SCRIPT_DIRECTORY, '..');
const DEEP_LINK_SCHEME = 'io.bluetape4k.clinic.appointment';
const DEEP_LINK_HOST = 'open';

export class NativeWebViewBridgeContractError extends Error {
  constructor(report) {
    super(`Native WebView bridge contract failed: ${report.failures.join('; ')}`);
    this.name = 'NativeWebViewBridgeContractError';
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
    failures.push(`missing bridge contract file: ${relativePath}`);
    return null;
  }
  return filePath;
}

function validatePackage(root, failures) {
  const packagePath = requireFile(root, 'package.json', failures);
  if (!packagePath) return;
  const packageJson = readJson(packagePath);
  const appVersion = packageJson.dependencies?.['@capacitor/app'];
  if (!appVersion) failures.push('@capacitor/app must be a runtime dependency');
  if (appVersion && appVersion !== '8.1.1')
    failures.push(`@capacitor/app must stay pinned at 8.1.1: ${appVersion}`);
}

function validateSource(root, failures) {
  const parserPath = requireFile(root, 'src/app/core/api/native-deep-link.ts', failures);
  const bridgePath = requireFile(
    root,
    'src/app/core/services/native-webview-bridge.service.ts',
    failures,
  );
  const appPath = requireFile(root, 'src/app/app.ts', failures);
  if (parserPath) {
    const parser = readText(parserPath);
    for (const marker of [
      `NATIVE_DEEP_LINK_SCHEME = '${DEEP_LINK_SCHEME}'`,
      `NATIVE_DEEP_LINK_HOST = '${DEEP_LINK_HOST}'`,
      'parseNativeDeepLink',
      'Object.freeze',
    ]) {
      if (!parser.includes(marker)) failures.push(`deep-link parser is missing ${marker}`);
    }
  }
  if (bridgePath) {
    const bridge = readText(bridgePath);
    for (const marker of [
      "NATIVE_WEBVIEW_EVENT_NAME = 'clinic.native.navigation.v1'",
      "addListener('appUrlOpen'",
      'getLaunchUrl()',
      'allowedTenants()',
      'markUnauthorized()',
      'dispatchNavigationEvent',
    ]) {
      if (!bridge.includes(marker)) failures.push(`native bridge is missing ${marker}`);
    }
    if (/localStorage|sessionStorage/u.test(bridge))
      failures.push('native bridge must not persist authentication in browser storage');
  }
  if (appPath && !readText(appPath).includes('nativeWebViewBridge.start()'))
    failures.push('app shell must start native WebView bridge');
}

function validateAndroid(root, failures) {
  const manifestPath = requireFile(root, 'android/app/src/main/AndroidManifest.xml', failures);
  if (!manifestPath) return;
  const manifest = readText(manifestPath);
  for (const marker of [
    'android.intent.action.VIEW',
    'android.intent.category.DEFAULT',
    'android.intent.category.BROWSABLE',
    'android:host="open"',
    'android:scheme="@string/custom_url_scheme"',
  ]) {
    if (!manifest.includes(marker))
      failures.push(`Android deep-link metadata is missing ${marker}`);
  }
}

function validateIos(root, failures) {
  const plistPath = requireFile(root, 'ios/App/App/Info.plist', failures);
  if (!plistPath) return;
  const plist = readText(plistPath);
  if (!plist.includes('<key>CFBundleURLTypes</key>'))
    failures.push('iOS Info.plist is missing CFBundleURLTypes');
  if (!plist.includes(`<string>${DEEP_LINK_SCHEME}</string>`))
    failures.push(`iOS Info.plist is missing ${DEEP_LINK_SCHEME}`);
}

export function validateNativeWebViewBridgeContract({ root = DEFAULT_PROJECT_ROOT } = {}) {
  const failures = [];
  validatePackage(root, failures);
  validateSource(root, failures);
  validateAndroid(root, failures);
  validateIos(root, failures);
  const report = {
    ok: failures.length === 0,
    scheme: DEEP_LINK_SCHEME,
    host: DEEP_LINK_HOST,
    failures,
  };
  if (!report.ok) throw new NativeWebViewBridgeContractError(report);
  return report;
}

function main() {
  try {
    console.log(JSON.stringify(validateNativeWebViewBridgeContract(), null, 2));
  } catch (error) {
    if (error instanceof NativeWebViewBridgeContractError)
      console.error(JSON.stringify(error.report, null, 2));
    else console.error(error.message);
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

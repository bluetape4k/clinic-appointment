import { readFileSync } from 'node:fs';

const REQUIRED_MARKERS = Object.freeze([
  'workflow_dispatch:',
  'inputs:',
  'ref:',
  'expected_sha:',
  'actions/checkout@',
  'git rev-parse HEAD',
  'npm run cap:sync',
  '(cd frontend/appointment-frontend && ./android/gradlew',
  'android-webview',
  'ios-webview',
  'adb shell am start',
  'xcrun simctl openurl',
  'native-webview-report.json',
  'actions/upload-artifact@',
]);

export function validateNativeWebViewWorkflow(content) {
  const missing = REQUIRED_MARKERS.filter((marker) => !content.includes(marker));
  if (missing.length > 0) throw new Error(`missing workflow markers: ${missing.join(', ')}`);
  const androidSection = content.split('  ios-webview:', 1)[0];
  if (androidSection.includes('set -euo pipefail')) {
    throw new Error('native workflow script must use POSIX sh options (set -eu)');
  }
  return { ok: true, missing: [] };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const workflowPath = new URL('../../../.github/workflows/native-webview-ci.yml', import.meta.url);
  const content = readFileSync(workflowPath, 'utf8');
  console.log(JSON.stringify(validateNativeWebViewWorkflow(content), null, 2));
}

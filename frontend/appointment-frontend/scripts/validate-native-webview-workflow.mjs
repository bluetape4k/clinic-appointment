import { readFileSync } from 'node:fs';

const REQUIRED_MARKERS = Object.freeze([
  'workflow_dispatch:',
  'inputs:',
  'ref:',
  'expected_sha:',
  'actions/checkout@',
  'git rev-parse HEAD',
  'npm run cap:sync',
  '(cd frontend/appointment-frontend/android && ./gradlew',
  'emulator-boot-timeout: 1200',
  'android-webview',
  'ios-webview',
  'adb shell am start',
  'adb shell am start -W -a android.intent.action.VIEW -d',
  'connectedDebugAndroidTest',
  'xcodebuild test',
  'xcrun simctl openurl',
  'native-webview-report.json',
  'NATIVE_SCHEMA_VERSION',
  'NATIVE_DEVICE_JSON',
  'NATIVE_INTERACTIONS_JSON',
  'NATIVE_ARTIFACTS_JSON',
  'test-results.xml',
  'screenshot.png',
  'actions/upload-artifact@',
  'Capture Android native UI artifacts',
  'Capture iOS native UI artifacts',
  'continue-on-error: true',
  'if: always()',
  'Enforce Android native UI result',
  'Enforce iOS native UI result',
]);

const DEEP_LINK_COMMAND_PATTERN =
  /^\s*adb shell am start -W -a android\.intent\.action\.VIEW -d 'io\.bluetape4k\.clinic\.appointment:\/\/open\/tenant-default\/calendar\?view=week&date=2026-08-27'\s*$/u;

export function validateNativeWebViewWorkflow(content) {
  const missing = REQUIRED_MARKERS.filter((marker) => !content.includes(marker));
  if (missing.length > 0) throw new Error(`missing workflow markers: ${missing.join(', ')}`);
  const androidSection =
    content.split('  android-webview:', 2)[1]?.split('  ios-webview:', 1)[0] ?? '';
  if (!/java-version:\s*["']21["']/u.test(androidSection)) {
    throw new Error('Android native workflow must use Java 21 for the Capacitor Gradle toolchain');
  }
  if (androidSection.includes('set -euo pipefail')) {
    throw new Error('native workflow script must use POSIX sh options (set -eu)');
  }
  if (!/gradlew[^\n]*connectedDebugAndroidTest/u.test(androidSection)) {
    throw new Error('Android native UI test must run connectedDebugAndroidTest');
  }
  const iosSection = content.split('  ios-webview:', 2)[1] ?? '';
  if (!/xcodebuild\s+\\\s*test/u.test(iosSection)) {
    throw new Error('iOS native UI test must run xcodebuild test');
  }
  const deepLinkCommands = content
    .split('\n')
    .filter((line) => line.includes('adb shell am start -W -a android.intent.action.VIEW -d'));
  if (!deepLinkCommands.every((line) => DEEP_LINK_COMMAND_PATTERN.test(line))) {
    throw new Error('Android deep-link command must be a single device-shell-safe invocation');
  }
  return { ok: true, missing: [] };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const workflowPath = new URL('../../../.github/workflows/native-webview-ci.yml', import.meta.url);
  const content = readFileSync(workflowPath, 'utf8');
  console.log(JSON.stringify(validateNativeWebViewWorkflow(content), null, 2));
}

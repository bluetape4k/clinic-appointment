import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { validateNativeWebViewWorkflow } from './validate-native-webview-workflow.mjs';

const workflowPath = new URL('../../../.github/workflows/native-webview-ci.yml', import.meta.url);
const mirroredWorkflowPath = new URL('../../../.github/workflows/frontend-ci.yml', import.meta.url);

test('native workflow는 exact ref, 양 플랫폼 job, smoke command와 report artifact를 가진다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  const result = validateNativeWebViewWorkflow(content);
  assert.equal(result.ok, true);
  assert.deepEqual(result.missing, []);
});

test('required marker가 빠진 workflow는 fail-closed 된다', () => {
  assert.throws(
    () => validateNativeWebViewWorkflow('name: incomplete\non: workflow_dispatch\n'),
    /missing workflow markers/u,
  );
});

test('Android emulator runner script가 POSIX sh에서 실행 가능한 옵션을 사용한다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  assert.throws(
    () => validateNativeWebViewWorkflow(content.replaceAll('set -eu', 'set -euo pipefail')),
    /POSIX sh options/u,
  );
});

test('Android native Gradle toolchain은 Java 21을 사용한다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  assert.throws(
    () =>
      validateNativeWebViewWorkflow(content.replace('java-version: "21"', 'java-version: "25"')),
    /Java 21/u,
  );
});

test('Android deep-link는 device shell에서 해석 가능한 단일 호출이다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  assert.throws(
    () =>
      validateNativeWebViewWorkflow(
        content.replace(
          "?view=week&date=2026-08-27'",
          "?view=week&date=2026-08-27' io.bluetape4k.clinic.appointment",
        ),
      ),
    /device-shell-safe/u,
  );
});

test('native UI workflow는 Android instrumentation과 iOS XCTest를 별도 실행한다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  assert.throws(
    () => validateNativeWebViewWorkflow(content.replace(/connectedDebugAndroidTest/g, '')),
    /Android native UI test|missing workflow markers/u,
  );
  assert.throws(
    () => validateNativeWebViewWorkflow(content.replace('xcodebuild \\\n            test', 'xcodebuild \\\n            build')),
    /iOS native UI test|missing workflow markers/u,
  );
});

test('native UI report는 device·interaction·artifact schema를 CI env로 전달한다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  for (const marker of [
    'NATIVE_SCHEMA_VERSION',
    'NATIVE_DEVICE_JSON',
    'NATIVE_INTERACTIONS_JSON',
    'NATIVE_ARTIFACTS_JSON',
    'test-results.xml',
    'screenshot.png',
    'xcodebuild test',
  ]) {
    assert.equal(content.includes(marker), true, `missing native UI marker: ${marker}`);
  }
});

test('Android runner action은 repository root에서 native artifact를 frontend 경로에 기록한다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  const androidSection = content.split('  android-webview:', 2)[1]?.split('  ios-webview:', 1)[0] ?? '';
  assert.match(
    androidSection,
    /mkdir -p frontend\/appointment-frontend\/artifacts\/native-android-ui/u,
  );
  assert.match(
    androidSection,
    /frontend\/appointment-frontend\/artifacts\/native-android-ui\/logcat-live\.txt/u,
  );
});

test('Android runner action은 AOSP emulator를 이미 프로비저닝된 상태로 고정한다', () => {
  const content = readFileSync(workflowPath, 'utf8');
  const androidSection = content.split('  android-webview:', 2)[1]?.split('  ios-webview:', 1)[0] ?? '';
  assert.match(androidSection, /target:\s*default/u);
  for (const command of [
    'settings put global device_provisioned 1',
    'settings put secure user_setup_complete 1',
    'settings put global setup_wizard_has_run 1',
  ]) {
    assert.equal(
      androidSection.includes(`adb -s "$android_serial" shell ${command}`),
      true,
      `missing emulator provisioning command: ${command}`,
    );
  }
});

test('mirrored frontend workflow도 native UI와 exact dispatch contract를 유지한다', () => {
  const content = readFileSync(mirroredWorkflowPath, 'utf8');
  for (const marker of [
    'if: github.event_name == \'workflow_dispatch\'',
    'EXPECTED_SHA: ${{ github.sha }}',
    'connectedDebugAndroidTest',
    'xcodebuild test',
    'NATIVE_SCHEMA_VERSION',
    'NATIVE_DEVICE_JSON',
    'NATIVE_INTERACTIONS_JSON',
    'NATIVE_ARTIFACTS_JSON',
    'actions/upload-artifact@',
    'if: always()',
    'Enforce Android native UI result',
    'Enforce iOS native UI result',
  ]) {
    assert.equal(content.includes(marker), true, `missing mirrored native UI marker: ${marker}`);
  }
  assert.match(content, /test "\$\(git rev-parse HEAD\)" = "\$EXPECTED_SHA"/u);
  assert.match(content, /xcodebuild\s+\\\s+test/u);
});

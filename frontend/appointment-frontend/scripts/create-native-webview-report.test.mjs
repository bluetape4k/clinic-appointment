import assert from 'node:assert/strict';
import test from 'node:test';
import { createNativeWebViewReport } from './create-native-webview-report.mjs';

const baseInput = Object.freeze({
  platform: 'android',
  commit: 'a'.repeat(40),
  toolchain: { java: '25', android: '35' },
  commands: ['cap:sync', 'assembleDebug', 'launch', 'deep-link'],
  result: 'passed',
  generatedAt: '2026-08-27T13:45:00Z',
});

const nativeUiInput = Object.freeze({
  ...baseInput,
  schemaVersion: 2,
  device: {
    profile: 'pixel_5',
    viewport: '1080x1920',
    orientations: ['portrait', 'landscape'],
  },
  interactions: [
    { name: 'bottom-tab-route', result: 'passed' },
    { name: 'focus-keyboard-viewport', result: 'passed' },
    { name: 'orientation-safe-area', result: 'passed' },
  ],
  artifacts: [
    'artifacts/native-android-ui/report.json',
    'artifacts/native-android-ui/test-results.xml',
    'artifacts/native-android-ui/screenshot.png',
  ],
});

test('native report는 exact commit과 platform/toolchain/command 결과를 보존한다', () => {
  const report = createNativeWebViewReport(baseInput);

  assert.deepEqual(report, {
    schemaVersion: 1,
    generatedAt: baseInput.generatedAt,
    platform: baseInput.platform,
    commit: baseInput.commit,
    toolchain: baseInput.toolchain,
    commands: baseInput.commands,
    result: 'passed',
  });
});

test('native report는 failed 결과도 성공으로 정규화하지 않는다', () => {
  const report = createNativeWebViewReport({ ...baseInput, result: 'failed' });
  assert.equal(report.result, 'failed');
});

test('native report는 credential/raw output field를 거부한다', () => {
  assert.throws(
    () => createNativeWebViewReport({ ...baseInput, raw_output: 'private output' }),
    /unsupported report field/u,
  );
  assert.throws(
    () => createNativeWebViewReport({ ...baseInput, commands: ['token=secret'] }),
    /forbidden report content/u,
  );
});

test('native report는 허용되지 않은 result와 malformed commit을 거부한다', () => {
  assert.throws(() => createNativeWebViewReport({ ...baseInput, result: 'skipped' }), /result/u);
  assert.throws(() => createNativeWebViewReport({ ...baseInput, commit: 'short' }), /commit/u);
});

test('native UI report는 device·viewport·orientation·interaction·artifact 증거를 보존한다', () => {
  const report = createNativeWebViewReport(nativeUiInput);

  assert.deepEqual(report, {
    schemaVersion: 2,
    generatedAt: nativeUiInput.generatedAt,
    platform: nativeUiInput.platform,
    commit: nativeUiInput.commit,
    toolchain: nativeUiInput.toolchain,
    device: nativeUiInput.device,
    commands: nativeUiInput.commands,
    interactions: nativeUiInput.interactions,
    artifacts: nativeUiInput.artifacts,
    result: nativeUiInput.result,
  });
});

test('schema v1 report는 native UI evidence field를 혼입하지 않는다', () => {
  assert.throws(
    () => createNativeWebViewReport({ ...baseInput, device: nativeUiInput.device }),
    /schemaVersion 1/u,
  );
});

test('native UI report는 bounded evidence와 안전한 상대 artifact path만 허용한다', () => {
  assert.throws(
    () => createNativeWebViewReport({ ...nativeUiInput, device: { ...nativeUiInput.device, viewport: 'unknown' } }),
    /device/u,
  );
  assert.throws(
    () => createNativeWebViewReport({ ...nativeUiInput, interactions: [{ name: 'bad interaction', result: 'passed' }] }),
    /interaction/u,
  );
  assert.throws(
    () => createNativeWebViewReport({ ...nativeUiInput, artifacts: ['/tmp/native-report.json'] }),
    /artifact/u,
  );
  assert.throws(
    () => createNativeWebViewReport({ ...nativeUiInput, artifacts: ['artifacts/../secret.txt'] }),
    /artifact/u,
  );
});

test('native UI report는 credential와 raw output이 nested evidence에 섞이는 것을 거부한다', () => {
  assert.throws(
    () => createNativeWebViewReport({
      ...nativeUiInput,
      interactions: [{ name: 'bottom-tab-route', result: 'passed', raw_output: 'secret' }],
    }),
    /unsupported interaction field/u,
  );
  assert.throws(
    () => createNativeWebViewReport({
      ...nativeUiInput,
      artifacts: ['artifacts/password-reset.txt'],
    }),
    /forbidden report content/u,
  );
});

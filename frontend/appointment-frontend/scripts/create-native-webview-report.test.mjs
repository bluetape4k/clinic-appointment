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

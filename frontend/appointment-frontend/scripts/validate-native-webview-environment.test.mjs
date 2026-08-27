import assert from 'node:assert/strict';
import test from 'node:test';
import { collectNativeEnvironment } from './validate-native-webview-environment.mjs';

test('환경 probe는 iOS와 Android command 상태를 target별로 분리한다', () => {
  const result = collectNativeEnvironment({
    commandRunner: (command) =>
      command === 'xcodebuild'
        ? { status: 0, stdout: 'Xcode 16.4\n', stderr: '' }
        : command === 'xcrun'
          ? { status: 0, stdout: 'iPhone 16 (Shutdown)\n', stderr: '' }
          : { status: 127, stdout: '', stderr: 'not found' },
  });

  assert.equal(result.commands.xcodebuild.available, true);
  assert.equal(result.commands.adb.available, false);
  assert.equal(result.commands.sdkmanager.available, false);
  assert.equal(result.targets.ios, true);
  assert.equal(result.targets.android, false);
});

test('command 실패는 raw stderr 없이 안전한 reason으로 기록한다', () => {
  const result = collectNativeEnvironment({
    commandRunner: () => ({ status: 1, stdout: '', stderr: 'private machine detail' }),
  });

  assert.equal(result.commands.xcodebuild.available, false);
  assert.equal(result.commands.xcodebuild.reason, 'command-failed');
  assert.equal(JSON.stringify(result).includes('private machine detail'), false);
});

test('version line은 bounded metadata로 정규화된다', () => {
  const result = collectNativeEnvironment({
    commandRunner: () => ({ status: 0, stdout: `${'v'.repeat(300)}\nsecond line`, stderr: '' }),
  });

  assert.equal(result.commands.xcodebuild.version.length, 200);
  assert.equal(result.generatedBy, 'validate-native-webview-environment');
});

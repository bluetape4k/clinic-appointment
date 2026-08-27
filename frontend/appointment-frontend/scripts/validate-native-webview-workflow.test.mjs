import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import { validateNativeWebViewWorkflow } from './validate-native-webview-workflow.mjs';

const workflowPath = new URL('../../../.github/workflows/native-webview-ci.yml', import.meta.url);

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

import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  NativeWebViewBridgeContractError,
  validateNativeWebViewBridgeContract,
} from './validate-native-webview-bridge.mjs';

async function createFixture({ missingAndroid = false, wrongScheme = false } = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'clinic-native-bridge-'));
  await fs.mkdir(path.join(root, 'src/app/core/api'), { recursive: true });
  await fs.mkdir(path.join(root, 'src/app/core/services'), { recursive: true });
  await fs.mkdir(path.join(root, 'android/app/src/main'), { recursive: true });
  await fs.mkdir(path.join(root, 'ios/App/App'), { recursive: true });
  await fs.writeFile(
    path.join(root, 'package.json'),
    JSON.stringify({ dependencies: { '@capacitor/app': '8.1.1' } }),
  );
  await fs.writeFile(
    path.join(root, 'src/app/core/api/native-deep-link.ts'),
    "export const NATIVE_DEEP_LINK_SCHEME = 'io.bluetape4k.clinic.appointment'; export const NATIVE_DEEP_LINK_HOST = 'open'; export function parseNativeDeepLink() {} Object.freeze({});",
  );
  await fs.writeFile(
    path.join(root, 'src/app/core/services/native-webview-bridge.service.ts'),
    "export const NATIVE_WEBVIEW_EVENT_NAME = 'clinic.native.navigation.v1'; addListener('appUrlOpen'); getLaunchUrl(); allowedTenants(); markUnauthorized(); dispatchNavigationEvent();",
  );
  await fs.writeFile(path.join(root, 'src/app/app.ts'), 'void this.nativeWebViewBridge.start();');
  if (!missingAndroid)
    await fs.writeFile(
      path.join(root, 'android/app/src/main/AndroidManifest.xml'),
      'android.intent.action.VIEW android.intent.category.DEFAULT android.intent.category.BROWSABLE android:host="open" android:scheme="@string/custom_url_scheme"',
    );
  await fs.writeFile(
    path.join(root, 'ios/App/App/Info.plist'),
    `<key>CFBundleURLTypes</key><string>${wrongScheme ? 'wrong.scheme' : 'io.bluetape4k.clinic.appointment'}</string>`,
  );
  return root;
}

test('valid native bridge metadata satisfies the contract', async () => {
  const report = validateNativeWebViewBridgeContract({ root: await createFixture() });
  assert.equal(report.ok, true);
  assert.equal(report.host, 'open');
});

test('missing Android metadata fails closed', async () => {
  await assert.rejects(
    async () =>
      validateNativeWebViewBridgeContract({ root: await createFixture({ missingAndroid: true }) }),
    NativeWebViewBridgeContractError,
  );
});

test('wrong iOS scheme fails closed', async () => {
  await assert.rejects(
    async () =>
      validateNativeWebViewBridgeContract({ root: await createFixture({ wrongScheme: true }) }),
    /iOS Info.plist is missing/,
  );
});

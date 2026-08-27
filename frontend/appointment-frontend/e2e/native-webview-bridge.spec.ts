import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';

const TENANT_CODE = 'tenant-default';

function makeWorkforceToken(): string {
  const encode = (value: string): string => Buffer.from(value).toString('base64url');
  const payload = {
    exp: Math.floor(Date.now() / 1000) + 3600,
    roles: ['ROLE_STAFF'],
    allowedTenants: [TENANT_CODE],
    clinicId: 1,
  };
  return `${encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${encode(JSON.stringify(payload))}.fixture`;
}

async function installBrowserFixture(page: Page): Promise<void> {
  await page.addInitScript(
    ({ token, tenantCode }) => {
      (
        globalThis as typeof globalThis & {
          __CLINIC_WORKFORCE_AUTH__?: { token: string; tenantCode: string };
          __CLINIC_NATIVE_BRIDGE_EVENTS__?: unknown[];
        }
      ).__CLINIC_WORKFORCE_AUTH__ = { token, tenantCode };
      const events: unknown[] = [];
      globalThis.addEventListener('clinic.native.navigation.v1', (event) => events.push(event));
      (
        globalThis as typeof globalThis & { __CLINIC_NATIVE_BRIDGE_EVENTS__?: unknown[] }
      ).__CLINIC_NATIVE_BRIDGE_EVENTS__ = events;
    },
    { token: makeWorkforceToken(), tenantCode: TENANT_CODE },
  );

  await page.route('**/api/**', async (route: Route) => {
    if (route.request().method() === 'OPTIONS') {
      await route.fulfill({ status: 204 });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [] }),
    });
  });
}

test('브라우저에서는 native bridge가 no-op이고 기존 workforce session을 재사용한다', async ({
  page,
}) => {
  await installBrowserFixture(page);

  await page.goto('/appointments');
  await expect(page.getByText('예약 목록', { exact: true })).toBeVisible();

  const browserContract = await page.evaluate(() => ({
    bridgeEvents:
      (globalThis as typeof globalThis & { __CLINIC_NATIVE_BRIDGE_EVENTS__?: unknown[] })
        .__CLINIC_NATIVE_BRIDGE_EVENTS__?.length ?? 0,
    legacyToken: globalThis.localStorage.getItem('auth_token'),
    sessionToken: globalThis.sessionStorage.getItem('auth_token'),
    tenantScope: globalThis.sessionStorage.getItem('appointment_tenant_code'),
  }));

  expect(browserContract.bridgeEvents).toBe(0);
  expect(browserContract.legacyToken).toBeNull();
  expect(browserContract.sessionToken).toBeNull();
  expect(browserContract.tenantScope).toBe(TENANT_CODE);
});

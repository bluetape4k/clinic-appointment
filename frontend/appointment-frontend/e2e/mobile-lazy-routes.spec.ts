import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';

const TENANT_CODE = 'tenant-default';
const CLINIC_ID = 1;

function makeWorkforceToken(): string {
  const encode = (value: string): string => Buffer.from(value).toString('base64url');
  const payload = {
    exp: Math.floor(Date.now() / 1000) + 3600,
    roles: ['ROLE_STAFF'],
    allowedTenants: [TENANT_CODE],
    clinicId: CLINIC_ID,
  };
  return `${encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${encode(JSON.stringify(payload))}.fixture`;
}

async function installMobileFixture(page: Page): Promise<void> {
  await page.addInitScript(
    ({ token, tenantCode }) => {
      (
        globalThis as typeof globalThis & {
          __CLINIC_WORKFORCE_AUTH__?: { token: string; tenantCode: string };
        }
      ).__CLINIC_WORKFORCE_AUTH__ = { token, tenantCode };
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

async function hasHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  );
}

async function loadedLazyChunks(page: Page): Promise<string[]> {
  return page.evaluate(() =>
    performance
      .getEntriesByType('resource')
      .map((entry) => entry.name)
      .filter((name) => /\/chunk-[^/?]+\.js(?:\?|$)/u.test(name)),
  );
}

test.describe('모바일 WebView lazy route 계약', () => {
  for (const width of [320, 375, 393, 430]) {
    test(`${width}px viewport에서 calendar·appointments·portal lazy route를 연다`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 });
      await installMobileFixture(page);

      await page.goto('/calendar');
      await expect(page.getByRole('button', { name: '오늘' })).toBeVisible();
      await expect(page).toHaveURL(/\/calendar\/week\/\d{4}-\d{2}-\d{2}$/u);
      const calendarChunks = await loadedLazyChunks(page);
      expect(calendarChunks.length, `calendar lazy chunks at ${width}px`).toBeGreaterThan(0);
      expect(await hasHorizontalOverflow(page), `calendar overflow at ${width}px`).toBe(false);

      await page.getByRole('link', { name: '예약 관리' }).click();
      await expect(page).toHaveURL(/\/appointments$/u);
      await expect(page.getByText('예약 목록', { exact: true })).toBeVisible();
      expect(
        (await loadedLazyChunks(page)).length,
        `appointments lazy chunks at ${width}px`,
      ).toBeGreaterThan(0);
      expect(await hasHorizontalOverflow(page), `appointments overflow at ${width}px`).toBe(false);

      await page.goto('/portal/login');
      await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
      expect(await hasHorizontalOverflow(page), `portal overflow at ${width}px`).toBe(false);
    });
  }
  test('짧은 landscape viewport에서 하단 nav touch target과 overflow를 보존한다', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 667, height: 375 });
    await installMobileFixture(page);

    await page.goto('/calendar');
    await expect(page.getByRole('button', { name: '오늘' })).toBeVisible();
    expect(await hasHorizontalOverflow(page), 'landscape overflow').toBe(false);

    const targets = await page
      .locator('.bottom-nav-item, .mobile-content button')
      .evaluateAll((elements) =>
        elements.map((element) => ({
          height: element.getBoundingClientRect().height,
          touchAction: getComputedStyle(element).touchAction,
        })),
      );
    expect(targets.length).toBeGreaterThan(0);
    expect(
      Math.min(...targets.map((target) => target.height)),
      'minimum touch target',
    ).toBeGreaterThanOrEqual(44);
    expect(
      targets.every((target) => target.touchAction === 'manipulation'),
      'touch action contract',
    ).toBe(true);
  });
});

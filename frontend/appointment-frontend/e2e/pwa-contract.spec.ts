import { expect, test } from '@playwright/test';

test.describe('PWA installability·offline 계약', () => {
  test('manifest와 앱 셸 metadata를 production 경로로 제공한다', async ({ page }) => {
    const response = await page.request.get('/manifest.webmanifest');
    expect(response.ok()).toBe(true);
    const manifest = (await response.json()) as {
      display: string;
      start_url: string;
      scope: string;
      icons: Array<{ src: string; type: string }>;
    };

    expect(manifest.display).toBe('standalone');
    expect(manifest.start_url).toBe('/');
    expect(manifest.scope).toBe('/');
    expect(manifest.icons).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ src: 'icons/icon.svg', type: 'image/svg+xml' }),
      ]),
    );

    await page.goto('/portal/login');
    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
      'href',
      'manifest.webmanifest',
    );
    await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#1d4ed8');
  });

  test('offline·online 전환을 전역 상태 영역에 표시한다', async ({ page }) => {
    await page.goto('/portal/login');

    await page.evaluate(() => window.dispatchEvent(new Event('offline')));
    await expect(page.locator('[data-pwa-status]')).toContainText('오프라인');

    await page.evaluate(() => window.dispatchEvent(new Event('online')));
    await expect(page.locator('[data-pwa-status]')).toBeHidden();
  });
});

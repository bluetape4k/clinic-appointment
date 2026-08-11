import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const PATIENT_SESSION = {
  success: true,
  data: {
    tenantCode: 'tenant-default',
    role: 'PATIENT',
    displayName: '홍길동',
    expiresAt: '2099-01-01T00:00:00Z',
  },
};

async function signIn(page: Page): Promise<void> {
  await page.route('**/api/tenant-default/auth/csrf', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { ready: true } }),
    }),
  );
  await page.route('**/api/tenant-default/auth/login', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PATIENT_SESSION),
    }),
  );
  await page.route('**/api/tenant-default/auth/session', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PATIENT_SESSION),
    }),
  );
  await page.route('**/api/tenant-default/notifications', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [] }),
    }),
  );
  await page.route('**/api/tenant-default/notifications/stream', route =>
    route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' }),
  );

  await page.goto('/portal/login');
  await page.getByLabel('Tenant').fill('tenant-default');
  await page.getByLabel('식별자 종류').selectOption('EMAIL');
  await page.getByRole('textbox', { name: '식별자' }).fill('patient@example.com');
  await page.getByLabel('비밀번호').fill('correct horse battery staple');
  await page.getByRole('button', { name: '로그인' }).click();
  await page.waitForURL(/\/portal\/appointments$/);
}

test.describe('환자 포털 브라우저 계약', () => {
  test('예약·알림 탐색과 예약 요청 폼의 접근 가능한 이름을 제공한다', async ({ page }) => {
    await signIn(page);

    await expect(page.getByText('임상 포털', { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: '예약 현황' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '새 예약 요청' })).toBeVisible();
    await expect(page.getByLabel('예약 계획 ID')).toHaveAttribute('type', 'number');
    await expect(page.getByLabel('희망 시작')).toHaveAttribute('type', 'datetime-local');
    await expect(page.getByRole('link', { name: '예약 현황' })).toHaveAttribute('aria-current', 'page');

    await page.getByRole('link', { name: '알림' }).click();
    await expect(page).toHaveURL(/\/portal\/notifications$/);
    await expect(page.getByRole('heading', { name: '알림' })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '새 알림이 없습니다.' })).toBeVisible();

    await page.getByRole('link', { name: '예약 현황' }).click();
    await expect(page).toHaveURL(/\/portal\/appointments$/);
    await expect(page.getByRole('heading', { name: '예약 현황' })).toBeVisible();

    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
    expect(overflow).toBe(false);
  });

  test('320·736·1024px viewport에서 가로 overflow 없이 keyboard focus를 유지한다', async ({ page }) => {
    for (const width of [320, 736, 1024]) {
      await page.setViewportSize({ width, height: 900 });
      await signIn(page);

      const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
      expect(overflow, `viewport ${width}px`).toBe(false);

      const appointmentsLink = page.getByRole('link', { name: '예약 현황' });
      await appointmentsLink.focus();
      await expect(appointmentsLink).toBeFocused();
    }
  });

  test('승인된 C 참조 상태용 확정 예약 visual fixture를 렌더링한다', async ({ page }) => {
    await signIn(page);
    await page.goto('/portal/appointments/visual-fixture');

    await expect(page.getByRole('heading', { name: '환자 포털 확정 예약 참조 상태' })).toBeAttached();
    await expect(page.getByRole('heading', { name: '피부 재생 관리' })).toBeVisible();
    await expect(page.getByText('2026년 8월 20일 10:30')).toBeVisible();
    await expect(page.getByText('3회차 / 10회')).toBeVisible();
    await expect(page.getByText('확정', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /예약 상세 보기/ })).toBeVisible();
  });
});

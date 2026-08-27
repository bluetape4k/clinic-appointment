import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';

const TENANT_CODE = 'tenant-default';
const CLINIC_ID = 1;
const APPOINTMENT = {
  id: 42,
  clinicId: CLINIC_ID,
  doctorId: 4,
  treatmentTypeId: 5,
  patientName: '홍길동',
  patientPhone: '010-1234-5678',
  appointmentDate: '2026-08-27',
  startTime: '10:00:00',
  endTime: '10:30:00',
  status: 'REQUESTED',
};

interface RecordedRequest {
  method: string;
  path: string;
  authorization: string | undefined;
}

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

async function installNativeWebViewFixture(page: Page): Promise<RecordedRequest[]> {
  const requests: RecordedRequest[] = [];
  await page.addInitScript(
    ({ token, tenantCode }) => {
      const state = globalThis as typeof globalThis & {
        __CLINIC_WORKFORCE_AUTH__?: { token: string; tenantCode: string };
        __CLINIC_NATIVE_BRIDGE_EVENTS__?: unknown[];
      };
      state.__CLINIC_WORKFORCE_AUTH__ = { token, tenantCode };
      state.__CLINIC_NATIVE_BRIDGE_EVENTS__ = [];
      globalThis.addEventListener('clinic.native.navigation.v1', (event) => {
        state.__CLINIC_NATIVE_BRIDGE_EVENTS__?.push(event);
      });
    },
    { token: makeWorkforceToken(), tenantCode: TENANT_CODE },
  );

  await page.route(`**/api/${TENANT_CODE}/**`, async (route) => {
    const request = route.request();
    requests.push({
      method: request.method(),
      path: new URL(request.url()).pathname,
      authorization: request.headers().authorization,
    });
    await fulfillApi(route);
  });
  return requests;
}

async function fulfillApi(route: Route): Promise<void> {
  const request = route.request();
  if (request.method() === 'OPTIONS') {
    await route.fulfill({ status: 204 });
    return;
  }

  const path = new URL(request.url()).pathname;
  if (path.endsWith('/clinics/1/doctors')) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: {
          content: [],
          totalCount: 0,
          pageNumber: 0,
          pageSize: 20,
          totalPages: 0,
          isFirst: true,
          isLast: true,
          hasNext: false,
          hasPrevious: false,
        },
      }),
    });
    return;
  }

  if (path.endsWith('/clinics/1/treatment-types')) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { content: [] } }),
    });
    return;
  }

  if (path.endsWith('/appointments/42') && request.method() === 'GET') {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: APPOINTMENT }),
    });
    return;
  }

  if (path.endsWith('/appointments') && request.method() === 'GET') {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [APPOINTMENT] }),
    });
    return;
  }

  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: [] }),
  });
}

async function assertNoHorizontalOverflow(page: Page): Promise<void> {
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
    ),
  ).toBe(true);
}

async function assertFocusedElementIsVisible(page: Page): Promise<void> {
  const focused = await page.evaluate(() => {
    const element = document.activeElement;
    if (!(element instanceof HTMLElement)) return null;
    const rect = element.getBoundingClientRect();
    const viewport = globalThis.visualViewport;
    const top = viewport?.offsetTop ?? 0;
    const bottom = top + (viewport?.height ?? globalThis.innerHeight);
    return { top: rect.top, bottom: rect.bottom, viewportTop: top, viewportBottom: bottom };
  });
  expect(focused).not.toBeNull();
  expect(focused!.top).toBeGreaterThanOrEqual(focused!.viewportTop);
  expect(focused!.bottom).toBeLessThanOrEqual(focused!.viewportBottom + 1);
}

test.describe('모바일 WebView 통합 계약', () => {
  test('workforce 인증·tenant API·calendar/appointment lazy route를 유지한다', async ({ page }) => {
    const requests = await installNativeWebViewFixture(page);
    await page.setViewportSize({ width: 375, height: 812 });

    await page.goto('/calendar');
    await expect(page.getByRole('button', { name: '오늘' })).toBeVisible();
    await expect(page).toHaveURL(/\/calendar\/week\/\d{4}-\d{2}-\d{2}$/u);
    await expect
      .poll(() => requests.some((request) => request.path.endsWith('/appointments')))
      .toBe(true);
    expect(
      requests
        .filter((request) => request.path.startsWith(`/api/${TENANT_CODE}/`))
        .every((request) => request.authorization?.startsWith('Bearer ')),
    ).toBe(true);
    await assertNoHorizontalOverflow(page);

    await page.getByRole('link', { name: '예약 관리' }).click();
    await expect(page).toHaveURL(/\/appointments$/u);
    await expect(page.getByText('예약 목록', { exact: true })).toBeVisible();
    await expect(page.getByText('홍길동', { exact: true })).toBeVisible();
    await assertNoHorizontalOverflow(page);

    const sessionContract = await page.evaluate(() => ({
      legacyLocalToken: globalThis.localStorage.getItem('auth_token'),
      legacySessionToken: globalThis.sessionStorage.getItem('auth_token'),
      tenantCode: globalThis.sessionStorage.getItem('appointment_tenant_code'),
      nativeEvents:
        (globalThis as typeof globalThis & { __CLINIC_NATIVE_BRIDGE_EVENTS__?: unknown[] })
          .__CLINIC_NATIVE_BRIDGE_EVENTS__?.length ?? 0,
    }));
    expect(sessionContract.legacyLocalToken).toBeNull();
    expect(sessionContract.legacySessionToken).toBeNull();
    expect(sessionContract.tenantCode).toBe(TENANT_CODE);
    expect(sessionContract.nativeEvents).toBe(0);

    const startDateInput = page.getByLabel('시작일');
    await startDateInput.focus();
    await expect(startDateInput).toBeFocused();
    await assertFocusedElementIsVisible(page);

    const appointmentsLink = page.getByRole('link', { name: '캘린더' });
    await appointmentsLink.focus();
    await expect(appointmentsLink).toBeFocused();
    await assertFocusedElementIsVisible(page);
  });

  test('deep link와 appointment detail이 native custom URL의 대상 route를 연다', async ({
    page,
  }) => {
    const requests = await installNativeWebViewFixture(page);
    await page.setViewportSize({ width: 320, height: 720 });

    await page.goto('/calendar/month/2026-08-27');
    await expect(page).toHaveURL('/calendar/month/2026-08-27');
    await expect(page.getByText('2026년 8월', { exact: false })).toBeVisible();
    await assertNoHorizontalOverflow(page);

    await page.goto('/appointments/42');
    await expect(page).toHaveURL('/appointments/42');
    await expect(page.getByText('예약 상세', { exact: true })).toBeVisible();
    await expect(page.getByText('홍길동', { exact: true })).toBeVisible();
    await assertNoHorizontalOverflow(page);
    expect(requests.some((request) => request.path.endsWith('/appointments/42'))).toBe(true);
  });
});

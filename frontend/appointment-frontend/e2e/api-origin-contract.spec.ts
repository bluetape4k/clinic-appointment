import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const API_ORIGIN = 'https://api.example.test';
const PATIENT_SESSION = {
  success: true,
  data: {
    tenantCode: 'tenant-default',
    role: 'PATIENT',
    displayName: '홍길동',
    expiresAt: '2099-01-01T00:00:00Z',
  },
};

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': 'http://127.0.0.1:4200',
  'Access-Control-Allow-Credentials': 'true',
  'Access-Control-Allow-Headers': 'Content-Type, X-XSRF-TOKEN',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
};

async function installApiOrigin(page: Page): Promise<void> {
  await page.addInitScript((origin: string) => {
    (
      globalThis as typeof globalThis & {
        __CLINIC_API_CONFIG__?: { apiOrigin: string };
      }
    ).__CLINIC_API_CONFIG__ = { apiOrigin: origin };
  }, API_ORIGIN);
  await page.context().addCookies([
    { name: 'XSRF-TOKEN', value: 'csrf-e2e', domain: '127.0.0.1', path: '/' },
    {
      name: 'PATIENT_SESSION',
      value: 'patient-e2e',
      domain: 'api.example.test',
      path: '/',
      secure: true,
      sameSite: 'None',
    },
  ]);
}

test.describe('Capacitor WebView API origin 계약', () => {
  test('runtime origin에서 tenant path·credentials·XSRF login을 유지한다', async ({ page }) => {
    await installApiOrigin(page);
    let loginRequest:
      | { url: string; xsrf: string | undefined; cookie: string | undefined }
      | undefined;
    let mutationRequest:
      | {
          url: string;
          xsrf: string | undefined;
          cookie: string | undefined;
          body: Record<string, unknown>;
        }
      | undefined;
    let logoutRequest:
      | { url: string; xsrf: string | undefined; cookie: string | undefined }
      | undefined;

    await page.route(`${API_ORIGIN}/api/tenant-default/**`, async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'OPTIONS') {
        await route.fulfill({ status: 204, headers: CORS_HEADERS });
        return;
      }
      if (url.pathname.endsWith('/auth/csrf')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify({ success: true, data: { ready: true } }),
        });
        return;
      }
      if (url.pathname.endsWith('/auth/login')) {
        loginRequest = {
          url: request.url(),
          xsrf: request.headers()['x-xsrf-token'],
          cookie: request.headers().cookie,
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify(PATIENT_SESSION),
        });
        return;
      }
      if (url.pathname.endsWith('/auth/session')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify(PATIENT_SESSION),
        });
        return;
      }
      if (url.pathname.endsWith('/appointment-requests') && request.method() === 'POST') {
        mutationRequest = {
          url: request.url(),
          xsrf: request.headers()['x-xsrf-token'],
          cookie: request.headers().cookie,
          body: JSON.parse(request.postData() || '{}') as Record<string, unknown>,
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { ...CORS_HEADERS, ETag: '"1"' },
          body: JSON.stringify({
            appointmentId: 42,
            proposalId: 126,
            status: 'PROPOSED',
            version: 1,
            expiresAt: '2099-01-01T00:00:00Z',
            productName: '피부 재생 관리',
            sessionNumber: 3,
            totalSessions: 10,
            clinicDisplayName: '서울 메인 클리닉',
          }),
        });
        return;
      }
      if (url.pathname.endsWith('/auth/logout')) {
        logoutRequest = {
          url: request.url(),
          xsrf: request.headers()['x-xsrf-token'],
          cookie: request.headers().cookie,
        };
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify(null),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: CORS_HEADERS,
        body: JSON.stringify({ success: true, data: [] }),
      });
    });

    await page.goto('/portal/login');
    await page.getByLabel('Tenant').fill('tenant-default');
    await page.getByLabel('식별자 종류').selectOption('EMAIL');
    await page.getByRole('textbox', { name: '식별자' }).fill('patient@example.com');
    await page.getByLabel('비밀번호').fill('correct horse battery staple');
    await page.getByRole('button', { name: '로그인' }).click();
    await page.waitForURL(/\/portal\/appointments$/);

    expect(loginRequest).toEqual({
      url: `${API_ORIGIN}/api/tenant-default/auth/login`,
      xsrf: 'csrf-e2e',
      cookie: expect.stringContaining('PATIENT_SESSION=patient-e2e'),
    });
    await expect(page.getByRole('heading', { name: '예약 현황' })).toBeVisible();

    await page.getByLabel('예약 계획 ID').fill('77');
    await page.getByLabel('희망 시작').fill('2026-08-20T10:00');
    await page.getByLabel('희망 종료').fill('2026-08-20T10:30');
    await page.getByLabel('동의 권위').fill('consent:clinic-a');
    await page.getByLabel('동의 증빙 ID').fill('01J1M6Y6XRK8N0W2M3P4Q5R6S7');
    await page.getByRole('button', { name: '예약 요청 보내기' }).click();

    await expect(page.locator('[data-step="PROPOSED"]')).toHaveAttribute('aria-current', 'step');
    expect(mutationRequest).toEqual({
      url: `${API_ORIGIN}/api/tenant-default/appointment-requests`,
      xsrf: 'csrf-e2e',
      cookie: expect.stringContaining('PATIENT_SESSION=patient-e2e'),
      body: {
        appointmentPlanId: 77,
        preferredStartAt: '2026-08-20T10:00:00.000Z',
        preferredEndAt: '2026-08-20T10:30:00.000Z',
        evidence: {
          evidenceAuthority: 'consent:clinic-a',
          evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7',
        },
      },
    });

    await page.getByRole('button', { name: '로그아웃' }).click();
    await page.waitForURL(/\/portal\/login$/);
    expect(logoutRequest).toEqual({
      url: `${API_ORIGIN}/api/tenant-default/auth/logout`,
      xsrf: 'csrf-e2e',
      cookie: expect.stringContaining('PATIENT_SESSION=patient-e2e'),
    });
  });

  test('cross-origin 인증 실패도 기존 patient 화면 오류로 전파한다', async ({ page }) => {
    await installApiOrigin(page);

    await page.route(`${API_ORIGIN}/api/tenant-default/**`, async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      if (request.method() === 'OPTIONS') {
        await route.fulfill({ status: 204, headers: CORS_HEADERS });
      } else if (url.pathname.endsWith('/auth/csrf')) {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify({ success: true, data: { ready: true } }),
        });
      } else if (url.pathname.endsWith('/auth/login')) {
        await route.fulfill({
          status: 403,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify({ success: false, code: 'CSRF_INVALID' }),
        });
      } else {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: CORS_HEADERS,
          body: JSON.stringify({ success: true, data: [] }),
        });
      }
    });

    await page.goto('/portal/login');
    await page.getByLabel('Tenant').fill('tenant-default');
    await page.getByLabel('식별자 종류').selectOption('EMAIL');
    await page.getByRole('textbox', { name: '식별자' }).fill('patient@example.com');
    await page.getByLabel('비밀번호').fill('invalid');
    await page.getByRole('button', { name: '로그인' }).click();

    await expect(page.getByRole('alert')).toContainText(
      '입력한 tenant와 로그인 정보를 확인해 주세요.',
    );
    await expect(page).toHaveURL(/\/portal\/login$/);
  });
});

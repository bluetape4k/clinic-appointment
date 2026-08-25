import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';

type WorkforceRole = 'ROLE_ADMIN' | 'ROLE_STAFF' | 'ROLE_PATIENT';
type ApiFailureStatus = 401 | 403;

interface RecordedRequest {
  method: string;
  path: string;
  authorization: string | undefined;
}

const TENANT_CODE = 'tenant-default';
const CLINIC_ID = 1;
const DOCTOR = {
  id: 4,
  clinicId: CLINIC_ID,
  name: '김민준',
  specialty: '피부과',
  providerType: 'DOCTOR',
};
const TREATMENT_TYPE = {
  id: 5,
  clinicId: CLINIC_ID,
  name: '피부 재생 관리',
  category: '피부',
  defaultDurationMinutes: 30,
  requiredProviderType: 'DOCTOR',
  requiresEquipment: false,
};
const APPOINTMENT = {
  id: 42,
  clinicId: CLINIC_ID,
  doctorId: DOCTOR.id,
  treatmentTypeId: TREATMENT_TYPE.id,
  patientName: '홍길동',
  patientPhone: '010-1234-5678',
  appointmentDate: new Date().toISOString().slice(0, 10),
  startTime: '10:00:00',
  endTime: '10:30:00',
  status: 'REQUESTED',
};

function makeJwt(roles: WorkforceRole[]): string {
  const encode = (value: string): string => Buffer.from(value).toString('base64url');
  const payload = {
    exp: Math.floor(Date.now() / 1000) + 3600,
    roles,
    allowedTenants: [TENANT_CODE],
    clinicId: CLINIC_ID,
  };
  return `${encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))}.${encode(JSON.stringify(payload))}.signature`;
}

async function installWorkforceFixture(
  page: Page,
  role: WorkforceRole,
  failure?: { pathSuffix: string; status: ApiFailureStatus },
): Promise<RecordedRequest[]> {
  const requests: RecordedRequest[] = [];
  const token = makeJwt([role]);

  await page.addInitScript(
    ({ token, tenantCode }) => {
      (
        globalThis as typeof globalThis & {
          __CLINIC_WORKFORCE_AUTH__?: { token: string; tenantCode: string };
        }
      ).__CLINIC_WORKFORCE_AUTH__ = { token, tenantCode };
    },
    { token, tenantCode: TENANT_CODE },
  );

  await page.route(`**/api/${TENANT_CODE}/**`, async (route) => {
    requests.push(recordRequest(route));
    await fulfillWorkforceRequest(route, failure);
  });

  return requests;
}

function recordRequest(route: Route): RecordedRequest {
  const request = route.request();
  return {
    method: request.method(),
    path: new URL(request.url()).pathname,
    authorization: request.headers().authorization,
  };
}

async function fulfillWorkforceRequest(
  route: Route,
  failure?: { pathSuffix: string; status: ApiFailureStatus },
): Promise<void> {
  const request = route.request();
  const path = new URL(request.url()).pathname;
  const method = request.method();

  if (failure && path.endsWith(failure.pathSuffix)) {
    await route.fulfill({
      status: failure.status,
      contentType: 'application/json',
      body: JSON.stringify({
        success: false,
        error: failure.status === 401 ? '인증 필요' : '권한 없음',
      }),
    });
    return;
  }

  if (path.endsWith(`/clinics/${CLINIC_ID}/doctors`)) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: {
          content: [DOCTOR],
          totalCount: 1,
          pageNumber: 0,
          pageSize: 20,
          totalPages: 1,
          isFirst: true,
          isLast: true,
          hasNext: false,
          hasPrevious: false,
        },
      }),
    });
    return;
  }

  if (path.endsWith(`/clinics/${CLINIC_ID}/treatment-types`)) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: {
          content: [TREATMENT_TYPE],
          totalCount: 1,
          pageNumber: 0,
          pageSize: 20,
          totalPages: 1,
          isFirst: true,
          isLast: true,
          hasNext: false,
          hasPrevious: false,
        },
      }),
    });
    return;
  }

  if (path.endsWith('/clinics')) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: {
          content: [
            {
              id: CLINIC_ID,
              name: '서울 메인 클리닉',
              slotDurationMinutes: 30,
              timezone: 'Asia/Seoul',
              locale: 'ko-KR',
              maxConcurrentPatients: 4,
              openOnHolidays: false,
            },
          ],
          totalCount: 1,
          pageNumber: 0,
          pageSize: 20,
          totalPages: 1,
          isFirst: true,
          isLast: true,
          hasNext: false,
          hasPrevious: false,
        },
      }),
    });
    return;
  }

  if (path.endsWith('/clinics/1/slots')) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: [
          {
            date: APPOINTMENT.appointmentDate,
            startTime: APPOINTMENT.startTime,
            endTime: APPOINTMENT.endTime,
            doctorId: DOCTOR.id,
            equipmentIds: [],
            remainingCapacity: 1,
          },
        ],
      }),
    });
    return;
  }

  if (path.endsWith('/appointments') && method === 'GET') {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [APPOINTMENT] }),
    });
    return;
  }

  if (path.endsWith('/appointments/42') && method === 'GET') {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: APPOINTMENT }),
    });
    return;
  }

  if (path.endsWith('/appointments') && method === 'POST') {
    const requestBody = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>;
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: { ...APPOINTMENT, id: 43, ...requestBody, status: 'REQUESTED' },
      }),
    });
    return;
  }

  if (path.endsWith('/appointments/42/status') && method === 'PATCH') {
    const requestBody = JSON.parse(request.postData() ?? '{}') as Record<string, unknown>;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: { ...APPOINTMENT, ...requestBody } }),
    });
    return;
  }

  if (path.includes('/admin/stats/')) {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        success: true,
        data: path.endsWith('/appointments')
          ? { clinicId: CLINIC_ID, from: '2026-08-01', to: '2026-08-31', totals: {}, daily: [] }
          : path.endsWith('/doctors')
            ? { clinicId: CLINIC_ID, from: '2026-08-01', to: '2026-08-31', doctors: [] }
            : {
                clinicId: CLINIC_ID,
                from: '2026-08-01',
                to: '2026-08-31',
                totalCancelled: 0,
                totalNoShow: 0,
                totalRescheduled: 0,
                totalCompleted: 0,
                cancellationRate: 0,
                noShowRate: 0,
                daily: [],
              },
      }),
    });
    return;
  }

  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ success: true, data: [] }),
  });
}

function expectWorkforceRequests(requests: RecordedRequest[]): void {
  expect(requests.length).toBeGreaterThan(0);
  expect(requests.every((request) => request.path.startsWith(`/api/${TENANT_CODE}/`))).toBe(true);
  expect(requests.every((request) => request.authorization?.startsWith('Bearer '))).toBe(true);
}

test.describe('workforce host bootstrap 브라우저 계약', () => {
  test('직원 handoff가 예약 목록·대표 생성 화면과 tenant Bearer scope를 연결한다', async ({
    page,
  }) => {
    const requests = await installWorkforceFixture(page, 'ROLE_STAFF');

    await page.goto('/appointments');
    await expect(page.getByText('예약 목록', { exact: true })).toBeVisible();
    await expect(page.getByText('홍길동', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /새 예약/ })).toBeVisible();

    await page.getByRole('button', { name: /새 예약/ }).click();
    await expect(page).toHaveURL(/\/appointments\/new$/);
    await expect(page.getByText('새 예약', { exact: true })).toBeVisible();

    await page.getByRole('combobox', { name: '담당 의사' }).click();
    await page.getByRole('option', { name: /김민준/ }).click();
    await page.getByRole('combobox', { name: '진료 유형' }).click();
    await page.getByRole('option', { name: /피부 재생 관리/ }).click();
    await page
      .locator('input[formcontrolname="appointmentDate"]')
      .fill(APPOINTMENT.appointmentDate);
    await page.locator('input[formcontrolname="appointmentDate"]').press('Tab');
    await page.getByLabel('환자명').fill('이순신');
    await page.getByLabel('연락처').fill('010-9876-5432');
    await expect(page.getByText('10:00 ~ 10:30', { exact: true })).toBeVisible();
    await page.getByText('10:00 ~ 10:30', { exact: true }).click();
    await page.getByRole('button', { name: '예약 등록' }).click();
    await expect(page).toHaveURL(/\/appointments\/43$/);

    expectWorkforceRequests(requests);
    expect(
      requests.some(
        (request) => request.method === 'POST' && request.path.endsWith('/appointments'),
      ),
    ).toBe(true);
  });

  test('관리자 handoff가 관리 대시보드·통계 화면과 tenant API를 연결한다', async ({ page }) => {
    const requests = await installWorkforceFixture(page, 'ROLE_ADMIN');

    await page.goto('/management');
    await expect(page.getByText('관리 대시보드', { exact: true })).toBeVisible();
    await page.goto('/management/clinics');
    await expect(page.getByText('서울 메인 클리닉', { exact: true })).toBeVisible();
    await page.goto('/management/admin-dashboard');
    await expect(page.getByText('어드민 대시보드', { exact: true })).toBeVisible();

    await page.goto('/appointments/42');
    await expect(page.getByText('예약 상세', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '확정', exact: true }).click();
    await expect(page.getByText('상태 변경', { exact: true })).toBeVisible();
    await page.getByRole('dialog').getByRole('button', { name: '확정', exact: true }).click();
    await expect(page.getByRole('button', { name: '체크인', exact: true })).toBeVisible();

    expectWorkforceRequests(requests);
    expect(requests.some((request) => request.path.endsWith('/admin/stats/appointments'))).toBe(
      true,
    );
    expect(requests.some((request) => request.path.endsWith('/clinics'))).toBe(true);
    expect(
      requests.some(
        (request) => request.method === 'PATCH' && request.path.endsWith('/appointments/42/status'),
      ),
    ).toBe(true);
  });

  test('patient role handoff는 workforce management 경계를 통과하지 못하고 calendar로 이동한다', async ({
    page,
  }) => {
    await installWorkforceFixture(page, 'ROLE_PATIENT');

    await page.goto('/management');

    await expect(page).toHaveURL(/\/calendar/);
  });

  for (const [status, message] of [
    [401, '인증 필요'],
    [403, '권한 없음'],
  ] as const) {
    test(`workforce ${status} 응답을 세션 오류 메시지로 표시한다`, async ({ page }) => {
      const requests = await installWorkforceFixture(page, 'ROLE_STAFF', {
        pathSuffix: '/appointments',
        status,
      });

      await page.goto('/appointments');
      await expect(page.getByText(message, { exact: true })).toBeVisible();
      await expect(page).toHaveURL(/\/appointments$/);

      expectWorkforceRequests(requests);
    });
  }
});

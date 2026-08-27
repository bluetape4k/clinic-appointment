import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpHeaders } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { TenantApiClient } from './tenant-api-client';
import { TenantContextService } from './tenant-context.service';
import { authInterceptor } from '../interceptors/auth.interceptor';
import { AuthService } from '../services/auth.service';

function makeJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 60, ...payload }));
  return `${header}.${body}.signature`;
}

describe('TenantApiClient', () => {
  let client: TenantApiClient;
  let httpMock: HttpTestingController;
  let tenant: TenantContextService;
  let auth: AuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    client = TestBed.inject(TenantApiClient);
    httpMock = TestBed.inject(HttpTestingController);
    tenant = TestBed.inject(TenantContextService);
    auth = TestBed.inject(AuthService);
    tenant.setTenant('tenant-a');
    auth.setToken(makeJwt({ roles: ['ROLE_STAFF'] }));
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('tenant path를 인코딩하고 workforce Bearer scope를 요청에 전파한다', async () => {
    const promise = client.request<{ ok: boolean }>('GET', '/appointments', {
      authScope: 'workforce-bearer',
    });
    const request = httpMock.expectOne('/api/tenant-a/appointments');

    expect(request.request.headers.get('Authorization')).toBe('Bearer ' + auth.getToken());
    expect(request.request.withCredentials).toBe(false);
    request.flush({ ok: true }, { headers: new HttpHeaders({ ETag: '"1"' }) });

    await expect(promise).resolves.toMatchObject({
      body: { ok: true },
      headers: expect.anything(),
    });
  });

  it('patient cookie scope는 Bearer를 붙이지 않고 credentials를 활성화한다', async () => {
    const promise = client.request<{ ok: boolean }>('GET', '/auth/session', {
      authScope: 'patient-cookie',
      withCredentials: true,
    });
    const request = httpMock.expectOne('/api/tenant-a/auth/session');

    expect(request.request.headers.has('Authorization')).toBe(false);
    expect(request.request.withCredentials).toBe(true);
    request.flush({ ok: true });

    await expect(promise).resolves.toMatchObject({ body: { ok: true } });
  });

  it('patient cookie scope는 credentials를 생략해도 자동으로 활성화한다', async () => {
    const promise = client.request<{ ok: boolean }>('GET', '/auth/session', {
      authScope: 'patient-cookie',
    });
    const request = httpMock.expectOne('/api/tenant-a/auth/session');

    expect(request.request.withCredentials).toBe(true);
    request.flush({ ok: true });

    await expect(promise).resolves.toMatchObject({ body: { ok: true } });
  });

  it('patient cookie scope에서 credentials를 명시적으로 끄면 network 전에 실패한다', async () => {
    await expect(
      client.request('POST', '/auth/logout', {
        authScope: 'patient-cookie',
        withCredentials: false,
      }),
    ).rejects.toThrow('withCredentials');
    httpMock.expectNone(() => true);
  });

  it('workforce Bearer scope에서 credentials를 켜면 network 전에 실패한다', async () => {
    await expect(
      client.request('GET', '/appointments', {
        authScope: 'workforce-bearer',
        withCredentials: true,
      }),
    ).rejects.toThrow('workforce-bearer');
    httpMock.expectNone(() => true);
  });

  it('tenant가 없으면 네트워크 요청 전에 실패한다', async () => {
    tenant.clear();

    await expect(
      client.request('GET', '/appointments', { authScope: 'workforce-bearer' }),
    ).rejects.toThrow('tenant scope');
    httpMock.expectNone(() => true);
  });

  it('내부 path가 아니면 네트워크 요청 전에 실패한다', async () => {
    await expect(
      client.request('GET', 'https://attacker.example/api', { authScope: 'workforce-bearer' }),
    ).rejects.toThrow('API path');
    httpMock.expectNone(() => true);
  });
});

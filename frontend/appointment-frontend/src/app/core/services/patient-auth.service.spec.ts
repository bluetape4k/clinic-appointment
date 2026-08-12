import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PatientAuthService } from './patient-auth.service';
import { TenantContextService } from '../api/tenant-context.service';

describe('PatientAuthService', () => {
  let service: PatientAuthService;
  let httpMock: HttpTestingController;
  let tenant: TenantContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientAuthService);
    httpMock = TestBed.inject(HttpTestingController);
    tenant = TestBed.inject(TenantContextService);
    tenant.setTenant('tenant-a');
  });

  afterEach(() => {
    try {
      httpMock.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  it('login은 CSRF bootstrap 뒤 구조화된 식별자와 cookie credentials로 요청한다', async () => {
    const resultPromise = service.login('tenant-a', {
      identifier: { key: 'PHONE', value: '010-1234-5678' },
      password: 'correct horse battery staple',
    });

    const csrf = httpMock.expectOne('/api/tenant-a/auth/csrf');
    expect(csrf.request.method).toBe('GET');
    expect(csrf.request.withCredentials).toBe(true);
    csrf.flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));

    const login = httpMock.expectOne('/api/tenant-a/auth/login');
    expect(login.request.method).toBe('POST');
    expect(login.request.withCredentials).toBe(true);
    expect(login.request.body.identifier).toEqual({ key: 'PHONE', value: '010-1234-5678' });
    login.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '홍길동',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });

    await expect(resultPromise).resolves.toMatchObject({ displayName: '홍길동' });
    expect(service.session()?.tenantCode).toBe('tenant-a');
    expect(service.isAuthenticated()).toBe(true);
  });

  it('register는 PHONE EMAIL LOGIN_ID를 key=value 배열로 보낸다', async () => {
    const resultPromise = service.register('tenant-a', {
      displayName: '홍길동',
      password: 'correct horse battery staple',
      identifiers: [
        { key: 'PHONE', value: '010-1234-5678' },
        { key: 'EMAIL', value: 'patient@example.com' },
        { key: 'LOGIN_ID', value: 'hong' },
      ],
    });

    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const register = httpMock.expectOne('/api/tenant-a/auth/register');
    expect(register.request.withCredentials).toBe(true);
    expect(register.request.body.identifiers).toHaveLength(3);
    register.flush({ success: true, data: { registered: true } });

    await expect(resultPromise).resolves.toEqual({ registered: true });
    expect(service.isAuthenticated()).toBe(false);
  });

  it('logout은 CSRF를 새로 받고 cookie 요청 후 세션을 비운다', async () => {
    const resultPromise = service.logout('tenant-a');

    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const logout = httpMock.expectOne('/api/tenant-a/auth/logout');
    expect(logout.request.withCredentials).toBe(true);
    logout.flush(null);

    await expect(resultPromise).resolves.toBeUndefined();
    expect(service.session()).toBeNull();
  });
});

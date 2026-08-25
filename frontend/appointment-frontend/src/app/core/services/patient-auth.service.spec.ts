import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PatientAuthService } from './patient-auth.service';
import { SessionStateService } from './session-state.service';
import { TenantContextService } from '../api/tenant-context.service';

describe('PatientAuthService', () => {
  let service: PatientAuthService;
  let httpMock: HttpTestingController;
  let tenant: TenantContextService;
  let sessionState: SessionStateService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientAuthService);
    httpMock = TestBed.inject(HttpTestingController);
    tenant = TestBed.inject(TenantContextService);
    sessionState = TestBed.inject(SessionStateService);
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
    const versionBeforeLogout = service.currentSessionVersion();
    const resultPromise = service.logout('tenant-a');

    expect(service.currentSessionVersion()).toBe(versionBeforeLogout + 1);

    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const logout = httpMock.expectOne('/api/tenant-a/auth/logout');
    expect(logout.request.withCredentials).toBe(true);
    logout.flush(null);

    await expect(resultPromise).resolves.toBeUndefined();
    expect(service.session()).toBeNull();
  });

  it('tenant 전환 중 지연된 session 복원 응답은 현재 session을 덮어쓰지 않는다', async () => {
    const promise = service.sessionFor('tenant-a');
    const request = httpMock.expectOne('/api/tenant-a/auth/session');

    tenant.setTenant('tenant-b');
    service.beginSessionChange();
    request.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '이전 환자',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });

    await expect(promise).rejects.toThrow('현재 session');
    expect(service.session()).toBeNull();
  });

  it('동시에 시작한 session 복원은 최신 요청만 session을 적용한다', async () => {
    const firstPromise = service.sessionFor('tenant-a');
    const first = httpMock.expectOne('/api/tenant-a/auth/session');

    const secondPromise = service.sessionFor('tenant-a');
    const second = httpMock.expectOne('/api/tenant-a/auth/session');

    first.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '이전 관찰',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });
    await expect(firstPromise).rejects.toThrow('현재 session');
    expect(service.session()).toBeNull();

    second.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '최신 관찰',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });
    await expect(secondPromise).resolves.toMatchObject({ displayName: '최신 관찰' });
    expect(service.session()?.displayName).toBe('최신 관찰');
  });

  it('새 login이 시작되면 이전 login 응답은 현재 session을 덮어쓰지 않는다', async () => {
    const firstPromise = service.login('tenant-a', {
      identifier: { key: 'PHONE', value: '010-1111-1111' },
      password: 'first-password',
    });
    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const firstLogin = httpMock.expectOne('/api/tenant-a/auth/login');

    const secondPromise = service.login('tenant-a', {
      identifier: { key: 'EMAIL', value: 'second@example.com' },
      password: 'second-password',
    });
    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const secondLogin = httpMock.expectOne('/api/tenant-a/auth/login');

    firstLogin.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '첫 환자',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });
    await expect(firstPromise).rejects.toThrow('현재 session');
    expect(service.session()).toBeNull();

    secondLogin.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '두 번째 환자',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });
    await expect(secondPromise).resolves.toMatchObject({ displayName: '두 번째 환자' });
    expect(service.session()?.displayName).toBe('두 번째 환자');
  });

  it('새 login이 시작되면 지연된 logout finally가 새 session을 비우지 않는다', async () => {
    const logoutPromise = service.logout('tenant-a');
    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const logout = httpMock.expectOne('/api/tenant-a/auth/logout');

    const loginPromise = service.login('tenant-a', {
      identifier: { key: 'LOGIN_ID', value: 'new-patient' },
      password: 'new-password',
    });
    httpMock.expectOne('/api/tenant-a/auth/csrf').flush({ success: true, data: { ready: true } });
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    const login = httpMock.expectOne('/api/tenant-a/auth/login');

    logout.flush(null);
    await expect(logoutPromise).resolves.toBeUndefined();

    login.flush({
      success: true,
      data: {
        tenantCode: 'tenant-a', role: 'PATIENT', displayName: '새 환자',
        expiresAt: '2099-01-01T00:00:00Z',
      },
    });
    await expect(loginPromise).resolves.toMatchObject({ displayName: '새 환자' });
    expect(service.session()?.displayName).toBe('새 환자');
  });

  it('tenant 인자가 현재 context와 다르면 요청하지 않고 tenant 상태를 기록한다', async () => {
    await expect(service.sessionFor('tenant-b')).rejects.toThrow('tenant scope');

    expect(sessionState.status('patient')).toBe('tenant-missing');
    httpMock.expectNone('/api/tenant-a/auth/session');
  });

  it('session 복원 401은 patient unauthorized 상태로 전파한다', async () => {
    const promise = service.ensureSession();
    const request = httpMock.expectOne('/api/tenant-a/auth/session');
    request.flush(null, { status: 401, statusText: 'Unauthorized' });

    await expect(promise).resolves.toBe(false);
    expect(sessionState.status('patient')).toBe('unauthorized');
  });

  it('session 복원 403은 patient forbidden 상태로 전파한다', async () => {
    const promise = service.ensureSession();
    const request = httpMock.expectOne('/api/tenant-a/auth/session');
    request.flush(null, { status: 403, statusText: 'Forbidden' });

    await expect(promise).resolves.toBe(false);
    expect(sessionState.status('patient')).toBe('forbidden');
  });
});

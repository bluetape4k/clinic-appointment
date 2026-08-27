import {
  HttpContext,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { of, firstValueFrom } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { API_AUTH_SCOPE } from '../api/api-auth-context';
import { pwaNetworkInterceptor } from './pwa-network.interceptor';

describe('pwaNetworkInterceptor', () => {
  let onlineDescriptor: PropertyDescriptor | undefined;

  beforeEach(() => {
    onlineDescriptor = Object.getOwnPropertyDescriptor(window.navigator, 'onLine');
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: true });
    TestBed.configureTestingModule({});
  });

  afterEach(() => {
    if (onlineDescriptor) {
      Object.defineProperty(window.navigator, 'onLine', onlineDescriptor);
    }
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  const execute = (request: HttpRequest<unknown>) => {
    const next = vi.fn<HttpHandlerFn>(() => of(new HttpResponse({ status: 200 })));
    const response$ = TestBed.runInInjectionContext(() => pwaNetworkInterceptor(request, next));
    return { next, response$ };
  };

  it('workforce bearer GET은 Service Worker 우회를 명시한다', async () => {
    const request = new HttpRequest('GET', '/api/tenant-default/clinics', {
      context: new HttpContext().set(API_AUTH_SCOPE, 'workforce-bearer'),
    });
    const { next, response$ } = execute(request);

    await firstValueFrom(response$);

    expect(next).toHaveBeenCalledOnce();
    expect(next.mock.calls[0][0].headers.get('ngsw-bypass')).toBe('true');
  });

  it('patient cookie GET도 인증 응답을 cache에서 제외한다', async () => {
    const request = new HttpRequest('GET', '/api/tenant-default/auth/session', {
      context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
      withCredentials: true,
    });
    const { next, response$ } = execute(request);

    await firstValueFrom(response$);

    expect(next.mock.calls[0][0].headers.get('ngsw-bypass')).toBe('true');
  });

  it('public GET은 인증 scope가 없으면 Service Worker 경계를 유지한다', async () => {
    const request = new HttpRequest('GET', '/api/public/master-data/clinics');
    const { next, response$ } = execute(request);

    await firstValueFrom(response$);

    expect(next.mock.calls[0][0].headers.has('ngsw-bypass')).toBe(false);
  });

  it('mutation은 no-store 헤더를 추가한다', async () => {
    const request = new HttpRequest('POST', '/api/tenant-default/appointment-requests', {
      body: { appointmentPlanId: 42 },
      context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
      withCredentials: true,
    });
    const { next, response$ } = execute(request);

    await firstValueFrom(response$);

    expect(next.mock.calls[0][0].headers.get('Cache-Control')).toBe('no-store');
    expect(next.mock.calls[0][0].headers.get('Pragma')).toBe('no-cache');
  });

  it('offline mutation은 network 호출 없이 명시적인 status 0 오류를 반환한다', async () => {
    Object.defineProperty(window.navigator, 'onLine', { configurable: true, value: false });
    const request = new HttpRequest('PATCH', '/api/tenant-default/appointments/42', {
      body: { status: 'CANCELLED' },
      context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
      withCredentials: true,
    });
    const { next, response$ } = execute(request);

    await expect(firstValueFrom(response$)).rejects.toEqual(
      expect.objectContaining<Partial<HttpErrorResponse>>({
        status: 0,
        statusText: 'OFFLINE_MUTATION',
      }),
    );
    expect(next).not.toHaveBeenCalled();
  });
});

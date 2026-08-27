import { TestBed } from '@angular/core/testing';
import {
  HttpContext,
  HttpHeaders,
  HttpHandlerFn,
  HttpRequest,
  HttpResponse,
  HttpXsrfTokenExtractor,
} from '@angular/common/http';
import { of } from 'rxjs';
import { describe, expect, it, beforeEach } from 'vitest';

import { API_AUTH_SCOPE } from '../api/api-auth-context';
import { xsrfInterceptor } from './xsrf.interceptor';

describe('xsrfInterceptor', () => {
  let extractor: { getToken: ReturnType<typeof vi.fn> };

  const runInterceptor = (request: HttpRequest<unknown>) => {
    let captured: HttpRequest<unknown> | undefined;
    const next: HttpHandlerFn = (value) => {
      captured = value;
      return of(new HttpResponse({ status: 200 }));
    };
    TestBed.runInInjectionContext(() => xsrfInterceptor(request, next));
    return captured;
  };

  beforeEach(() => {
    extractor = { getToken: vi.fn() };
    TestBed.configureTestingModule({
      providers: [{ provide: HttpXsrfTokenExtractor, useValue: extractor }],
    });
  });

  it('cross-origin patient mutation에 extractor token을 header로 전달한다', () => {
    extractor.getToken.mockReturnValue('csrf-token');
    const request = new HttpRequest(
      'POST',
      'https://api.example.test/api/tenant-a/appointments',
      null,
      {
        context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
        withCredentials: true,
      },
    );

    const result = runInterceptor(request);

    expect(result?.headers.get('X-XSRF-TOKEN')).toBe('csrf-token');
    expect(result?.withCredentials).toBe(true);
    expect(extractor.getToken).toHaveBeenCalledOnce();
  });

  it('safe method와 workforce Bearer scope에는 XSRF header를 추가하지 않는다', () => {
    extractor.getToken.mockReturnValue('csrf-token');
    const safe = runInterceptor(
      new HttpRequest('GET', 'https://api.example.test/api/tenant-a/appointments', {
        context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
      }),
    );
    const bearer = runInterceptor(
      new HttpRequest('POST', 'https://api.example.test/api/tenant-a/appointments', null, {
        context: new HttpContext().set(API_AUTH_SCOPE, 'workforce-bearer'),
      }),
    );

    expect(safe?.headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(bearer?.headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(extractor.getToken).not.toHaveBeenCalled();
  });

  it('same-origin patient mutation은 Angular built-in XSRF interceptor에 위임한다', () => {
    extractor.getToken.mockReturnValue('csrf-token');
    const result = runInterceptor(
      new HttpRequest('POST', '/api/tenant-a/appointments', null, {
        context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
        withCredentials: true,
      }),
    );

    expect(result?.headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(extractor.getToken).not.toHaveBeenCalled();
  });

  it('patient scope라도 credentials가 꺼져 있으면 token을 읽지 않는다', () => {
    extractor.getToken.mockReturnValue('csrf-token');
    const result = runInterceptor(
      new HttpRequest('POST', 'https://api.example.test/api/tenant-a/appointments', null, {
        context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
        withCredentials: false,
      }),
    );

    expect(result?.headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(extractor.getToken).not.toHaveBeenCalled();
  });

  it('token이 없거나 caller header가 있으면 기존 request를 보존한다', () => {
    extractor.getToken.mockReturnValue(null);
    const missing = runInterceptor(
      new HttpRequest('POST', 'https://api.example.test/api/tenant-a/appointments', null, {
        context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
        withCredentials: true,
      }),
    );
    const existing = runInterceptor(
      new HttpRequest('POST', 'https://api.example.test/api/tenant-a/appointments', null, {
        context: new HttpContext().set(API_AUTH_SCOPE, 'patient-cookie'),
        headers: new HttpHeaders({ 'X-XSRF-TOKEN': 'caller-token' }),
        withCredentials: true,
      }),
    );

    expect(missing?.headers.has('X-XSRF-TOKEN')).toBe(false);
    expect(existing?.headers.get('X-XSRF-TOKEN')).toBe('caller-token');
    expect(extractor.getToken).toHaveBeenCalledOnce();
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';

import { AuthService } from './auth.service';
import { WorkforceAuthBootstrapService } from './workforce-auth-bootstrap.service';

describe('WorkforceAuthBootstrapService', () => {
  let service: WorkforceAuthBootstrapService;
  let auth: {
    bootstrap: ReturnType<typeof vi.fn>;
    markUnauthorized: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    auth = {
      bootstrap: vi.fn(),
      markUnauthorized: vi.fn(),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: auth }],
    });
    service = TestBed.inject(WorkforceAuthBootstrapService);
  });

  afterEach(() => {
    Reflect.deleteProperty(globalThis, '__CLINIC_WORKFORCE_AUTH__');
    TestBed.resetTestingModule();
    vi.restoreAllMocks();
  });

  it('호스트 handoff를 인증 서비스에 전달하고 전역 참조를 즉시 제거한다', () => {
    globalThis.__CLINIC_WORKFORCE_AUTH__ = {
      token: 'workforce-token',
      tenantCode: 'tenant-default',
    };

    service.restore();

    expect(auth.bootstrap).toHaveBeenCalledOnce();
    expect(auth.bootstrap).toHaveBeenCalledWith('workforce-token', 'tenant-default');
    expect(globalThis.__CLINIC_WORKFORCE_AUTH__).toBeUndefined();
    expect(auth.markUnauthorized).not.toHaveBeenCalled();
  });

  it('handoff가 없으면 인증 상태를 건드리지 않는다', () => {
    service.restore();

    expect(auth.bootstrap).not.toHaveBeenCalled();
    expect(auth.markUnauthorized).not.toHaveBeenCalled();
  });

  it('형식이 잘못된 handoff는 폐기하고 앱 부팅을 계속할 수 있게 한다', () => {
    globalThis.__CLINIC_WORKFORCE_AUTH__ = { token: 42 } as never;

    expect(() => service.restore()).not.toThrow();
    expect(auth.bootstrap).not.toHaveBeenCalled();
    expect(auth.markUnauthorized).toHaveBeenCalledOnce();
    expect(globalThis.__CLINIC_WORKFORCE_AUTH__).toBeUndefined();
  });

  it('bootstrap 실패도 앱 부팅 예외로 전파하지 않고 handoff를 폐기한다', () => {
    auth.bootstrap.mockImplementation(() => {
      throw new Error('invalid workforce token');
    });
    globalThis.__CLINIC_WORKFORCE_AUTH__ = { token: 'workforce-token' };

    expect(() => service.restore()).not.toThrow();
    expect(auth.markUnauthorized).toHaveBeenCalledOnce();
    expect(globalThis.__CLINIC_WORKFORCE_AUTH__).toBeUndefined();
  });
});

import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';

import { AuthService } from './auth.service';
import { SessionStateService } from './session-state.service';
import { TenantContextService } from '../api/tenant-context.service';

/** Build a minimal JWT with given payload. */
function makeJwt(payload: Record<string, unknown>): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const body = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 60, ...payload }));
  return `${header}.${body}.signature`;
}

describe('AuthService', () => {
  let store: Record<string, string>;
  let originalLocalStorage: Storage;
  let originalSessionStorage: Storage;

  function makeMockStorage(): Storage {
    return {
      getItem: (key: string) => store[key] ?? null,
      setItem: (key: string, value: string) => { store[key] = value; },
      removeItem: (key: string) => { delete store[key]; },
      clear: () => { for (const k of Object.keys(store)) delete store[k]; },
      key: (index: number) => Object.keys(store)[index] ?? null,
      get length() { return Object.keys(store).length; },
    } as Storage;
  }

  /** Install mock localStorage and create a fresh AuthService. */
  function createService(initialStore: Record<string, string> = {}): AuthService {
    store = { ...initialStore };
    (globalThis as any).localStorage = makeMockStorage();
    (globalThis as any).sessionStorage = makeMockStorage();
    TestBed.configureTestingModule({});
    return TestBed.inject(AuthService);
  }

  beforeEach(() => {
    originalLocalStorage = (globalThis as any).localStorage;
    originalSessionStorage = (globalThis as any).sessionStorage;
    store = {};
  });

  afterEach(() => {
    (globalThis as any).localStorage = originalLocalStorage;
    (globalThis as any).sessionStorage = originalSessionStorage;
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
  });

  it('서비스가 생성된다', () => {
    const service = createService();
    expect(service).toBeTruthy();
  });

  describe('setToken() / getToken() / removeToken()', () => {
    it('기존 localStorage·sessionStorage의 JWT를 초기화하고 인증하지 않는다', () => {
      const service = createService({ auth_token: makeJwt({ roles: [] }) });

      expect(store['auth_token']).toBeUndefined();
      expect(service.getToken()).toBeNull();
      expect(service.isAuthenticated()).toBe(false);
    });

    it('JWT를 브라우저 저장소에 기록하지 않고 메모리 세션으로만 유지한다', () => {
      const service = createService();
      (globalThis as any).sessionStorage = makeMockStorage();
      const token = makeJwt({ roles: [], exp: Math.floor(Date.now() / 1000) + 60 });

      service.setToken(token);

      expect(store).toEqual({});
      expect((globalThis as any).sessionStorage.length).toBe(0);
      expect(service.getToken()).toBe(token);
      TestBed.resetTestingModule();
      expect(createService().getToken()).toBeNull();
    });

    it('setToken()으로 보관된 토큰을 getToken()으로 가져올 수 있다', () => {
      const service = createService();
      const token = makeJwt({ roles: [], exp: Math.floor(Date.now() / 1000) + 60 });
      service.setToken(token);
      expect(service.getToken()).toBe(token);
    });

    it('removeToken() 후 getToken()은 null을 반환한다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: [], exp: Math.floor(Date.now() / 1000) + 60 }));
      service.removeToken();
      expect(service.getToken()).toBeNull();
    });

    it('토큰 없을 때 getToken()은 null을 반환한다', () => {
      const service = createService();
      expect(service.getToken()).toBeNull();
    });
  });

  it('localStorage가 제공되지 않아도 인증 서비스가 예외 없이 초기화된다', () => {
    (globalThis as any).localStorage = undefined;
    TestBed.configureTestingModule({});

    const service = TestBed.inject(AuthService);

    expect(service.getToken()).toBeNull();
    expect(() => service.setToken(makeJwt({ roles: ['ROLE_PATIENT'] }))).not.toThrow();
    expect(service.isAuthenticated()).toBe(true);
    expect(() => service.removeToken()).not.toThrow();
  });

  describe('workforce bootstrap', () => {
    it('허용 tenant가 하나면 비영속 token과 tenant를 함께 복원한다', () => {
      const service = createService();
      const tenant = TestBed.inject(TenantContextService);
      const state = TestBed.inject(SessionStateService);

      service.bootstrap(makeJwt({ roles: ['ROLE_STAFF'], allowedTenants: ['tenant-a'] }));

      expect(tenant.tenantCode()).toBe('tenant-a');
      expect(service.getToken()).toContain('.');
      expect(state.status('workforce')).toBe('authenticated');
      expect(store['auth_token']).toBeUndefined();
    });

    it('다중 tenant token은 명시적으로 선택한 허용 tenant만 복원한다', () => {
      const service = createService();
      const tenant = TestBed.inject(TenantContextService);

      service.bootstrap(makeJwt({ roles: ['ROLE_ADMIN'], allowedTenants: ['tenant-a', 'tenant-b'] }), 'tenant-b');

      expect(tenant.tenantCode()).toBe('tenant-b');
      expect(service.isAuthenticated()).toBe(true);
    });

    it('허용되지 않은 tenant는 token과 tenant를 모두 폐기한다', () => {
      const service = createService();
      const tenant = TestBed.inject(TenantContextService);
      const state = TestBed.inject(SessionStateService);

      expect(() => service.bootstrap(
        makeJwt({ roles: ['ROLE_STAFF'], allowedTenants: ['tenant-a'] }),
        'tenant-b',
      )).toThrow('tenant scope');

      expect(service.getToken()).toBeNull();
      expect(tenant.tenantCode()).toBeNull();
      expect(state.status('workforce')).toBe('unauthorized');
    });

    it('허용 tenant가 여러 개인데 선택값이 없으면 추측하지 않는다', () => {
      const service = createService();
      const tenant = TestBed.inject(TenantContextService);

      expect(() => service.bootstrap(
        makeJwt({ roles: ['ROLE_STAFF'], allowedTenants: ['tenant-a', 'tenant-b'] }),
      )).toThrow('tenant scope');

      expect(service.getToken()).toBeNull();
      expect(tenant.tenantCode()).toBeNull();
    });

    it('명시한 tenant 값이 공백이면 허용 tenant를 대신 선택하지 않는다', () => {
      const service = createService();
      const tenant = TestBed.inject(TenantContextService);

      expect(() => service.bootstrap(
        makeJwt({ roles: ['ROLE_STAFF'], allowedTenants: ['tenant-a'] }),
        '   ',
      )).toThrow('tenant scope');

      expect(service.getToken()).toBeNull();
      expect(tenant.tenantCode()).toBeNull();
    });

    it('허용 목록에 유효하지 않은 tenant code가 있으면 원자적으로 폐기한다', () => {
      const service = createService();
      const tenant = TestBed.inject(TenantContextService);
      const state = TestBed.inject(SessionStateService);

      expect(() => service.bootstrap(
        makeJwt({ roles: ['ROLE_STAFF'], allowedTenants: ['tenant with space'] }),
      )).toThrow('tenant scope');

      expect(service.getToken()).toBeNull();
      expect(tenant.tenantCode()).toBeNull();
      expect(state.status('workforce')).toBe('unauthorized');
    });
  });

  describe('JWT 역할 파싱', () => {
    it('roles 배열이 있는 JWT를 파싱하여 roles signal에 반영한다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_ADMIN', 'ROLE_STAFF'] }));
      expect(service.roles()).toContain('ROLE_ADMIN');
      expect(service.roles()).toContain('ROLE_STAFF');
    });

    it('role 단일 문자열을 파싱하여 배열로 저장한다', () => {
      const service = createService();
      service.setToken(makeJwt({ role: 'ROLE_PATIENT' }));
      expect(service.roles()).toContain('ROLE_PATIENT');
    });

    it('removeToken() 호출 시 roles signal이 빈 배열이 된다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_ADMIN'] }));
      service.removeToken();
      expect(service.roles()).toEqual([]);
    });

    it('잘못된 형식의 토큰은 빈 배열로 처리한다', () => {
      const service = createService();
      service.setToken('header.!!!invalid!!!.sig');
      expect(service.roles()).toEqual([]);
    });

    it('토큰이 없으면 roles signal은 빈 배열이다', () => {
      const service = createService();
      expect(service.roles()).toEqual([]);
    });
  });

  describe('computed role signals', () => {
    it('ROLE_ADMIN 토큰 → isAdmin()이 true이다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_ADMIN'] }));
      expect(service.isAdmin()).toBe(true);
    });

    it('ROLE_ADMIN 토큰 → isStaff(), isDoctor(), isPatient()는 false이다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_ADMIN'] }));
      expect(service.isStaff()).toBe(false);
      expect(service.isDoctor()).toBe(false);
      expect(service.isPatient()).toBe(false);
    });

    it('ROLE_STAFF 토큰 → isStaff()가 true이다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_STAFF'] }));
      expect(service.isStaff()).toBe(true);
    });

    it('ROLE_DOCTOR 토큰 → isDoctor()가 true이다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_DOCTOR'] }));
      expect(service.isDoctor()).toBe(true);
    });

    it('ROLE_PATIENT 토큰 → isPatient()가 true이다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: ['ROLE_PATIENT'] }));
      expect(service.isPatient()).toBe(true);
    });

    it('토큰 없을 때 모든 role computed는 false이다', () => {
      const service = createService();
      expect(service.isAdmin()).toBe(false);
      expect(service.isStaff()).toBe(false);
      expect(service.isDoctor()).toBe(false);
      expect(service.isPatient()).toBe(false);
    });
  });

  describe('isAuthenticated', () => {
    it('exp가 지난 JWT는 authenticated로 인정하지 않는다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: [], exp: Math.floor(Date.now() / 1000) - 1 }));

      expect(service.isAuthenticated()).toBe(false);
      expect(service.getToken()).toBeNull();
    });

    it('nbf가 미래인 JWT는 authenticated로 인정하지 않는다', () => {
      const service = createService();
      service.setToken(makeJwt({
        roles: [],
        exp: Math.floor(Date.now() / 1000) + 60,
        nbf: Math.floor(Date.now() / 1000) + 60,
      }));

      expect(service.isAuthenticated()).toBe(false);
      expect(service.getToken()).toBeNull();
    });

    it('토큰이 있으면 isAuthenticated()가 true이다', () => {
      const service = createService();
      service.setToken(makeJwt({ roles: [], exp: Math.floor(Date.now() / 1000) + 60 }));
      expect(service.isAuthenticated()).toBe(true);
    });

    it('토큰이 없으면 isAuthenticated()가 false이다', () => {
      const service = createService();
      expect(service.isAuthenticated()).toBe(false);
    });
  });
});

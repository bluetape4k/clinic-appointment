import { Injectable, signal } from '@angular/core';

const TENANT_CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

@Injectable({ providedIn: 'root' })
export class TenantContextService {
  private readonly _tenantCode = signal<string | null>(null);

  readonly tenantCode = this._tenantCode.asReadonly();

  constructor() {
    const storedTenant = readStoredTenant();
    if (storedTenant) this._tenantCode.set(storedTenant);
  }

  setTenant(tenantCode: string): void {
    const normalized = tenantCode.trim();
    if (!TENANT_CODE.test(normalized)) {
      throw new Error('유효하지 않은 tenant code입니다.');
    }
    this._tenantCode.set(normalized);
    try {
      globalThis.sessionStorage?.setItem(TENANT_STORAGE_KEY, normalized);
    } catch {
      // private browsing이나 storage quota 오류가 tenant 인증을 차단하지 않도록 한다.
    }
  }

  clear(): void {
    this._tenantCode.set(null);
    try {
      globalThis.sessionStorage?.removeItem(TENANT_STORAGE_KEY);
    } catch {
      // storage cleanup 실패는 이미 메모리에서 제거한 tenant scope를 되살리지 않는다.
    }
  }

  requireTenant(): string {
    const tenantCode = this._tenantCode();
    if (!tenantCode) {
      throw new Error('tenant scope가 설정되지 않았습니다.');
    }
    return tenantCode;
  }
}

const TENANT_STORAGE_KEY = 'appointment_tenant_code';

function readStoredTenant(): string | null {
  try {
    const stored = globalThis.sessionStorage?.getItem(TENANT_STORAGE_KEY)?.trim() ?? '';
    return TENANT_CODE.test(stored) ? stored : null;
  } catch {
    return null;
  }
}

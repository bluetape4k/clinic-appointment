import { Injectable, signal } from '@angular/core';

const TENANT_CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

@Injectable({ providedIn: 'root' })
export class TenantContextService {
  private readonly _tenantCode = signal<string | null>(null);

  readonly tenantCode = this._tenantCode.asReadonly();

  setTenant(tenantCode: string): void {
    const normalized = tenantCode.trim();
    if (!TENANT_CODE.test(normalized)) {
      throw new Error('유효하지 않은 tenant code입니다.');
    }
    this._tenantCode.set(normalized);
  }

  clear(): void {
    this._tenantCode.set(null);
  }

  requireTenant(): string {
    const tenantCode = this._tenantCode();
    if (!tenantCode) {
      throw new Error('tenant scope가 설정되지 않았습니다.');
    }
    return tenantCode;
  }
}

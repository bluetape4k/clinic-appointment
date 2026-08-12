import { beforeEach, afterEach, describe, expect, it } from 'vitest';

import { TenantContextService } from './tenant-context.service';

describe('TenantContextService', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(() => sessionStorage.clear());

  it('tenant code는 bearer token 없이 같은 tab의 reload에서만 복원된다', () => {
    const first = new TenantContextService();
    first.setTenant('tenant-a');

    const reloaded = new TenantContextService();

    expect(reloaded.tenantCode()).toBe('tenant-a');
    expect(sessionStorage.getItem('appointment_tenant_code')).toBe('tenant-a');
  });

  it('clear는 메모리와 session storage의 tenant scope를 함께 제거한다', () => {
    const service = new TenantContextService();
    service.setTenant('tenant-a');

    service.clear();

    expect(service.tenantCode()).toBeNull();
    expect(sessionStorage.getItem('appointment_tenant_code')).toBeNull();
  });

  it('저장된 값이 tenant 문법에 맞지 않으면 무시한다', () => {
    sessionStorage.setItem('appointment_tenant_code', 'tenant/other');

    expect(new TenantContextService().tenantCode()).toBeNull();
  });
});

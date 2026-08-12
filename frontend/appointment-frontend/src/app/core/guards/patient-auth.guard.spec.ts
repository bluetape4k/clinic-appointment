import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { vi } from 'vitest';

import { patientAuthGuard } from './patient-auth.guard';
import { PatientAuthService } from '../services/patient-auth.service';
import { TenantContextService } from '../api/tenant-context.service';

describe('patientAuthGuard', () => {
  let patientAuth: { ensureSession: ReturnType<typeof vi.fn>; isPatient: ReturnType<typeof vi.fn> };
  let tenant: { tenantCode: ReturnType<typeof vi.fn> };
  let router: { createUrlTree: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    patientAuth = { ensureSession: vi.fn(), isPatient: vi.fn().mockReturnValue(true) };
    tenant = { tenantCode: vi.fn().mockReturnValue('tenant-a') };
    router = { createUrlTree: vi.fn().mockReturnValue({ toString: () => '/portal/login' }) };
    TestBed.configureTestingModule({
      providers: [
        { provide: PatientAuthService, useValue: patientAuth },
        { provide: TenantContextService, useValue: tenant },
        { provide: Router, useValue: router },
      ],
    });
  });

  it('tenant과 cookie session이 있으면 통과한다', async () => {
    patientAuth.ensureSession.mockResolvedValue(true);

    const result = await TestBed.runInInjectionContext(() => patientAuthGuard({} as never, {} as never));

    expect(result).toBe(true);
    expect(patientAuth.ensureSession).toHaveBeenCalledOnce();
  });

  it('환자 역할이 아니면 인증 session이 있어도 portal 진입을 거부한다', async () => {
    patientAuth.ensureSession.mockResolvedValue(true);
    patientAuth.isPatient.mockReturnValue(false);

    const result = await TestBed.runInInjectionContext(() => patientAuthGuard({} as never, {} as never));

    expect(result).toEqual(expect.objectContaining({ toString: expect.any(Function) }));
    expect(router.createUrlTree).toHaveBeenCalledWith(['/portal/login']);
  });

  it('session이 없으면 login으로 이동한다', async () => {
    patientAuth.ensureSession.mockResolvedValue(false);

    const result = await TestBed.runInInjectionContext(() => patientAuthGuard({} as never, {} as never));

    expect(result).toEqual(expect.objectContaining({ toString: expect.any(Function) }));
    expect(router.createUrlTree).toHaveBeenCalledWith(['/portal/login']);
  });

  it('tenant가 없으면 backend 호출 없이 login으로 이동한다', async () => {
    tenant.tenantCode.mockReturnValue(null);

    const result = await TestBed.runInInjectionContext(() => patientAuthGuard({} as never, {} as never));

    expect(result).toEqual(expect.objectContaining({ toString: expect.any(Function) }));
    expect(patientAuth.ensureSession).not.toHaveBeenCalled();
  });
});

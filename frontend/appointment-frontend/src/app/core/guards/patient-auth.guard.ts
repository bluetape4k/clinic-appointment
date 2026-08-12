import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { TenantContextService } from '../api/tenant-context.service';
import { PatientAuthService } from '../services/patient-auth.service';

/** tenant cookie session을 복원한 뒤에만 환자 포털 내부 route를 엽니다. */
export const patientAuthGuard: CanActivateFn = async () => {
  const auth = inject(PatientAuthService);
  const tenant = inject(TenantContextService);
  const router = inject(Router);

  if (!tenant.tenantCode()) {
    return router.createUrlTree(['/portal/login']);
  }

  if (!(await auth.ensureSession()) || !auth.isPatient()) {
    return router.createUrlTree(['/portal/login']);
  }
  return true;
};

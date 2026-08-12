import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { PatientAuthService } from '../../core/services/patient-auth.service';
import { TenantContextService } from '../../core/api/tenant-context.service';
import { AppointmentCommitmentFacade } from './appointment-commitment.facade';

export interface PatientPortalNavItem {
  label: string;
  route: string;
}

@Component({
  selector: 'app-patient-portal-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './patient-portal-shell.component.html',
  styleUrl: './patient-portal-shell.component.scss',
})
export class PatientPortalShellComponent {
  readonly auth = inject(PatientAuthService);
  private readonly tenant = inject(TenantContextService);
  private readonly router = inject(Router);
  private readonly commitment = inject(AppointmentCommitmentFacade);
  readonly navItems: PatientPortalNavItem[] = [
    { label: '예약 현황', route: '/portal/appointments' },
    { label: '알림', route: '/portal/notifications' },
    { label: '내 정보', route: '/portal/profile' },
  ];

  async logout(): Promise<void> {
    const tenantCode = this.tenant.tenantCode();
    try {
      if (tenantCode) await this.auth.logout(tenantCode);
    } finally {
      this.commitment.resetForSessionChange();
      await this.router.navigate(['/portal/login'], { replaceUrl: true });
    }
  }
}

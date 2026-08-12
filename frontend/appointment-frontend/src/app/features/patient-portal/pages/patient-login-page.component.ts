import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { PatientLoginIdentifierKey } from '../../../core/api/patient-auth.models';
import { TenantContextService } from '../../../core/api/tenant-context.service';
import { PatientAuthService } from '../../../core/services/patient-auth.service';

@Component({
  selector: 'app-patient-login-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './patient-login-page.component.html',
  styleUrl: './patient-login-page.component.scss',
})
export class PatientLoginPageComponent {
  readonly auth = inject(PatientAuthService);
  private readonly tenant = inject(TenantContextService);
  private readonly router = inject(Router);

  readonly identifierKeys: Array<{ key: PatientLoginIdentifierKey; label: string }> = [
    { key: 'PHONE', label: '전화번호' },
    { key: 'EMAIL', label: '이메일' },
    { key: 'LOGIN_ID', label: '로그인 ID' },
  ];
  readonly tenantCode = signal(this.tenant.tenantCode() ?? '');
  readonly identifierKey = signal<PatientLoginIdentifierKey>('PHONE');
  readonly identifierValue = signal('');
  readonly password = signal('');
  readonly errorMessage = signal<string | null>(null);

  async submit(): Promise<void> {
    this.errorMessage.set(null);
    try {
      const tenantCode = this.tenantCode().trim();
      if (!tenantCode || !this.identifierValue().trim() || !this.password()) {
        throw new Error('required');
      }
      this.tenant.setTenant(tenantCode);
      await this.auth.login(tenantCode, {
        identifier: { key: this.identifierKey(), value: this.identifierValue() },
        password: this.password(),
      });
      await this.router.navigateByUrl('/portal/appointments');
    } catch {
      this.errorMessage.set('입력한 tenant와 로그인 정보를 확인해 주세요.');
    }
  }
}

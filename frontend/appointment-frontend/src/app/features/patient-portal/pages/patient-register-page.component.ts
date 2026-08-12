import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import {
  PatientLoginIdentifierKey,
  PatientLoginIdentifierRequest,
} from '../../../core/api/patient-auth.models';
import { TenantContextService } from '../../../core/api/tenant-context.service';
import { PatientAuthService } from '../../../core/services/patient-auth.service';

interface IdentifierRow extends PatientLoginIdentifierRequest {}

@Component({
  selector: 'app-patient-register-page',
  standalone: true,
  imports: [FormsModule, RouterLink],
  templateUrl: './patient-register-page.component.html',
  styleUrl: './patient-register-page.component.scss',
})
export class PatientRegisterPageComponent {
  readonly auth = inject(PatientAuthService);
  private readonly tenant = inject(TenantContextService);
  private readonly router = inject(Router);

  readonly identifierOptions: Array<{ key: PatientLoginIdentifierKey; label: string }> = [
    { key: 'PHONE', label: '전화번호' },
    { key: 'EMAIL', label: '이메일' },
    { key: 'LOGIN_ID', label: '로그인 ID' },
  ];
  readonly tenantCode = signal(this.tenant.tenantCode() ?? '');
  readonly displayName = signal('');
  readonly password = signal('');
  readonly identifiers = signal<IdentifierRow[]>([{ key: 'PHONE', value: '' }]);
  readonly errorMessage = signal<string | null>(null);
  readonly availableOptions = computed(() => {
    const selected = new Set(this.identifiers().map(identifier => identifier.key));
    return this.identifierOptions.filter(option => !selected.has(option.key));
  });

  addIdentifier(): void {
    const next = this.availableOptions()[0];
    if (!next || this.identifiers().length >= 3) return;
    this.identifiers.update(rows => [...rows, { key: next.key, value: '' }]);
  }

  removeIdentifier(index: number): void {
    if (this.identifiers().length <= 1) return;
    this.identifiers.update(rows => rows.filter((_, rowIndex) => rowIndex !== index));
  }

  updateIdentifier(index: number, patch: Partial<IdentifierRow>): void {
    this.identifiers.update(rows => rows.map((row, rowIndex) => rowIndex === index ? { ...row, ...patch } : row));
  }

  async submit(): Promise<void> {
    this.errorMessage.set(null);
    try {
      const tenantCode = this.tenantCode().trim();
      const identifiers = this.identifiers().map(identifier => ({ ...identifier, value: identifier.value.trim() }));
      if (!tenantCode || !this.displayName().trim() || !this.password() || identifiers.some(identifier => !identifier.value)) {
        throw new Error('required');
      }
      this.tenant.setTenant(tenantCode);
      await this.auth.register(tenantCode, {
        displayName: this.displayName().trim(),
        password: this.password(),
        identifiers,
      });
      await this.router.navigateByUrl('/portal/login');
    } catch {
      this.errorMessage.set('입력값을 확인하거나 이미 등록된 식별자를 바꿔 주세요.');
    }
  }
}

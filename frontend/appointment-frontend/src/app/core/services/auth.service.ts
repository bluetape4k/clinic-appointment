import { Injectable, computed, inject, signal } from '@angular/core';
import { TenantContextService } from '../api/tenant-context.service';
import { SessionStateService } from './session-state.service';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly LEGACY_TOKEN_KEY = 'auth_token';
  private _token: string | null = null;
  private readonly tenant = inject(TenantContextService);
  private readonly sessionState = inject(SessionStateService);

  private readonly _decodedToken = signal<Record<string, unknown> | null>(null);

  readonly roles = computed<string[]>(() => {
    const payload = this._decodedToken();
    if (!payload) return [];
    const roles = payload['roles'] ?? payload['role'] ?? [];
    return Array.isArray(roles) ? roles : [roles as string];
  });

  readonly allowedTenants = computed<string[]>(() => {
    const payload = this._decodedToken();
    return payload ? this._tenantCodes(payload) : [];
  });

  readonly isAuthenticated = computed(() => this._decodedToken() !== null);

  readonly clinicId = computed(() => {
    const raw = this._decodedToken()?.['clinicId'];
    const id = typeof raw === 'number' ? raw : Number(raw);
    return Number.isFinite(id) && id > 0 ? id : 0;
  });

  readonly isAdmin = computed(() => this.roles().includes('ROLE_ADMIN'));
  readonly isStaff = computed(() => this.roles().includes('ROLE_STAFF'));
  readonly isDoctor = computed(() => this.roles().includes('ROLE_DOCTOR'));
  readonly isPatient = computed(() => this.roles().includes('ROLE_PATIENT'));

  constructor() {
    this._clearLegacyTokens();
  }

  getToken(): string | null {
    return this._token;
  }

  setToken(token: string): void {
    const decodedToken = this._parseToken(token);
    if (decodedToken === null) {
      this.removeToken();
      return;
    }

    const allowedTenants = this._tenantCodes(decodedToken);
    const currentTenant = this.tenant.tenantCode();
    if (allowedTenants.length > 0 && currentTenant && !allowedTenants.includes(currentTenant)) {
      this.rejectWorkforceToken();
      return;
    }
    if (!currentTenant && allowedTenants.length === 1) {
      try {
        this.tenant.setTenant(allowedTenants[0]);
      } catch {
        this.rejectWorkforceToken();
        return;
      }
    }

    this._token = token;
    this._decodedToken.set(decodedToken);
    this.sessionState.mark('workforce', 'authenticated');
  }

  /** Gateway가 제공한 비영속 workforce JWT와 선택 tenant를 원자적으로 복원합니다. */
  bootstrap(token: string, tenantCode?: string): void {
    const decodedToken = this._parseToken(token);
    const allowedTenants = decodedToken === null ? [] : this._tenantCodes(decodedToken);
    const explicitTenant = tenantCode === undefined ? undefined : tenantCode.trim();
    const currentTenant = this.tenant.tenantCode();
    const selectedTenant = explicitTenant
      ?? currentTenant
      ?? (allowedTenants.length === 1 ? allowedTenants[0] : undefined);
    if (
      decodedToken === null ||
      allowedTenants.length === 0 ||
      !selectedTenant ||
      !allowedTenants.includes(selectedTenant)
    ) {
      throw this.rejectWorkforceToken();
    }

    try {
      this.tenant.setTenant(selectedTenant);
    } catch {
      throw this.rejectWorkforceToken();
    }
    this._token = token;
    this._decodedToken.set(decodedToken);
    this.sessionState.mark('workforce', 'authenticated');
  }

  removeToken(): void {
    this._token = null;
    this._decodedToken.set(null);
    this.sessionState.mark('workforce', 'anonymous');
  }

  markUnauthorized(): void {
    this.sessionState.mark('workforce', 'unauthorized');
  }

  markForbidden(): void {
    this.sessionState.mark('workforce', 'forbidden');
  }

  private rejectWorkforceToken(): Error {
    this.removeToken();
    this.tenant.clear();
    this.sessionState.mark('workforce', 'unauthorized');
    return new Error('workforce token 또는 tenant scope가 올바르지 않습니다.');
  }

  private _parseToken(token: string): Record<string, unknown> | null {
    if (!token) return null;
    try {
      const payloadPart = token.split('.')[1];
      if (!payloadPart) return null;

      const payload = JSON.parse(this._decodeBase64Url(payloadPart)) as Record<string, unknown>;
      if (!this._hasValidTimeClaims(payload)) return null;
      return payload;
    } catch {
      return null;
    }
  }

  private _hasValidTimeClaims(payload: Record<string, unknown>): boolean {
    const now = Math.floor(Date.now() / 1000);
    const exp = payload['exp'];
    if (typeof exp !== 'number' || !Number.isFinite(exp) || now >= exp) return false;

    const nbf = payload['nbf'];
    return nbf === undefined
      || (typeof nbf === 'number' && Number.isFinite(nbf) && now >= nbf);
  }

  private _tenantCodes(payload: Record<string, unknown>): string[] {
    const values = payload['allowedTenants'];
    return Array.isArray(values)
      ? values.filter((value): value is string => typeof value === 'string' && value.length > 0)
      : [];
  }

  private _decodeBase64Url(value: string): string {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/');
    const padding = (4 - (normalized.length % 4)) % 4;
    return atob(normalized.padEnd(normalized.length + padding, '='));
  }

  private _clearLegacyTokens(): void {
    try {
      globalThis.localStorage?.removeItem(this.LEGACY_TOKEN_KEY);
    } catch {
      // Storage access can be denied by browser privacy settings.
    }

    try {
      globalThis.sessionStorage?.removeItem(this.LEGACY_TOKEN_KEY);
    } catch {
      // Storage access can be denied by browser privacy settings.
    }
  }
}

import { Injectable, computed, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly LEGACY_TOKEN_KEY = 'auth_token';
  private _token: string | null = null;

  private readonly _decodedToken = signal<Record<string, unknown> | null>(null);

  readonly roles = computed<string[]>(() => {
    const payload = this._decodedToken();
    if (!payload) return [];
    const roles = payload['roles'] ?? payload['role'] ?? [];
    return Array.isArray(roles) ? roles : [roles as string];
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

    this._token = token;
    this._decodedToken.set(decodedToken);
  }

  removeToken(): void {
    this._token = null;
    this._decodedToken.set(null);
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

import { Injectable, computed, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'auth_token';

  private readonly _decodedToken = signal<Record<string, unknown> | null>(this._parseToken());

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

  getToken(): string | null {
    return this._storage()?.getItem(this.TOKEN_KEY) ?? null;
  }

  setToken(token: string): void {
    this._storage()?.setItem(this.TOKEN_KEY, token);
    this._decodedToken.set(this._parseToken());
  }

  removeToken(): void {
    this._storage()?.removeItem(this.TOKEN_KEY);
    this._decodedToken.set(null);
  }

  private _parseToken(): Record<string, unknown> | null {
    const token = this.getToken();
    if (!token) return null;
    try {
      return JSON.parse(atob(token.split('.')[1])) as Record<string, unknown>;
    } catch {
      return null;
    }
  }

  private _storage(): Storage | null {
    try {
      return typeof globalThis.localStorage === 'undefined' ? null : globalThis.localStorage;
    } catch {
      return null;
    }
  }
}

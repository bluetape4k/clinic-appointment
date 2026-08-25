import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';

import { ApiResponse } from '../models/api-response.model';
import { TenantContextService } from '../api/tenant-context.service';
import { TenantApiClient } from '../api/tenant-api-client';
import { SessionStateService } from './session-state.service';
import {
  PatientLoginRequest,
  PatientRegisterRequest,
  PatientRegistrationResponse,
  PatientSessionSummary,
} from '../api/patient-auth.models';

/** HttpOnly patient session cookie를 사용하는 환자 인증 API입니다. */
@Injectable({ providedIn: 'root' })
export class PatientAuthService {
  private readonly api = inject(TenantApiClient);
  private readonly tenant = inject(TenantContextService);
  private readonly sessionState = inject(SessionStateService);

  private readonly _session = signal<PatientSessionSummary | null>(null);
  private _sessionVersion = 0;
  readonly session = this._session.asReadonly();
  readonly isAuthenticated = computed(() => this._session() !== null);
  readonly isPatient = computed(() => this._session()?.role === 'PATIENT');
  readonly loading = signal(false);

  /** cookie/session 주체가 바뀌는 순간 동기적으로 증가하는 client epoch입니다. */
  currentSessionVersion(): number {
    return this._sessionVersion;
  }

  beginSessionChange(): void {
    this._sessionVersion += 1;
    this._session.set(null);
    this.sessionState.mark('patient', 'anonymous');
  }

  async bootstrapCsrf(tenantCode: string): Promise<void> {
    this.requireTenant(tenantCode);
    await this.api.request<ApiResponse<{ ready: boolean }>>('GET', this.path('csrf'), {
      authScope: 'patient-cookie',
      withCredentials: true,
    });
  }

  async register(
    tenantCode: string,
    request: PatientRegisterRequest,
  ): Promise<PatientRegistrationResponse> {
    this.loading.set(true);
    try {
      await this.bootstrapCsrf(tenantCode);
      const response = await this.api.request<ApiResponse<PatientRegistrationResponse>>('POST', this.path('register'), {
        body: request,
        authScope: 'patient-cookie',
        withCredentials: true,
      });
      return this.requireData(response.body, '회원가입 응답이 비어 있습니다.');
    } finally {
      this.loading.set(false);
    }
  }

  async login(tenantCode: string, request: PatientLoginRequest): Promise<PatientSessionSummary> {
    this.beginSessionChange();
    const requestedSessionVersion = this._sessionVersion;
    const requestedTenantCode = tenantCode;
    this.loading.set(true);
    try {
      await this.bootstrapCsrf(tenantCode);
      const response = await this.api.request<ApiResponse<PatientSessionSummary>>('POST', this.path('login'), {
        body: request,
        authScope: 'patient-cookie',
        withCredentials: true,
      });
      const session = this.requireData(response.body, '로그인 응답이 비어 있습니다.');
      if (
        requestedSessionVersion !== this._sessionVersion ||
        this.tenant.tenantCode() !== requestedTenantCode
      ) {
        throw new Error('로그인 응답이 현재 session에 속하지 않습니다.');
      }
      this._session.set(session);
      this.sessionState.mark('patient', 'authenticated');
      return session;
    } finally {
      if (requestedSessionVersion === this._sessionVersion) this.loading.set(false);
    }
  }

  async sessionFor(tenantCode: string): Promise<PatientSessionSummary> {
    // A session restore is a new identity observation. Invalidate any older
    // restore/login result before issuing the request so two concurrent cookie
    // observations cannot apply in completion order.
    this.beginSessionChange();
    this.requireTenant(tenantCode);
    const requestedSessionVersion = this._sessionVersion;
    const requestedTenantCode = tenantCode;
    const response = await this.api.request<ApiResponse<PatientSessionSummary>>('GET', this.path('session'), {
      authScope: 'patient-cookie',
      withCredentials: true,
    });
    const session = this.requireData(response.body, '환자 session 응답이 비어 있습니다.');
    if (
      requestedSessionVersion !== this._sessionVersion ||
      this.tenant.tenantCode() !== requestedTenantCode
    ) {
      throw new Error('환자 session 응답이 현재 session에 속하지 않습니다.');
    }
    this._session.set(session);
    this.sessionState.mark('patient', 'authenticated');
    return session;
  }

  async logout(tenantCode: string): Promise<void> {
    this.beginSessionChange();
    const requestedSessionVersion = this._sessionVersion;
    this.loading.set(true);
    try {
      await this.bootstrapCsrf(tenantCode);
      await this.api.request<void>('POST', this.path('logout'), {
        body: null,
        authScope: 'patient-cookie',
        withCredentials: true,
      });
    } finally {
      if (requestedSessionVersion === this._sessionVersion) {
        this._session.set(null);
        this.loading.set(false);
        this.sessionState.mark('patient', 'anonymous');
      }
    }
  }

  /** route guard가 새로고침 뒤 cookie session을 한 번만 복원합니다. */
  async ensureSession(): Promise<boolean> {
    const tenantCode = this.tenant.tenantCode();
    if (!tenantCode) {
      this._session.set(null);
      this.sessionState.mark('patient', 'tenant-missing');
      return false;
    }

    const current = this._session();
    if (current && Date.parse(current.expiresAt) > Date.now()) return true;

    try {
      await this.sessionFor(tenantCode);
      return true;
    } catch (error) {
      this._session.set(null);
      const status = error instanceof HttpErrorResponse ? error.status : 0;
      this.sessionState.mark('patient', status === 401 ? 'unauthorized' : status === 403 ? 'forbidden' : 'anonymous');
      return false;
    }
  }

  clearSession(): void {
    this.beginSessionChange();
  }

  private path(action: string): string {
    return `/auth/${action}`;
  }

  private requireTenant(tenantCode: string): void {
    if (!tenantCode || this.tenant.tenantCode() !== tenantCode) {
      this.sessionState.mark('patient', 'tenant-missing');
      throw new Error('tenant scope가 설정되지 않았습니다.');
    }
  }

  private requireData<T>(response: ApiResponse<T> | null, message: string): T {
    if (!response || !response.success || response.data == null) throw new Error(message);
    return response.data;
  }
}

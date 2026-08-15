import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../environments/environment';
import { ApiResponse } from '../models/api-response.model';
import { TenantContextService } from '../api/tenant-context.service';
import {
  PatientLoginRequest,
  PatientRegisterRequest,
  PatientRegistrationResponse,
  PatientSessionSummary,
} from '../api/patient-auth.models';

/** HttpOnly patient session cookie를 사용하는 환자 인증 API입니다. */
@Injectable({ providedIn: 'root' })
export class PatientAuthService {
  private readonly http = inject(HttpClient);
  private readonly tenant = inject(TenantContextService);

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
  }

  async bootstrapCsrf(tenantCode: string): Promise<void> {
    await firstValueFrom(
      this.http.get<ApiResponse<{ ready: boolean }>>(this.url(tenantCode, 'csrf'), {
        withCredentials: true,
      }),
    );
  }

  async register(
    tenantCode: string,
    request: PatientRegisterRequest,
  ): Promise<PatientRegistrationResponse> {
    this.loading.set(true);
    try {
      await this.bootstrapCsrf(tenantCode);
      const response = await firstValueFrom(
        this.http.post<ApiResponse<PatientRegistrationResponse>>(
          this.url(tenantCode, 'register'),
          request,
          { withCredentials: true },
        ),
      );
      return this.requireData(response, '회원가입 응답이 비어 있습니다.');
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
      const response = await firstValueFrom(
        this.http.post<ApiResponse<PatientSessionSummary>>(
          this.url(tenantCode, 'login'),
          request,
          { withCredentials: true },
        ),
      );
      const session = this.requireData(response, '로그인 응답이 비어 있습니다.');
      if (
        requestedSessionVersion !== this._sessionVersion ||
        this.tenant.tenantCode() !== requestedTenantCode
      ) {
        throw new Error('로그인 응답이 현재 session에 속하지 않습니다.');
      }
      this._session.set(session);
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
    const requestedSessionVersion = this._sessionVersion;
    const requestedTenantCode = tenantCode;
    const response = await firstValueFrom(
      this.http.get<ApiResponse<PatientSessionSummary>>(
        this.url(tenantCode, 'session'),
        { withCredentials: true },
      ),
    );
    const session = this.requireData(response, '환자 session 응답이 비어 있습니다.');
    if (
      requestedSessionVersion !== this._sessionVersion ||
      this.tenant.tenantCode() !== requestedTenantCode
    ) {
      throw new Error('환자 session 응답이 현재 session에 속하지 않습니다.');
    }
    this._session.set(session);
    return session;
  }

  async logout(tenantCode: string): Promise<void> {
    this.beginSessionChange();
    const requestedSessionVersion = this._sessionVersion;
    this.loading.set(true);
    try {
      await this.bootstrapCsrf(tenantCode);
      await firstValueFrom(
        this.http.post<void>(this.url(tenantCode, 'logout'), null, { withCredentials: true }),
      );
    } finally {
      if (requestedSessionVersion === this._sessionVersion) {
        this._session.set(null);
        this.loading.set(false);
      }
    }
  }

  /** route guard가 새로고침 뒤 cookie session을 한 번만 복원합니다. */
  async ensureSession(): Promise<boolean> {
    const tenantCode = this.tenant.tenantCode();
    if (!tenantCode) {
      this._session.set(null);
      return false;
    }

    const current = this._session();
    if (current && Date.parse(current.expiresAt) > Date.now()) return true;

    try {
      await this.sessionFor(tenantCode);
      return true;
    } catch (error) {
      this._session.set(null);
      if (error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403)) {
        return false;
      }
      return false;
    }
  }

  clearSession(): void {
    this.beginSessionChange();
  }

  private url(tenantCode: string, action: string): string {
    const normalized = tenantCode.trim();
    if (!normalized) throw new Error('tenant scope가 설정되지 않았습니다.');
    return `${environment.apiUrl}/${encodeURIComponent(normalized)}/auth/${action}`;
  }

  private requireData<T>(response: ApiResponse<T>, message: string): T {
    if (!response.success || response.data == null) throw new Error(message);
    return response.data;
  }
}

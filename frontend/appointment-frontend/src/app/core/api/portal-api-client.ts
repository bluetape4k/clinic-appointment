import { HttpClient, HttpErrorResponse, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { environment } from '../../../environments/environment';
import {
  ApiEnvelope,
  AppointmentCommitmentResponse,
  AppointmentProposalResponse,
  AvailableSlot,
  CancelAppointmentRequest,
  CreateAppointmentRequest,
  DeclineProposalRequest,
  CancellationHistoryPageResult,
  PortalResponse,
  PatientCancellationHistoryPage,
  PatientHistoryQuery,
  PortalClinicMetadata,
  ProposalDecisionRequest,
} from './portal-api.models';
import { mapPortalApiError, PortalApiException } from './portal-api-error';
import type { PortalNotification } from './portal-event-stream.adapter';
import { TenantContextService } from './tenant-context.service';
import { PatientAuthService } from '../services/patient-auth.service';

@Injectable({ providedIn: 'root' })
export class PortalApiClient {
  private readonly http = inject(HttpClient);
  private readonly tenant = inject(TenantContextService);
  private readonly auth = inject(PatientAuthService);

  async requestAppointment(
    body: CreateAppointmentRequest,
    idempotencyKey: string,
  ): Promise<PortalResponse<AppointmentProposalResponse>> {
    return this.post('/appointment-requests', body, {
      'Idempotency-Key': idempotencyKey,
      'If-None-Match': '*',
    });
  }

  async getCommitment(appointmentId: number): Promise<PortalResponse<AppointmentCommitmentResponse>> {
    return this.get(`/appointments/${this.requirePositiveId(appointmentId)}/commitment`);
  }

  async acceptProposal(
    appointmentId: number,
    proposalId: number,
    body: ProposalDecisionRequest,
    idempotencyKey: string,
    etag: string,
  ): Promise<PortalResponse<AppointmentCommitmentResponse>> {
    return this.post(
      `/appointments/${this.requirePositiveId(appointmentId)}/proposals/${this.requirePositiveId(proposalId)}/accept`,
      body,
      { 'Idempotency-Key': idempotencyKey, 'If-Match': etag },
    );
  }

  async declineProposal(
    appointmentId: number,
    proposalId: number,
    body: DeclineProposalRequest,
    idempotencyKey: string,
    etag: string,
  ): Promise<PortalResponse<AppointmentCommitmentResponse>> {
    return this.post(
      `/appointments/${this.requirePositiveId(appointmentId)}/proposals/${this.requirePositiveId(proposalId)}/decline`,
      body,
      { 'Idempotency-Key': idempotencyKey, 'If-Match': etag },
    );
  }

  async cancelAppointment(
    appointmentId: number,
    body: CancelAppointmentRequest,
    idempotencyKey: string,
    etag: string,
  ): Promise<PortalResponse<AppointmentCommitmentResponse>> {
    return this.post(
      `/appointments/${this.requirePositiveId(appointmentId)}/cancel`,
      body,
      { 'Idempotency-Key': idempotencyKey, 'If-Match': etag },
    );
  }

  async getSlots(
    clinicId: number,
    doctorId: number,
    treatmentTypeId: number,
    date: string,
    requestedDurationMinutes?: number,
  ): Promise<PortalResponse<AvailableSlot[]>> {
    const params = new HttpParams()
      .set('doctorId', doctorId)
      .set('treatmentTypeId', treatmentTypeId)
      .set('date', date);
    const requestParams = requestedDurationMinutes == null
      ? params
      : params.set('requestedDurationMinutes', requestedDurationMinutes);
    const response = await this.send<ApiEnvelope<AvailableSlot[]>>('GET', `/clinics/${this.requirePositiveId(clinicId)}/slots`, undefined, {}, requestParams);
    return { ...response, body: response.body?.data ?? [] };
  }

  async getClinic(clinicId: number): Promise<PortalResponse<PortalClinicMetadata>> {
    const response = await this.send<ApiEnvelope<PortalClinicMetadata>>('GET', `/clinics/${this.requirePositiveId(clinicId)}`);
    const clinic = response.body?.data;
    if (!clinic) throw new Error('병원 권위 메타데이터가 비어 있습니다.');
    return { ...response, body: clinic };
  }

  async getNotifications(): Promise<PortalResponse<PortalNotification[]>> {
    const response = await this.send<ApiEnvelope<PortalNotification[]>>('GET', '/notifications');
    return { ...response, body: response.body?.data ?? [] };
  }

  /**
   * 환자 취소 이력의 public query 계약입니다. 환자·tenant 식별자와 cache/ETag는
   * 호출자에게서 받지 않으며, 이 client의 session-memory cache에서만 관리합니다.
   */
  async getCancellationHistory(query: PatientHistoryQuery): Promise<CancellationHistoryPageResult> {
    validateHistoryQuery(query);
    const tenantCode = this.tenant.requireTenant();
    this.resetHistoryScopeIfTenantChanged(tenantCode);
    const sessionVersion = this.auth.currentSessionVersion();
    const requestEpoch = this.historyRequestEpoch;
    const generation = this.historyTenantGeneration;
    const params = new HttpParams()
      .set('limit', query.limit);
    const requestParams = query.cursor == null ? params : params.set('cursor', query.cursor);
    const cacheKey = generation == null ? null : this.historyCacheKey(query, generation, sessionVersion, tenantCode);
    const cached = cacheKey == null ? undefined : this.historyCache.get(cacheKey);
    const headers: Record<string, string> = {};
    if (cached?.etag) headers['If-None-Match'] = cached.etag;
    if (generation != null) headers['X-Tenant-Identity-Generation'] = generation;

    try {
      const response = await this.send<PatientCancellationHistoryPage>(
        'GET',
        '/patient/appointments/cancellation-history',
        undefined,
        headers,
        requestParams,
        true,
      );
      const etag = requireStrongHistoryEtag(response.etag);
      const responseGeneration = requireTenantIdentityGeneration(response.tenantIdentityGeneration ?? null);
      const body = response.body;
      if (!body) throw new Error('취소 이력 응답이 비어 있습니다.');
      this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
      if (generation != null && generation !== responseGeneration) {
        this.historyCache.clear();
        this.historyTenantGeneration = null;
        if (query.cursor === null && this.historyGenerationRecoveryEpoch !== requestEpoch) {
          this.historyGenerationRecoveryEpoch = requestEpoch;
          this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
          return this.getCancellationHistoryUncached(query, requestEpoch, sessionVersion, tenantCode);
        }
        throw new Error('취소 이력 tenant generation이 안정되지 않았습니다.');
      }
      this.historyTenantGeneration = responseGeneration;
      this.historyCache.set(
        this.historyCacheKey(query, responseGeneration, sessionVersion, tenantCode),
        { body, etag, generation: responseGeneration, sessionVersion, tenantCode },
      );
      return { kind: 'body', body, etag, tenantIdentityGeneration: responseGeneration };
    } catch (error) {
      if (error instanceof PortalApiNotModified) {
        const current = cacheKey == null ? undefined : this.historyCache.get(cacheKey);
        if (!current) {
          // 304 without a matching private body is a caller/cache contract violation.
          this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
          return this.getCancellationHistoryUncached(query, requestEpoch, sessionVersion, tenantCode);
        }
        let responseGeneration: string;
        try {
          responseGeneration = requireTenantIdentityGeneration(error.generation);
        } catch {
          this.historyCache.clear();
          this.historyTenantGeneration = null;
          this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
          return this.getCancellationHistoryUncached(query, requestEpoch, sessionVersion, tenantCode);
        }
        if (responseGeneration !== current.generation || responseGeneration !== this.historyTenantGeneration) {
          this.historyCache.clear();
          this.historyTenantGeneration = null;
          if (query.cursor === null && this.historyGenerationRecoveryEpoch !== requestEpoch) {
            this.historyGenerationRecoveryEpoch = requestEpoch;
            this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
            return this.getCancellationHistoryUncached(query, requestEpoch, sessionVersion, tenantCode);
          }
          throw new Error('취소 이력 tenant generation이 안정되지 않았습니다.');
        }
        this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
        return { kind: 'not-modified', body: current.body, etag: current.etag, tenantIdentityGeneration: current.generation };
      }
      throw error;
    }
  }

  /** facade/session lifecycle이 바뀔 때 private history body와 ETag를 제거합니다. */
  clearCancellationHistoryCache(): void {
    this.historyRequestEpoch += 1;
    this.historyCache.clear();
    this.historyTenantGeneration = null;
    this.historyTenantCode = null;
    this.historyGenerationRecoveryEpoch = null;
  }

  private async get<T>(path: string): Promise<PortalResponse<T>> {
    return this.send<T>('GET', path);
  }

  private async post<T>(path: string, body: unknown, headers: Record<string, string>): Promise<PortalResponse<T>> {
    return this.send<T>('POST', path, body, headers);
  }

  private async send<T>(
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
    extraHeaders: Record<string, string> = {},
    params?: HttpParams,
    historyNotModified = false,
  ): Promise<PortalResponse<T>> {
    try {
      const tenantCode = this.tenant.requireTenant();
      const headers = new HttpHeaders({ Accept: 'application/json', ...extraHeaders });
      const response = await firstValueFrom(this.http.request<T>(method, `${environment.apiUrl}/${encodeURIComponent(tenantCode)}${path}`, {
        body,
        headers,
        params,
        observe: 'response',
      }));
      if (response.status === 304 && historyNotModified) {
        throw new PortalApiNotModified(response.headers.get('X-Tenant-Identity-Generation'));
      }
      return {
        body: response.body as T,
        etag: response.headers.get('ETag'),
        retryAfterSeconds: parseRetryAfter(response.headers),
        tenantIdentityGeneration: response.headers.get('X-Tenant-Identity-Generation'),
      };
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 304 && historyNotModified) {
          throw new PortalApiNotModified(error.headers.get('X-Tenant-Identity-Generation'));
        }
        throw new PortalApiException(mapPortalApiError(error), error);
      }
      if (error instanceof Error && error.message.includes('tenant scope')) {
        throw new PortalApiException({
          kind: 'tenant-missing',
          status: 0,
          code: 'TENANT_SCOPE_MISSING',
          message: error.message,
          retryAfterSeconds: null,
          correlationId: null,
        }, error);
      }
      throw error;
    }
  }

  private async getCancellationHistoryUncached(
    query: PatientHistoryQuery,
    requestEpoch: number,
    sessionVersion: number,
    tenantCode: string,
  ): Promise<CancellationHistoryPageResult> {
    validateHistoryQuery(query);
    const params = new HttpParams().set('limit', query.limit);
    const requestParams = query.cursor == null ? params : params.set('cursor', query.cursor);
    const response = await this.send<PatientCancellationHistoryPage>(
      'GET',
      '/patient/appointments/cancellation-history',
      undefined,
      {},
      requestParams,
      true,
    );
    const etag = requireStrongHistoryEtag(response.etag);
    const generation = requireTenantIdentityGeneration(response.tenantIdentityGeneration ?? null);
    if (!response.body) throw new Error('취소 이력 응답이 비어 있습니다.');
    this.ensureHistoryRequestCurrent(tenantCode, requestEpoch, sessionVersion);
    if (this.historyTenantGeneration != null && this.historyTenantGeneration !== generation) {
      this.historyCache.clear();
      this.historyTenantGeneration = null;
      if (query.cursor !== null || this.historyGenerationRecoveryEpoch === requestEpoch) {
        throw new Error('취소 이력 tenant generation이 안정되지 않았습니다.');
      }
      this.historyGenerationRecoveryEpoch = requestEpoch;
    }
    this.historyTenantGeneration = generation;
    const cacheKey = this.historyCacheKey(query, generation, sessionVersion, tenantCode);
    this.historyCache.set(cacheKey, { body: response.body, etag, generation, sessionVersion, tenantCode });
    return { kind: 'body', body: response.body, etag, tenantIdentityGeneration: generation };
  }

  private historyCache = new Map<string, { body: PatientCancellationHistoryPage; etag: string; generation: string; sessionVersion: number; tenantCode: string }>();
  private historyRequestEpoch = 0;
  private historyTenantGeneration: string | null = null;
  private historyTenantCode: string | null = null;
  private historyGenerationRecoveryEpoch: number | null = null;

  private historyCacheKey(query: PatientHistoryQuery, generation: string, sessionVersion: number, tenantCode: string): string {
    return `${sessionVersion}\u0000${generation}\u0000${tenantCode}\u0000${query.cursor ?? ''}\u0000${query.limit}`;
  }

  private ensureHistoryRequestCurrent(tenantCode: string, requestEpoch: number, sessionVersion: number): void {
    if (
      requestEpoch !== this.historyRequestEpoch ||
      sessionVersion !== this.auth.currentSessionVersion() ||
      this.tenant.tenantCode() !== tenantCode
    ) {
      throw new Error('취소 이력 응답이 현재 session에 속하지 않습니다.');
    }
  }

  private resetHistoryScopeIfTenantChanged(tenantCode: string): void {
    if (this.historyTenantCode !== null && this.historyTenantCode !== tenantCode) {
      this.historyRequestEpoch += 1;
      this.historyCache.clear();
      this.historyTenantGeneration = null;
      this.historyGenerationRecoveryEpoch = null;
    }
    this.historyTenantCode = tenantCode;
  }

  private requirePositiveId(value: number): number {
    if (!Number.isInteger(value) || value <= 0) {
      throw new Error('식별자는 양의 정수여야 합니다.');
    }
    return value;
  }
}

class PortalApiNotModified extends Error {
  constructor(readonly generation: string | null) {
    super('취소 이력 응답이 변경되지 않았습니다.');
  }
}

function validateHistoryQuery(query: PatientHistoryQuery): void {
  if (!Number.isInteger(query.limit) || query.limit < 1 || query.limit > 50) {
    throw new Error('취소 이력 조회 건수는 1에서 50 사이여야 합니다.');
  }
  if (query.cursor !== null && typeof query.cursor !== 'string') {
    throw new Error('취소 이력 cursor가 올바르지 않습니다.');
  }
  if (typeof query.cursor === 'string' && query.cursor.length === 0) {
    throw new Error('취소 이력 cursor가 올바르지 않습니다.');
  }
}

function requireStrongHistoryEtag(etag: string | null): string {
  if (!etag || !/^"sha256:[0-9a-f]{64}"$/.test(etag)) {
    throw new Error('취소 이력 ETag가 올바르지 않습니다.');
  }
  return etag;
}

function requireTenantIdentityGeneration(generation: string | null): string {
  if (!generation || !/^v1\.[A-Za-z0-9_-]{1,32}$/.test(generation)) {
    throw new Error('tenant identity generation이 올바르지 않습니다.');
  }
  return generation;
}

function parseRetryAfter(headers: HttpHeaders): number | null {
  const raw = headers.get('Retry-After');
  if (!raw) return null;
  const seconds = Number.parseInt(raw, 10);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

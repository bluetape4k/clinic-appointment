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
  PortalResponse,
  ProposalDecisionRequest,
} from './portal-api.models';
import { mapPortalApiError, PortalApiException } from './portal-api-error';
import type { PortalNotification } from './portal-event-stream.adapter';
import { TenantContextService } from './tenant-context.service';

@Injectable({ providedIn: 'root' })
export class PortalApiClient {
  private readonly http = inject(HttpClient);
  private readonly tenant = inject(TenantContextService);

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

  async getNotifications(): Promise<PortalResponse<PortalNotification[]>> {
    const response = await this.send<ApiEnvelope<PortalNotification[]>>('GET', '/notifications');
    return { ...response, body: response.body?.data ?? [] };
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
      return {
        body: response.body as T,
        etag: response.headers.get('ETag'),
        retryAfterSeconds: parseRetryAfter(response.headers),
      };
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
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

  private requirePositiveId(value: number): number {
    if (!Number.isInteger(value) || value <= 0) {
      throw new Error('식별자는 양의 정수여야 합니다.');
    }
    return value;
  }
}

function parseRetryAfter(headers: HttpHeaders): number | null {
  const raw = headers.get('Retry-After');
  if (!raw) return null;
  const seconds = Number.parseInt(raw, 10);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null;
}

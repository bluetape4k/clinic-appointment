import { Injectable, inject, signal } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { ApiResponse, Appointment, CreateAppointmentRequest, UpdateStatusRequest } from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class AppointmentService {
  private readonly api = inject(TenantApiClient);
  private readonly basePath = '/appointments';

  private readonly _appointments = signal<Appointment[]>([]);
  readonly appointments = this._appointments.asReadonly();

  readonly loading = signal(false);

  async getByDateRange(clinicId: number, from: string, to: string): Promise<Appointment[]> {
    this.loading.set(true);
    try {
      const params = new HttpParams()
        .set('clinicId', clinicId)
        .set('startDate', from)
        .set('endDate', to);
      const res = await this.api.request<ApiResponse<Appointment[]>>('GET', this.basePath, {
        params,
        authScope: 'workforce-bearer',
      });
      const data = res.body?.data ?? [];
      this._appointments.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(id: number): Promise<Appointment> {
    const res = await this.api.request<ApiResponse<Appointment>>('GET', `${this.basePath}/${id}`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data as Appointment;
  }

  async create(request: CreateAppointmentRequest): Promise<Appointment> {
    const res = await this.api.request<ApiResponse<Appointment>>('POST', this.basePath, {
      body: request,
      authScope: 'workforce-bearer',
    });
    const created = res.body?.data as Appointment;
    this._appointments.update(list => [...list, created]);
    return created;
  }

  async updateStatus(id: number, request: UpdateStatusRequest): Promise<Appointment> {
    const res = await this.api.request<ApiResponse<Appointment>>('PATCH', `${this.basePath}/${id}/status`, {
      body: request,
      authScope: 'workforce-bearer',
    });
    const updated = res.body?.data as Appointment;
    this._appointments.update(list =>
      list.map(a => (a.id === id ? updated : a))
    );
    return updated;
  }

  async cancel(id: number, reason?: string): Promise<Appointment> {
    const res = await this.api.request<ApiResponse<Appointment>>('DELETE', `${this.basePath}/${id}`, {
      authScope: 'workforce-bearer',
    });
    const cancelled = res.body?.data as Appointment;
    this._appointments.update(list =>
      list.map(a => (a.id === id ? cancelled : a))
    );
    return cancelled;
  }
}

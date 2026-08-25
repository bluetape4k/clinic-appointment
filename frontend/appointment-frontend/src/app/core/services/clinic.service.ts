import { Injectable, inject, signal } from '@angular/core';
import { ApiResponse, Clinic, ClinicBreakTime, OperatingHours, PagedData } from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class ClinicService {
  private readonly api = inject(TenantApiClient);
  private readonly basePath = '/clinics';

  private readonly _clinics = signal<Clinic[]>([]);
  readonly clinics = this._clinics.asReadonly();

  readonly loading = signal(false);

  async getAll(): Promise<Clinic[]> {
    this.loading.set(true);
    try {
      const res = await this.api.request<ApiResponse<PagedData<Clinic>>>('GET', this.basePath, {
        authScope: 'workforce-bearer',
      });
      const data = res.body?.data?.content ?? [];
      this._clinics.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(clinicId: number): Promise<Clinic> {
    const res = await this.api.request<ApiResponse<Clinic>>('GET', `${this.basePath}/${clinicId}`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data as Clinic;
  }

  async getOperatingHours(clinicId: number): Promise<OperatingHours[]> {
    const res = await this.api.request<ApiResponse<OperatingHours[]>>('GET', `${this.basePath}/${clinicId}/operating-hours`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data ?? [];
  }

  async getBreakTimes(clinicId: number): Promise<ClinicBreakTime[]> {
    const res = await this.api.request<ApiResponse<ClinicBreakTime[]>>('GET', `${this.basePath}/${clinicId}/break-times`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data ?? [];
  }
}

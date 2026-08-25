import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import {
  ApiResponse,
  AppointmentStatsResponse,
  CancellationStatsResponse,
  DoctorStatsResponse,
} from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class DashboardStatsService {
  private readonly api = inject(TenantApiClient);
  private readonly basePath = '/admin/stats';

  async getAppointmentStats(
    clinicId: number,
    from: string,
    to: string,
    statuses?: string[],
  ): Promise<AppointmentStatsResponse> {
    let params = new HttpParams()
      .set('clinicId', clinicId)
      .set('from', from)
      .set('to', to);
    if (statuses?.length) {
      statuses.forEach(s => { params = params.append('statuses', s); });
    }
    const res = await this.api.request<ApiResponse<AppointmentStatsResponse>>('GET', `${this.basePath}/appointments`, {
      params,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as AppointmentStatsResponse;
  }

  async getDoctorStats(
    clinicId: number,
    from: string,
    to: string,
    limit = 20,
  ): Promise<DoctorStatsResponse> {
    const params = new HttpParams()
      .set('clinicId', clinicId)
      .set('from', from)
      .set('to', to)
      .set('limit', limit);
    const res = await this.api.request<ApiResponse<DoctorStatsResponse>>('GET', `${this.basePath}/doctors`, {
      params,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as DoctorStatsResponse;
  }

  async getCancellationStats(
    clinicId: number,
    from: string,
    to: string,
  ): Promise<CancellationStatsResponse> {
    const params = new HttpParams()
      .set('clinicId', clinicId)
      .set('from', from)
      .set('to', to);
    const res = await this.api.request<ApiResponse<CancellationStatsResponse>>('GET', `${this.basePath}/cancellations`, {
      params,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as CancellationStatsResponse;
  }
}

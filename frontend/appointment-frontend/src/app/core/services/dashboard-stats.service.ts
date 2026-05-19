import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  ApiResponse,
  AppointmentStatsResponse,
  CancellationStatsResponse,
  DoctorStatsResponse,
} from '../models';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class DashboardStatsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/admin/stats`;

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
    const res = await firstValueFrom(
      this.http.get<ApiResponse<AppointmentStatsResponse>>(
        `${this.baseUrl}/appointments`,
        { params },
      ),
    );
    return res.data!;
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
    const res = await firstValueFrom(
      this.http.get<ApiResponse<DoctorStatsResponse>>(
        `${this.baseUrl}/doctors`,
        { params },
      ),
    );
    return res.data!;
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
    const res = await firstValueFrom(
      this.http.get<ApiResponse<CancellationStatsResponse>>(
        `${this.baseUrl}/cancellations`,
        { params },
      ),
    );
    return res.data!;
  }
}

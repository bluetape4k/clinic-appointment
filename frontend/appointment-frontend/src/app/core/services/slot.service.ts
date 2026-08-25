import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { ApiResponse, AvailableSlot } from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class SlotService {
  private readonly api = inject(TenantApiClient);

  async getAvailableSlots(
    clinicId: number,
    doctorId: number,
    treatmentTypeId: number,
    date: string,
    requestedDurationMinutes?: number,
  ): Promise<AvailableSlot[]> {
    let params = new HttpParams()
      .set('doctorId', doctorId)
      .set('treatmentTypeId', treatmentTypeId)
      .set('date', date);

    if (requestedDurationMinutes != null) {
      params = params.set('requestedDurationMinutes', requestedDurationMinutes);
    }

    const res = await this.api.request<ApiResponse<AvailableSlot[]>>('GET', `/clinics/${clinicId}/slots`, {
      params,
      authScope: 'workforce-bearer',
    });
    return res.body?.data ?? [];
  }
}

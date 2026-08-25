import { Injectable, inject, signal } from '@angular/core';
import { ApiResponse, PagedData, TreatmentType } from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class TreatmentTypeService {
  private readonly api = inject(TenantApiClient);

  private readonly _treatmentTypes = signal<TreatmentType[]>([]);
  readonly treatmentTypes = this._treatmentTypes.asReadonly();

  readonly loading = signal(false);

  async loadByClinic(clinicId: number): Promise<TreatmentType[]> {
    this.loading.set(true);
    try {
      const res = await this.api.request<ApiResponse<PagedData<TreatmentType>>>('GET', `/clinics/${clinicId}/treatment-types`, {
        authScope: 'workforce-bearer',
      });
      const data = res.body?.data?.content ?? [];
      this._treatmentTypes.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(treatmentTypeId: number): Promise<TreatmentType> {
    const res = await this.api.request<ApiResponse<TreatmentType>>('GET', `/treatment-types/${treatmentTypeId}`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data as TreatmentType;
  }
}

import { Injectable, inject, signal } from '@angular/core';
import { ApiResponse, Equipment, PagedData } from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class EquipmentService {
  private readonly api = inject(TenantApiClient);

  private readonly _equipments = signal<Equipment[]>([]);
  readonly equipments = this._equipments.asReadonly();

  readonly loading = signal(false);

  async loadByClinic(clinicId: number): Promise<Equipment[]> {
    this.loading.set(true);
    try {
      const res = await this.api.request<ApiResponse<PagedData<Equipment>>>('GET', `/clinics/${clinicId}/equipments`, {
        authScope: 'workforce-bearer',
      });
      const data = res.body?.data?.content ?? [];
      this._equipments.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(equipmentId: number): Promise<Equipment> {
    const res = await this.api.request<ApiResponse<Equipment>>('GET', `/equipments/${equipmentId}`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data as Equipment;
  }
}

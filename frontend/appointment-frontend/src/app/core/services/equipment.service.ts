import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiResponse, Equipment, PagedData } from '../models';
import { environment } from '../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class EquipmentService {
  private readonly http = inject(HttpClient);

  private readonly _equipments = signal<Equipment[]>([]);
  readonly equipments = this._equipments.asReadonly();

  readonly loading = signal(false);

  async loadByClinic(clinicId: number): Promise<Equipment[]> {
    this.loading.set(true);
    try {
      const res = await firstValueFrom(
        this.http.get<ApiResponse<PagedData<Equipment>>>(
          `${environment.apiUrl}/clinics/${clinicId}/equipments`
        )
      );
      const data = res.data?.content ?? [];
      this._equipments.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(equipmentId: number): Promise<Equipment> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<Equipment>>(`${environment.apiUrl}/equipments/${equipmentId}`)
    );
    return res.data!;
  }
}

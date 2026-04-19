import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiResponse, TreatmentType } from '../models';

@Injectable({ providedIn: 'root' })
export class TreatmentTypeService {
  private readonly http = inject(HttpClient);

  private readonly _treatmentTypes = signal<TreatmentType[]>([]);
  readonly treatmentTypes = this._treatmentTypes.asReadonly();

  readonly loading = signal(false);

  async loadByClinic(clinicId: number): Promise<TreatmentType[]> {
    this.loading.set(true);
    try {
      const res = await firstValueFrom(
        this.http.get<ApiResponse<TreatmentType[]>>(
          `/api/clinics/${clinicId}/treatment-types`
        )
      );
      const data = res.data ?? [];
      this._treatmentTypes.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(treatmentTypeId: number): Promise<TreatmentType> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<TreatmentType>>(
        `/api/treatment-types/${treatmentTypeId}`
      )
    );
    return res.data!;
  }
}

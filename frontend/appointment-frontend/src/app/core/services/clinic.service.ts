import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiResponse, Clinic, ClinicBreakTime, OperatingHours } from '../models';

@Injectable({ providedIn: 'root' })
export class ClinicService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/clinics';

  private readonly _clinics = signal<Clinic[]>([]);
  readonly clinics = this._clinics.asReadonly();

  readonly loading = signal(false);

  async getAll(): Promise<Clinic[]> {
    this.loading.set(true);
    try {
      const res = await firstValueFrom(
        this.http.get<ApiResponse<Clinic[]>>(this.baseUrl)
      );
      const data = res.data ?? [];
      this._clinics.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(clinicId: number): Promise<Clinic> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<Clinic>>(`${this.baseUrl}/${clinicId}`)
    );
    return res.data!;
  }

  async getOperatingHours(clinicId: number): Promise<OperatingHours[]> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<OperatingHours[]>>(
        `${this.baseUrl}/${clinicId}/operating-hours`
      )
    );
    return res.data ?? [];
  }

  async getBreakTimes(clinicId: number): Promise<ClinicBreakTime[]> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<ClinicBreakTime[]>>(
        `${this.baseUrl}/${clinicId}/break-times`
      )
    );
    return res.data ?? [];
  }
}

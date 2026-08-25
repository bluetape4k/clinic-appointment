import { Injectable, inject, signal } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import { ApiResponse, Doctor, DoctorAbsence, DoctorSchedule, PagedData } from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private readonly api = inject(TenantApiClient);

  private readonly _doctors = signal<Doctor[]>([]);
  readonly doctors = this._doctors.asReadonly();

  readonly loading = signal(false);

  async loadByClinic(clinicId: number): Promise<Doctor[]> {
    this.loading.set(true);
    try {
      const res = await this.api.request<ApiResponse<PagedData<Doctor>>>('GET', `/clinics/${clinicId}/doctors`, {
        authScope: 'workforce-bearer',
      });
      const data = res.body?.data?.content ?? [];
      this._doctors.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(doctorId: number): Promise<Doctor> {
    const res = await this.api.request<ApiResponse<Doctor>>('GET', `/doctors/${doctorId}`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data as Doctor;
  }

  async getSchedules(doctorId: number): Promise<DoctorSchedule[]> {
    const res = await this.api.request<ApiResponse<DoctorSchedule[]>>('GET', `/doctors/${doctorId}/schedules`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data ?? [];
  }

  async getAbsences(doctorId: number, from: string, to: string): Promise<DoctorAbsence[]> {
    const params = new HttpParams()
      .set('from', from)
      .set('to', to);
    const res = await this.api.request<ApiResponse<DoctorAbsence[]>>('GET', `/doctors/${doctorId}/absences`, {
      params,
      authScope: 'workforce-bearer',
    });
    return res.body?.data ?? [];
  }
}

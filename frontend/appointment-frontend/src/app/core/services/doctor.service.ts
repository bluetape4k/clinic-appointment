import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiResponse, Doctor, DoctorAbsence, DoctorSchedule } from '../models';

@Injectable({ providedIn: 'root' })
export class DoctorService {
  private readonly http = inject(HttpClient);

  private readonly _doctors = signal<Doctor[]>([]);
  readonly doctors = this._doctors.asReadonly();

  readonly loading = signal(false);

  async loadByClinic(clinicId: number): Promise<Doctor[]> {
    this.loading.set(true);
    try {
      const res = await firstValueFrom(
        this.http.get<ApiResponse<Doctor[]>>(
          `/api/clinics/${clinicId}/doctors`
        )
      );
      const data = res.data ?? [];
      this._doctors.set(data);
      return data;
    } finally {
      this.loading.set(false);
    }
  }

  async getById(doctorId: number): Promise<Doctor> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<Doctor>>(`/api/doctors/${doctorId}`)
    );
    return res.data!;
  }

  async getSchedules(doctorId: number): Promise<DoctorSchedule[]> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<DoctorSchedule[]>>(
        `/api/doctors/${doctorId}/schedules`
      )
    );
    return res.data ?? [];
  }

  async getAbsences(doctorId: number, from: string, to: string): Promise<DoctorAbsence[]> {
    const params = new HttpParams()
      .set('from', from)
      .set('to', to);
    const res = await firstValueFrom(
      this.http.get<ApiResponse<DoctorAbsence[]>>(
        `/api/doctors/${doctorId}/absences`,
        { params }
      )
    );
    return res.data ?? [];
  }
}

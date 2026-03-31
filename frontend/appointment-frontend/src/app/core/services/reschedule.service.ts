import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { ApiResponse, RescheduleCandidate } from '../models';

@Injectable({ providedIn: 'root' })
export class RescheduleService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/appointments';

  /**
   * 진료실 휴진 일괄 재배정 후보 조회 (R1)
   */
  async getClosureCandidates(
    appointmentId: number,
    clinicId: number,
    closureDate: string,
    searchDays: number,
  ): Promise<Map<number, RescheduleCandidate[]>> {
    const params = new HttpParams()
      .set('clinicId', clinicId)
      .set('closureDate', closureDate)
      .set('searchDays', searchDays);
    const res = await firstValueFrom(
      this.http.post<ApiResponse<Record<number, RescheduleCandidate[]>>>(
        `${this.baseUrl}/${appointmentId}/reschedule/closure`,
        null,
        { params },
      )
    );
    return new Map(Object.entries(res.data ?? {}).map(([k, v]) => [Number(k), v]));
  }

  /**
   * 개별 예약 재배정 후보 목록 조회 (R2)
   */
  async getCandidates(appointmentId: number): Promise<RescheduleCandidate[]> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<RescheduleCandidate[]>>(
        `${this.baseUrl}/${appointmentId}/reschedule/candidates`,
      )
    );
    return res.data ?? [];
  }

  /**
   * 선택한 후보로 재배정 확정 (R3)
   */
  async confirm(appointmentId: number, candidateId: number): Promise<number> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<number>>(
        `${this.baseUrl}/${appointmentId}/reschedule/confirm/${candidateId}`,
        null,
      )
    );
    return res.data!;
  }

  /**
   * 최적 후보로 자동 재배정 (R4)
   */
  async autoReschedule(appointmentId: number): Promise<number | null> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<number | null>>(
        `${this.baseUrl}/${appointmentId}/reschedule/auto`,
        null,
      )
    );
    return res.data ?? null;
  }
}

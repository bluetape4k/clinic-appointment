import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  ApiResponse,
  CreateEquipmentUnavailabilityRequest,
  EquipmentUnavailabilityExceptionRecord,
  EquipmentUnavailabilityRecord,
  UnavailabilityConflictResponse,
  UnavailabilityExceptionRequest,
  UpdateEquipmentUnavailabilityRequest,
} from '../models';

@Injectable({ providedIn: 'root' })
export class EquipmentUnavailabilityService {
  private readonly http = inject(HttpClient);

  private baseUrl(clinicId: number, equipmentId: number): string {
    return `/api/clinics/${clinicId}/equipments/${equipmentId}/unavailabilities`;
  }

  /** 사용불가 스케줄 목록 조회 (E1) */
  async getList(
    clinicId: number,
    equipmentId: number,
    from: string,
    to: string,
  ): Promise<EquipmentUnavailabilityRecord[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    const res = await firstValueFrom(
      this.http.get<ApiResponse<EquipmentUnavailabilityRecord[]>>(
        this.baseUrl(clinicId, equipmentId),
        { params },
      )
    );
    return res.data ?? [];
  }

  /** 사용불가 스케줄 등록 (E2) */
  async create(
    clinicId: number,
    equipmentId: number,
    request: CreateEquipmentUnavailabilityRequest,
  ): Promise<EquipmentUnavailabilityRecord> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<EquipmentUnavailabilityRecord>>(
        this.baseUrl(clinicId, equipmentId),
        request,
      )
    );
    return res.data!;
  }

  /** 사용불가 스케줄 수정 (E3) */
  async update(
    clinicId: number,
    equipmentId: number,
    id: number,
    request: UpdateEquipmentUnavailabilityRequest,
  ): Promise<EquipmentUnavailabilityRecord> {
    const res = await firstValueFrom(
      this.http.put<ApiResponse<EquipmentUnavailabilityRecord>>(
        `${this.baseUrl(clinicId, equipmentId)}/${id}`,
        request,
      )
    );
    return res.data!;
  }

  /** 사용불가 스케줄 삭제 (E4) */
  async delete(clinicId: number, equipmentId: number, id: number): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(`${this.baseUrl(clinicId, equipmentId)}/${id}`)
    );
  }

  /** 예외 날짜 추가 (E5) */
  async addException(
    clinicId: number,
    equipmentId: number,
    id: number,
    request: UnavailabilityExceptionRequest,
  ): Promise<EquipmentUnavailabilityExceptionRecord> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<EquipmentUnavailabilityExceptionRecord>>(
        `${this.baseUrl(clinicId, equipmentId)}/${id}/exceptions`,
        request,
      )
    );
    return res.data!;
  }

  /** 예외 날짜 삭제 (E6) */
  async deleteException(
    clinicId: number,
    equipmentId: number,
    id: number,
    exId: number,
  ): Promise<void> {
    await firstValueFrom(
      this.http.delete<void>(
        `${this.baseUrl(clinicId, equipmentId)}/${id}/exceptions/${exId}`,
      )
    );
  }

  /** 충돌 예약 조회 — 등록된 스케줄 기준 (E7) */
  async detectConflicts(
    clinicId: number,
    equipmentId: number,
    id: number,
  ): Promise<UnavailabilityConflictResponse> {
    const res = await firstValueFrom(
      this.http.get<ApiResponse<UnavailabilityConflictResponse>>(
        `${this.baseUrl(clinicId, equipmentId)}/${id}/conflicts`,
      )
    );
    return res.data!;
  }

  /** 충돌 예약 미리보기 — 등록 전 (E8) */
  async previewConflicts(
    clinicId: number,
    equipmentId: number,
    request: CreateEquipmentUnavailabilityRequest,
  ): Promise<UnavailabilityConflictResponse> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<UnavailabilityConflictResponse>>(
        `${this.baseUrl(clinicId, equipmentId)}/preview-conflicts`,
        request,
      )
    );
    return res.data!;
  }
}

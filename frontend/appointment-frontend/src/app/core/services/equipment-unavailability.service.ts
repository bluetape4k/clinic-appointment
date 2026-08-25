import { Injectable, inject } from '@angular/core';
import { HttpParams } from '@angular/common/http';
import {
  ApiResponse,
  CreateEquipmentUnavailabilityRequest,
  EquipmentUnavailabilityExceptionRecord,
  EquipmentUnavailabilityRecord,
  UnavailabilityConflictResponse,
  UnavailabilityExceptionRequest,
  UpdateEquipmentUnavailabilityRequest,
} from '../models';
import { TenantApiClient } from '../api/tenant-api-client';

@Injectable({ providedIn: 'root' })
export class EquipmentUnavailabilityService {
  private readonly api = inject(TenantApiClient);

  private baseUrl(clinicId: number, equipmentId: number): string {
    return `/clinics/${clinicId}/equipments/${equipmentId}/unavailabilities`;
  }

  /** 사용불가 스케줄 목록 조회 (E1) */
  async getList(
    clinicId: number,
    equipmentId: number,
    from: string,
    to: string,
  ): Promise<EquipmentUnavailabilityRecord[]> {
    const params = new HttpParams().set('from', from).set('to', to);
    const res = await this.api.request<ApiResponse<EquipmentUnavailabilityRecord[]>>('GET', this.baseUrl(clinicId, equipmentId), {
      params,
      authScope: 'workforce-bearer',
    });
    return res.body?.data ?? [];
  }

  /** 사용불가 스케줄 등록 (E2) */
  async create(
    clinicId: number,
    equipmentId: number,
    request: CreateEquipmentUnavailabilityRequest,
  ): Promise<EquipmentUnavailabilityRecord> {
    const res = await this.api.request<ApiResponse<EquipmentUnavailabilityRecord>>('POST', this.baseUrl(clinicId, equipmentId), {
      body: request,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as EquipmentUnavailabilityRecord;
  }

  /** 사용불가 스케줄 수정 (E3) */
  async update(
    clinicId: number,
    equipmentId: number,
    id: number,
    request: UpdateEquipmentUnavailabilityRequest,
  ): Promise<EquipmentUnavailabilityRecord> {
    const res = await this.api.request<ApiResponse<EquipmentUnavailabilityRecord>>('PUT', `${this.baseUrl(clinicId, equipmentId)}/${id}`, {
      body: request,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as EquipmentUnavailabilityRecord;
  }

  /** 사용불가 스케줄 삭제 (E4) */
  async delete(clinicId: number, equipmentId: number, id: number): Promise<void> {
    await this.api.request<void>('DELETE', `${this.baseUrl(clinicId, equipmentId)}/${id}`, {
      authScope: 'workforce-bearer',
    });
  }

  /** 예외 날짜 추가 (E5) */
  async addException(
    clinicId: number,
    equipmentId: number,
    id: number,
    request: UnavailabilityExceptionRequest,
  ): Promise<EquipmentUnavailabilityExceptionRecord> {
    const res = await this.api.request<ApiResponse<EquipmentUnavailabilityExceptionRecord>>('POST', `${this.baseUrl(clinicId, equipmentId)}/${id}/exceptions`, {
      body: request,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as EquipmentUnavailabilityExceptionRecord;
  }

  /** 예외 날짜 삭제 (E6) */
  async deleteException(
    clinicId: number,
    equipmentId: number,
    id: number,
    exId: number,
  ): Promise<void> {
    await this.api.request<void>('DELETE', `${this.baseUrl(clinicId, equipmentId)}/${id}/exceptions/${exId}`, {
      authScope: 'workforce-bearer',
    });
  }

  /** 충돌 예약 조회 — 등록된 스케줄 기준 (E7) */
  async detectConflicts(
    clinicId: number,
    equipmentId: number,
    id: number,
  ): Promise<UnavailabilityConflictResponse> {
    const res = await this.api.request<ApiResponse<UnavailabilityConflictResponse>>('GET', `${this.baseUrl(clinicId, equipmentId)}/${id}/conflicts`, {
      authScope: 'workforce-bearer',
    });
    return res.body?.data as UnavailabilityConflictResponse;
  }

  /** 충돌 예약 미리보기 — 등록 전 (E8) */
  async previewConflicts(
    clinicId: number,
    equipmentId: number,
    request: CreateEquipmentUnavailabilityRequest,
  ): Promise<UnavailabilityConflictResponse> {
    const res = await this.api.request<ApiResponse<UnavailabilityConflictResponse>>('POST', `${this.baseUrl(clinicId, equipmentId)}/preview-conflicts`, {
      body: request,
      authScope: 'workforce-bearer',
    });
    return res.body?.data as UnavailabilityConflictResponse;
  }
}

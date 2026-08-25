import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { TenantContextService } from '../api/tenant-context.service';
import { DashboardStatsService } from './dashboard-stats.service';
import { EquipmentUnavailabilityService } from './equipment-unavailability.service';

describe('management tenant services', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(TenantContextService).setTenant('tenant-a');
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('equipment unavailability CRUD와 예외 API가 tenant path를 공유한다', async () => {
    const service = TestBed.inject(EquipmentUnavailabilityService);
    const record = { id: 7, equipmentId: 2, unavailableFrom: '2026-08-20T09:00:00Z', unavailableTo: '2026-08-20T10:00:00Z' };
    const request = {
      unavailableDate: '2026-08-20',
      isRecurring: false,
      recurringDayOfWeek: null,
      effectiveFrom: '2026-08-20',
      effectiveUntil: null,
      startTime: '09:00:00',
      endTime: '10:00:00',
      reason: '정기 점검',
    };
    const exceptionRequest = {
      originalDate: '2026-08-25',
      exceptionType: 'SKIP' as const,
      rescheduledDate: null,
      rescheduledStartTime: null,
      rescheduledEndTime: null,
      reason: null,
    };

    const listPromise = service.getList(1, 2, '2026-08-20', '2026-08-21');
    const list = httpMock.expectOne(request =>
      request.url === '/api/tenant-a/clinics/1/equipments/2/unavailabilities'
      && request.params.get('from') === '2026-08-20'
      && request.params.get('to') === '2026-08-21',
    );
    expect(list.request.method).toBe('GET');
    list.flush({ success: true, data: [record] });
    await expect(listPromise).resolves.toEqual([record]);

    const createPromise = service.create(1, 2, request);
    const create = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities');
    expect(create.request.method).toBe('POST');
    create.flush({ success: true, data: record });
    await expect(createPromise).resolves.toEqual(record);

    const updatePromise = service.update(1, 2, 7, request);
    const update = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities/7');
    expect(update.request.method).toBe('PUT');
    update.flush({ success: true, data: record });
    await expect(updatePromise).resolves.toEqual(record);

    const exceptionPromise = service.addException(1, 2, 7, exceptionRequest);
    const exception = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities/7/exceptions');
    expect(exception.request.method).toBe('POST');
    exception.flush({ success: true, data: { id: 3, exceptionDate: '2026-08-25' } });
    await expect(exceptionPromise).resolves.toEqual({ id: 3, exceptionDate: '2026-08-25' });

    const conflictPromise = service.detectConflicts(1, 2, 7);
    const conflict = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities/7/conflicts');
    expect(conflict.request.method).toBe('GET');
    conflict.flush({ success: true, data: { conflicts: [] } });
    await expect(conflictPromise).resolves.toEqual({ conflicts: [] });
  });

  it('equipment unavailability 삭제와 preview도 workforce tenant 경로를 사용한다', async () => {
    const service = TestBed.inject(EquipmentUnavailabilityService);
    const request = {
      unavailableDate: null,
      isRecurring: false,
      recurringDayOfWeek: null,
      effectiveFrom: '2026-08-20',
      effectiveUntil: null,
      startTime: '09:00:00',
      endTime: '10:00:00',
      reason: null,
    };

    const deletePromise = service.delete(1, 2, 7);
    const deleteRequest = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities/7');
    expect(deleteRequest.request.method).toBe('DELETE');
    deleteRequest.flush(null);
    await expect(deletePromise).resolves.toBeUndefined();

    const exceptionPromise = service.deleteException(1, 2, 7, 3);
    const exceptionRequest = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities/7/exceptions/3');
    expect(exceptionRequest.request.method).toBe('DELETE');
    exceptionRequest.flush(null);
    await expect(exceptionPromise).resolves.toBeUndefined();

    const previewPromise = service.previewConflicts(1, 2, request);
    const preview = httpMock.expectOne('/api/tenant-a/clinics/1/equipments/2/unavailabilities/preview-conflicts');
    expect(preview.request.method).toBe('POST');
    preview.flush({ success: true, data: { conflicts: [] } });
    await expect(previewPromise).resolves.toEqual({ conflicts: [] });
  });

  it('dashboard 통계 세 endpoint가 같은 tenant path와 query를 사용한다', async () => {
    const service = TestBed.inject(DashboardStatsService);

    const appointmentsPromise = service.getAppointmentStats(1, '2026-08-01', '2026-08-31', ['CONFIRMED', 'CANCELLED']);
    const appointments = httpMock.expectOne(request =>
      request.url === '/api/tenant-a/admin/stats/appointments'
      && request.params.getAll('statuses')?.join(',') === 'CONFIRMED,CANCELLED',
    );
    expect(appointments.request.method).toBe('GET');
    appointments.flush({ success: true, data: { total: 10 } });
    await expect(appointmentsPromise).resolves.toEqual({ total: 10 });

    const doctorsPromise = service.getDoctorStats(1, '2026-08-01', '2026-08-31', 5);
    const doctors = httpMock.expectOne(request =>
      request.url === '/api/tenant-a/admin/stats/doctors' && request.params.get('limit') === '5',
    );
    expect(doctors.request.params.get('limit')).toBe('5');
    doctors.flush({ success: true, data: { total: 2 } });
    await expect(doctorsPromise).resolves.toEqual({ total: 2 });

    const cancellationsPromise = service.getCancellationStats(1, '2026-08-01', '2026-08-31');
    const cancellations = httpMock.expectOne(request => request.url === '/api/tenant-a/admin/stats/cancellations');
    expect(cancellations.request.method).toBe('GET');
    cancellations.flush({ success: true, data: { total: 1 } });
    await expect(cancellationsPromise).resolves.toEqual({ total: 1 });
  });
});

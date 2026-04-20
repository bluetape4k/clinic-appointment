import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RescheduleService } from './reschedule.service';

describe('RescheduleService', () => {
  let service: RescheduleService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(RescheduleService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('서비스가 생성된다', () => {
    expect(service).toBeTruthy();
  });

  describe('getClosureCandidates()', () => {
    it('휴진 일괄 재배정 후보를 Map으로 반환한다', async () => {
      const mockData = {
        10: [{ id: 1, appointmentId: 10, doctorId: 2, appointmentDate: '2026-04-25', startTime: '09:00', endTime: '09:30' }],
        11: [{ id: 2, appointmentId: 11, doctorId: 2, appointmentDate: '2026-04-25', startTime: '10:00', endTime: '10:30' }],
      };

      const promise = service.getClosureCandidates(10, 1, '2026-04-22', 7);

      const req = httpTesting.expectOne(
        r => r.url === '/api/appointments/10/reschedule/closure'
          && r.params.get('clinicId') === '1'
          && r.params.get('closureDate') === '2026-04-22'
          && r.params.get('searchDays') === '7'
      );
      expect(req.request.method).toBe('POST');
      req.flush({ success: true, data: mockData });

      const result = await promise;
      expect(result).toBeInstanceOf(Map);
      expect(result.get(10)).toHaveLength(1);
      expect(result.get(11)).toHaveLength(1);
    });

    it('빈 응답 시 빈 Map을 반환한다', async () => {
      const promise = service.getClosureCandidates(99, 1, '2026-04-22', 7);

      const req = httpTesting.expectOne(r => r.url === '/api/appointments/99/reschedule/closure');
      req.flush({ success: true, data: null });

      const result = await promise;
      expect(result.size).toBe(0);
    });
  });

  describe('getCandidates()', () => {
    it('개별 예약 재배정 후보 목록을 반환한다', async () => {
      const mockCandidates = [
        { id: 1, appointmentId: 10, doctorId: 2, appointmentDate: '2026-04-25', startTime: '09:00', endTime: '09:30' },
        { id: 2, appointmentId: 10, doctorId: 3, appointmentDate: '2026-04-26', startTime: '14:00', endTime: '14:30' },
      ];

      const promise = service.getCandidates(10);

      const req = httpTesting.expectOne('/api/appointments/10/reschedule/candidates');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockCandidates });

      const result = await promise;
      expect(result).toEqual(mockCandidates);
    });

    it('빈 응답 시 빈 배열을 반환한다', async () => {
      const promise = service.getCandidates(999);

      const req = httpTesting.expectOne('/api/appointments/999/reschedule/candidates');
      req.flush({ success: true, data: null });

      const result = await promise;
      expect(result).toEqual([]);
    });
  });

  describe('confirm()', () => {
    it('선택한 후보로 재배정을 확정하고 새 appointmentId를 반환한다', async () => {
      const promise = service.confirm(10, 1);

      const req = httpTesting.expectOne('/api/appointments/10/reschedule/confirm/1');
      expect(req.request.method).toBe('POST');
      req.flush({ success: true, data: 42 });

      const result = await promise;
      expect(result).toBe(42);
    });
  });

  describe('autoReschedule()', () => {
    it('최적 후보로 자동 재배정하고 새 appointmentId를 반환한다', async () => {
      const promise = service.autoReschedule(10);

      const req = httpTesting.expectOne('/api/appointments/10/reschedule/auto');
      expect(req.request.method).toBe('POST');
      req.flush({ success: true, data: 55 });

      const result = await promise;
      expect(result).toBe(55);
    });

    it('자동 배정 불가 시 null을 반환한다', async () => {
      const promise = service.autoReschedule(10);

      const req = httpTesting.expectOne('/api/appointments/10/reschedule/auto');
      req.flush({ success: true, data: null });

      const result = await promise;
      expect(result).toBeNull();
    });
  });
});

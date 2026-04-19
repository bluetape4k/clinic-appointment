import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { DoctorService } from './doctor.service';

describe('DoctorService', () => {
  let service: DoctorService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DoctorService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  it('서비스가 생성된다', () => {
    expect(service).toBeTruthy();
  });

  it('초기 doctors signal은 빈 배열이다', () => {
    expect(service.doctors()).toEqual([]);
  });

  describe('loadByClinic()', () => {
    it('clinicId=1 로드 시 HTTP 호출하고 doctors signal에 설정된다', async () => {
      const mockDoctors = [
        { id: 1, clinicId: 1, name: '김민준', specialty: '일반의', providerType: 'DOCTOR' },
        { id: 2, clinicId: 1, name: '이서연', specialty: '치과', providerType: 'DOCTOR' },
      ];

      const promise = service.loadByClinic(1);

      const req = httpTesting.expectOne('/api/clinics/1/doctors');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockDoctors });

      await promise;
      expect(service.doctors()).toEqual(mockDoctors);
    });

    it('빈 응답 시 빈 배열이 설정된다', async () => {
      const promise = service.loadByClinic(999);

      const req = httpTesting.expectOne('/api/clinics/999/doctors');
      req.flush({ success: true, data: [] });

      await promise;
      expect(service.doctors()).toEqual([]);
    });
  });

  describe('getById()', () => {
    it('특정 의사 정보를 반환한다', async () => {
      const mockDoctor = { id: 1, clinicId: 1, name: '김민준', specialty: '일반의', providerType: 'DOCTOR' };

      const promise = service.getById(1);

      const req = httpTesting.expectOne('/api/doctors/1');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockDoctor });

      const result = await promise;
      expect(result).toEqual(mockDoctor);
    });
  });

  describe('getSchedules()', () => {
    it('의사 스케줄 목록을 반환한다', async () => {
      const mockSchedules = [
        { id: 1, doctorId: 1, dayOfWeek: 'MONDAY', startTime: '09:00', endTime: '18:00' },
      ];

      const promise = service.getSchedules(1);

      const req = httpTesting.expectOne('/api/doctors/1/schedules');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockSchedules });

      const result = await promise;
      expect(result).toEqual(mockSchedules);
    });
  });

  describe('getAbsences()', () => {
    it('의사 휴무 정보를 반환한다', async () => {
      const mockAbsences = [
        { id: 1, doctorId: 1, absenceDate: '2026-04-20', reason: '개인 사유' },
      ];

      const promise = service.getAbsences(1, '2026-04-01', '2026-04-30');

      const req = httpTesting.expectOne(
        r => r.url === '/api/doctors/1/absences' && r.params.get('from') === '2026-04-01'
      );
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockAbsences });

      const result = await promise;
      expect(result).toEqual(mockAbsences);
    });
  });
});

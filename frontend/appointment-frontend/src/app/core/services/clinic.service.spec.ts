import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { ClinicService } from './clinic.service';

describe('ClinicService', () => {
  let service: ClinicService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ClinicService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('서비스가 생성된다', () => {
    expect(service).toBeTruthy();
  });

  it('초기 clinics signal은 빈 배열이다', () => {
    expect(service.clinics()).toEqual([]);
  });

  it('초기 loading signal은 false이다', () => {
    expect(service.loading()).toBe(false);
  });

  describe('getAll()', () => {
    it('전체 클리닉 목록을 가져와 clinics signal에 설정한다', async () => {
      const mockClinics = [
        { id: 1, name: '서울 클리닉', phone: '02-1234-5678', address: '서울', slotDurationMinutes: 30 },
        { id: 2, name: '부산 클리닉', phone: '051-9876-5432', address: '부산', slotDurationMinutes: 20 },
      ];

      const promise = service.getAll();

      const req = httpTesting.expectOne('/api/clinics');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockClinics });

      const result = await promise;
      expect(result).toEqual(mockClinics);
      expect(service.clinics()).toEqual(mockClinics);
    });

    it('빈 응답 시 빈 배열이 설정된다', async () => {
      const promise = service.getAll();

      const req = httpTesting.expectOne('/api/clinics');
      req.flush({ success: true, data: null });

      const result = await promise;
      expect(result).toEqual([]);
      expect(service.clinics()).toEqual([]);
    });

    it('loading signal이 요청 중 true, 완료 후 false로 변경된다', async () => {
      const promise = service.getAll();
      expect(service.loading()).toBe(true);

      const req = httpTesting.expectOne('/api/clinics');
      req.flush({ success: true, data: [] });

      await promise;
      expect(service.loading()).toBe(false);
    });
  });

  describe('getById()', () => {
    it('특정 클리닉 정보를 반환한다', async () => {
      const mockClinic = { id: 1, name: '서울 클리닉', phone: '02-1234-5678', address: '서울', slotDurationMinutes: 30 };

      const promise = service.getById(1);

      const req = httpTesting.expectOne('/api/clinics/1');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockClinic });

      const result = await promise;
      expect(result).toEqual(mockClinic);
    });
  });

  describe('getOperatingHours()', () => {
    it('클리닉 영업시간 목록을 반환한다', async () => {
      const mockHours = [
        { id: 1, clinicId: 1, dayOfWeek: 'MONDAY', openTime: '09:00', closeTime: '18:00' },
      ];

      const promise = service.getOperatingHours(1);

      const req = httpTesting.expectOne('/api/clinics/1/operating-hours');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockHours });

      const result = await promise;
      expect(result).toEqual(mockHours);
    });

    it('빈 응답 시 빈 배열을 반환한다', async () => {
      const promise = service.getOperatingHours(1);

      const req = httpTesting.expectOne('/api/clinics/1/operating-hours');
      req.flush({ success: true, data: null });

      const result = await promise;
      expect(result).toEqual([]);
    });
  });

  describe('getBreakTimes()', () => {
    it('클리닉 휴식시간 목록을 반환한다', async () => {
      const mockBreaks = [
        { id: 1, clinicId: 1, startTime: '12:00', endTime: '13:00' },
      ];

      const promise = service.getBreakTimes(1);

      const req = httpTesting.expectOne('/api/clinics/1/break-times');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockBreaks });

      const result = await promise;
      expect(result).toEqual(mockBreaks);
    });
  });
});

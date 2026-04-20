import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { EquipmentService } from './equipment.service';

describe('EquipmentService', () => {
  let service: EquipmentService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(EquipmentService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  it('서비스가 생성된다', () => {
    expect(service).toBeTruthy();
  });

  it('초기 equipments signal은 빈 배열이다', () => {
    expect(service.equipments()).toEqual([]);
  });

  it('초기 loading signal은 false이다', () => {
    expect(service.loading()).toBe(false);
  });

  describe('loadByClinic()', () => {
    it('clinicId로 장비 목록을 로드하고 equipments signal에 설정한다', async () => {
      const mockEquipments = [
        { id: 1, clinicId: 1, name: 'X-ray', equipmentType: 'IMAGING' },
        { id: 2, clinicId: 1, name: 'MRI', equipmentType: 'IMAGING' },
      ];

      const promise = service.loadByClinic(1);

      const req = httpTesting.expectOne('/api/clinics/1/equipments');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockEquipments });

      const result = await promise;
      expect(result).toEqual(mockEquipments);
      expect(service.equipments()).toEqual(mockEquipments);
    });

    it('빈 응답 시 빈 배열이 설정된다', async () => {
      const promise = service.loadByClinic(99);

      const req = httpTesting.expectOne('/api/clinics/99/equipments');
      req.flush({ success: true, data: null });

      const result = await promise;
      expect(result).toEqual([]);
      expect(service.equipments()).toEqual([]);
    });

    it('loading signal이 요청 중 true, 완료 후 false로 변경된다', async () => {
      const promise = service.loadByClinic(1);
      expect(service.loading()).toBe(true);

      const req = httpTesting.expectOne('/api/clinics/1/equipments');
      req.flush({ success: true, data: [] });

      await promise;
      expect(service.loading()).toBe(false);
    });
  });

  describe('getById()', () => {
    it('특정 장비 정보를 반환한다', async () => {
      const mockEquipment = { id: 1, clinicId: 1, name: 'X-ray', equipmentType: 'IMAGING' };

      const promise = service.getById(1);

      const req = httpTesting.expectOne('/api/equipments/1');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockEquipment });

      const result = await promise;
      expect(result).toEqual(mockEquipment);
    });
  });
});

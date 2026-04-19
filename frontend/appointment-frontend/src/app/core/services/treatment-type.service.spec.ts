import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { TreatmentTypeService } from './treatment-type.service';

describe('TreatmentTypeService', () => {
  let service: TreatmentTypeService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(TreatmentTypeService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  it('서비스가 생성된다', () => {
    expect(service).toBeTruthy();
  });

  it('초기 treatmentTypes signal은 빈 배열이다', () => {
    expect(service.treatmentTypes()).toEqual([]);
  });

  describe('loadByClinic()', () => {
    it('clinicId=1 로드 시 HTTP 호출하고 signal에 설정된다', async () => {
      const mockTypes = [
        {
          id: 1, clinicId: 1, name: '기본 진료', category: 'TREATMENT',
          defaultDurationMinutes: 30, requiredProviderType: 'DOCTOR', requiresEquipment: false,
        },
        {
          id: 2, clinicId: 1, name: '스케일링', category: 'TREATMENT',
          defaultDurationMinutes: 60, requiredProviderType: 'DOCTOR', requiresEquipment: true,
        },
      ];

      const promise = service.loadByClinic(1);

      const req = httpTesting.expectOne('/api/clinics/1/treatment-types');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockTypes });

      await promise;
      expect(service.treatmentTypes()).toEqual(mockTypes);
    });

    it('빈 응답 시 빈 배열이 설정된다', async () => {
      const promise = service.loadByClinic(999);

      const req = httpTesting.expectOne('/api/clinics/999/treatment-types');
      req.flush({ success: true, data: [] });

      await promise;
      expect(service.treatmentTypes()).toEqual([]);
    });
  });

  describe('getById()', () => {
    it('특정 진료 유형 정보를 반환한다', async () => {
      const mockType = {
        id: 1, clinicId: 1, name: '기본 진료', category: 'TREATMENT',
        defaultDurationMinutes: 30, requiredProviderType: 'DOCTOR', requiresEquipment: false,
      };

      const promise = service.getById(1);

      const req = httpTesting.expectOne('/api/treatment-types/1');
      expect(req.request.method).toBe('GET');
      req.flush({ success: true, data: mockType });

      const result = await promise;
      expect(result).toEqual(mockType);
    });
  });
});

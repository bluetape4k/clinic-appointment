import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { firstValueFrom, toArray } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { RescheduleService } from './reschedule.service';
import { AuthService } from './auth.service';

const mockAuthService = { getToken: () => 'test-token', removeToken: vi.fn() };

describe('RescheduleService', () => {
  let service: RescheduleService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: mockAuthService },
      ],
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

  describe('streamBatchReschedule()', () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    it('progress + complete 이벤트를 순서대로 방출한다', async () => {
      const ssePayload =
        'event: progress\ndata: {"appointmentId":1,"candidateCount":3,"totalProcessed":1,"done":false}\n\n' +
        'event: complete\ndata: {"appointmentId":-1,"candidateCount":0,"totalProcessed":1,"done":true}\n\n';

      const stream = new ReadableStream({
        start(ctrl) {
          ctrl.enqueue(new TextEncoder().encode(ssePayload));
          ctrl.close();
        },
      });
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, body: stream } as Response);

      const params = { clinicId: 1, closureDate: '2026-05-19', searchDays: 7 };
      const events = await firstValueFrom(service.streamBatchReschedule(params).pipe(toArray()));

      expect(events).toHaveLength(2);
      expect(events[0]).toEqual({ appointmentId: 1, candidateCount: 3, totalProcessed: 1, done: false });
      expect(events[1]).toEqual({ appointmentId: -1, candidateCount: 0, totalProcessed: 1, done: true });
    });

    it('HTTP 오류 응답 시 에러를 방출한다', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false, status: 403 } as Response);

      const params = { clinicId: 1, closureDate: '2026-05-19', searchDays: 7 };
      await expect(firstValueFrom(service.streamBatchReschedule(params))).rejects.toThrow('SSE failed: 403');
    });

    it('401 응답에서 현재 세션을 제거한다', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false, status: 401 } as Response);

      const params = { clinicId: 1, closureDate: '2026-05-19', searchDays: 7 };
      await expect(firstValueFrom(service.streamBatchReschedule(params))).rejects.toThrow('SSE: 인증이 필요합니다.');

      expect(mockAuthService.removeToken).toHaveBeenCalledOnce();
    });

    it('구독 취소 시 fetch를 abort한다', () => {
      const abortFn = vi.fn();
      vi.spyOn(globalThis, 'AbortController').mockImplementation(
        class {
          abort = abortFn;
          signal = {} as AbortSignal;
        } as unknown as new () => AbortController,
      );
      vi.spyOn(globalThis, 'fetch').mockReturnValue(new Promise(() => {}));

      const params = { clinicId: 1, closureDate: '2026-05-19', searchDays: 7 };
      const sub = service.streamBatchReschedule(params).subscribe();
      sub.unsubscribe();

      expect(abortFn).toHaveBeenCalledOnce();
    });
  });
});

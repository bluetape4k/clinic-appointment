import { describe, expect, it } from 'vitest';
import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';

import { mapPortalApiError } from './portal-api-error';
import type { CreateAppointmentRequest, CommitmentStatus } from './portal-api.models';

describe('환자 포털 API 계약', () => {
  it('CreateAppointmentRequest는 서버 DTO의 필수 wire key만 사용한다', () => {
    const request: CreateAppointmentRequest = {
      appointmentPlanId: 17,
      preferredStartAt: '2026-08-20T01:30:00Z',
      preferredEndAt: '2026-08-20T02:00:00Z',
      evidence: {
        evidenceAuthority: 'consent:clinic-a',
        evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7',
      },
    };

    expect(Object.keys(request).sort()).toEqual([
      'appointmentPlanId',
      'evidence',
      'preferredEndAt',
      'preferredStartAt',
    ]);
    expect(Object.keys(request.evidence).sort()).toEqual(['evidenceAuthority', 'evidenceId']);
  });

  it('commitment 상태 enum은 서버의 proposal lifecycle과 동일하다', () => {
    const statuses: CommitmentStatus[] = ['PROPOSED', 'HELD', 'CONFIRMED', 'EXPIRED', 'CANCELLED'];

    expect(statuses).toEqual(['PROPOSED', 'HELD', 'CONFIRMED', 'EXPIRED', 'CANCELLED']);
  });

  it('문서화된 precondition·재시도 HTTP 상태를 UI error kind로 보존한다', () => {
    const expectedKinds: Record<number, ReturnType<typeof mapPortalApiError>['kind']> = {
      401: 'unauthorized',
      403: 'forbidden',
      409: 'conflict',
      410: 'expired',
      412: 'conflict',
      422: 'precondition',
      428: 'precondition',
      429: 'retryable',
      503: 'retryable',
    };

    for (const [status, kind] of Object.entries(expectedKinds)) {
      const error = mapPortalApiError(new HttpErrorResponse({
        status: Number(status),
        statusText: 'contract test',
        error: { errorCode: `HTTP_${status}`, error: '계약 테스트 오류', correlationId: 'contract-1' },
        headers: new HttpHeaders({ 'Retry-After': '7' }),
      }));

      expect(error.kind).toBe(kind);
      expect(error.status).toBe(Number(status));
      expect(error.correlationId).toBe('contract-1');
      expect(error.retryAfterSeconds).toBe(7);
    }
  });
});

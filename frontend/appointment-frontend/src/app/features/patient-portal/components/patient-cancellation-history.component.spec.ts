import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach, vi } from 'vitest';

import { PortalApiClient } from '../../../core/api/portal-api-client';
import { PortalApiException } from '../../../core/api/portal-api-error';
import { TenantContextService } from '../../../core/api/tenant-context.service';
import { PatientCancellationHistoryComponent } from './patient-cancellation-history.component';

const etag = '"sha256:' + 'a'.repeat(64) + '"';
const generation = 'v1.history-generation';

function entry(appointmentRef: string) {
  return {
    appointmentRef,
    productName: null,
    sessionNumber: 3,
    totalSessions: 10,
    visitStartAt: '2026-08-20T01:30:00Z',
    visitEndAt: '2026-08-20T02:00:00Z',
    fromStatus: null,
    fromStatusLabel: null,
    toStatus: 'CANCELLED',
    toStatusLabel: '취소',
    reasonCode: 'CUSTOMER_REQUEST',
    reasonLabel: '고객 요청',
    reasonDetail: null,
    actorRole: 'PATIENT',
    actorLabel: '환자',
    occurredAt: '2026-08-20T00:30:00Z',
  };
}

describe('환자 취소 이력 timeline', () => {
  let client: { getCancellationHistory: ReturnType<typeof vi.fn>; clearCancellationHistoryCache: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    client = {
      getCancellationHistory: vi.fn().mockResolvedValue({
        kind: 'body',
        body: { limit: 20, entries: [entry('ref-1')], nextCursor: 'cursor-2' },
        etag,
        tenantIdentityGeneration: generation,
      }),
      clearCancellationHistoryCache: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [PatientCancellationHistoryComponent],
      providers: [{ provide: PortalApiClient, useValue: client }],
    });
    TestBed.inject(TenantContextService).setTenant('clinic-a');
  });

  it('환자용 label과 null fallback을 렌더링하고 raw enum을 노출하지 않는다', async () => {
    const fixture = TestBed.createComponent(PatientCancellationHistoryComponent);
    fixture.detectChanges();
    await fixture.componentInstance.retryInitial();
    fixture.detectChanges();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('상품 정보 없음');
    expect(fixture.nativeElement.textContent).toContain('3회차 / 10회');
    expect(fixture.nativeElement.textContent).toContain('이전 상태 확인 불가');
    expect(fixture.nativeElement.textContent).not.toContain('CUSTOMER_REQUEST');
    expect(fixture.nativeElement.querySelector('ol[aria-busy="false"]')).not.toBeNull();
  });

  it('load-more 실패 시 기존 목록과 cursor를 보존한다', async () => {
    const fixture = TestBed.createComponent(PatientCancellationHistoryComponent);
    fixture.detectChanges();
    await fixture.componentInstance.retryInitial();
    fixture.detectChanges();
    client.getCancellationHistory.mockRejectedValueOnce(new Error('temporary'));

    await expect(fixture.componentInstance.loadMore()).rejects.toThrow('temporary');
    fixture.detectChanges();
    expect(fixture.componentInstance.entries()).toHaveLength(1);
    expect(fixture.nativeElement.textContent).toContain('같은 위치 다시 시도');
  });

  it('load-more stale cursor는 첫 페이지를 한 번만 복구한다', async () => {
    const fixture = TestBed.createComponent(PatientCancellationHistoryComponent);
    fixture.detectChanges();
    await fixture.componentInstance.retryInitial();
    fixture.detectChanges();
    client.getCancellationHistory
      .mockRejectedValueOnce(new PortalApiException({
        kind: 'conflict', status: 409, code: 'PATIENT_HISTORY_SNAPSHOT_CONFLICT',
        message: 'stale cursor', retryAfterSeconds: null, correlationId: null,
      }))
      .mockResolvedValueOnce({
        kind: 'body',
        body: { limit: 20, entries: [entry('ref-2')], nextCursor: null },
        etag,
        tenantIdentityGeneration: generation,
      });

    await expect(fixture.componentInstance.loadMore()).resolves.toMatchObject({ kind: 'accepted' });
    expect(client.getCancellationHistory).toHaveBeenCalledTimes(4);
    expect(client.getCancellationHistory).toHaveBeenLastCalledWith({ cursor: null, limit: 20 });
    expect(fixture.componentInstance.entries().map(item => item.appointmentRef)).toEqual(['ref-2']);
    expect(client.clearCancellationHistoryCache).toHaveBeenCalled();
  });
});

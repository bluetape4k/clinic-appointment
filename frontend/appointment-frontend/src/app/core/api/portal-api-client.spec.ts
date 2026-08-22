import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpHeaders } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { PortalApiClient } from './portal-api-client';
import { PortalApiException } from './portal-api-error';
import { TenantContextService } from './tenant-context.service';
import { PatientAuthService } from '../services/patient-auth.service';

const evidence = {
  evidenceAuthority: 'consent:clinic-a',
  evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7',
};

describe('PortalApiClient', () => {
  let client: PortalApiClient;
  let httpMock: HttpTestingController;
  let tenant: TenantContextService;
  let auth: PatientAuthService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    client = TestBed.inject(PortalApiClient);
    httpMock = TestBed.inject(HttpTestingController);
    tenant = TestBed.inject(TenantContextService);
    auth = TestBed.inject(PatientAuthService);
    tenant.setTenant('clinic-a');
  });

  afterEach(() => httpMock.verify());

  it('tenant-scoped client가 생성된다', () => {
    expect(client).toBeTruthy();
  });

  it('tenant scope가 없으면 네트워크 요청 전에 거절한다', async () => {
    tenant.clear();

    await expect(client.getCommitment(42)).rejects.toMatchObject({
      state: { kind: 'tenant-missing' },
    });
  });

  it('최초 예약 요청에 tenant path와 create precondition header를 넣는다', async () => {
    const promise = client.requestAppointment(
      {
        appointmentPlanId: 7,
        preferredStartAt: '2026-08-20T01:30:00Z',
        preferredEndAt: '2026-08-20T02:00:00Z',
        evidence,
      },
      'request_01J1M6Y6XRK8N0W2M3P4Q5R6S7',
    );

    const request = httpMock.expectOne('/api/clinic-a/appointment-requests');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('request_01J1M6Y6XRK8N0W2M3P4Q5R6S7');
    expect(request.request.headers.get('If-None-Match')).toBe('*');
    expect(request.request.body.evidence).toEqual(evidence);
    request.flush(
      {
        appointmentId: 42,
        commitmentId: 9,
        proposalId: 11,
        status: 'PROPOSED',
        version: 1,
        expiresAt: '2026-08-20T02:30:00Z',
        policySnapshot: { snapshotId: 1, snapshotHash: 'hash', tenantGeneration: 1, clinicGeneration: 1, sourceVersions: {} },
      },
      { headers: new HttpHeaders({ ETag: '"1"' }), status: 202, statusText: 'Accepted' },
    );

    await expect(promise).resolves.toMatchObject({ etag: '"1"', body: { appointmentId: 42 } });
  });

  it('commitment read의 ETag를 response envelope로 반환한다', async () => {
    const promise = client.getCommitment(42);
    const request = httpMock.expectOne('/api/clinic-a/appointments/42/commitment');
    expect(request.request.method).toBe('GET');
    request.flush(
      {
        appointmentId: 42,
        commitmentId: 9,
        status: 'PROPOSED',
        version: 3,
        currentProposal: {
          proposalId: 11,
          revision: 1,
          startsAt: '2026-08-20T01:30:00Z',
          endsAt: '2026-08-20T02:00:00Z',
          expiresAt: '2026-08-20T02:30:00Z',
          expired: false,
          representativeTreatmentName: '피부 재생 관리',
          policySnapshot: { snapshotId: 1, snapshotHash: 'hash', tenantGeneration: 1, clinicGeneration: 1, sourceVersions: {} },
        },
        confirmedProposalId: null,
        effectivePolicySnapshotId: null,
      },
      { headers: new HttpHeaders({ ETag: '"3"' }) },
    );

    await expect(promise).resolves.toMatchObject({ etag: '"3"', body: { version: 3 } });
  });

  it('proposal 결정에 Idempotency-Key와 If-Match를 함께 넣는다', async () => {
    const promise = client.acceptProposal(42, 11, { evidence }, 'accept_01J1M6Y6XRK8N0W2M3P4Q5R6S7', '"3"');
    const request = httpMock.expectOne('/api/clinic-a/appointments/42/proposals/11/accept');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('accept_01J1M6Y6XRK8N0W2M3P4Q5R6S7');
    expect(request.request.headers.get('If-Match')).toBe('"3"');
    request.flush({ appointmentId: 42, commitmentId: 9, status: 'CONFIRMED', version: 4, currentProposal: {}, confirmedProposalId: 11, effectivePolicySnapshotId: 1 });

    await expect(promise).resolves.toMatchObject({ body: { status: 'CONFIRMED' } });
  });

  it('취소에 code-only body와 최신 ETag를 함께 넣는다', async () => {
    const promise = client.cancelAppointment(42, { reasonCode: 'CUSTOMER_REQUEST' }, 'cancel_01J1M6Y6XRK8N0W2M3P4Q5R6S7', '"4"');
    const request = httpMock.expectOne('/api/clinic-a/appointments/42/cancel');
    expect(request.request.method).toBe('POST');
    expect(request.request.headers.get('Idempotency-Key')).toBe('cancel_01J1M6Y6XRK8N0W2M3P4Q5R6S7');
    expect(request.request.headers.get('If-Match')).toBe('"4"');
    expect(request.request.body).toEqual({ reasonCode: 'CUSTOMER_REQUEST' });
    request.flush({ appointmentId: 42, commitmentId: 9, status: 'CANCELLED', version: 5, currentProposal: {}, confirmedProposalId: 11, effectivePolicySnapshotId: 1 }, { headers: new HttpHeaders({ ETag: '"5"' }) });

    await expect(promise).resolves.toMatchObject({ etag: '"5"', body: { status: 'CANCELLED' } });
  });

  it('슬롯 조회도 같은 tenant scope와 typed query를 사용한다', async () => {
    const promise = client.getSlots(3, 4, 5, '2026-08-20', 30);
    const request = httpMock.expectOne((candidate) =>
      candidate.url === '/api/clinic-a/clinics/3/slots' &&
      candidate.params.get('doctorId') === '4' &&
      candidate.params.get('treatmentTypeId') === '5' &&
      candidate.params.get('date') === '2026-08-20' &&
      candidate.params.get('requestedDurationMinutes') === '30',
    );
    expect(request.request.method).toBe('GET');
    request.flush({ success: true, data: [{ date: '2026-08-20', startTime: '10:00:00', endTime: '10:30:00', doctorId: 4, equipmentIds: [], remainingCapacity: 1 }] });

    await expect(promise).resolves.toMatchObject({ body: [{ doctorId: 4 }] });
  });

  it('병원 metadata도 같은 tenant scope에서 timezone을 읽는다', async () => {
    const promise = client.getClinic(3);
    const request = httpMock.expectOne('/api/clinic-a/clinics/3');
    expect(request.request.method).toBe('GET');
    request.flush({ success: true, data: { id: 3, name: '서울 메인 클리닉', timezone: 'Asia/Seoul' } });

    await expect(promise).resolves.toMatchObject({ body: { id: 3, timezone: 'Asia/Seoul' } });
  });

  it('알림 snapshot도 tenant-scoped envelope에서 typed 목록으로 변환한다', async () => {
    const promise = client.getNotifications();
    const request = httpMock.expectOne('/api/clinic-a/notifications');
    expect(request.request.method).toBe('GET');
    request.flush({ success: true, data: [{ eventId: 'event-1', appointmentId: 42, sequence: 1, status: 'PROPOSED', title: '예약 제안', message: '확인하세요.', createdAt: '2026-08-20T01:30:00Z', read: false }] });

    await expect(promise).resolves.toMatchObject({ body: [{ eventId: 'event-1', appointmentId: 42 }] });
  });

  it('412 오류와 Retry-After를 재동기화 가능한 error state로 매핑한다', async () => {
    const promise = client.getCommitment(42);
    const request = httpMock.expectOne('/api/clinic-a/appointments/42/commitment');
    request.flush(
      { errorCode: 'ETAG_MISMATCH', error: '최신 예약 상태를 확인하세요', correlationId: 'corr-1' },
      { status: 412, statusText: 'Precondition Failed', headers: new HttpHeaders({ 'Retry-After': '5' }) },
    );

    await expect(promise).rejects.toSatisfy((error: PortalApiException) =>
      error.state.kind === 'conflict' &&
      error.state.status === 412 &&
      error.state.retryAfterSeconds === 5 &&
      error.state.correlationId === 'corr-1',
    );
  });

  it('503 오류의 Retry-After를 retryable 상태로 보존한다', async () => {
    const promise = client.getCommitment(42);
    const request = httpMock.expectOne('/api/clinic-a/appointments/42/commitment');
    request.flush(
      { errorCode: 'INTAKE_UNAVAILABLE', error: '잠시 후 다시 시도하세요', correlationId: 'corr-2' },
      { status: 503, statusText: 'Service Unavailable', headers: new HttpHeaders({ 'Retry-After': '10' }) },
    );

    await expect(promise).rejects.toSatisfy((error: PortalApiException) =>
      error.state.kind === 'retryable' && error.state.retryAfterSeconds === 10,
    );
  });

  it('취소 이력 최초 조회는 cursor 없이 요청하고 tenant generation을 private cache에 결속한다', async () => {
    const promise = client.getCancellationHistory({ cursor: null, limit: 20 });
    const request = httpMock.expectOne(candidate =>
      candidate.url === '/api/clinic-a/patient/appointments/cancellation-history' &&
      candidate.params.get('limit') === '20' &&
      !candidate.params.has('cursor') &&
      !candidate.headers.has('If-None-Match'),
    );
    request.flush({ limit: 20, entries: [], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + 'a'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-a' }),
    });

    await expect(promise).resolves.toMatchObject({ kind: 'body', tenantIdentityGeneration: 'v1.generation-a' });
  });

  it('같은 page의 304는 동일 generation private body만 재사용한다', async () => {
    const etag = '"sha256:' + 'b'.repeat(64) + '"';
    const first = client.getCancellationHistory({ cursor: null, limit: 20 });
    const firstRequest = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');
    firstRequest.flush({ limit: 20, entries: [], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: etag, 'X-Tenant-Identity-Generation': 'v1.generation-b' }),
    });
    await first;

    const second = client.getCancellationHistory({ cursor: null, limit: 20 });
    const secondRequest = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');
    expect(secondRequest.request.headers.get('If-None-Match')).toBe(etag);
    expect(secondRequest.request.headers.get('X-Tenant-Identity-Generation')).toBe('v1.generation-b');
    secondRequest.flush(null, {
      status: 304,
      statusText: 'Not Modified',
      headers: new HttpHeaders({ ETag: etag, 'X-Tenant-Identity-Generation': 'v1.generation-b' }),
    });

    await expect(second).resolves.toMatchObject({ kind: 'not-modified', body: { entries: [] } });
  });

  it('tenant generation 변경은 기존 body를 반환하지 않고 첫 페이지를 한 번만 무조건 재조회한다', async () => {
    const first = client.getCancellationHistory({ cursor: null, limit: 20 });
    const firstRequest = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');
    firstRequest.flush({ limit: 20, entries: [{ appointmentRef: 'old' }], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + '1'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-a' }),
    });
    await first;

    const second = client.getCancellationHistory({ cursor: null, limit: 20 });
    const generationChanged = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');
    expect(generationChanged.request.headers.get('X-Tenant-Identity-Generation')).toBe('v1.generation-a');
    generationChanged.flush({ limit: 20, entries: [{ appointmentRef: 'new' }], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + '2'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-b' }),
    });

    await new Promise(resolve => setTimeout(resolve, 0));
    const bootstrap = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');
    expect(bootstrap.request.headers.has('If-None-Match')).toBe(false);
    expect(bootstrap.request.headers.has('X-Tenant-Identity-Generation')).toBe(false);
    bootstrap.flush({ limit: 20, entries: [{ appointmentRef: 'fresh' }], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + '3'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-b' }),
    });

    await expect(second).resolves.toMatchObject({ body: { entries: [{ appointmentRef: 'fresh' }] } });
  });

  it('tenant 전환은 기존 history cache를 비우고 generation bootstrap을 다시 수행한다', async () => {
    const first = client.getCancellationHistory({ cursor: null, limit: 20 });
    const firstRequest = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');
    firstRequest.flush({ limit: 20, entries: [], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + 'c'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-c' }),
    });
    await first;

    tenant.setTenant('clinic-b');
    const second = client.getCancellationHistory({ cursor: null, limit: 20 });
    const secondRequest = httpMock.expectOne('/api/clinic-b/patient/appointments/cancellation-history?limit=20');
    expect(secondRequest.request.headers.has('If-None-Match')).toBe(false);
    expect(secondRequest.request.headers.has('X-Tenant-Identity-Generation')).toBe(false);
    secondRequest.flush({ limit: 20, entries: [], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + 'd'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-d' }),
    });

    await expect(second).resolves.toMatchObject({ tenantIdentityGeneration: 'v1.generation-d' });
  });

  it('session reset 뒤 지연된 history 응답은 private cache를 다시 채우지 않는다', async () => {
    const promise = client.getCancellationHistory({ cursor: null, limit: 20 });
    const request = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');

    auth.beginSessionChange();
    client.clearCancellationHistoryCache();
    request.flush({ limit: 20, entries: [{ appointmentRef: 'old' }], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + 'e'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-e' }),
    });

    await expect(promise).rejects.toThrow('현재 session');
  });

  it('tenant 전환 뒤 지연된 이전 tenant history 응답은 새 tenant cache에 저장하지 않는다', async () => {
    const promise = client.getCancellationHistory({ cursor: null, limit: 20 });
    const request = httpMock.expectOne('/api/clinic-a/patient/appointments/cancellation-history?limit=20');

    tenant.setTenant('clinic-b');
    client.clearCancellationHistoryCache();
    request.flush({ limit: 20, entries: [{ appointmentRef: 'old-tenant' }], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + 'f'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-f' }),
    });

    await expect(promise).rejects.toThrow('현재 session');
    const next = client.getCancellationHistory({ cursor: null, limit: 20 });
    const nextRequest = httpMock.expectOne('/api/clinic-b/patient/appointments/cancellation-history?limit=20');
    expect(nextRequest.request.headers.has('If-None-Match')).toBe(false);
    expect(nextRequest.request.headers.has('X-Tenant-Identity-Generation')).toBe(false);
    nextRequest.flush({ limit: 20, entries: [], nextCursor: null }, {
      status: 200,
      statusText: 'OK',
      headers: new HttpHeaders({ ETag: '"sha256:' + '0'.repeat(64) + '"', 'X-Tenant-Identity-Generation': 'v1.generation-b' }),
    });
    await expect(next).resolves.toMatchObject({ body: { entries: [] } });
  });
});

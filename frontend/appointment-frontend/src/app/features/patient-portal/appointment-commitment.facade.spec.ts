import { describe, expect, it, beforeEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';

import { AppointmentCommitmentFacade } from './appointment-commitment.facade';
import { PortalApiClient } from '../../core/api/portal-api-client';
import { PortalApiException } from '../../core/api/portal-api-error';
import { AppointmentCommitmentResponse, AppointmentProposalResponse, PortalResponse } from '../../core/api/portal-api.models';

const proposalResponse: AppointmentProposalResponse = {
  appointmentId: 42,
  commitmentId: 9,
  proposalId: 11,
  status: 'PROPOSED',
  version: 1,
  expiresAt: '2026-08-20T02:30:00Z',
  policySnapshot: { snapshotId: 1, snapshotHash: 'hash', tenantGeneration: 1, clinicGeneration: 1, sourceVersions: {} },
};

const commitmentResponse: AppointmentCommitmentResponse = {
  appointmentId: 42,
  commitmentId: 9,
  status: 'CONFIRMED',
  version: 4,
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
  confirmedProposalId: 11,
  effectivePolicySnapshotId: 1,
};

describe('AppointmentCommitmentFacade', () => {
  let facade: AppointmentCommitmentFacade;
  let client: {
    requestAppointment: ReturnType<typeof vi.fn>;
    getCommitment: ReturnType<typeof vi.fn>;
    acceptProposal: ReturnType<typeof vi.fn>;
    declineProposal: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    client = {
      requestAppointment: vi.fn(),
      getCommitment: vi.fn(),
      acceptProposal: vi.fn(),
      declineProposal: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: PortalApiClient, useValue: client }] });
    facade = TestBed.inject(AppointmentCommitmentFacade);
  });

  it('예약 요청 결과를 proposal 상태와 ETag로 저장한다', async () => {
    client.requestAppointment.mockResolvedValue({ body: proposalResponse, etag: '"1"', retryAfterSeconds: null } satisfies PortalResponse<AppointmentProposalResponse>);

    await facade.requestAppointment({
      appointmentPlanId: 7,
      preferredStartAt: '2026-08-20T01:30:00Z',
      preferredEndAt: '2026-08-20T02:00:00Z',
      evidence: { evidenceAuthority: 'consent:clinic-a', evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7' },
    }, 'request-key');

    expect(client.requestAppointment).toHaveBeenCalledTimes(1);
    expect(facade.state()).toMatchObject({ view: 'proposal', appointmentId: 42, proposalId: 11, etag: '"1"' });
  });

  it('같은 intent의 빠른 연속 결정은 두 번째 HTTP 호출을 만들지 않는다', async () => {
    let resolve!: (value: PortalResponse<AppointmentCommitmentResponse>) => void;
    client.acceptProposal.mockReturnValue(new Promise<PortalResponse<AppointmentCommitmentResponse>>(done => { resolve = done; }));
    facade.seedCommitment(42, 11, '"3"');

    const first = facade.acceptProposal({ evidence: { evidenceAuthority: 'consent:clinic-a', evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7' } }, 'accept-key');
    const second = facade.acceptProposal({ evidence: { evidenceAuthority: 'consent:clinic-a', evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7' } }, 'accept-key');
    expect(client.acceptProposal).toHaveBeenCalledTimes(1);

    resolve({ body: commitmentResponse, etag: '"4"', retryAfterSeconds: null });
    await Promise.all([first, second]);
    expect(facade.state()).toMatchObject({ view: 'confirmed', etag: '"4"' });
  });

  it('ETag 충돌은 최신 commitment를 다시 읽고 조용히 덮어쓰지 않는다', async () => {
    client.acceptProposal.mockRejectedValue(new PortalApiException({ kind: 'conflict', status: 412, code: 'ETAG_MISMATCH', message: '최신 상태를 확인하세요', retryAfterSeconds: null, correlationId: 'corr-1' }));
    client.getCommitment.mockResolvedValue({ body: commitmentResponse, etag: '"4"', retryAfterSeconds: null });
    facade.seedCommitment(42, 11, '"3"');

    await expect(facade.acceptProposal({ evidence: { evidenceAuthority: 'consent:clinic-a', evidenceId: '01J1M6Y6XRK8N0W2M3P4Q5R6S7' } }, 'accept-key')).rejects.toThrow();

    expect(client.getCommitment).toHaveBeenCalledWith(42);
    expect(facade.state()).toMatchObject({ view: 'confirmed', etag: '"4"', notice: '최신 예약 상태를 불러왔습니다.' });
  });

  it('proposal 만료는 expired view와 사용자 안내로 매핑한다', async () => {
    client.declineProposal.mockRejectedValue(new PortalApiException({ kind: 'expired', status: 410, code: 'PROPOSAL_EXPIRED', message: '제안이 만료되었습니다.', retryAfterSeconds: null, correlationId: null }));
    facade.seedCommitment(42, 11, '"3"');

    await expect(facade.declineProposal({ reasonCode: 'CUSTOMER_REQUEST' }, 'decline-key')).rejects.toThrow();

    expect(facade.state()).toMatchObject({ view: 'expired', notice: '제안이 만료되었습니다.' });
  });
});

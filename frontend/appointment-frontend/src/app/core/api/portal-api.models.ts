export type CommitmentStatus = 'PROPOSED' | 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export const CANCELLATION_REASON_CODES = [
  'CUSTOMER_REQUEST',
  'REFUND',
  'EQUIPMENT_FAILURE',
  'CLINIC_REQUEST',
] as const;

export type CancellationReasonCode = typeof CANCELLATION_REASON_CODES[number];

export interface ConsentEvidence {
  evidenceAuthority: string;
  evidenceId: string;
}

export interface CreateAppointmentRequest {
  appointmentPlanId: number;
  preferredStartAt: string;
  preferredEndAt: string;
  evidence: ConsentEvidence;
}

export interface AppointmentPolicySourceVersionSummary {
  tenantVersion: number;
  clinicVersion: number | null;
}

export interface AppointmentPolicySnapshot {
  snapshotId: number;
  snapshotHash: string;
  tenantGeneration: number;
  clinicGeneration: number;
  sourceVersions: Record<string, AppointmentPolicySourceVersionSummary>;
}

export interface AppointmentProposalResponse {
  appointmentId: number;
  commitmentId: number;
  proposalId: number;
  status: CommitmentStatus;
  version: number;
  expiresAt: string;
  policySnapshot: AppointmentPolicySnapshot;
  productName?: string | null;
  sessionNumber?: number | null;
  totalSessions?: number | null;
  clinicDisplayName?: string | null;
}

export interface AppointmentProposalSummary {
  proposalId: number;
  revision: number;
  startsAt: string;
  endsAt: string;
  expiresAt: string;
  expired: boolean;
  representativeTreatmentName: string;
  policySnapshot: AppointmentPolicySnapshot;
  productName?: string | null;
  sessionNumber?: number | null;
  totalSessions?: number | null;
  clinicDisplayName?: string | null;
}

export interface AppointmentCommitmentResponse {
  appointmentId: number;
  commitmentId: number;
  status: CommitmentStatus;
  version: number;
  currentProposal: AppointmentProposalSummary;
  confirmedProposalId: number | null;
  effectivePolicySnapshotId: number | null;
}

export interface ProposalDecisionRequest {
  evidence: ConsentEvidence;
}

export interface DeclineProposalRequest {
  reasonCode: string;
}

/** 환자 포털 취소 요청은 서버의 등록 code만 전송하며 운영자 detail은 노출하지 않는다. */
export interface CancelAppointmentRequest {
  reasonCode: CancellationReasonCode;
}

export interface AvailableSlot {
  date: string;
  startTime: string;
  endTime: string;
  doctorId: number;
  equipmentIds: number[];
  remainingCapacity: number;
}

export interface ApiEnvelope<T> {
  success?: boolean;
  data: T | null;
}

export interface PortalResponse<T> {
  body: T;
  etag: string | null;
  retryAfterSeconds: number | null;
}

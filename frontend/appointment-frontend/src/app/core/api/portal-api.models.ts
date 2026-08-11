export type CommitmentStatus = 'PROPOSED' | 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

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

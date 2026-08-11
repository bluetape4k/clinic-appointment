import { Injectable, inject, signal } from '@angular/core';

import {
  AppointmentCommitmentResponse,
  AppointmentProposalResponse,
  CommitmentStatus,
  CreateAppointmentRequest,
  DeclineProposalRequest,
  PortalResponse,
  ProposalDecisionRequest,
} from '../../core/api/portal-api.models';
import { PortalApiClient } from '../../core/api/portal-api-client';
import { PortalApiErrorState, PortalApiException } from '../../core/api/portal-api-error';

export type AppointmentCommitmentView = 'idle' | 'loading' | 'submitting' | 'proposal' | 'held' | 'confirmed' | 'expired' | 'error';

export interface AppointmentCommitmentState {
  view: AppointmentCommitmentView;
  appointmentId: number | null;
  proposalId: number | null;
  status: CommitmentStatus | null;
  commitment: AppointmentCommitmentResponse | null;
  proposal: AppointmentProposalResponse | null;
  etag: string | null;
  busy: boolean;
  notice: string | null;
  error: PortalApiErrorState | null;
}

const initialState: AppointmentCommitmentState = {
  view: 'idle',
  appointmentId: null,
  proposalId: null,
  status: null,
  commitment: null,
  proposal: null,
  etag: null,
  busy: false,
  notice: null,
  error: null,
};

@Injectable({ providedIn: 'root' })
export class AppointmentCommitmentFacade {
  private readonly client = inject(PortalApiClient);
  private readonly _state = signal<AppointmentCommitmentState>(initialState);

  readonly state = this._state.asReadonly();

  async requestAppointment(body: CreateAppointmentRequest, idempotencyKey: string): Promise<void> {
    if (this._state().busy) return;
    this.requireKey(idempotencyKey);
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.requestAppointment(body, idempotencyKey);
      this.applyProposal(response);
    } catch (error) {
      this.applyError(error);
      throw error;
    } finally {
      this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async loadCommitment(appointmentId: number): Promise<void> {
    if (this._state().busy) return;
    this._state.update(state => ({ ...state, view: 'loading', busy: true, error: null }));
    try {
      const response = await this.client.getCommitment(appointmentId);
      this.applyCommitment(response.body, response.etag, '최신 예약 상태를 불러왔습니다.');
    } catch (error) {
      this.applyError(error);
      throw error;
    } finally {
      this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async acceptProposal(body: ProposalDecisionRequest, idempotencyKey: string): Promise<void> {
    const current = this.requireDecisionContext(idempotencyKey);
    if (this._state().busy) return;
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.acceptProposal(current.appointmentId, current.proposalId, body, idempotencyKey, current.etag);
      this.applyCommitment(response.body, response.etag, '예약 제안을 확정했습니다.');
    } catch (error) {
      if (this.isEtagConflict(error)) {
        await this.refreshAfterConflict(current.appointmentId);
      } else {
        this.applyError(error);
      }
      throw error;
    } finally {
      this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async declineProposal(body: DeclineProposalRequest, idempotencyKey: string): Promise<void> {
    const current = this.requireDecisionContext(idempotencyKey);
    if (this._state().busy) return;
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.declineProposal(current.appointmentId, current.proposalId, body, idempotencyKey, current.etag);
      this.applyCommitment(response.body, response.etag, '예약 제안을 거절했습니다.');
    } catch (error) {
      if (this.isEtagConflict(error)) {
        await this.refreshAfterConflict(current.appointmentId);
      } else {
        this.applyError(error);
      }
      throw error;
    } finally {
      this._state.update(state => ({ ...state, busy: false }));
    }
  }

  seedCommitment(appointmentId: number, proposalId: number, etag: string): void {
    this._state.set({
      ...initialState,
      view: 'proposal',
      appointmentId,
      proposalId,
      status: 'PROPOSED',
      etag,
    });
  }

  private applyProposal(response: PortalResponse<AppointmentProposalResponse>): void {
    const proposal = response.body;
    this._state.update(state => ({
      ...state,
      view: this.viewForStatus(proposal.status),
      appointmentId: proposal.appointmentId,
      proposalId: proposal.proposalId,
      status: proposal.status,
      proposal,
      commitment: null,
      etag: response.etag,
      notice: '예약 요청이 접수되었습니다. 제안 내용을 확인하세요.',
      error: null,
    }));
  }

  private applyCommitment(response: AppointmentCommitmentResponse, etag: string | null, notice: string | null): void {
    this._state.update(state => ({
      ...state,
      view: this.viewForStatus(response.status),
      appointmentId: response.appointmentId,
      proposalId: response.currentProposal.proposalId,
      status: response.status,
      commitment: response,
      proposal: null,
      etag,
      notice,
      error: null,
    }));
  }

  private async refreshAfterConflict(appointmentId: number): Promise<void> {
    const response = await this.client.getCommitment(appointmentId);
    this.applyCommitment(response.body, response.etag, '최신 예약 상태를 불러왔습니다.');
  }

  private applyError(error: unknown): void {
    if (!(error instanceof PortalApiException)) {
      this._state.update(state => ({ ...state, view: 'error', notice: '예약 요청을 처리하지 못했습니다.', error: null }));
      return;
    }
    const view = error.state.kind === 'expired' ? 'expired' : 'error';
    this._state.update(state => ({ ...state, view, notice: error.state.message, error: error.state }));
  }

  private requireDecisionContext(idempotencyKey: string): { appointmentId: number; proposalId: number; etag: string } {
    this.requireKey(idempotencyKey);
    const state = this._state();
    if (!state.appointmentId || !state.proposalId || !state.etag) {
      throw new Error('최신 예약 제안과 ETag가 필요합니다.');
    }
    return { appointmentId: state.appointmentId, proposalId: state.proposalId, etag: state.etag };
  }

  private requireKey(key: string): void {
    if (!key.trim()) throw new Error('Idempotency-Key가 필요합니다.');
  }

  private isEtagConflict(error: unknown): boolean {
    return error instanceof PortalApiException && error.state.status === 412;
  }

  private viewForStatus(status: CommitmentStatus): AppointmentCommitmentView {
    if (status === 'HELD') return 'held';
    if (status === 'CONFIRMED') return 'confirmed';
    if (status === 'EXPIRED') return 'expired';
    return 'proposal';
  }
}

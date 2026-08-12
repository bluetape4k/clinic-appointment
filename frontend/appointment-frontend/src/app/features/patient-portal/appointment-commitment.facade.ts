import { effect, Injectable, inject, signal } from '@angular/core';

import {
  AppointmentCommitmentResponse,
  AppointmentProposalResponse,
  CancelAppointmentRequest,
  CommitmentStatus,
  CreateAppointmentRequest,
  DeclineProposalRequest,
  PortalResponse,
  ProposalDecisionRequest,
} from '../../core/api/portal-api.models';
import { PortalApiClient } from '../../core/api/portal-api-client';
import { PortalApiErrorState, PortalApiException } from '../../core/api/portal-api-error';
import { PatientAuthService } from '../../core/services/patient-auth.service';

const APPOINTMENT_REFERENCE_PREFIX = 'appointment_portal_last_id:';

export type AppointmentCommitmentView = 'idle' | 'loading' | 'submitting' | 'proposal' | 'held' | 'confirmed' | 'cancelled' | 'expired' | 'error';

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
  private readonly auth = inject(PatientAuthService);
  private readonly _state = signal<AppointmentCommitmentState>(initialState);
  private readonly conflictRefreshes = new Map<number, Promise<void>>();
  private lastSessionReference: unknown;
  private hasObservedSession = false;

  readonly state = this._state.asReadonly();

  constructor() {
    effect(() => {
      const session = this.auth.session();
      if (this.hasObservedSession && this.lastSessionReference !== session) {
        this.resetForSessionChange();
      }
      this.lastSessionReference = session;
      this.hasObservedSession = true;
    });
  }

  /** 인증 주체가 바뀌거나 로그아웃할 때 이전 환자 예약을 즉시 폐기합니다. */
  resetForSessionChange(): void {
    this.sessionGeneration += 1;
    this.conflictRefreshes.clear();
    this._state.set(initialState);
    try {
      const storage = globalThis.sessionStorage;
      if (!storage) return;
      for (let index = storage.length - 1; index >= 0; index -= 1) {
        const key = storage.key(index);
        if (key?.startsWith(APPOINTMENT_REFERENCE_PREFIX)) storage.removeItem(key);
      }
    } catch {
      // storage cleanup 실패와 무관하게 메모리의 server-owned 상태는 폐기합니다.
    }
  }

  async requestAppointment(body: CreateAppointmentRequest, idempotencyKey: string): Promise<boolean> {
    if (this._state().busy) return false;
    this.requireKey(idempotencyKey);
    const generation = this.sessionGeneration;
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.requestAppointment(body, idempotencyKey);
      if (!this.isCurrentGeneration(generation)) return false;
      this.applyProposal(response);
      return true;
    } catch (error) {
      if (this.isCurrentGeneration(generation)) this.applyError(error);
      throw error;
    } finally {
      if (this.isCurrentGeneration(generation)) this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async loadCommitment(appointmentId: number): Promise<void> {
    if (this._state().busy) return;
    const generation = this.sessionGeneration;
    this._state.update(state => ({ ...state, view: 'loading', busy: true, error: null }));
    try {
      const response = await this.client.getCommitment(appointmentId);
      if (!this.isCurrentGeneration(generation)) return;
      this.applyCommitment(response.body, response.etag, '최신 예약 상태를 불러왔습니다.');
    } catch (error) {
      if (this.isCurrentGeneration(generation)) this.applyError(error);
      throw error;
    } finally {
      if (this.isCurrentGeneration(generation)) this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async acceptProposal(body: ProposalDecisionRequest, idempotencyKey: string): Promise<boolean> {
    const current = this.requireDecisionContext(idempotencyKey);
    if (this._state().busy) return false;
    const generation = this.sessionGeneration;
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.acceptProposal(current.appointmentId, current.proposalId, body, idempotencyKey, current.etag);
      if (!this.isCurrentGeneration(generation)) return false;
      this.applyCommitment(response.body, response.etag, '예약 제안을 확정했습니다.');
      return true;
    } catch (error) {
      if (this.isCurrentGeneration(generation)) {
        if (this.isEtagConflict(error)) {
          await this.refreshAfterConflict(current.appointmentId, generation);
        } else {
          this.applyError(error);
        }
      }
      throw error;
    } finally {
      if (this.isCurrentGeneration(generation)) this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async declineProposal(body: DeclineProposalRequest, idempotencyKey: string): Promise<boolean> {
    const current = this.requireDecisionContext(idempotencyKey);
    if (this._state().busy) return false;
    const generation = this.sessionGeneration;
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.declineProposal(current.appointmentId, current.proposalId, body, idempotencyKey, current.etag);
      if (!this.isCurrentGeneration(generation)) return false;
      this.applyCommitment(response.body, response.etag, '예약 제안을 거절했습니다.');
      return true;
    } catch (error) {
      if (this.isCurrentGeneration(generation)) {
        if (this.isEtagConflict(error)) {
          await this.refreshAfterConflict(current.appointmentId, generation);
        } else {
          this.applyError(error);
        }
      }
      throw error;
    } finally {
      if (this.isCurrentGeneration(generation)) this._state.update(state => ({ ...state, busy: false }));
    }
  }

  async cancelAppointment(body: CancelAppointmentRequest, idempotencyKey: string): Promise<boolean> {
    const current = this.requireMutationContext(idempotencyKey);
    if (this._state().busy) return false;
    const generation = this.sessionGeneration;
    this._state.update(state => ({ ...state, view: 'submitting', busy: true, notice: null, error: null }));
    try {
      const response = await this.client.cancelAppointment(current.appointmentId, body, idempotencyKey, current.etag);
      if (!this.isCurrentGeneration(generation)) return false;
      this.applyCommitment(response.body, response.etag, '예약을 취소했습니다.');
      return true;
    } catch (error) {
      if (this.isCurrentGeneration(generation)) {
        if (this.isEtagConflict(error)) {
          await this.refreshAfterConflict(current.appointmentId, generation);
        } else {
          this.applyError(error);
        }
      }
      throw error;
    } finally {
      if (this.isCurrentGeneration(generation)) this._state.update(state => ({ ...state, busy: false }));
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

  private async refreshAfterConflict(appointmentId: number, generation: number): Promise<void> {
    const existing = this.conflictRefreshes.get(appointmentId);
    if (existing) return existing;

    const refresh = this.client.getCommitment(appointmentId)
      .then(response => {
        if (!this.isCurrentGeneration(generation)) return;
        const current = this._state();
        if (current.appointmentId !== appointmentId) return;
        if (current.commitment && response.body.version < current.commitment.version) return;
        this.applyCommitment(response.body, response.etag, '최신 예약 상태를 불러왔습니다.');
      })
      .catch(error => {
        if (this.isCurrentGeneration(generation)) this.applyError(error);
        throw error;
      })
      .finally(() => {
        if (this.conflictRefreshes.get(appointmentId) === refresh) this.conflictRefreshes.delete(appointmentId);
      });
    this.conflictRefreshes.set(appointmentId, refresh);
    return refresh;
  }

  private sessionGeneration = 0;

  private isCurrentGeneration(generation: number): boolean {
    return generation === this.sessionGeneration;
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

  private requireMutationContext(idempotencyKey: string): { appointmentId: number; etag: string } {
    this.requireKey(idempotencyKey);
    const state = this._state();
    if (!state.appointmentId || !state.etag) {
      throw new Error('최신 예약 상태와 ETag가 필요합니다.');
    }
    return { appointmentId: state.appointmentId, etag: state.etag };
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
    if (status === 'CANCELLED') return 'cancelled';
    if (status === 'EXPIRED') return 'expired';
    return 'proposal';
  }
}

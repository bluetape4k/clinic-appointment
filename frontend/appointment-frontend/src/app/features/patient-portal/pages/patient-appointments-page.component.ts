import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AppointmentCardComponent, AppointmentCardModel } from '../components/appointment-card.component';
import { AppointmentCommitmentFacade } from '../appointment-commitment.facade';

interface AppointmentDraft {
  appointmentPlanId: number | null;
  preferredStartAt: string;
  preferredEndAt: string;
  evidenceAuthority: string;
  evidenceId: string;
  productName: string;
  sessionNumber: number | null;
  totalSessions: number | null;
}

@Component({
  selector: 'app-patient-appointments-page',
  standalone: true,
  imports: [FormsModule, AppointmentCardComponent],
  template: `
    <section class="portal-page" aria-labelledby="appointments-title">
      <p class="portal-eyebrow">APPOINTMENTS</p>
      <h2 id="appointments-title">예약 현황</h2>
      <p class="portal-page-intro">요청 중인 예약과 확정된 방문 일정을 확인하세요.</p>

      @if (appointment; as currentAppointment) {
        <app-appointment-card [appointment]="currentAppointment" />
      } @else {
        <form class="request-form" (ngSubmit)="requestAppointment()" aria-labelledby="request-title">
          <h3 id="request-title">새 예약 요청</h3>
          <p class="form-help">희망 시간과 동의 증빙을 입력하면 병원에 제안 생성을 요청합니다.</p>
          <label>예약 계획 ID <input name="appointmentPlanId" type="number" min="1" required [(ngModel)]="draft.appointmentPlanId" /></label>
          <div class="form-grid">
            <label>희망 시작 <input name="preferredStartAt" type="datetime-local" required [(ngModel)]="draft.preferredStartAt" /></label>
            <label>희망 종료 <input name="preferredEndAt" type="datetime-local" required [(ngModel)]="draft.preferredEndAt" /></label>
          </div>
          <label>동의 권위 <input name="evidenceAuthority" required [(ngModel)]="draft.evidenceAuthority" placeholder="consent:clinic-a" /></label>
          <label>동의 증빙 ID <input name="evidenceId" required minlength="20" [(ngModel)]="draft.evidenceId" /></label>
          <button type="submit" [disabled]="facade.state().busy">예약 요청 보내기</button>
        </form>
      }

      @if (facade.state().status === 'PROPOSED') {
        <div class="proposal-actions" aria-labelledby="proposal-title">
          <h3 id="proposal-title">제안 확인</h3>
          <p>{{ proposalLabel }}</p>
          <div class="action-row">
            <button type="button" (click)="acceptProposal()" [disabled]="facade.state().busy">제안 동의</button>
            <button type="button" class="secondary-button" (click)="declineProposal()" [disabled]="facade.state().busy">제안 거절</button>
          </div>
        </div>
      }

      @if (facade.state().notice; as notice) {
        <p class="portal-notice" role="status" aria-live="polite">{{ notice }}</p>
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .portal-page { max-width: 760px; }
    .portal-eyebrow { margin: 0 0 8px; color: var(--portal-muted); font-size: .75rem; font-weight: 700; letter-spacing: .12em; }
    h2 { margin: 0; font-size: clamp(1.35rem, 3vw, 1.75rem); letter-spacing: -.03em; }
    .portal-page-intro { margin: 8px 0 0; color: var(--portal-muted); }
    .request-form, .proposal-actions { display: grid; gap: 14px; margin-top: 24px; padding: 20px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    h3 { margin: 0; font-size: 1.1rem; }
    .form-help { margin: -6px 0 2px; color: var(--portal-muted); }
    label { display: grid; gap: 6px; color: var(--portal-muted); font-size: .875rem; }
    input { min-height: 42px; border: 1px solid var(--portal-line); background: var(--portal-surface); color: var(--portal-ink); padding: 8px 10px; font: inherit; }
    input:focus-visible, button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 2px; }
    .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    button { justify-self: start; min-height: 42px; border: 1px solid var(--portal-ink); background: var(--portal-ink); color: var(--portal-surface-raised); padding: 8px 14px; cursor: pointer; font: inherit; }
    button:disabled { cursor: wait; opacity: .6; }
    .action-row { display: flex; flex-wrap: wrap; gap: 10px; }
    .secondary-button { background: transparent; color: var(--portal-ink); }
    .portal-notice { margin-top: 16px; padding: 12px 14px; border-left: 3px solid var(--portal-focus); background: var(--portal-surface-raised); }
    @media (max-width: 560px) { .form-grid { grid-template-columns: 1fr; } }
  `],
})
export class PatientAppointmentsPageComponent {
  readonly facade = inject(AppointmentCommitmentFacade);

  draft: AppointmentDraft = {
    appointmentPlanId: null,
    preferredStartAt: '',
    preferredEndAt: '',
    evidenceAuthority: '',
    evidenceId: '',
    productName: '',
    sessionNumber: null,
    totalSessions: null,
  };

  get appointment(): AppointmentCardModel | null {
    const state = this.facade.state();
    if (!state.appointmentId || !state.status) return null;
    const proposal = state.commitment?.currentProposal;
    return {
      appointmentId: state.appointmentId,
      fallbackTitle: proposal?.representativeTreatmentName || '예약 제안',
      productName: this.draft.productName || null,
      sessionNumber: this.draft.sessionNumber,
      totalSessions: this.draft.totalSessions,
      status: state.status,
      startsAt: proposal?.startsAt || this.toIso(this.draft.preferredStartAt),
      endsAt: proposal?.endsAt || this.toIso(this.draft.preferredEndAt),
    };
  }

  async requestAppointment(): Promise<void> {
    if (!this.draft.appointmentPlanId) return;
    await this.facade.requestAppointment({
      appointmentPlanId: this.draft.appointmentPlanId,
      preferredStartAt: this.toIso(this.draft.preferredStartAt),
      preferredEndAt: this.toIso(this.draft.preferredEndAt),
      evidence: { evidenceAuthority: this.draft.evidenceAuthority, evidenceId: this.draft.evidenceId },
    }, this.intentKey('request'));
  }

  async acceptProposal(): Promise<void> {
    await this.facade.acceptProposal({ evidence: { evidenceAuthority: this.draft.evidenceAuthority, evidenceId: this.draft.evidenceId } }, this.intentKey('accept'));
  }

  async declineProposal(): Promise<void> {
    await this.facade.declineProposal({ reasonCode: 'CUSTOMER_REQUEST' }, this.intentKey('decline'));
  }

  get proposalLabel(): string {
    const state = this.facade.state();
    const proposal = state.commitment?.currentProposal;
    if (proposal) return `${proposal.representativeTreatmentName} · ${proposal.startsAt}`;
    if (state.proposal) return `제안 만료 시각 ${state.proposal.expiresAt}`;
    return '제안 내용을 확인하세요.';
  }

  private intentKey(action: string): string {
    const appointmentPlanId = this.draft.appointmentPlanId || 'unknown';
    return `portal-${action}-${appointmentPlanId}`;
  }

  private toIso(value: string): string {
    return value ? new Date(value).toISOString() : '';
  }
}

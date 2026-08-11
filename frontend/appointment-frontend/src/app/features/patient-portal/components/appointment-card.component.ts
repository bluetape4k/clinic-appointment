import { Component, Input } from '@angular/core';

import { CommitmentStatus } from '../../../core/api/portal-api.models';
import { formatAppointmentSession, resolveAppointmentTitle } from '../appointment-summary';

export interface AppointmentCardModel {
  appointmentId: number;
  fallbackTitle: string;
  productName?: string | null;
  sessionNumber?: number | null;
  totalSessions?: number | null;
  status: CommitmentStatus;
  startsAt: string;
  endsAt: string;
}

const STATUS_LABELS: Record<CommitmentStatus, string> = {
  PROPOSED: '제안 확인 필요',
  HELD: '잠시 선점됨',
  CONFIRMED: '확정',
  EXPIRED: '만료',
  CANCELLED: '취소됨',
};

@Component({
  selector: 'app-appointment-card',
  standalone: true,
  template: `
    <article class="appointment-card" [attr.aria-labelledby]="'appointment-' + appointment.appointmentId">
      <div class="appointment-card__heading">
        <div>
          <p class="appointment-card__eyebrow">예약 {{ appointment.appointmentId }}</p>
          <h3 [id]="'appointment-' + appointment.appointmentId">{{ title }}</h3>
        </div>
        <span class="appointment-status" [attr.data-status]="appointment.status">{{ statusLabel }}</span>
      </div>
      <div class="appointment-card__meta">
        <time [attr.datetime]="appointment.startsAt">{{ dateLabel }}</time>
        @if (sessionLabel; as session) {
          <span data-session>{{ session }}</span>
        }
      </div>
      <div class="appointment-card__footer">
        <span>{{ timeLabel }}</span>
        <button type="button" class="text-button">예약 상세 보기<span aria-hidden="true"> ↗</span></button>
      </div>
    </article>
  `,
  styles: [`
    :host { display: block; }
    .appointment-card { display: grid; gap: 16px; padding: 20px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    .appointment-card__heading, .appointment-card__footer { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
    .appointment-card__eyebrow { margin: 0 0 6px; color: var(--portal-muted); font-size: .75rem; letter-spacing: .08em; }
    h3 { margin: 0; font-size: clamp(1.1rem, 3vw, 1.35rem); letter-spacing: -.02em; }
    .appointment-status { display: inline-flex; flex: 0 0 auto; align-items: center; min-height: 28px; padding: 4px 8px; border: 1px solid currentColor; color: var(--portal-status-proposed); font-size: .875rem; }
    .appointment-status[data-status="HELD"] { color: var(--portal-status-held); }
    .appointment-status[data-status="CONFIRMED"] { color: var(--portal-status-confirmed); }
    .appointment-status[data-status="EXPIRED"], .appointment-status[data-status="CANCELLED"] { color: var(--portal-status-expired); }
    .appointment-card__meta, .appointment-card__footer { color: var(--portal-muted); }
    .appointment-card__meta { display: flex; flex-wrap: wrap; gap: 8px 16px; }
    .text-button { border: 0; background: transparent; color: var(--portal-ink); cursor: pointer; font: inherit; text-decoration: underline; text-underline-offset: 3px; }
    .text-button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 3px; }
    @media (max-width: 420px) { .appointment-card__heading, .appointment-card__footer { display: grid; } }
  `],
})
export class AppointmentCardComponent {
  @Input({ required: true }) appointment!: AppointmentCardModel;

  get title(): string {
    return resolveAppointmentTitle(this.appointment, this.appointment.fallbackTitle);
  }

  get sessionLabel(): string | null {
    return formatAppointmentSession(this.appointment);
  }

  get statusLabel(): string {
    return STATUS_LABELS[this.appointment.status];
  }

  get dateLabel(): string {
    return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'long' }).format(new Date(this.appointment.startsAt));
  }

  get timeLabel(): string {
    const format = new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit' });
    return `${format.format(new Date(this.appointment.startsAt))}–${format.format(new Date(this.appointment.endsAt))}`;
  }
}

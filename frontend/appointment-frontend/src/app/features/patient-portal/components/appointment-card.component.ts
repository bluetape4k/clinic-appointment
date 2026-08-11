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
      <p class="appointment-card__eyebrow">다음 방문</p>
      <h3 [id]="'appointment-' + appointment.appointmentId">{{ title }}</h3>
      <div class="appointment-card__meta">
        <time [attr.datetime]="appointment.startsAt">{{ dateTimeLabel }}</time>
        @if (sessionLabel; as session) {
          <span data-session>{{ session }}</span>
        }
        <span class="appointment-status" [attr.data-status]="appointment.status">{{ statusLabel }}</span>
      </div>
      <p class="appointment-card__description">담당 의료진과 방문 준비사항을 확인하세요.</p>
      <button type="button" class="text-button">예약 상세 보기<span aria-hidden="true"> ↗</span></button>
    </article>
  `,
  styles: [`
    :host { display: block; }
    .appointment-card { display: grid; gap: 16px; }
    .appointment-card__eyebrow { margin: 0; color: var(--portal-muted); font-size: .8rem; }
    h3 { margin: 0; font-size: clamp(1.35rem, 3vw, 1.75rem); letter-spacing: -.03em; }
    .appointment-status { display: inline-flex; align-items: center; gap: 6px; color: var(--portal-status-proposed); }
    .appointment-status::before { content: '●'; }
    .appointment-status[data-status="HELD"] { color: var(--portal-status-held); }
    .appointment-status[data-status="CONFIRMED"] { color: var(--portal-status-confirmed); }
    .appointment-status[data-status="EXPIRED"], .appointment-status[data-status="CANCELLED"] { color: var(--portal-status-expired); }
    .appointment-card__meta { display: flex; flex-wrap: wrap; gap: 8px 16px; color: var(--portal-muted); }
    .appointment-card__description { margin: 0; }
    .text-button { border: 0; background: transparent; color: var(--portal-ink); cursor: pointer; font: inherit; text-decoration: underline; text-underline-offset: 3px; }
    .text-button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 3px; }
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

  get dateTimeLabel(): string {
    return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'long', timeStyle: 'short' }).format(new Date(this.appointment.startsAt));
  }
}

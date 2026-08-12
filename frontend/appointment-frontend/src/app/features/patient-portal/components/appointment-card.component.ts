import { Component, EventEmitter, Input, Output } from '@angular/core';

import { CommitmentStatus } from '../../../core/api/portal-api.models';
import { formatAppointmentSession, resolveAppointmentTitle } from '../appointment-summary';

export interface AppointmentCardModel {
  appointmentId: number;
  fallbackTitle: string;
  productName?: string | null;
  sessionNumber?: number | null;
  totalSessions?: number | null;
  clinicDisplayName?: string | null;
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

const PORTAL_TIME_ZONE = 'Asia/Seoul';
const STATUS_STEPS: ReadonlyArray<{ id: CommitmentStatus; label: string }> = [
  { id: 'PROPOSED', label: '제안됨' },
  { id: 'HELD', label: '선점됨' },
  { id: 'CONFIRMED', label: '확정됨' },
  { id: 'CANCELLED', label: '취소됨' },
  { id: 'EXPIRED', label: '만료됨' },
];

@Component({
  selector: 'app-appointment-card',
  standalone: true,
  template: `
    <article class="appointment-card" [attr.aria-labelledby]="'appointment-' + appointment.appointmentId">
      <span class="appointment-card__eyebrow">다음 방문</span>
      <h3 [id]="'appointment-' + appointment.appointmentId">{{ title }}</h3>
      <div class="appointment-card__meta">
        <time [attr.datetime]="appointment.startsAt">{{ dateTimeLabel }}</time>
        @if (appointment.clinicDisplayName; as clinic) {
          <span data-clinic>{{ clinic }}</span>
        }
        @if (sessionLabel; as session) {
          <span data-session>{{ session }}</span>
        }
        <span class="appointment-status" [attr.data-status]="appointment.status">{{ statusLabel }}</span>
      </div>
      <ol class="status-stepper" aria-label="예약 진행 상태">
        @for (step of statusSteps; track step.id) {
          <li [attr.data-step]="step.id" [class.status-step--active]="step.id === appointment.status" [attr.aria-current]="step.id === appointment.status ? 'step' : null">{{ step.label }}</li>
        }
      </ol>
      <p class="appointment-card__description">담당 의료진과 방문 준비사항을 확인하세요.</p>
      @if (canCancel) {
        <button type="button" class="text-button" data-cancel (click)="cancelRequested.emit()">예약 취소</button>
      } @else {
        <p class="terminal-notice" data-terminal>{{ terminalLabel }}</p>
      }
    </article>
  `,
  styles: [`
    :host { display: block; }
    .appointment-card { display: block; }
    .appointment-card__eyebrow { color: var(--portal-muted); font-size: .8rem; letter-spacing: .08em; text-transform: uppercase; }
    h3 { margin: 21.44px 0; font-size: 2rem; line-height: 1.5; }
    .appointment-status { display: inline-flex; align-items: center; gap: 6px; color: var(--portal-status-proposed); }
    .appointment-status::before { content: '●'; }
    .appointment-status[data-status="HELD"] { color: var(--portal-status-held); }
    .appointment-status[data-status="CONFIRMED"] { color: var(--portal-status-confirmed); }
    .appointment-status[data-status="EXPIRED"], .appointment-status[data-status="CANCELLED"] { color: var(--portal-status-expired); }
    .appointment-card__meta { display: flex; flex-wrap: wrap; gap: 8px 16px; color: var(--portal-muted); font-size: 1rem; }
    .appointment-card__description { margin: 16px 0; }
    .status-stepper { display: flex; flex-wrap: wrap; gap: 8px; margin: 20px 0 0; padding: 0; list-style: none; color: var(--portal-muted); font-size: .82rem; }
    .status-stepper li { border: 1px solid var(--portal-line); padding: 5px 8px; }
    .status-stepper .status-step--active { border-color: var(--portal-ink); background: var(--portal-ink); color: var(--portal-surface-raised); }
    .terminal-notice { margin: 16px 0 0; color: var(--portal-muted); }
    .text-button { border: 1px solid var(--portal-ink); background: var(--portal-ink); color: var(--portal-surface-raised); cursor: pointer; padding: 10px 14px; }
    .text-button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 3px; }
  `],
})
export class AppointmentCardComponent {
  @Input({ required: true }) appointment!: AppointmentCardModel;
  @Output() readonly cancelRequested = new EventEmitter<void>();

  readonly statusSteps = STATUS_STEPS;

  get title(): string {
    return resolveAppointmentTitle(this.appointment, this.appointment.fallbackTitle);
  }

  get sessionLabel(): string | null {
    return formatAppointmentSession(this.appointment);
  }

  get statusLabel(): string {
    return STATUS_LABELS[this.appointment.status];
  }

  get canCancel(): boolean {
    return this.appointment.status === 'PROPOSED' || this.appointment.status === 'HELD' || this.appointment.status === 'CONFIRMED';
  }

  get terminalLabel(): string {
    return this.appointment.status === 'CANCELLED'
      ? '취소가 완료된 예약입니다.'
      : '더 이상 변경할 수 없는 예약입니다.';
  }

  get dateTimeLabel(): string {
    const startsAt = new Date(this.appointment.startsAt);
    const date = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'long', timeZone: PORTAL_TIME_ZONE }).format(startsAt);
    const time = new Intl.DateTimeFormat('ko-KR', {
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZone: PORTAL_TIME_ZONE,
    }).format(startsAt);
    return `${date} ${time}`;
  }
}

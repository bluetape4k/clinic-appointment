import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';

import { PortalApiClient } from '../../../core/api/portal-api-client';
import { PortalEventStreamAdapter, PortalNotification } from '../../../core/api/portal-event-stream.adapter';
import { TenantContextService } from '../../../core/api/tenant-context.service';
import { formatAppointmentSession, resolveAppointmentTitle } from '../appointment-summary';

@Component({
  selector: 'app-patient-notifications-page',
  standalone: true,
  template: `
    <section class="portal-page" aria-labelledby="notifications-title">
      <p class="portal-eyebrow">NOTIFICATIONS</p>
      <h2 id="notifications-title">알림</h2>
      <p class="portal-page-intro">예약 제안, 확정, 재배정 소식을 놓치지 않도록 알려드립니다.</p>
      @if (notice(); as currentNotice) {
        <p class="portal-notice" role="status" aria-live="polite">{{ currentNotice }}</p>
      }
      @if (notifications().length === 0) {
        <div class="portal-empty" role="status">
          <strong>새 알림이 없습니다.</strong>
          <span>예약 상태가 바뀌면 이곳에 시간과 다음 행동이 표시됩니다.</span>
        </div>
      } @else {
        <ol class="notification-list" aria-label="예약 알림">
          @for (notification of notifications(); track notification.eventId) {
            <li [class.is-unread]="!notification.read">
              <article>
                <div class="notification-heading">
                  <div>
                    <p class="notification-eyebrow">{{ statusLabel(notification.status) }}</p>
                    <h3>{{ notificationTitle(notification) }}</h3>
                  </div>
                  @if (sessionLabel(notification); as session) {
                    <span class="notification-session">{{ session }}</span>
                  }
                </div>
                <p class="notification-message">{{ notification.message }}</p>
                <div class="notification-footer">
                  <time [attr.datetime]="notification.createdAt">{{ notification.createdAt }}</time>
                  <div class="notification-actions">
                    <button type="button" (click)="markRead(notification.eventId)">{{ notification.read ? '읽음' : '읽음으로 표시' }}</button>
                    <a href="/portal/appointments">예약 상세 보기<span aria-hidden="true"> ↗</span></a>
                  </div>
                </div>
              </article>
            </li>
          }
        </ol>
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .portal-page { max-width: 760px; }
    .portal-eyebrow { margin: 0 0 8px; color: var(--portal-muted); font-size: .75rem; font-weight: 700; letter-spacing: .12em; }
    h2 { margin: 0; font-size: clamp(1.35rem, 3vw, 1.75rem); letter-spacing: -.03em; }
    .portal-page-intro { margin: 8px 0 0; color: var(--portal-muted); }
    .portal-empty { display: grid; gap: 8px; margin-top: 24px; padding: 24px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    .portal-empty span, .portal-notice, .notification-message, time { color: var(--portal-muted); }
    .portal-notice { margin-top: 20px; padding: 12px 14px; border-left: 3px solid var(--portal-focus); background: var(--portal-surface-raised); }
    .notification-list { display: grid; gap: 12px; margin: 24px 0 0; padding: 0; list-style: none; }
    .notification-list li { border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    .notification-list li.is-unread { border-left: 3px solid var(--portal-focus); }
    .notification-list article { display: grid; gap: 12px; padding: 18px; }
    .notification-heading, .notification-footer { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
    .notification-eyebrow { margin: 0 0 5px; color: var(--portal-muted); font-size: .75rem; letter-spacing: .08em; }
    h3 { margin: 0; font-size: 1.05rem; }
    .notification-session { color: var(--portal-muted); white-space: nowrap; }
    .notification-message { margin: 0; }
    .notification-footer { align-items: center; font-size: .875rem; }
    .notification-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
    button, a { color: var(--portal-ink); font: inherit; }
    button { border: 0; background: transparent; cursor: pointer; padding: 4px 0; text-decoration: underline; text-underline-offset: 3px; }
    a { text-decoration: underline; text-underline-offset: 3px; }
    button:focus-visible, a:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 3px; }
    @media (max-width: 520px) { .notification-heading, .notification-footer { display: grid; } }
  `],
})
export class PatientNotificationsPageComponent {
  private readonly adapter = inject(PortalEventStreamAdapter);
  private readonly client = inject(PortalApiClient);
  private readonly tenant = inject(TenantContextService);
  private readonly destroyRef = inject(DestroyRef);

  readonly notifications = signal<PortalNotification[]>([]);
  readonly notice = signal<string | null>(null);

  constructor() {
    const tenantCode = this.tenant.tenantCode();
    if (!tenantCode) {
      this.notice.set('tenant scope를 확인하면 실시간 알림을 연결합니다.');
      return;
    }
    this.adapter.connect({
      streamUrl: `/api/${encodeURIComponent(tenantCode)}/notifications/stream`,
      poll: () => this.client.getNotifications().then(response => response.body),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: notification => this.notifications.update(current => {
        const withoutCurrent = current.filter(item => item.eventId !== notification.eventId);
        return [notification, ...withoutCurrent].sort((left, right) => right.sequence - left.sequence);
      }),
      error: () => this.notice.set('알림 연결을 확인할 수 없습니다. 예약 현황에서 최신 상태를 확인하세요.'),
    });
  }

  markRead(eventId: string): void {
    this.notifications.update(items => items.map(item => item.eventId === eventId ? { ...item, read: true } : item));
  }

  notificationTitle(notification: PortalNotification): string {
    return resolveAppointmentTitle(notification, notification.title);
  }

  sessionLabel(notification: PortalNotification): string | null {
    return formatAppointmentSession(notification);
  }

  statusLabel(status: PortalNotification['status']): string {
    return {
      PROPOSED: '제안 확인 필요',
      HELD: '잠시 선점됨',
      CONFIRMED: '확정',
      EXPIRED: '만료',
      CANCELLED: '취소됨',
    }[status];
  }
}

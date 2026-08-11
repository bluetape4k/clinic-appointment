import { Component } from '@angular/core';

@Component({
  selector: 'app-patient-notifications-page',
  standalone: true,
  template: `
    <section class="portal-page" aria-labelledby="notifications-title">
      <p class="portal-eyebrow">NOTIFICATIONS</p>
      <h2 id="notifications-title">알림</h2>
      <p class="portal-page-intro">예약 제안, 확정, 재배정 소식을 놓치지 않도록 알려드립니다.</p>
      <div class="portal-empty" role="status">
        <strong>새 알림이 없습니다.</strong>
        <span>예약 상태가 바뀌면 이곳에 시간과 다음 행동이 표시됩니다.</span>
      </div>
    </section>
  `,
  styles: [`
    :host { display: block; }
    .portal-page { max-width: 760px; }
    .portal-eyebrow { margin: 0 0 8px; color: var(--portal-muted); font-size: .75rem; font-weight: 700; letter-spacing: .12em; }
    h2 { margin: 0; font-size: clamp(1.35rem, 3vw, 1.75rem); letter-spacing: -.03em; }
    .portal-page-intro { margin: 8px 0 0; color: var(--portal-muted); }
    .portal-empty { display: grid; gap: 8px; margin-top: 24px; padding: 24px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    .portal-empty span { color: var(--portal-muted); }
  `],
})
export class PatientNotificationsPageComponent {}

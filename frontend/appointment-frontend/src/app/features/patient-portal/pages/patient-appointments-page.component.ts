import { Component } from '@angular/core';

@Component({
  selector: 'app-patient-appointments-page',
  standalone: true,
  template: `
    <section class="portal-page" aria-labelledby="appointments-title">
      <p class="portal-eyebrow">APPOINTMENTS</p>
      <h2 id="appointments-title">예약 현황</h2>
      <p class="portal-page-intro">요청 중인 예약과 확정된 방문 일정을 확인하세요.</p>
      <div class="portal-empty" role="status">
        <strong>표시할 예약을 준비하고 있습니다.</strong>
        <span>예약을 요청하면 이곳에서 제안과 확정 상태를 확인할 수 있습니다.</span>
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
export class PatientAppointmentsPageComponent {}

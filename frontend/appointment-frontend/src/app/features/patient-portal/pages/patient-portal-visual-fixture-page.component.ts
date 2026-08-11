import { Component } from '@angular/core';

import { AppointmentCardComponent, AppointmentCardModel } from '../components/appointment-card.component';

@Component({
  selector: 'app-patient-portal-visual-fixture-page',
  standalone: true,
  imports: [AppointmentCardComponent],
  template: `
    <section class="visual-fixture" aria-labelledby="visual-fixture-title">
      <h2 id="visual-fixture-title" class="sr-only">환자 포털 확정 예약 참조 상태</h2>
      <app-appointment-card [appointment]="appointment" />
    </section>
  `,
  styles: [`
    .visual-fixture { max-width: 720px; }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }
  `],
})
export class PatientPortalVisualFixturePageComponent {
  readonly appointment: AppointmentCardModel = {
    appointmentId: 42,
    fallbackTitle: '2026년 8월 20일 방문',
    productName: '피부 재생 관리',
    sessionNumber: 3,
    totalSessions: 10,
    status: 'CONFIRMED',
    startsAt: '2026-08-20T01:30:00Z',
    endsAt: '2026-08-20T02:00:00Z',
  };
}

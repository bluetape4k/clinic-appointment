import { Component } from '@angular/core';

@Component({
  selector: 'app-patient-profile-page',
  standalone: true,
  template: `
    <section class="portal-page" aria-labelledby="profile-title">
      <p class="portal-eyebrow">PROFILE</p>
      <h2 id="profile-title">내 정보</h2>
      <p class="portal-page-intro">예약에 사용하는 환자 계정과 동의 상태를 확인하세요.</p>
      <div class="portal-empty" role="status">
        <strong>환자 정보를 불러오는 중입니다.</strong>
        <span>인증된 세션의 정보만 표시하며 tenant 범위를 벗어난 정보는 요청하지 않습니다.</span>
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
export class PatientProfilePageComponent {}

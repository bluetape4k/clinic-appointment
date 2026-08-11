import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

export interface PatientPortalNavItem {
  label: string;
  route: string;
}

@Component({
  selector: 'app-patient-portal-shell',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './patient-portal-shell.component.html',
  styleUrl: './patient-portal-shell.component.scss',
})
export class PatientPortalShellComponent {
  readonly navItems: PatientPortalNavItem[] = [
    { label: '예약 현황', route: '/portal/appointments' },
    { label: '알림', route: '/portal/notifications' },
    { label: '내 정보', route: '/portal/profile' },
  ];
}

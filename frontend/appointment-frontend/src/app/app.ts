import { Component, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatTabsModule } from '@angular/material/tabs';
import { MatButtonModule } from '@angular/material/button';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs/operators';
import { WorkforceAuthBootstrapService } from './core/services/workforce-auth-bootstrap.service';
import { MobileViewportDirective } from './shared';

export interface NavItem {
  label: string;
  icon: string;
  route: string;
}

@Component({
  selector: 'app-root',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatToolbarModule,
    MatIconModule,
    MatListModule,
    MatTabsModule,
    MatButtonModule,
    MobileViewportDirective,
  ],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly breakpointObserver = inject(BreakpointObserver);
  private readonly router = inject(Router);
  private readonly workforceAuthBootstrap = inject(WorkforceAuthBootstrapService);

  readonly isPatientPortal = signal(this.router.url.startsWith('/portal'));

  readonly navItems: NavItem[] = [
    { label: '캘린더', icon: 'calendar_today', route: '/calendar' },
    { label: '예약 관리', icon: 'event_note', route: '/appointments' },
    { label: '관리', icon: 'settings', route: '/management' },
  ];

  readonly isDesktop = toSignal(
    this.breakpointObserver
      .observe([Breakpoints.Medium, Breakpoints.Large, Breakpoints.XLarge])
      .pipe(map(result => result.matches)),
    { initialValue: false }
  );

  constructor() {
    this.workforceAuthBootstrap.restore();

    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe(event => this.isPatientPortal.set(event.urlAfterRedirects.startsWith('/portal')));
  }
}

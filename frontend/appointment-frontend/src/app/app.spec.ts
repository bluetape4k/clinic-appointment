import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { vi } from 'vitest';
import { App } from './app';
import { WorkforceAuthBootstrapService } from './core/services/workforce-auth-bootstrap.service';

describe('App', () => {
  let workforceAuthBootstrap: { restore: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    workforceAuthBootstrap = { restore: vi.fn() };
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([{ path: 'portal', children: [] }, { path: 'calendar', children: [] }]),
        provideLocationMocks(),
        provideAnimationsAsync(),
        { provide: WorkforceAuthBootstrapService, useValue: workforceAuthBootstrap },
      ],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should have navigation items', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app.navItems.length).toBe(3);
    expect(app.navItems[0].label).toBe('캘린더');
  });

  it('앱 셸 생성 시 호스트 workforce 인증 handoff를 복원한다', () => {
    TestBed.createComponent(App);

    expect(workforceAuthBootstrap.restore).toHaveBeenCalledOnce();
  });

  it('환자 포털 route에서는 staff shell 대신 portal outlet만 사용한다', async () => {
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    fixture.detectChanges();

    await router.navigateByUrl('/portal');
    expect(fixture.componentInstance.isPatientPortal()).toBe(true);

    await router.navigateByUrl('/calendar');
    expect(fixture.componentInstance.isPatientPortal()).toBe(false);
  });
});

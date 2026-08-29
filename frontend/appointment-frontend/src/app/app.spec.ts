import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { vi } from 'vitest';
import { App } from './app';
import { WorkforceAuthBootstrapService } from './core/services/workforce-auth-bootstrap.service';
import { NativeWebViewBridgeService } from './core/services/native-webview-bridge.service';

describe('App', () => {
  let workforceAuthBootstrap: { restore: ReturnType<typeof vi.fn> };
  let nativeWebViewBridge: { start: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    workforceAuthBootstrap = { restore: vi.fn() };
    nativeWebViewBridge = { start: vi.fn().mockResolvedValue(undefined) };
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [
        provideRouter([
          { path: 'portal', children: [] },
          { path: 'calendar', children: [] },
        ]),
        provideLocationMocks(),
        provideAnimationsAsync(),
        { provide: WorkforceAuthBootstrapService, useValue: workforceAuthBootstrap },
        { provide: NativeWebViewBridgeService, useValue: nativeWebViewBridge },
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

  it('앱 셸 생성 시 native WebView bridge를 시작한다', () => {
    TestBed.createComponent(App);

    expect(nativeWebViewBridge.start).toHaveBeenCalledOnce();
  });

  it('모바일 하단 내비게이션을 native accessibility group으로 노출한다', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const navigationGroup = fixture.nativeElement.querySelector(
      '[role="group"][aria-label="모바일 하단 내비게이션 영역"]',
    );
    expect(navigationGroup).toBeTruthy();
    expect(
      navigationGroup.querySelector('nav[aria-label="모바일 하단 내비게이션"]'),
    ).toBeTruthy();
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

  it('offline/update 상태를 전역 status region으로 노출한다', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    window.dispatchEvent(new Event('offline'));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-pwa-status]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('[data-pwa-status]')?.textContent).toContain(
      '오프라인',
    );
  });
});

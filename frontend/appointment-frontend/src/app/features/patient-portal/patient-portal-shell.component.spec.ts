import { describe, expect, it, beforeEach } from 'vitest';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';

import { PatientPortalShellComponent } from './patient-portal-shell.component';

describe('PatientPortalShellComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PatientPortalShellComponent],
      providers: [provideRouter([]), provideLocationMocks(), provideHttpClient()],
    }).compileComponents();
  });

  it('환자 포털 shell이 생성된다', () => {
    const fixture = TestBed.createComponent(PatientPortalShellComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('승인된 세 가지 탐색 label과 route를 노출한다', () => {
    const fixture = TestBed.createComponent(PatientPortalShellComponent);
    const component = fixture.componentInstance;

    expect(component.navItems).toEqual([
      { label: '예약 현황', route: '/portal/appointments' },
      { label: '알림', route: '/portal/notifications' },
      { label: '내 정보', route: '/portal/profile' },
    ]);
  });

  it('렌더링된 nav 링크에 screen reader 이름과 focusable native link를 제공한다', () => {
    const fixture = TestBed.createComponent(PatientPortalShellComponent);
    fixture.detectChanges();

    const nav = fixture.nativeElement.querySelector('nav[aria-label="환자 포털"]');
    const links = Array.from(nav.querySelectorAll('a')) as HTMLAnchorElement[];
    expect(links.map(link => link.querySelector('.nav-label')?.textContent?.trim())).toEqual(['예약 현황', '알림', '내 정보']);
    expect(links.every(link => link.getAttribute('href')?.startsWith('/portal/'))).toBe(true);
  });

  it('shell은 내부 컨테이너에서 수평 overflow를 만들지 않는 구조를 사용한다', () => {
    const fixture = TestBed.createComponent(PatientPortalShellComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('main.portal-main')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('router-outlet')).toBeTruthy();
  });
});

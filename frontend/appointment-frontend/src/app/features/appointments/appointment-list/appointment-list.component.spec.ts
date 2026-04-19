import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { WritableSignal, signal } from '@angular/core';

import { AppointmentListComponent } from './appointment-list.component';
import { AuthService } from '../../../core/services/auth.service';

describe('AppointmentListComponent', () => {
  let httpMock: HttpTestingController;
  let isAdmin: WritableSignal<boolean>;
  let isStaff: WritableSignal<boolean>;

  beforeEach(() => {
    isAdmin = signal(false);
    isStaff = signal(false);

    TestBed.configureTestingModule({
      imports: [AppointmentListComponent],
      providers: [
        provideRouter([]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideAnimationsAsync(),
        {
          provide: AuthService,
          useValue: {
            isAdmin,
            isStaff,
            isDoctor: signal(false),
            isPatient: signal(false),
            getToken: vi.fn().mockReturnValue(null),
          },
        },
      ],
    });

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  async function createAndFlush() {
    const fixture = TestBed.createComponent(AppointmentListComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    // Flush all pending HTTP requests triggered by ngOnInit
    const reqs = httpMock.match(() => true);
    reqs.forEach(req => {
      if (req.request.url.includes('/api/appointments')) {
        req.flush({ data: [] });
      } else if (req.request.url.includes('/api/clinics/')) {
        req.flush({ success: true, data: [] });
      } else {
        req.flush({ success: true, data: [] });
      }
    });

    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('컴포넌트가 생성된다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('예약이 없을 때 빈 데이터소스를 가진다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance.dataSource.data).toHaveLength(0);
  });

  it('ADMIN 권한이면 canCreate()가 true이다', async () => {
    isAdmin.set(true);
    const fixture = await createAndFlush();
    expect(fixture.componentInstance.canCreate()).toBe(true);
  });

  it('STAFF 권한이면 canCreate()가 true이다', async () => {
    isStaff.set(true);
    const fixture = await createAndFlush();
    expect(fixture.componentInstance.canCreate()).toBe(true);
  });

  it('ADMIN/STAFF 권한이 없으면 canCreate()가 false이다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance.canCreate()).toBe(false);
  });

  it('"새 예약" 버튼이 canCreate()=true 일 때 템플릿에 렌더된다', async () => {
    isAdmin.set(true);
    const fixture = await createAndFlush();
    const button = fixture.nativeElement.querySelector('.new-button');
    expect(button).toBeTruthy();
  });

  it('"새 예약" 버튼이 canCreate()=false 일 때 템플릿에 없다', async () => {
    const fixture = await createAndFlush();
    const button = fixture.nativeElement.querySelector('.new-button');
    expect(button).toBeNull();
  });
});

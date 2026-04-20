import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { DoctorListComponent } from './doctor-list.component';

describe('DoctorListComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DoctorListComponent],
      providers: [
        provideRouter([]),
        provideLocationMocks(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideAnimationsAsync(),
      ],
    });

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  async function createAndFlush(mockDoctors: unknown[] = []) {
    const fixture = TestBed.createComponent(DoctorListComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    httpMock.match(() => true).forEach(req =>
      req.flush({ success: true, data: mockDoctors })
    );
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('컴포넌트가 생성된다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('displayedColumns가 name, specialty, providerType을 포함한다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance.displayedColumns).toContain('name');
    expect(fixture.componentInstance.displayedColumns).toContain('specialty');
    expect(fixture.componentInstance.displayedColumns).toContain('providerType');
  });

  it('의사 목록 데이터가 주입되면 doctors signal에 반영된다', async () => {
    const mockDoctors = [
      { id: 1, clinicId: 1, name: '김민준', specialty: '일반의', providerType: 'DOCTOR' },
    ];
    const fixture = await createAndFlush(mockDoctors);
    expect(fixture.componentInstance.doctors()).toHaveLength(1);
    expect(fixture.componentInstance.doctors()[0].name).toBe('김민준');
  });

  it('의사가 없을 때 doctors signal이 빈 배열이다', async () => {
    const fixture = await createAndFlush([]);
    expect(fixture.componentInstance.doctors()).toHaveLength(0);
  });
});

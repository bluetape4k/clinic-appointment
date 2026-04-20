import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { ClinicListComponent } from './clinic-list.component';

const MOCK_CLINICS = [
  { id: 1, name: '서울 메인 클리닉', slotDurationMinutes: 30, timezone: 'Asia/Seoul', locale: 'ko-KR', maxConcurrentPatients: 5, openOnHolidays: false },
  { id: 2, name: '부산 해운대 클리닉', slotDurationMinutes: 20, timezone: 'Asia/Seoul', locale: 'ko-KR', maxConcurrentPatients: 3, openOnHolidays: true },
];

describe('ClinicListComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ClinicListComponent],
      providers: [
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

  async function createAndFlush(mockClinics: unknown[] = []) {
    const fixture = TestBed.createComponent(ClinicListComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    httpMock.match(() => true).forEach(req =>
      req.flush({ success: true, data: mockClinics })
    );
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('컴포넌트가 생성된다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('API 응답 데이터가 clinics signal에 반영된다', async () => {
    const fixture = await createAndFlush(MOCK_CLINICS);
    expect(fixture.componentInstance.clinics()).toHaveLength(2);
    expect(fixture.componentInstance.clinics()[0].name).toBe('서울 메인 클리닉');
  });

  it('각 클리닉은 id, name, slotDurationMinutes, timezone 필드를 가진다', async () => {
    const fixture = await createAndFlush(MOCK_CLINICS);
    const clinic = fixture.componentInstance.clinics()[0];
    expect(clinic).toHaveProperty('id');
    expect(clinic).toHaveProperty('name');
    expect(clinic).toHaveProperty('slotDurationMinutes');
    expect(clinic).toHaveProperty('timezone');
  });

  it('클리닉 카드가 clinics 개수만큼 렌더된다', async () => {
    const fixture = await createAndFlush(MOCK_CLINICS);
    const cards = fixture.nativeElement.querySelectorAll('mat-card');
    expect(cards.length).toBe(fixture.componentInstance.clinics().length);
  });

  it('클리닉이 없을 때 clinics signal이 빈 배열이다', async () => {
    const fixture = await createAndFlush([]);
    expect(fixture.componentInstance.clinics()).toHaveLength(0);
  });
});

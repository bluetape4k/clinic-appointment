import { describe, it, expect, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { ClinicListComponent } from './clinic-list.component';

describe('ClinicListComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ClinicListComponent],
      providers: [
        provideAnimationsAsync(),
      ],
    });
  });

  it('컴포넌트가 생성된다', () => {
    const fixture = TestBed.createComponent(ClinicListComponent);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('clinics 목록에 MOCK_CLINICS 데이터가 포함된다', () => {
    const fixture = TestBed.createComponent(ClinicListComponent);
    fixture.detectChanges();

    const clinics = fixture.componentInstance.clinics;
    expect(clinics.length).toBeGreaterThan(0);
  });

  it('각 클리닉은 id, name, phone, address, slotDurationMinutes 필드를 가진다', () => {
    const fixture = TestBed.createComponent(ClinicListComponent);
    fixture.detectChanges();

    const clinic = fixture.componentInstance.clinics[0];
    expect(clinic).toHaveProperty('id');
    expect(clinic).toHaveProperty('name');
    expect(clinic).toHaveProperty('phone');
    expect(clinic).toHaveProperty('address');
    expect(clinic).toHaveProperty('slotDurationMinutes');
  });

  it('클리닉 카드가 clinics 개수만큼 렌더된다', () => {
    const fixture = TestBed.createComponent(ClinicListComponent);
    fixture.detectChanges();

    const cards = fixture.nativeElement.querySelectorAll('mat-card');
    expect(cards.length).toBe(fixture.componentInstance.clinics.length);
  });
});

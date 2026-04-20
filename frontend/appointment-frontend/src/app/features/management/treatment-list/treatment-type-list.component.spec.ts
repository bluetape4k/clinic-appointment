import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { TreatmentTypeListComponent } from './treatment-type-list.component';

describe('TreatmentTypeListComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TreatmentTypeListComponent],
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

  async function createAndFlush(mockTypes: unknown[] = []) {
    const fixture = TestBed.createComponent(TreatmentTypeListComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    httpMock.match(() => true).forEach(req =>
      req.flush({ success: true, data: mockTypes })
    );
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('컴포넌트가 생성된다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('displayedColumns가 name, category, duration, requiresEquipment을 포함한다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance.displayedColumns).toContain('name');
    expect(fixture.componentInstance.displayedColumns).toContain('category');
    expect(fixture.componentInstance.displayedColumns).toContain('duration');
    expect(fixture.componentInstance.displayedColumns).toContain('requiresEquipment');
  });

  it('진료유형 목록이 주입되면 treatmentTypes signal에 반영된다', async () => {
    const mockTypes = [
      { id: 1, clinicId: 1, name: '일반진료', category: 'GENERAL', defaultDurationMinutes: 30, requiresEquipment: false },
    ];
    const fixture = await createAndFlush(mockTypes);
    expect(fixture.componentInstance.treatmentTypes()).toHaveLength(1);
    expect(fixture.componentInstance.treatmentTypes()[0].name).toBe('일반진료');
  });

  it('진료유형이 없을 때 treatmentTypes signal이 빈 배열이다', async () => {
    const fixture = await createAndFlush([]);
    expect(fixture.componentInstance.treatmentTypes()).toHaveLength(0);
  });
});

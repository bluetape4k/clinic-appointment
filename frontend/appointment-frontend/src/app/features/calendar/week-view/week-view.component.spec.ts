import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { WeekViewComponent } from './week-view.component';

describe('WeekViewComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WeekViewComponent],
      providers: [
        provideRouter([{ path: 'calendar/week/:date', component: WeekViewComponent }]),
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

  async function createAndFlush() {
    const fixture = TestBed.createComponent(WeekViewComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    httpMock.match(() => true).forEach(req => req.flush({ success: true, data: [] }));
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('컴포넌트가 생성된다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('08:00~18:00 범위의 timeSlots가 생성된다', async () => {
    const fixture = await createAndFlush();
    const slots = fixture.componentInstance['timeSlots'];
    expect(slots.length).toBe(20);
    expect(slots[0].label).toBe('08:00');
    expect(slots[slots.length - 1].label).toBe('17:30');
  });

  it('weekDays가 7일을 포함한다', async () => {
    const fixture = await createAndFlush();
    const weekDays = fixture.componentInstance['weekDays'];
    expect(weekDays()).toHaveLength(7);
  });

  it('loading signal이 초기에 false이다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance['loading']()).toBe(false);
  });
});

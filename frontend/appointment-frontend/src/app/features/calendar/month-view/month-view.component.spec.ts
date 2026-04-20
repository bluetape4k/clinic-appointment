import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';

import { MonthViewComponent } from './month-view.component';

describe('MonthViewComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MonthViewComponent],
      providers: [
        provideRouter([{ path: 'calendar/month/:date', component: MonthViewComponent }]),
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
    const fixture = TestBed.createComponent(MonthViewComponent);
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

  it('weekdays가 7개 레이블을 가진다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance['weekdays']).toHaveLength(7);
  });

  it('cells는 최소 28개 이상의 날짜 셀을 가진다', async () => {
    const fixture = await createAndFlush();
    const cells = fixture.componentInstance['cells']();
    expect(cells.length).toBeGreaterThanOrEqual(28);
  });

  it('cells의 각 항목은 date, dateStr, day, isCurrentMonth 필드를 가진다', async () => {
    const fixture = await createAndFlush();
    const cell = fixture.componentInstance['cells']()[0];
    expect(cell).toHaveProperty('date');
    expect(cell).toHaveProperty('dateStr');
    expect(cell).toHaveProperty('day');
    expect(cell).toHaveProperty('isCurrentMonth');
  });

  it('loading signal이 초기에 false이다', async () => {
    const fixture = await createAndFlush();
    expect(fixture.componentInstance['loading']()).toBe(false);
  });
});

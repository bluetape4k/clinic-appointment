import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideLocationMocks } from '@angular/common/testing';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { signal } from '@angular/core';

import { DayViewComponent } from './day-view.component';

describe('DayViewComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [DayViewComponent],
      providers: [
        provideRouter([{ path: 'calendar/day/:date', component: DayViewComponent }]),
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
    const fixture = TestBed.createComponent(DayViewComponent);
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
    expect(slots.length).toBe(20); // (18-8) * 2 = 20 슬롯
    expect(slots[0].label).toBe('08:00');
    expect(slots[slots.length - 1].label).toBe('17:30');
  });

  it('getAppointmentsForSlot은 해당 슬롯에 맞는 예약만 반환한다', async () => {
    const fixture = await createAndFlush();
    const comp = fixture.componentInstance;

    // appointmentService.appointments signal에 목 데이터 주입
    const mockAppt = { id: 1, doctorId: 2, startTime: '09:00', endTime: '09:30', status: 'CONFIRMED' } as any;
    comp['appointmentService']['_appointments'].set([mockAppt]);

    const slot = { label: '09:00', hour: 9, minute: 0 };
    const result = comp['getAppointmentsForSlot'](2, slot);
    expect(result).toHaveLength(1);
    expect(result[0].id).toBe(1);
  });

  it('getAppointmentsForSlot은 다른 의사의 예약을 반환하지 않는다', async () => {
    const fixture = await createAndFlush();
    const comp = fixture.componentInstance;

    const mockAppt = { id: 1, doctorId: 3, startTime: '09:00', endTime: '09:30', status: 'CONFIRMED' } as any;
    comp['appointmentService']['_appointments'].set([mockAppt]);

    const slot = { label: '09:00', hour: 9, minute: 0 };
    const result = comp['getAppointmentsForSlot'](2, slot);
    expect(result).toHaveLength(0);
  });

  it('statusColor는 상태별로 올바른 색상을 반환한다', async () => {
    const fixture = await createAndFlush();
    const comp = fixture.componentInstance;

    expect(comp['statusColor']('CONFIRMED')).toBe('#4CAF50');
    expect(comp['statusColor']('CANCELLED')).toBe('#F44336');
    expect(comp['statusColor']('UNKNOWN')).toBe('#9E9E9E');
  });
});

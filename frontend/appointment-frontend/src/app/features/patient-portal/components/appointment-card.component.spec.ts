import { describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';

import { AppointmentCardComponent, AppointmentCardModel } from './appointment-card.component';

const baseAppointment: AppointmentCardModel = {
  appointmentId: 42,
  fallbackTitle: '2026년 8월 20일 방문',
  productName: '피부 재생 관리',
  sessionNumber: 3,
  totalSessions: 10,
  status: 'CONFIRMED',
  startsAt: '2026-08-20T01:30:00Z',
  endsAt: '2026-08-20T02:00:00Z',
};

describe('AppointmentCardComponent', () => {
  it('상품명과 회차를 주 제목·상태 메타 순서로 표시한다', async () => {
    await TestBed.configureTestingModule({ imports: [AppointmentCardComponent] }).compileComponents();
    const fixture = TestBed.createComponent(AppointmentCardComponent);
    fixture.componentInstance.appointment = baseAppointment;
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h3')?.textContent?.trim()).toBe('피부 재생 관리');
    expect(fixture.nativeElement.querySelector('[data-session]')?.textContent?.trim()).toBe('3회차 / 10회');
    expect(fixture.nativeElement.querySelector('[data-status]')?.textContent?.trim()).toBe('확정');
  });

  it('상품명이 없으면 빈 제목 영역을 만들지 않고 fallback을 사용한다', async () => {
    await TestBed.configureTestingModule({ imports: [AppointmentCardComponent] }).compileComponents();
    const fixture = TestBed.createComponent(AppointmentCardComponent);
    fixture.componentInstance.appointment = { ...baseAppointment, productName: null, sessionNumber: 3, totalSessions: null };
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('h3')?.textContent?.trim()).toBe('2026년 8월 20일 방문');
    expect(fixture.nativeElement.querySelector('[data-session]')?.textContent?.trim()).toBe('3회차');
    expect(fixture.nativeElement.querySelector('.product-placeholder')).toBeNull();
  });

  it('상태는 색상만이 아니라 직접 텍스트로 표시한다', async () => {
    await TestBed.configureTestingModule({ imports: [AppointmentCardComponent] }).compileComponents();
    const fixture = TestBed.createComponent(AppointmentCardComponent);
    fixture.componentInstance.appointment = { ...baseAppointment, status: 'EXPIRED' };
    fixture.detectChanges();

    const status = fixture.nativeElement.querySelector('[data-status]');
    expect(status?.textContent?.trim()).toBe('만료');
    expect(status?.getAttribute('data-status')).toBe('EXPIRED');
  });

  it('한국어 포털 시간은 실행 환경 timezone과 무관하게 서울 현지 시각으로 표시한다', async () => {
    await TestBed.configureTestingModule({ imports: [AppointmentCardComponent] }).compileComponents();
    const fixture = TestBed.createComponent(AppointmentCardComponent);
    fixture.componentInstance.appointment = baseAppointment;
    fixture.detectChanges();

    expect(fixture.componentInstance.dateTimeLabel).toBe('2026년 8월 20일 10:30');
  });
});

import { describe, expect, it } from 'vitest';

import { formatAppointmentSession, resolveAppointmentTitle } from './appointment-summary';

describe('appointment summary formatter', () => {
  it('상품명이 있으면 상품명을 예약 제목으로 사용한다', () => {
    expect(resolveAppointmentTitle({ productName: '피부 재생 관리' }, '진료 예약')).toBe('피부 재생 관리');
  });

  it('상품명이 없으면 빈 제목 대신 일정 fallback을 사용한다', () => {
    expect(resolveAppointmentTitle({ productName: '  ' }, '2026년 8월 20일 방문')).toBe('2026년 8월 20일 방문');
  });

  it('전체 회차가 있으면 N회차 / M회 형식으로 표시한다', () => {
    expect(formatAppointmentSession({ sessionNumber: 3, totalSessions: 10 })).toBe('3회차 / 10회');
  });

  it('전체 회차가 없으면 N회차만 표시한다', () => {
    expect(formatAppointmentSession({ sessionNumber: 3, totalSessions: null })).toBe('3회차');
  });

  it('회차가 없으면 빈 메타 슬롯을 만들지 않는다', () => {
    expect(formatAppointmentSession({ sessionNumber: null, totalSessions: 10 })).toBeNull();
  });
});

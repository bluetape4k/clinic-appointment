import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import { AvailableSlot } from '../../../core/api/portal-api.models';
import { PortalApiClient } from '../../../core/api/portal-api-client';
import { PortalApiException } from '../../../core/api/portal-api-error';
import { PatientSlotCalendarComponent } from './patient-slot-calendar.component';

const firstSlot: AvailableSlot = {
  date: '2026-08-20',
  startTime: '10:00:00',
  endTime: '10:30:00',
  doctorId: 4,
  equipmentIds: [9],
  remainingCapacity: 1,
};

const secondSlot: AvailableSlot = {
  ...firstSlot,
  startTime: '11:00:00',
  endTime: '11:30:00',
};

function apiError(kind: 'tenant-missing' | 'forbidden' | 'conflict' | 'expired' | 'retryable'): PortalApiException {
  return new PortalApiException({
    kind,
    status: kind === 'conflict' ? 409 : kind === 'expired' ? 410 : kind === 'forbidden' ? 403 : kind === 'tenant-missing' ? 0 : 503,
    code: `SLOT_${kind.toUpperCase()}`,
    message: '슬롯 상태를 확인할 수 없습니다.',
    retryAfterSeconds: kind === 'retryable' ? 5 : null,
    correlationId: null,
  });
}

describe('환자 슬롯 선택 캘린더', () => {
  let api: { getSlots: ReturnType<typeof vi.fn>; getClinic: ReturnType<typeof vi.fn> };

  beforeEach(async () => {
    api = {
      getSlots: vi.fn(),
      getClinic: vi.fn().mockResolvedValue({
        body: { id: 3, name: '서울 메인 클리닉', timezone: 'Asia/Seoul' },
        etag: null,
        retryAfterSeconds: null,
      }),
    };
    await TestBed.configureTestingModule({
      imports: [PatientSlotCalendarComponent],
      providers: [{ provide: PortalApiClient, useValue: api }],
    }).compileComponents();
  });

  function createComponent() {
    const fixture = TestBed.createComponent(PatientSlotCalendarComponent);
    const component = fixture.componentInstance;
    component.clinicId = 3;
    component.doctorId = 4;
    component.treatmentTypeId = 5;
    component.requestedDurationMinutes = 30;
    component.date = '2026-08-20';
    component.appointmentPlanId = 77;
    component.productName = '피부 재생 관리';
    component.sessionNumber = 3;
    component.totalSessions = 10;
    component.clinicName = '서울 메인 클리닉';
    return { fixture, component };
  }

  it('tenant·clinic·의사·진료유형·날짜·시간을 함께 조회하고 선택 결과를 예약 draft 계약으로 방출한다', async () => {
    api.getSlots.mockResolvedValue({ body: [firstSlot], etag: null, retryAfterSeconds: null });
    const { fixture, component } = createComponent();
    const selections: unknown[] = [];
    component.slotSelected.subscribe(value => selections.push(value));

    await component.loadSlots();
    component.selectSlot(firstSlot);
    fixture.detectChanges();

    expect(api.getClinic).toHaveBeenCalledWith(3);
    expect(api.getSlots).toHaveBeenCalledWith(3, 4, 5, '2026-08-20', 30);
    expect(selections[0]).toMatchObject({
      clinicId: 3,
      doctorId: 4,
      treatmentTypeId: 5,
      appointmentPlanId: 77,
      clinicTimezone: 'Asia/Seoul',
      productName: '피부 재생 관리',
      sessionNumber: 3,
      totalSessions: 10,
      clinicName: '서울 메인 클리닉',
      preferredStartAt: '2026-08-20T10:00:00',
      preferredEndAt: '2026-08-20T10:30:00',
    });
    expect(fixture.nativeElement.querySelector('[data-slot-summary]')?.textContent).toContain('피부 재생 관리');
    expect(fixture.nativeElement.querySelector('[data-slot-summary]')?.textContent).toContain('3회차 / 10회');
    expect(fixture.nativeElement.querySelector('[data-slot-summary]')?.textContent).toContain('서울 메인 클리닉');
  });

  it('응답에 다른 날짜나 의사의 슬롯이 섞여도 현재 조회 scope 밖의 결과를 표시하지 않는다', async () => {
    api.getSlots.mockResolvedValue({ body: [firstSlot, { ...secondSlot, date: '2026-08-21' }, { ...secondSlot, doctorId: 99 }], etag: null, retryAfterSeconds: null });
    const { component } = createComponent();

    await component.loadSlots();

    expect(component.slots()).toEqual([firstSlot]);
    expect(component.view()).toBe('ready');
  });

  it('슬롯이 없으면 empty 상태와 날짜 변경 안내를 보여준다', async () => {
    api.getSlots.mockResolvedValue({ body: [], etag: null, retryAfterSeconds: null });
    const { fixture, component } = createComponent();

    await component.loadSlots();
    fixture.detectChanges();

    expect(component.view()).toBe('empty');
    expect(fixture.nativeElement.querySelector('[data-slot-empty]')?.textContent).toContain('다른 날짜');
  });

  it.each([
    ['tenant-missing', 'scope-error', 'data-slot-scope-error'],
    ['forbidden', 'scope-error', 'data-slot-scope-error'],
    ['conflict', 'conflict', 'data-slot-conflict'],
    ['expired', 'expired', 'data-slot-expired'],
    ['retryable', 'error', 'data-slot-error'],
  ] as const)('%s API 오류를 재시도 가능한 %s 사용자 상태로 매핑한다', async (kind, view, selector) => {
    api.getSlots.mockRejectedValue(apiError(kind));
    const { fixture, component } = createComponent();

    await component.loadSlots();
    fixture.detectChanges();

    expect(component.view()).toBe(view);
    expect(fixture.nativeElement.querySelector(`[${selector}]`)).not.toBeNull();
  });

  it('빠른 날짜 전환에서 늦게 도착한 이전 응답을 버린다', async () => {
    let resolveFirst!: (value: { body: AvailableSlot[]; etag: null; retryAfterSeconds: null }) => void;
    api.getSlots
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ body: [{ ...secondSlot, date: '2026-08-21' }], etag: null, retryAfterSeconds: null });
    const { component } = createComponent();

    const first = component.loadSlots();
    await Promise.resolve();
    component.date = '2026-08-21';
    const second = component.loadSlots();
    resolveFirst({ body: [firstSlot], etag: null, retryAfterSeconds: null });
    await Promise.all([first, second]);

    expect(component.slots()).toEqual([{ ...secondSlot, date: '2026-08-21' }]);
    expect(component.view()).toBe('ready');
  });

  it('잔여 정원이 사라진 슬롯을 선택하면 conflict 상태로 방출하지 않는다', async () => {
    const unavailable = { ...firstSlot, remainingCapacity: 0 };
    api.getSlots.mockResolvedValue({ body: [unavailable], etag: null, retryAfterSeconds: null });
    const { component } = createComponent();
    const emit = vi.spyOn(component.slotSelected, 'emit');

    await component.loadSlots();
    component.selectSlot(unavailable);

    expect(component.view()).toBe('conflict');
    expect(emit).not.toHaveBeenCalled();
  });

  it('현재 슬롯 목록에서 잔여 정원이 사라진 stale 객체를 선택하지 않는다', async () => {
    api.getSlots.mockResolvedValue({ body: [firstSlot], etag: null, retryAfterSeconds: null });
    const { component } = createComponent();
    const emit = vi.spyOn(component.slotSelected, 'emit');

    await component.loadSlots();
    component.slots.set([{ ...firstSlot, remainingCapacity: 0 }]);
    component.selectSlot(firstSlot);

    expect(component.view()).toBe('conflict');
    expect(emit).not.toHaveBeenCalled();
  });

  it('조회 후 병원·진료 유형·시간 조건이 바뀌면 이전 슬롯을 새 scope로 방출하지 않는다', async () => {
    api.getSlots.mockResolvedValue({ body: [firstSlot], etag: null, retryAfterSeconds: null });
    const { component } = createComponent();
    const emit = vi.spyOn(component.slotSelected, 'emit');

    await component.loadSlots();
    component.treatmentTypeId = 99;
    component.selectSlot(firstSlot);

    expect(component.view()).toBe('conflict');
    expect(emit).not.toHaveBeenCalled();
  });

  it('조회 기준이 바뀌어 선택이 무효화되면 부모 draft에 선택 해제를 알린다', async () => {
    api.getSlots.mockResolvedValue({ body: [firstSlot], etag: null, retryAfterSeconds: null });
    const { component } = createComponent();
    const cleared = vi.spyOn(component.slotSelectionCleared, 'emit');

    await component.loadSlots();
    component.selectSlot(firstSlot);
    component.treatmentTypeId = 99;
    component.selectSlot(firstSlot);

    expect(cleared).toHaveBeenCalledOnce();
    expect(component.selectedSlot()).toBeNull();
  });

  it('clinic·resource가 없는 404를 scope 오류로 안내한다', async () => {
    api.getSlots.mockRejectedValue(new PortalApiException({
      kind: 'unknown',
      status: 404,
      code: 'RESOURCE_NOT_FOUND',
      message: '요청한 자원을 찾을 수 없습니다.',
      retryAfterSeconds: null,
      correlationId: null,
    }));
    const { fixture, component } = createComponent();

    await component.loadSlots();
    fixture.detectChanges();

    expect(component.view()).toBe('scope-error');
    expect(fixture.nativeElement.querySelector('[data-slot-scope-error]')).not.toBeNull();
  });

  it('병원 metadata의 timezone이 없거나 유효하지 않으면 scope 오류로 중단한다', async () => {
    api.getClinic.mockResolvedValueOnce({
      body: { id: 3, name: '서울 메인 클리닉', timezone: 'Invalid/Zone' },
      etag: null,
      retryAfterSeconds: null,
    });
    api.getSlots.mockResolvedValue({ body: [firstSlot], etag: null, retryAfterSeconds: null });
    const { fixture, component } = createComponent();

    await component.loadSlots();
    fixture.detectChanges();

    expect(component.view()).toBe('scope-error');
    expect(api.getSlots).not.toHaveBeenCalled();
  });

  it('슬롯 목록은 roving tabindex와 방향키로 키보드 이동을 제공한다', async () => {
    api.getSlots.mockResolvedValue({ body: [firstSlot, secondSlot], etag: null, retryAfterSeconds: null });
    const { fixture, component } = createComponent();

    await component.loadSlots();
    fixture.detectChanges();
    const buttons = fixture.nativeElement.querySelectorAll('.slot-option') as NodeListOf<HTMLButtonElement>;
    component.onSlotKeydown(new KeyboardEvent('keydown', { key: 'ArrowRight' }), 0);
    fixture.detectChanges();

    expect(buttons[0].getAttribute('tabindex')).toBe('-1');
    expect(buttons[1].getAttribute('tabindex')).toBe('0');
    expect(component.focusedSlotIndex()).toBe(1);
  });
});

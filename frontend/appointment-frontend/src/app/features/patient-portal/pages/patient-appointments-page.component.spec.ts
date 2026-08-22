import { signal } from '@angular/core';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi, beforeEach } from 'vitest';

import { PortalApiException } from '../../../core/api/portal-api-error';
import { PortalApiClient } from '../../../core/api/portal-api-client';
import { SlotSelection } from '../components/patient-slot-calendar.component';
import { AppointmentCommitmentFacade, AppointmentCommitmentState } from '../appointment-commitment.facade';
import { PatientAppointmentsPageComponent } from './patient-appointments-page.component';

describe('환자 예약 현황 페이지', () => {
  let state: ReturnType<typeof signal<AppointmentCommitmentState>>;
  let facade: {
    state: ReturnType<typeof signal<AppointmentCommitmentState>>;
    cancelAppointment: ReturnType<typeof vi.fn>;
    loadCommitment: ReturnType<typeof vi.fn>;
    requestAppointment: ReturnType<typeof vi.fn>;
    acceptProposal: ReturnType<typeof vi.fn>;
    declineProposal: ReturnType<typeof vi.fn>;
  };
  const routeSnapshot = { queryParamMap: convertToParamMap({}) };

  const apiError = (status: number, kind?: 'tenant-missing' | 'transport' | 'unauthorized' | 'forbidden' | 'conflict' | 'expired' | 'precondition' | 'retryable' | 'unknown'): PortalApiException => new PortalApiException({
    kind: kind ?? (status === 503 ? 'retryable' : status === 412 ? 'conflict' : status === 422 ? 'precondition' : 'unknown'),
    status,
    code: `HTTP_${status}`,
    message: '예약 요청을 처리하지 못했습니다.',
    retryAfterSeconds: null,
    correlationId: null,
  });

  beforeEach(async () => {
    routeSnapshot.queryParamMap = convertToParamMap({});
    state = signal<AppointmentCommitmentState>({
      view: 'confirmed',
      appointmentId: 42,
      proposalId: 11,
      status: 'CONFIRMED',
      commitment: {
        appointmentId: 42,
        commitmentId: 9,
        status: 'CONFIRMED',
        version: 4,
        currentProposal: {
          proposalId: 11,
          revision: 1,
          startsAt: '2026-08-20T01:30:00Z',
          endsAt: '2026-08-20T02:00:00Z',
          expiresAt: '2026-08-20T02:30:00Z',
          expired: false,
          representativeTreatmentName: '피부 재생 관리',
          policySnapshot: { snapshotId: 1, snapshotHash: 'hash', tenantGeneration: 1, clinicGeneration: 1, sourceVersions: {} },
        },
        confirmedProposalId: 11,
        effectivePolicySnapshotId: 1,
      },
      proposal: null,
      etag: '"4"',
      busy: false,
      notice: null,
      error: null,
    });
    facade = {
      state,
      cancelAppointment: vi.fn().mockResolvedValue(true),
      loadCommitment: vi.fn().mockResolvedValue(undefined),
      requestAppointment: vi.fn().mockResolvedValue(undefined),
      acceptProposal: vi.fn().mockResolvedValue(undefined),
      declineProposal: vi.fn().mockResolvedValue(undefined),
    };
    await TestBed.configureTestingModule({
      imports: [PatientAppointmentsPageComponent],
      providers: [
        { provide: AppointmentCommitmentFacade, useValue: facade },
        { provide: PortalApiClient, useValue: {
          getSlots: vi.fn().mockResolvedValue({ body: [], etag: null, retryAfterSeconds: null }),
          clearCancellationHistoryCache: vi.fn(),
        } },
        { provide: ActivatedRoute, useValue: { snapshot: routeSnapshot } },
      ],
    }).compileComponents();
  });

  it('취소 확인은 code-only 요청을 보내고 raw code를 option label로 노출하지 않는다', async () => {
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    fixture.detectChanges();

    page.beginCancellation();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-cancel-confirmation]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('option')?.textContent?.trim()).toBe('개인 사유');
    expect(fixture.nativeElement.querySelector('[data-cancel-confirmation]')?.textContent).not.toContain('CUSTOMER_REQUEST');

    await page.confirmCancellation();

    expect(facade.cancelAppointment).toHaveBeenCalledWith(
      { reasonCode: 'CUSTOMER_REQUEST' },
      expect.stringMatching(/^portal-cancel-42-/),
    );
    expect(page.cancellationPending()).toBe(false);
  });

  it('412 뒤에는 확인 상태를 닫아 새 명시적 확인에서 새 key를 만든다', async () => {
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    facade.cancelAppointment.mockRejectedValueOnce(apiError(412));

    page.beginCancellation();
    const firstKey = (page as unknown as { cancellationIntentKey: string }).cancellationIntentKey;
    await page.confirmCancellation();
    page.beginCancellation();
    const secondKey = (page as unknown as { cancellationIntentKey: string }).cancellationIntentKey;

    expect(firstKey).not.toBe(secondKey);
    expect(page.cancellationPending()).toBe(true);
  });

  it('query appointment id가 있으면 새 페이지 상태에서 commitment를 다시 조회한다', async () => {
    state.set({
      view: 'idle',
      appointmentId: null,
      proposalId: null,
      status: null,
      commitment: null,
      proposal: null,
      etag: null,
      busy: false,
      notice: null,
      error: null,
    });
    routeSnapshot.queryParamMap = convertToParamMap({ appointmentId: '77' });

    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    fixture.detectChanges();
    await fixture.whenStable();

    expect(facade.loadCommitment).toHaveBeenCalledWith(77);
  });

  it('새로운 요청 intent는 terminal 오류 뒤 재시도에서 새 idempotency key를 만든다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 7;
    facade.requestAppointment.mockRejectedValue(apiError(422));
    fixture.detectChanges();

    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);
    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);

    expect(facade.requestAppointment.mock.calls[0][1]).not.toBe(facade.requestAppointment.mock.calls[1][1]);
  });

  it('transport 오류 뒤 재시도에서는 같은 intent key를 유지한다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 7;
    facade.requestAppointment.mockRejectedValue(apiError(503));
    fixture.detectChanges();

    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);
    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);

    expect(facade.requestAppointment.mock.calls[0][1]).toBe(facade.requestAppointment.mock.calls[1][1]);
  });

  it('명시적 transport 오류(status 0) 뒤에는 같은 intent key를 유지한다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 7;
    facade.requestAppointment.mockRejectedValue(apiError(0, 'transport'));
    fixture.detectChanges();

    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);
    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);

    expect(facade.requestAppointment.mock.calls[0][1]).toBe(facade.requestAppointment.mock.calls[1][1]);
  });

  it.each([408, 429, 500, 502, 504])('503가 아닌 HTTP %s 뒤에는 idempotency key를 회전한다', async status => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 7;
    facade.requestAppointment.mockRejectedValue(apiError(status));
    fixture.detectChanges();

    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);
    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);

    expect(facade.requestAppointment.mock.calls[0][1]).not.toBe(facade.requestAppointment.mock.calls[1][1]);
  });

  it('tenant scope 누락(status 0)은 transport처럼 key를 재사용하지 않는다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 7;
    facade.requestAppointment.mockRejectedValue(apiError(0, 'tenant-missing'));
    fixture.detectChanges();

    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);
    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);

    expect(facade.requestAppointment.mock.calls[0][1]).not.toBe(facade.requestAppointment.mock.calls[1][1]);
  });

  it('취소 503은 확인 상태와 같은 key를 유지하고 terminal 오류는 intent를 폐기한다', async () => {
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    facade.cancelAppointment.mockRejectedValueOnce(apiError(503)).mockRejectedValueOnce(apiError(422));

    page.beginCancellation();
    const retryKey = (page as unknown as { cancellationIntentKey: string }).cancellationIntentKey;
    await expect(page.confirmCancellation()).rejects.toBeInstanceOf(PortalApiException);
    expect(page.cancellationPending()).toBe(true);
    expect((page as unknown as { cancellationIntentKey: string }).cancellationIntentKey).toBe(retryKey);

    await expect(page.confirmCancellation()).rejects.toBeInstanceOf(PortalApiException);
    expect(page.cancellationPending()).toBe(false);
    expect((page as unknown as { cancellationIntentKey: string | null }).cancellationIntentKey).toBeNull();
  });

  it('동시 mutation의 busy 무시는 성공으로 간주하지 않아 첫 요청의 intent key를 보존한다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 7;
    let rejectFirst!: (error: unknown) => void;
    facade.requestAppointment
      .mockReturnValueOnce(new Promise<boolean>((_, reject) => { rejectFirst = reject; }))
      .mockResolvedValueOnce(false)
      .mockRejectedValueOnce(apiError(503));
    fixture.detectChanges();

    const first = page.requestAppointment();
    await page.requestAppointment();
    const firstKey = facade.requestAppointment.mock.calls[0][1];
    rejectFirst(apiError(503));
    await expect(first).rejects.toBeInstanceOf(PortalApiException);
    await expect(page.requestAppointment()).rejects.toBeInstanceOf(PortalApiException);

    expect(facade.requestAppointment.mock.calls[2][1]).toBe(firstKey);
  });

  it('복구가 인증 또는 서버 오류면 새 예약 폼 대신 명시적 재시도를 보여준다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    routeSnapshot.queryParamMap = convertToParamMap({ appointmentId: '77' });
    facade.loadCommitment.mockRejectedValue(apiError(503));

    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(page.restoreBlocked()).toBe(true);
    expect(fixture.nativeElement.querySelector('[data-restore-error]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.request-form')).toBeNull();
  });

  it('복구가 404면 서버에 예약이 없는 것으로 보고 새 예약 폼을 허용한다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    routeSnapshot.queryParamMap = convertToParamMap({ appointmentId: '77' });
    facade.loadCommitment.mockRejectedValue(apiError(404));

    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(page.restoreBlocked()).toBe(false);
    expect(fixture.nativeElement.querySelector('.request-form')).not.toBeNull();
  });

  it('새 예약 흐름에 tenant-scoped 슬롯 선택 캘린더를 표시한다', () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });

    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-slot-calendar]')).not.toBeNull();
  });

  it('슬롯 선택 시간을 appointment request payload의 시작·종료 값으로 그대로 반영한다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 77;
    page.draft.evidenceAuthority = 'consent:clinic-a';
    page.draft.evidenceId = '01J1M6Y6XRK8N0W2M3P4Q5R6S7';
    const selection: SlotSelection = {
      slot: { date: '2026-08-20', startTime: '10:00:00', endTime: '10:30:00', doctorId: 4, equipmentIds: [], remainingCapacity: 1 },
      clinicId: 3,
      doctorId: 4,
      treatmentTypeId: 5,
      requestedDurationMinutes: 30,
      appointmentPlanId: 77,
      clinicTimezone: 'Asia/Seoul',
      productName: '피부 재생 관리',
      sessionNumber: 3,
      totalSessions: 10,
      clinicName: '서울 메인 클리닉',
      preferredStartAt: '2026-08-20T10:00:00',
      preferredEndAt: '2026-08-20T10:30:00',
    };

    page.applySlotSelection(selection);
    await page.requestAppointment();

    expect(facade.requestAppointment).toHaveBeenCalledWith(
      expect.objectContaining({
        appointmentPlanId: 77,
        preferredStartAt: '2026-08-20T01:00:00.000Z',
        preferredEndAt: '2026-08-20T01:30:00.000Z',
      }),
      expect.stringMatching(/^portal-request-/),
    );
  });

  it('clinic timezone이 브라우저 timezone과 달라도 local 슬롯을 UTC로 정확히 변환한다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    routeSnapshot.queryParamMap = convertToParamMap({});
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 77;
    page.draft.evidenceAuthority = 'consent:clinic-a';
    page.draft.evidenceId = '01J1M6Y6XRK8N0W2M3P4Q5R6S7';
    page.applySlotSelection({
      slot: { date: '2026-08-20', startTime: '10:00:00', endTime: '10:30:00', doctorId: 4, equipmentIds: [], remainingCapacity: 1 },
      clinicId: 3, doctorId: 4, treatmentTypeId: 5, requestedDurationMinutes: 30,
      appointmentPlanId: 77, clinicTimezone: 'America/New_York', productName: null, sessionNumber: null, totalSessions: null,
      clinicName: '뉴욕 클리닉', preferredStartAt: '2026-08-20T10:00:00', preferredEndAt: '2026-08-20T10:30:00',
    });
    await page.requestAppointment();

    expect(facade.requestAppointment).toHaveBeenCalledWith(
      expect.objectContaining({ preferredStartAt: '2026-08-20T14:00:00.000Z', preferredEndAt: '2026-08-20T14:30:00.000Z' }),
      expect.stringMatching(/^portal-request-/),
    );
    fixture.destroy();
  });

  it('슬롯이 무효화되면 stale draft 시간을 지우고 새 슬롯 선택 전 요청을 막는다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 77;
    page.draft.evidenceAuthority = 'consent:clinic-a';
    page.draft.evidenceId = '01J1M6Y6XRK8N0W2M3P4Q5R6S7';
    page.applySlotSelection({
      slot: { date: '2026-08-20', startTime: '10:00:00', endTime: '10:30:00', doctorId: 4, equipmentIds: [], remainingCapacity: 1 },
      clinicId: 3, doctorId: 4, treatmentTypeId: 5, requestedDurationMinutes: 30,
      appointmentPlanId: 77, clinicTimezone: 'Asia/Seoul', productName: null, sessionNumber: null, totalSessions: null,
      clinicName: '서울 메인 클리닉', preferredStartAt: '2026-08-20T10:00:00', preferredEndAt: '2026-08-20T10:30:00',
    });

    page.clearSlotSelection();
    await page.requestAppointment();

    expect(page.draft.preferredStartAt).toBe('');
    expect(page.draft.preferredEndAt).toBe('');
    expect(facade.requestAppointment).not.toHaveBeenCalled();
    expect(page.slotSelectionError()).toContain('최신 가용 시간');
    fixture.destroy();
  });

  it('선택 후 예약 계획 ID가 바뀌면 기존 슬롯을 즉시 무효화한다', () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 77;
    page.applySlotSelection({
      slot: { date: '2026-08-20', startTime: '10:00:00', endTime: '10:30:00', doctorId: 4, equipmentIds: [], remainingCapacity: 1 },
      clinicId: 3, doctorId: 4, treatmentTypeId: 5, requestedDurationMinutes: 30,
      appointmentPlanId: 77, clinicTimezone: 'Asia/Seoul', productName: null, sessionNumber: null, totalSessions: null,
      clinicName: '서울 메인 클리닉', preferredStartAt: '2026-08-20T10:00:00', preferredEndAt: '2026-08-20T10:30:00',
    });

    page.onAppointmentPlanIdChange(88);

    expect(page.selectedSlotSelection()).toBeNull();
    expect(page.draft.preferredStartAt).toBe('');
    expect(page.draft.preferredEndAt).toBe('');
    fixture.destroy();
  });

  it('scope-locked 페이지는 다른 병원 범위의 슬롯을 예약 요청에 연결하지 않는다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    routeSnapshot.queryParamMap = convertToParamMap({ clinicId: '3', doctorId: '4', treatmentTypeId: '5', date: '2026-08-20' });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 77;
    page.applySlotSelection({
      slot: { date: '2026-08-20', startTime: '10:00:00', endTime: '10:30:00', doctorId: 99, equipmentIds: [], remainingCapacity: 1 },
      clinicId: 9, doctorId: 99, treatmentTypeId: 5, requestedDurationMinutes: 30,
      appointmentPlanId: 77, clinicTimezone: 'Asia/Seoul', productName: null, sessionNumber: null, totalSessions: null,
      clinicName: '다른 병원', preferredStartAt: '2026-08-20T10:00:00', preferredEndAt: '2026-08-20T10:30:00',
    });

    await page.requestAppointment();

    expect(page.selectedSlotSelection()).toBeNull();
    expect(facade.requestAppointment).not.toHaveBeenCalled();
    expect(page.slotSelectionError()).toContain('최신 가용 시간');
    fixture.destroy();
  });

  it('DST gap의 local 시간은 UTC payload로 추정하지 않고 예약 요청을 막는다', async () => {
    state.set({
      view: 'idle', appointmentId: null, proposalId: null, status: null, commitment: null, proposal: null,
      etag: null, busy: false, notice: null, error: null,
    });
    const fixture = TestBed.createComponent(PatientAppointmentsPageComponent);
    const page = fixture.componentInstance;
    page.draft.appointmentPlanId = 77;
    page.draft.evidenceAuthority = 'consent:clinic-a';
    page.draft.evidenceId = '01J1M6Y6XRK8N0W2M3P4Q5R6S7';
    page.applySlotSelection({
      slot: { date: '2026-03-08', startTime: '02:30:00', endTime: '03:00:00', doctorId: 4, equipmentIds: [], remainingCapacity: 1 },
      clinicId: 3, doctorId: 4, treatmentTypeId: 5, requestedDurationMinutes: 30,
      appointmentPlanId: 77, clinicTimezone: 'America/New_York', productName: null, sessionNumber: null, totalSessions: null,
      clinicName: '뉴욕 클리닉', preferredStartAt: '2026-03-08T02:30:00', preferredEndAt: '2026-03-08T03:00:00',
    });

    await page.requestAppointment();

    expect(facade.requestAppointment).not.toHaveBeenCalled();
    expect(page.slotSelectionError()).toContain('유효하지 않습니다');
    fixture.destroy();
  });
});

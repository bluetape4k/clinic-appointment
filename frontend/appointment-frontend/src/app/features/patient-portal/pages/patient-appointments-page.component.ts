import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { CancellationReasonCode } from '../../../core/api/portal-api.models';
import { PortalApiException } from '../../../core/api/portal-api-error';
import { TenantContextService } from '../../../core/api/tenant-context.service';
import { AppointmentCardComponent, AppointmentCardModel } from '../components/appointment-card.component';
import { PatientCancellationHistoryComponent } from '../components/patient-cancellation-history.component';
import { PatientSlotCalendarComponent, SlotSelection } from '../components/patient-slot-calendar.component';
import { AppointmentCommitmentFacade } from '../appointment-commitment.facade';

interface AppointmentDraft {
  appointmentPlanId: number | null;
  preferredStartAt: string;
  preferredEndAt: string;
  evidenceAuthority: string;
  evidenceId: string;
  productName: string;
  sessionNumber: number | null;
  totalSessions: number | null;
  clinicTimezone: string;
}

const DEFAULT_CLINIC_TIME_ZONE = 'UTC';

@Component({
  selector: 'app-patient-appointments-page',
  standalone: true,
  imports: [FormsModule, AppointmentCardComponent, PatientCancellationHistoryComponent, PatientSlotCalendarComponent],
  template: `
    <section class="portal-page" aria-labelledby="appointments-title">
      <p class="portal-eyebrow">APPOINTMENTS</p>
      <h2 id="appointments-title">예약 현황</h2>
      <p class="portal-page-intro">요청 중인 예약과 확정된 방문 일정을 확인하세요.</p>

      @if (appointment; as currentAppointment) {
        <app-appointment-card [appointment]="currentAppointment" (cancelRequested)="beginCancellation()" />
        @if (canCancel) {
          @if (!cancellationPending()) {
            <button type="button" class="cancel-button" data-cancel-open (click)="beginCancellation()">예약 취소하기</button>
          } @else {
            <section class="cancel-confirmation" data-cancel-confirmation aria-labelledby="cancel-title">
              <h3 id="cancel-title">예약을 취소할까요?</h3>
              <p>취소 사유를 선택하면 예약이 취소되고 현재 상태가 기록됩니다.</p>
              <label>취소 사유
                <select name="cancellationReasonCode" [(ngModel)]="cancellationReasonCode">
                  @for (option of cancellationReasonOptions; track option.code) {
                    <option [value]="option.code">{{ option.label }}</option>
                  }
                </select>
              </label>
              <div class="action-row">
                <button type="button" data-cancel-confirm (click)="confirmCancellation()" [disabled]="facade.state().busy">취소 확정</button>
                <button type="button" class="secondary-button" data-cancel-dismiss (click)="dismissCancellation()" [disabled]="facade.state().busy">돌아가기</button>
              </div>
            </section>
          }
        }
      } @else if (requesting) {
        <section class="requested-state" data-status-step="REQUESTED" aria-live="polite">
          <span class="portal-eyebrow">REQUESTED</span>
          <h3>예약 요청을 보내는 중입니다</h3>
          <p>병원에 가능한 시간을 확인하고 있습니다. 잠시만 기다려 주세요.</p>
        </section>
      } @else if (restoreBlocked()) {
        <section class="restore-error" data-restore-error role="alert" aria-labelledby="restore-error-title">
          <h3 id="restore-error-title">예약 상태를 불러오지 못했습니다</h3>
          <p>인증 상태나 서버 연결을 확인한 뒤 다시 시도해 주세요.</p>
          <button type="button" data-restore-retry (click)="retryRestore()">다시 불러오기</button>
        </section>
      } @else {
        <app-patient-slot-calendar
          [clinicId]="slotContext.clinicId"
          [doctorId]="slotContext.doctorId"
          [treatmentTypeId]="slotContext.treatmentTypeId"
          [date]="slotContext.date"
          [appointmentPlanId]="draft.appointmentPlanId"
          [productName]="draft.productName"
          [sessionNumber]="draft.sessionNumber"
          [totalSessions]="draft.totalSessions"
          [clinicName]="slotContext.clinicName"
          [scopeLocked]="slotContext.scopeLocked"
          (slotSelected)="applySlotSelection($event)"
          (slotSelectionCleared)="clearSlotSelection()"
        />
        <form class="request-form" (ngSubmit)="requestAppointment()" aria-labelledby="request-title">
          <h3 id="request-title">새 예약 요청</h3>
          <p class="form-help">슬롯을 선택한 뒤 예약 계획과 동의 증빙을 입력하면 병원에 제안 생성을 요청합니다.</p>
          <label>예약 계획 ID <input name="appointmentPlanId" type="number" min="1" required [(ngModel)]="draft.appointmentPlanId" (ngModelChange)="onAppointmentPlanIdChange($event)" /></label>
          <label>상품명 (선택) <input name="productName" [(ngModel)]="draft.productName" placeholder="피부 재생 관리" /></label>
          <div class="form-grid">
            <label>회차 (선택) <input name="sessionNumber" type="number" min="1" [(ngModel)]="draft.sessionNumber" /></label>
            <label>전체 회차 (선택) <input name="totalSessions" type="number" min="1" [(ngModel)]="draft.totalSessions" /></label>
          </div>
          <div class="form-grid">
            <label>희망 시작 <input name="preferredStartAt" type="datetime-local" required [(ngModel)]="draft.preferredStartAt" [readonly]="selectedSlotSelection() !== null" /></label>
            <label>희망 종료 <input name="preferredEndAt" type="datetime-local" required [(ngModel)]="draft.preferredEndAt" [readonly]="selectedSlotSelection() !== null" /></label>
          </div>
          @if (slotSelectionError(); as selectionError) {
            <p class="portal-notice" data-slot-selection-error role="alert">{{ selectionError }}</p>
          }
          <label>동의 권위 <input name="evidenceAuthority" required [(ngModel)]="draft.evidenceAuthority" placeholder="consent:clinic-a" /></label>
          <label>동의 증빙 ID <input name="evidenceId" required minlength="20" [(ngModel)]="draft.evidenceId" /></label>
          <button type="submit" [disabled]="facade.state().busy">예약 요청 보내기</button>
        </form>
      }

      @if (facade.state().status === 'PROPOSED') {
        <div class="proposal-actions" aria-labelledby="proposal-title">
          <h3 id="proposal-title">제안 확인</h3>
          <p>{{ proposalLabel }}</p>
          <div class="action-row">
            <button type="button" (click)="acceptProposal()" [disabled]="facade.state().busy">제안 동의</button>
            <button type="button" class="secondary-button" (click)="declineProposal()" [disabled]="facade.state().busy">제안 거절</button>
          </div>
        </div>
      }

      @if (facade.state().notice; as notice) {
        <p class="portal-notice" role="status" aria-live="polite">{{ notice }}</p>
      }

      <app-patient-cancellation-history />
    </section>
  `,
  styles: [`
    :host { display: block; }
    .portal-page { max-width: 760px; }
    .portal-eyebrow { margin: 0 0 8px; color: var(--portal-muted); font-size: .75rem; font-weight: 700; letter-spacing: .12em; }
    h2 { margin: 0; font-size: clamp(1.35rem, 3vw, 1.75rem); letter-spacing: -.03em; }
    .portal-page-intro { margin: 8px 0 0; color: var(--portal-muted); }
    .request-form, .proposal-actions { display: grid; gap: 14px; margin-top: 24px; padding: 20px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); scroll-margin-block: 24px; scroll-padding-block-end: calc(24px + env(safe-area-inset-bottom) + var(--mobile-keyboard-inset, 0px)); }
    .cancel-confirmation, .requested-state, .restore-error { display: grid; gap: 14px; margin-top: 20px; padding: 20px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    h3 { margin: 0; font-size: 1.1rem; }
    .form-help { margin: -6px 0 2px; color: var(--portal-muted); }
    label { display: grid; gap: 6px; color: var(--portal-muted); font-size: .875rem; }
    input, select { min-height: 44px; border: 1px solid var(--portal-line); background: var(--portal-surface); color: var(--portal-ink); padding: 8px 10px; font: inherit; }
    input:focus-visible, select:focus-visible, button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 2px; }
    .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
    button { justify-self: start; min-height: 44px; border: 1px solid var(--portal-ink); background: var(--portal-ink); color: var(--portal-surface-raised); padding: 8px 14px; cursor: pointer; font: inherit; touch-action: manipulation; }
    button:disabled { cursor: wait; opacity: .6; }
    .action-row { display: flex; flex-wrap: wrap; gap: 10px; }
    .secondary-button { background: transparent; color: var(--portal-ink); }
    .cancel-button { margin-top: 20px; }
    .portal-notice { margin-top: 16px; padding: 12px 14px; border-left: 3px solid var(--portal-focus); background: var(--portal-surface-raised); }
    @media (max-width: 560px) { .form-grid { grid-template-columns: 1fr; } .action-row { display: grid; grid-template-columns: 1fr; } .action-row button { width: 100%; } }
  `],
})
export class PatientAppointmentsPageComponent implements OnInit {
  readonly facade = inject(AppointmentCommitmentFacade);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly router = inject(Router, { optional: true });
  private readonly tenant = inject(TenantContextService, { optional: true });
  readonly slotContext = {
    clinicId: this.positiveId(this.route?.snapshot.queryParamMap.get('clinicId')),
    doctorId: this.positiveId(this.route?.snapshot.queryParamMap.get('doctorId')),
    treatmentTypeId: this.positiveId(this.route?.snapshot.queryParamMap.get('treatmentTypeId')),
    date: this.route?.snapshot.queryParamMap.get('date') || '',
    clinicName: this.route?.snapshot.queryParamMap.get('clinicName') || null,
    scopeLocked: Boolean(
      this.positiveId(this.route?.snapshot.queryParamMap.get('clinicId'))
      && this.positiveId(this.route?.snapshot.queryParamMap.get('doctorId'))
      && this.positiveId(this.route?.snapshot.queryParamMap.get('treatmentTypeId')),
    ),
  };

  readonly cancellationReasonOptions: ReadonlyArray<{ code: CancellationReasonCode; label: string }> = [
    { code: 'CUSTOMER_REQUEST', label: '개인 사유' },
    { code: 'REFUND', label: '환불 관련' },
    { code: 'EQUIPMENT_FAILURE', label: '장비 사정' },
    { code: 'CLINIC_REQUEST', label: '병원 안내' },
  ];

  cancellationReasonCode: CancellationReasonCode = 'CUSTOMER_REQUEST';
  readonly cancellationPending = signal(false);
  readonly restoreBlocked = signal(false);
  readonly slotSelectionError = signal<string | null>(null);
  readonly selectedSlotSelection = signal<SlotSelection | null>(null);
  private slotSelectionRequired = this.slotContext.scopeLocked;
  private selectedSlotPlanId: number | null = null;
  private cancellationIntentKey: string | null = null;
  private readonly intentKeys = new Map<string, { key: string; fingerprint: string }>();

  draft: AppointmentDraft = {
    appointmentPlanId: null,
    preferredStartAt: '',
    preferredEndAt: '',
    evidenceAuthority: '',
    evidenceId: '',
    productName: '',
    sessionNumber: null,
    totalSessions: null,
    clinicTimezone: DEFAULT_CLINIC_TIME_ZONE,
  };

  ngOnInit(): void {
    void this.restoreCommitment();
  }

  get appointment(): AppointmentCardModel | null {
    const state = this.facade.state();
    if (!state.appointmentId || !state.status) return null;
    const proposal = state.commitment?.currentProposal;
    const createdProposal = state.proposal;
    return {
      appointmentId: state.appointmentId,
      fallbackTitle: proposal?.representativeTreatmentName || '예약 제안',
      productName: (proposal?.productName ?? createdProposal?.productName ?? this.draft.productName) || null,
      sessionNumber: proposal?.sessionNumber ?? createdProposal?.sessionNumber ?? this.draft.sessionNumber,
      totalSessions: proposal?.totalSessions ?? createdProposal?.totalSessions ?? this.draft.totalSessions,
      clinicDisplayName: proposal?.clinicDisplayName ?? createdProposal?.clinicDisplayName,
      status: state.status,
      startsAt: proposal?.startsAt || this.toIso(this.draft.preferredStartAt),
      endsAt: proposal?.endsAt || this.toIso(this.draft.preferredEndAt),
    };
  }

  get canCancel(): boolean {
    const status = this.facade.state().status;
    return status === 'PROPOSED' || status === 'HELD' || status === 'CONFIRMED';
  }

  get requesting(): boolean {
    return this.facade.state().view === 'submitting' && !this.appointment;
  }

  async requestAppointment(): Promise<void> {
    if (!this.draft.appointmentPlanId) {
      if (this.slotSelectionRequired) this.slotSelectionError.set('예약 계획 ID를 입력한 뒤 슬롯을 선택해 주세요.');
      return;
    }
    this.slotSelectionError.set(null);
    const selectedSlot = this.selectedSlotSelection();
    const scopeMismatch = this.slotContext.scopeLocked && selectedSlot != null && (
      selectedSlot.clinicId !== this.slotContext.clinicId
      || selectedSlot.doctorId !== this.slotContext.doctorId
      || selectedSlot.treatmentTypeId !== this.slotContext.treatmentTypeId
    );
    if (this.slotSelectionRequired && (
      selectedSlot == null
      || this.selectedSlotPlanId !== this.draft.appointmentPlanId
      || scopeMismatch
    )) {
      this.slotSelectionError.set('최신 가용 시간을 다시 조회해 슬롯을 선택해 주세요.');
      return;
    }
    const preferredStartAt = this.toIso(this.draft.preferredStartAt);
    const preferredEndAt = this.toIso(this.draft.preferredEndAt);
    if (this.slotSelectionRequired && (!preferredStartAt || !preferredEndAt || preferredEndAt <= preferredStartAt)) {
      this.slotSelectionError.set('선택한 시간이 병원 timezone에서 유효하지 않습니다. 최신 슬롯을 다시 조회해 주세요.');
      return;
    }
    const action = 'request';
    const body = {
      appointmentPlanId: this.draft.appointmentPlanId,
      preferredStartAt,
      preferredEndAt,
      evidence: { evidenceAuthority: this.draft.evidenceAuthority, evidenceId: this.draft.evidenceId },
    };
    try {
      const applied = await this.facade.requestAppointment(body, this.intentKey(action, JSON.stringify(body)));
      if (!applied) return;
      this.intentKeys.delete(action);
      const appointmentId = this.facade.state().appointmentId;
      if (appointmentId) {
        this.rememberAppointment(appointmentId);
        void this.router?.navigate(['/portal/appointments'], {
          queryParams: { appointmentId },
          replaceUrl: true,
        });
      }
    } catch (error) {
      if (!this.shouldReuseIntentKey(error)) this.intentKeys.delete(action);
      throw error;
    }
  }

  applySlotSelection(selection: SlotSelection): void {
    if (!isValidTimeZone(selection.clinicTimezone)) {
      this.clearSlotSelection();
      this.slotSelectionError.set('병원의 권위 timezone 정보를 확인할 수 없습니다. 최신 슬롯을 다시 조회해 주세요.');
      return;
    }
    if (this.slotContext.scopeLocked && (
      selection.clinicId !== this.slotContext.clinicId
      || selection.doctorId !== this.slotContext.doctorId
      || selection.treatmentTypeId !== this.slotContext.treatmentTypeId
    )) {
      this.clearSlotSelection();
      this.slotSelectionError.set('선택한 슬롯이 현재 병원 범위와 일치하지 않습니다. 최신 슬롯을 다시 조회해 주세요.');
      return;
    }
    const boundPlanId = this.draft.appointmentPlanId ?? selection.appointmentPlanId;
    this.selectedSlotSelection.set(selection);
    this.selectedSlotPlanId = selection.appointmentPlanId ?? boundPlanId;
    this.slotSelectionRequired = true;
    this.slotSelectionError.set(null);
    this.draft.clinicTimezone = selection.clinicTimezone;
    this.draft.preferredStartAt = selection.preferredStartAt.slice(0, 16);
    this.draft.preferredEndAt = selection.preferredEndAt.slice(0, 16);
    if (this.draft.appointmentPlanId == null && selection.appointmentPlanId != null) {
      this.draft.appointmentPlanId = selection.appointmentPlanId;
    }
    if (!this.draft.productName && selection.productName) this.draft.productName = selection.productName;
    if (this.draft.sessionNumber == null && selection.sessionNumber != null) this.draft.sessionNumber = selection.sessionNumber;
    if (this.draft.totalSessions == null && selection.totalSessions != null) this.draft.totalSessions = selection.totalSessions;
  }

  clearSlotSelection(): void {
    this.selectedSlotSelection.set(null);
    this.selectedSlotPlanId = null;
    if (this.slotSelectionRequired) {
      this.draft.preferredStartAt = '';
      this.draft.preferredEndAt = '';
      this.slotSelectionError.set('슬롯이 변경되었습니다. 최신 가용 시간을 다시 조회해 다시 선택해 주세요.');
    }
  }

  onAppointmentPlanIdChange(value: number | null): void {
    if (this.selectedSlotSelection() && this.selectedSlotPlanId != null && this.selectedSlotPlanId !== value) {
      this.clearSlotSelection();
    }
  }

  async acceptProposal(): Promise<void> {
    const action = 'accept';
    const body = { evidence: { evidenceAuthority: this.draft.evidenceAuthority, evidenceId: this.draft.evidenceId } };
    const state = this.facade.state();
    try {
      const applied = await this.facade.acceptProposal(body, this.intentKey(action, JSON.stringify({ body, appointmentId: state.appointmentId, proposalId: state.proposalId, etag: state.etag })));
      if (!applied) return;
      this.intentKeys.delete(action);
    } catch (error) {
      if (!this.shouldReuseIntentKey(error)) this.intentKeys.delete(action);
      throw error;
    }
  }

  async declineProposal(): Promise<void> {
    const action = 'decline';
    const body = { reasonCode: 'CUSTOMER_REQUEST' as const };
    const state = this.facade.state();
    try {
      const applied = await this.facade.declineProposal(body, this.intentKey(action, JSON.stringify({ body, appointmentId: state.appointmentId, proposalId: state.proposalId, etag: state.etag })));
      if (!applied) return;
      this.intentKeys.delete(action);
    } catch (error) {
      if (!this.shouldReuseIntentKey(error)) this.intentKeys.delete(action);
      throw error;
    }
  }

  beginCancellation(): void {
    if (!this.canCancel || this.facade.state().busy) return;
    this.cancellationPending.set(true);
    this.cancellationIntentKey = this.newCancellationIntentKey();
  }

  dismissCancellation(): void {
    if (this.facade.state().busy) return;
    this.cancellationPending.set(false);
    this.cancellationIntentKey = null;
  }

  async confirmCancellation(): Promise<void> {
    if (!this.cancellationPending() || !this.cancellationIntentKey) return;
    try {
      const applied = await this.facade.cancelAppointment({ reasonCode: this.cancellationReasonCode }, this.cancellationIntentKey);
      if (!applied) return;
      this.resetCancellationIntent();
    } catch (error) {
      if (error instanceof PortalApiException && error.state.status === 412) {
        // 최신 ETag를 facade가 반영했으므로 사용자가 새로 확인할 때만 새 key를 발급합니다.
        this.resetCancellationIntent();
      } else if (this.shouldReuseIntentKey(error)) {
        // 503/명시적 transport만 같은 command 결과를 replay할 수 있도록 확인 상태를 유지합니다.
        throw error;
      } else {
        // terminal/precondition 오류는 기존 intent를 폐기해 재시도 시 새 의도로 시작합니다.
        this.resetCancellationIntent();
        throw error;
      }
    }
  }

  get proposalLabel(): string {
    const state = this.facade.state();
    const proposal = state.commitment?.currentProposal;
    if (proposal) return `${proposal.representativeTreatmentName} · ${proposal.startsAt}`;
    if (state.proposal) return `제안 만료 시각 ${state.proposal.expiresAt}`;
    return '제안 내용을 확인하세요.';
  }

  private intentKey(action: string, fingerprint: string): string {
    const existing = this.intentKeys.get(action);
    if (existing?.fingerprint === fingerprint) return existing.key;
    const random = this.secureRandomToken();
    const key = `portal-${action}-${random}`;
    this.intentKeys.set(action, { key, fingerprint });
    return key;
  }

  private shouldReuseIntentKey(error: unknown): boolean {
    if (!(error instanceof PortalApiException)) return false;
    return error.state.kind === 'transport' || error.state.status === 503;
  }

  private newCancellationIntentKey(): string {
    const appointmentId = this.facade.state().appointmentId || 'unknown';
    const random = this.secureRandomToken();
    return `portal-cancel-${appointmentId}-${random}`;
  }

  private secureRandomToken(): string {
    const cryptoApi = globalThis.crypto;
    if (cryptoApi?.randomUUID) return cryptoApi.randomUUID();
    if (cryptoApi?.getRandomValues) {
      const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
      return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
    }
    throw new Error('보안 난수 생성기를 사용할 수 없어 idempotency key를 만들 수 없습니다.');
  }

  private resetCancellationIntent(): void {
    this.cancellationPending.set(false);
    this.cancellationIntentKey = null;
  }

  private toIso(value: string): string {
    return value ? localDateTimeToIso(value, this.draft.clinicTimezone || DEFAULT_CLINIC_TIME_ZONE) : '';
  }

  /** 새로고침에서도 server-owned commitment를 다시 읽을 수 있는 최소 참조만 보존합니다. */
  private async restoreCommitment(): Promise<void> {
    const current = this.facade.state();
    const routeId = this.positiveId(this.route?.snapshot.queryParamMap.get('appointmentId'));
    const appointmentId = routeId ?? this.readRememberedAppointment();
    if (!appointmentId || current.appointmentId === appointmentId) {
      this.restoreBlocked.set(false);
      return;
    }

    try {
      await this.facade.loadCommitment(appointmentId);
      this.rememberAppointment(appointmentId);
      this.restoreBlocked.set(false);
    } catch (error) {
      // 404만 서버에 예약이 없다는 뜻이므로 새 요청 폼을 허용합니다.
      // 인증/권한/네트워크 오류는 기존 환자 상태를 숨긴 채 재시도 화면을 유지합니다.
      const notFound = error instanceof PortalApiException && error.state.status === 404;
      if (notFound) this.forgetRememberedAppointment();
      this.restoreBlocked.set(!notFound);
    }
  }

  async retryRestore(): Promise<void> {
    await this.restoreCommitment();
  }

  private rememberAppointment(appointmentId: number): void {
    const tenantCode = this.tenant?.tenantCode() || 'unknown';
    try {
      globalThis.sessionStorage?.setItem(this.storageKey(tenantCode), String(appointmentId));
    } catch {
      // private browsing이나 quota 오류가 예약 mutation을 막지 않도록 합니다.
    }
  }

  private readRememberedAppointment(): number | null {
    const tenantCode = this.tenant?.tenantCode() || 'unknown';
    try {
      return this.positiveId(globalThis.sessionStorage?.getItem(this.storageKey(tenantCode)));
    } catch {
      return null;
    }
  }

  private forgetRememberedAppointment(): void {
    const tenantCode = this.tenant?.tenantCode() || 'unknown';
    try {
      globalThis.sessionStorage?.removeItem(this.storageKey(tenantCode));
    } catch {
      // storage cleanup 실패와 무관하게 404의 새 예약 폼 전환은 허용합니다.
    }
  }

  private storageKey(tenantCode: string): string {
    return `appointment_portal_last_id:${tenantCode}`;
  }

  private positiveId(value: string | null | undefined): number | null {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }
}

function isValidTimeZone(value: string | null | undefined): value is string {
  if (!value?.trim()) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}

function localDateTimeToIso(value: string, timeZone: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match || !isValidTimeZone(timeZone)) return '';
  const [, year, month, day, hour, minute, second = '00'] = match;
  const expected = {
    year: Number(year),
    month: Number(month),
    day: Number(day),
    hour: Number(hour),
    minute: Number(minute),
    second: Number(second),
  };
  const localAsUtc = Date.UTC(expected.year, expected.month - 1, expected.day, expected.hour, expected.minute, expected.second);
  const offsets = new Set<number>();
  for (const delta of [-172800000, -86400000, 0, 86400000, 172800000]) {
    try {
      offsets.add(timeZoneOffsetMs(new Date(localAsUtc + delta), timeZone));
    } catch {
      return '';
    }
  }
  const candidates = [...offsets]
    .map(offset => new Date(localAsUtc - offset))
    .filter(instant => sameLocalDateTime(instant, expected, timeZone));
  return candidates.length === 1 ? candidates[0].toISOString() : '';
}

function sameLocalDateTime(
  instant: Date,
  expected: { year: number; month: number; day: number; hour: number; minute: number; second: number },
  timeZone: string,
): boolean {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    calendar: 'iso8601',
    numberingSystem: 'latn',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const values = Object.fromEntries(parts.filter(part => part.type !== 'literal').map(part => [part.type, Number(part.value)]));
  return values['year'] === expected.year
    && values['month'] === expected.month
    && values['day'] === expected.day
    && values['hour'] === expected.hour
    && values['minute'] === expected.minute
    && values['second'] === expected.second;
}

function timeZoneOffsetMs(instant: Date, timeZone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    calendar: 'iso8601',
    numberingSystem: 'latn',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant);
  const values = Object.fromEntries(parts.filter(part => part.type !== 'literal').map(part => [part.type, part.value]));
  const asUtc = Date.UTC(
    Number(values['year']),
    Number(values['month']) - 1,
    Number(values['day']),
    Number(values['hour']),
    Number(values['minute']),
    Number(values['second']),
  );
  return asUtc - instant.getTime();
}

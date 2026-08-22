import { Component, EventEmitter, Input, Output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AvailableSlot } from '../../../core/api/portal-api.models';
import { PortalApiClient } from '../../../core/api/portal-api-client';
import { PortalApiException } from '../../../core/api/portal-api-error';
import { formatAppointmentSession, resolveAppointmentTitle } from '../appointment-summary';

export type SlotCalendarView = 'idle' | 'loading' | 'ready' | 'empty' | 'scope-error' | 'conflict' | 'expired' | 'error';

export interface SlotSelection {
  slot: AvailableSlot;
  clinicId: number;
  doctorId: number;
  treatmentTypeId: number;
  requestedDurationMinutes: number | null;
  appointmentPlanId: number | null;
  clinicTimezone: string;
  productName: string | null;
  sessionNumber: number | null;
  totalSessions: number | null;
  clinicName: string | null;
  preferredStartAt: string;
  preferredEndAt: string;
}

@Component({
  selector: 'app-patient-slot-calendar',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="slot-calendar" data-slot-calendar aria-labelledby="slot-calendar-title">
      <div class="calendar-heading">
        <div>
          <p class="portal-eyebrow">SLOT SELECTION</p>
          <h3 id="slot-calendar-title">방문 날짜와 시간을 선택하세요</h3>
          <p class="calendar-help">환자 세션과 병원 범위에 맞는 가용 슬롯만 조회합니다.</p>
        </div>
        <span class="scope-badge">tenant scope</span>
      </div>

      <form class="slot-filters" (ngSubmit)="loadSlots()" aria-label="슬롯 조회 조건">
        <label>병원 ID
          <input name="clinicId" type="number" min="1" required [(ngModel)]="clinicId" [readonly]="scopeLocked" />
        </label>
        <label>의사 ID
          <input name="doctorId" type="number" min="1" required [(ngModel)]="doctorId" [readonly]="scopeLocked" />
        </label>
        <label>진료 유형 ID
          <input name="treatmentTypeId" type="number" min="1" required [(ngModel)]="treatmentTypeId" [readonly]="scopeLocked" />
        </label>
        <label>방문 날짜
          <input name="date" type="date" required [(ngModel)]="date" (change)="loadSlots()" />
        </label>
        <label>진료 시간(분, 선택)
          <input name="requestedDurationMinutes" type="number" min="1" [(ngModel)]="requestedDurationMinutes" />
        </label>
        <button type="submit" [disabled]="view() === 'loading'">
          {{ view() === 'loading' ? '조회 중…' : '가용 시간 조회' }}
        </button>
      </form>

      @if (view() === 'loading') {
        <p class="state-message" data-slot-loading role="status" aria-live="polite">가용 슬롯을 조회하고 있습니다.</p>
      } @else if (view() === 'idle') {
        <p class="state-message" data-slot-idle role="status">병원·의사·진료 유형과 날짜를 입력하면 가용 시간이 표시됩니다.</p>
      } @else if (view() === 'empty') {
        <div class="state-message" data-slot-empty role="status">
          <strong>선택한 날짜에는 가능한 시간이 없습니다.</strong>
          <p>다른 날짜를 선택하거나 병원에 문의해 주세요.</p>
        </div>
      } @else if (view() === 'scope-error') {
        <div class="state-message state-error" data-slot-scope-error role="alert">
          <strong>선택한 병원 범위의 시간을 확인할 수 없습니다.</strong>
          <p>로그인·병원 선택을 확인한 뒤 다시 시도해 주세요.</p>
          <button type="button" (click)="loadSlots()">다시 시도</button>
        </div>
      } @else if (view() === 'conflict') {
        <div class="state-message state-error" data-slot-conflict role="alert">
          <strong>방금 선택한 시간이 변경되었습니다.</strong>
          <p>최신 가용 시간을 다시 조회해 주세요.</p>
          <button type="button" (click)="loadSlots()">최신 시간 조회</button>
        </div>
      } @else if (view() === 'expired') {
        <div class="state-message state-error" data-slot-expired role="alert">
          <strong>선택한 날짜 또는 시간이 만료되었습니다.</strong>
          <p>새로운 날짜를 선택해 예약을 계속해 주세요.</p>
        </div>
      } @else if (view() === 'error') {
        <div class="state-message state-error" data-slot-error role="alert">
          <strong>가용 시간을 불러오지 못했습니다.</strong>
          <p>{{ errorMessage() }}</p>
          <button type="button" (click)="loadSlots()">다시 시도</button>
        </div>
      } @else {
        <div class="slot-results" aria-live="polite">
          <div class="results-heading">
            <h4>선택 가능한 시간</h4>
            <span>{{ slots().length }}개</span>
          </div>
          <div class="slot-list" role="listbox" aria-label="가용 방문 시간">
            @for (slot of slots(); track slotKey(slot); let index = $index) {
              <button
                type="button"
                role="option"
                class="slot-option"
                [class.is-selected]="selectedSlot()?.startTime === slot.startTime && selectedSlot()?.endTime === slot.endTime"
                [attr.aria-selected]="selectedSlot()?.startTime === slot.startTime && selectedSlot()?.endTime === slot.endTime"
                [attr.tabindex]="focusedSlotIndex() === index ? 0 : -1"
                (click)="selectSlot(slot)"
                (keydown)="onSlotKeydown($event, index)"
              >
                <span class="slot-time">{{ formatTime(slot.startTime) }}–{{ formatTime(slot.endTime) }}</span>
                <span class="slot-capacity">잔여 {{ slot.remainingCapacity }}명</span>
              </button>
            }
          </div>
        </div>
      }

      @if (selectedSlot(); as slot) {
        <section class="selection-summary" data-slot-summary aria-labelledby="slot-summary-title">
          <h4 id="slot-summary-title">선택 요약</h4>
          <p class="selection-title">{{ selectionTitle() }}</p>
          @if (selectionSession(); as session) {
            <p class="selection-meta">{{ session }}</p>
          }
          <dl>
            <div><dt>일시</dt><dd>{{ slot.date }} · {{ formatTime(slot.startTime) }}–{{ formatTime(slot.endTime) }}</dd></div>
            <div><dt>장소</dt><dd>{{ placeLabel() }}</dd></div>
            <div><dt>의사</dt><dd>#{{ selectedContext()?.doctorId ?? doctorId }}</dd></div>
          </dl>
          <p class="selection-hint" role="status">아래 예약 요청 폼에 선택한 시간이 반영되었습니다.</p>
        </section>
      }
    </section>
  `,
  styles: [`
    :host { display: block; }
    .slot-calendar { display: grid; gap: 18px; margin-top: 24px; padding: 20px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    .calendar-heading, .results-heading { display: flex; align-items: start; justify-content: space-between; gap: 12px; }
    .portal-eyebrow { margin: 0 0 6px; color: var(--portal-muted); font-size: .72rem; font-weight: 700; letter-spacing: .12em; }
    h3, h4 { margin: 0; }
    h3 { font-size: 1.1rem; }
    h4 { font-size: .98rem; }
    .calendar-help, .state-message p { margin: 6px 0 0; color: var(--portal-muted); }
    .scope-badge { flex: 0 0 auto; padding: 4px 7px; border: 1px solid var(--portal-line); color: var(--portal-muted); font-size: .7rem; letter-spacing: .08em; text-transform: uppercase; }
    .slot-filters { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
    label { display: grid; gap: 5px; color: var(--portal-muted); font-size: .8rem; }
    input { box-sizing: border-box; width: 100%; min-height: 40px; border: 1px solid var(--portal-line); background: var(--portal-surface); color: var(--portal-ink); padding: 7px 9px; font: inherit; }
    input:focus-visible, button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 2px; }
    .slot-filters button, .state-message button { align-self: end; min-height: 40px; border: 1px solid var(--portal-ink); background: var(--portal-ink); color: var(--portal-surface-raised); padding: 7px 12px; cursor: pointer; font: inherit; }
    button:disabled { cursor: wait; opacity: .6; }
    .state-message { margin: 0; padding: 14px; border-left: 3px solid var(--portal-focus); background: var(--portal-surface); }
    .state-error { border-left-color: #a64a43; }
    .state-error button { margin-top: 12px; }
    .results-heading > span { color: var(--portal-muted); font-size: .8rem; }
    .slot-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
    .slot-option { display: grid; gap: 5px; min-height: 64px; border: 1px solid var(--portal-line); background: var(--portal-surface); color: var(--portal-ink); padding: 10px; text-align: left; cursor: pointer; font: inherit; }
    .slot-option:hover, .slot-option.is-selected { border-color: var(--portal-focus); background: color-mix(in srgb, var(--portal-focus) 8%, var(--portal-surface)); }
    .slot-time { font-weight: 700; }
    .slot-capacity { color: var(--portal-muted); font-size: .78rem; }
    .selection-summary { display: grid; gap: 8px; padding-top: 16px; border-top: 1px solid var(--portal-line); }
    .selection-title { margin: 0; font-weight: 700; }
    .selection-meta, .selection-hint { margin: 0; color: var(--portal-muted); font-size: .86rem; }
    dl { display: grid; gap: 5px; margin: 4px 0 0; }
    dl > div { display: grid; grid-template-columns: 46px minmax(0, 1fr); gap: 8px; }
    dt { color: var(--portal-muted); }
    dd { margin: 0; }
    @media (max-width: 620px) { .slot-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); } .slot-list { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    @media (max-width: 420px) { .slot-calendar { padding: 14px; } .slot-filters { grid-template-columns: 1fr; } .calendar-heading { display: grid; } }
  `],
})
export class PatientSlotCalendarComponent {
  @Input() clinicId: number | null = null;
  @Input() doctorId: number | null = null;
  @Input() treatmentTypeId: number | null = null;
  @Input() requestedDurationMinutes: number | null = null;
  @Input() date = todayIsoDate();
  @Input() appointmentPlanId: number | null = null;
  @Input() productName: string | null = null;
  @Input() sessionNumber: number | null = null;
  @Input() totalSessions: number | null = null;
  @Input() clinicName: string | null = null;
  @Input() scopeLocked = false;
  @Output() readonly slotSelected = new EventEmitter<SlotSelection>();
  @Output() readonly slotSelectionCleared = new EventEmitter<void>();

  readonly view = signal<SlotCalendarView>('idle');
  readonly slots = signal<AvailableSlot[]>([]);
  readonly selectedSlot = signal<AvailableSlot | null>(null);
  readonly selectedContext = signal<{
    clinicId: number;
    doctorId: number;
    treatmentTypeId: number;
    clinicName: string | null;
    clinicTimezone: string;
    productName: string | null;
    sessionNumber: number | null;
    totalSessions: number | null;
  } | null>(null);
  readonly errorMessage = signal('네트워크 상태를 확인한 뒤 다시 시도해 주세요.');
  readonly focusedSlotIndex = signal(0);

  private requestVersion = 0;
  private loadedCriteriaFingerprint: string | null = null;
  private loadedClinic: { id: number; name: string; timezone: string } | null = null;

  constructor(private readonly api: PortalApiClient) {}

  async loadSlots(): Promise<void> {
    const requestVersion = ++this.requestVersion;
    this.clearSelection();
    this.loadedCriteriaFingerprint = null;
    this.loadedClinic = null;
    this.slots.set([]);
    const criteria = this.criteria();
    if (!criteria) {
      this.slots.set([]);
      this.view.set('idle');
      return;
    }

    this.view.set('loading');
    this.errorMessage.set('네트워크 상태를 확인한 뒤 다시 시도해 주세요.');
    try {
      const clinicResponse = await this.api.getClinic(criteria.clinicId);
      if (requestVersion !== this.requestVersion) return;
      const clinic = clinicResponse.body;
      if (!clinic || clinic.id !== criteria.clinicId || typeof clinic.name !== 'string' || !isValidTimeZone(clinic.timezone)) {
        this.errorMessage.set('병원의 권위 timezone 정보를 확인할 수 없습니다. 병원 선택을 확인한 뒤 다시 시도해 주세요.');
        this.view.set('scope-error');
        return;
      }
      this.loadedClinic = clinic;
      const response = await this.api.getSlots(
        criteria.clinicId,
        criteria.doctorId,
        criteria.treatmentTypeId,
        criteria.date,
        criteria.requestedDurationMinutes ?? undefined,
      );
      if (requestVersion !== this.requestVersion) return;
      const currentCriteria = this.criteria();
      if (!currentCriteria || this.criteriaFingerprint(currentCriteria) !== this.criteriaFingerprint(criteria)) {
        this.slots.set([]);
        this.view.set('conflict');
        this.errorMessage.set('조회 조건이 변경되었습니다. 최신 가용 시간을 다시 조회해 주세요.');
        return;
      }
      const scopedSlots = response.body.filter(slot => slot.date === criteria.date && slot.doctorId === criteria.doctorId);
      this.slots.set(scopedSlots);
      this.loadedCriteriaFingerprint = this.criteriaFingerprint(criteria);
      this.focusedSlotIndex.set(0);
      this.view.set(scopedSlots.length ? 'ready' : 'empty');
    } catch (error) {
      if (requestVersion !== this.requestVersion) return;
      this.slots.set([]);
      this.setErrorState(error);
    }
  }

  selectSlot(slot: AvailableSlot): void {
    const criteria = this.criteria();
    const currentFingerprint = criteria ? this.criteriaFingerprint(criteria) : null;
    const currentSlot = this.slots().find(candidate => this.slotKey(candidate) === this.slotKey(slot));
    const isCurrentResult = currentFingerprint !== null
      && currentFingerprint === this.loadedCriteriaFingerprint
      && currentSlot != null
      && this.loadedClinic?.id === criteria?.clinicId;
    if (!criteria || !isCurrentResult || currentSlot == null || slot.date !== criteria.date || slot.doctorId !== criteria.doctorId
      || slot.remainingCapacity < 1 || currentSlot.remainingCapacity < 1) {
      this.view.set('conflict');
      this.errorMessage.set('선택한 슬롯의 병원·의사 범위 또는 잔여 정원이 최신 상태와 다릅니다.');
      this.clearSelection();
      return;
    }
    const clinic = this.loadedClinic;
    if (!clinic) {
      this.view.set('scope-error');
      this.errorMessage.set('병원의 권위 metadata를 확인할 수 없습니다. 병원 선택을 확인한 뒤 다시 시도해 주세요.');
      this.clearSelection();
      return;
    }
    const context = {
      clinicId: criteria.clinicId,
      doctorId: criteria.doctorId,
      treatmentTypeId: criteria.treatmentTypeId,
      clinicName: clinic.name.trim() || null,
      clinicTimezone: clinic.timezone,
      productName: this.productName?.trim() || null,
      sessionNumber: this.sessionNumber,
      totalSessions: this.totalSessions,
    };
    this.selectedSlot.set(currentSlot);
    this.selectedContext.set(context);
    this.slotSelected.emit({
      slot: currentSlot,
      clinicId: context.clinicId,
      doctorId: context.doctorId,
      treatmentTypeId: context.treatmentTypeId,
      requestedDurationMinutes: criteria.requestedDurationMinutes,
      appointmentPlanId: this.appointmentPlanId,
      clinicTimezone: context.clinicTimezone,
      productName: context.productName,
      sessionNumber: context.sessionNumber,
      totalSessions: context.totalSessions,
      clinicName: context.clinicName,
      preferredStartAt: `${currentSlot.date}T${currentSlot.startTime}`.slice(0, 19),
      preferredEndAt: `${currentSlot.date}T${currentSlot.endTime}`.slice(0, 19),
    });
  }

  onSlotKeydown(event: KeyboardEvent, index: number): void {
    const columns = this.slotColumnCount();
    const row = Math.floor(index / columns);
    let next = index;
    if (event.key === 'ArrowRight') next = Math.min(index + 1, this.slots().length - 1);
    else if (event.key === 'ArrowLeft') next = Math.max(index - 1, 0);
    else if (event.key === 'ArrowDown') next = Math.min(index + columns, this.slots().length - 1);
    else if (event.key === 'ArrowUp') next = Math.max(index - columns, 0);
    else if (event.key === 'Home') next = row * columns;
    else if (event.key === 'End') next = Math.min(row * columns + columns - 1, this.slots().length - 1);
    else return;
    event.preventDefault();
    this.focusedSlotIndex.set(next);
    const target = event.currentTarget as HTMLElement | null;
    const buttons = target?.parentElement?.querySelectorAll<HTMLButtonElement>('.slot-option');
    buttons?.item(next)?.focus();
  }

  slotKey(slot: AvailableSlot): string {
    return `${slot.date}-${slot.startTime}-${slot.endTime}-${slot.doctorId}`;
  }

  formatTime(value: string): string {
    return value.slice(0, 5);
  }

  selectionTitle(): string {
    const context = this.selectedContext();
    return resolveAppointmentTitle({ productName: context?.productName }, `진료 유형 #${context?.treatmentTypeId ?? this.treatmentTypeId}`);
  }

  selectionSession(): string | null {
    const context = this.selectedContext();
    return formatAppointmentSession({ sessionNumber: context?.sessionNumber ?? null, totalSessions: context?.totalSessions ?? null });
  }

  placeLabel(): string {
    const context = this.selectedContext();
    return context?.clinicName || (context?.clinicId ? `클리닉 #${context.clinicId}` : '장소 미지정');
  }

  private criteria(): { clinicId: number; doctorId: number; treatmentTypeId: number; date: string; requestedDurationMinutes: number | null } | null {
    const clinicId = positiveInteger(this.clinicId);
    const doctorId = positiveInteger(this.doctorId);
    const treatmentTypeId = positiveInteger(this.treatmentTypeId);
    if (!clinicId || !doctorId || !treatmentTypeId || !/^\d{4}-\d{2}-\d{2}$/.test(this.date)) return null;
    const requestedDurationMinutes = positiveInteger(this.requestedDurationMinutes);
    return { clinicId, doctorId, treatmentTypeId, date: this.date, requestedDurationMinutes };
  }

  private setErrorState(error: unknown): void {
    if (error instanceof PortalApiException) {
      if (error.state.kind === 'tenant-missing' || error.state.kind === 'unauthorized' || error.state.kind === 'forbidden') {
        this.view.set('scope-error');
        return;
      }
      if (error.state.status === 404) {
        this.view.set('scope-error');
        return;
      }
      if (error.state.kind === 'conflict' || error.state.kind === 'precondition') {
        this.view.set('conflict');
        return;
      }
      if (error.state.kind === 'expired') {
        this.view.set('expired');
        return;
      }
      this.errorMessage.set(error.state.retryAfterSeconds == null
        ? error.state.message
        : `${error.state.message} ${error.state.retryAfterSeconds}초 후 다시 시도해 주세요.`);
    } else if (error instanceof Error && error.message) {
      if (error.message.includes('병원 권위 메타데이터')) {
        this.view.set('scope-error');
        return;
      }
      this.errorMessage.set(error.message);
    }
    this.view.set('error');
  }

  private slotColumnCount(): number {
    return typeof globalThis.innerWidth === 'number' && globalThis.innerWidth >= 621 ? 3 : 2;
  }

  private clearSelection(): void {
    if (this.selectedSlot()) this.slotSelectionCleared.emit();
    this.selectedSlot.set(null);
    this.selectedContext.set(null);
  }

  private criteriaFingerprint(criteria: { clinicId: number; doctorId: number; treatmentTypeId: number; date: string; requestedDurationMinutes: number | null }): string {
    return [criteria.clinicId, criteria.doctorId, criteria.treatmentTypeId, criteria.date, criteria.requestedDurationMinutes ?? ''].join('|');
  }
}

function positiveInteger(value: number | null): number | null {
  return Number.isInteger(value) && value != null && value > 0 ? value : null;
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function isValidTimeZone(value: string): boolean {
  if (!value?.trim()) return false;
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}

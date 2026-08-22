import { Component, OnDestroy, OnInit, computed, effect, inject, signal } from '@angular/core';

import {
  CancellationHistoryPageResult,
  PatientCancellationHistoryEntry,
  PatientHistoryQuery,
} from '../../../core/api/portal-api.models';
import { PortalApiClient } from '../../../core/api/portal-api-client';
import { PortalApiException } from '../../../core/api/portal-api-error';
import { TenantContextService } from '../../../core/api/tenant-context.service';

export type PatientCancellationHistoryStatus =
  | 'initialLoading'
  | 'ready'
  | 'loadingMore'
  | 'initialError'
  | 'loadMoreError'
  | 'exhausted';

export type PatientHistoryLoadMoreResult =
  | { kind: 'accepted' }
  | { kind: 'busy' }
  | { kind: 'exhausted' };

const INITIAL_QUERY: PatientHistoryQuery = { cursor: null, limit: 20 };

/** 환자 취소 이력을 session-bound keyset timeline으로 표시합니다. */
@Component({
  selector: 'app-patient-cancellation-history',
  standalone: true,
  template: `
    <section class="history" aria-labelledby="cancellation-history-title">
      <div class="history-heading">
        <div>
          <p class="portal-eyebrow">HISTORY</p>
          <h3 id="cancellation-history-title">취소 이력</h3>
        </div>
        @if (busy()) { <span class="history-busy" aria-live="polite">불러오는 중</span> }
      </div>

      @if (status() === 'initialLoading') {
        <div class="history-skeleton" aria-hidden="true"><span></span><span></span><span></span></div>
        <p class="sr-only" aria-live="polite">취소 이력을 불러오는 중입니다.</p>
      } @else if (status() === 'initialError') {
        <div class="history-error" role="alert">
          <p>{{ errorMessage() }}</p>
          <button type="button" (click)="retryInitial()">다시 불러오기</button>
        </div>
      } @else if (entries().length === 0) {
        <p class="history-empty" role="status">표시할 취소 이력이 없습니다.</p>
      } @else {
        <p class="sr-only" role="status" aria-live="polite">{{ announcement() }}</p>
        <ol class="history-list" [attr.aria-busy]="busy()">
          @for (entry of entries(); track entry.appointmentRef) {
            <li class="history-item">
              <div class="history-marker" aria-hidden="true"></div>
              <article>
                <div class="history-meta">
                  <time [attr.datetime]="entry.occurredAt">{{ formatTime(entry.occurredAt) }}</time>
                  <span>{{ entry.actorLabel }}</span>
                </div>
                <h4>{{ entry.toStatusLabel }}</h4>
                <p class="history-product">{{ entry.productName ?? '상품 정보 없음' }} · {{ sessionLabel(entry) }}</p>
                <p class="history-visit">{{ visitLabel(entry) }}</p>
                <p class="history-reason">{{ entry.reasonLabel }} @if (entry.reasonDetail) { · {{ entry.reasonDetail }} }</p>
                <p class="history-transition">{{ entry.fromStatusLabel ?? '이전 상태 확인 불가' }} → {{ entry.toStatusLabel }}</p>
              </article>
            </li>
          }
        </ol>
        <div class="history-more">
          <button
            type="button"
            [attr.aria-disabled]="status() === 'exhausted' || status() === 'loadingMore'"
            [disabled]="false"
            (click)="loadMore()"
          >{{ status() === 'exhausted' ? '모든 취소 이력을 불러왔습니다' : status() === 'loadingMore' ? '불러오는 중…' : '더 보기' }}</button>
        </div>
        @if (status() === 'loadMoreError') {
          <div class="history-error" role="alert">
            <p>{{ errorMessage() }}</p>
            <button type="button" (click)="retryLoadMore()">같은 위치 다시 시도</button>
          </div>
        }
      }
    </section>
  `,
  styles: [`
    :host { display: block; margin-top: 28px; }
    .history { padding: 20px; border: 1px solid var(--portal-line); background: var(--portal-surface-raised); }
    .history-heading { display: flex; align-items: start; justify-content: space-between; gap: 12px; }
    .portal-eyebrow { margin: 0 0 6px; color: var(--portal-muted); font-size: .72rem; font-weight: 700; letter-spacing: .12em; }
    h3, h4 { margin: 0; }
    h3 { font-size: 1.1rem; }
    h4 { margin-top: 8px; font-size: 1rem; }
    .history-busy, .history-meta, .history-visit, .history-transition { color: var(--portal-muted); font-size: .85rem; }
    .history-skeleton { display: grid; gap: 10px; margin-top: 18px; }
    .history-skeleton span { display: block; height: 58px; background: linear-gradient(90deg, var(--portal-line), var(--portal-surface), var(--portal-line)); background-size: 200% 100%; animation: history-shimmer 1.3s infinite; }
    .history-list { display: grid; gap: 0; margin: 20px 0 0; padding: 0; list-style: none; }
    .history-item { position: relative; display: grid; grid-template-columns: 18px 1fr; gap: 12px; padding-bottom: 22px; }
    .history-item:not(:last-child)::before { position: absolute; top: 12px; bottom: 0; left: 8px; width: 1px; background: var(--portal-line); content: ''; }
    .history-marker { z-index: 1; width: 16px; height: 16px; margin-top: 2px; border: 4px solid var(--portal-surface-raised); border-radius: 50%; background: var(--portal-focus); box-shadow: 0 0 0 1px var(--portal-focus); }
    .history-meta { display: flex; flex-wrap: wrap; gap: 8px; }
    .history-product, .history-reason { margin: 8px 0 0; }
    .history-product { color: var(--portal-ink); font-weight: 600; }
    .history-reason { color: var(--portal-ink); }
    .history-visit, .history-transition { margin: 5px 0 0; }
    .history-empty, .history-error { margin: 18px 0 0; padding: 14px; background: var(--portal-surface); }
    .history-more { display: flex; justify-content: center; margin-top: 4px; }
    button { min-height: 40px; border: 1px solid var(--portal-ink); background: var(--portal-ink); color: var(--portal-surface-raised); padding: 8px 14px; cursor: pointer; font: inherit; }
    button[aria-disabled='true'] { opacity: .7; }
    button:focus-visible { outline: 3px solid var(--portal-focus); outline-offset: 2px; }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
    @keyframes history-shimmer { to { background-position: -200% 0; } }
    @media (max-width: 320px) { .history { padding: 14px; } .history-item { grid-template-columns: 14px 1fr; gap: 8px; } .history-marker { width: 12px; height: 12px; } .history-item:not(:last-child)::before { left: 6px; } }
  `],
})
export class PatientCancellationHistoryComponent implements OnInit, OnDestroy {
  private readonly client = inject(PortalApiClient);
  private readonly tenant = inject(TenantContextService);
  private readonly _status = signal<PatientCancellationHistoryStatus>('initialLoading');
  private readonly _entries = signal<PatientCancellationHistoryEntry[]>([]);
  private readonly _nextCursor = signal<string | null>(null);
  private readonly _errorMessage = signal<string | null>(null);
  private readonly _announcement = signal('');
  private requestEpoch = 0;
  private recoveryEpoch = -1;
  private observedTenant: string | null = null;
  private destroyed = false;

  readonly status = this._status.asReadonly();
  readonly entries = this._entries.asReadonly();
  readonly errorMessage = computed(() => this._errorMessage() ?? '취소 이력을 불러오지 못했습니다.');
  readonly announcement = this._announcement.asReadonly();
  readonly busy = computed(() => this.status() === 'initialLoading' || this.status() === 'loadingMore');

  private readonly tenantEffect = effect(() => {
    const tenantCode = this.tenant.tenantCode();
    if (this.observedTenant === null) {
      this.observedTenant = tenantCode;
      return;
    }
    if (tenantCode !== this.observedTenant) {
      this.observedTenant = tenantCode;
      this.resetForScopeChange();
      if (!this.destroyed) void this.loadInitial();
    }
  });

  ngOnInit(): void {
    void this.loadInitial();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    this.requestEpoch += 1;
    this._entries.set([]);
    this._nextCursor.set(null);
    this.client.clearCancellationHistoryCache();
    this.tenantEffect.destroy();
  }

  async loadMore(): Promise<PatientHistoryLoadMoreResult> {
    if (this.status() === 'initialLoading' || this.status() === 'loadingMore') return { kind: 'busy' };
    const cursor = this._nextCursor();
    if (!cursor || this.status() === 'exhausted') return { kind: 'exhausted' };
    this._status.set('loadingMore');
    this._errorMessage.set(null);
    const epoch = this.requestEpoch;
    const tenantCode = this.observedTenant;
    try {
      const result = await this.request({ cursor, limit: INITIAL_QUERY.limit });
      if (!this.isCurrent(epoch, tenantCode)) return { kind: 'busy' };
      this.applyPage(result, true);
      return { kind: 'accepted' };
    } catch (error) {
      if (this.isUnauthorized(error)) {
        this.resetForUnauthorized();
        this._status.set('initialError');
        this._errorMessage.set(this.safeMessage(error));
        throw error;
      }
      const recoverable = error instanceof PortalApiException && (error.state.status === 400 || error.state.status === 409);
      if (recoverable && this.recoveryEpoch !== epoch) {
        // A stale cursor is a page contract failure, not a reason to replay the
        // same cursor. Purge the private page cache and recover exactly once from
        // an unconditional first-page request for this component epoch.
        this.recoveryEpoch = epoch;
        this.client.clearCancellationHistoryCache();
        this._entries.set([]);
        this._nextCursor.set(null);
        this._errorMessage.set(null);
        this._status.set('initialLoading');
        try {
          const result = await this.request(INITIAL_QUERY);
          if (this.isCurrent(epoch, tenantCode)) this.applyPage(result, false);
          return { kind: 'accepted' };
        } catch (recoveryError) {
          error = recoveryError;
        }
      }
      if (this.isCurrent(epoch, tenantCode)) {
        this._status.set('loadMoreError');
        this._errorMessage.set(this.safeMessage(error));
      }
      throw error;
    }
  }

  async retryInitial(): Promise<void> { await this.loadInitial(); }
  async retryLoadMore(): Promise<PatientHistoryLoadMoreResult> { return this.loadMore(); }

  formatTime(value: string): string {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '시간 확인 불가';
    return new Intl.DateTimeFormat('ko-KR', {
      year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short',
    }).format(date);
  }

  sessionLabel(entry: PatientCancellationHistoryEntry): string {
    if (entry.sessionNumber == null || entry.totalSessions == null) return '회차 정보 없음';
    return `${entry.sessionNumber}회차 / ${entry.totalSessions}회`;
  }

  visitLabel(entry: PatientCancellationHistoryEntry): string {
    if (!entry.visitStartAt || !entry.visitEndAt) return '방문 시간 확인 불가';
    return `${this.formatTime(entry.visitStartAt)} ~ ${this.formatTime(entry.visitEndAt)}`;
  }

  private async loadInitial(): Promise<void> {
    this._status.set('initialLoading');
    this._entries.set([]);
    this._nextCursor.set(null);
    this._errorMessage.set(null);
    const epoch = ++this.requestEpoch;
    const tenantCode = this.observedTenant ?? this.tenant.tenantCode();
    if (this.observedTenant === null) this.observedTenant = tenantCode;
    try {
      const result = await this.request(INITIAL_QUERY);
      if (!this.isCurrent(epoch, tenantCode)) return;
      this.applyPage(result, false);
    } catch (error) {
      if (!this.isCurrent(epoch, tenantCode)) return;
      if (this.isUnauthorized(error)) {
        this.resetForUnauthorized();
        this._status.set('initialError');
        this._errorMessage.set(this.safeMessage(error));
        return;
      }
      const recoverable = error instanceof PortalApiException && (error.state.status === 400 || error.state.status === 409);
      if (recoverable && this.recoveryEpoch !== epoch) {
        this.recoveryEpoch = epoch;
        this.client.clearCancellationHistoryCache();
        this._status.set('initialLoading');
        try {
          const result = await this.request(INITIAL_QUERY);
          if (this.isCurrent(epoch, tenantCode)) this.applyPage(result, false);
          return;
        } catch (retryError) { error = retryError; }
      }
      this._status.set('initialError');
      this._errorMessage.set(this.safeMessage(error));
    }
  }

  private async request(query: PatientHistoryQuery): Promise<CancellationHistoryPageResult> {
    return this.client.getCancellationHistory(query);
  }

  private applyPage(result: CancellationHistoryPageResult, append: boolean): void {
    const existing = append ? this._entries() : [];
    const merged = [...existing, ...result.body.entries];
    const unique = [...new Map(merged.map(entry => [entry.appointmentRef, entry])).values()];
    this._entries.set(unique);
    this._nextCursor.set(result.body.nextCursor);
    this._status.set(result.body.nextCursor ? 'ready' : 'exhausted');
    this._announcement.set(append ? `${result.body.entries.length}건을 추가했습니다.` : `${unique.length}건의 취소 이력을 불러왔습니다.`);
  }

  private resetForScopeChange(): void {
    this.requestEpoch += 1;
    this.recoveryEpoch = -1;
    this._entries.set([]);
    this._nextCursor.set(null);
    this._errorMessage.set(null);
    this._status.set('initialLoading');
    this.client.clearCancellationHistoryCache();
  }

  private isCurrent(epoch: number, tenantCode: string | null): boolean {
    return !this.destroyed && epoch === this.requestEpoch && tenantCode === this.observedTenant;
  }

  private safeMessage(error: unknown): string {
    if (!(error instanceof PortalApiException)) return '취소 이력을 불러오지 못했습니다.';
    if (error.state.status === 401) return '로그인 세션이 만료되었습니다. 다시 로그인해 주세요.';
    if (error.state.status === 403) return '취소 이력을 조회할 권한이 없습니다.';
    if (error.state.status === 404) return '선택한 병원을 찾을 수 없습니다.';
    if (error.state.status === 503) return '취소 이력을 잠시 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.';
    if (error.state.status === 500) return '취소 이력을 표시할 수 없습니다. 오류 번호를 확인해 주세요.';
    return '취소 이력 요청을 확인한 뒤 다시 시도해 주세요.';
  }

  private isUnauthorized(error: unknown): boolean {
    return error instanceof PortalApiException && error.state.status === 401;
  }

  private resetForUnauthorized(): void {
    this.requestEpoch += 1;
    this.recoveryEpoch = -1;
    this._entries.set([]);
    this._nextCursor.set(null);
    this._errorMessage.set(null);
    this.client.clearCancellationHistoryCache();
  }
}

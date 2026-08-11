import { Injectable } from '@angular/core';
import { Observable, Subscription, from, of, timer } from 'rxjs';
import { catchError, exhaustMap } from 'rxjs/operators';

import { CommitmentStatus } from './portal-api.models';

export interface PortalNotification {
  eventId: string;
  appointmentId: number;
  sequence: number;
  status: CommitmentStatus;
  title: string;
  message: string;
  productName?: string | null;
  sessionNumber?: number | null;
  totalSessions?: number | null;
  createdAt: string;
  read: boolean;
}

export type PortalNotificationEventSource = {
  onmessage: ((event: { data: string }) => void) | null;
  onerror: ((event: unknown) => void) | null;
  close: () => void;
};

export interface PortalEventStreamOptions {
  streamUrl: string;
  poll: () => Promise<PortalNotification[]>;
  eventSourceFactory?: (url: string) => PortalNotificationEventSource;
  pollIntervalMs?: number;
}

@Injectable({ providedIn: 'root' })
export class PortalEventStreamAdapter {
  private readonly subscriptions = new Set<Subscription>();
  private readonly sources = new Set<PortalNotificationEventSource>();
  private readonly sinks = new Set<(event: PortalNotification) => void>();
  private readonly seenEventIds = new Set<string>();
  private readonly latestSequenceByAppointment = new Map<number, number>();

  connect(options: PortalEventStreamOptions): Observable<PortalNotification> {
    return new Observable<PortalNotification>(subscriber => {
      let source: PortalNotificationEventSource | null = null;
      let pollSubscription: Subscription | null = null;
      let fallbackStarted = false;

      const emit = (event: PortalNotification): void => {
        if (this.accept(event)) subscriber.next(event);
      };
      this.sinks.add(emit);

      const startPolling = (): void => {
        if (fallbackStarted || subscriber.closed) return;
        fallbackStarted = true;
        const interval = Math.max(options.pollIntervalMs ?? 30_000, 1_000);
        pollSubscription = timer(0, interval)
          .pipe(
            exhaustMap(() => from(options.poll()).pipe(catchError(() => of([])))),
          )
          .subscribe(snapshot => snapshot.forEach(emit));
        if (pollSubscription) this.subscriptions.add(pollSubscription);
      };

      try {
        source = options.eventSourceFactory?.(options.streamUrl) ?? this.defaultEventSource(options.streamUrl);
        this.sources.add(source);
        source.onmessage = message => {
          try {
            const parsed = JSON.parse(message.data) as PortalNotification;
            emit(parsed);
          } catch {
            // 잘못된 SSE payload는 현재 상태를 오염시키지 않고 다음 polling resync에 맡긴다.
          }
        };
        source.onerror = () => {
          source?.close();
          startPolling();
        };
      } catch {
        startPolling();
      }

      return () => {
        source?.close();
        this.sources.delete(source as PortalNotificationEventSource);
        this.sinks.delete(emit);
        pollSubscription?.unsubscribe();
        if (pollSubscription) this.subscriptions.delete(pollSubscription);
      };
    });
  }

  async resync(options: PortalEventStreamOptions): Promise<void> {
    const snapshot = await options.poll();
    snapshot.forEach(event => this.sinks.forEach(sink => sink(event)));
  }

  closeAll(): void {
    for (const source of this.sources) source.close();
    for (const subscription of this.subscriptions) subscription.unsubscribe();
    this.sources.clear();
    this.sinks.clear();
    this.subscriptions.clear();
  }

  private accept(event: PortalNotification): boolean {
    if (!event.eventId || !Number.isInteger(event.appointmentId) || !Number.isInteger(event.sequence)) return false;
    if (this.seenEventIds.has(event.eventId)) return false;
    const latest = this.latestSequenceByAppointment.get(event.appointmentId) ?? 0;
    if (event.sequence <= latest) return false;
    this.seenEventIds.add(event.eventId);
    this.latestSequenceByAppointment.set(event.appointmentId, event.sequence);
    return true;
  }

  private defaultEventSource(url: string): PortalNotificationEventSource {
    if (typeof EventSource === 'undefined') {
      throw new Error('SSE를 사용할 수 없는 환경입니다.');
    }
    return new EventSource(url) as unknown as PortalNotificationEventSource;
  }
}

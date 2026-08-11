import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';

import {
  PortalEventStreamAdapter,
  PortalEventStreamOptions,
  PortalNotification,
  PortalNotificationEventSource,
} from './portal-event-stream.adapter';

const notification = (sequence: number, eventId = `event-${sequence}`): PortalNotification => ({
  eventId,
  appointmentId: 42,
  sequence,
  status: sequence > 1 ? 'CONFIRMED' : 'PROPOSED',
  title: '피부 재생 관리',
  message: sequence > 1 ? '예약이 확정되었습니다.' : '새 제안을 확인하세요.',
  productName: '피부 재생 관리',
  sessionNumber: 3,
  totalSessions: 10,
  createdAt: `2026-08-20T01:3${sequence}:00Z`,
  read: false,
});

describe('PortalEventStreamAdapter', () => {
  let adapter: PortalEventStreamAdapter;
  let source: PortalNotificationEventSource;
  let options: PortalEventStreamOptions;

  beforeEach(() => {
    adapter = new PortalEventStreamAdapter();
    source = {
      onmessage: null,
      onerror: null,
      close: vi.fn(),
    };
    options = {
      streamUrl: '/api/clinic-a/notifications/stream',
      poll: vi.fn().mockResolvedValue([]),
      eventSourceFactory: vi.fn().mockReturnValue(source),
      pollIntervalMs: 60_000,
    };
  });

  afterEach(() => adapter.closeAll());

  it('SSE event를 reducer로 전달한다', () => {
    const received: PortalNotification[] = [];
    adapter.connect(options).subscribe(value => received.push(value));
    source.onmessage?.({ data: JSON.stringify(notification(1)) });

    expect(received).toEqual([notification(1)]);
  });

  it('중복 event와 순서가 늦은 event는 한 번만 적용한다', () => {
    const received: PortalNotification[] = [];
    adapter.connect(options).subscribe(value => received.push(value));
    source.onmessage?.({ data: JSON.stringify(notification(2)) });
    source.onmessage?.({ data: JSON.stringify(notification(2)) });
    source.onmessage?.({ data: JSON.stringify(notification(1, 'old-event')) });

    expect(received.map(event => event.sequence)).toEqual([2]);
  });

  it('SSE 연결 오류 후 polling fallback으로 동일 reducer를 사용한다', async () => {
    const received: PortalNotification[] = [];
    const poll = vi.fn().mockResolvedValue([notification(1)]);
    const subscription = adapter.connect({ ...options, poll }).subscribe(value => received.push(value));
    source.onerror?.(new Event('error'));

    await vi.waitFor(() => expect(received).toHaveLength(1));
    expect(poll).toHaveBeenCalled();
    expect(source.close).toHaveBeenCalled();
    subscription.unsubscribe();
  });

  it('tab 재진입 resync는 최신 snapshot만 적용한다', async () => {
    const received: PortalNotification[] = [];
    adapter.connect(options).subscribe(value => received.push(value));
    source.onmessage?.({ data: JSON.stringify(notification(1)) });
    options.poll = vi.fn().mockResolvedValue([notification(1), notification(2)]);

    await adapter.resync(options);

    expect(received.map(event => event.sequence)).toEqual([1, 2]);
  });
});

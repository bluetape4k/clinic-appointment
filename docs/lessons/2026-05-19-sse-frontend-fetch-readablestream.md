# Lesson: SSE with fetch + ReadableStream in Angular (Issues #30, #31)

## Root Cause / Context

`EventSource` (the browser's built-in SSE API) does not support custom headers.
This makes it incompatible with JWT Bearer token authentication, which requires
`Authorization: Bearer <token>` on every request.

## Decision

Use `fetch + ReadableStream` instead of `EventSource` to maintain JWT auth header
support without modifying the backend or switching to query-parameter token passing.

## Implementation Pattern

```typescript
streamBatchReschedule(params): Observable<RescheduleProgressEvent> {
  return new Observable(observer => {
    const controller = new AbortController();

    fetch(url, {
      headers: { Accept: 'text/event-stream', Authorization: `Bearer ${token}` },
      signal: controller.signal,
    }).then(async response => {
      const reader = response.body.getReader();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const blocks = buffer.split('\n\n');
        buffer = blocks.pop() ?? '';
        for (const block of blocks) { /* parse data: lines */ }
      }
    });

    return () => controller.abort(); // teardown aborts fetch
  });
}
```

## Key Invariants

- **`buffer.split('\n\n')` + `pop()`**: Retains the incomplete trailing block across
  chunk boundaries. This is mandatory for correct SSE parsing over streaming HTTP.
- **`TextDecoder({ stream: true })`**: Required for multi-byte UTF-8 character
  boundaries to not corrupt characters split across chunks.
- **`AbortController` teardown**: Registered as the Observable's return function so
  `unsubscribe()` always aborts the underlying fetch, preventing connection leaks.
- **`ngOnDestroy` calls `stopBatchStream()`**: Ensures the Subscription and the
  underlying AbortController are cleaned up when the component is destroyed.

## Pitfalls Caught in Review

1. **Hard-coded URL**: Initial implementation used `/api/reschedule/batch/stream`
   directly. Must use `environment.apiUrl` prefix to match all other service methods
   and support environment-specific API base URLs.
2. **`response.body!` non-null assertion**: Replaced with explicit null guard:
   `if (!response.body) { observer.error(...); return; }`.
3. **401 not distinguished**: Added explicit `response.status === 401` check with
   a Korean user-facing message, distinct from generic `SSE failed: N` errors.
4. **`fetch` bypasses Angular interceptors**: Acknowledged trade-off — SSE cannot
   use `HttpClient`. 401 and error handling must be implemented inline.

## Test Pattern for fetch-based SSE

```typescript
// Mock fetch with a ReadableStream SSE payload
vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, body: stream } as Response);

// Mock AbortController as a class (vi.fn() as arrow function fails with "not a constructor")
vi.spyOn(globalThis, 'AbortController').mockImplementation(
  class { abort = abortFn; signal = {} as AbortSignal; } as unknown as new () => AbortController,
);

// RescheduleService now injects AuthService — TestBed must provide a mock:
{ provide: AuthService, useValue: { getToken: () => 'test-token' } }
```

## Outcome

- 11/11 service tests passing
- `streamBatchReschedule()` emits progress events in real-time, completes on terminal event
- Component cleans up Subscription in `ngOnDestroy`
- CI: all checks pass

# 교훈: Angular에서 fetch + ReadableStream으로 SSE 처리 (Issues #30, #31)

## 원인과 배경

`EventSource`(브라우저 내장 SSE API)는 사용자 정의 header를 지원하지 않는다.
따라서 모든 요청에 `Authorization: Bearer <token>`이 필요한 JWT Bearer token
인증과 함께 사용할 수 없다.

## 결정

backend를 수정하거나 query parameter로 token을 전달하는 방식으로 바꾸지 않고 JWT
auth header를 유지하기 위해 `EventSource` 대신 `fetch + ReadableStream`을 사용한다.

## 구현 패턴

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

## 핵심 불변 조건

- **`buffer.split('\n\n')` + `pop()`**: chunk 경계에서 잘린 마지막 block을 다음
  chunk까지 유지한다. streaming HTTP에서 SSE를 올바르게 파싱하려면 반드시 필요하다.
- **`TextDecoder({ stream: true })`**: 여러 바이트로 구성된 UTF-8 문자가 chunk
  경계에서 잘려도 손상되지 않도록 반드시 사용한다.
- **`AbortController` teardown**: Observable의 return function으로 등록하므로
  `unsubscribe()`가 항상 underlying fetch를 중단해 connection leak를 막는다.
- **`ngOnDestroy`가 `stopBatchStream()` 호출**: component가 destroy될 때
  Subscription과 underlying AbortController를 정리한다.

## 리뷰에서 발견한 함정

1. **하드코딩한 URL**: 초기 구현은 `/api/reschedule/batch/stream`을 직접 사용했다.
   다른 service method와 맞추고 환경별 API base URL을 지원하려면
   `environment.apiUrl` prefix를 사용해야 한다.
2. **`response.body!` non-null assertion**: 명시적인 null guard로 교체했다.
   `if (!response.body) { observer.error(...); return; }`.
3. **401을 구분하지 않음**: 일반 `SSE failed: N` 오류와 구분되는 한국어 사용자
   메시지와 함께 `response.status === 401` 검사를 명시적으로 추가했다.
4. **`fetch`는 Angular interceptor를 우회함**: SSE는 `HttpClient`를 사용할 수
   없다는 trade-off를 인정했다. 401과 오류 처리는 inline으로 구현해야 한다.

## fetch 기반 SSE 테스트 패턴

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

## 결과

- service test 11/11 통과
- `streamBatchReschedule()`이 progress event를 실시간으로 방출하고 terminal
  event에서 완료한다.
- component가 `ngOnDestroy`에서 Subscription을 정리한다.
- CI: 모든 검사 통과

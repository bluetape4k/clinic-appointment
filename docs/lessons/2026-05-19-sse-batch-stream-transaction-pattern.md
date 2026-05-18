# SSE 배치 스트림 — 트랜잭션-over-SSE P0 버그 및 수정 패턴

## 작업 개요

Issue #29: GET /api/reschedule/batch/stream — 임시휴진 일괄 재배정 진행 상황을 SSE로 스트리밍하는 엔드포인트 구현.

## P0 버그: 트랜잭션 안에서 SSE 전송

### 문제

초기 설계에서 `onProgress` 콜백(SSE 이벤트 전송)이 `transaction {}` 블록 안에서 호출되었다.

```kotlin
// WRONG — DB 연결을 유지한 채 네트워크 I/O 수행
transaction {
    for (appointment in affected) {
        // ... DB 작업 ...
        onProgress(appointmentId, candidateCount) // SSE flush + 클라이언트 연결
    }
}
```

**결과:**
- JDBC 커넥션이 SSE flush + 클라이언트 연결 해제 동안 유지됨
- 클라이언트 연결이 끊기면 전체 배치가 롤백됨
- 커넥션 풀 고갈 위험

### 수정

트랜잭션을 두 레벨로 분리:

1. **공유 트랜잭션**: 상태 업데이트(PENDING_RESCHEDULE) + 이력 저장
2. **예약별 트랜잭션**: 후보 슬롯 저장 (각 예약마다 별도 트랜잭션)
3. **onProgress 호출**: 예약별 트랜잭션 커밋 후, DB 커넥션 해제 이후

```kotlin
// CORRECT — onProgress는 트랜잭션 바깥에서 호출
val affected = transaction { /* 상태 업데이트 */ }

for (appointment in affected) {
    val candidateCount = transaction { /* 후보 저장 */ }
    onProgress(appointmentId, candidateCount) // 커넥션 해제 후 안전
}
```

**Why:** 네트워크 I/O(SSE flush)와 DB 트랜잭션을 동시에 유지하면 커넥션 풀 고갈과 의도치 않은 롤백이 발생한다.

## P1: 정적 Executor 제거

### 문제

```kotlin
companion object : KLogging() {
    private val executor = Executors.newVirtualThreadPerTaskExecutor() // 앱 종료 시 닫히지 않음
}
```

`companion object`의 정적 executor는 스프링 컨텍스트와 수명이 다르고, 커넥션 수 제한 없음.

### 수정

```kotlin
Thread.ofVirtual().start { /* SSE 작업 */ }
```

각 SSE 요청마다 새 가상 스레드를 생성. `SseEmitter` 특성상 요청당 하나의 스레드만 필요하므로 스레드 폭발 위험 없음.

## P1: searchDays 범위 검증

`searchDays` 상한 없이 DB를 수십 일치 스캔 가능 → 무제한 부하.

```kotlin
searchDays.requireInRange(1, 30, "searchDays")
```

`GlobalExceptionHandler`가 `IllegalArgumentException`을 400으로 변환하므로 별도 처리 불필요.

## SSE 컨트롤러 설계 원칙

1. `SseEmitter(0L)` — 타임아웃 없음 (배치 크기에 비례한 시간 필요)
2. 가상 스레드로 백그라운드 처리 (`Thread.ofVirtual().start {}`)
3. `runCatching { }.onFailure { emitter.completeWithError(ex) }` — 에러 시 스트림 종료
4. 마지막에 반드시 `emitter.complete()` 호출
5. `done=true` 터미널 이벤트로 스트림 완료 신호

## 테스트 포인트

- `searchDays=0`, `searchDays=31` → 400
- 영향받는 예약 없음 → 터미널 이벤트만 (totalProcessed=0)
- 예약 존재 → progress 이벤트 + 터미널 이벤트 (totalProcessed=1)
- `RestClient.exchange { response.bodyTo(String::class.java) }` 패턴으로 SSE 스트림 전체 수신 가능

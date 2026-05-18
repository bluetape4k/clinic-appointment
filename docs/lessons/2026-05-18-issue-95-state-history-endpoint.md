# Issue #95: State History Endpoint

## 요약

예약 상태 변경 이력 조회 API (`GET /api/appointments/{id}/history`) 구현.

## 핵심 결정

1. **DTO 분리**: `AppointmentStateHistoryRecord`의 `AppointmentState` sealed class를 API에서 직접 노출하면
   `{"name":"REQUESTED"}` 형태로 직렬화됨. `StateHistoryResponse` DTO를 만들어 `fromState`/`toState`를
   `String`으로 평탄화하여 소비자 친화적 JSON 생성.

2. **단일 트랜잭션**: 존재 확인 + 이력 조회를 별도 `transaction {}`으로 분리하면 race condition 가능.
   하나의 트랜잭션으로 통합.

3. **정렬 전략**: DB 레벨에서 `changedAt DESC, id DESC`로 정렬하여 동일 timestamp 내에서도
   결정적 순서 보장. Kotlin 메모리 정렬 제거.

4. **ID 검증**: persisted record의 `id`는 non-null이어야 하므로 `?: 0L` 대신
   `requireNotNull("id")` 사용으로 silent failure 방지.

## 리뷰 결과

| Reviewer | P0 | P1 | P2 |
|----------|----|----|-----|
| Claude Code Tier 4 | 0 | 2 (fixed) | 3 |
| Codex CLI | 0 | 1 (cross-cutting, deferred) | 1 (fixed) |

- Codex P1 (clinic ownership check): 기존 모든 endpoint에 동일하게 부재. 별도 security issue로 분리 필요.

## 검증

- `./gradlew :appointment-api:test` — 15 tests, 0 failures, ~20s

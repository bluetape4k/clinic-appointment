# Issue #96: cancel() 하드코딩된 reason 제거

## 근본 원인

`AppointmentService.cancel()`이 caller의 reason 파라미터를 받지 않고
"Cancelled by user" 문자열을 3곳에 하드코딩. 감사 이력(state history)과
도메인 이벤트에 실제 취소 사유가 기록되지 않음.

## 수정 내용

| 파일 | 변경 |
|------|------|
| `AppointmentController.kt` | `@RequestParam(required = false) reason: String?` 추가 |
| `AppointmentService.kt` | `cancel(id, reason)` — `effectiveReason = reason ?: "Cancelled by user"` |

3곳 모두 `effectiveReason` 사용: state machine event, history record, domain event.

## 설계 결정

- DELETE body 대신 query parameter 선택 — HTTP spec 상 DELETE body는 비표준
- 기본값 유지 (`"Cancelled by user"`) — 하위호환성 보장
- `effectiveReason` 로컬 val로 일관성 확보

## 검증

- appointment-api + appointment-core: 전체 테스트 통과 (1m 29s)
- Tier 4 code review: PASS (0 blocking issues)

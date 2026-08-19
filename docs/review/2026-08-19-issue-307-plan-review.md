# Issue #307 구현 계획 리뷰

## 리뷰 범위

- 대상: `docs/superpowers/plans/2026-08-19-issue-307-ddd-event-transaction-boundary-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-08-19-issue-307-ddd-event-transaction-boundary-design.md`
- workflow run: `20260819T063400Z-d38bd54a`
- 실행: 여섯 관점과 통합 판정을 main fallback으로 기록하고, P2 보강 후 재검토했다.

## 관점별 판정

| 우선순위 | 관점 | 근거 | 조치 | 재검토 |
|---|---|---|---|---|
| P0/P1 없음 | 성능 | 새 hot path나 benchmark를 만들지 않고 세 행 원자성·connection identity·불필요한 round trip만 fixture에서 확인한다. | 실제 서비스 성능 결론을 내리지 않는 N/A 근거와 targeted 명령을 유지했다. | PASS |
| P0/P1 없음 | 안정성 | commit·rollback·동기 listener 실패·AFTER_COMMIT 실패·resource cleanup을 Task 3~4에 순서대로 배치했다. rollback retry는 새 aggregate와 증가한 attempt로 한 번만 검증한다. | P2였던 buffer 수명 모호성을 attempt/version assertion으로 보강했다. | PASS |
| P0/P1 없음 | 보안 | opaque ID만 event payload로 전달하고 synchronous listener 외부 I/O를 금지한다. 다중 transaction manager와 publisher opt-in 기본 경계를 negative context로 확인한다. | auto-configuration 미명시 context, class condition, ordering, 다중 manager fail-closed 검증을 Task 2·4에 추가했다. | PASS |
| P0/P1 없음 | 운영·복구 | dependencyInsight, H2 context startup, rollback 보류 조건, commit SHA와 diff/audit 결과를 구현 리뷰에 남긴다. | legacy `runCatching`의 기존 warn 관찰은 보존하고, 예외 분류·metric 확장은 후속 범위로 명시했다. | PASS |
| P0/P1 없음 | 개발자·API | Task 1 dependency → Task 2 RED → Task 3·4 GREEN → Task 5 docs → Task 6 verification 순서이며, Kotlin Exposed v1 import와 외부 proxy 호출을 고정한다. | deprecated import/receiver shadowing 점검과 Spring auto-configuration ordering을 추가했다. | PASS |
| P0/P1 없음 | 사용자·호출자 | 실제 public API와 README는 바꾸지 않고 AppointmentService KDoc에 outbox authority·signal·외부 bean/self-invocation 계약을 기록한다. suspend/coroutine은 bounded non-suspend 범위의 N/A로 증거를 남긴다. | README N/A와 coroutine cancellation N/A의 구체적 근거를 Task 6에 추가했다. | PASS |

## 필수 계획 검증

| 검증 항목 | 판정 | 근거 |
|---|---|---|
| 모든 설계 수용 기준 매핑 | PASS | 계획의 `수용 기준 매핑` 표가 10개 기준을 Task 1~6과 증거에 연결한다. |
| 실행 순서 | PASS | dependency와 RED가 GREEN보다 먼저이고, 문서·module verification은 구현 뒤에 온다. |
| 후속 task 의존성 | PASS | 각 task가 이전 task의 파일·fixture·검증 결과만 사용한다. |
| Spring auto-configuration | PASS | 명시적 opt-in, no-config baseline, class condition, ordering, single/dual manager를 확인한다. |
| Exposed 관용구 | PASS | Exposed v1 import, `transaction {}`, schema setup, receiver shadowing 점검을 명시했다. |
| 테스트 범위 | PASS | 성공·rollback·listener 실패·resource lifecycle·manager ambiguity·retry attempt를 포함한다. |
| 문서·언어 | PASS | 한국어 KDoc와 구현 리뷰 문서, README 변경 N/A 근거를 지정했다. |
| rollback·보류 | PASS | connection mismatch, partial commit, listener 호출, resource leak, context startup failure마다 기존 경계 유지 조건이 있다. |

## 남은 P2 처리

초기 계획 검토에서 지적된 세 가지 P2를 모두 계획에 반영했다.

1. rollback buffer에 attempt/version과 새 aggregate 재시도 assertion을 추가했다.
2. publisher auto-configuration의 opt-in baseline, class condition, ordering, dual manager fail-closed를 추가했다.
3. legacy `runCatching` 예외 분류·metric 확대는 현재 service 동작을 바꾸지 않는 후속 범위로 명시하고, 기존 warn 관찰과 opaque correlation ID를 보존한다.

## Writer gate

| 항목 | 결과 | 근거 |
|---|---|---|
| SPW-01 구조·목적 | PASS | 목표, 구조, 파일 책임, 순차 task를 분리했다. |
| SPW-02 용어·계약 | PASS | transaction 경계, outbox authority, signal, opaque ID를 일관되게 사용했다. |
| SPW-03 실행 가능성 | PASS | 정확한 파일·Gradle 명령·예상 결과·보류 조건을 적었다. |
| SPW-04 안전성·회귀 | PASS | 기존 service wiring과 legacy signal을 보존하고 실패 시 승격하지 않는다. |
| SPW-05 한국어 자연스러움 | PASS | `audit-korean-terms.mjs` 결과 `findings=0`이다. |

## 최종 판정

- P0: 0
- P1: 0
- P2: 구현 전에 모두 수정·명시적으로 보류됨
- Step 3-R: **PASS — 계획대로 구현 진행**
- README/공개 API 변경: N/A. 이번 변경은 test fixture와 기존 service KDoc에 한정한다.
- coroutine cancellation/concurrency stress: N/A. 승인된 bounded non-suspend pilot이며 source-level로 suspend annotation을 추가하지 않는다.

# Issue #17 closure 상태 이벤트 구현 계획 리뷰

## 판정

`PASS` — 구현을 시작할 수 있다. P0=0, P1=0, P2=0, P3=0.

리뷰 대상은 `docs/superpowers/specs/2026-08-09-issue-17-closure-status-event-design.md`와
`docs/superpowers/plans/2026-08-09-issue-17-closure-status-event-plan.md`의 최신 commit이다.
이번 문서 리뷰는 소스 mutation을 수행하지 않았으며, 구현 전에 계획의 누락을 다시 대조했다.

## 여섯 관점 대조

| 관점 | 확인 결과 | 근거 |
|---|---|---|
| Architecture | PASS | `AppointmentStatusEventWriter`를 notification port와 분리하고 생성자 필수 의존성으로 구성한다. core는 messaging을 참조하지 않으며 callback scope를 재구성하지 않는다. |
| Security | PASS | closure exact matcher가 generic POST보다 앞서고, controller/checker가 tenant ownership·role·non-empty `allowedClinicIds`·exact clinic membership을 함께 검증한다. client correlation은 trace metadata로만 사용하고 `httpRoot`가 server causation을 만든다. |
| Performance | PASS | precompute/write two-phase, slot key cache, `MAX_SLOT_CALCULATIONS=3_000`, candidate `2_000`, preflight/write `LIMIT 101`, SQL `<=2_700`, 2-thread latch lock harness가 계획에 있다. |
| Stability | PASS | write transaction은 snapshot ID/version/status를 재검증한 뒤 mutation하며 writer/history/codec/DB 오류를 전부 rollback한다. competing writer와 candidate overflow의 no-mutation assertion이 있다. |
| Testability | PASS | core context/closure RED-GREEN, messaging canonical/history, existing `AppointmentService` caller regression, API 503 failure-injection test configuration과 exact test method가 지정되어 있다. |
| Operations | PASS | bounded read-only runbook, low-cardinality log code와 duration/count telemetry, README 범위, SSE follow-up owner/acceptance task가 지정되어 있다. broker/registry/SLO는 명시적으로 PENDING이다. |

## P2/P3 재확인

- value class 비교는 `.value`를 사용한다.
- caller 입력 범위와 probe limit은 `requireInRange`를 사용한다.
- bounded probe 이름은 exact count를 암시하지 않는다.
- README 경로는 `appointment-api/README.ko.md`로 고정한다.
- generic `AppointmentOutboxWriter.statusChanged` caller와 closure caller를 모두 회귀 검증한다.

## 구현 전 조건

- RED 테스트가 새 port/context/signature 누락으로 실패하는 것을 먼저 확인한다.
- security integration test와 API failure-injection test가 기본 writer를 우회하지 않는지 확인한다.
- performance harness의 SQL/lock threshold가 실제 statement counter와 latch evidence를 남기는지 확인한다.
- GitHub Issue #17에 SSE status/lifecycle follow-up owner와 acceptance criteria를 PR readiness 전에 기록한다.

## 미검증 범위

실제 broker·Schema Registry 인증 endpoint, production MySQL/Flyway 적용, 운영 SLO, SSE status lifecycle은 이 계획 리뷰의 대상이 아니다. 이 항목들은 Issue #17의 closure PR에서 `PENDING`으로 보고한다.

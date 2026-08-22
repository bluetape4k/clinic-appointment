# #316 `@LeaderScheduled` 전환 구현 계획 검토

## 검토 범위와 기준

- 설계 기준: `docs/superpowers/specs/2026-08-22-issue-316-leader-scheduled-design.md` (설계 검토 승인 커밋 `94b649a5`)
- 계획 기준: `docs/superpowers/plans/2026-08-22-issue-316-leader-scheduled-plan.md` (최신 계획 커밋 `8b92ccb5`)
- 검토 기준: `bluetape-full-feature` Step 3-R, `step-3r-plan-review.md`, `review-perspectives.md`, Kotlin/Spring/coroutine checklist
- 검토 범위: 계획의 파일 책임, task ordering, RED/GREEN 명령, proxy/AOP·optional bean·health·metrics·DB fencing 경계, rollback 및 PR handoff
- 구현 source는 아직 변경하지 않았다. 따라서 이 문서는 계획 readiness를 검토하며 runtime/test PASS를 주장하지 않는다.

## 6개 관점 독립 검토

| 우선순위 | 관점 | 근거 | 판정 및 필요한 조치 | 재검토 |
|---|---|---|---|---|
| PASS | Performance | 계획 lines 156–175의 metric callback/lock-tag 검증과 lines 190–193의 blocking·polling·allocation·cardinality scan | reminder tick은 저빈도 단일 action이고 새 hot loop를 만들지 않는다. 정량 benchmark는 Issue #316 수용 기준이 아니므로 별도 성능 이슈로 확장하지 않고 정적 scan과 AOP metric 증거로 충분하다. | Step 5, Step 6 |
| PASS | Stability | 계획 lines 86–99의 cancellation/body 오류, lines 161–175의 proxy·lease·shutdown, lines 248–255의 Docker 실패·복귀 정책 | 성공·contention·backend 오류·취소·lease 만료·shutdown·재실행을 모두 별도 검증한다. Docker/Redis 미가동을 PASS로 대체하지 않는 규칙이 명시되어 있다. | Step 5 |
| PASS | Security | 고정 lock 이름(lines 24, 120), upstream lock-tag sanitization(lines 157–159), 새 입력·secret·권한 경계가 없다는 명시(lines 193–194) | 사용자 입력, secret, 인증/인가 경계를 새로 만들지 않으며 metric에 raw lock/tenant cardinality를 노출하지 않는다. 추가 보안 작업은 N/A다. | Step 5, Step 6 |
| PASS | Operator/Ops | conditional bean matrix(lines 132–148), auto-configuration 순서와 connection ownership(lines 139–145), rollback/PR/CI gate(lines 248–255, 217–232) | startup 조건, health, recorder, shared Redis owner, rollback, Issue/PR/CI read-back이 계획에 있다. merge는 새 명시 승인을 요구한다. | Step 4, Step 7 |
| PASS | Developer/API | 파일 지도(lines 15–52), dependency alias와 module graph N/A(lines 15–21), 순차 task 1–7 | 새 module/API/schema를 만들지 않고 upstream API를 adapter로 재사용한다. public runner/scheduler 설정 계약과 deprecated direct-call surface 보존이 명시되어 있다. | Task 1–7 |
| PASS | User/Caller | 기존 property key/API 보존(lines 54–60), 한국어 KDoc/lesson/PR 정책(lines 194, 197–201), README N/A(lines 194) | caller-visible configuration와 사용법은 바뀌지 않는다. 따라서 README 변경은 N/A이며, 내부 wiring 결정은 KDoc/lesson/PR body로 전달한다. | Step 6, Step 7 |

## 통합 계획 판정

| 검사 | 결과 | 근거 |
|---|---|---|
| SPW-01 요구사항·DoD 추적 | PASS | 계획 수용 기준 추적표가 설계의 9개 기준을 Task 2–7 증거에 연결한다(lines 234–246). |
| SPW-02 정확한 파일·명령·기대 결과 | PASS | 각 task에 파일 목록, RED/GREEN 순서, `./gradlew`, `git diff --check`, 자연스러움 audit 명령이 있다. |
| SPW-03 위험별 검증 | PASS | proxy와 `ScheduledTaskHolder`, optional classpath/host override, single `LeaderState`, `leader.aop.*`, DB fencing을 별도 테스트한다. |
| SPW-04 복귀·재실행·placeholder | PASS | artifact 전체에서 동적 PR 번호·추상 revert 표기를 제거했고, alias 실패·Docker 실패·proxy 실패의 PENDING/rollback 경계를 명시했다. |
| SPW-05 승인 게이트 | PASS | 사용자 승인 전 source implementation을 금지하고, merge에도 새 승인을 요구한다(lines 257–265, 230–232). |
| settings/module/CI/coverage | N/A | 새 module이 없으며 기존 `appointment-notification` build graph만 사용한다(line 17–18). |
| README/public docs | N/A | public API, property key, 운영 명령이 바뀌지 않고 내부 scheduler wiring만 변경된다(line 194). |
| Exposed/import/receiver 검토 | N/A | Exposed source나 transaction 경계를 변경하지 않는다. 기존 DB fencing 회귀만 수행한다. |

## P2/P3 및 환경 경계

| 우선순위 | 항목 | 처리 |
|---|---|---|
| P2 (환경 의존) | Redis 8 lease 만료·state read-back은 Docker/Colima가 필요하다. | 계획 lines 168–172, 252가 실패/skip을 성공으로 바꾸지 않고 해당 acceptance를 PENDING으로 남기도록 고정한다. 실제 실행 가능한 환경에서는 `Redis 8 singleton launcher` 결과를 필수 evidence로 수집한다. |
| P2 (범위 보류) | 정량 scheduler latency/throughput benchmark | Issue #316은 scheduler leader 경계·metrics baseline과 correctness를 요구하지만 새로운 hot path나 public performance contract를 만들지 않는다. 계획의 upstream metric 및 static scan으로 이번 범위를 닫고, 정량 비교가 필요하면 별도 성능 Issue로 분리한다. |

두 P2는 계획에 이미 보류 이유와 PASS 금지 조건이 있어 P0/P1 closure를 막지 않는다. 구현 중 실제 성능 회귀 또는 Docker 환경 실패가 발견되면 이 표를 갱신하고 해당 acceptance를 완료 상태로 올리지 않는다.

## 최종 verdict

- P0: **0**
- P1: **0**
- P2: **2 (환경·범위 보류, 위 근거와 재검증 조건 명시)**
- P3: **0**
- Step 3-R: **PASS**

계획은 구현 가능한 순서와 구체적인 검증 명령을 갖추었고, 승인 전 구현 금지·proxy 경계·DB fencing authority·optional dependency·upstream metrics namespace를 보존한다. 다음 게이트는 이 계획 문서에 대한 사용자의 명시 승인이다. 승인 전에는 `appointment-notification` Kotlin/Gradle source를 수정하지 않는다.

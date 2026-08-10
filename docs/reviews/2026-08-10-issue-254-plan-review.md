# #254 구현 계획 6-lens 검토

검토 대상: `docs/superpowers/plans/2026-08-10-issue-254-leader-micrometer-plan.md`,
승인 설계와 현재 `appointment-notification` 구조.

| 우선순위 | 영역 | 발견 | 계획 수정 |
|---|---|---|---|
| P2 | 요구사항 매핑 | leader action, four meters, failure/cancel/Redis, fallback, DB authority가 Task 1~4에 매핑됨. | 수정 없음 |
| P2 | 순서 | dependency resolve → RED → implementation → auto-config → full test → docs/evidence 순서가 선행 산출물과 일치한다. | 수정 없음 |
| P1 | 호환성 | guard 제거만 하면 직접 생성 caller가 깨질 수 있다. | Task 2를 수정해 deprecated `ReminderRecoveryTriggerGuard?`와 기존 constructor 위치를 보존하고 auto-config provider만 제거했다. |
| P2 | 테스트 | success, skip, action/Redis failure, cancellation, metric active cleanup, no-registry fallback을 명시했다. | 실제 Redis/Testcontainers는 scope 밖이며 lesson의 gap으로 기록한다. |
| P2 | Spring | leader bean interface return, `@ConditionalOnBean(StatefulRedisConnection)`, decorator class guard, optional `MeterRegistry`, runner provider를 명시했다. | context startup regression을 Task 3에 유지 |
| P2 | README/KDoc/GitHub | public scheduled behavior가 바뀌므로 README 두 파일과 Korean issue/lesson/review가 필요하다. | Task 5 Step 0과 Step 4/5에 명시했다. |
| P2 | 성능/안정성 | blocking group elector와 scheduler thread, active gauge/lease cleanup을 놓치면 안 된다. | 기존 `runSynchronously` 재사용, decorator cleanup assertion, implementation review를 추가했다. |
| P2 | 보안/운영 | lock tag raw 노출과 registry 부재를 다룬다. | `redacted-lock` assertion 및 optional raw fallback을 유지 |
| P2 | 모듈/CI | 새 모듈이나 settings 등록은 없고 변경은 catalog alias와 notification 모듈이다. | module build/dependencyInsight 및 no-PR gate 기록 |

## 통합 판정

- 설계의 모든 DoD가 Task 1~5의 구체 단계·명령·파일에 매핑된다.
- Kotlin cancellation/null safety와 optional constructor compatibility가 계획에
  명시되어 있다.
- `README.md`/`README.ko.md`와 KDoc/issue text의 Korean artifact 요구를
  누락하지 않는다.
- P0=0, P1=0으로 수렴했다. P2는 구현/검증 증거로 추적한다.

결론: 계획 검토 PASS (P0=0, P1=0). 구현 단계로 진행한다.

# #254 설계 문서 6-lens 검토

검토 대상: `docs/superpowers/specs/2026-08-10-issue-254-leader-micrometer-design.md`
및 issue #254 원문, `appointment-notification` 현재 구현, bluetape4k-leader
0.5.0 공식 source.

| 우선순위 | 관점 | 근거 | 조치 | 재검토 |
|---|---|---|---|---|
| P2 | 성능 | scheduled thread에서 기존 `runSynchronously`와 blocking group elector를 사용한다. | 새 coroutine builder를 추가하지 않고 기존 동기 Spring 경계를 재사용한다. acquire/metric overhead는 module test와 구현 review에서 확인한다. | 구현 review |
| P2 | 안정성 | action 실패·취소 시 broad `Exception` catch가 cancellation을 흡수할 위험이 있다. | `CancellationException`을 먼저 재전파하고 공식 decorator/lettuce finally cleanup을 테스트 acceptance로 고정한다. | runner test |
| P2 | 보안 | lock name을 metric tag로 그대로 내보내면 cardinality/식별자 노출 위험이 있다. | decorator 기본 sanitization을 사용하고 `redacted-lock` assertion을 추가한다. | micrometer test |
| P2 | 운영 | `MeterRegistry`가 없는 로컬 context와 Redis 장애의 진단 경계가 필요하다. | registry optional raw elector fallback, Redis failure tick absorption, issue에 실 Redis gap을 별도 기록한다. | auto-config + lesson |
| P1 | 개발자/API | `ReminderRecoveryTriggerGuard` 제거는 직접 생성 caller의 source compatibility를 깨뜨릴 수 있다. | 설계를 수정해 타입/선택적 인자를 deprecated legacy로 유지하고, auto-config scheduled path에서만 provider를 제거한다. | 설계 integration |
| P2 | 사용자/호출자 | Redis가 없을 때 reminder가 전혀 실행되지 않으면 단일 인스턴스 설치가 깨진다. | optional elector `null` direct path를 보존하고 runner 테스트로 고정한다. | auto-config test |
| P2 | 통합 | 공식 group decorator는 blocking API이고 DB 정확성은 별도 lease/fencing에 있다. | scheduler paging은 유지하고 leader action만 바운더리로 감싸며 DB schema/policy는 제외한다. | 최종 review |

## main-session integration

- P1은 deprecated 호환 설계로 수정했으며 최신 spec은 P0=0/P1=0이다.
- 6개 관점의 P2는 각각 계획 Task 1~5의 테스트·문서·rollback 증거에 매핑했다.
- 공식 source는 `InstrumentedLeaderGroupElector`의 `runIfLeader`와
  `LeaderMetricTagOptions.Default`를 직접 확인했다. suspend group decorator가
  별도로 없어 공식 blocking decorator를 선택했다.
- 변경은 notification module과 catalog alias에 한정된다. DB lease/fencing,
  outbox policy, Actuator surface, frontend는 scope 밖이다.
- 외부 Redis/Testcontainers 및 CI/production은 구현 후 별도 PENDING 증거로
  남기며 local unit/context tests를 대체하지 않는다.

결론: 설계 검토 PASS (P0=0, P1=0). 계획 검토 단계로 진행한다.

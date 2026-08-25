# Issue #317 설계 inline review

## 검토 범위와 근거

- 대상: `docs/superpowers/specs/2026-08-25-issue-317-leader-scheduled-policy-design.md`
- 현재 clinic ref: `develop`의 `NotificationSchedulingRunners.kt`,
  `NotificationAutoConfiguration.kt`, reminder scheduling tests
- upstream 근거: bluetape4k-leader Issue #603, PR #761, develop의
  `LeaderScheduledPolicyProperties`·`LeaderScheduledPolicyRegistry`·
  `LeaderScheduledPolicyBeanPostProcessor`·auto-configuration source
- 의존성 근거: clinic leader `0.5.0`, Central Snapshots timestamp
  `1.0.0-20260824.195548-7`
- 방식: 사용자 지침에 따라 별도 reviewer agent 없이 main session inline review로
  여섯 관점을 순서대로 적용하고 통합했다.

## 관점별 결과

| 관점 | 검토 결과 | 심각도 |
|---|---|---|
| 성능 | property registry는 startup immutable lookup만 추가하고 scheduler tick마다 새 scheduler/trigger를 만들지 않는다. 기존 fixed delay와 DB claim 경로를 유지한다. | P0/P1 없음 |
| 안정성 | plain `@Scheduled` 우회를 막기 위해 factory·enabled policy·selector 조건을 runner 생성 경계에 둔다. `lease-time >= suspendBridgeTimeout` safety bound와 contention/backend 오류/cancellation/context close를 함께 검증한다. | P0/P1 없음 |
| 보안 | lock name·backend bean·SpEL name은 upstream startup validation을 사용하고, 설정 예시에 secret을 넣지 않는다. wildcard/regex selector를 금지한다. | P0/P1 없음 |
| 운영 | disabled rollback, profile 예시, immutable timestamp pin, stable release 전환, DB claim/fence 책임 경계를 명시했다. | P0/P1 없음 |
| 개발/API | upstream `LeaderScheduledPolicyProperties`와 registry를 그대로 재사용하고 clinic 전용 policy model을 만들지 않는다. `@Scheduled` delay와 leader policy의 책임이 분리되어 있다. | P0/P1 없음 |
| 사용자/호출자 | exact `beanName#methodName` 예시와 잘못된 selector/duration/backend의 startup 거부를 문서화했다. policy 누락 시 unguarded 실행을 허용하지 않는다. | P0/P1 없음 |

## 통합 판정

- **중복/모순:** `@LeaderScheduled`의 explicit annotation 우선순위 때문에
  property가 적용되지 않는 대안을 제거했고, 선택안은 plain `@Scheduled`로
  일관된다.
- **미확정 가정:** `lettuceLeaderElectionFactory` bean 이름은 현재 clinic
  auto-configuration과 기존 테스트가 사용하는 이름을 implementation 단계에서
  다시 확인한다. 이름이 바뀌었으면 설정·테스트·문서를 함께 갱신한다.
- **의존성 위험:** stable 1.0.x가 아직 없으므로 timestamp artifact를 직접
  고정한다. lockfile/verification metadata, PR body, rollback 문서에 동일
  값을 반복해 drift를 방지한다.
- **범위 누수:** 다른 `@Scheduled` 작업, 동적 reload, DB claim/fence 의미론은
  수정하지 않는다.

## 결과

| Priority | Count | Disposition |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 없음 |
| P3 | 0 | 없음 |

`git diff --check`와 Korean terminology audit가 통과했다. 설계 review는
P0=0/P1=0으로 PASS이며, 구현 전환 시 exact bean name·timestamp metadata·조건부
runner 생성 테스트를 먼저 증명한다.

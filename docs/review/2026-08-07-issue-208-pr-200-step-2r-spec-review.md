# PR #200 프로필 변경 예약 재평가 명세 2-R 검토

검토일: 2026-08-07
대상 PR: [#200](https://github.com/bluetape4k/clinic-appointment/pull/200)
대상 exact head: `4f7b41a498dd1c0b4dc9fea41ed1721fe9e8d53f`
검토 단계: Type A Step 2-R (명세)

## 근거와 범위

- 명세: `docs/superpowers/specs/2026-07-30-profile-change-reservation-reevaluation-design.md`
- 계획: `docs/superpowers/plans/2026-07-30-profile-change-reservation-reevaluation-plan.md`
- 관련 이슈: [#200](https://github.com/bluetape4k/clinic-appointment/issues/200), [#208](https://github.com/bluetape4k/clinic-appointment/issues/208)
- 기준 계약: `PROPOSED`/`HELD`만 자동 재평가, `CONFIRMED` 보호, bounded catch-up, clinic 공정성, CRM 개인정보 비복제

PR 본문에 있던 일반적인 “six perspectives” 문구는 durable review evidence로 사용하지 않았다. 이 문서는 명세를 여섯 독립 관점과 main-session 통합 관점으로 다시 읽은 exact-head 백필 기록이다.

## 여섯 관점

| 관점 | 판단 | P0/P1 | 근거 또는 조치 |
|---|---|---:|---|
| 성능 | 범위 조회, page/cursor, 전역·clinic 상한이 bounded contract로 고정됨 | 0/0 | 무제한 전체 환자 스캔·coroutine 생성 금지 |
| 안정성 | lease, retry, stale revision, CAS, 기술 실패 시 hold 보존이 분리됨 | 0/0 | `HELD` 대체 전 기존 allocation 보호 |
| 보안·개인정보 | CRM 원본·연락처·특징을 예약서비스에 저장하지 않음 | 0/0 | fingerprint와 최소 assessment만 사용 |
| 운영 | catch-up, drain/redrive, SLO, 저카디널리티 지표가 명세에 포함됨 | 0/0 | 운영 endpoint와 실패 복구 책임 분리 |
| 개발자/API | policy override, event key, 상태 전이와 transaction 경계가 명시됨 | 0/0 | 구현 구체화는 3-R 계획으로 위임 |
| 사용자·호출자 | `CONFIRMED` 자동 변경 금지와 hold 보호가 외부 계약으로 고정됨 | 0/0 | 확정 변경은 별도 proposal·동의 필요 |

## 통합 및 역사적 주의사항

명세 자체에는 P0/P1 blocker가 없다. 다만 exact implementation head의 구현 결함은 명세 PASS와 별개다. PR #200 exact head에서 Actuator adapter와 scheduler에 `runBlocking`이 사용된 사실은 후속 구현 6-R에서 P1으로 기록했으며, PR [#215](https://github.com/bluetape4k/clinic-appointment/pull/215)의 `9899dacbd62eaec02b9e2ee51a2162715fc9ef82`에서 Reactor coroutine bridge와 suspend scheduler로 수정했다.

## 판정

**Step 2-R: PASS — P0=0, P1=0.** 이 PASS는 명세 gate에 한정된다. 구현 gate는 아래 3-R과 remediation 후 6-R 기록을 함께 참조해야 한다.

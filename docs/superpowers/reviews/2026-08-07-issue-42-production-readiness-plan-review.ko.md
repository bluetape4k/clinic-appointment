# Issue #42 production readiness spec/plan review

검토 대상은 `2026-08-07-issue-42-production-readiness-design.md`와
`2026-08-07-issue-42-production-readiness-plan.md`의 현재 worktree 버전이다.
여섯 관점에서 동일한 요구사항과 현재 Issue #42 구현을 대조하고, main-session에서
중복·충돌·증거 공백을 통합했다.

| Priority | 관점 | 근거 | 조치 | 재검토 |
|---|---|---|---|---|
| P1 | 안정성 | Task 4가 crash/rebalance를 “정확히 한 번”으로 표현하면 at-least-once 계약과 충돌할 수 있음 | 문서와 테스트는 side effect idempotency/inbox 상태 및 최종 commit을 증명하고, broker redelivery 자체는 허용하도록 해석 | 안정성 lane |
| P1 | 보안 | Schema Registry credential이 Spring binding에 직접 들어가면 secret이 configuration snapshot에 남을 위험 | resolver port와 secret 비로그 계약을 명시하고, 테스트에서 header 존재/진단 비노출만 확인 | 보안 lane |
| P1 | 운영 | 실제 production endpoint/credential/cluster가 없는 상태에서 local singleton을 production proof로 오인할 위험 | 설계와 계획 모두 production execution을 별도 PENDING으로 분리하고 PR DoD에 unchecked 항목을 남김 | 운영 lane |
| P2 | 성능 | lag·lock contention 측정의 source와 aggregation이 계획 단계에서 모호함 | 기존 PostgreSQL `kotlinx-benchmark` fixture를 source로 고정하고, JSON report와 chart를 같은 source에서 생성 | 성능 lane |
| P2 | 개발자/API | listener adapter가 public `@KafkaListener`인지 factory listener인지 불명확하면 API 범위가 커질 수 있음 | 공통 adapter는 container callback port로 제한하고, public route는 existing security bean이 있을 때만 조건부 wiring | 개발자 lane |
| P2 | 사용자/호출자 | replay 승인 실패와 cross-tenant 시 오류 의미가 runbook에 없으면 운영자가 재시도할 수 있음 | Task 6 테스트와 runbook에 unauthorized/cross-scope/invalid-range의 side-effect-free 결과를 추가 | 호출자 lane |
| P3 | 문서 | benchmark 수치를 deployment SLO와 비교할 기준이 없음 | README와 chart caption에 “benchmark evidence, not deployment SLO”를 유지하고 production SLO는 PENDING으로 표시 | 문서 검증 |

## 통합 결과

- P0: 0
- P1: 0 (위 P1 세 건은 계획에 이미 fail-closed/PENDING/at-least-once 조치가 반영됨)
- P2: 4 (구현 단계에서 지정된 테스트·runbook·source ledger로 처리)
- P3: 1 (문서 표기 유지)

계획 순서는 docs → migration → registry → listener → observability/benchmark → replay →
verification으로 의존성을 만족한다. Spring 조건부 bean, Exposed transaction 경계,
singleton launcher, Kotlin test idiom, Korean internal docs/English public PR 규칙을
검증 명령과 함께 포함했다. 별도 사용자 결정이나 설계 재승인은 필요하지 않다.

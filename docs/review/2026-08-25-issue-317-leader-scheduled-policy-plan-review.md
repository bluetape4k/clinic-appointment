# Issue #317 구현 계획 inline review

## 검토 범위

- 대상: `docs/superpowers/plans/2026-08-25-issue-317-leader-scheduled-policy-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-08-25-issue-317-leader-scheduled-policy-design.md`
- 검토 방식: 사용자 지침에 따라 여섯 관점을 main session에서 inline으로
  수행하고 통합했다. 별도 reviewer agent나 추가 worktree는 사용하지 않았다.
- 현재 증거: 설계 commit `934908b7`, baseline
  `NotificationSchedulingRunnersTest` 13 passing, upstream policy PR #761,
  Central timestamp `1.0.0-20260824.195548-7`.

## 관점별 결과

| 관점 | 계획 검증 | 심각도 |
|---|---|---|
| 성능 | Task 5가 tick당 scheduler/reflective registry 생성 여부와 blocking/round-trip drift를 확인하고 기존 fixed delay를 보존한다. | P0/P1 없음 |
| 안정성 | Task 2~3이 policy disabled/factory 부재/selector 누락/짧은 lease를 RED/GREEN으로 고정하고, Task 5가 cancellation·backend·context close와 순차 container 실행을 검증한다. | P0/P1 없음 |
| 보안 | Task 4가 secret 없는 설정 예시, exact selector, backend bean/SpEL startup validation, wildcard·regex 금지를 문서화한다. | P0/P1 없음 |
| 운영 | Task 1의 immutable timestamp lock/hash, Task 4의 profile rollback, Task 6의 lesson/PR evidence가 stable release 전환과 운영 경계를 남긴다. | P0/P1 없음 |
| 개발/API | Task 3이 `LeaderScheduledPolicyProperties`/registry/BPP를 재사용하고, `@Scheduled` delay와 leader metadata를 분리한다. auto-configuration 조건과 registration ordering을 positive/negative context로 검증한다. | P0/P1 없음 |
| 사용자/호출자 | Task 4가 두 README와 application/test profile을 같은 YAML 계약으로 동기화하고, 필드 범위·cardinality·DB claim/fence 책임을 설명한다. | P0/P1 없음 |

## 통합 traceability 검증

| 확인 항목 | 결과 |
|---|---|
| 설계 acceptance 1~7 각각의 구현 task | 모두 Task 1~6에 연결 |
| dependency -> test -> production -> docs -> final review 순서 | 순서가 역전되지 않음 |
| Spring 조건/등록 순서 | upstream policy auto-configuration을 context에 직접 등록하고 factory/registry/property positive·negative를 검증 |
| 짧은 lease 의미 | `lease-time` 명시 및 `suspendBridgeTimeout` 이상 safety bound로 고정 |
| Exposed/DB 경계 | production Exposed 코드를 변경하지 않고 DB claim/fence 책임만 문서·테스트 계약으로 유지 |
| rollback/재실행 | catalog 실패, startup guard 실패, container/network 실패, PR P0/P1별 복귀 지점이 있음 |
| public docs/locale | `README.md`와 `README.ko.md`, application/test YAML, Korean lesson 포함 |

## 발견과 처리

초기 계획 review에서 upstream validator만으로는 reminder bounded call보다
짧은 `lease-time`을 의미상 거부하지 않는다는 gap을 확인했다. 설계와 계획에
`lease-time >= NotificationProperties.worker.suspendBridgeTimeout`을 추가하고,
`lease-time=10s`, `suspend-bridge-timeout=30s` startup negative test를 넣었다.
이는 upstream policy model을 복제하지 않고 clinic workload의 one-bean safety
bound만 추가하는 최소 보완이다. 수정 후 여섯 관점과 통합 검토를 다시 실행했다.

## 결과

| Priority | Count | Disposition |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 없음 |
| P3 | 0 | 없음 |

계획 문서의 SPW-01~05, Korean terminology audit, `git diff --check`,
placeholder/identifier consistency self-review가 PASS다. Step 4 전환 조건은
Task 1 catalog resolution, Task 2 RED, 설계/계획 commit이며 이후 구현을 시작한다.


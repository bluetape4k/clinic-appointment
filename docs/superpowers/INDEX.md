# 슈퍼파워스 작업 인덱스

| # | 날짜 | 주제 | 상태 | 링크 |
|---|------|------|------|------|
| 1 | 2026-04-20 | H2+PostgreSQL+MySQL8 multi-DB 테스트 + Virtual Threads | ✅ 완료 | [spec](specs/2026-04-20-multi-db-test-virtual-threads-design.md) / [plan](plans/2026-04-21-multi-db-test-virtual-threads-plan.md) |
| 2 | 2026-07-24 | 예약 생성 idempotency | ✅ 완료 | [spec](specs/2026-07-24-appointment-idempotency-design.md) / [plan](plans/2026-07-24-appointment-idempotency.md) |
| 3 | 2026-07-26 | 예약 플랜과 수용량 기반 구현 | ✅ 완료 | [spec](specs/2026-07-26-appointment-plan-and-capacity-design.md) / [plan](plans/2026-07-26-appointment-plan-foundation.md) |
| 4 | 2026-07-27 | 예약 정책 기반 구현 | ✅ 완료 | [spec](specs/2026-07-27-scheduling-policy-foundation-design.md) / [plan](plans/2026-07-27-scheduling-policy-foundation-plan.md) / [API](../api/scheduling-policy.md) / [runbook](../runbooks/scheduling-policy-activation.md) / [review](../review/2026-07-28-scheduling-policy-task10-review.md) |
| 5 | 2026-07-29 | 설계 시각 동반 문서 이력 | 🚧 clinic 공개 준비 | [spec](specs/2026-07-29-visual-companion-history-design.md) / [plan](plans/2026-07-29-visual-companion-history-plan.md) |
| 6 | 2026-07-30 | 프로필 변경 기반 진행 중 예약 재평가 | 📝 구현 계획 완료 | [spec](specs/2026-07-30-profile-change-reservation-reevaluation-design.md) / [plan](plans/2026-07-30-profile-change-reservation-reevaluation-plan.md) |
| 7 | 2026-08-08 | Issue #208 Type A review gate 백필 및 현재 remediation 정합화 | ⚠️ 차단: historical gate 미증명 / current remediation PASS | [status](../review/2026-08-07-issue-208-retrospective-gate-status.md) / [current seven-tier](../review/2026-08-08-issue-208-current-remediation-seven-tier.md) / [lesson](../lessons/2026-08-07-issue-208-type-a-review-gate-backfill.md) / [reviews](../review/2026-08-07-issue-208-pr-200-step-2r-spec-review.md) |
| 8 | 2026-08-08 | Issue #204 notification outbox readiness와 외부 rollout 경계 | ⚠️ 외부 rollout HOLD / local readiness PASS | [readiness](../review/2026-08-08-issue-204-notification-outbox-readiness.md) / [lesson](../lessons/2026-08-08-issue-204-readiness-boundary.md) / [runbook](../runbooks/notification-outbox-operations.md) |
| 9 | 2026-08-12 | Issue #34 환자 예약 취소·알림 schema v2·포털 상태 전이 | ⚠️ 로컬 구현·모듈 검증 PASS / 성능·보호된 외부 gate PENDING | [spec](specs/2026-08-12-issue-34-patient-commitment-design.md) / [plan](plans/2026-08-12-issue-34-patient-commitment-plan.md) / [risk](risk/2026-08-12-issue-34-patient-commitment-risk-register.ko.md) / [review](../reviews/2026-08-12-issue-34-implementation-review.ko.md) |
| 10 | 2026-08-19 | Issue #307 DDD 이벤트와 `@Transactional` 경계 | 📝 설계·계획 리뷰 PASS / 구현 진행 | [spec](specs/2026-08-19-issue-307-ddd-event-transaction-boundary-design.md) / [plan](plans/2026-08-19-issue-307-ddd-event-transaction-boundary-plan.md) / [spec review](../review/2026-08-19-issue-307-spec-review.md) / [plan review](../review/2026-08-19-issue-307-plan-review.md) |

✅ 완료: 4  📝 계획 완료: 1  ⚠️ 차단/HOLD: 3  🚧 진행 중: 1

## 공개 시각 동반 문서

Markdown 원본이 규범이며 HTML은 이해를 돕는 자체 포함형 시각 동반 문서입니다.
`public`은 중앙 Pages 기준 데이터의 공개 허용목록 포함 여부를 뜻한다.

| 설계 | 원본 Markdown | English HTML | 한국어 HTML | Profile | Public |
|---|---|---|---|---|---|
| 예약 플랜·수용량 | [source](specs/2026-07-26-appointment-plan-and-capacity-design.md) | [English](specs/2026-07-26-appointment-plan-and-capacity-design.en.html) | [한국어](specs/2026-07-26-appointment-plan-and-capacity-design.html) | `hybrid` (`simulation` 기본) | `true` |
| 예약 정책 기반 구현 | [source](specs/2026-07-27-scheduling-policy-foundation-design.md) | [English](specs/2026-07-27-scheduling-policy-foundation-design.en.html) | [한국어](specs/2026-07-27-scheduling-policy-foundation-design.html) | `hybrid` (`simulation` 기본) | `true` |
| 상품 예약 운영 특성 분류 | [source](specs/2026-07-29-issue-184-visit-commitment-design.md) | [English](specs/2026-07-29-issue-184-product-scheduling-classification.en.html) | [한국어](specs/2026-07-29-issue-184-product-scheduling-classification.html) | `simulation` | `true` |
| 패키지 상품 구성 | [source](specs/2026-07-29-issue-184-visit-commitment-design.md) | [English](specs/2026-07-29-issue-184-package-product-composition.en.html) | [한국어](specs/2026-07-29-issue-184-package-product-composition.html) | `simulation` | `true` |
| 상품 실행 BOM의 예약 전개 흐름 | [source](specs/2026-07-29-issue-184-visit-commitment-design.md) | [English](specs/2026-07-29-issue-184-product-bom-to-appointment-flow.en.html) | [한국어](specs/2026-07-29-issue-184-product-bom-to-appointment-flow.html) | `simulation` | `true` |

# Issue #184 Step 2-R 설계 명세 검토

## 검토 범위

- 기준 설계 커밋: `09c8ca5`
- 최종 검토 대상:
  - `docs/superpowers/specs/2026-07-29-issue-184-visit-commitment-design.md`
  - `docs/superpowers/specs/2026-07-29-issue-184-product-scheduling-classification.html`
  - `docs/superpowers/specs/2026-07-29-issue-184-package-product-composition.html`
  - `docs/superpowers/specs/2026-07-29-issue-184-product-bom-to-appointment-flow.html`
- 검토 방식: 6개 독립 관점과 본 세션 통합 검토
- 명령 제한: 문서 읽기와 HTML 브라우저 smoke test만 수행하고 빌드·DB·Testcontainers
  명령은 실행하지 않았다.

## 최초 검토 결과

| 관점 | P0 | P1 | P2 | P3 | 주요 보완 항목 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 2 | 2 | 0 | Plan 계산 상한, 증분 재계산, 잠금·index·성능 목표, 보존 정책 |
| 안정성 | 0 | 2 | 2 | capacity bucket 직렬화, 예약 교체 자기 충돌, version gap과 멱등 선점 |
| 보안 | 0 | 1 | 1 | 외부 event 안전 역직렬화, Gateway 우회 차단 |
| 운영 | 0 | 2 | 2 | 격리·redrive, V10 rollout/rollback, metric·owner |
| 개발자/API | 0 | 2 | 2 | legacy write 경계, endpoint·event·consent 계약 |
| 사용자/호출자 | 0 | 3 | 2 | reason code, migration 격리 상태, 시각 자료의 강제 제약·lifecycle 표현 |
| 통합 검토 | 0 | 1 | 0 | 0 | 검토한 대안과 기각 사유의 명시 |

최초 합계는 `P0=0`, `P1=13`, `P2=11`, `P3=1`이다. P0/P1이 남아 있으므로
Step 3 진입을 차단하고 설계 명세와 시각 자료를 보완했다.

## 통합 보완

- Plan graph, 반복, 탐색 기간, candidate와 proposal 수에 platform safety ceiling을
  두고 일반·최대 Plan의 p95/p99 목표를 정의했다.
- 변경된 항목과 의존·자원·시간 dirty set만 다시 계산하도록 범위를 제한했다.
- capacity bucket row CAS/lock, 활성 allocation index, 교체 대상 allocation 제외,
  deadlock retry 예산을 정의했다.
- 외부 event envelope, DTO allowlist, payload 크기·깊이, polymorphic typing 금지와
  domain mapping 전 격리를 정의했다.
- version gap, poison message, 권한 있는 redrive, 감사 기록과 보존 기간을 정의했다.
- expand-only V10, shadow ingest, clinic allowlist, feature flag rollback과 backup
  drill을 정의했다.
- legacy API가 새 commitment row를 변경하지 못하도록 write 경계를 확정했다.
- 고객·관리자 endpoint, 조건부 HTTP header와 stable reason code를 구체화했다.
- 구성 상품의 강제 제약을 HTML에서 읽기 전용으로 표시하고, 패키지 운영 정책과
  분리했다.
- proposal 상태를 동시 상태가 아니라 lifecycle 예시로 표현했다.
- 분리하지 않은 단일 aggregate, 패키지 단일 시간, 예약서비스의 BOM 재해석 등
  검토 후 기각한 대안을 명시했다.

## 최종 재검토 결과

| 관점 | P0 | P1 | 판정 |
|---|---:|---:|---|
| 성능 | 0 | 0 | PASS |
| 안정성 | 0 | 0 | PASS |
| 보안 | 0 | 0 | PASS |
| 운영 | 0 | 0 | PASS |
| 개발자/API | 0 | 0 | PASS |
| 사용자/호출자 | 0 | 0 | PASS |
| 본 세션 통합 검토 | 0 | 0 | PASS |

P2/P3 항목도 별도 후속 이슈로 남기지 않고 이번 설계 보완에 포함했다. 최종
판정은 `P0=0`, `P1=0`이며 Step 2-R은 통과했다.

## 검증 근거

- 세 시각 자료 링크 존재와 파일 유효성 확인
- 패키지 화면의 강제 제약 read-only, 운영 정책 editable 상태 확인
- 상품 BOM 흐름의 6단계와 proposal lifecycle 표기 확인
- 두 변경 HTML의 브라우저 console error 0건
- 미완성 표식 검색 결과 0건
- `git diff --check` 통과

## Step DoD

| Check | Action | Status | Evidence | Failure / Next Action |
|---|---|---|---|---|
| A-03 / Step 2 | 승인된 업무 결정을 한국어 설계 명세와 HTML로 보존 | PASS | 설계 명세와 시각 자료 3개 | 없음 |
| Step 2-R 독립 검토 | 6개 관점 검토와 P0/P1 보완·재검토 | PASS | 최초·최종 검토표, 최종 `P0=0/P1=0` | 없음 |
| Step 2-R 통합 검토 | 중복 제거, 심각도 정규화, 대안·증거·문서 정합성 확인 | PASS | 이 문서의 통합 보완과 최종 판정 | 없음 |

Required checks: 3/3; N/A: 0; Blocked: 0.

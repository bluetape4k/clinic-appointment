# Issue #184 Step 3-R 구현 계획 검토

## 검토 범위

- 기준 설계:
  `docs/superpowers/specs/2026-07-29-issue-184-visit-commitment-design.md`
- 구현 계획:
  `docs/superpowers/plans/2026-07-29-issue-184-visit-commitment-plan.md`
- 검토 방식: 6개 독립 관점과 본 세션 통합 검토
- 명령 제한: 문서와 현재 source pattern만 읽었으며 build, DB, Testcontainers,
  Gatling은 실행하지 않았다.

## 최초 검토 결과

| 관점 | P0 | P1 | P2 | P3 | 주요 보완 항목 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 0 | 2 | 1 | 조회별 index/EXPLAIN, benchmark dataset·표본·artifact, 혼합 contention |
| 안정성 | 0 | 0 | 0 | 0 | lifecycle, rollback, retry, 다중 DB recovery 계약 PASS |
| 보안 | 0 | 0 | 0 | 0 | Gateway trust, scope, event allowlist, PII, redrive authority PASS |
| 운영 | 0 | 2 | 1 | 1 | 부분 활성화 rollback, owner/alert/redrive, retention test, runbook 시점 |
| 개발자/API | 0 | 0 | 1 | 1 | endpoint별 service signature와 ETag, 실행 가능한 Kotlin diagnostics |
| 사용자/호출자 | 0 | 0 | 0 | 0 | 가예약·승인·동의·기존 예약 보호·API 사용성 PASS |
| 본 세션 통합 | 0 | 2 | 1 | 0 | 실제 진료 공간, 미확정 v2 nullable projection, 29개 인수 기준 추적 |

최초 합계는 `P0=0`, `P1=4`, `P2=5`, `P3=3`이다. P0/P1이 남아 있어
Step 4 진입을 차단하고 계획을 보완했다.

## 통합 보완

- `TreatmentSpaces` table/repository를 추가해 표시용 room type이 아니라 실제
  진료실·수술실을 capability, capacity, bucket 단위와 함께 점유하도록 했다.
- 기존 `scheduling_appointments`에 `model_version`과 nullable 확정 projection을
  도입하는 V10 경계를 계획했다. 미확정 v2 방문을 legacy DTO로 mapping하지 않고
  확정 transaction만 projection을 채우도록 했다.
- allocation overlap, current proposal, audit 조회의 predicate와 index column
  순서를 고정하고 PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` 증거를 추가했다.
- 고정 seed, 일반·최대 dataset, warm-up 20회, 측정 100회, raw Gatling artifact와
  누락 metric 실패 조건을 계획했다.
- 전담 자원, capacity bucket, 의료진·장비·공간 다중 잠금과 idempotency replay를
  함께 가하는 혼합 부하를 추가했다.
- 부분 활성화 rollback에서 기존 `COMMITMENT_V2` row는 v2 API로 계속 관리하고
  신규 유입만 차단하며 table을 삭제하거나 legacy row로 변환하지 않도록 했다.
- 예약팀, 상품·구매팀, CRM의 owner와 alert route, CRM 15분 ACK SLA, redrive
  승인자와 append-only 감사 필드를 고정했다.
- fake `Clock` 기반 retention 경계, legal hold, 미전달·미해결·poison record
  보존 테스트를 추가했다.
- 8개 endpoint, service signature, header-to-command와 response-version-to-ETag
  mapping을 고정하고 `compileKotlin` 검증 명령을 추가했다.
- 설계의 인수 기준 수를 29개로 바로잡아 모두 Task와 자동화 증거에 연결했다.

운영 관점의 “runbook 파일이 아직 없음” P3는 구현 전 계획 검토에서 Task 9가
생성할 산출물 자체를 요구한 것이므로 계획 결함이 아닌 실행 전 상태로
정규화했다. Task 9와 최종 DoD가 파일 생성·내용·테스트를 차단 조건으로 유지한다.

## 영향 관점 재검토

| 관점 | 이전 finding | 재검토 |
|---|---|---|
| 성능 | P2=2, P3=1 | 모두 RESOLVED |
| 운영 | P1=2, P2=1 | 모두 RESOLVED, runbook pre-implementation P3는 N/A로 정규화 |
| 개발자/API | P2=1, P3=1 | 모두 RESOLVED |
| 본 세션 통합 | P1=2, P2=1 | 모두 RESOLVED |

## 최종 판정

| 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 0 | 0 | 0 | PASS |
| 안정성 | 0 | 0 | 0 | 0 | PASS |
| 보안 | 0 | 0 | 0 | 0 | PASS |
| 운영 | 0 | 0 | 0 | 0 | PASS |
| 개발자/API | 0 | 0 | 0 | 0 | PASS |
| 사용자/호출자 | 0 | 0 | 0 | 0 | PASS |
| 본 세션 통합 | 0 | 0 | 0 | 0 | PASS |

최종 판정은 `P0=0`, `P1=0`, `P2=0`, `P3=0`이다. Step 3-R을 통과했고
Step 3-P 위험 예측도 계획의 concurrency, DB consistency, security, MSA event,
performance, rollout/rollback 항목으로 반영했다.

## 검증 근거

- 설계 인수 기준 29개와 계획 추적 row 29개 일치
- Task 10개, 각 dependency/write scope/RED/GREEN/명령/예상 결과/commit 고정
- H2→PostgreSQL→MySQL 순차 검증과 PostgreSQL 별도 성능 증거
- 외부 event bounds/allowlist/quarantine와 Gateway actor/scope 경계
- KDoc, README locale parity, OpenAPI, runbook, metric/alert/owner 반영
- 미결정 표식 검색 0건
- `git diff --check` 통과

## Step DoD

| Check | Action | Status | Evidence | Failure / Next Action |
|---|---|---|---|---|
| A-04 / Step 3 | 승인된 설계를 파일·순서·TDD·검증 명령이 있는 계획으로 변환 | PASS | 구현 계획 Task 1~10 | 없음 |
| Step 3-R 독립 검토 | 6개 관점 검토와 영향 관점 재검토 | PASS | 최초·재검토·최종 표 | 없음 |
| Step 3-R 통합 검토 | 심각도 정규화, source compatibility, 29개 추적 확인 | PASS | 통합 보완 목록 | 없음 |
| Step 3-P | 고위험 실패 신호·완화·rollback/rerun 고정 | PASS | 계획 4절 위험 예측 | 없음 |

Required checks: 4/4; N/A: 0; Blocked: 0.

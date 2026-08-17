# Issue #333 waitlist transaction retry 계획 검토

## 판정

**구현 진행 가능 — P0=0, P1=0, P2=0, P3=0.**

검토 대상은 [계획 문서](../plans/2026-08-17-issue-333-waitlist-transaction-retry-plan.md)와 현재
`develop` 기준 source/test/helper evidence다. Issue #333은 현재 `OPEN`이며 `debop` assignee,
`bug`·`maintenance` label, `1.4.0` milestone을 유지하고 있다. 구현 전 계획의 Gradle 메서드명
필터가 Kotlin backtick 이름의 공백에 의존하지 않도록 클래스 필터로 보정했다.

## 관점별 검토

| 관점 | P0 | P1 | P2 | P3 | 판단과 근거 |
|---|---:|---:|---:|---:|---|
| 성능 | 0 | 0 | 0 | 0 | retry callback마다 새 transaction을 열되 attempt 수·sleep은 기존 `ContentionRetryPolicy`가 제한한다. bounded executor와 순차 PostgreSQL 실행으로 검증하므로 무제한 부하·병렬 container 실행을 만들지 않는다. 계획 Task 2/3/6을 그대로 수용한다. |
| 안정성 | 0 | 0 | 0 | 0 | `55P03` lock timeout과 serializable `40001`을 실제 PostgreSQL에서 재현하고 `maxAttempts = 1`, latch, distinct transaction identity, bounded shutdown을 함께 확인한다. aborted transaction을 재사용하면 테스트가 실패하므로 회귀 신호가 명확하다. |
| 보안 | 0 | 0 | 0 | 0 | 인증·권한·tenant 입력·secret·외부 endpoint를 변경하지 않는다. SQLSTATE 분류는 PostgreSQL strategy로 제한하고 기존 non-retryable exception identity를 보존한다. |
| 운영 | 0 | 0 | 0 | 0 | 이 예제 Issue의 acceptance는 `PostgreSQLServer.Launcher.postgres` 기반 시뮬레이션이다. production deployment/canary/SLO 증거는 범위 밖 N/A로 명시하고, 대신 Colima 상태·container lifecycle·bounded timeout을 로컬 검증한다. |
| 개발자·API | 0 | 0 | 0 | 0 | `claim`, `process`, `withContentionRetry`와 예외의 public signature를 유지한다. caller-owned transaction과 fresh top-level callback 계약은 Korean KDoc으로 명시하고 새 dependency/module을 추가하지 않는다. |
| 사용자·호출자 | 0 | 0 | 0 | 0 | `claim`부터 `process`·enqueue·terminal fence까지 caller transaction 원자성을 유지한다. 기존 outbox rollback 테스트를 보존하고 retry coordinator를 transaction 밖에서 호출하는 경계를 계획·KDoc·PG test로 연결한다. |

## 통합 검토

- 현재 production source에는 `WaitlistDeliveryRepository.claim`을 호출하는 delivery worker가
  연결되어 있지 않고, 실제 retry coordinator 사용처는 repository와 test contract에 한정되어
  있다. 따라서 이번 수정은 불필요한 orchestration API를 만들지 않고 caller contract를
  명시하는 최소 범위를 유지한다.
- `WaitlistDeliveryRepository.claim`의 기존 내부 `withContentionRetry`(현재 source line 174),
  retry classifier(line 642), `WaitlistDeliveryService.process`의 caller-owned transaction
  KDoc, `TestDB.POSTGRESQL`·`Containers.Postgres`·`WithTables` fixture를 계획이 직접 소유한다.
- `@Testcontainers`·`GenericContainer`를 사용하지 않고 singleton launcher를 재사용한다. 실제
  PostgreSQL test와 H2/unit test는 순차 실행하며, production 운영 증거는 요구하지 않는다.
- 변경 전 RED → 최소 production 수정 → GREEN → full `:appointment-core:test` 순서와
  rollback point가 계획에 있다. 실패 시 fixture를 먼저 고치고 production code를 수정하지
  않는 fail-closed 순서도 유지한다.

## 문서·한국어 검토

- **SPW-01~05:** PASS — source ledger, acceptance-to-plan mapping, file/command traceability,
  read-back과 unsupported claim 제한을 확인했다.
- **KO-01~06:** PASS — 한국어 설명을 유지하고 `SQLSTATE`, `PostgreSQLServer.Launcher.postgres`,
  `inTopLevelTransaction`, Gradle 명령 등 필요한 기술 식별자만 원문으로 보존했다.
- `docs/lessons/`는 구현 후 실제 실패·복구 증거가 생길 때 작성하며, 재사용 가능한 lesson이
  없으면 checklist에 네 가지 absence category 기반 N/A를 기록한다.

## 승인 조건

1. 위 여섯 관점과 통합 검토에서 P0/P1이 없다.
2. 사용자가 이 계획을 명시적으로 승인한 뒤에만 Task 1 RED test mutation을 시작한다.
3. 구현 완료 후 exact head의 targeted PostgreSQL, module test, static/diff check와 final code
   review를 새 증거로 갱신한다.

Constraint: Issue #333의 실제 PostgreSQL 시뮬레이션 범위와 caller-owned transaction 원자성을 유지한다.
Rejected: production deployment evidence, raw container, 새 public orchestration API | 현재 예제 acceptance와 최소 변경 범위를 벗어난다.
Confidence: high
Scope-risk: narrow
Directive: retry 경계를 바꾸는 후속 수정은 반드시 fresh top-level transaction identity와 aborted transaction rollback을 함께 검증한다.
Tested: live `gh issue view 333`, source/fixture symbol read-back, plan `git diff --check`, Gradle class-filter command review.
Not-tested: 구현 전이므로 RED/GREEN, PostgreSQL contention, full module test, CI는 아직 실행하지 않았다.

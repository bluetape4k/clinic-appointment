# Issue #33 Step 3-R 구현 계획 검토

## 검토 대상

- 계획: `docs/superpowers/plans/2026-08-12-issue-33-patient-authentication-plan.md`
- 설계: `docs/superpowers/specs/2026-08-12-issue-33-patient-authentication-design.md`
- 설계 검토: `docs/review/2026-08-12-issue-33-step-2r-spec-review.md`
- 범위: `feat/issue-33-patient-auth` worktree의 API, core, Angular frontend, migration,
  운영 문서와 Issue #33 delivery gate

## 판정

**PASS — P0 0건, P1 0건, P2 0건.**

계획은 승인된 설계와 현재 저장소의 모듈/테스트 실행 방식에 맞으며, production code에
들어가기 전에 core/API/frontend RED 테스트를 두고 GREEN/REFACTOR를 순서대로 수행하도록
구성되어 있다. Angular Gradle task에는 Gradle `--tests` 필터가 없으므로 frontend RED와
회귀 명령을 전체 `:frontend:appointment-frontend:test`로 고정한 것도 실행 가능한 계약이다.

## 여섯 관점 검토

### 1. 요구사항·추적성

- Issue #33의 PATIENT actor, tenant-scoped login/session, HttpOnly cookie, expiry/nbf,
  401/403, logout, 다른 tenant 거절, Angular guard/interceptor/screen을 각각 계획의
  5–12단계와 최종 E2E 단계에 연결했다.
- 사용자가 확정한 `PHONE`, `EMAIL`, `LOGIN_ID` 세 key와 `{key,value}` request shape가
  목표, core test, service/controller test, Angular form/client 전반에 일관되게 반영됐다.
- OTP/ownership/recovery, native push, member auto-link, production canary는 비범위/후속
  issue로 분리되어 구현자가 범위를 확장할 여지가 없다.

### 2. 아키텍처·보안

- 기존 staff/admin Bearer JWT를 보존하고 Authorization 우선·patient cookie fallback을
  명시했다. `nbf`를 global mandatory로 바꾸지 않는 호환성 제약도 plan과 parser test에 있다.
- patient JWT claim invariant, opaque subject, no token/password/PII response/log, stale
  cookie deletion, generic credential error, tenant membership을 service/security test가
  각각 검증한다.
- CSRF를 cookie auth와 분리된 `CookieCsrfTokenRepository` + `csrf.spa()`로 고정하고
  login/logout 뒤 token refresh와 Angular XSRF 이름을 함께 지정했다.
- local unbounded rate limiter를 금지하고 protected profile fail-closed/no-op test adapter를
  wiring 단계에 넣어 운영 보안 경계를 코드와 테스트로 남긴다.

### 3. 데이터·migration

- `scheduling_patient_accounts`와 `scheduling_patient_login_identities`의 tenant FK,
  account FK, `(tenant,key,value)` 및 `(account,key)` uniqueness, inactive filtering을
  core repository test와 V26 H2 migration contract에 매핑했다.
- H2/PostgreSQL/MySQL V26을 additive로 만들고 down migration/기존 member 자동 변환을
  금지해 rollback 위험을 제한했다. 기존 V25 sequence와 Flyway test 연결도 계획에 있다.
- Exposed transaction 경계를 repository contract와 service wiring에 반복해 명시했다.

### 4. Kotlin·Angular 패턴 적합성

- core immutable record/value model, enum key, repository 분리, 기존 `RecordMappers.kt`와
  `AbstractExposedTest` 규칙을 따른다. raw `!!`/transaction 누락/PII subject 재사용을 계획에서
  금지했다.
- API DTO·service·controller·security를 별도 패키지로 두고 명시적 `ServiceConfig` wiring을
  유지한다. 기존 `SecurityConfig` authorization manager 순서는 보존하고 auth matcher만
  generic tenant matcher 앞에 삽입한다.
- Angular에서는 cookie session과 workforce in-memory bearer를 분리하고, built-in XSRF,
  functional interceptor, signal 기반 AuthService, standalone route/page 패턴을 현재 구조에
  맞춰 확장한다. 페이지는 기존 visualize style/accessibility를 유지한다.

### 5. 테스트·검증 가능성

- core → migration/wiring → service → controller → security → frontend → E2E 순서로
  prerequisite가 명확하며 각 구현 앞에 RED 명령이 있다.
- future `nbf`, expired, malformed cookie, wrong tenant, role mismatch, CSRF missing,
  limiter rejection, no-nbf workforce compatibility를 명시한 negative coverage가 있다.
- 모듈별 전체 test/build와 기존 baseline(API 155 XML/725 tests, frontend 33 files/214 tests)
  비교를 최종 증거로 요구한다. Colima/Ryuk harness failure는 환경 변수 재실행으로 분리한다.

### 6. 운영·delivery

- cookie TTL/Secure/CSRF bootstrap, edge rate-limit prerequisite, log redaction, drain,
  stale cookie clear, additive rollback을 runbook 항목으로 고정했다.
- 7-tier review와 lesson artifact를 별도 파일로 남기고, Korean issue/PR body, `Closes #33`,
  milestone/assignee/labels parity, 마지막 `## DoD Status`, CI 확인 후 fresh merge approval
  gate까지 계획에 포함했다.
- wiki research 보존과 `git diff --check`/GNO 검증이 계획에 있어 외부 공식 문서의 결정 근거가
  휘발되지 않는다.

## 추적성 요약

| 설계/Issue 계약 | 구현 계획 단계 | 검증 증거 |
|---|---|---|
| 세 structured login identifiers | 1, 2, 5, 6, 11, 12 | core/service/client/page tests |
| tenant isolation | 2, 4, 6, 8, 9, 10, 13 | repository/security/E2E |
| HttpOnly patient cookie | 6, 8, 9, 10, 12 | issuer/filter/controller/Angular tests |
| exp/nbf 및 workforce 호환 | 6, 9, 10, 13 | issuer/parser/filter/security tests |
| CSRF/XSRF | 8, 10, 12, 13 | MockMvc + Angular client/interceptor |
| 401/403/logout/expiry UI | 7–13 | controller/security/guard/shell/E2E |
| migration/rollback | 3, 4, 14, 15 | V26 contract + runbook + build evidence |
| 7-tier/PR/merge gate | 14, 15 | review/lesson/PR CI + fresh approval |

## 실행 판단

Step 3-R 계획 검토를 통과시킨다. 승인된 설계와 계획에 따라 다음 작업은 core RED 테스트
작성부터 시작하며, RED 증거가 남기 전에는 production implementation을 수정하지 않는다.

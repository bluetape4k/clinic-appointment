# Issue #399 messaging readiness 진단 개선 계획

## 목표

`appointment-messaging`의 readiness 검사가 schema·serializer·Schema Registry
실패를 boolean으로 축약하지 않고, 운영자가 재시도 여부와 대상 경계를 판단할 수
있는 bounded structured diagnostic으로 보존한다. fail-closed, secret/PII 비노출,
기존 relay·startup 계약은 유지한다.

## 기준선과 범위

- 저장소: `bluetape4k/clinic-appointment`
- Issue: [#399](https://github.com/bluetape4k/clinic-appointment/issues/399)
- Epic: [#407](https://github.com/bluetape4k/clinic-appointment/issues/407)
- 선행 child head: `22feb7d9ae8ecf77e962ca99acf6c706652028f1` (`#402` PR #412)
- 작업 branch: `fix/issue-399-readiness-cause`
- 대상 모듈: `appointment-messaging`
- 포함: readiness 상태·health detail·startup warning, JDBC metadata 원인 분류,
  schema missing/permission/timeout/driver 회귀 테스트, 운영 문서와 7-Tier artifact
- 제외: outbox table/claim/transaction, Kafka wire, notification module 구현(후속 #400),
  raw SQL message·credential·tenant/clinic/appointment 값의 노출

## 재사용 결정

1. 기존 `AppointmentMessagingReadinessProbe`와 `AppointmentMessagingHealthIndicator`
   경계를 유지하고 `AppointmentReadinessDiagnostic`만 readiness 상태에 추가한다.
2. JDBC `SQLException` 분류는 JDK 표준 `SQLTimeoutException`, SQLState `28`/`08`와
   `bluetape4k` 로깅·assertions 계약을 재사용한다. 새 의존성이나 ad-hoc logging
   framework는 추가하지 않는다.
3. 기존 metadata 후보 조회(table/catalog/schema 대소문자 fallback)는 유지하되,
   모든 후보가 실패한 경우에만 원인 분류를 만들고 정상적인 empty metadata는
   schema contract missing으로 분리한다.
4. diagnostic에는 operation, bounded target, stable code, sanitized error class,
   retryability만 보존한다. exception message, JDBC URL, credential, payload와
   tenant/clinic/appointment 식별자는 저장하거나 health 응답에 넣지 않는다.

## 순차 실행 계획

- [x] **Task 1 — 현재 결함과 stacked 기준선을 고정한다.**
  - live Issue/Epic/PR와 #402 head, validator/probe/health/startup caller, 기존
    테스트·runbook·README를 source에서 확인한다.
  - DoD: Type C 재현 조건과 정확한 base/head가 기록된다.
- [x] **Task 2 — RED 테스트를 추가한다.**
  - schema missing, permission denied, timeout, driver failure가 stable code,
    operation/target/errorClass/retryable로 보존되는 테스트를 작성한다.
  - DoD: production 변경 전 신규 테스트가 기대 실패하고 `bluetape4k-assertions`를
    사용한다.
- [x] **Task 3 — bounded diagnostic을 구현한다.**
  - validator가 원인별 diagnostic을 probe에 기록하고 health detail·startup log가
    동일한 구조를 소비하도록 연결한다.
  - DoD: fail-closed/compatibility fallback/secret·PII 비노출이 유지된다.
- [x] **Task 4 — 문서와 운영 계약을 갱신한다.**
  - README 두 파일, messaging operations runbook, Korean KDoc와 lesson에
    diagnostic code·재시도 경계를 기록한다.
  - DoD: source·health·runbook 설명이 일치한다.
- [ ] **Task 5 — 7-Tier 검토와 PR 전달을 완료한다.**
  - Kotlin checklist, terminology audit, sequential module verification, Lore
    commit, exact stacked PR metadata/CI/readback을 수행한다.
  - DoD: P0/P1=0, exact base/head, PR은 open 상태이며 train merge는 수행하지 않는다.

## 롤백과 재실행

- 진단 모델이 caller contract를 깨면 `AppointmentReadinessDiagnostic`와 연결부만
  되돌리고 #402 head를 base로 유지한다.
- Testcontainers/PostgreSQL 실패는 코드 실패로 단정하지 않고 Colima·Docker·launcher
  상태를 확인한 뒤 heavy test를 순차 재실행한다.
- CI 실패는 exact PR head에서 실패 job을 읽어 해당 테스트부터 수정하고, 전체 train의
  merge approval은 모든 child가 완료될 때까지 요청하지 않는다.

## 계획 DoD

- schema missing, permission denied, timeout/driver failure의 원인 코드와 retryability가
  bounded 구조로 보존된다.
- readiness/health/startup이 같은 diagnostic을 사용하고 raw error/secret/PII를 노출하지
  않는다.
- #399 PR은 #402 head를 base로 쌓이며 merge하지 않는다.

## 문서 작성 점검

- [x] SPW-01: Korean plan, Issue/Epic, current source tip, base/head, scope와 제외 범위를 고정했다.
- [x] SPW-02: 파일 경계·순서·RED/GREEN·검증·rollback·DoD를 포함했다.
- [x] SPW-03: 현재 기술 용어와 API identifier를 보존한 한국어 기술 문체를 사용했다.
- [x] SPW-04: live GitHub와 #402 source/test/runbook를 대조했다.
- [x] SPW-05: 계획·검토·lesson Markdown을 read-back했고, PR delivery 항목은 전달 완료 뒤 갱신한다.

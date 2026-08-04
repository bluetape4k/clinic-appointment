# Issue #39 tenant query isolation lesson

## 맥락

`clinicId`만 전달하는 scheduling·reschedule·direct notification 경계는 같은
clinic-local identifier가 tenant 사이에서 재사용될 때 조회와 side effect의
ownership을 보장하지 못했다. 동시에 V21을 한 번에 `NOT NULL`로 바꾸면 old-node
writer가 아직 tenant column을 보내지 않는 rolling 배포를 깨뜨릴 수 있었다.

## 결정

1. 검증된 DB authority를 나타내는 단일 `TenantClinicScope(tenantGroupId,
   clinicId)`를 core에 두고 slot, solver, closure, event, notification에
   immutable 값으로 전달한다. 인증 객체나 thread-local context를 재사용하지 않는다.
2. direct claim·permit·provider 호출 직전에 scope를 다시 확인하고, canary는
   `canaryScopes`를 기준으로 평가한다. 기존 `canaryClinicIds`는 동일 clinic 집합을
   검증하는 deprecated rolling bridge로만 남긴다.
3. V21은 세 dialect에 nullable tenant event-log column, clinic join backfill,
   tenant FK/index, scope-leading direct lookup index를 additive하게 배포한다.
   old-node drain과 zero-null 확인 뒤의 `NOT NULL` hardening은 별도 release로 둔다.

## 결과

- cross-tenant repository, slot, direct claim, claimed-row mismatch, equipment
  API, event-log best-effort, canary validation 회귀 테스트를 추가했다.
- H2/PostgreSQL/MySQL V21 migration integration 3건, API migration 1건,
  cross-tenant repository 3 dialect, notification outbox/dispatcher/readiness
  targeted suites, API controller/migration targeted suite, 그리고 PostgreSQL/MySQL
  query-plan 2건이 최신 실행에서 통과했다.
- 기본 core/solver/event/notification 전체 실행은 이 환경에서 외부 PostgreSQL/MySQL
  launcher가 unavailable하여 waitlist schema connection failure로 중단되었고, API
  전체 실행은 300초 wrapper timeout으로 완료되지 않았다. 이 수치는 Issue #39
  동작 실패가 아니라 환경 검증 한계로 기록하며 CI에서 재실행한다.
- V21 운영 runbook에 orphan/null/mismatch-row preflight, EXPLAIN/lock 확인, MySQL
  partial-DDL 복구, `PAUSED` rollback과 낮은 cardinality metric을 기록했다.

## 놓친 점

초기 구현 검토에서 event-log 실패 로그가 raw driver 예외를 남길 수 있었다. 최종
수정에서는 tenant/query payload가 포함될 수 있는 예외 메시지를 버리고 bounded
`EVENT_LOG_WRITE_FAILED` reason code만 기록하도록 했다.

## 다음 작업의 guard

1. 새 scheduling·notification public API는 clinic-only 또는 tenant-only 인자를
   추가하지 말고 `TenantClinicScope`를 named argument로 받는다.
2. tenant predicate를 loop 안의 ownership query로 보강하지 말고 기존 query에
   포함했는지와 statement/query budget을 함께 검증한다.
3. nullable rolling migration은 readiness와 old-node drain evidence가 없으면
   `NOT NULL`로 승격하지 않으며, schema-down rollback 대신 application `PAUSED`
   절차를 사용한다.

# 예약 정책 관리 API — Task 9 검토 기록

## 결과

Task 9은 tenant 기본 정책과 clinic override에 동일한 lifecycle API를 공개한다.
정책 scope와 행위자는 request body가 아니라 URL, 데이터베이스 소유관계, API Gateway가
전달한 검증된 principal에서만 만든다. 공개 경로는 draft, validate, preview, preview job,
approve, schedule, activate, retire, replay, effective read의 tenant/clinic 20개 route다.

최종 6-R/7-R gate는 `P0=0`, `P1=0`으로 수렴했다. 최초 독립 검토에서 발견된 P1은
처리할 worker가 없는 단계에서 비동기 preview job을 만들 수 있던 문제와 OpenAPI·controller
회귀 범위의 누락이었다. 모두 코드와 테스트를 수정한 뒤 해당 관점을 다시 검토했다.

## 신뢰 경계

1. Spring Security는 구체적인 policy matcher를 넓은 `/admin/**`보다 먼저 평가한다.
2. tenant/clinic route는 `ADMIN|STAFF`, `SCOPE_policy:write`, tenant membership을
   요구하고 clinic route는 정확한 clinic membership을 추가한다.
3. controller는 `TenantClinicAccessChecker`와 `ActorContextResolver`로 신뢰된
   `PolicyScopeRef`와 `ActorContext`를 만든다.
4. DTO의 미등록 필드와 중첩 generation의 권한 상승 필드는 fail-closed로 거부한다.
5. definition, preview job, activation command, 완료 evidence token 조회는 tenant, scope,
   `clinicScopeKey`를 저장소 predicate에 포함한다.

## HTTP 계약

- 즉시 activate와 replay는 `Idempotency-Key` header가 필수다. Spring binding은 안정적인
  `POLICY_PAYLOAD_INVALID` 응답을 위해 nullable로 받고, OpenAPI는 caller 계약대로
  `required=true`로 공개한다.
- schedule 성공은 `202`, 즉시 activate와 replay 성공은 `200`이다. OpenAPI에 불가능한
  성공 status를 함께 노출하지 않는다.
- 비동기 preview는 `202`, exact polling `Location`, 설정 기반 정수 초 `Retry-After`를
  반환한다.
- `decisionAt`과 `serviceAt`은 UTC 또는 명시적 offset이 있는 RFC 3339 문자열만 받는다.
  server-now 기본값은 없고 `serviceAt < decisionAt`은 거부한다.
- 미완료 preview는 result hash와 activation evidence token을 공개하지 않는다.

## 단계적 rollout 보정

`adminWriteEnabled=true`, `previewWorkerEnabled=false`는 합법적인 draft 관리 단계다.
이 구성에서 preview가 동기 한도를 넘으면 worker가 회수하지 못하는 `PENDING` job이 생길
수 있으므로 preview route는 worker flag가 켜지기 전 `404 POLICY_RESOURCE_NOT_FOUND`로
닫는다. 회귀 테스트는 `previewService.submit`이 한 번도 호출되지 않음을 검증한다.

polling limiter는 한 API process의 편의 부하 제어다. hard entry cap과 expiry는
프로세스 메모리를 보호하지만 다중 instance 전체의 rate limit을 대신하지 않는다. SaaS
운영 환경은 API Gateway 또는 분산 rate limiter를 별도로 적용해야 한다.

## 독립 검토 수렴

| 관점 | 최초 | 처리 | 최종 |
|---|---|---|---|
| 성능 | P3 1: polling key hard cap 부재 | hard cap과 10,000-key fake-clock 회귀 테스트 추가 | P0 0, P1 0, P2 0, P3 0 |
| 안정성 | P3 2: limiter churn, 429 wire 증거 분리 | hard cap·expiry 보강, 안정 오류/Retry-After 하위 계약 유지; Redis test 종료와 429 HTTP 증거는 Task 10 | P0 0, P1 0, P2 0, P3 2 |
| 보안 | P2 1, P3 1: token 전역 조회, substring route 분류 | scope 포함 SQL 조회, exact route predicate와 3 dialect 회귀 추가 | P0 0, P1 0, P2 0, P3 0 |
| 운영 | P1 1, P2 1, P3 1: orphan async job, process-local 제한, facade metric | worker 전 fail-closed, 분산 제한 책임 문서화; metric은 Task 10으로 이관 | P0 0, P1 0, P2 0, P3 1 |
| 개발자/API | P1 2, P2 1: 필수 header/OpenAPI, lifecycle route 테스트, 오류 status | header·status 계약과 tenant/clinic lifecycle 위임 테스트 추가 | P0 0, P1 0, P2 0, P3 0 |
| 사용자/호출자 | P2 2, P3 2: 성공 status 혼합, 403/404, 문서, artifact | 성공 annotation 분리, 오류 status 보강, artifact 재생성; 공개 문서는 Task 10 | P0 0, P1 0, P2 0, P3 1 |
| 본 세션 통합 | 중복 finding과 Task 9/10 경계 확인 | P0/P1/P2 수정, 네 가지 P3 이관 근거와 검증 명령 기록 | P0 0, P1 0, P2 0, P3 4 |

## 검증 증거

- tenant/clinic lifecycle controller와 generated OpenAPI 집중 테스트: 통과
- preview limiter, request fail-closed, worker-off no-orphan 집중 테스트: 통과
- 완료 evidence token exact-scope 조회: H2, PostgreSQL, MySQL 8 모두 통과
- preview job primary-key exact-scope 조회: H2, PostgreSQL, MySQL 8 모두 통과
- PostgreSQL datasource와 Flyway를 사용한 실제 HTTP security integration: 4개 통과
- Task 9 집중 테스트와 기존 appointment/plan API 회귀 테스트를 독립 프로세스로
  분리 실행: 모두 통과
- production concurrency quick scan: Task 9 변경 경로의 신규 blocking/sleep/global coroutine hit 없음

H2는 빠른 구조 피드백에만 사용한다. PostgreSQL+Flyway 실행과 PostgreSQL dialect 결과가
운영 의미의 권위 증거이며, MySQL 8 결과는 지원 dialect의 동등성을 추가로 확인한다.

여러 Spring test context를 한 Gradle 실행에 묶으면 기존 Redis near-cache와 test container
종료 순서에서 context당 1분 `RedisCommandTimeoutException` 로그가 누적됐다. 개별 테스트
결과는 모두 성공했지만 단일 묶음은 4분 실행 제한을 넘겼으므로 완료 증거로 사용하지 않았다.
Task 9의 기능 증거는 Redis context를 공유하지 않는 독립 프로세스 실행으로 수집했다.

## 명시적 이관

Task 10은 `docs/api/scheduling-policy.md`, 운영 runbook, 영문·국문 README parity,
관리 API low-cardinality metric, Redis test context 종료 정리,
polling 429 HTTP 경계 테스트, 전체 dialect/concurrency/performance 증거를 완성한다.
따라서 Task 9 완료를 전체 caller 문서 또는 운영 관측성 완료로 표현하지 않는다.

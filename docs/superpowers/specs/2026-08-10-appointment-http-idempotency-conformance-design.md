# appointment HTTP bounded-wait 멱등성 conformance 설계

## 목표

`bluetape4k-projects:1.12.1`의 `assertBoundedWaitHttpIdempotencyConformance`를
`appointment-api`의 Spring MVC 테스트 경계에 연결해 bounded-wait HTTP 계약을
반복 검증한다. 기존 `AppointmentControllerTest`의 H2/Exposed durable create-or-replay
검증은 유지하고, 공통 fixture가 증명하지 않는 restart·외부 side effect·실제 DB 원자성은
별도 테스트 책임으로 명시한다.

## 근거와 현재 경계

- `AppointmentService.create`는 tenant/clinic 범위의 idempotency row와 appointment를
  같은 `transaction {}`에서 저장하고, unique 충돌 뒤 기존 결과를 재조회한다.
- `AppointmentControllerTest`는 no-key 201, terminal replay 201/200, fingerprint conflict,
  expiry, 두 동시 요청의 단일 appointment를 실제 HTTP/DB 경계에서 검증한다.
- 공통 fixture는 `exchange`, owner/waiter barrier, virtual clock, cancellation,
  quiescence를 adapter에 요구한다. 현재 동기 DB endpoint에 임의 outcome과 virtual clock을
  주입하면 production persistence를 증명하는 것이 아니므로 test application 경계를 둔다.

## 선택한 구조

1. `AppointmentHttpIdempotencyConformanceTest` 안에 Spring `MockMvc` adapter를 둔다.
   request header/body는 실제 MVC controller와 filter를 통과하고 response는
   `HttpIdempotencyResponse`로 변환한다.
2. test-only `AppointmentBoundedWaitTestApplication`은 tenant-scoped key,
   canonical JSON fingerprint, owner/waiter admission, timeout/overflow, cancellation,
   transient abandon, retention, replay-header safety를 deterministic하게 관리한다.
3. test-only controller는 `/api/{tenantCode}/appointments`와 동일한 생성 boundary를
   사용하되 synthetic fixture payload를 받는다. 인증/인가가 끝난 뒤에만 key record를
   조회하고, raw key/body/credential을 로그나 응답에 노출하지 않는다.
4. bounded body filter와 interruptible blocking dispatcher를 test가 소유하고 닫는다.
   fixture watchdog과 behavioral virtual clock은 서로 분리한다.
5. 기존 실제 endpoint 테스트는 변경하지 않고, conformance 테스트 KDoc/lesson에서
   durable persistence, process restart recovery, authorization/rate-limit 및 외부
   exactly-once side effect가 별도 검증임을 명시한다.

## 대안과 결정

| 대안 | 장점 | 단점 | 결정 |
|---|---|---|---|
| 실제 `AppointmentController`/DB에 fixture control을 직접 주입 | production path와 가까움 | owner outcome/virtual clock을 DB 트랜잭션에 주입해 false proof를 만들고 테스트가 flaky해짐 | 기각 |
| 공통 fixture를 복사해 API 전용 assertion으로 재작성 | 초기 구현이 쉬움 | bluetape fixture 업데이트 시 계약 drift와 중복 assertion이 생김 | 기각 |
| Spring MVC adapter + test-only deterministic application, 실제 DB 테스트 분리 | 같은 fixture를 그대로 재사용하고 lifecycle을 deterministic하게 증명 | durable 계약은 별도 테스트가 필요함 | 채택 |

## 오류·보안·운영 계약

- 인증되지 않은 profile은 401, 쓰기 권한 없는 profile은 403이며 record present/absent를
  구별할 수 없다.
- 동일 scope의 다른 fingerprint는 즉시 409 `idempotency_key_reused`다.
- waiter limit 초과는 즉시 429와 양의 정수 `Retry-After`, wait timeout은 409와
  `idempotency_in_flight`/`Retry-After`를 사용한다.
- owner가 commit 전 취소되면 transient 503으로 waiter를 종료하고 ownership을
  reclaim한다. commit 후 response delivery 취소는 terminal replay를 보존한다.
- replay snapshot은 content type과 명시적 allowlist만 보존하며 credential·token·
  hop-by-hop header는 denylist로 강제한다.
- fixture diagnostics와 `sideEffectCount`는 key/body/identity를 출력하지 않는다.

## 검증 기준

1. 공통 `assertBoundedWaitHttpIdempotencyConformance(adapter, config)`를 scenario 제외
   없이 통과한다.
2. body overflow filter, interruptible exchange, executor 종료와 `quiescence == 0`을
   별도 lifecycle 테스트로 고정한다.
3. 기존 `AppointmentControllerTest`의 실제 H2 durable replay/concurrent proof가
   계속 통과한다.
4. `./gradlew :appointment-api:test`와 `:appointment-api:build`, `git diff --check`가
   통과한다.

## 제외 범위

waitlist/policy/commitment endpoint 확장, process restart/durable recovery의 새 구현,
외부 provider exactly-once, PR/원격 전달 및 merge는 이번 작업에 포함하지 않는다.

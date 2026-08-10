# Issue #255 HTTP 멱등성 conformance lesson

## 맥락

appointment 생성 endpoint에는 same-key terminal replay와 동시 요청 회귀가 있었지만,
bounded waiter 수, deadline timeout, overflow, cancellation, owner disconnect와 응답
delivery cleanup을 한 번에 검증하는 공통 계약은 없었다. `bluetape4k-dependencies:1.4.0`
선행 변경으로 `bluetape4k-junit5:1.12.1`의 bounded-wait HTTP fixture를 사용할 수 있게
되어 endpoint 경계를 별도 conformance 테스트로 고정했다.

## 발견과 근본 원인

첫 RED skeleton은 fixture가 owner 시작 신호를 기다리는 즉시 `NotImplementedError: RED`로
실패했다. 이후 실제 fake app을 연결하자 Spring Boot BOM이 test runtime에서
Coroutines 1.10.2를 선택해 fixture가 호출하는 Coroutines 1.11
`ReceiveChannel.cancel$default` 링크에서 `NoSuchMethodError`가 발생했다. shared BOM 선언만
있다고 ABI 정렬이 보장되는 것은 아니었다.

## 결정

- 실제 endpoint path `/api/{tenantCode}/appointments`, 인증 profile, operation,
  resource identity, `Idempotency-Key`를 Spring MockMvc adapter로 연결한다.
- fixture의 virtual clock와 arbitrary owner outcome은 deterministic test app에서만
  실행한다. 실제 DB commit/restart/side-effect 증거는 기존 integration test로 분리한다.
- tenant·operation·resource·key를 SHA-256 scope로 묶고 인증/쓰기 권한을 idempotency lookup
  전에 검사한다.
- bounded body filter는 declared length와 unknown length를 모두 `413`으로 차단하며,
  replay snapshot은 allowlist·denylist·header/body 상한을 적용한다.
- `appointment-api` 구성에서 `org.jetbrains.kotlinx:kotlinx-coroutines-*`를 프로젝트가
  선언한 1.11.0으로 정렬해 `bluetape4k-junit5:1.12.1` ABI를 보장한다.
- test stereotype가 Spring Boot 통합 context에 자동 스캔되지 않도록 conformance
  controller에는 비활성 `@Profile`을 붙이고, MockMvc standalone setup으로만 등록한다.

## 검증

- RED: `awaitOwnerStarted`의 `NotImplementedError: RED`를 확인했다.
- GREEN: `AppointmentHttpIdempotencyConformanceTest` 3개가 통과했다.
  - 공통 bounded-wait conformance 전체 시나리오
  - declared/unknown body overflow 선차단
  - blocking MockMvc 취소와 caller-owned thread 회수
- dependency evidence: `dependencyInsight`가 `kotlinx-coroutines-core-jvm:1.11.0`을
  선택하고 ABI 오류가 사라졌다.
- 실제 `AppointmentControllerTest`는 Redis Testcontainer가
  `~/.colima/default/docker.sock`를 mount하지 못해 context 초기화 단계에서 PENDING이다.

## 미래 guard

1. 공통 fixture를 추가할 때는 먼저 endpoint-specific adapter의 RED test를 두고, fixture가
   요구하는 모든 control method를 fake app의 상태 전이와 연결한다.
2. Spring Boot BOM과 bluetape4k BOM을 함께 올릴 때는 `dependencyInsight`에서 Coroutines,
   Kotlin, Jackson ABI를 실제 runtime classpath로 확인한다. 선언된 BOM만 검증하지 않는다.
3. test-only `@Controller`는 component scan에서 격리하고, standalone MockMvc 등록 여부를
   별도 smoke test로 확인한다.
4. conformance PASS를 durable/restart/external side-effect PASS로 확장하지 않는다. 각
   책임에 맞는 integration/readiness 증거를 별도로 요구한다.

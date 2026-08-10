# Issue #255 appointment HTTP 멱등성 conformance seven-tier 검토

검토일: 2026-08-10
검토 범위: `appointment-api`의 Spring MVC 테스트 경계와 `bluetape4k-junit5:1.12.1` bounded-wait fixture 연동
작업 브랜치: `codex/issue-255-idempotency-conformance`
선행 기준: Issue #253 dependency BOM 1.4.0 변경

## 판단 범위

이번 변경은 실제 appointment 생성 endpoint와 동일한 path·인증 profile·idempotency header를
사용하는 deterministic Spring MVC 테스트 애플리케이션을 추가하고, 공통
`assertBoundedWaitHttpIdempotencyConformance`를 실행한다. fixture의 virtual clock와 owner
abandonment를 실제 Exposed transaction에 주입하지 않으므로, DB commit/restart와 외부
side-effect exactly-once는 기존 `AppointmentControllerTest`의 책임으로 남긴다.

## Seven-tier 결과

| tier | 관점 | 판단 | P0/P1/P2/P3 | 근거 |
|---|---|---|---:|---|
| 1 | 요구사항·계약 | terminal replay, in-flight waiter, deadline timeout, overflow, cancellation, abandonment, retention, header/key/body 경계를 공통 fixture로 실행 | 0/0/0/0 | `AppointmentHttpIdempotencyConformanceTest.kt:76-94`, fixture 전체 conformance 통과 |
| 2 | 구조·아키텍처 | deterministic fake app/adapter와 실제 DB durable 검증을 분리해 virtual time이 영속성 증거를 가장하지 않음 | 0/0/0/0 | 테스트 KDoc `:67-72`, adapter/application `:201-265`, `:309-673` |
| 3 | 도메인·정합성 | tenant·operation·resource·key를 scope에 포함하고 key 재사용 payload 충돌은 409로 종료; 인증 실패는 lookup 전에 종료 | 0/0/0/0 | controller `:274-295`, scope/fingerprint `:459-475`, `:588-603` |
| 4 | 구현·Kotlin 패턴 | `sealed interface Action`, `ReentrantLock.withLock`, immutable response copy, `runInterruptible`로 blocking MockMvc를 IO 경계에 격리; 테스트 source만 변경 | 0/0/0/0 | `:208-236`, `:312-337`, `:497-550`, Kotlin pattern checklist 대조 |
| 5 | 테스트·검증 | conformance 1개, declared/unknown body overflow 1개, blocking cancellation/thread 회수 1개를 module-scoped target으로 통과 | 0/0/0/0 | `./gradlew :appointment-api:test --tests ...AppointmentHttpIdempotencyConformanceTest` 성공 |
| 6 | 성능·동시성 | bounded 8-thread executor, per-key waiter cap=1, virtual clock, quiescence(0,0,0)로 waiter/gate 누수를 검증 | 0/0/0/0 | config `:172-199`, quiescence assertion `:90-92`, cancellation test `:124-170` |
| 7 | 보안·운영·전달 | replay header allowlist/denylist, body/key upper bound, raw key redaction, Coroutines ABI 정렬을 고정; PR/merge/CI는 미승인 범위 | 0/0/0/0 | header/body guard `:552-585`, `:675-715`, `appointment-api/build.gradle.kts:89-99` |

## Kotlin `bluetape-kotlin-patterns` 확인

- 테스트 자원은 `use`/`finally`로 닫고, blocking 호출은 `runInterruptible(dispatcher)`로 호출자 취소를 전달한다.
- 상태 전이는 `sealed interface`와 명시적 `RecordState`로 제한하며, mutable state는 lock 내부에서만 변경한다.
- scope에는 raw idempotency key를 저장하지 않고 SHA-256 digest만 사용한다.
- `!!` 대신 `checkNotNull`을 사용하고, HTTP 응답은 `copy`/`buildMap`으로 불변 snapshot을 만든다.
- production Exposed transaction이나 raw Testcontainers를 새로 추가하지 않았다.

## 검증 한계와 후속 경계

- 기본 명령의 전체 `:appointment-api:test`는 Colima Docker socket 경로를
  `~/.colima/default/docker.sock`로 전달하는 Testcontainers Ryuk mount 오류로
  `AppointmentControllerTest` context 초기화에서 실패했다. Docker daemon 자체는
  정상이므로 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`를 지정해
  재검증했다.
- 위 override를 적용한 `AppointmentControllerTest` 단독 실행은 25개 테스트가
  통과해 durable same-key replay, concurrent convergence, expiry replay 경계를
  확인했다. 단, 같은 override의 전체 모듈 실행은 707개 중 12개가 기존
  `AppointmentCommitmentSecurityIntegrationTest`에 집중되어 실패했다. 해당 보안
  클래스만 단독 실행하면 12개 모두 통과하므로 이번 변경의 conformance 실패로
  해석하지 않지만, 모듈 전체 DoD는 PENDING으로 남긴다.
- fixture는 process restart recovery, durable persistence, authorization policy의 전체
  matrix, 외부 notification exactly-once를 증명하지 않는다. 이 네 항목은 기존 integration
  test와 운영 readiness 검증으로 분리한다.
- PR 생성·push·merge·원격 CI는 사용자가 승인하지 않았으므로 이번 검토에서 N/A다.

## 결론

**현재 로컬 conformance 구현: PASS — P0=0, P1=0, P2=0, P3=0.**

**durable replay/concurrency 단독 증거: PASS — `AppointmentControllerTest` 25개.**

**module 전체 테스트 DoD: PENDING — 기존 보안 통합 테스트 12개가 전체 실행에서만
실패하므로 원인 분리와 안정화가 별도 후속 과제다.**

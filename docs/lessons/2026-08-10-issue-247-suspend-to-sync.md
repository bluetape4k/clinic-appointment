# Issue #247 suspend-to-sync 경계 lesson

학습일: 2026-08-10
대상: `appointment-notification`

## 문제

`NotificationEventListener`와 scheduler runner가 `startCoroutine`와
`CountDownLatch.await()`를 직접 조합해 suspend delivery가 끝날 때까지 기다렸다.
resume이 오지 않으면 bounded executor worker와 scheduler 호출 thread가 무기한 점유되고,
`EmptyCoroutineContext` 때문에 cancellation owner도 경계 밖으로 사라졌다. notification
Auto-Configuration의 `@EnableScheduling`은 이 문제와 별개로 호스트의 unrelated
`@Scheduled` bean까지 암묵적으로 활성화했다.

## 결정

- blocking Spring event/scheduler 경계에서만 `runBlocking { withTimeout(...) }`를 사용한다.
- timeout은 `NotificationSuspendBridgeTimeoutException`이라는 일반 runtime 오류로 매핑하고,
  실제 `CancellationException`과 `InterruptedException`은 각각 호출자에게 전파하고 interrupt
  flag를 보존한다.
- `clinic.notification.worker.suspend-bridge-timeout`을 모듈 설정으로 노출해 event listener,
  Kafka consumer, 네 scheduler runner가 같은 deadline을 사용하게 한다.
- library auto-configuration은 scheduling을 켜지 않는다. scheduling이 필요한 호스트가
  `@EnableScheduling`을 명시적으로 선택한다.
- Kafka route DTO는 `Serializable`과 `serialVersionUID`를 명시하고, reflection 기반 Java
  serialization round-trip 회귀를 둔다.

## 검증

- 수정 전 never-resume 회귀: 2초 `CompletableFuture.get`이 끝나지 않아
  `TimeoutException`으로 RED.
- 수정 후 targeted 회귀 6건: deadline timeout, cancellation 전파, interruption 전파와 interrupt
  flag 보존, event listener 설정 적용, scheduler timeout, auto-configuration scheduling opt-in,
  route serialization 통과.
- `./gradlew :appointment-notification:test --no-daemon --no-configuration-cache --console=plain --rerun-tasks`:
  `SUCCESS: Executed 140 tests`, `BUILD SUCCESSFUL`.
- `./gradlew :appointment-notification:build --no-daemon --no-configuration-cache --console=plain --rerun-tasks`:
  test 140건, Kover verify, `check`, `build` 모두 통과.
- `git diff --check` 통과.

## 후속

`NotificationSchemaReadinessTest`의 직접 schema 생성은 schema contract fixture이므로 이번
브리지 변경에서 유지한다. 모듈 전체의 기존 JUnit `assertThrows` 정리는 동작 변경과 분리해
별도 maintenance issue로 다룬다. 실제 운영 scheduler/Redis와 원격 CI는 이 로컬 작업 범위에서
실행하지 않았다.

# #319 지원되는 리더 수명주기 관측 설계

## 문제와 목표

`appointment-notification`의 reminder recovery는 `@LeaderScheduled` 실행 경계와
기존 `leader.aop.*` 메트릭을 사용한다. 그러나 notification 모듈이 운영 관측
계약으로 제공하는 이벤트 이름과 태그 경계가 없어서, 한 번의 reminder 실행에서
획득·실행·skip·revoke 결과를 notification 관점으로 구분하기 어렵다.

현재 clinic-appointment가 잠근 `bluetape4k-leader:0.5.0`에는 다음 계약이
있다.

- `LeaderAopMetricsRecorder`: lock 획득·미획득과 task 완료·실패 callback
- `LeaderElectionListener`: elected·skipped·revoked callback
- `MicrometerObservationLeaderAopMetricsRecorder`와
  `MicrometerObservationLeaderElectionListener`: `ObservationRegistry` bridge

반면 lease-extension observer는 0.5.0에 없고, upstream의 후속 release에도
아직 반영되지 않았다. 따라서 이번 범위의 목표는 실제로 호출되는 callback만
notification 전용 저카디널리티 Observation으로 연결하는 것이다.

## 범위와 비범위

지원하는 notification lifecycle은 다음 네 가지다.

| lifecycle | 입력 callback | Observation 의미 |
|---|---|---|
| `acquire` | `onLockAcquired` | reminder lock을 획득함 |
| `skip` | `onLockNotAcquired` | lock을 획득하지 못해 action을 실행하지 않음 |
| `execute` | `onTaskFinished`, `onTaskFailed` | 획득한 action의 성공·실패·취소 결과 |
| `revoke` | `LeaderElectionListener.onRevoked` | leader lease가 revoke됨 |

다음 항목은 이번 변경에서 관측하지 않는다.

- `extend`: 0.5.0에 observer callback이 없으므로 성공처럼 추정하지 않는다.
- `ownership-loss`: 현재 callback으로 lease 소유권 상실을 판정할 수 없다.
- lease 연장·shutdown의 내부 상태를 polling해서 추정하는 별도 감시자
- 새 Redis/DB claim·fencing 계약, 새 telemetry backend, raw tenant·payload·request ID

`extend`와 `ownership-loss`는 upstream이 release한 observer 계약을 확인한 뒤
별도 후속 이슈에서 다시 설계한다.

## 대안과 선택

### A. AOP callback + listener registry를 사용하는 notification 관측 브리지 (권고)

notification 모듈에 하나의 얇은 bridge를 등록한다. bridge는
`LeaderAopMetricsRecorder`로 acquire·skip·execute를 받고
`LeaderElectionListener`로 revoke를 받는다. `LeaderElectionObservabilityAutoConfiguration`
이 `LeaderElectionListenerRegistry`에 Spring listener bean을 연결하므로, bridge가
`LeaderElectionEventPublisher.events`를 직접 구독하거나 coroutine scope를 소유하지
않는다.

각 callback은 고정된 Observation 이름과 low-cardinality key-value를 기록한다.
관측 callback 내부의 예외는 bridge가 삼키고 로그로 남긴다. scheduler가 던진 원래
예외와 `CancellationException`은 callback이 변경하지 않는다.

이 대안은 0.5.0에 이미 있는 API만 사용하고, notification이 운영 대시보드에서
사용할 이름과 태그를 별도로 고정한다. 기존 upstream `leader.aop.*` 및
`leader.election.event` 관측은 유지하되 같은 이름을 재사용하지 않아 중복 의미를
만들지 않는다.

### B. upstream Observation bean만 활성화하고 notification 계약은 추가하지 않음

Spring Boot의 `LeaderObservationAutoConfiguration`이 제공하는 recorder와 listener를
그대로 사용하고 테스트와 문서만 보강한다. 변경량은 작지만 reminder 전용 관측
이름·태그·callback 오류 격리 계약을 코드로 고정할 수 없고, lock name 포함 여부도
호스트의 `bluetape4k.leader.observability.tracing` 설정에 맡겨진다.

### C. 미래 lease-extension API를 reflection으로 탐색

현재 classpath에 없는 `LeaderLeaseExtensionObserver`를 선택적으로 찾아 연결한다.
지원되지 않는 API를 런타임 추측으로 감싸면 누락을 성공처럼 기록하거나 버전별
signature 차이를 조용히 삼킬 수 있다. 재현 가능한 계약이 아니므로 선택하지 않는다.

## 결정한 설계

### Bridge 계약

새 bridge는 `NotificationLeaderObservationBridge`라는 내부 구현으로 둔다. 공개
API가 아니며 reminder lock 하나만 처리한다.

- `name != REMINDER_RECOVERY_LOCK_NAME`인 callback은 무시한다.
- `ObservationRegistry.isNoop`이면 아무 작업도 하지 않는다.
- Observation 이름은 `clinic.notification.leader.lifecycle`로 고정한다.
- low-cardinality 태그는 `operation`, `outcome`, `lock` 세 개만 사용한다.
- `lock` 값은 raw lock name이 아니라 고정된 `reminder` enum 값이다.
- `operation`은 `acquire`, `execute`, `skip`, `revoke` 중 하나다.
- `outcome`은 `acquired`, `success`, `error`, `cancelled`, `skipped`, `revoked` 중 하나다.
- `SkipReason`과 예외 class 이름은 tag로 내보내지 않는다. 필요한 원인은 기존
  upstream `leader.aop.lock.not.acquired`와 `leader.aop.task.failed` metric에 맡긴다.
- `onTaskFailed`는 취소 여부만 `cancelled`/`error`로 구분하고, observation에
  원래 throwable을 전달할 수는 있지만 원래 예외를 대체하거나 재전파하지 않는다.
- observation handler가 실패해도 bridge callback은 정상 반환한다.

`onElected`와 `onSkipped` listener callback은 AOP callback과 같은 결과를 중복
기록하지 않도록 사용하지 않는다. listener에서 실제로 필요한 `onRevoked`만
notification observation으로 변환한다. 이는 `LeaderElectionListenerRegistry`의
자동 등록 경계를 활용하면서 acquire/skip을 한 번만 기록하기 위한 선택이다.

### Spring wiring

- `ObservationRegistry`가 있고 notification이 활성화된 경우에만 bridge bean을
  만든다.
- `ObservationRegistry`가 없으면 기존 upstream AOP metric과 scheduler 동작을
  그대로 유지한다.
- `NotificationLeaderHealthMonitor`와 기존
  `NotificationLeaderAopMetricsRecorder`는 변경하지 않는다.
- 기존 `LeaderObservationAutoConfiguration`의 recorder/listener와 함께 등록될 수
  있지만 observation 이름이 다르므로 기존 meter·trace 의미를 덮어쓰지 않는다.
- bridge bean 등록은 `@ConditionalOnMissingBean`으로 host가 명시한 replacement를
  존중한다.

### 실행·오류 흐름

1. `@LeaderScheduled` AOP가 reminder lock을 시도한다.
2. 획득하면 bridge가 `acquire/acquired` observation을 종료한다.
3. action이 실행되고 성공하면 `execute/success`, 실패하면 `execute/error`,
   취소되면 `execute/cancelled` observation을 종료한다.
4. 정상 contention은 `skip/skipped` observation으로 종료한다.
5. lease revoke callback이 전달되면 `revoke/revoked` observation을 종료한다.
6. 각 단계에서 bridge observation이 실패해도 scheduler의 기존 failure mode,
   원래 action 예외, 취소 전파는 그대로 유지한다.

이 흐름은 lease 연장이나 ownership loss를 추정하지 않는다. 따라서 네 이벤트가
없다고 해서 lease가 안전하다고 해석하지 않으며, DB claim/fencing이 여전히
notification 정확성의 최종 권위다.

## 테스트 설계

다음 검증을 `appointment-notification` 모듈에 추가한다.

- bridge unit test: reminder lock만 기록하고 다른 lock은 무시하는지 확인
- lifecycle mapping test: acquire·execute 성공/실패/취소·skip·revoke 순서와
  outcome을 확인
- tag safety test: `tenant`, `payload`, `request`, raw lock name, exception tag가
  없고 고정된 세 tag만 있는지 확인
- no-op test: `ObservationRegistry.NOOP` 또는 registry 부재에서 기능이 유지되는지
  확인
- error isolation test: observation handler가 예외를 던져도 callback이 예외를
  밖으로 전파하지 않는지 확인
- existing `NotificationLeaderMicrometerTest` 회귀: `leader.aop.*` metric과
  `redacted-lock` tag, `shedlock.leader.*` 부재를 유지하는지 확인
- capability boundary test/documentation: `extend`와 `ownership-loss`를
  bridge가 생성하지 않음을 확인

Docker/Redis가 필요한 lease 수명주기 검증은 이 범위에 추가하지 않는다. 실제
backend lease extension 증거가 필요하면 upstream release가 준비된 후 별도
후속 이슈에서 다룬다.

## 호환성·롤백

- 기존 `NotificationLeaderAopMetricsRecorder`, health metric, scheduler API,
  DB schema와 lock 이름은 변경하지 않는다.
- ObservationRegistry가 없는 단일 JVM 환경은 새 bean 없이 이전 동작을 유지한다.
- bridge만 제거하거나 bean 조건을 끄면 upstream metric과 scheduler가 즉시 기존
  경로로 돌아간다. migration rollback은 필요하지 않다.
- 현재 #319 issue를 닫거나 metadata를 변경하지 않는다. 지원 범위 구현과
  lease-extension 후속 범위의 issue lifecycle은 별도 승인 후 처리한다.

## 수용 기준과 DoD

- [ ] 0.5.0 callback만으로 acquire·execute·skip·revoke Observation을 기록한다.
- [ ] `extend`·`ownership-loss`를 관측한다고 주장하는 코드와 문서가 없다.
- [ ] observation 이름과 low-cardinality 태그가 고정되고 raw 식별자가 노출되지 않는다.
- [ ] observation 오류가 scheduler 예외·취소 전파·기존 metric을 바꾸지 않는다.
- [ ] ObservationRegistry 부재 환경에서 기존 동작이 유지된다.
- [ ] 단위 테스트와 기존 notification leader Micrometer 테스트가 통과한다.
- [ ] `git diff --check`와 한국어 문서 자연스러움·용어 검토를 통과한다.

## 설계 검토 기록

- SPW-01 요구사항 추적: Issue #319의 네 지원 이벤트, low-cardinality, 예외 보존,
  optional ObservationRegistry를 범위·수용 기준에 연결했다.
- SPW-02 근거 원장: local `0.5.0` dependency lock과 upstream 0.5.0 API를
  확인했으며 lease-extension API가 없다는 사실을 비범위로 고정했다.
- SPW-03 대안 비교: A를 선택하고 B·C의 중복·설정 의존·reflection 위험을 기록했다.
- SPW-04 검증 계획: callback mapping, tag safety, no-op, error isolation, 회귀
  테스트를 명시했다.
- SPW-05 독자 관점: 운영자가 네 이벤트를 재구성할 수 있는 이름과 실패 경계를
  본문·표·DoD에서 같은 용어로 유지했다.
- KO-01~KO-06: 사실·식별자·불확실성·한국어 문체·용어·표면을 검토했다.
- KO-07: 코드와 문서가 고정된 뒤 `audit-korean-terms.mjs`를 실행한다.

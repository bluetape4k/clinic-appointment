# #254 구현 7-tier 검토

검토 대상은 현재 worktree의 `appointment-notification` 변경과
`bluetape4k-leader-micrometer` version-catalog alias이다. 설계·계획 문서의
P0/P1=0 판정을 기준으로 구현 diff, 테스트, 모듈 build, dependency graph를
다시 확인했다.

## 요구사항 추적

| 수용 조건 | 구현 근거 | 검증 근거 |
|---|---|---|
| reminder 전체 scan이 같은 leadership action 안에서 실행 | `NotificationSchedulingRunners.kt:89-95`에서 `runIfLeader` action 안에 `scheduler.triggerOnce()`를 배치 | `NotificationSchedulingRunnersTest` acquired/not-acquired 테스트 |
| 실제 elector bean이 실행 경계에 연결 | `NotificationAutoConfiguration.kt:461-470`의 optional `LeaderGroupElector` 주입 | auto-configuration 13개 테스트 |
| Micrometer acquired/not-acquired/duration/active 관측 | `NotificationAutoConfiguration.kt:539-554`의 `InstrumentedLeaderGroupElector` decorator | `NotificationLeaderMicrometerTest` 3개 테스트 |
| raw lock 식별자와 고 cardinality tag 차단 | decorator 기본 sanitization을 유지하고 `redacted-lock`만 assertion | `NotificationLeaderMicrometerTest` raw tag 부재 assertion |
| 실패·취소·Redis 오류 경계 | runner가 `CancellationException`을 먼저 재전파하고 일반 오류는 tick 경계에서 흡수 | runner 실패/취소/Redis 오류 테스트, decorator active gauge cleanup 테스트 |
| Redis/registry 선택적 동작 | Redis가 없으면 runner direct path, registry가 없으면 raw `LettuceLeaderGroupElector` 반환 | auto-configuration instrumented/raw fallback 테스트 |
| DB lease/fencing 권위와 범위 보호 | outbox schema·repository·worker lease/fencing 파일 미변경, README/KDoc에 권위 명시 | changed-file scope review |

## 7-tier 결과

| Tier | 우선순위 | 검토 결과 | 증거 |
|---|---|---|---|
| 성능 | P2 | blocking elector와 기존 `runSynchronously`가 Spring scheduled thread에서 동작한다. scanner는 기존 `batchSize`/`maxCandidatesPerRun` 상한을 유지하고 새 retry/buffer를 만들지 않는다. | `NotificationSchedulingRunners.kt:89-94`, `AppointmentReminderScheduler.kt:19-34` 정적 점검 |
| 안정성 | P0/P1 없음 | action/Redis 예외는 기존 tick 경계에 머물고 취소는 흡수하지 않는다. 공식 decorator의 `finally` cleanup을 active gauge 0 assertion으로 고정했다. | `NotificationSchedulingRunners.kt:103-106`, decorator failure 테스트 |
| 보안 | P0/P1 없음 | lock name을 metric tag로 그대로 내보내지 않고 기본 `redacted-lock`을 사용한다. 새로운 raw ID 로깅이나 secret 처리는 추가하지 않았다. | `NotificationLeaderMicrometerTest` tag assertion, README 운영 계약 |
| 운영 | P0/P1 없음 | acquired/skip/duration/active 네 meter와 Redis 장애 로그 경계를 연결했다. registry가 없어도 raw elector로 기능을 유지한다. | auto-config 두 fallback 테스트, dependency `0.5.0` resolve |
| 개발자/API | P0/P1 없음 | `ReminderRecoveryTriggerGuard`와 scheduler 인자는 direct caller source 호환용 deprecated로 남기고, 자동 구성 scheduled 경계에서는 제거했다. runner elector는 nullable 기본값이다. | `AppointmentReminderScheduler.kt:10-17,47-49`, plan compatibility decision |
| 사용자/호출자 | P0/P1 없음 | 다중 인스턴스에서는 한 tick을 leader action이 소유하고, 단일 인스턴스에서는 기존 direct path를 보존한다. 발송 정확성은 DB lease/fencing에 남는다. | README 두 locale, runner acquired/skip 테스트 |
| 통합/테스트 | P0/P1 없음 | version catalog, auto-configuration, runner, official decorator가 함께 검증되고 기존 notification suite가 회귀하지 않았다. | 전체 142개 테스트, module build, dependency insight |

## 성능·안정성 스캔

다음 파일을 현재 diff 기준으로 점검했다.

- `NotificationSchedulingRunners.kt`
- `NotificationAutoConfiguration.kt`
- `AppointmentReminderScheduler.kt`
- `NotificationLeaderMicrometerTest.kt`
- `NotificationSchedulingRunnersTest.kt`
- `NotificationAutoConfigurationTest.kt`

점검 명령은 `git diff --check`, `rg` 기반 blocking/cancellation/leader 경계
검색, `./gradlew :appointment-notification:test`,
`./gradlew :appointment-notification:build`이다. P0/P1 finding은 없다.
스케줄러는 원래 동기 Spring 경계였고, 새 코드는 페이지 상한·기존 helper·예외
경계를 재사용한다. coroutine 취소는 broad `Exception`보다 먼저 재전파한다.

## 검증 결과

```text
./gradlew :appointment-notification:test -> SUCCESS: Executed 142 tests in 5.7s
./gradlew :appointment-notification:test --tests '*NotificationSchedulingRunnersTest' \
  --tests '*NotificationLeaderMicrometerTest' --tests '*NotificationAutoConfigurationTest'
  -> SUCCESS: Executed 28 tests in 4.3s
./gradlew :appointment-notification:build -> BUILD SUCCESSFUL
./gradlew :appointment-notification:test --tests '*NotificationAutoConfigurationTest'
  -> SUCCESS: Executed 13 tests in 3.7s
./gradlew :appointment-notification:dependencyInsight \
  --dependency bluetape4k-leader-micrometer --configuration testRuntimeClasspath
  -> io.github.bluetape4k.leader:bluetape4k-leader-micrometer:0.5.0
```

`git diff --check`도 통과했다. 전체 build의 `koverVerify`와 module test가
현재 worktree 변경에 대해 성공했으며, resolved version은 직접 version을
추가하지 않고 BOM 규칙으로 `0.5.0`이다.

## 남은 갭과 판정

- 실제 Redis 서버를 이용한 lease 만료·fencing·네트워크 단절 통합 테스트는
  이 모듈의 기존 singleton launcher 범위 밖이므로 실행하지 않았다.
- GitHub Actions/production Redis 검증은 로컬 변경 작업의 권한·환경 밖이다.
  이는 코드 실패가 아니라 delivery/production `PENDING` 경계로 issue 댓글에
  명시한다.
- 새 benchmark나 성능 수치를 주장하지 않는다. 현재 근거는 bounded scan과
  unit/context 테스트, module build이다.

최종 구현 review 판정: **PASS (P0=0, P1=0)**. 위 PENDING 항목을 숨기지 않는
조건에서 로컬 구현·검증 DoD를 충족한다.

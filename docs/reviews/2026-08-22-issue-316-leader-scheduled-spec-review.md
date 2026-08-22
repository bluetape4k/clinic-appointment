# #316 `@LeaderScheduled` 전환 설계 검토

## 검토 범위와 기준

- 검토 대상: `docs/superpowers/specs/2026-08-22-issue-316-leader-scheduled-design.md`
- 기준 ref: 설계 보완 commit `7e9d358d`, feature branch
  `feat/issue-316-leader-scheduled`
- 저장소 근거: `NotificationSchedulingRunners.kt`,
  `NotificationAutoConfiguration.kt`, `NotificationLeaderHealth.kt`, 현재
  notification 테스트
- upstream 근거: `bluetape4k-leader 0.5.0`의 `LeaderScheduled.kt`,
  `LeaderElectionAspect.kt`, `LeaderState.kt`, `LeaderAopMetricsRecorder.kt`,
  `MicrometerNames.kt`
- 판정: P0/P1은 구현을 막는 결함, P2/P3는 명시적 후속 범위 또는 검증
  증거로 관리한다.

## 독립 관점 검토

| 우선순위 | 관점 | 근거와 판단 | 필요한 조치 | 재검토 |
|---|---|---|---|---|
| P2 | 성능 | `LeaderElectionAspect`는 factory/options 조합을 cache하고, health `state` 조회는 scheduled tick이 아닌 health 호출 경계에 둔다. tick마다 기존 lock 획득·해제 비용 외에 새 반복 조회를 추가하지 않는다. | 구현 후 AOP proxy와 기존 runner의 targeted test를 실행하고, 불필요한 health 조회가 tick에 들어가지 않는지 diff에서 확인한다. 별도 benchmark는 hot path 변경 증거가 생길 때만 연다. | 성능 구현 diff |
| P2 | 안정성 | `failureMode=SKIP`, `CancellationException` 재전파, 별도 application-ready bootstrap, single `LeaderState` cleanup 경계를 명시했다. 실제 shutdown/lease loss는 Spring lifecycle 환경 검증이 필요하다. | 계획에 proxy 직접 호출 구분, cancellation, shutdown, lease 대체 증거를 포함한다. | 계획 리뷰 및 테스트 |
| P2 | 보안 | lock 이름은 상수이고 annotation name은 외부 입력을 받지 않는다. upstream validator와 metric tag sanitization을 사용하므로 raw leader 식별자를 새로 노출하지 않는다. | 새 설정값을 추가하지 않고 기존 `redacted-lock` 계약을 유지한다. | 구현 import/metric test |
| P2 | 운영 | upstream `leader.aop.*`와 notification bounded health를 분리하고 rollback이 dependency/annotation commit 단위로 가능하다. 본문 예외는 기존 log/notification metrics로 남고 AOP `task.failed`와 혼동하지 않도록 명시했다. | PR DoD에 metric namespace read-back, health condition, rollback 경계를 포함한다. | 최종 review |
| P1 (해소) | 개발자/API | `@LeaderScheduled`는 single elector를 사용하지만 기존 health monitor는 group state를 읽고 있어 객체·상태 계약을 혼동할 위험이 있었다. 설계가 `LeaderElector`/`LeaderState.leader`로 전환하고, host-provided elector 우선과 optional conditions를 명시해 결함을 해소했다. | 계획에서 constructor compatibility, conditional context test, annotation import/API dependency를 정확한 파일 작업으로 고정한다. | 계획 리뷰에서 재확인 |
| P2 | 사용자/호출자 | public scheduler와 DB fencing은 유지되고, scheduled path만 annotation 경계로 바뀐다. Redis 없는 단일 JVM은 local factory로 계속 실행하며, 새 leader wait/lease 정책은 #317로 분리했다. | Korean KDoc/README가 실제 annotation과 지원 조건을 설명하는지 최종 문서 검토에서 확인한다. | 최종 문서 review |

## 통합 검토

### 중복·모순 정리

- P1 후보였던 group-versus-single health 불일치는 설계에서 `LeaderElector`와
  single `LeaderState`로 전환해 해소했다.
- AOP `task.failed`가 scheduler 본문에서 흡수된 예외까지 자동 기록한다고
  오해할 수 있었으나, 설계 보완에서 backend/AOP 실패와 본문 log/
  `NotificationOutboxMetrics`를 분리했다.
- `ApplicationReadyEvent` self-invocation은 AOP를 우회할 수 있으므로 별도
  bootstrap bean을 명시했고, 계획 단계에서 proxied/direct 호출 테스트를
  필수로 둔다.

### 저장소·릴리스·증거 점검

- 변경 대상은 `appointment-notification`, version catalog alias, 한국어
  설계/검토/lesson 문서로 제한한다. 새 module, schema migration, release
  train 변경은 없다.
- `bluetape4k-leader-spring-boot`는 BOM 관리 alias로만 추가하며 직접 버전을
  선언하지 않는다.
- DB claim/fencing, Redis 8.8 테스트 계약, deprecated direct-call surface는
  issue 범위 밖에서 보존한다.
- 구현 전에 계획 문서와 계획 review를 별도 승인하고, 구현 후 targeted/module
  test와 live Issue/PR read-back으로 증거를 갱신한다.

## 통합 판정

| 항목 | 결과 | 근거 |
|---|---|---|
| P0 | 0 | 설계 단계에서 즉시 차단 결함 없음 |
| P1 | 0 | single elector health 전환, metric 경계, proxy bootstrap을 문서에 반영 |
| P2 | 5 | 구현 diff·계획·최종 검증에서 재확인하거나 후속 증거로 관리 |
| P3 | 0 | 별도 저위험 발견 없음 |
| 설계 승인 상태 | PASS | 권장안 A를 사용자 승인받고 설계 commit `7e9d358d`에 반영 |

## 남은 게이트

- `writing-plans`로 수용 기준별 exact file/action/test/rollback을 작성한다.
- 계획 문서에 대해 동일한 6개 관점과 통합 검토를 수행하고 P0=0/P1=0을
  재확인한다.
- 계획 승인 전에는 Kotlin/Gradle 구현을 시작하지 않는다.

## 문서 게이트

- SPW-01: PASS — 독자·목적·한국어 정책·현재 소스와 upstream 근거를 고정했다.
- SPW-02: PASS — spec review의 범위, 6개 관점, 통합 판정, 남은 게이트를
  기록했다.
- SPW-03: PASS — 한국어 기술 문체와 stable terminology를 적용했다.
- SPW-04: PASS — 설계 commit, 로컬 경로, upstream 계약, acceptance/DoD를
  서로 대조했다.
- SPW-05: PASS — 최종 Markdown read-back, 표·목록·코드 토큰·P0/P1 판정을
  확인했다.

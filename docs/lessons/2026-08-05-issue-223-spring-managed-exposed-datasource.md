# Issue #223: Spring-managed Exposed DataSource 연결 경계

## Context

`ServiceConfig`와 `ProfileReevaluationConfiguration`이 각각
`Database.connect(dataSource)`와 Exposed 전역 기본 database 복원을 구현하고 있었다.
테스트·migration·dialect·Gatling 경로에는 Spring context를 사용할 수 없는 독립 fixture가
있으므로 전역 치환은 pool 소유권과 schema 격리를 깨뜨릴 수 있었다.

## Decision

- `ExposedDatabaseFactory`가 주입받은 `DataSource`로만 Exposed handle을 만들고 하나의
  `ReentrantLock`과 `finally` 복원으로 global default registration을 직렬화한다.
- `ExposedDatabaseLifecycle`이 factory 소유 handle의 Exposed manager를 context destroy
  시 `closeAndUnregister`한다. pool/connection은 Spring과 명시적 transaction 경계가
  소유한다.
- 세 Spring wiring test는 Hikari `DataSource`를 supplier로 만들고 고유 marker를 기록한
  뒤 context의 `Database`로 읽는다. context 종료 후 Hikari `isClosed`를 확인한다.
- standalone, migration/dialect, Gatling fixture는 목적과 owner/close 규칙을 runbook
  allowlist에 남기고 유지한다.

## Surprising failure and correction

첫 RED test는 factory symbol 부재를 정확히 드러냈다. factory를 추가한 뒤 H2 2.4에서
`value`가 reserved identifier라 marker DDL이 실패했으므로 `marker_value`로 바꿨다.
반복 transaction의 첫 실행은 dialect/isolation metadata acquisition을 추가로 만들기
때문에 warm-up 이후 acquisition 증가량을 측정하도록 성능 검증을 고정했다.

## Fresh evidence

- `./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --no-build-cache`
  — 4 passing; concurrent registration, exact injected DataSource acquisition, default
  restoration, lifecycle unregister를 검증했다.
- `./gradlew :appointment-api:test --tests '*DataSourceOwnershipContractTest' --no-build-cache`
  — 2 passing; production direct setup 경계를 검증했다.
- `./gradlew :appointment-api:test --tests '*ExposedDatabaseFactoryTest' --tests '*DataSourceOwnershipContractTest' --tests '*AppointmentCommitmentApplicationWiringTest' --tests '*ProfileReevaluationWiringTest' --tests '*NotificationReminderRecoveryWiringTest' --no-build-cache`
  — 17 actionable tasks, `BUILD SUCCESSFUL`; marker query와 Hikari close assertion을 포함한다.
- `git diff --check` — PASS.
- production inventory — `Database.connect(`는 `ExposedDatabaseFactory.kt` 한 곳이며,
  production Kotlin/Java source에 Hikari/SimpleDriver/DriverManager/JDBC literal은 없다.

## Review lesson

초기 계획은 default 복원만 다루고 Exposed manager registry 해제를 명시하지 않았다.
Stability review가 `Database.connect`의 global registration과
`TransactionManager.closeAndUnregister` 경계를 확인한 뒤 lifecycle bean과 실행 가능한
테스트를 추가했다. Security review가 발견한 `application*.yml`의 기존 profile URL과
sample credential은 Spring 설정 입력이며 이번 Kotlin/Java ownership guard의 범위가
아니다. 이 변경은 해당 credential/TLS 정책을 완화하거나 해결했다고 주장하지 않는다.

## Future guard

새 runtime pool은 명시적 qualifier/bean name, pool 고유 marker wiring test, lifecycle
unregister를 함께 추가한다. 새 standalone direct setup은 정확한 파일, 분류 이유,
resource owner, close 방법을 runbook에 먼저 기록하고 production audit를 통과시킨다.

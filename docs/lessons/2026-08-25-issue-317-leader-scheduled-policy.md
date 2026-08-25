# Issue #317 leader scheduled 정책 외부화 lesson

## 결정

리마인더 recovery runner에서 `@LeaderScheduled`의 운영 값을 제거하고 plain
`@Scheduled` fixed delay만 남겼다. `bluetape4k.leader.scheduling`의 upstream
properties·registry·BPP가 exact selector를 통해 lock name, wait/lease/min-lease,
backend, auto-extend, stream-bounded, failure mode를 startup에 적용한다.

정책이 꺼져 있거나 leader factory가 없으면 runner 자체를 만들지 않는다. 따라서
설정을 빠뜨린 profile이 leader 보호 없는 scheduled 실행으로 조용히 전환되지 않는다.
reminder의 bounded suspend bridge timeout보다 짧은 lease도 startup에서 거부한다.
leader lock은 중복 tick을 줄이는 실행 경계이고, 최종 상태 변경의 권위는 기존 DB
claim/fence와 provider idempotency key에 남는다.

## 재사용과 의존성

upstream Issue #603/PR #761이 merge된 뒤에도 stable 1.0.x가 없어 Central timestamp 사전 릴리스
`1.0.0-20260824.195548-7`을 네 leader 모듈에 동일하게 고정했다. 해당 artifact가
Java 25 Gradle variant만 제공하므로 root consumer toolchain·API fixture·Gatling compile
경계를 Java 25로 맞췄다. Java 21 consumer가 project dependency를 읽지 못해 governance
resolution이 실패한 사실을 확인했다. Gradle verification metadata writer가 timestamp와
normalized SNAPSHOT duplicate key를 처리하지 못해 Central에서 받은 정확한 SHA-256을
수동 read-back으로 추가했다.

테스트에서는 `LeaderElectorFactory` mock을 context마다 새로 만들지 않는다.
`ReusableLeaderElectorFactory`를 테스트 클래스 companion에서 한 번 생성하고 각
context에 필요한 `LeaderElector`만 교체한다. 이는 factory identity를 재사용하면서
테스트 간 elector 상태를 명시적으로 주입하는 경계다.
Java 25 테스트 runtime에서는 `bluetape4k-junit5:1.12.1`의 기본 jdk21 preview
provider를 그대로 사용할 수 없다. Java 21과 Java 25의 `StructuredTaskScope` preview
API와 class-file target이 다르므로 ServiceLoader가 jdk21 provider를 읽는 순간
탐색을 중단하고 provider 부재 오류를 낸다. API 모듈은 BOM이 관리하는
`bluetape4k-virtualthread-jdk25:1.12.1`을 test runtime에 추가하고 jdk21 provider를
제외했으며, 두 provider를 동시에 두지 않도록 lockfile과 verification metadata를
함께 갱신했다.
새 context 검증은 `bluetape4k-assertions`를 사용하고, plain `@Scheduled`와 외부
leader policy의 책임을 테스트 이름·실패 메시지에도 일치시킨다.

## 검증과 rollback

- targeted notification tests: 46개 통과
- 전체 `appointment-notification:test`: 210개 통과
- 전체 `appointment-api:test`: 869개 통과, 실패 0개, 오류 0개, skip 3개
- custom policy name이 health monitor state 조회와 AOP/Observation filter에 전달됨
- compile: `:appointment-notification:compileKotlin` 통과
- root compile-only build와 `detekt` (`NO-SOURCE`) 통과
- dependency contract/lock와 strict verification 통과
- consumer fixtures, `:appointment-notification:build`, `verifyDependencyGovernance` 통과
- negative context: default-off, factory 부재, selector 누락, 짧은 lease 확인
- rollback: `bluetape4k.leader.scheduling.enabled=false`로 reminder runner를 중지
- stable 전환: 1.0.x가 배포된 뒤 timestamp pin·lock·verification을 함께 바꾸고
  같은 테스트/빌드를 재실행

운영 profile은 `lettuceLeaderElectionFactory`를 사용하고 Redis 없는 API test
profile은 policy를 명시적으로 끈다. runtime reload, wildcard selector, global
`@Scheduled` 자동 변환은 이 이슈의 범위가 아니다.

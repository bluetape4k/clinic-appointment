# Suspend 경로의 JDBC Exposed transaction IO 경계

학습일: 2026-08-07
대상: `clinic-appointment`, `JdbcAppointmentReminderRecoveryStore`

## 문제

`JdbcAppointmentReminderRecoveryStore`의 `suspend` materializer가 `transaction(database)`를 호출하는 것만으로는 blocking JDBC 작업을 안전하게 실행할 수 없다. 호출자가 notification scanner의 suspend 경로일 때 caller dispatcher에서 JDBC transaction이 실행되면 event-loop starvation과 dispatcher 경계 위반이 발생한다.

## 규칙

- blocking Exposed transaction 전체를 주입된 `ioDispatcher` 안에 둔다.
- `enqueue`, `suppressMissed`처럼 직접 transaction을 여는 진입점은 각각 경계를 가진다.
- `scheduleFuture`처럼 다른 materializer로 위임하는 진입점은 위임 대상의 경계를 재사용하고 별도 nested dispatch를 만들지 않는다.
- dispatcher hop 횟수나 source 문자열 개수만 세는 검사는 보조 신호다. 회귀 테스트는 가능하면 실제 JDBC statement가 어느 실행 context/thread에서 수행됐는지 관찰해야 한다.

## 적용 결과

commit `569e5bc28863d94d3a7fe6bb9028d5443fc98489`에서 `enqueue`와 `suppressMissed`를 `withContext(ioDispatcher) { transaction(database) { ... } }`로 감싸고, `scheduleFuture`는 수정된 `enqueue`로 위임하게 했다. `findCandidates`의 기존 IO 경계와 scanner의 due/future/missed 호출 경로도 함께 확인했다.

## 검증

- 수정 전 dispatcher-boundary 회귀 테스트: RED
- 수정 후 targeted `JdbcAppointmentReminderRecoveryStoreTest` 및 `KotlinProductionPatternComplianceTest`: 14 passing
- 영향 범위 profile/notification/reliability/commitment 테스트: 171 passing
- 독립 code-quality review: P0=0/P1=0
- 독립 architecture review: P0=0/P1=0, P2=3 (`WATCH`)

## 후속

현재 local fix는 구현 P1을 해소했지만, Issue #208의 historical Type A gate를 소급 증명하지 않는다. 후속 review artifact는 exact implementation head와 six-lens identity/count를 명시하고, transaction-thread 관찰 테스트와 review metadata 정합화를 포함해야 한다.

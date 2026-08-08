# Suspend 경로의 JDBC Exposed transaction IO 경계

학습일: 2026-08-08 (현재 remediation delivery 정합화 갱신)
대상: `clinic-appointment`, `JdbcAppointmentReminderRecoveryStore`

## 문제

`JdbcAppointmentReminderRecoveryStore`의 `suspend` materializer가 `transaction(database)`를 호출하는 것만으로는 blocking JDBC 작업을 안전하게 실행할 수 없다. 호출자가 notification scanner의 suspend 경로일 때 caller dispatcher에서 JDBC transaction이 실행되면 event-loop starvation과 dispatcher 경계 위반이 발생한다.

## 규칙

- blocking Exposed transaction 전체를 주입된 `ioDispatcher` 안에 둔다.
- `enqueue`, `suppressMissed`처럼 직접 transaction을 여는 진입점은 각각 경계를 가진다.
- `scheduleFuture`처럼 다른 materializer로 위임하는 진입점은 위임 대상의 경계를 재사용하고 별도 nested dispatch를 만들지 않는다.
- dispatcher hop 횟수나 source 문자열 개수만 세는 검사는 보조 신호다. 회귀 테스트는 가능하면 실제 JDBC statement가 어느 실행 context/thread에서 수행됐는지 관찰해야 한다.

## 적용 결과

production fix commit `569e5bc28863d94d3a7fe6bb9028d5443fc98489`에서 `enqueue`와 `suppressMissed`를 `withContext(ioDispatcher) { transaction(database) { ... } }`로 감싸고, `scheduleFuture`는 수정된 `enqueue`로 위임하게 했다. `findCandidates`의 기존 IO 경계와 scanner의 due/future/missed 호출 경로도 함께 확인했다.

후속 evidence commits `e666bfc`와 `936e62d`는 실제 JDBC statement 실행을 관찰하는 proxy 회귀 테스트를 추가하고, 테스트명을 statement 범위에 맞게 조정했으며, source-occurrence 개수에 의존하던 정적 검사를 `withContext(ioDispatcher)` 존재 여부만 확인하는 보조 guard로 줄였다. 현재 구현 exact head는 PR #232 `936e62d7af98a82f4db147813fcd1c41e44498fe`이며, merge commit은 `addff53107a5fc3d9e30e160bb66e253f809f5b7`다.

## 검증

- 수정 전 dispatcher-boundary 회귀 테스트: RED
- 수정 후 targeted `JdbcAppointmentReminderRecoveryStoreTest` 및 `KotlinProductionPatternComplianceTest`: 14 passing
- 영향 범위 profile/notification/reliability/commitment 테스트: 171 passing
- 독립 final code-quality review: P0=0/P1=0/P2=0/P3=0 (`COMMENT` only because `lsp_diagnostics` is unavailable)
- 독립 final architecture review: implementation `CLEAR`; pre-refresh metadata `WATCH` P0=0/P1=0/P2=1/P3=0, resolved by the exact-head documentation refresh PR #233
- `git diff --check`: passing

## 후속

PR #232는 구현 P1을 해소했고 PR #233은 exact-head current metadata를 정합화했지만, Issue #208의 historical Type A gate를 소급 증명하지 않는다. `Statement.execute*` 관찰은 JDBC statement 실행 context를 증명하며, connection lifecycle 전체를 증명하는 주장은 아니다. PR #232/#233의 push/CI/merge는 완료됐고, Issue closure는 historical six-lens identity/count 또는 reviewed `N/A` 결정 전까지 PENDING으로 유지한다.

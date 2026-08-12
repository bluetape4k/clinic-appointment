# Issue #34 환자 예약 약속 구현 7-tier code review

## 결론

현재 구현은 로컬 모듈 검증과 포털 단위·브라우저 계약까지 통과했지만,
PostgreSQL 취소 성능 gate, 실제 mixed-schema backlog benchmark, 보호된
backend Playwright harness가 아직 없다. 따라서 이 문서의 최종 상태는
`PENDING`이며 PR/merge 준비 상태로 승격하지 않는다.

- 검토 대상: `feat/issue-34-patient-commitment`
- 기준: `develop` (`fe772eb4`)부터 구현 커밋 `043ca8e` 및 이후 미커밋 변경
- 범위: 환자 code-only 취소, ADMIN/STAFF bounded detail, canonical hash와
  cancellation snapshot, notification v1/v2 readiness, Angular portal cancel
  flow, migration/security/observability
- 제외: Issue #305 환자 취소 이력 조회·감사 UI

## 7-tier 판정

| Tier | 검토 내용 | 현재 근거 | 판정 |
|---|---|---|---|
| 1. 구조·의존성 | reason registry를 `appointment-core`에 두고 API/event/notification이 공통 계약을 사용한다. ServiceConfig와 NotificationAutoConfiguration의 v2 flag/readiness 경계를 연결했다. | `CancellationReasonRegistry.kt`, `ServiceConfig.kt`, `NotificationAutoConfiguration.kt` | 로컬 PASS |
| 2. 보안·개인정보 | 취소 route의 ADMIN/STAFF/PATIENT matcher와 ownership 재검증, patient detail 차단, DB 원문·outbox hash 분리를 적용했다. | `AppointmentCommitmentHttpSupport.kt`, `AppointmentCommitmentAccessResolver.kt`, security integration tests | 로컬 PASS; 실제 IDOR fixture는 미실행 |
| 3. API·도메인 | 폐쇄 reason code, bounded detail, ETag/idempotency, 상태 terminal 전이를 API·command·frontend에 반영했다. | DTO/command tests, OpenAPI contract, facade/page tests | 로컬 PASS |
| 4. 데이터·트랜잭션 | V27 additive migration과 cancellation detail snapshot을 상태 전환·audit/outbox 전에 같은 transaction으로 기록한다. | H2/PostgreSQL/MySQL migration tests, command/atomicity tests | 로컬 PASS; 기본 Colima Ryuk 소켓 환경은 2건 실패했으나 Ryuk 비활성 재실행에서 698건 통과 |
| 5. 이벤트·알림 | canonical `cancel-v1\\0` codec, v1/v2 dual-read, cancellation template v2, null/detail escape, producer readiness gate를 구현했다. | event/notification/API tests | 로컬 PASS; 실제 backlog drain 성능 미검증 |
| 6. 포털·접근성 | Angular 22 client/facade와 `CANCELLED` terminal stepper, code-only confirmation, 412 single-flight를 구현했다. 로그인 주체 전환 시 facade/sessionStorage를 폐기하고 세대가 지난 비동기 응답을 차단하며, busy/stale mutation은 성공으로 오인하지 않는다. | 38개 파일·252개 Vitest tests, build, 4 Playwright tests | 로컬 PASS; protected backend harness와 320px/AT matrix 미검증 |
| 7. 테스트·운영·성능 | 모듈별 검증과 diff hygiene는 통과했으나 계획된 30초 warm-up/5분 PostgreSQL gate와 codec benchmark artifact/CI가 없다. | 아래 증거 목록 | PENDING |

## 독립 검토 결과

### Code reviewer

독립 `implementation_code_review` lane에서 처음 보고된 P1은 다음 구현으로
닫았다. (1) v1 producer의 cancellation detail은 조용히 버리지 않고
`NotificationContractException`으로 fail-closed하며 outbox row를 남기지 않는다.
(2) codec envelope, claimed DB metadata, renderer catalog identity를 공통
registry로 검증하고 reminder canonical key와 slot의 교차 조합을 거부한다.
(3) 페이지 intent key는 secure random+입력 fingerprint이며 성공·terminal·412에서는
폐기하고 명시적 transport/503에서만 재사용한다. status 0 tenant-missing과
transport를 분리하고 408/429/500/502/504는 회전시킨다. facade busy/stale 결과는
`false`로 명시해 페이지가 성공으로 처리하지 않으며 non-412 취소 오류는 호출자에게
재전파한다. (4) commitment 복구는 404만 신규 폼을 허용하고 그 밖의 인증·권한·
네트워크 오류는 retry 상태를 표시한다. (5) legacy notification writer의 detail
overload는 detail을 조용히 폐기하지 않고 계약 예외로 닫는다. 이 항목들의
targeted regression은 event codec 14개, notification 155개, API 25개,
frontend 252개가 통과했다. 최신 architect 재검토는 코드 기준 P0=0/P1=0/P2=0,
`CLEAR`로 판정했다. code reviewer의 최종 재검토 회신 전까지 merge gate는 열지 않는다.

### Architect

독립 `implementation_arch_review` 결과는 최신 수정 기준 `CLEAR`이다
(P0=0, P1=0, P2=0).
로그아웃·로그인 reset이 memory state, conflict map, appointment reference
storage를 삭제하고 session generation을 증가시키며, request/load/accept/
decline/cancel/412 refresh의 성공·오류·`finally`가 세대를 비교한다. deferred
응답 회귀 테스트도 추가되어 이전 환자 응답이 새 세션에 기록되는 P1은 닫혔다.

`clinicDisplayName`은 proposal snapshot이 아니라 tenant·clinic ownership을
재검증한 현재 canonical `Clinics.name`으로 표시하는 정책이며, 설계 문서와 구현이
일치한다. 외부 rollout·성능·보호 backend 증거가 없어 PR/merge 상태는 계속
`PENDING`이다.

## 새로 확인한 검증 증거

| 명령 | 결과 |
|---|---|
| `./gradlew :appointment-core:test --tests '*CancellationReasonRegistryTest*' --no-daemon` | BUILD SUCCESSFUL (targeted registry lane) |
| `./gradlew :appointment-core:test --no-daemon --rerun-tasks` (Ryuk disabled) | 698 passing, BUILD SUCCESSFUL; 기본 환경은 Colima Ryuk socket mount 오류로 2건 실패 |
| `./gradlew :appointment-event:test --no-daemon` | 194 passing, BUILD SUCCESSFUL |
| `./gradlew :appointment-event:test --tests '*NotificationOutboxCodecTest*' --no-daemon` | 14 passing, BUILD SUCCESSFUL |
| `./gradlew :appointment-notification:test --no-daemon --rerun-tasks` | 155 passing, BUILD SUCCESSFUL |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test` (Issue #34 관련 filter) | 102 passing, BUILD SUCCESSFUL |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --no-daemon --rerun-tasks` | 771 passing, 3 pending, BUILD SUCCESSFUL (5분 39초) |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --tests '*FlywayMigrationTest*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*FlywayMySQLMigrationTest*' --no-daemon` | 21 passing, 1 pending, BUILD SUCCESSFUL |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --tests '*JdbcAppointmentReminderRecoveryStoreTest*' --no-daemon` | 10 passing, BUILD SUCCESSFUL; recovery schema v1 확인 |
| frontend `npx ng test --watch=false` | 38 files, 252 passing |
| frontend `npm run build` | 성공 |
| frontend `npm run test:e2e` | 4 passing |
| `git diff --check` | 오류 없음 |

## 미검증·차단 항목

1. `appointment-api:gatlingRun`의 PostgreSQL fixture/simulation, 고정 dataset,
   30초 warm-up·5분 측정·baseline/candidate 3회와 p95/p99/lock-wait artifact가
   아직 구현·실행되지 않았다.
2. 실제 notification v1/v2 JSON decode와 DB backlog drain을 수행하는
   `NotificationCodecBacklogBenchmarkTest` 및 comparator/CI artifact가 없다.
3. 보호된 backend와 Playwright를 한 번에 실행해 ETag/412, 권한, outbox,
   trace/screenshot/request-count를 보존하는 harness가 없다.
4. production rollout readiness, schema backlog 0, provider delivery unknown 상태는
   운영 환경에서 확인하지 않았다.

## 상태

- P0: 0 (현재 확인 범위)
- P1: 0 (architect 재검토 기준; code reviewer 최종 보고서 수신 전 보류)
- P2: 0 (architect 재검토 기준)
- Architectural Status: `CLEAR`
- 최종: `PENDING`
- PR/merge: 성능·보호된 외부 gate가 충족될 때까지 대기

성능 artifact가 없는 상태에서 merge blocker를 우회하지 않는다. 다음 실행은
계획 Task 7의 PostgreSQL 취소 simulation과 실제 codec backlog benchmark를
동일 환경에서 먼저 추가하는 것이다.

# Issue #34 환자 예약 약속 구현 7-tier code review

## 결론

현재 구현은 로컬 모듈 검증과 포털 단위·브라우저 계약까지 통과했고,
PostgreSQL 취소 성능 lane과 실제 mixed-schema backlog benchmark의 실행
harness도 추가했다. 이전 exact head의 PR 일반 CI 22개 검사는 모두 통과했지만,
독립 최종 검토에서 Flyway 비활성 스키마의 취소 snapshot 테이블 누락과 cancellation
template v1 backlog 미지원이 확인됐다. 두 항목은 회귀 테스트와 함께 로컬에서 수정했고
repair head 재검토에서 code review는 `APPROVE`였지만 architect가 operator detail의
PHI/PII 확산 방어가 계획과 risk register에만 남은 P1을 확인했다. 공통 registry에
민감 식별자 패턴 차단을 추가하고 API/event decode가 같은 계약을 사용하도록 수정했으며,
새 exact head 검증을 기다린다. 고정 window의
baseline/candidate 3회 artifact, 보호된 backend Playwright harness, 운영 rollout
증거는 아직 없다. 따라서 이 문서의 최종 상태는 `PENDING`이며 PR/merge 준비
상태로 승격하지 않는다.

- 검토 대상: `feat/issue-34-patient-commitment`
- 기준: `develop` (`fe772eb4`)부터 현재 브랜치의 모든 committed implementation changes와 독립 검토 P1 repair diff
- 범위: 환자 code-only 취소, ADMIN/STAFF bounded detail, canonical hash와
  cancellation snapshot, notification v1/v2 readiness, Angular portal cancel
  flow, migration/security/observability
- 제외: Issue #305 환자 취소 이력 조회·감사 UI

## 7-tier 판정

| Tier | 검토 내용 | 현재 근거 | 판정 |
|---|---|---|---|
| 1. 구조·의존성 | reason registry를 `appointment-core`에 두고 API/event/notification이 공통 계약을 사용한다. ServiceConfig와 NotificationAutoConfiguration의 v2 flag/readiness 경계를 연결했다. | `CancellationReasonRegistry.kt`, `ServiceConfig.kt`, `NotificationAutoConfiguration.kt` | 로컬 PASS |
| 2. 보안·개인정보 | 취소 route의 ADMIN/STAFF/PATIENT matcher와 ownership 재검증, patient detail 차단, DB 원문·outbox hash 분리, 공통 registry의 email·전화/계좌/카드형 숫자열·민감 field marker 차단을 적용했다. 오류에는 원문을 포함하지 않고 API/event decode가 같은 계약을 사용한다. | `CancellationReasonRegistry.kt`, `AppointmentCommitmentHttpSupport.kt`, `AppointmentCommitmentAccessResolver.kt`, API/event/security tests | 로컬 PASS; 실제 IDOR fixture와 production ACL·backup·provider log 정책은 미검증 |
| 3. API·도메인 | 폐쇄 reason code, bounded detail, ETag/idempotency, 상태 terminal 전이를 API·command·frontend에 반영했다. | DTO/command tests, OpenAPI contract, facade/page tests | 로컬 PASS |
| 4. 데이터·트랜잭션 | V27 additive migration과 cancellation detail snapshot을 상태 전환·audit/outbox 전에 같은 transaction으로 기록한다. Flyway 비활성 dev/test 초기화기도 같은 snapshot 테이블을 생성한다. | H2/PostgreSQL/MySQL migration tests, command/atomicity tests, `SchemaInitConfigTest` | 로컬 PASS; 기본 Colima Ryuk 소켓 환경은 2건 실패했으나 Ryuk 비활성 재실행에서 698건 통과 |
| 5. 이벤트·알림 | canonical `cancel-v1\\0` codec, v1/v2 dual-read, cancellation template v1/v2, null/detail escape, 두 template의 producer readiness gate와 실제 outbox-row backlog drain harness를 구현했다. | event/notification/API tests, `NotificationCodecBacklogBenchmarkTest` | 로컬 PASS; 고정 10,000건·30초/5분 3회 성능 비교 미검증 |
| 6. 포털·접근성 | Angular 22 client/facade와 `CANCELLED` terminal stepper, code-only confirmation, 412 single-flight를 구현했다. 로그인 주체 전환 시 facade/sessionStorage를 폐기하고 세대가 지난 비동기 응답을 차단하며, busy/stale mutation은 성공으로 오인하지 않는다. | 38개 파일·252개 Vitest tests, build, 4 Playwright tests | 로컬 PASS; protected backend harness와 320px/AT matrix 미검증 |
| 7. 테스트·운영·성능 | 모듈별 검증과 diff hygiene, PostgreSQL cancel/codec smoke wiring, PR 일반 CI는 통과했으나 계획된 30초 warm-up/5분 고정 window의 baseline/candidate artifact와 보호된 backend gate가 없다. | 아래 증거 목록 | PENDING |

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
frontend 252개가 통과했다. 이후 exact head 최종 검토에서 Flyway 비활성
`SchemaInitConfig`가 `AppointmentCancellationDetails`를 만들지 않는 P1을 새로 찾았다.
실제 초기화기를 실행한 H2 회귀 테스트를 먼저 RED로 확인한 뒤 테이블을 FK 의존 순서에
추가했고, 관련 API targeted 46개 테스트가 통과했다. repair head `058006f5` 독립
재검토는 P0/P1/P2/P3 모두 0으로 `APPROVE`했으며 새 privacy repair head의 재검토
전까지 merge gate는 열지 않는다.

### Architect

이전 `implementation_arch_review` 결과는 당시 수정 기준 `CLEAR`였다
(P0=0, P1=0, P2=0).
로그아웃·로그인 reset이 memory state, conflict map, appointment reference
storage를 삭제하고 session generation을 증가시키며, request/load/accept/
decline/cancel/412 refresh의 성공·오류·`finally`가 세대를 비교한다. deferred
응답 회귀 테스트도 추가되어 이전 환자 응답이 새 세션에 기록되는 P1은 닫혔다.

`clinicDisplayName`은 proposal snapshot이 아니라 tenant·clinic ownership을
재검증한 현재 canonical `Clinics.name`으로 표시하는 정책이며, 설계 문서와 구현이
일치한다. 외부 rollout·성능·보호 backend 증거가 없어 PR/merge 상태는 계속
`PENDING`이다. 이후 exact head 최종 검토는 기본 catalog가 cancellation template
v2만 제공해 기존 v1 backlog가 `TEMPLATE_NOT_FOUND`로 소진될 수 있는 P1을 찾았다.
기본 catalog에 code-only v1 template을 추가하고, readiness가 모든 활성 channel에서
v1과 v2 identity·필수 field를 함께 검증하도록 수정했다. v2-only catalog 거부와 실제
v1 렌더링 회귀를 RED→GREEN으로 확인했다. repair head `058006f5` 재검토는 두 항목이
닫혔음을 확인했지만 operator detail에 PHI/PII가 저장·전파될 수 있는 계획상 미완료
P1을 새로 확인했다. API 계약은 유지하면서 `appointment-core` registry가 email,
전화·계좌·카드형 숫자열, 환자번호·진단·처방 marker를 거부하도록 하고 API request와
event codec의 negative test를 RED→GREEN으로 고정했다. ISO 날짜·시간 일정 문구는
오탐하지 않는 회귀도 함께 고정했다. 이 privacy repair를 포함한 새 exact head의 독립
재검토 전까지 merge gate는 열지 않는다.

## 새로 확인한 검증 증거

| 명령 | 결과 |
|---|---|
| `./gradlew :appointment-core:test --tests '*CancellationReasonRegistryTest*' --no-daemon` | BUILD SUCCESSFUL (targeted registry lane) |
| `./gradlew :appointment-core:test --no-daemon --rerun-tasks` (Ryuk disabled) | 698 passing, BUILD SUCCESSFUL; 기본 환경은 Colima Ryuk socket mount 오류로 2건 실패 |
| `./gradlew :appointment-event:test --no-daemon --rerun-tasks` | 197 passing, BUILD SUCCESSFUL |
| `./gradlew :appointment-event:test --tests '*NotificationOutboxCodecTest*' --no-daemon` | 14 passing, BUILD SUCCESSFUL |
| `./gradlew :appointment-event:test --tests '*NotificationCodecBacklogBenchmarkTest*' --no-daemon` | 실제 H2 outbox smoke PASS, artifact 생성 |
| `./gradlew :appointment-event:test --tests '*NotificationCodecBacklogBenchmarkTest*' --rerun-tasks -Dissue34.codec.benchmark=true -Dissue34.codec.rows=1000 -Dissue34.codec.measureSeconds=0 -Dissue34.codec.warmupSeconds=0 -Dissue34.codec.mix=current-heavy` | current-heavy wiring smoke PASS, decode failures 0 |
| `./gradlew :appointment-notification:test --no-daemon` | 157 passing, BUILD SUCCESSFUL; cancellation v1/v2 catalog/readiness 포함 |
| `./gradlew :appointment-api:test --tests '*SchemaInitConfigTest*' --tests '*AppointmentCommitmentCommandServiceTest*' --tests '*AppointmentNotificationWriterTest*' --tests '*AppointmentMessagingAutoConfigurationWiringTest*' --no-daemon` | 46 passing, BUILD SUCCESSFUL; Flyway 비활성 snapshot table 포함 |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-core:test --tests '*CancellationReasonRegistryTest*' :appointment-event:test --tests '*NotificationOutboxCodecTest*' :appointment-notification:test --tests '*NotificationTemplateRendererTest*' --tests '*NotificationAutoConfigurationTest*' :appointment-api:test --tests '*AppointmentRequestV2Test*' --tests '*SchemaInitConfigTest*' --tests '*AppointmentCommitmentCommandServiceTest*' --tests '*AppointmentNotificationWriterTest*' --rerun-tasks --no-daemon` | 52 passing, BUILD SUCCESSFUL; 민감 식별자 차단·원문 비노출·정상 일정 오탐 방지 포함 |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test` (Issue #34 관련 filter) | 102 passing, BUILD SUCCESSFUL |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --no-daemon --rerun-tasks` | 771 passing, 3 pending, BUILD SUCCESSFUL (5분 39초) |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --tests '*FlywayMigrationTest*' --tests '*FlywayPostgreSQLMigrationTest*' --tests '*FlywayMySQLMigrationTest*' --no-daemon` | 21 passing, 1 pending, BUILD SUCCESSFUL |
| `TESTCONTAINERS_RYUK_DISABLED=true ./gradlew :appointment-api:test --tests '*JdbcAppointmentReminderRecoveryStoreTest*' --no-daemon` | 10 passing, BUILD SUCCESSFUL; recovery schema v1 확인 |
| frontend `npx ng test --watch=false` | 38 files, 252 passing |
| frontend `npm run build` | 성공 |
| frontend `npm run test:e2e` | 4 passing |
| `git diff --check` | 오류 없음 |
| `node --test tests/benchmarks/appointment-messaging-benchmark-scripts.test.mjs` | 9 passing, BUILD SUCCESSFUL; baseline/candidate 동일 `sourceCommit` 거부 |
| `gh pr checks 306 --repo bluetape4k/clinic-appointment` | 22 checks passing, 0 pending/failing; CI/Frontend/Visual Companion jobs completed successfully |

## 미검증·차단 항목

1. `appointment-api:gatlingRun`의 PostgreSQL fixture/simulation과 고정 dataset,
   30초 warm-up·5분 측정 경로는 구현됐지만 baseline/candidate 3회와
   p95/p99/lock-wait artifact를 아직 실행·비교하지 않았다.
2. 실제 notification v1/v2 JSON decode와 DB backlog drain을 수행하는
   `NotificationCodecBacklogBenchmarkTest`와 mixed-ratio comparator는 구현됐고
   smoke가 통과했지만, 10,000건·30초/5분 3회 artifact와 CI gate는 아직 없다.
   comparator는 `sourceCommit`이 없거나 `unknown`이거나 baseline/candidate가
   같으면 실패하도록 고정했다.
3. 보호된 backend와 Playwright를 한 번에 실행해 ETag/412, 권한, outbox,
   trace/screenshot/request-count를 보존하는 harness가 없다.
4. production rollout readiness, schema backlog 0, provider delivery unknown 상태는
   운영 환경에서 확인하지 않았다.

## 상태

- P0: 0 (현재 확인 범위)
- P1: 로컬 known issue 0; 새 exact head 독립 재검토 전 보류
- P2: 0 (현재 확인 범위)
- Architectural Status: `PENDING` (`058006f5` 재검토 `BLOCK`, privacy repair exact-head 재검토 전)
- 최종: `PENDING`
- PR CI: `88cdb5fa` 기준 22/22 checks PASS; `058006f5`는 마지막 확인 시 17 PASS/3 running이며 privacy repair의 새 exact head CI·독립 review와 성능·보호된 외부 gate는 미완료
- PR/merge: 성능·보호된 외부 gate와 독립 review가 충족될 때까지 대기

성능 artifact가 없는 상태에서 merge blocker를 우회하지 않는다. `issue34.mode`는
현재 report metadata만 바꾸므로 동일 코드 경로를 baseline/candidate로 반복 실행한
결과는 pre-change 근거가 아니다. 다음 실행은 실제 pre-change 구현 또는 승인된
baseline artifact를 확보한 뒤 계획 Task 7의 PostgreSQL 취소 simulation과 실제
codec backlog benchmark를 동일 환경에서 3회 실행해 comparator evidence를 남기는
것이다.

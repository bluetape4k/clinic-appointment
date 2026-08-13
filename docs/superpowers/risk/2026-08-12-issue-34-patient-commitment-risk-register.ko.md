# Issue #34 취소 알림·포털 위험 register

| 위험 | 신호 | 완화 | rollback/rerun 지점 |
|---|---|---|---|
| patient가 operator용 detail을 주입 | role matrix test에서 PATIENT body가 2xx가 됨 | controller와 application/command 양쪽에서 detail non-null을 거부하고 `400`; request hash에는 role과 canonical detail을 함께 포함 | Task 1/3 테스트 실패 시 해당 task로 복귀; security test 재실행 |
| v1 consumer가 v2 outbox를 거부 | codec decode failure, quarantine 증가, notification lag 증가 | consumer/decoder를 먼저 v1/v2 호환으로 배포하고 모든 worker replica readiness·codec matrix·mixed-schema drain을 확인한 뒤 feature flag로 v2 producer를 활성화; template version을 cancellation만 2로 분리 | writer를 v1로 되돌린 뒤 `schema_version=2`의 `PENDING`/`PROCESSING`/`RETRY_WAIT`가 lease+retry 최대 구간 동안 0인지 확인하기 전에는 dual-reader 제거 금지 |
| cancellation template v2가 준비되지 않음 | `TEMPLATE_NOT_FOUND` retry/exhausted 또는 detail 미렌더링 | 모든 활성 channel의 catalog/renderer readiness와 missing-template fail-before-producer-activation을 검증 | v2 producer 중지, template v2 readiness 복구 전 code-only writer 유지 |
| operator가 staged rollout 상태를 판정할 수 없음 | compile-time schema version만 바뀌거나 일부 replica가 v1-only인 상태에서 v2 row가 생성됨 | `clinic.notification.v2-producer` default-off feature flag/property, readiness endpoint, replica별 codec matrix, vendor별 JSON extraction/indexed projection backlog query, decode/template metric·alert와 timeout runbook을 함께 제공 | readiness/metric evidence 없이는 v2 producer 및 dual-reader 제거 금지 |
| cancel 상태와 detail/outbox가 부분 기록 | transaction rollback 뒤 detail/audit/outbox row count 불일치 | 같은 Exposed transaction, idempotency result 완료 이후에만 응답; rollback/duplicate 테스트 | V27은 additive 유지, writer를 이전 code-only mode로 전환; Task 3 atomicity 재실행 |
| 안내 문구가 template/log injection 경계를 넘음 | 미등록 text가 persisted payload/log에 존재 | 서버 소유 고정 안내문 exact allow-list, durable object `toString()` redaction, raw detail 로그 금지 | 새 producer 중지 후 code-only template; Task 1/4 validation 재실행 |
| 동일 ETag의 빠른 취소가 두 번 side effect 발생 | duplicate allocation release/notification row 또는 412 누락 | `Idempotency-Key` + `If-Match` 필수, replay는 stored result 반환, busy UI guard | API를 일시 read-only로 전환하지 않고 failed request를 same key replay; Task 3/5 concurrency tests 재실행 |
| 취소와 reminder scanner가 경합 | 취소 후 stale reminder 발송, lease recovery가 late completion을 수락 | `PENDING`/`RETRY_WAIT`/`PROCESSING` 각각에서 cancel commit의 suppression, lease fence, rollback 보존을 command 통합 테스트로 검증 | provider 호출 전이면 suppression, 호출 후 delivery unknown이면 운영 상태와 환자 노출 정책을 별도 처리; Task 3 concurrency lane 재실행 |
| cancel route 권한 완화로 다른 role이 진입 | security integration에서 DOCTOR/SYSTEM이 200 또는 service lookup 수행 | matcher는 ADMIN/STAFF/PATIENT만 허용하고 controller/access resolver가 재검증; denied request lookup budget assertion | matcher 변경을 revert하고 operator/PATIENT 허용 범위로 복귀; Task 1 security lane 재실행 |
| V27 vendor별 DDL 차이 | H2는 통과하지만 PostgreSQL/MySQL migration/constraint가 실패 | 세 vendor Flyway contract test, 기존 V26 identity/timestamp/charset convention 재사용 | V27 additive table은 남기고 feature flag로 detail write 중단; migration test 재실행 |
| 포털 terminal 상태가 사용자에게 오해를 줌 | CANCELLED/EXPIRED에서도 mutation button 노출, focus/aria 누락 | status stepper의 text+aria-current, terminal action hidden, Playwright 320px/keyboard 검증 | UI cancel action만 숨기고 read-only status 유지; Task 6 component/e2e 재실행 |
| 상태 매핑 누락으로 취소가 제안 상태로 보임 | `CANCELLED` response가 proposal view로 귀결되거나 `REQUESTED` step이 API 상태로 오인됨 | local `REQUESTED`와 API status step ID를 분리하고 terminal view를 명시적 union으로 고정 | facade/component test 실패 시 Task 6 재실행 |
| 412 재조회가 오래된 취소를 재전송 | 자동 retry로 의도하지 않은 취소 또는 stale response가 최신 상태를 덮음 | appointment별 single-flight refresh, stale response 무시, 확인 dialog focus 복귀, 새 intent에서만 새 key 발급 | 412에서는 mutation을 중지하고 사용자가 재확인할 때까지 대기; Task 5/6 browser lane 재실행 |
| 브라우저가 실제 commitment/cancel 경계를 검증하지 못함 | API stub만 통과하고 ETag/412/terminal mutation 회귀가 누락됨 | deterministic backend fixture 또는 보호 harness, trace/screenshot/request-count 보존 | backend integration과 E2E를 함께 재실행하고 증거 없이는 PR 준비 금지 |
| accept/decline stale mutation이 cancel과 다른 412 정책을 사용 | 기존 proposal 결정이 자동 재시도되거나 결정적 key로 잘못 replay됨 | request/accept/decline/cancel 공통 single-flight refresh·명시 재확인·새 key 정책, timeout/503만 key 재사용 | 모든 mutation의 412 browser 흐름 재실행 |
| 생성 충돌에서 appointment 참조가 없어 중복 요청 발생 | `appointment-requests`의 412/409 뒤 새 key가 자동 발급되거나 복구 대상이 없음 | 생성은 same-key proposal replay 또는 안정적 appointment/commitment 참조+ETag가 있는 응답만 복구 가능; 새 key는 새 사용자 intent에서만 발급 | 첫 생성/same-key replay/conflict browser contract 재실행 |
| fixture-only browser evidence | 화면은 통과하지만 실제 backend 권한/ETag/outbox가 검증되지 않음 | 보호된 backend harness를 기본으로 하고 backend 응답·상태·outbox와 trace를 같은 artifact에 보존 | fixture-only 결과는 PR DoD에서 제외 |
| 취소 hot path 지연과 지표 오염 | cancel p95/p99가 기준선보다 상승하거나 proposal latency가 함께 상승 | cancel 전용 timer, result/replay 저카디널리티 tag, PostgreSQL 고정 dataset/동시성·lock-wait 게이트 | p95 10% 또는 p99 15% 회귀 시 producer/feature rollout 중지; metric/benchmark lane 재실행 |
| mixed-schema notification backlog가 느려짐 | v1/v2 decode failure, drain-time 또는 p99 상승 | schema discriminator 단일 분기, 예외 fallback 금지, 혼합 fixture scale test | v2 producer 중지 후 dual-read drain; scale harness 재실행 |
| detail이 민감정보 저장소로 확산 | DB/outbox/DLQ/backup/provider log에 PHI/PII 또는 raw text가 남음 | 공통 registry의 서버 소유 고정 안내문 exact allow-list, API/event negative test, durable object/provider request redaction, appointment lifecycle 삭제와 notification terminal retention을 적용; production ACL·backup·provider 설정은 활성화 전 확인 | detail producer 중지 후 code-only mode; privacy/security lane 재실행 |
| 성능 gate가 합성 또는 단일 실행에 머묾 | H2/atOnceUsers/합성 queue 비용만 통과하거나 baseline artifact가 없음 | PostgreSQL Testcontainers cancel simulation과 실제 codec backlog benchmark를 동일 환경 3회 실행하고 report comparator/CI exit code를 강제 | benchmark artifact/comparator가 없으면 PR 준비 `PENDING`; 절대·상대 latency/error/retry/lock threshold 초과 시 merge 중단 |
| 의도된 conflict를 오류로 집계 | load mix의 expected `412`/retry exhaustion 때문에 error threshold가 필연적으로 초과 | scenario success와 unexpected 5xx/timeout·비의도 exhaustion의 분모와 report field를 분리 | benchmark report schema/비교기에서 expected outcome을 제외하지 않으면 실행 중단 |
| canonical hash 규약이 구현마다 달라 replay 충돌/우회 발생 | delimiter join, Unicode normalization, null 처리의 구현 차이 | `appointment-core`의 `CancellationReasonRegistry`가 제공하는 `cancel-v1` length-prefixed UTF-8 codec만 API/event에서 사용하고 replay 테스트로 고정 | codec test가 실패하면 idempotency rollout 중단 |
| 미등록 cancellation reason code가 durable event로 유입 | regex만 통과한 신규 code가 DTO·event·frontend에서 서로 다르게 해석됨 | `appointment-core` 폐쇄 registry를 DTO·command·event codec·OpenAPI·frontend catalog의 단일 source로 사용 | registry contract test가 없으면 producer 활성화 PENDING |
| notification property가 실제 worker gate와 분리됨 | flag가 설정돼도 auto-configuration이 binding/health/worker에 연결되지 않거나 invalid config가 기동 후 발견됨 | `NotificationAutoConfiguration` context test로 `clinic.notification.v2-producer` binding, default-off, fail-fast, readiness gate를 검증 | wiring evidence 없이는 v2 producer 활성화 금지 |
| API writer와 notification worker가 서로 다른 rollout flag를 사용함 | `ServiceConfig.appointmentNotificationWriter`가 readiness와 무관하게 v2 envelope를 생성하거나 worker가 v1-only로 기동함 | ServiceConfig writer, NotificationAutoConfiguration worker/readiness/metrics/alert가 같은 properties/gate를 주입받는 context contract test | flag/readiness bean wiring 증거가 없으면 producer 전환 PENDING |

## 중단 기준

- P0 또는 P1 보안/데이터 일관성/notification compatibility가 하나라도 남으면
  구현을 PR 단계로 진행하지 않는다.
- 테스트가 실패하면 재시도만 하지 않고 원인 로그와 diff를 확인한 뒤 해당
  task의 RED 단계부터 다시 실행한다.
- production canary/SLO는 이 작업에서 실행하지 않으며, live evidence가 없으면
  `PENDING`으로 보고한다.
- H2 단독 성능 결과는 merge 근거로 인정하지 않으며, PostgreSQL 측정이 없으면
  성능 게이트는 `PENDING`으로 남긴다.
- 중단 기준은 모든 review lane의 P0/P1 0으로 통일한다. schema backlog 0,
  template readiness, reminder lease 경합, PostgreSQL 성능 evidence가 없으면
  PR 준비 상태로 올리지 않는다.
- activation과 rollback의 조건을 혼동하지 않는다. rollback은 v2 producer flag off,
  v2 active backlog와 `EXHAUSTED` 0 또는 승인된 reconciliation, dual-reader 보존을
  확인해야 하며, outbox JSON query의 실행·예상 출력·timeout 증거가 없으면
  `PENDING`이다.

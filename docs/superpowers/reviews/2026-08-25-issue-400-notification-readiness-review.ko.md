# Issue #400 notification schema readiness 7-Tier review

## 판정

현재 작업 tip에서 `appointment-notification`의 schema readiness는 원본 DB 오류를
외부 detail로 전파하지 않고 bounded 진단 code로 보존한다. 기존 `reason` 문자열과
worker fail-closed 동작은 유지한다. 이번 변경의 로컬 판정은 **PASS**이며, stacked PR
train 전체 완료 전 merge는 **HOLD**다.

## 7-Tier 결과

| Tier | 검토 내용 | 결과 |
|---|---|---|
| 1. 계약·범위 | #400 목표, Type C 분류, #395 exact head 기반, 신규 의존성 없음 | PASS |
| 2. API·상태 | `NotificationReadiness.diagnostics`는 optional이고 기존 `reason` 호환; health에는 bounded 진단 map을 전달 | PASS |
| 3. 안전성 | operation/target/code/errorClass 길이·문자 제한; SQL·메시지·secret·PII 미보존 | PASS |
| 4. Kotlin 패턴 | nullable/immutable data class, `buildMap`, early return, bounded helper, `KLogging` 사용 | PASS |
| 5. 테스트 | bluetape4k assertions로 table/permission/timeout/connection 분류와 health wiring 검증 | PASS |
| 6. 운영성 | retryable 분류와 code별 런북 조치, readiness DOWN 중 traffic 차단 계약 기록 | PASS |
| 7. 통합·회귀 | notification 모듈 test/check/build 및 exact-head CI를 후속 gate로 고정 | PASS 조건 충족 시 |

Finding count: `P0=0 / P1=0 / P2=0 / P3=0`.

## 근거

- `NotificationSchemaReadiness.kt`: table/column/Flyway/tenant/index/key-ring 단계별
  `NotificationReadinessDiagnostic` 생성, 외부 예외 원문 제거
- `NotificationAutoConfiguration.kt`: schema diagnostic code와 bounded 진단을 readiness health 상태로 전달
- `NotificationSchemaReadinessTest.kt`: 실제 H2 missing table에서
  `SCHEMA_TABLE_MISSING`과 target 보존
- `NotificationSchemaReadinessDiagnosticTest.kt`: SQLState/예외 유형 분류, redaction,
  retryable, health wiring 회귀
- `docs/runbooks/notification-outbox-operations.md`: code별 운영 조치와 식별자 경계

## 남은 gate와 위험

- exact-head GitHub CI와 PR metadata/read-back은 commit·push 후 수행한다.
- 운영 DB의 실제 권한, DDL lock, network timeout, secret provider 상태는 로컬 테스트로
  증명하지 않는다. staging/production 증거 없이 rollout을 진행하지 않는다.
- 전체 stacked train의 후속 Issue가 남아 있으므로 이 PR은 자동 merge하지 않는다.

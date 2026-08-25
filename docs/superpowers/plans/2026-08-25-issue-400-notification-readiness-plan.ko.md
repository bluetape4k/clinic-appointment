# Issue #400 notification schema readiness 진단 계획

## 목표

`appointment-notification`의 schema readiness가 DB 장애를 단순한 `DOWN` 문자열로
잃지 않도록 operation, target, stable code, 예외 종류, 재시도 가능성만 bounded하게
보존한다. SQL, 예외 메시지, secret, 개인정보는 health detail·로그·문서에 노출하지
않는다.

## 범위와 분류

| 항목 | 결정 |
|---|---|
| Issue | #400 |
| 모듈 | `:appointment-notification` |
| workflow | Type C 안정성·운영 진단 개선 |
| stacked base | #395 / PR #416의 exact head `ee24b76ac838a47f8ad2703eda86ff9205a9a4d3` |
| 재사용 | 기존 `NotificationSchemaReadiness`, `NotificationReadiness`, `NotificationOutboxHealthIndicator`, bluetape4k assertions |
| 의존성 | 신규 의존성 없음; Java SQLState와 기존 bluetape4k logging만 사용 |
| 외부 side effect | merge·release·production rollout 없음 |

## 구현 경계

1. 필수 table/column/Flyway/index/tenant preflight/key-ring 검사마다 bounded 진단을
   만들고 기존 `reason` 계약은 호환한다.
2. SQLState `42`, `28`, `08`, `HYT00`과 JDBC 예외 유형을 table/column 누락, 권한,
   연결, timeout으로 분류한다. 그 밖의 실패는 `SCHEMA_METADATA_UNAVAILABLE`로
   fail closed한다.
3. Auto-Configuration은 schema/producer readiness를 한 번만 읽어 안정적인 code를
   `NotificationOutboxReadinessSnapshot`에 전달한다.
4. 운영 런북에 code별 조치와 개인정보 경계를 기록한다.
5. 테스트는 `bluetape4k-assertions`를 사용해 code, retryable, redaction, health wiring을
   고정한다.

## 검증 계획

- 대상 테스트: `NotificationSchemaReadinessTest` 및 진단·Auto-Configuration wiring 회귀
- 모듈 게이트: `:appointment-notification:test`, `:appointment-notification:check`,
  `:appointment-notification:build`
- 7-Tier review: P0/P1/P2/P3 finding을 각각 기록하고 현재 tip에서 재검증
- 문서: `bluetape-writer` 한국어 용어 audit 및 `git diff --check`
- PR: exact head 수동 CI, PR body/read-back, unresolved review thread 0, merge hold

## 완료 조건

로컬 테스트·정적 검토·exact-head CI가 모두 통과하고, Issue #400과 PR에 재현 명령,
redaction 경계, known gap, stacked train 선행/후행 관계를 한국어로 기록한다. 전체
train(#392~#402)이 끝날 때까지 PR은 merge하지 않는다.

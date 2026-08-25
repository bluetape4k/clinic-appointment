# Issue #400 schema readiness 진단 lesson

## 문제

notification readiness가 예외 메시지를 그대로 reason/log에 넣으면 DB table·column
권한, timeout, 연결 실패를 운영자가 구분하기 어렵고 SQL·secret·내부 식별자가 health
detail로 새어 나갈 위험이 있다.

## 결정

기존 readiness reason은 하위 호환을 위해 유지하고, 별도 `NotificationReadinessDiagnostic`
으로 operation, 논리 target, stable code, 안전한 error class, retryable만 보존한다.
JDBC SQLState와 예외 유형을 우선 분류하고 미분류 오류는
`SCHEMA_METADATA_UNAVAILABLE`로 fail closed한다. Auto-Configuration은 이 code를
outbox readiness health 상태와 bounded diagnostics detail에 전달해 운영자가 원인을 확인할 수 있게 했다.

## 검증

H2 missing table 통합 회귀와 SQLState `42`, permission `28`, connection `08`, timeout
`HYT00` 단위 계약, redaction, health wiring을 bluetape4k assertions로 검증했다. 운영
런북에는 code별 첫 조치와 `retryable` 해석을 기록했으며, raw SQL·예외 메시지·secret·PII는
테스트와 문서에 포함하지 않았다.

## 다음 작업자에게

1. 새로운 diagnostic code를 추가할 때 대문자 stable code, bounded target, retry 정책과
   런북 표를 함께 갱신한다.
2. health endpoint에 raw `reason`이나 exception message를 다시 연결하지 않는다.
3. `retryable=true`는 무제한 재시작 허가가 아니며, DB lock·pool·network 원인을 확인한
   뒤 readiness 회복을 관측한다.
4. 실제 staging/production 장애 자료를 저장할 때도 tenant·clinic·member·appointment
   식별자와 credential을 제거한다.

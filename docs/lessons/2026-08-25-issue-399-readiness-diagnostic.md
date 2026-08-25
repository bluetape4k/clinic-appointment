# Issue #399 readiness 진단 lesson

## 상황

`appointment-messaging` readiness가 serializer·schema·Schema Registry 실패를
boolean으로만 축약해 운영자가 schema 부재와 DB 권한·timeout·driver 장애를
구분할 수 없었다.

## 결정

- 기존 `AppointmentMessagingReadinessProbe`와 `AppointmentMessagingHealthIndicator`
  경계를 유지하고 `AppointmentReadinessDiagnostic`만 readiness 상태에 추가했다.
- JDBC 표준 예외와 SQLState를 이용해 schema missing, permission denied, timeout,
  driver failure를 stable code와 retryability로 분류했다.
- health/startup에는 operation, bounded target, code, sanitized error class,
  retryable만 전달하고 raw exception message·JDBC URL·credential·PII는 제거했다.
- 테스트는 `io.bluetape4k.assertions`를 사용하고, startup 로그는 기존
  `io.bluetape4k.logging`을 재사용했다. 새 dependency나 ad-hoc abstraction은
  추가하지 않았다.

## 결과와 검증

- readiness validator targeted test `14 passing`
- properties/auto-configuration targeted test `22 passing`
- `:appointment-messaging:test` `134 passing`
- `:appointment-messaging:check` `BUILD SUCCESSFUL`
- permission/timeout/driver의 원인 분류와 secret-like message 비노출을 테스트로 고정했다.

## 다음 guard

readiness 원인을 추가할 때는 먼저 stable operation·code·retryability를 정하고,
원본 예외 메시지를 health/log에 그대로 연결하지 않는다. metadata 호환성
fallback은 정상적인 empty metadata와 모든 후보 호출 실패를 구분해야 하며,
진단 목록은 bounded 상태로 유지한다.

## 문서 작성 점검

- [x] SPW-01: 상황·결정·결과·다음 guard를 source와 테스트에서 고정했다.
- [x] SPW-02: 재사용 판단과 검증 결과를 포함했다.
- [x] SPW-03: 한국어 기술 문체와 code token을 보존했다.
- [x] SPW-04: validator·probe·health·startup·test를 read-back했다.
- [x] SPW-05: 최종 lesson을 다시 읽고 reusable guard를 확인했다.

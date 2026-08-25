# Issue #399 readiness 진단 7-Tier 검토

검토일: 2026-08-25  
검토 branch: `fix/issue-399-readiness-cause`  
선행 child base: `22feb7d9ae8ecf77e962ca99acf6c706652028f1` (`#402`)  
구현 source tip: `f01f8d6a`

## 검토 범위

- `AppointmentMessagingReadinessValidator`의 serializer·JDBC metadata·Schema Registry 진단
- `AppointmentMessagingReadinessProbe` 상태와 `AppointmentMessagingHealthIndicator` detail
- startup warning과 `appointment-messaging` README/runbook 운영 계약
- schema missing, permission denied, timeout, driver failure 회귀 테스트
- 제외: outbox claim/transaction, Kafka wire adapter, notification scheduler, raw exception
  message·JDBC URL·credential·tenant/clinic/appointment 값의 노출

## 재사용 판단

| 후보 | 판정 | 근거 |
|---|---|---|
| `AppointmentMessagingReadinessProbe`/`HealthIndicator` | 채택 | 기존 readiness·health 경계를 유지하고 상태에 diagnostic만 추가했다. |
| JDK `SQLException` 분류 | 채택 | `SQLTimeoutException`, `SQLInvalidAuthorizationSpecException`, `SQLNonTransientConnectionException`과 SQLState `HYT00`/`28`/`08`을 재사용했다. |
| `io.bluetape4k.assertions` | 채택 | 신규 회귀 테스트가 `assertFailsWith`, `shouldBeEqualTo`, `shouldBeTrue/False`, `shouldNotBeNull`을 사용한다. |
| `io.bluetape4k.logging` | 채택 | startup의 안전한 diagnostic summary에 기존 `KLogging`/`warn`을 사용한다. |
| 원본 JDBC 예외 메시지 | 제외 | 비밀·PII·연결 문자열이 운영 응답과 로그 경계를 넘을 수 있어 error class만 bounded하게 보존한다. |

## 모듈별 7-Tier 판정

| Tier | 판정 | 현재 근거 |
|---|---|---|
| 성능 | PASS | readiness 검사 횟수와 metadata fallback은 유지하고, diagnostic은 최대 8개로 제한했다. 별도 latency 수치는 주장하지 않는다. |
| 안정성/수명주기 | PASS | schema·serializer·registry 중 하나라도 실패하면 기존 fail-closed와 `checked` 재시도 계약을 유지한다. 이전 진단은 다음 검사에서 교체한다. |
| 보안/데이터 경계 | PASS | operation/target/stable code/errorClass/retryable만 노출하고 raw message·JDBC URL·credential·식별자 값을 저장하지 않는다. |
| 운영/관측성 | PASS | health와 startup log가 동일한 bounded diagnostic을 소비하며 timeout/permission/driver와 missing contract를 구분한다. |
| 개발자/API | PASS | 기존 probe/상태 생성 호출을 깨지 않고 `AppointmentReadinessDiagnostic`과 health detail만 확장했다. |
| 사용자/호출자 | PASS | relay가 바라보는 boolean readiness와 startup의 fail-closed 예외 메시지는 유지되고, 운영자는 재시도 경계를 확인할 수 있다. |
| 통합/테스트/빌드 | PASS | readiness 14개, properties/auto-configuration 22개, 전체 messaging 134개와 module `check`가 통과했다. |

판정: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## Kotlin checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| KT-01 | PASS | `bluetape-kotlin-patterns`의 null-safety·bounded value·testing 규칙과 Kotlin/Spring reference를 적용했다. |
| KT-02 | PASS | 기존 validator/probe/health/startup caller, JDBC fallback, test launcher와 bluetape4k assertions/logging 사용처를 source에서 확인했다. |
| KT-03 | PASS | 새 `!!`, raw `runCatching` 축약, 원본 예외 메시지 노출, ad-hoc assertion, 새 dependency가 없다. |
| KT-04 | PASS | RED 후 targeted GREEN, full test/check, `git diff --check`, 문서 read-back을 수행했다. |
| KT-05 | PASS | 적용 가능한 Kotlin rows `11/11`, testing rows `5/5`; auto-configuration 구조를 바꾸지 않아 별도 auto-configuration 설계 행은 `N/A`다. |

## Fresh verification

| 명령 | 결과 |
|---|---|
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test --tests '*AppointmentMessagingReadinessValidatorTest'` | `BUILD SUCCESSFUL`, `14 passing` |
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test --tests '*AppointmentMessagingPropertiesTest' --tests '*AppointmentMessagingAutoConfigurationTest'` | `BUILD SUCCESSFUL`, `22 passing` |
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test` | `BUILD SUCCESSFUL`, `134 passing` |
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:check` | `BUILD SUCCESSFUL`, Kover verify 포함 |
| `git diff --check` | `PASS` |
| `rg 'UUID\\.randomUUID|assertThrows|kotlin\\.test\\.assertFailsWith|runCatching|!!'` 대상 변경 Kotlin | 결과 없음 |
| README 공통 진단 문단 parity | `README.md`와 `README.ko.md` 해당 문단 diff 없음 |

`detekt`는 이 모듈에 task가 등록되어 있지 않아 실행할 수 없었다. module의
authoritative static/build gate인 `:appointment-messaging:check`를 사용했고, task
부재를 코드 실패로 해석하지 않았다.

## Finding disposition

- P0/P1: 없음.
- P2: 별도 readiness latency benchmark는 이 child 범위에 없으므로 수치를 주장하지 않는다.
- P3: 없음.
- 기존 relay·Kafka·outbox 동작은 boolean readiness 경계를 제외하고 변경하지 않았으며,
  전체 module test로 회귀 여부를 확인했다.

## PR 전 결론

`PASS` — #399 구현·문서·7-Tier 검토 gate를 통과했다. branch는 #402 exact head
위에 1개 implementation commit을 포함하며, PR 생성·exact-head CI·merge는
delivery 단계에서 수행한다. 전체 stacked train의 merge approval은 남은 child가
완료될 때까지 요청하지 않는다.

## 문서 작성 점검

- [x] SPW-01: 범위, source tip, 선행 base, 제외 항목과 증거를 고정했다.
- [x] SPW-02: 7-Tier 판정, Kotlin checklist, findings, gaps와 결론을 포함했다.
- [x] SPW-03: 한국어 기술 문체와 정확한 API·명령·식별자를 유지했다.
- [x] SPW-04: source·test·README·runbook·실행 결과를 대조했다.
- [x] SPW-05: 최종 Markdown read-back과 P0/P1 count를 확인했다.

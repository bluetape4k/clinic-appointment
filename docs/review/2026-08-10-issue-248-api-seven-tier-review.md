# 이슈 #248 `appointment-api` 7-tier 검토 결과

## 범위와 기준

- 기준 커밋: `ea131f7fe11e1775d161469e152e57a87574e6b2`
- 대상 모듈: `appointment-api` production/test Kotlin 소스
- 적용 기준: `$bluetape-kotlin-patterns`, 저장소의 Exposed fixture 규칙, 7-tier 검토
- 작업 브랜치: `codex/issue-248-api`

## 검토 결과

| Tier | 판정 | 근거 및 조치 |
|---|---|---|
| 성능 | 특이사항 없음 | 조회 트랜잭션을 직접 호출하도록 바꿨지만 쿼리 수·범위는 변경하지 않았다. |
| 안정성 | 개선 | 네 조회 컨트롤러가 SQL/connection 예외를 404로 삼키지 않고 전역 5xx 경계로 전달한다. 세 production `!!`를 진단 가능한 `checkNotNull`로 교체했다. |
| 보안 | 특이사항 없음 | tenant 검증과 권한 경계는 유지했으며, 조회 결과가 없는 경우의 응답만 404로 고정했다. |
| 운영 | 개선 | DB 장애와 리소스 부재를 구분하여 알람·재시도 가능한 실패 원인을 보존한다. |
| 개발자/API | 부분 개선 | nullable ID 오류 메시지와 정상 null→404 계약을 테스트로 고정했다. 전체 `data class` 직렬화 계약은 별도 P2로 남겼다. |
| 사용자/호출자 | 개선 | 일반 NPE 대신 `AppointmentRecord.id`/`RescheduleCandidateRecord.id` invariant가 드러난다. |
| 통합/테스트 | 부분 개선 | production/test 소스 재귀 compliance guard, bluetape assertion guard, migration-safe schema creation guard를 추가했다. 의도적인 schema drop 시뮬레이션과 타 모듈 검사는 별도 범위다. |

## 변경 내용

- `EquipmentController`, `TreatmentTypeController`, `DoctorController`, `ClinicController`에서 `runCatching { transaction { ... } }.getOrNull()` 제거
- `AppointmentResponse`, `RescheduleCandidateResponse`, 장비 비가용 충돌 응답 mapper의 nullable ID를 명시적 `checkNotNull`로 변경
- 신규 `ResourceLookupFailureControllerTest`에서 네 DB 장애 전파와 네 정상 미존재 404, 두 nullable ID 오류 계약 검증
- `KotlinProductionPatternComplianceTest`가 API production/test 전체를 재귀 검사
- `NearCacheAdapterTest`의 JUnit `assertThrows`를 bluetape assertion으로 변경
- `AppointmentStatsProjectionConsumerTest` 및 API test fixture의 일반 schema creation을 `createMissingTablesAndColumns`로 변경하고 projection fixture를 정리

## 검증 증거

1. baseline compliance: 4 tests passed. 기존 하드코딩 목록이 production의 세 `!!`를 놓치는 상태를 확인했다.
2. RED: 신규 회귀 6 tests가 기존 구현에서 `SQLException` 미전파 4건과 `NullPointerException` 2건으로 실패했다.
3. targeted GREEN: `ResourceLookupFailureControllerTest` + compliance 15 tests passed.
4. final compliance: 7 tests passed.
5. affected module tests: controller·fixture·compliance 대상 225 tests 중 구현/fixture 관련 테스트는 통과했다. 첫 실행의 compliance self-scan false positive를 제외한 뒤 guard를 수정하고 7 tests를 재실행해 통과했다.
6. container-backed controller tests: Colima 환경에서 `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/Users/debop/.colima/default/docker.sock`를 사용해 30 tests passed.
7. `git diff --check` 및 production `rg -n '!!' appointment-api/src/main/kotlin` 모두 clean.

## 잔여 P2 및 제한

- `appointment-api/src/main`의 모든 `data class`에 `Serializable`/`serialVersionUID`를 일괄 추가하는 것은 Spring configuration·worker 내부 상태까지 의미를 바꾸는 넓은 변경이다. 현재 변경에서는 계약상 API 응답과 이번 nullable ID 경계만 고정했으며, 전체 직렬화 목록은 별도 이슈로 분리해야 한다.
- `ProfileReevaluationFailureIntegrationTest`의 `SchemaUtils.drop`은 의도적으로 schema 장애를 재현하는 테스트 setup이므로 일반 fixture guard의 예외로 남겼다.
- 전체 `./gradlew :appointment-api:test`는 Testcontainers Redis 종료 시 near-cache `CLIENT TRACKING OFF` 재연결/1분 timeout으로 300초 도구 제한을 넘겼다. 원격 CI와 production 검증은 실행하지 않았다.

## 상태

P1 안정성·nullable ID·compliance 회귀는 완료했다. 이 문서의 잔여 P2와 전체 모듈 테스트 종료 경계는 이슈 #248에 계속 추적한다.

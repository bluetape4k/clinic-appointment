# Issue #184 Task 7 Step 6-R 코드 리뷰

## 검토 범위

- Gateway 인증 envelope에서 고객·관리자 actor와 tenant·clinic scope를 만드는 경계
- 고객 가예약, 관리자 직접 생성·승인·확정·변경제안, 고객 수락·거부, commitment 조회 API
- `Idempotency-Key`, `If-None-Match`, strong `If-Match`/`ETag` 계약
- 서버측 정책·환자·Plan·자원·동의 증빙 resolver와 request DTO의 권한 분리
- stable error registry, privacy-safe 오류 응답, OpenAPI status·schema
- legacy 조회·변경·휴진 재예약·장비 장애 경로의 commitment v2 보호
- 대형 패키지 item 저장 round-trip과 H2·PostgreSQL·MySQL 회귀
- 기준: `bluetape-kotlin-patterns`, Exposed caller-owned transaction,
  Spring MVC thin controller, Gateway trust boundary, 한국어 KDoc

## 최종 판정

| Tier | 관점 | P0 | P1 | P2 | P3 | 판정 |
|---|---|---:|---:|---:|---:|---|
| 1 | 본 세션 통합·업무규칙 | 0 | 0 | 0 | 0 | PASS |
| 2 | 사용자·호출자 | 0 | 0 | 0 | 0 | PASS |
| 3 | 개발자·API | 0 | 0 | 0 | 0 | PASS |
| 4 | 성능 | 0 | 0 | 0 | 0 | PASS |
| 5 | 안정성·동시성 | 0 | 0 | 0 | 0 | PASS |
| 6 | 보안 | 0 | 0 | 0 | 0 | PASS |
| 7 | 운영·SRE | 0 | 0 | 0 | 0 | PASS |

Task 7 최종 차단 집계는 `P0=0`, `P1=0`이다. 최초 검토에서 발견한 성능
P2와 개발자·API P3도 이번 Task 범위에서 닫았으므로 이관된 미해결 finding은 없다.

## 업무 경계 판정

### Gateway가 신뢰할 수 있는 유일한 actor 원천이다

고객과 관리자는 별도 controller를 사용하지만 둘 다
`ActorContextResolver.resolveAppointmentActor()`가 만든 context만 application
service로 전달한다. request body에는 actor, tenant, clinic, patient, 정책 mode,
허용 동의 유형, 약관 hash, 담당자 또는 자원 권한을 넣지 않는다.

환자 조회는 Gateway subject를 tenant namespace의 fingerprint로 변환해 Plan과
appointment 소유권을 확인한다. 관리자 요청도 Gateway가 허용한 단일 clinic scope를
벗어날 수 없다. audit와 consent에는 token이나 개인정보 원문 대신 길이와 문자가
제한된 opaque reference만 저장한다.

### 가예약과 확정은 서로 다른 권한·동의 계약이다

- 고객 생성은 `202 PROPOSED`이며 병원 확정 전 자원 점유를 확정하지 않는다.
- 관리자 직접 생성은 유효 정책이 직접 확정을 허용할 때만 `201 CONFIRMED`가 된다.
- 관리자 승인과 고객 수락은 exact proposal, 최신 version, 필요한 동의 증빙을
  다시 검증한다.
- 변경 proposal 대기·거부·만료 중에는 기존 확정 예약과 자원 점유를 보존한다.
- feature rollback은 신규 ingress만 막으며 이미 존재하는 commitment의 종결
  mutation은 계속 허용한다.

### HTTP precondition은 body와 분리한다

생성은 `Idempotency-Key`와 `If-None-Match: *`, 변경은 `Idempotency-Key`와
strong `If-Match`를 요구한다. 누락은 `428`, 현재 version 불일치는 `412`로
정규화한다. mutation 응답은 현재 version을 strong `ETag`로 반환한다.
OpenAPI도 모든 필수 header를 `required=true`로 게시한다.

## 리뷰 중 발견하고 닫은 결함

### 대형 패키지 item의 선형 DB round-trip

최초 성능 검토에서는 proposal item마다 Plan revision treatment SELECT와 INSERT를
수행해 100개 이상 세부 진료를 가진 패키지에서 수백 번의 DB round-trip이 발생하는
P2를 발견했다.

수정 후 저장 순서는 다음과 같다.

```text
proposal scope SELECT 1회
→ (planRevisionId, treatmentKey) composite scoped SELECT 1회
→ 모든 immutable snapshot 대조
→ AppointmentItems batch INSERT 1회
```

100개 item 회귀 테스트가 treatment scope SELECT 1회와 item INSERT 1회를
statement interceptor로 검증한다. 검증 하나라도 실패하면 batch insert 전에
예외가 발생하고 caller transaction 전체가 rollback된다.

### 불완전한 OpenAPI 오류 계약

최초 개발자·API 검토에서는 일부 수락·거부·승인·확정·변경제안·조회 endpoint가
성공 응답만 추론에 맡기고 stable error response를 명시하지 않은 P3를 발견했다.

모든 v2 commitment operation에 업무상 가능한 오류 status와
`SchedulingApiErrorResponse` schema를 명시했다. 통합 테스트는 실제
`/v3/api-docs` JSON에서 각 operation의 성공·오류 status, 필수 header,
공통 오류 schema의 `errorCode`, `correlationId`, `retryable`, `action`을 검증한다.

### legacy 경로의 v2 row 오염

legacy 단건·기간 조회와 update는 `modelVersion == LEGACY` 및 완전한 projection을
요구한다. legacy status mutation은 commitment v2 row에서
`NEW_APPOINTMENT_API_REQUIRED`를 반환한다. 휴진에 따른 closure reschedule과
장비 장애 영향 조회도 v2 row를 legacy 예약으로 재예약하지 않는다.

### H2 test engine 초기화 순서

core repository와 closure test를 함께 실행할 때 H2 engine이 JVM 기본 timezone을
먼저 고정하면 `LocalDate`가 왕복 과정에서 이동할 수 있었다. 공용 commitment test
support가 schema 생성 전에 UTC를 설정하도록 보정했다. 이는 production schema
변경이 아니라 H2 테스트의 순서 독립성 복구다.

## 오류와 운영 계약

- scope·actor 오류는 `401/403/404`로 정보 노출을 제한한다.
- proposal·consent 만료는 `410`, current/version 경합은 `409/412`로 분리한다.
- 계산 상한과 feasible slot 부재는 각각 안정적인 `422/409` 오류로 변환한다.
- 예상하지 못한 오류는 parser·SQL·token·개인정보를 노출하지 않고 correlation ID와
  제한된 재시도 안내만 반환한다.
- `api-enabled=false`에서는 handler와 OpenAPI path가 함께 사라진다.
- `ingress-enabled=false`에서는 신규 고객 요청과 관리자 직접 생성만 차단하고,
  기존 예약의 승인·수락·거부·변경제안은 유지한다.

## 후속 Task 경계

### Task 8 — 외부 완료·부분 이행·환불 사실

예약 서비스는 치료 완료나 환불을 스스로 추론하지 않는다. 상품·구매·시술·환불
서비스에서 받은 event를 검증하고, 완료 항목은 보존하면서 미래 항목만 새 Plan
revision과 proposal로 조정한다. `PREDECESSOR_NOT_COMPLETED`의 실제 완료 사실
연결도 Task 8에서 수행한다.

### Task 9 — production wiring과 관측성

현재 API는 feature flag와 테스트 application service 경계를 제공한다. 실제
policy·inventory·patient resolver bean, tenant별 rollout, metric·alert,
Gateway 운영 연동은 Task 9 범위다.

### Task 10 — 장기 성능 회귀

Task 7은 100개 item의 statement-count를 고정했다. 실제 PostgreSQL 데이터 규모의
1/10/100/500 item latency, idempotency replay, allocation contention과 장기
성능 matrix는 Task 10에서 검증한다.

## 검증 증거

- 고객·관리자·보안·오류·legacy API 대상 테스트: 95개, 실패 0
- `AppointmentCommitmentExceptionResolutionTest`: 5개, 실패 0
- commitment repository·closure 표적 테스트: 34개, 실패 0
- `AppointmentItemRepositoryTest`: 4개, 실패 0
- `AppointmentCommitmentSecurityIntegrationTest`: 5개, 실패 0
- `:appointment-core:build --no-build-cache --rerun-tasks`: 451개 통과,
  실패 0, 오류 0, skipped 0, Kover verify 통과
- `:appointment-api:build --no-build-cache --rerun-tasks`: 전체 358개 중
  356개 통과, 기존 skipped 2, 실패 0, 오류 0, Kover verify 통과
- `git diff --check`: 위반 0
- 신규 production 금지 패턴 `!!`, `println`, broad `runCatching`,
  `synchronized`, `GlobalScope`, `Thread.sleep`: 추가 0

모듈별 `detekt` task는 존재하지 않고 root `detekt`는 `NO-SOURCE`이므로 정적 분석
통과로 과장하지 않는다. Kotlin compiler와 전체 module build, 금지 패턴 diff scan,
독립 7-Tier 검토를 이번 Task의 코드 품질 증거로 사용했다. 기존
`PackageExecutionSnapshot`의 Kotlin 2.5 copy visibility 경고는 Task 7에서
추가된 경고가 아니며 별도 기술부채로 남아 있다.

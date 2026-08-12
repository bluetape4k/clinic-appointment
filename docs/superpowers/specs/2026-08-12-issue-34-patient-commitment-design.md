# Issue #34 — 환자 예약 약속 포털과 취소 알림 설계

## 결정 상태

사용자 결정에 따라 #34에는 환자 포털의 예약 생성·조회·동의·취소와
관리자/직원 취소 설명의 감사·알림 전달을 포함한다. 환자가 자신의 취소
이력을 포털에서 다시 조회하는 읽기 API와 이력 UI는 #305로 분리한다.

## 목표와 범위

환자가 tenant·clinic 범위 안에서 예약 약속의 현재 상태를 안전하게 확인하고,
현재 proposal을 동의·거절하거나 아직 유효한 약속을 취소할 수 있게 한다.
상태는 Codex visualize에서 선택한 A안인 단계형 stepper로 표시하며, 상품명이
있으면 제목으로, 회차가 있으면 `3회차 / 10회` 형식의 메타로 표시한다.

이번 변경은 다음을 함께 제공한다.

- patient actor가 `PROPOSED`, `HELD`, `CONFIRMED` 약속을 취소할 수 있는 API
- admin/staff actor가 취소 code와 bounded 환자 안내 문구를 입력하는 API
- 취소 상태 변경, 감사 metadata, scheduling outbox, notification outbox의
  동일 transaction 기록
- 취소 안내 문구를 환자 알림의 typed parameter로 전달하는 notification schema
  v2와 v1 payload dual-read
- 포털의 예약 요청·현재 commitment 조회·proposal accept/decline·취소 화면
- ETag, idempotency, stale 상태, 접근성, 빠른 연속 입력과 모바일 reflow 테스트

다음은 범위에서 제외한다.

- 환자용 raw audit log 조회 또는 취소 이력 목록 API/UI (#305)
- staff/admin 전용 예약 목록·취소 화면
- 환불·결제 처리와 의료·개인정보를 포함한 취소 문구의 의미 해석
- notification provider의 실제 SMS/email 문구 템플릿 운영 배포

## 현재 코드 근거

- commitment 조회 응답은 현재 상태, version, proposal, 정책 snapshot만 반환하고
  감사 이력을 노출하지 않는다.
- 관리자 취소 route는 이미
  `POST /api/{tenantCode}/appointments/{id}/cancel`이며 `reasonCode`만 받는다.
- application/command service에는 patient ownership을 검사하는
  `cancelAppointment` 경로가 있으나 patient controller/security mapping이 없다.
- notification `AppointmentCancelledParameters`와 outbox schema v1은
  `cancellationReasonCode`만 허용하며 자유 문구를 거부한다.

## 외부 API 계약

### 취소

기존 경로를 유지하고 actor role에 따라 권한을 분기한다.

`POST /api/{tenantCode}/appointments/{id}/cancel`

공통 header:

- `Idempotency-Key`: 필수, 재시도 때 동일 key 재사용
- `If-Match`: 필수, 최신 commitment ETag

body:

```json
{
  "reasonCode": "CUSTOMER_REQUEST",
  "reasonDetail": "진료 일정이 변경되어 예약을 취소합니다."
}
```

- `reasonCode`는 기존 uppercase business code allow-list와 동일하게 검증한다.
- `reasonDetail`은 선택적 UTF-8 text이며 최대 500자, blank·ISO control character를
  거부한다. HTML·template delimiter는 renderer에서 escape하며, 의료정보·결제정보·
  환자 식별자를 입력하지 않는 운영 규칙을 문서화한다.
- `ADMIN` 또는 `STAFF`만 `reasonDetail`을 보낼 수 있다. `PATIENT`가 해당 필드를 보내면
  `400`으로 거부하고, patient command에는 detail을 전달하지 않는다.
- `ADMIN`, `STAFF`, `PATIENT` 모두 tenant path, clinic scope, commitment ownership 검사를
  통과해야 한다. 다른 actor type은 `403`이다.
- `PROPOSED`, `HELD`, `CONFIRMED`만 취소할 수 있으며 `EXPIRED`, `CANCELLED`는
  기존 transition error로 거부한다.
- 성공 응답은 기존 `AppointmentCommitmentResponse`와 새 ETag를 반환한다.

### 기존 proposal 결정과 조회

다음 계약은 유지한다.

- `GET /api/{tenantCode}/appointments/{id}/commitment`
- `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/accept`
- `POST /api/{tenantCode}/appointments/{id}/proposals/{proposalId}/decline`
- `POST /api/{tenantCode}/appointment-requests`

appointment ID가 이미 있는 accept/decline/cancel mutation은 최신 ETag와 idempotency
key를 사용하고, `412`이면 appointment별 최신 상태를 single-flight로 다시 읽은 뒤
사용자가 새 intent로 재확인한다. 아직 appointment ID가 없는
`POST /api/{tenantCode}/appointment-requests` 생성은 `If-None-Match: *`와 idempotency
key만 사용한다. 생성의 `412/409`는 같은 key의 최초 proposal 결과를 replay하거나
안정적인 appointment/commitment 참조와 ETag를 반환해야 하며, 참조가 없는 경우
새 key 재시도를 자동 허용하지 않는다. 사용자가 새 예약 요청을 명시적으로 시작할
때만 새 key를 발급한다. 조회 응답에는 #305가 구현되기 전까지 audit 이력이나
`reasonDetail`을 추가하지 않는다.

## 백엔드 경계

### Actor와 command

`AppointmentCommitmentApplicationService.cancelAppointment`는 resolver가
검증한 actor를 command에 전달한다. command context hash에는 registry가 정의한
`cancel-v1` canonical codec으로 `reasonCode`와 관리자 detail을 함께 포함해
같은 idempotency key로 다른 문구를 재사용할 수 없게 한다. codec은
`cancel-v1\\0` prefix 뒤 각 필드를 unsigned 32-bit big-endian UTF-8 byte length와
bytes로 직렬화하며, nullable detail은 `0xffffffff` length로 표현한다. 입력
code point를 그대로 UTF-8로 인코딩하고 Unicode normalization이나 delimiter join을
사용하지 않는다.

reason code와 canonical codec의 단일 source of truth는
`appointment-core/.../commitment/CancellationReasonRegistry.kt`이다. API와
event가 함께 의존하는 core contract로 모듈 순환을 만들지 않으며, 현재 등록 목록은
`CUSTOMER_REQUEST`, `REFUND`, `EQUIPMENT_FAILURE`, `CLINIC_REQUEST`이다.
DTO·command·event codec·OpenAPI enum·frontend catalog는 이 registry를 사용하고
미등록 대문자 code도 거부한다.

controller는 role별 입력 규칙만 담당하고, tenant·clinic·patient 소유권과
상태 전이는 기존 access resolver와 command service가 담당한다. request body는
actor, tenant, clinic, patient subject를 선택하지 못한다.

### 취소 detail 저장

취소 성공 transaction 안에서 다음을 원자적으로 기록한다.

1. commitment 상태와 appointment projection을 `CANCELLED`로 전환
2. 활성 allocation과 reminder를 해제/억제
3. `AppointmentAuditEvents`에 기존 hash metadata 기록
4. `scheduling_appointment_cancellation_details`에 환자 안내 문구 snapshot 기록
5. scheduling outbox와 notification outbox 기록
6. idempotency 결과 snapshot 완료

취소 직후 reminder scanner가 이미 `PENDING`, `RETRY_WAIT`, `PROCESSING`인 row를
발견하는 경우에도 `CANCELLED` 상태와 lease/recovery 경합을 재확인해 발송하지
않아야 한다. cancel transaction, scanner, lease expiry/recovery가 동시에 실행되는
경합을 PostgreSQL 통합 테스트로 검증한다.

새 cancellation detail table은 tenant·clinic·appointment·commitment·proposal,
reason code, nullable bounded detail, actor role, actor scope hash, detail hash,
occurredAt을 보유한다. raw actor ID, token, patient subject, phone/email은
저장하지 않는다. commitment 하나의 terminal cancellation에 하나의 row만
허용한다. detail hash는 audit payload hash와 notification 재현 검증에 사용한다.

기존 `AppointmentAuditEvents.payloadHash`는 원문을 저장하지 않는 원칙을
유지하며 command hash에 detail을 포함한다. 애플리케이션 로그에는 detail을
출력하지 않는다.

### Notification contract v2

`AppointmentCancelledParameters`에 nullable
`cancellationReasonDetail`을 추가한다. 이 값은 같은 500자/control-character
검증을 통과한 환자 안내 문구만 담으며 member profile, recipient name, phone,
email, rendered message는 계속 durable envelope에 넣지 않는다.

- 새 producer는 `NotificationOutboxEnvelope.CURRENT_SCHEMA_VERSION = 2`를
  사용한다.
- decoder는 schema v1과 v2를 모두 읽고, v1 취소 payload의 detail은 `null`로
  해석한다.
- 배포 순서는 consumer/decoder를 먼저 v1/v2 호환으로 올리고 readiness와
  mixed-schema drain을 확인한 뒤, default-off `clinic.notification.v2-producer`
  feature flag를 통해 producer를 v2로 전환한다. 모든 active worker replica가
  동일 build와 codec `{1,2}` readiness를 보고하지 않으면 producer를 활성화하지
  않는다.
- `NotificationAutoConfiguration`은 `@EnableConfigurationProperties`로
  `NotificationProperties`를 실제 worker/health bean에 연결한다. 이 binding은
  `clinic.notification.v2-producer`를 default-off로 두고 invalid config를
  fail-fast하며, codec/template/channel readiness가 모두 준비되지 않으면 v2
  producer worker를 생성하거나 활성화하지 않는다.
- API의 `ServiceConfig.appointmentNotificationWriter`도 같은 properties/readiness
  gate를 주입받아 cancellation만 v2를 선택한다. flag가 꺼져 있거나 readiness가
  준비되지 않으면 writer는 v1로 유지하며, auto-configuration의 worker·health·
  metrics·alert bean과 writer의 선택이 context test에서 같은 경계를 사용해야 한다.
- v2의 취소 template version은 `2`로 올리고, 다른 template은 version `1`을
  유지한다.
- notification renderer/catalog가 cancellation template version `2`와 code/detail
  parameter를 readiness check로 등록한 뒤에만 v2를 처리한다. render 단계는
  code/detail을 text와 HTML 양쪽에서 escape하며, detail이 없으면 기존 code-only
  문구와 동일한 결과를 낸다.
- outbox idempotency digest는 기존 appointment version/slot contract를
  유지한다. 동일 취소 command replay에서는 새 notification row를 만들지
  않는다.

### 권한 matrix

| 경로 | PATIENT | ADMIN/STAFF | 기타 |
|---|---:|---:|---:|
| commitment 조회 | 허용(소유권 필수) | 허용(tenant·clinic 필수) | 거부 |
| 예약 요청 | 허용 | 거부 | 거부 |
| proposal accept/decline | 허용(소유권 필수) | 거부 | 거부 |
| appointment cancel | 허용(code만) | 허용(code + detail) | 거부 |
| admin proposal/confirm/change | 거부 | 허용 | 거부 |

Security matcher, controller actor check, application access resolver가 같은
matrix를 독립적으로 확인한다. role denied 요청은 service DB lookup 전에
차단하며, cross-tenant·cross-clinic·cross-patient 결과는 존재 여부를
구분하지 않는 privacy-safe error로 반환한다.

## 포털 UX

### 상태 stepper

예약 카드와 예약 페이지는 다음 순서를 공유한다. 이번 변경에는 별도 상세 route를
추가하지 않고 카드에서 현재 commitment를 확인한다.

`요청됨 → 제안됨 → 선점됨 → 확정됨 → 취소됨/만료됨`

API에는 `REQUESTED` 상태가 없으므로 요청 전송 중인 local view만 `REQUESTED`로
표현하고, 응답 상태는 `PROPOSED`, `HELD`, `CONFIRMED`, `CANCELLED`, `EXPIRED`를
명시적인 step ID로 매핑한다. `CANCELLED`와 `EXPIRED`는 terminal view이며 proposal
view로 되돌아가지 않는다. 현재 상태는 색상뿐 아니라 텍스트와
`aria-current="step"`로 표현한다.
상품명이 있으면 제목, 대표 진료명은 fallback 제목, 회차가 있으면
`회차 / 전체 회차`, 없으면 회차 메타를 숨긴다. 방문 일시와 clinic 표시명은
proposal snapshot에서 렌더링한다.

### 액션과 오류

- `PROPOSED`: accept, decline, cancel
- `HELD`: cancel
- `CONFIRMED`: cancel
- `EXPIRED`/`CANCELLED`: mutation action hidden, terminal explanation shown
- busy 상태에서는 모든 mutation button을 disabled하고 동일 intent key를
  재사용한다.
- `401/403/409/410/412/422/428/503`은 상태별 한국어 안내와 재시도/새로고침
  행동을 제공한다. request/accept/decline/cancel 모든 mutation에서 `412`는
  같은 intent key로 자동 재전송하지 않고 최신 commitment/ETag만 single-flight로
  읽어 표시한 뒤, 사용자가 명시적으로 다시 확인할 때 새 intent key를 발급한다.
  transport timeout/503에서는 같은 intent key를 유지해 서버 replay를 가능하게 한다.

취소는 확인 dialog에서 code를 선택하고 최종 확인한다. 환자 화면에는
operator detail 입력란을 노출하지 않는다. 취소 완료 뒤에는 terminal step과
알림 발송 예정 안내만 보여 주며, 이력 상세는 #305에서 제공한다.

## 변경 파일 경계

- `appointment-api`: cancel DTO/controller/security/application/command,
  cancellation detail table·migration·repository, API/OpenAPI/security tests
- `appointment-event`: cancelled typed parameter, schema v2 dual-read codec,
  template version/contract tests
- `frontend/appointment-frontend`: portal API model/client/facade, appointment
  page/card/stepper/cancel confirmation, unit/browser tests
- `docs`: 본 설계와 구현 계획, issue/README 연결 문서

legacy appointment cancellation, payment/refund, #305 history endpoint는 이
변경에서 수정하지 않는다.

## 검증 기준

- Kotlin: `./gradlew :appointment-event:test :appointment-api:test`
- frontend: `npm test -- --watch=false`와 `npm run build` (module directory)
- targeted tests: DTO validation, patient/admin cancel role matrix, ownership,
  idempotent replay, ETag conflict, cancellation detail atomicity, Flyway H2/
  PostgreSQL/MySQL contract, notification v1/v2 codec, HTML/text escaping,
  portal facade and browser flow
- 취소 성능 검증은 PostgreSQL warm-up 30초, 측정 5분, 고정 dataset 100개
  appointment, 동일 appointment 경합 10개/서로 다른 appointment 병렬 20개를 사용한다.
  patient/admin 성공, idempotent replay, expected `412` version conflict,
  expected retry exhaustion을 분리해 p95/p99를 기록한다. expected conflict/exhaustion은
  scenario success로 집계하고, unexpected HTTP 5xx/timeout과 비의도 exhaustion만
  error rate/retry exhaustion threshold 분모에 포함한다. 기준선 대비 p95 10% 초과 또는
  p99 15% 초과 또는 절대 p95 500ms·p99 1s 초과, error rate 1% 초과,
  retry exhaustion 0.1% 초과, lock-wait p95 50ms 초과 시 merge를 중단한다.
  pre/post를 동일 machine/container image/dataset/seed로 3회 측정해 median과
  분산을 기록하며 H2 결과만으로 통과시키지 않는다.
- 취소 요청은 proposal latency에 합산하지 않는 전용 end-to-end timer와
  `result`/`replay` 저카디널리티 tag를 사용한다. access resolver 이전/이후 경계를
  문서화하고 lock contention·retry exhaustion을 별도 계측한다.
- v1/v2 notification backlog는 schema discriminator를 한 번 읽어 분기하고,
  legacy-heavy 80/20과 current-heavy 20/80, 각 10,000건, 500자 detail fixture의
  실제 codec decode throughput, p95/p99, decode failure, drain-time을 측정한다.
  synthetic fairness harness와 실제 codec benchmark를 분리하고 동일 절대/상대
  회귀 상한을 적용하며 예외 기반 fallback은 사용하지 않는다.
- notification renderer/catalog의 cancellation template v2 readiness와 consumer-first
  rollout/rollback을 검증한다. v1-only worker가 남아 있거나 template readiness가
  확인되지 않으면 v2 producer 전환은 중단한다.
- 취소와 reminder scanner의 `PENDING`/`RETRY_WAIT`/`PROCESSING`/lease recovery
  경합에서 stale reminder가 발송되지 않는지 PostgreSQL 통합 테스트로 확인한다.
- `412` 자동 재조회는 appointment별 single-flight로 coalesce하고 stale response를
  무시한다. 연속 conflict에서도 GET request-count가 1회를 넘지 않는 browser test를
  추가한다.
- `git diff --check`와 `bluetape-kotlin-patterns` 7-tier review에서 P0/P1 0
- CI와 exact PR head 검증 후 별도 merge approval을 받아야 merge한다.

## 롤백

활성화와 rollback은 별도 checklist로 운영한다. 활성화는 decoder `{1,2}`와
renderer/catalog readiness, 모든 active worker replica 동일 build, v2 producer
flag default-off 상태를 확인한 뒤에만 진행한다. rollback은 v2 producer flag off,
v2 `PENDING`/`PROCESSING`/`RETRY_WAIT` backlog와 `EXHAUSTED`가 모두 0이거나
승인된 reconciliation 완료 상태, dual-reader 보존 기간 경과를 확인한다. outbox
row에는 schema version column이 없으므로 vendor별 JSON extraction 또는 별도
indexed projection query를 사용하며, query·예상 출력·timeout·redrive/suppression
절차를 runbook에 고정한다. V27 table은 삭제하지 않으며 이미 발송된 알림은
회수하지 않는다.

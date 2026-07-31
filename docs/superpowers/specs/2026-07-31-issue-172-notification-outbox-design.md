# 회원 식별자 기반 알림 outbox 설계

> 상태: 대화 설계 승인 및 Type A 6개 관점 검토 완료 (`P0=0`, `P1=0`)
>
> 기준일: 2026-07-31
>
> 관련 이슈:
> [#172 Make notification failure path observable and retryable](https://github.com/bluetape4k/clinic-appointment/issues/172)

## 1. 문제

현재 알림 경로는 Spring `@EventListener`가 예약 event를 받은 뒤 예약을 다시
조회하고 `NotificationChannel`을 즉시 호출한다. 이 구조에서는 예약 transaction이
commit된 뒤 프로세스가 종료되거나 발송 adapter가 실패하면 알림 요청을 복구할
durable record가 없다.

`ResilientNotificationChannel`은 최종 실패를 warning log로 남긴 뒤 소비하고,
`DummyNotificationChannel`만 성공 이력을 기록한다. 따라서 운영자는 실제 발송
실패와 재시도 상태를 DB에서 확인할 수 없고, 성공 기록만 조회하는 기존 dedupe
계약으로는 실패 이후의 안전한 재처리 여부를 판단하기 어렵다.

현재 구현은 이름과 전화번호를 예약 row에서 읽고, raw 전화번호와 렌더링에 가까운
payload를 알림 이력에 저장한다. 이 정보는 회원 서비스가 소유해야 하며 알림
재시도와 감사에 필요하지 않다. 예약서비스가 알림을 위해 개인정보 사본을 늘리면
보존 기간, 접근 권한, 정정과 탈퇴 처리가 회원 DB와 어긋날 수 있다.

## 2. 목표

1. 알림을 발생시키는 예약 변경과 알림 outbox 생성을 같은 DB transaction에서
   commit한다.
2. 발송 실패를 상태, 시도 횟수, 다음 시도 시각과 안정적인 오류 코드로 확인한다.
3. 재시도 횟수와 기간에 상한을 두고 최대 시도 소진을 운영 신호로 노출한다.
4. 성공한 논리 알림은 다시 발송하지 않는다.
5. 수신자 정보는 `memberId`만 영속 전달하고 이름, 전화번호, 언어와 수신 동의는
   발송 시점에 회원 DB에서 조회한다.
6. 이름, raw 전화번호, 완성된 메시지 본문과 임의 예외 메시지를 outbox, 발송 이력,
   log와 metric에 남기지 않는다.
7. version이 고정된 parameterized template으로 발송 내용을 재현 가능하게 만든다.
8. 여러 애플리케이션 인스턴스가 동시에 poll해도 같은 row를 동시에 처리하지
   않는다.
9. 기존 예약 중 `memberId`가 없는 row는 추정 매칭이나 개인정보 fallback 없이
   명시적으로 자동 알림에서 제외한다.

## 3. 비목표와 범위

### 3.1 이번 변경에 포함하지 않는 항목

- SMS, email 또는 push provider 제품 선정
- 회원 DB의 schema, 개인정보 정정, 탈퇴와 동의 정책 변경
- 이름이나 전화번호를 이용한 회원 추정 매칭
- `scheduling_appointments`에 이미 저장된 이름과 전화번호의 전면 제거
- 병원별 template 편집 UI와 동적 template 배포 시스템
- 외부 message broker 도입
- 모든 외부 scheduling event의 generic publication outbox 재설계
- provider가 지원하지 않는 exactly-once delivery 보장

예약 테이블의 이름과 전화번호는 기존 운영 화면과 API가 사용하므로 제거 범위가
크다. Issue #172에서는 알림 경로가 이 값을 읽거나 복사하지 않도록 제한한다.
예약서비스 전반의 개인정보 최소화는 별도 이슈에서 API와 화면 영향까지 함께
검토한다.

### 3.2 서비스 책임

| 관심사 | 소유자 | 책임 |
|---|---|---|
| 회원 이름, 연락처, 언어, 채널별 수신 동의 | 회원 서비스 | 원본 저장, 정정, 탈퇴와 조회 |
| 예약 상태와 알림 발생 조건 | 예약서비스 | 상태 변경과 알림 요청의 원자적 기록 |
| `memberId`와 예약의 연결 | 예약서비스·회원 서비스 경계 | 로그인 또는 Plan 연결을 서버에서 검증 |
| template key/version과 parameter schema | 알림 서비스 | 허용된 template과 입력 검증 |
| 메시지 렌더링과 provider 호출 | 알림 서비스 | 발송 직전 회원 정보 조회 후 처리 |
| 재시도, lease, 발송 결과와 운영 지표 | 알림 서비스 | durable lifecycle과 장애 복구 |

## 4. 현재 근거

| 근거 | 현재 동작 | 설계에 미치는 영향 |
|---|---|---|
| `NotificationEventListener` | Spring event 수신 뒤 예약 조회와 직접 발송 | 커밋 이후 유실 구간을 durable outbox로 교체 |
| `ResilientNotificationChannel` | 최종 예외를 warning으로 소비 | durable worker가 최종 outcome을 기록하도록 변경 |
| `NotificationHistoryTable` | raw `recipient`, `payloadJson`, `errorMessage` 저장 가능 | 비식별·제한된 발송 시도 이력으로 교체 |
| `SchedulingUserPrincipal.patientSubjectId` | 로그인 환자에게 필수인 안정적 subject | self-service 예약의 `memberId` 해석 근거 |
| `Appointments.patientExternalId` | 고객 서비스가 발급한 선택적 외부 식별자 | 물리 column을 유지한 채 `memberId` migration 원천으로 사용 |
| `Appointments.patientReferenceFingerprint` | 환자 소유권 비교용 비가역 참조 | 회원 조회 key로 사용할 수 없음 |
| `SchedulingOutboxEvents` | 외부 scheduling event publication lifecycle | notification delivery lifecycle과 분리 |

`AppointmentService`의 legacy command와 commitment v2 command는 각각 transaction
경계가 다르다. 구현에서는 두 변경 경로가 공통 notification outbox writer를 같은
transaction 안에서 호출하도록 해야 한다. transaction 밖에서 Spring event를 받아
outbox를 만드는 방식은 commit과 outbox insert 사이의 장애 구간을 남기므로
허용하지 않는다.

## 5. 대안 비교

### 5.1 선택: 알림 전용 transactional outbox

알림 요청을 예약 변경 transaction에 함께 기록하고, 알림 worker가 row를 claim해
회원 조회, template 렌더링과 provider 발송을 수행한다.

장점은 예약 변경과 알림 요청의 원자성, 상한이 있는 재시도, 상태 조회와 개인정보
경계가 하나의 모델에 들어간다는 점이다. 추가 table, worker, migration과 운영
지표가 필요하지만 Issue #172의 실패 복구 요구를 가장 직접적으로 충족한다.

### 5.2 기각: 현재 event listener에 실패 이력과 재시도만 추가

직접 발송 listener가 실패 row를 남기도록 바꾸면 provider 오류는 관측할 수 있다.
그러나 transaction commit 뒤 listener 실행 전 프로세스가 종료되면 실패 row 자체가
생기지 않는다. 장애가 발생했다는 사실조차 남지 않는 구간을 제거할 수 없으므로
기각한다.

### 5.3 기각: generic `SchedulingOutboxEvents`를 알림 전달 상태로 재사용

기존 generic outbox는 consumer-facing scheduling event publication을 위한
`PENDING/PUBLISHED/FAILED` lifecycle을 가진다. 알림의 회원 조회, suppression,
provider delivery와 retry lease를 여기에 넣으면 event publication과 사용자 알림의
성공 의미가 섞인다. 또한 기존 payload 계약은 patient reference를 금지한다.

동일한 Exposed·transaction·retention 패턴은 재사용하되, table과 상태 계약은
알림 전용으로 분리한다.

### 5.4 보류: 외부 broker와 독립 notification service

대규모 SaaS 환경에서 알림 서비스를 별도 배포 단위로 분리할 수 있다. 그러나
broker publish 자체에도 transactional outbox가 필요하고, 현재 이슈는 저장소 안의
유실·재시도 결함을 먼저 닫는 작업이다. 알림 전용 outbox를 broker publisher가
읽도록 바꾸는 확장 경로는 열어 두되 이번 변경에는 broker를 추가하지 않는다.

## 6. 구성요소 경계

### 6.1 `appointment-event`

알림 요청의 durable contract와 caller-transaction repository를 둔다.

- `NotificationOutboxEvents`
- `NotificationOutboxRepository`
- `NotificationOutboxKeyRing`
- `NotificationOutboxHasher`
- event type, template key/version, 상태와 오류 코드
- typed template parameter 직렬화 계약

`appointment-api`는 이미 `appointment-event`에 의존하고,
`appointment-notification`도 `appointment-event`에 의존한다. 따라서 이 위치는
모듈 의존성을 역전하거나 순환시키지 않는다.

키 링과 해시 계약은 `appointment-event`가 port로 정의한다. `appointment-api`는
enqueue용 구현을, `appointment-notification`은 종료 행 비식별화용 구현을
constructor injection으로 받는다. 두 구현은 같은 외부 secret-backed key registry를
사용하되 key material을 event 객체, DB, 로그나 설정 파일에 기록하지 않는다.
repository가 precomputed digest를 신뢰하거나 호출 모듈이 임의 key를 선택하게 하지
않는다.

### 6.2 `appointment-api`

예약 생성, 확정, 취소와 재배정 command transaction에서
`NotificationOutboxRepository.enqueue`를 호출한다.

- legacy와 commitment v2 command가 같은 outbox 계약을 사용한다.
- 기존 Spring domain event는 logging과 다른 in-process observer를 위해 유지할 수
  있지만 알림 발송의 기준 문서가 아니다.
- 알림 listener가 Spring event를 받아 직접 channel을 호출하는 경로는 제거한다.

### 6.3 `appointment-notification`

polling, claim/lease, 회원 조회, template 렌더링, provider 호출, durable outcome과
metric을 소유한다.

- `NotificationOutboxWorker`
- `NotificationDeliveryAttempts`
- `MemberNotificationProfileResolver`
- `NotificationTemplateCatalog`
- provider별 `NotificationChannel`
- retry policy와 lease recovery
- retention/redaction runner

`LeaderGroupElector`는 reminder schedule trigger를 줄이는 보조 수단으로 유지할 수
있다. outbox 처리의 정확성은 leader election이 아니라 DB claim/lease가 보장한다.
leader 전환이나 Redis 장애가 중복 발송 또는 영구 정지를 만들면 안 된다.

## 7. 식별자와 개인정보 경계

### 7.1 `memberId` 해석

`memberId`는 회원 서비스가 조회할 수 있는 안정적이고 opaque한 식별자다. 이름,
전화번호나 의료 식별자를 encode하면 안 된다.

- 고객이 직접 예약하면 인증된 `patientSubjectId`를 서버 adapter가 `memberId`로
  해석한다. self-service request body에서 회원 ID를 신뢰하지 않는다.
- 직원이 대신 예약하면 선택한 회원 또는 Appointment Plan의 회원 참조를 회원
  서비스에서 검증한 뒤 사용한다.
- `patientSubjectId`와 `memberId`가 같은 namespace라는 보장은 adapter 계약으로
  명시하지 않는 한 가정하지 않는다.
- `patientSubjectId`에서 `memberId`를 해석하는 resolver가 없거나 namespace·tenant
  검증에 실패하면 값을 그대로 복사하지 않고 예약 생성 또는 outbox enqueue를
  `MEMBER_ID_REQUIRED`나 별도 stable code로 거절한다.
- `patientReferenceFingerprint`는 ownership 비교 전용이므로 역조회하거나
  `memberId` 대신 사용할 수 없다.

외부 모델의 기준 이름은 `memberId`로 통일한다. 기존
`Appointments.patientExternalId` physical column은 migration 호환을 위해 유지하고
repository 경계에서 `MemberId`로 해석한다. 신규 예약은 application invariant로
non-null을 강제한다. 향후 physical column rename은 별도 schema cleanup으로 다룬다.

### 7.2 영속 금지 정보

다음 값은 outbox, attempt history, log와 metric에 저장하지 않는다.

- 회원 이름과 raw 전화번호
- 주소, email, 생년월일과 의료 식별자
- 완성된 메시지 제목과 본문
- provider request body
- 자유 텍스트 취소 사유
- stack trace와 원문 예외 메시지
- 인증 token, credential과 key material

알림 worker는 회원 profile과 렌더링 결과를 provider 호출에 필요한 시간 동안만
메모리에 보유한다.

### 7.3 종료 행의 데이터 최소화

활성 아웃박스 행은 발송을 위해 raw `memberId`를 보유할 수 있다. 행이 `SENT`,
`SUPPRESSED` 또는 `EXHAUSTED`가 되는 트랜잭션에서 `memberId`, `appointmentId`와
parameter 본문을 즉시 제거한다. retention runner는 즉시 제거에 실패한 row를
보정하고 보존 기간이 끝난 이력을 삭제한다. raw field 제거가 끝나지 않은 종료
행은 운영 조회에 노출하지 않으며, 제거 지연의 상한과 실패를 alert로 감시한다.

종료 이력에는 tenant와 용도를 분리한 HMAC fingerprint만 남긴다. fingerprint는
정규화 입력, `hmacKeyId`, `fingerprintVersion`을 함께 기록한다. active key와
직전 key를 허용하는 제한된 키 교체 유예 기간을 두고, 오래된 key의 조회 정책과
폐기 시점을 명시한다. audit fingerprint key 조회가 실패해도 raw field는 즉시
제거하고 `HMAC_KEY_UNAVAILABLE`을 남기며 fingerprint 생성을 보류한다.
idempotency key ring을 읽을 수 없으면 중복 enqueue를 피하기 위해 command를
fail-closed로 거절한다. idempotency key와 감사용 fingerprint는 서로 다른
domain-separated key를 사용한다.

provider message ID는 provider가 개인정보를 포함하지 않는 opaque delivery ID라고
문서화한 경우에만 길이와 문자셋을 제한해 저장한다. 그 조건을 만족하지 않으면 raw
값을 저장하지 않고 stable audit code 또는 허용된 hash만 남긴다.

종료 행의 보존 기본값은 다음과 같다.

| 상태 | raw 정보 제거 | 이력 보존 |
|---|---|---|
| `SENT` | 종료 상태 전환 트랜잭션 | 7일 |
| `SUPPRESSED` | 종료 상태 전환 트랜잭션 | 7일 |
| `EXHAUSTED` | 종료 상태 전환 트랜잭션 | 30일 |

보존 기간은 platform default로 제공하되 법적·운영 요구가 생기면 별도 정책
설계에서 tenant/clinic override와 상한을 정의한다.

## 8. Outbox 계약

### 8.1 논리 envelope

```kotlin
data class NotificationOutboxEnvelope(
    val schemaVersion: Int,
    val eventId: NotificationEventId,
    val idempotencyKey: NotificationIdempotencyKey,
    val tenantGroupId: Long,
    val clinicId: Long,
    val appointmentId: Long,
    val memberId: MemberId,
    val channel: NotificationChannelType,
    val eventType: NotificationEventType,
    val notificationSlot: NotificationSlot,
    val templateKey: NotificationTemplateKey,
    val templateVersion: Int,
    val parameterType: NotificationParameterType,
    val parameters: NotificationTemplateParameters,
    val occurredAt: Instant,
    val availableAt: Instant,
)
```

`eventId`는 한 domain event instance를 식별한다. `idempotencyKey`는 다음 요소를
정규화한 뒤 domain-separated HMAC으로 만든다.

```text
tenantGroupId
+ clinicId
+ appointmentId
+ appointmentVersion 또는 proposal revision
+ eventType
+ channel
+ notificationSlot
```

같은 예약에 같은 종류의 알림이 여러 번 필요하면 appointment version 또는
proposal revision이나 `notificationSlot`이 달라야 한다. reminder의
`notificationSlot`은 `REMINDER_24H`처럼 예약 version 안의 발송 시점을 구분한다.
DB unique index는 key version과 idempotency key 조합으로 동일 논리 알림의 중복
enqueue를 차단한다. key rotation 중에는 active key와 직전 key로 계산한 값을 모두
조회해 기존 논리 알림을 다시 만들지 않는다.

위 envelope는 발송 가능한 활성 행의 계약이므로 `memberId`가 non-null이다.
legacy 억제 이력은 같은 outbox table에 종료 상태 `SUPPRESSED` 행으로 기록하되
`memberId`, `appointmentId`, template과 parameter가 없는 별도 repository
operation을 사용한다.

영속 행은 다음 두 종류의 sealed contract로 구분한다.

| 행 종류 | 허용 상태 | 필수 값 | 반드시 `NULL`인 값 |
|---|---|---|---|
| `SENDABLE` | 모든 상태 | 활성 상태일 때 appointment, member, channel, event type, notification slot, template, parameter | 없음 |
| `LEGACY_SUPPRESSION` | `SUPPRESSED`만 | tenant, clinic, `eventId`, idempotency digest, suppression reason | appointment, member, channel, event type, notification slot, provider key, template, parameter |

DB `CHECK` constraint는 위 조합을 강제하고 worker claim query는
`rowKind = SENDABLE`만 선택한다. legacy 행의 idempotency digest는 command
transaction 안에서 raw appointment ID와 version으로 계산하지만, 원본 ID는 insert
payload나 영속 행에 남기지 않는다.

opaque ID와 HMAC key는 Kotlin `@JvmInline value class`로, channel·event type·slot과
parameter type은 닫힌 enum 또는 sealed type으로 정의한다. durable JSON에는
`schemaVersion`과 `parameterType` discriminator를 반드시 기록한다.
`NotificationTemplateParameters` 구현은 `Serializable`이며
`serialVersionUID`를 명시한다. 알 수 없는 schema/parameter version은 추정
deserialization이나 fallback 없이 `TEMPLATE_PARAMETER_INVALID`로 격리한다.

### 8.2 Parameterized template

outbox는 `Map<String, Any?>`를 받지 않는다. event별 sealed parameter model을
정의하고 직렬화 전에 길이, enum, 시각과 허용 field를 검증한다.

예시는 다음과 같다.

```kotlin
sealed interface NotificationTemplateParameters

data class AppointmentConfirmedParameters(
    val clinicDisplayName: String,
    val appointmentDate: LocalDate,
    val startTime: LocalTime,
) : NotificationTemplateParameters
```

`memberName`, `phone`, `locale`과 consent는 parameter가 아니다. 알림 서비스가
발송 시점의 회원 profile에서 채운다. 취소 알림은 자유 텍스트 reason 대신 등록된
`reasonCode`만 사용할 수 있다.

renderer는 parameter를 실행 가능한 template 표현식이 아니라 data로만 취급한다.
SMS, email, push의 출력 문맥별 escaping을 적용하고 control character, 허용하지
않은 URI scheme, template-expression delimiter와 길이 상한 위반은 fail-closed로
거절한다.

template version은 outbox enqueue 시점에 고정한다. worker는 해당 version을 찾지
못하면 fallback template을 임의 선택하지 않고 종료 configuration failure로
격리한다. 초기 구현은 code-owned `NotificationTemplateCatalog`를 사용하며 병원별
편집과 동적 배포는 후속 범위다.

예약 시각과 병원명도 민감할 수 있으므로 template별 최소 field만 허용하고 종료 상태
전환 뒤 제거한다.

### 8.3 상태

| 상태 | 의미 |
|---|---|
| `PENDING` | 첫 발송을 기다림 |
| `PROCESSING` | 유효한 lease를 가진 worker가 처리 중 |
| `RETRY_WAIT` | 재시도 시각까지 대기 |
| `SENT` | provider가 성공을 확인함 |
| `SUPPRESSED` | 회원 상태·동의·연락처 또는 legacy 정책으로 발송하지 않음 |
| `EXHAUSTED` | 상한이 있는 재시도를 소진해 자동 처리 종료 |

허용 전이는 다음과 같다.

```text
PENDING ───────→ PROCESSING ───────→ SENT
                    ├──────────────→ SUPPRESSED
                    ├──────────────→ EXHAUSTED
                    └→ RETRY_WAIT ─→ PROCESSING

PROCESSING -- lease 만료 복구 --> RETRY_WAIT
```

종료 상태에서 활성 상태로 복귀시키지 않는다. raw member/appointment/template
정보를 제거한 종료 행은 자동 redrive할 수 없다. 수동 재알림이 필요하면
현재 예약과 회원 상태를 다시 검증해 새 `eventId`와 수동 generation을 가진 별도
논리 알림을 만든다.

## 9. 처리와 동시성

### 9.1 transaction 경계

1. 예약 command transaction이 예약 상태, 상태 이력, 감사 record와 notification
   outbox를 함께 기록한다.
2. 예약이 확정되면 예정된 reminder row를 같은 command transaction에서 미래
   `availableAt`과 `notificationSlot`로 미리 기록한다. 재배정이나 취소 transaction은
   이전 appointment version의 reminder를
   `SUPPRESSED(APPOINTMENT_CHANGED)`로 끝내고 새 version의 reminder를 만든다.
   scheduler는 provider를 직접 호출하지 않으며, 운영 보정 scanner가 제한된
   catch-up window에서 누락된 reminder materialization만 같은 idempotency key로
   복구한다. window를 지난 reminder는 `SUPPRESSED(REMINDER_WINDOW_MISSED)`로
   기록한다.
3. poller는 처리 가능한 행 ID를 상한이 정해진 페이지로 찾는다.
4. worker는 짧은 transaction에서 조건부 update로 row 하나를 `PROCESSING`으로
   claim하고 `leaseOwner`, 새 opaque `leaseToken`, `leaseUntil`, 증가된 attempt를
   기록한다.
5. 회원 조회, template 렌더링과 provider I/O는 transaction 밖에서 수행한다.
6. worker는 짧은 transaction에서 `id`, `PROCESSING`, `leaseOwner`,
   `leaseToken`, attempt 번호와 만료되지 않은 `leaseUntil`을 모두 조건으로 삼는
   fenced update로 종료 상태 또는 `RETRY_WAIT` 상태를 기록한다. 조건이 불일치하면
   stale worker는 row를 변경하지 않고 `LEASE_LOST`와 알 수 없는 provider 결과를
   기록한다.

외부 I/O 동안 DB transaction과 row lock을 유지하지 않는다.

### 9.2 다중 인스턴스 claim

정확성은 다음 조건부 갱신으로 보장한다.

```text
status IN (PENDING, RETRY_WAIT)
AND availableAt <= databaseNow
AND nextAttemptAt <= databaseNow
```

claim update가 1 row를 변경한 worker만 발송할 수 있다. database별
`SKIP LOCKED` 지원 차이에 정확성을 의존하지 않으며, H2/PostgreSQL/MySQL에서 같은
CAS 의미를 검증한다.

별도 recovery query는 `PROCESSING AND leaseUntil < databaseNow`인 row를 찾아 새
fencing token으로 직접 reclaim하거나 `RETRY_WAIT`로 원자 전환한다. 이때
`LEASE_LOST` attempt와 복구 metric을 남긴다. 만료된 `PROCESSING`
row가 일반 claim 조건 밖에 남아 정지하는 상태를 허용하지 않는다.

저장되는 lifecycle 시각과 CAS 비교는 transaction-local database UTC 시각을
기준으로 한다. H2, PostgreSQL, MySQL의 timestamp 정밀도를 가장 낮은 공통
정밀도로 정규화한다. 애플리케이션 node의 local clock은 lease 소유권 판단에
사용하지 않는다.

poller는 전역 FIFO만 사용하지 않는다. tenant/clinic을 고려한 제한된
round-robin 또는 동등한 fair scheduling으로 clinic별 최대 in-flight를 제한하고,
한 대형 병원의 backlog가 다른 병원을 고갈시키지 않게 한다. channel/provider
bulkhead도 분리한다. 공용 metric에 tenant/clinic label을 추가하지 않고,
권한이 제한된 DB dashboard에서 병원별 lag를 조회한다.

claim 경로에는 dialect별 실행 계획에 맞춘 index를 둔다. 최소한 active status,
`rowKind`, tenant/clinic 또는 실제 fair-scheduling shard cursor, `availableAt`,
`nextAttemptAt`, lease expiry와 `id` 정렬을 지원하는 claim/recovery index와
`(idempotencyKeyVersion, idempotencyKey)` unique index가 필요하다. 최종 column
순서는 구현한 공정 claim 질의의 `WHERE`와 `ORDER BY`에 맞춘다.
PostgreSQL, MySQL, H2 migration은 대표적인 활성·종료 backlog에서
`EXPLAIN` 또는 동등한 실행 계획 근거로 poll과 recovery가 full scan과 대규모
sort를 만들지 않음을 검증한다.

lease 시간은 provider timeout과 in-process retry 상한보다 길어야 한다. 긴
provider 호출을 허용해야 하면 명시적 lease 연장을 추가한다. lease 상실 뒤에는
성공 상태를 기록하지 않으며, provider idempotency key로 중복 가능성을 줄인다.

### 9.3 전달 의미

DB transaction과 외부 provider 호출을 하나의 원자 작업으로 만들 수 없으므로
기본 전달 의미는 at-least-once다.

- provider가 idempotency key를 지원하면 동일 key를 전달한다.
- timeout 뒤 provider 성공 여부를 알 수 없으면 `DELIVERY_RESULT_UNKNOWN`으로
  재시도하되 중복 가능성을 metric과 운영 화면에 노출한다.
- provider가 idempotency를 지원하지 않으면 exactly-once를 주장하지 않는다.
- `SENT` row와 같은 idempotency key는 다시 발송하지 않는다.

## 10. 회원 조회와 suppression

`MemberNotificationProfileResolver`는 최소한 다음 값을 반환한다.

```kotlin
data class MemberNotificationProfile(
    val displayName: String,
    val destination: String,
    val locale: Locale,
    val consent: NotificationConsent,
)
```

응답은 outbox에 다시 저장하지 않는다. worker는 tenant와 member scope가 요청과
일치하는지 확인하고 채널별 consent를 평가한다.

resolver는 provider adapter와 별도의 timeout, bulkhead, rate limiter와 circuit
breaker를 사용한다. worker의 전체 동시성은 DB claim, 회원 서비스와 provider가
허용하는 동시성 중 가장 낮은 값으로 제한한다. 같은 batch에서 동일 회원 조회는
in-process single-flight로 합칠 수 있지만, 최신 연락처와 동의를 건너뛰는 영속
cache는 두지 않는다.

| 결과 | 처리 |
|---|---|
| timeout, 429, 5xx | retryable |
| 회원 없음 또는 탈퇴 | `SUPPRESSED(MEMBER_NOT_AVAILABLE)` |
| 채널 연락처 없음 | `SUPPRESSED(DESTINATION_UNAVAILABLE)` |
| 수신 거부 | `SUPPRESSED(CONSENT_DENIED)` |
| tenant/member scope 불일치 | `SUPPRESSED(MEMBER_SCOPE_MISMATCH)`와 보안 경보 |
| 허용하지 않은 profile field 포함 | fail-closed, payload 비기록 |

queue 대기 중 회원이 연락처를 바꾸거나 동의를 철회하면 발송 시점의 최신 값을
적용한다. 이전 전화번호나 enqueue 시점의 동의를 snapshot하지 않는다.

## 11. 재시도와 장애 분류

### 11.1 durable retry

durable outbox가 장시간 retry의 단일 기준이다. Resilience4j의 in-process retry는
network transient를 흡수하는 작은 상한으로 제한하거나 provider adapter에서
비활성화해 두 계층의 attempt 수가 곱해지지 않게 한다.

기본 정책은 exponential backoff와 deterministic jitter를 사용하고 다음 두 상한을
모두 적용한다.

- 최대 시도 횟수
- 최초 시도부터의 최대 경과 시간

platform default는 durable attempt 6회, 최대 경과 시간 24시간, lease별 provider
호출 1회다. durable attempt는 획득한 lease 수를 기준으로 계산하며 provider
adapter의 in-process attempt와 구분한다. 설정 범위는 durable attempt 1~10회,
최대 경과 시간 15분~72시간, lease별 provider 호출 1~2회로 제한하고 두 attempt의
곱은 platform 최대 12회를 넘을 수 없다. 설정 누락이나 범위 위반은 무제한 retry가
아니라 default 적용 또는 기동 거부로 fail-closed한다.

### 11.2 안정적인 오류 코드

재시도·실패와 발송 억제를 서로 다른 닫힌 enum으로 관리한다.

`NotificationFailureCode`:

- `MEMBER_DIRECTORY_UNAVAILABLE`
- `PROVIDER_RATE_LIMITED`
- `PROVIDER_UNAVAILABLE`
- `CIRCUIT_OPEN`
- `DELIVERY_RESULT_UNKNOWN`
- `TEMPLATE_NOT_FOUND`
- `TEMPLATE_PARAMETER_INVALID`
- `LEASE_LOST`
- `HMAC_KEY_UNAVAILABLE`

`NotificationSuppressionReasonCode`:

- `MEMBER_NOT_AVAILABLE`
- `DESTINATION_UNAVAILABLE`
- `CONSENT_DENIED`
- `MEMBER_SCOPE_MISMATCH`
- `MEMBER_ID_MISSING_LEGACY`
- `APPOINTMENT_CHANGED`
- `REMINDER_WINDOW_MISSED`

DB 값, API 응답, metric outcome과 테스트는 이 목록의 값만 사용한다. 새 값을 추가할
때에는 migration, OpenAPI와 운영 action mapping을 함께 변경한다.

원문 예외 메시지와 provider payload를 DB나 metric tag에 넣지 않는다. 필요한
진단은 trace ID, outbox ID, provider code의 allowlist와 내부 stack trace 수집
정책을 분리해 다룬다.

## 12. 신규 예약과 기존 예약 호환

### 12.1 신규 예약

모든 신규 예약은 `memberId`를 가져야 한다.

- `POST /api/v2/appointments`: 인증된 `patientSubjectId`와
  `CreateAppointmentRequestV2.appointmentPlanId`로 서버가 `memberId`를 해석한다.
  request body에 회원 ID를 추가하지 않는다.
- `POST /api/v2/admin/appointments`:
  `DirectCreateAppointmentRequest.appointmentPlanId`가 가리키는 회원을 Plan access
  resolver가 검증한다. 이 endpoint도 이름이나 전화번호를 입력으로 받지 않는다.
- `POST /api/{tenantCode}/appointments`: legacy 직원 API의
  `CreateAppointmentRequest`에 `memberId`를 추가한다. 최종 계약에서는
  `memberId`가 필수이고 이름·전화번호는 회원 선택을 대체하지 못한다.
- 기존 예약의 확정, 취소, 재배정 command는 영속된 `memberId`를 사용하며 caller가
  다시 전달하지 않는다.

legacy 직원 API는 `memberId` 하나만 받는다. `appointmentPlanId`와 회원 선택을
동시에 받는 새 DTO를 만들지 않는다. v2 관리자 API는 기존 Plan ID만 사용하므로
두 입력이 모호하게 공존하지 않는다. 선택한 회원은 현재 tenant/clinic에서 접근할
수 있어야 한다.

회원 참조 오류는 `SchedulingApiErrorResponse`로 반환한다.

| 상황 | HTTP | `errorCode` | caller action |
|---|---:|---|---|
| legacy 요청에 `memberId` 없음 | 422 | `MEMBER_ID_REQUIRED` | 회원을 선택해 다시 요청 |
| 회원 없음 | 404 | `MEMBER_NOT_FOUND` | 최신 회원 검색 결과 사용 |
| tenant/clinic scope 불일치 | 403 | `MEMBER_SCOPE_MISMATCH` | 접근 범위와 선택 회원 확인 |
| 여러 mapping으로 결정 불가 | 409 | `MEMBER_REFERENCE_AMBIGUOUS` | Plan 또는 회원 데이터 정리 |
| 회원 서비스 일시 장애 | 503 | `MEMBER_DIRECTORY_UNAVAILABLE` | `Retry-After` 뒤 같은 멱등성 키로 재시도 |

오류 본문은 기존 `success`, `error`, `errorCode`, `correlationId`,
`retryable`, `action` 계약을 사용하고 이름, 전화번호, raw member ID를 반사하지
않는다.

이름이나 전화번호만으로 신규 예약을 생성해 자동 알림 대상으로 만드는 fallback은
제공하지 않는다.

`appointment.notification.member-id-enforcement`는 rollout 중에만
`OBSERVE`와 `ENFORCE`를 허용한다. `OBSERVE`에서는 legacy endpoint의 누락 요청을
한시적으로 허용하되 자동 알림은 종료 상태의 legacy suppression으로 끝내고 migration
metric을 남긴다. `ENFORCE`에서는 422로 거절한다. 신규 배포의 platform default는
`ENFORCE`이고 clinic별 `OBSERVE` 예외에는 만료 시각과 담당자가 필요하다. 전환이
끝나면 `OBSERVE` 분기를 제거한다.

### 12.2 기존 예약

기존 nullable column과 row는 migration 중 유지한다.

- `patientExternalId`가 회원 서비스의 확정적인 ID임을 검증할 수 있으면
  `memberId` migration 원천으로 사용한다.
- `memberId`가 없는 기존 예약에서 알림 event가 발생하면 발송 가능한 outbox를
  만들지 않는다. 같은 command 또는 reminder materialization transaction에서
  같은 논리 idempotency key의 종료 아웃박스 행을
  `SUPPRESSED(MEMBER_ID_MISSING_LEGACY)`로 upsert한다.
- legacy suppression row는 raw `memberId`, appointment ID, 이름, 연락처,
  template parameter, message와 예외를 저장하지 않는다. tenant/clinic scope,
  `eventId`, HMAC fingerprint, idempotency digest, stable reason code만 둔다.
  발송 분류에 쓰는 event type, notification slot, channel, template, provider
  key, parameter 계열 필드는 모두 `NULL`로 둔다.
- 이름과 전화번호로 회원을 추정하지 않는다.
- 자동 backfill은 회원 서비스가 제공하는 확정적인 mapping만 사용한다.
- backfill 전까지 해당 예약의 자동 알림은 제외한다.

backfill은 쓰기 전에 dry-run report를 만든다. report는 tenant/clinic별
eligible, backfilled, suppressed, mapping-conflict 수와 개인정보 없는 reason
code만 포함한다. `ENFORCE` 전환 조건은 대상 caller의 OpenAPI 반영, 최근 7일간
신규 missing-member 0건, unresolved mapping 목록의 담당자 지정과 dry-run 승인이다.

## 13. 발송 시도 이력과 관측성

### 13.1 발송 시도 이력

`NotificationDeliveryAttempts`는 다음과 같이 상한이 정해진 정보만 가진다.

- outbox ID와 attempt 번호
- channel, event type, template key/version
- 시작·완료 시각과 duration
- outcome과 안정적인 오류 코드
- 검증된 `providerMessageReference`
- tenant-scoped destination fingerprint
- correlation/trace ID

raw `recipient`, `payloadJson`, 렌더링 본문과 `errorMessage` column은 새 계약에서
사용하지 않는다. 기존 history table은 additive migration과 dual-read 기간을 거쳐
중단하며, 성공 dedupe는 outbox idempotency unique index가 담당한다.

attempt table은 `(outboxId, attemptNumber)` unique constraint와 종료 시각 기반
purge index를 둔다. row 수는 retry 정책의 최대 attempt와 lease recovery 기록
상한으로 제한하고, 아웃박스 종료 행의 보존 기간과 같은 기간 안에 크기가 제한된 page로
삭제한다.

`providerMessageReference`는 종료 아웃박스 행과 같은 opaque-ID 검증, 길이·문자셋
제한과 hash fallback을 적용한다. raw provider ID를 기본 문자열 column으로
저장하지 않는다.

attempt row는 claim transaction에서 `(outboxId, attemptNumber, leaseToken)`으로
먼저 만든다. outbox 상태의 fenced update가 실패한 stale worker는 새 attempt를
추가하지 않고 자신이 만든 attempt row만 `LEASE_LOST`로 끝낸다. 새 lease owner는
새 attempt 번호와 token을 사용하므로 unique 충돌과 이전 worker의 상태 덮어쓰기를
막는다.

### 13.2 metric

최소 지표는 다음과 같다.

| 지표 | 설명 |
|---|---|
| `clinic.notification.outbox.pending` | 처리 가능한 대기 row 수 |
| `clinic.notification.outbox.oldest.age` | 가장 오래된 활성 행의 age |
| `clinic.notification.delivery.attempts` | channel/event/outcome별 attempt 수 |
| `clinic.notification.delivery.latency` | enqueue부터 종료 상태까지 시간 |
| `clinic.notification.delivery.retries` | retryable failure 수 |
| `clinic.notification.delivery.suppressed` | reason code별 suppression 수 |
| `clinic.notification.delivery.exhausted` | 자동 처리 종료 수 |
| `clinic.notification.delivery.lease.recovered` | 만료 lease 복구 수 |

member, appointment, 전화번호, outbox ID를 metric label로 사용하지 않는다.
tenant와 clinic도 cardinality가 커질 수 있으므로 공용 metric label에서는 제외하고,
병원별 운영 조회는 DB query 또는 제한된 dashboard filter로 제공한다.

pending 수와 oldest age는 indexed active-row query, worker가 갱신하는 cached
gauge 또는 상한이 정해진 sampler로 계산한다. scrape마다 전체 table에 exact
`COUNT`/`MIN` full scan을 수행하지 않는다.

초기 alert 기준은 다음과 같고 배포 전 부하 검증으로 조정할 수 있다. 설정 변경은
상한을 벗어날 수 없고 알림 플랫폼 on-call이 소유한다.

| 신호 | 기본 기준 | 심각도 | 해제 조건 |
|---|---|---|---|
| oldest 활성 행 age | 5분 초과 10분 지속 / 30분 초과 5분 지속 | warning / critical | 10분 동안 5분 미만 |
| `EXHAUSTED` | 5분에 1건 / 10건 이상 | ticket / critical | 15분 동안 신규 0건 |
| provider failure ratio | 최소 100건 중 20% / 50% 초과 5분 | warning / critical | 15분 동안 5% 미만 |
| `DELIVERY_RESULT_UNKNOWN` | 1건 / 5분에 5건 이상 | warning / critical | 원인 확인과 신규 0건 |
| lease recovery ratio | 최소 100건 중 5% 초과 10분 | warning | 15분 동안 1% 미만 |
| pending backlog | 10분 연속 증가하며 10,000건 초과 | warning | 15분 감소 추세 |

공용 alert label은 channel, event type, outcome과 provider category만 사용한다.
운영 dashboard는 권한 검사 뒤 tenant, clinic, 상태, channel, event type,
reason code와 시간 범위로 필터링할 수 있지만 raw member/appointment/outbox ID는
반환하지 않는다.

### 13.3 운영 조회와 caller 표시

직원용 조회는 예약별 안전한 알림 상태를 제공한다.

```text
status
reasonCode
nextAttemptAt
exhaustedAt
recommendedAction
patientVisible
```

이 조회는 실제 destination, 회원 ID, provider payload와 본문을 반환하지 않는다.
`CONSENT_DENIED`는 “회원 설정 확인”, `DESTINATION_UNAVAILABLE`은 “회원 연락처
확인”, `EXHAUSTED`는 “알림 담당자 확인”처럼 닫힌 action registry로 안내한다.
환자 화면과 API는 예약 성공 여부를 알림 성공과 결합하지 않으며 상세 suppression
reason이나 provider 실패를 노출하지 않는다.

`REMINDER_WINDOW_MISSED`는 예약 상태를 바꾸지 않고 늦은 reminder도 자동 발송하지
않는다. 직원 dashboard에 수동 연락 또는 확인 처리 action item을 만들며 담당자가
acknowledge할 수 있다. catch-up window 기본값은 30분이고 platform 설정으로
관리한다.

## 14. 주요 실패 모드

### 14.1 예약 commit 뒤 프로세스 종료

outbox가 예약 변경과 같은 transaction에서 commit되므로 다음 worker가 row를
처리한다. outbox insert에 실패하면 예약 변경도 rollback된다.

### 14.2 provider 성공 뒤 DB 완료 기록 전 종료

row lease가 만료된 뒤 재처리될 수 있다. provider idempotency key가 있으면 중복을
억제한다. 지원하지 않는 provider에서는 at-least-once 중복 가능성을 인정하고
`DELIVERY_RESULT_UNKNOWN`을 운영 신호로 남긴다.

### 14.3 회원 서비스 장애

이름과 전화번호 fallback을 예약 DB에서 읽지 않는다. row를 `RETRY_WAIT`로 보내고
상한이 있는 재시도를 적용한다. 상한을 소진하면 `EXHAUSTED`로 전환한다.

### 14.4 회원 탈퇴 또는 수신 동의 철회

발송 시점의 회원 상태를 적용해 `SUPPRESSED`로 종료한다. enqueue 시점의 전화번호나
동의를 사용하지 않는다.

### 14.5 잘못된 template 또는 parameter

임의 fallback 문구를 보내지 않는다. `TEMPLATE_NOT_FOUND` 또는
`TEMPLATE_PARAMETER_INVALID`로 격리하고 alert한다. template version 배포는 해당
version을 사용하는 활성 아웃박스가 없어질 때까지 제거할 수 없다.

### 14.6 worker 중복 claim 또는 lease 상실

조건부 update에 성공한 worker만 provider를 호출한다. lease owner가 바뀌었거나
만료되면 이전 worker는 종료 상태 update를 수행하지 않는다. 반복되는 lease 상실은
timeout과 lease 설정 불일치로 경보한다.

### 14.7 대규모 backlog

polling batch, worker concurrency와 provider bulkhead에 상한을 둔다.
oldest age와 pending 수를 경보하며 한 transaction에서 backlog 전체를 읽거나
무제한 coroutine을 만들지 않는다.

## 15. Migration과 rollout

1. 알림 outbox·attempt table과 index를 H2/PostgreSQL/MySQL Flyway migration으로
   additive하게 추가한다.
2. `memberId` application invariant와 기존 `patientExternalId` adapter를 추가한다.
3. legacy caller를 `OBSERVE`에서 검증하고 migration readiness gate를 통과시킨 뒤
   clinic별로 `ENFORCE`한다.
4. 예약 command transaction에서 notification outbox를 dual-write한다.
5. worker를 shadow mode로 실행해 claim 대상, member resolution과 template 검증
   결과를 확인하되 provider는 호출하지 않는다.
6. 기존 direct listener와 새 worker의 동시 발송을 막는 상호 배타 feature flag로
   1개 clinic canary를 활성화한다.
7. 최소 24시간과 1,000개 논리 알림을 관찰하고 unknown/duplicate 0건, critical
   alert 0건, oldest 활성 행 age 5분 미만, suppression reason 설명 가능을 확인한다.
8. 기준을 통과하면 알림 플랫폼 owner와 clinic owner가 다음 clinic 묶음을
   승인한다. 실패하면 provider 호출만 중단하고 enqueue와 retention은 유지한다.
9. 전체 clinic에서 같은 gate를 통과한 뒤 direct listener 발송 경로를 제거한다.
10. 기존 `NotificationHistoryTable` dual-read를 종료하고 retention 후 제거한다.

outbox·attempt 조회 API와 운영 dashboard는 tenant/clinic scope와 역할 기반
권한을 매 요청마다 검증한다. raw `memberId`, appointment ID와 parameter는
worker service account만 접근할 수 있다. 사용자 화면과 일반 운영 조회는
redacted fingerprint, stable outcome과 제한된 metadata만 반환한다.

rollback은 새 enqueue를 중단하고 direct listener를 다시 켜는 방식으로 수행하지
않는다. 그렇게 하면 rollback 시점의 commit과 listener 사이에 다시 유실 구간이
생긴다. 새 worker 발송을 중단하더라도 outbox enqueue는 유지하고, 장애를 복구한
worker가 아직 종료되지 않은 `PENDING`과 `RETRY_WAIT` backlog를 처리한다.

schema rollback은 additive table을 즉시 삭제하지 않는다. 이전 binary가 새 table을
무시할 수 있는지 확인하고, queued row 보존과 개인정보 retention을 우선한다.

### 15.1 수동 `re-notify` command와 runbook

종료 행의 개인정보를 복구하거나 상태를 되돌리는 redrive는 제공하지 않는다.
수동 `re-notify`는 권한 있는 직원이 명시한 최대 100개 appointment ID를 현재
예약 DB에서 다시 읽고, 회원 mapping·동의·연락처·template을 새로 검증해 별도
논리 알림을 만든다.

- `SUPPRESSED(CONSENT_DENIED|MEMBER_NOT_AVAILABLE|MEMBER_SCOPE_MISMATCH)`는
  대상이 아니다.
- `SENT`와 `DELIVERY_RESULT_UNKNOWN`은 provider 결과 확인과 이중 승인 없이는
  대상이 아니다.
- 실제 실행 전에 개인정보 없는 eligible/ineligible 수와 reason을 dry-run한다.
- 알림 플랫폼 전용 service account와 clinic 운영자의 이중 승인이 필요하다.
- clinic별·provider별 rate limit을 적용한다.
- 새 `eventId`, `manualGeneration`과 새 idempotency key를 사용하고 원본 종료 행의
  fingerprint만 감사 연결로 남긴다.
- 실행자, 승인자, scope, generation, 시작·중단·완료 시각과 결과 수만 감사한다.
- 중단하면 아직 enqueue하지 않은 appointment를 남기고 같은 generation으로
  재개한다.
- `SENT` 중복 차단, suppression 우회 금지, 현재 동의 재평가와 부분 실패 재개를
  테스트한다.

### 15.2 Health와 운영 소유권

notification worker의 liveness는 process와 poll loop heartbeat만 확인한다.
readiness는 schema version, DB claim/recovery, idempotency key ring을 확인하며
실패하면 새 worker traffic을 받지 않는다. 회원 서비스와 provider circuit open,
oldest age 증가, audit fingerprint 보류와 retention 실패는 readiness를 즉시
내리지 않고 degraded health와 alert로 노출한다. 단, idempotency key ring 장애는
중복 방지를 위해 enqueue도 503으로 차단한다.

key registry 설정 이름은
`appointment.notification.crypto.idempotency-key-ring`과
`appointment.notification.crypto.audit-key-ring`이다. 값은 external secret
reference이며 설정 파일에 key를 직접 넣지 않는다. Security owner가 90일마다
rotation하고 직전 key를 최대 retry 72시간과 종료 행 최대 보존 30일을 포함한
35일 overlap 동안 유지한다. emergency revoke 시 enqueue readiness를 내리고
중복 가능성을 확인한 뒤 새 active key를 배포한다. key lookup·rotation alert는
Security와 notification on-call에 함께 전달한다.

### 15.3 Schema migration runbook

각 DB migration은 DDL lock timeout, PostgreSQL concurrent index 또는 동등한
온라인 전략, MySQL online DDL 지원 여부와 H2 테스트 대체 범위를 기록한다.
staging dry-run에서 활성·종료 대표 행 수로 실행 시간과 lock을 측정한다.
old binary/new schema, new binary/old schema, new binary/new schema의 호환성
matrix와 rollback hold point를 문서화한다. schema readiness 전에는 worker,
recovery와 retention runner를 시작하지 않는다.

### 15.4 Command와 outbox writer 연결

| command 경로 | transaction 안의 알림 작업 | 핵심 입력·멱등성 | 필수 테스트 |
|---|---|---|---|
| legacy create | 생성 event enqueue 또는 legacy suppression | appointment version, channel, `CREATED` slot | commit/rollback, missing member |
| legacy status/confirm | 상태 event와 미래 reminder enqueue | 새 version, status event, reminder slot | 중복 상태 요청, reminder 선기록 |
| legacy cancel/reschedule | 취소/재배정 event, 이전 reminder suppression | 이전·새 version, `APPOINTMENT_CHANGED` | 원자 suppression과 새 reminder |
| v2 customer create | proposal/hold 정책에 맞는 enqueue | commitment/proposal version, Plan member | actor mapping 실패 |
| v2 admin direct create/confirm | 확정 event와 reminder enqueue | commitment/proposal version, Plan member | Plan scope와 중복 command |
| v2 approve/cancel/change | 상태 event와 reminder 갱신 | ETag version, proposal revision | stale ETag와 rollback |
| reminder 보정 scanner | 누락 enqueue 또는 window-missed suppression | appointment version, reminder slot | downtime 전·중·후 |

## 16. 테스트 전략

### 16.1 원자성과 멱등성

- 예약 변경과 outbox insert가 함께 commit되는지 확인한다.
- outbox insert 실패 시 예약 변경도 rollback되는지 확인한다.
- 같은 idempotency key enqueue가 한 row만 만드는지 확인한다.
- 성공 row와 같은 key를 worker가 다시 발송하지 않는지 확인한다.

### 16.2 회원·template 경계

- self-service actor의 `patientSubjectId`가 server-side `memberId`로 해석되는지
  확인한다.
- 직원 예약의 회원 scope 불일치를 거절하는지 확인한다.
- 이름, 전화번호, locale, consent가 outbox JSON에 없는지 확인한다.
- template별 허용 field 밖의 parameter를 거절하는지 확인한다.
- 알 수 없는 envelope schema와 parameter discriminator를 거절하는지 확인한다.
- channel별 escaping과 허용하지 않은 URI/control character를 거절하는지
  확인한다.
- 회원 조회 실패, 탈퇴, 연락처 없음과 동의 거부를 각각 retry 또는 suppression으로
  분류하는지 확인한다.

### 16.3 retry와 lifecycle

- retryable failure가 `RETRY_WAIT`와 다음 시도 시각을 기록하는지 확인한다.
- 최대 시도와 최대 경과 시간을 소진하면 `EXHAUSTED`가 되는지 확인한다.
- lease가 만료된 `PROCESSING` row를 한 worker만 복구하는지 확인한다.
- 취소, 예외와 shutdown 이후 DB transaction이나 coroutine이 남지 않는지 확인한다.
- circuit open과 durable retry가 attempt 폭증을 만들지 않는지 확인한다.
- stale worker가 자신의 attempt만 `LEASE_LOST`로 끝내고 새 owner의 row를
  변경하지 않는지 확인한다.
- `re-notify` dry-run, `SENT` 제외, generation fencing과 중단 후 재개를 확인한다.

### 16.4 concurrency와 database

- 여러 worker가 같은 row를 동시에 처리하지 않는지 반복 검증한다.
- provider 호출 중 DB transaction이 열려 있지 않은지 확인한다.
- 크기가 제한된 batch가 backlog 전체를 materialize하지 않는지 확인한다.
- H2, PostgreSQL과 MySQL에서 claim CAS, unique index와 migration을 검증한다.
- 만료된 `PROCESSING` row가 recovery query로 정지 없이 복구되는지 확인한다.
- app clock skew가 lease 조기 회수나 retry starvation을 만들지 않는지 확인한다.
- scheduler가 due 전·due window·window 후에 중단된 경우 reminder가 각각
  선기록, catch-up 또는 suppression되는지 확인한다.
- 한 대형 clinic backlog가 작은 clinic의 claim을 고갈시키지 않는지 확인한다.
- Testcontainers 검증은 repository 지침에 따라 singleton launcher를 사용하고
  database별로 순차 실행한다.

### 16.5 개인정보와 운영

- history, outbox, log와 metric에 raw 이름·전화번호·본문이 없는지 확인한다.
- 종료 행 비식별화 뒤 `memberId`와 parameter 본문이 제거되는지 확인한다.
- 운영 조회가 raw `memberId`, appointment ID와 parameter를 반환하지 않는지
  확인한다.
- 다른 tenant/clinic의 직원이 알림 상태를 조회하거나 `re-notify`할 수 없고,
  일반 직원·환자·worker 외 service account가 수동 재알림을 실행할 수 없는지
  확인한다.
- idempotency/audit key rotation의 active·previous lookup과 key 장애 시
  fail-closed 동작을 확인한다.
- provider reference가 opaque-ID 규칙을 어기면 raw 저장하지 않는지 확인한다.
- metric label에 member, appointment, tenant와 clinic ID가 없는지 확인한다.
- `EXHAUSTED`, oldest age와 lease recovery signal을 운영자가 확인할 수 있는지
  검증한다.
- legacy `memberId` 누락 row가 fallback 발송되지 않는지 확인한다.

## 17. 수용 기준

1. 예약 상태 변경과 알림 outbox가 같은 transaction에서 commit 또는 rollback된다.
2. provider 실패가 durable attempt와 안정적인 오류 코드로 남는다.
3. retry는 최대 시도와 최대 경과 시간 안에서 동작한다.
4. 성공한 idempotency key는 다시 발송되지 않는다.
5. 다중 worker와 lease 복구 상황에서 같은 row를 동시에 처리하지 않는다.
6. outbox 수신자 정보는 `memberId`뿐이며 이름과 전화번호는 회원 DB에서 조회한다.
7. parameterized template은 key/version과 typed parameter allowlist를 사용한다.
8. 이름, raw 연락처, 완성된 본문과 원문 예외가 outbox·history·log·metric에 없다.
9. 모든 신규 예약은 검증된 `memberId`를 가진다.
10. `memberId`가 없는 기존 예약은 발송 가능한 outbox를 만들지 않고 같은
    transaction의 개인정보 없는 종료 아웃박스 행에
    `MEMBER_ID_MISSING_LEGACY`를 기록해 자동 발송에서 제외한다.
11. 회원 조회 장애는 retry하고 탈퇴·연락처 없음·동의 거부는 suppression한다.
12. `EXHAUSTED`, retry, lag와 oldest age를 metric·dashboard·alert로 관측할 수 있다.
13. H2, PostgreSQL과 MySQL에서 migration과 claim lifecycle이 통과한다.
14. direct Spring listener가 알림 전달의 기준 경로로 남지 않는다.
15. 대표적인 대규모 활성·종료 backlog에서 실행 계획과 합성 부하를 검증해
    poll당 조회 row 수, claim latency, in-flight worker와 정리 작업에 상한이 있음을
    확인한다.
16. retention과 redaction은 indexed 종료 시각과 처리 상태 순서로 크기가 제한된
    page를 사용하고 짧은 transaction과 backpressure로 claim 처리를 고갈시키지
    않는다.
17. legacy/v2/customer/admin endpoint별 `memberId` 해석과 공개 오류 계약이
    OpenAPI에 반영되고 요청·거절 예시가 검증된다.
18. `re-notify`, alert, health, key rotation과 multi-database migration runbook이
    실제 설정 이름과 운영 owner를 포함한다.

## 18. 완료 조건

- 승인된 개인정보·회원 식별자 경계가 코드와 테스트에 반영된다.
- 알림 outbox와 예약 변경의 원자성이 검증된다.
- retry, suppression, exhaustion과 lease recovery 테스트가 통과한다.
- 신규 예약의 `memberId` 필수 정책과 legacy 제외 정책이 검증된다.
- parameterized template이 이름과 연락처를 회원 DB에서 채운다.
- 기존 raw `recipient`, `payloadJson`, `errorMessage` 중심 이력 계약이 제거된다.
- module-scoped test와 multi-database migration 검증이 통과한다.
- README와 OpenAPI에 self-service 성공·거절, staff 대리 예약, legacy suppression,
  retry/`EXHAUSTED` 조회 예시를 추가한다.
- 운영 runbook에 alert 대응, canary 중단, `re-notify`, key rotation, migration과
  reminder miss 후속 조치를 추가한다.
- spec/plan review와 최종 code review에서 P0=0, P1=0이다.
- Issue #172의 실패 관측, 상한이 있는 재시도, dead-letter signal과 성공 dedupe 기준을
  충족한다.

## 19. 설계 검토 기록

| 관점 | 최초 결과 | 최종 결과 | 처리 |
|---|---|---|---|
| 성능 | P1=3, P2=4 | P0=0, P1=0 | claim index, 병원별 공정성, 외부 의존성 보호와 부하 검증 반영 |
| 안정성 | P1=4, P2=3 | P0=0, P1=0 | lease 복구·fencing, DB 시각, reminder 선기록 반영 |
| 보안·개인정보 | P1=2, P2=3, P3=2 | P0=0, P1=0 | 키 교체, 조회 권한, 즉시 비식별화와 입력 escaping 반영 |
| 운영 | P1=2, P2=4 | P0=0, P1=0 | alert 기준, canary, health, migration과 `re-notify` runbook 반영 |
| 개발자·API | P1=3, P2=4 | P0=0, P1=0 | 닫힌 code, sealed 행, key-ring port와 command 연결표 반영 |
| 사용자·호출자 | P1=2, P2=3, P3=1 | P0=0, P1=0 | endpoint 전환, 공개 오류, 직원 상태와 migration gate 반영 |

caller-facing OpenAPI와 runbook 예시는 구현과 함께 실제 DTO·endpoint·오류 registry를
기준으로 작성해야 하므로 구현 계획의 문서 작업으로 배치한다. 설계 문서만으로
가상 응답 예시를 먼저 확정하지 않는다.

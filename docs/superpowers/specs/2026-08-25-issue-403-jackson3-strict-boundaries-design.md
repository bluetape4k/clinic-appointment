# Issue #403 Jackson3 strict 경계 설계

## 목표

`clinic-appointment`의 라이브러리 예제가 요청 본문과 아웃박스 JSON을
`bluetape4k` 생태계의 안전한 조합으로 처리하도록 경계를 보강한다. 핵심은
Spring이 `JsonNode`를 materialize하기 전에 scheduling-policy 요청의 원문을
검사하고, `appointment-event`가 permissive한 공용 기본 mapper에 의존하지
않도록 모듈 소유 strict mapper를 사용하는 것이다.

## 현재 근거와 범위

- `CatalogPayloadSizeFilter`는 policy envelope의 바이트 상한을 이미
  materialization 전에 검사하지만, 중복 key와 trailing token을 검사하지
  않는다.
- `NotificationOutboxCodec`와 `WaitlistNotificationOutboxCodec`는
  `jsonMapper {}`로 mapper를 직접 만들지만 duplicate detection,
  stream constraint, trailing-token 계약이 명시되지 않았다.
- `appointment-messaging`의 caller-owned mapper는 strict 설정을 이미
  갖추고 있으므로 이번 변경에서 재사용 경계를 바꾸지 않는다.
- API의 직접 `bluetape4k-jackson3` 의존성은 소스 import가 없고
  `bluetape4k-dependencies`를 통해 compile classpath에 남는다. 의존성
  insight로 이를 확인한 뒤 직접 의존성을 제거한다. 다만 API가 notification과
  messaging을 조립하고 messaging이 `bluetape4k-kafka4`를 사용하므로
  Jackson3·JSON·Jakarta JSON은 production runtime 전이 의존성으로 유지한다.
  lockfile에는 이 runtime 경로를 보존하고 compile classpath 직접 유입이
  사라졌음을 별도로 증명한다.

이번 구현은 Issue #403의 P1 범위만 다룬다. catalog 요청의 기존 의미, 정책
DTO의 unknown-field 계약, messaging mapper의 소유권은 변경하지 않는다.

## 설계

### API raw ingress

기존 `CatalogPayloadSizeFilter`가 policy codec이 소유한
`SchedulingPolicyPayloadCodec.MAX_PAYLOAD_BYTES`에 envelope overhead를 더한
bounded byte array를 Spring chain에 넘기기 전에 Jackson3 `JsonFactory`의
`StreamReadFeature.STRICT_DUPLICATE_DETECTION`으로 JSON 문서를 한 번
검사한다. `FAIL_ON_TRAILING_TOKENS`도 같은 boundary mapper에 설정한다.
필터가 body를 다시 감싸므로 Spring `@RequestBody` materialization은
동일한 원문을 읽는다.

크기 초과와 strict parse 실패는 모두 기존의
`POLICY_PAYLOAD_INVALID` safe envelope로 반환한다. 로그와 응답에는 원문
payload나 parser 상세를 넣지 않는다. catalog sync path는 현재의 바이트 상한
동작만 유지한다.

### Event strict JSON

`appointment-event` 내부에 `AppointmentEventJson` factory를 둔다. factory는
기존 `appointment-messaging`의 검증된 Jackson3 조합을 모듈 경계 안에서
재사용한다.

- Kotlin `StrictNullChecks`
- unknown property, primitive null, missing creator property 거부
- nullable contract field는 명시적으로 허용하고 나머지 null은 domain codec이 검증
- tree key와 streaming key의 중복 거부
- trailing token 거부
- bounded nesting/document/string/name constraints
- map entry key 정렬을 통한 canonical serialization
- UTF-8 직렬화 결과와 입력 원문의 64KiB byte 상한

두 outbox codec은 이 factory 하나를 공유하며 기존의 domain validation과
오류 타입/메시지는 유지한다. 예외 원문은 저장하거나 반환하지 않는다.
waitlist envelope의 `eventId`·`idempotencyKey`는 저장 column 길이와 같은
상한을 encode 전에 검증하고, 두 codec 모두 canonical wire JSON golden을
고정한다.

### 검증 계약

- API: top-level과 nested duplicate key, trailing token, 기존 envelope size
  guard가 모두 controller 진입 전에 거부되는지 확인한다.
- Event: notification/waitlist codec 각각에 duplicate, trailing token,
  oversized/string constraint 실패와 deterministic/canonical encode golden
  검사를 추가한다. UTF-8 다중 바이트 입력도 byte 상한 전에 거부한다.
- 기존 API/event/messaging 모듈의 targeted 및 full test를 순차 실행한다.

## 위험과 제외

- strict parser를 전역 Spring mapper로 교체하지 않는다. 다른 API DTO의
  호환성에 영향을 줄 수 있기 때문이다.
- event document limit은 outbox payload의 bounded contract로 고정한다.
  현재 DTO가 허용하는 정상 payload보다 작지 않도록 golden/fixture로 확인한다.
- messaging 모듈은 이미 caller-owned strict mapper를 사용하므로 중복
  abstraction을 만들지 않는다.

## 완료 조건

1. API raw boundary가 duplicate/trailing/size를 safe error로 거부한다.
2. 두 event codec이 shared strict factory와 hostile/golden 테스트를 사용한다.
3. API 직접 `bluetape4k-jackson3` 의존성 제거 후 compile classpath가
   `bluetape4k-dependencies`를 통해 정상 해석되고, messaging→kafka4
   runtime 전이 경로는 lockfile에 의도적으로 남는다.
4. 모듈 테스트와 7-Tier review에서 P0/P1이 0이다.

## Writer gate

- SPW-01: 현재 Issue #403와 dependency insight 증거를 기준으로 작성했다.
- SPW-02: 목표, 설계, 제외 범위, 완료 조건을 분리했다.
- SPW-03: `payload`, `mapper`, `duplicate key` 등 식별자와 명령은 원문을
  보존했다.
- SPW-04: `materialization`, `저장`, `오류 반환` 경계를 섞지 않았다.
- SPW-05: 한국어 자연스러움·용어 검토를 마치고 변경 파일에 용어 감사를
  실행한다.

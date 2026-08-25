# Issue #402 Kafka4 adapter lesson

## 상황

`appointment-messaging`의 publisher가 Spring Kafka `KafkaTemplate.send`를
직접 호출하고 있었다. 테스트 topic·group 이름도 UUID 문자열에 의존했다.

## 결정

- Spring Kafka 발송은 `io.bluetape4k.kafka.spring.suspendSend`로 재사용하고,
  `Deferred.asCompletableFuture()`로 기존 `CompletionStage` 계약에 연결한다.
- publisher가 `SupervisorJob`과 dispatcher를 소유하고 `close()`에서 취소하도록 해
  caller 취소·publisher close·underlying Kafka future의 lifecycle을 같은 경계에 둔다.
- 테스트 전용 문자열 suffix는 `Base58.randomString(8)`을 사용한다. UUID-valued
  domain identity는 변경하지 않는다.
- `KafkaCodecs.String`은 `bluetape4k.kafka.codec.value.type` header를 추가하므로
  기존 StringSerializer wire를 유지해야 하는 이번 경로에는 적용하지 않는다.

## 결과와 검증

- publisher 단위 테스트 `5 passing`
- Kafka publisher integration `1 passing`
- Kafka consumer integration `2 passing`
- `:appointment-messaging:test` `130 passing`
- payload round-trip과 type header 부재를 실제 consumer record로 확인했다.

## 다음 guard

Kafka publisher 변경은 helper 재사용 여부만 보지 말고 `CompletionStage` cancellation,
`close()` ownership, serializer header 계약을 함께 검증한다. 테스트 isolation에는
UUID를 무조건 사용하지 말고 문자열 suffix인지 domain identity인지 먼저 구분한다.

## 문서 작성 점검

- [x] SPW-01: 상황·결정·결과·검증·surprise를 source와 테스트에서 고정했다.
- [x] SPW-02: context, decision, outcome, future guard 구조를 충족했다.
- [x] SPW-03: 한국어 기술 문체와 code token을 보존했다.
- [x] SPW-04: `SpringKafkaAppointmentPublisher.kt`와 integration test를 read-back했다.
- [x] SPW-05: 최종 lesson을 다시 읽고 reusable guard를 확인했다.

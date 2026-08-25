# Issue #402 Kafka4 adapter 7-Tier 검토

검토일: 2026-08-25
검토 branch: `feat/issue-402-kafka4-adapter`
선행 child base: `848c3a40b210e85672b48a4ecee68feb2f3e7f0a`
현재 source tip: `65ee2391b78af628bc33d3eedf29a454d7232356`

## 검토 범위

- `appointment-messaging`의 `SpringKafkaAppointmentPublisher`
- publisher lifecycle 단위 테스트와 Kafka singleton integration test
- Kafka consumer integration test의 테스트 전용 identifier 생성
- `appointment-messaging/README.md`, `README.ko.md`
- 제외: outbox schema·claim·transaction, readiness algorithm, payload schema,
  `KafkaCodecs.String` 및 `SuspendKafkaProducerTemplate` 도입

## 재사용 판단

| 후보 | 판정 | 근거 |
|---|---|---|
| `io.bluetape4k.kafka.spring.suspendSend` | 채택 | Spring `KafkaOperations`의 기존 `send(...).await()` extension이며 `SendResult`와 cancellation을 제공한다. |
| `Deferred.asCompletableFuture()` | 채택 | kotlinx.coroutines의 표준 bridge가 returned future cancellation을 coroutine에 전달한다. 수동 callback bridge보다 race surface가 작다. |
| `KafkaCodecs.String` | 보류 | 기본 `bluetape4k.kafka.codec.value.type` header가 기존 StringSerializer wire를 변경한다. integration test에서 header 부재를 고정했다. |
| `SuspendKafkaProducerTemplate` | 보류 | Reactor `KafkaSender`와 자체 scope를 소유하는 별도 lifecycle이므로 단일 adapter 범위를 넓히지 않았다. |
| `Base58.randomString(8)` | 채택 | 테스트용 topic·group suffix에 사용한다. UUID-valued domain identity는 유지한다. |

## 모듈별 7-Tier 판정

| Tier | 판정 | 현재 근거 |
|---|---|---|
| 성능 | PASS | producer 경로의 Kafka client와 serialization은 유지하고, coroutine bridge allocation만 추가했다. 별도 latency 수치는 주장하지 않는다. |
| 안정성/수명주기 | PASS | `SupervisorJob`이 publisher 소유 scope를 만들고 `close()`에서 취소한다. returned stage 취소와 broker future 취소를 단위 테스트로 확인했다. |
| 보안/데이터 경계 | PASS | topic·key·payload를 변경하지 않고 StringSerializer를 유지한다. type header 부재를 실제 consumer record에서 검증했다. |
| 운영/관측성 | PASS | KafkaAdmin non-creating readiness, failure code, timeout, outbox relay 경계를 변경하지 않았다. |
| 개발자/API | PASS | public `CompletionStage<*>`와 기존 constructor 호출을 유지하고 Korean KDoc/README에 helper 선택 이유를 기록했다. |
| 사용자/호출자 | PASS | broker ACK, failure, timeout, caller cancellation, publisher close의 반환 의미를 테스트로 고정했다. |
| 통합/테스트/빌드 | PASS | publisher 5개, publisher Kafka integration 1개, consumer integration 2개, 전체 messaging 130개가 통과했다. |

판정: `P0=0`, `P1=0`, `P2=0`, `P3=0`.

## Kotlin checklist

| 항목 | 판정 | 근거 |
|---|---|---|
| KT-01 | PASS | `bluetape-kotlin-patterns`와 testing/Spring reference를 읽고 적용했다. |
| KT-02 | PASS | sibling kafka4 extension, 현재 publisher/caller, README, test launcher를 source에서 확인했다. |
| KT-03 | PASS | `!!`, blocking event-loop call, swallowed cancellation, public contract drift가 없다. |
| KT-04 | PASS | targeted compile/test, sequential Testcontainers integration, full module test, `git diff --check`를 수행했다. |
| KT-05 | PASS | applicable Kotlin rows `11/11`, triggered testing rows `5/5`, Spring auto-configuration rows는 source가 auto-configuration phase를 변경하지 않아 `N/A`로 기록한다. |

## Fresh verification

| 명령 | 결과 |
|---|---|
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test --tests '*SpringKafkaAppointmentPublisherTest'` | `BUILD SUCCESSFUL`, `5 passing` |
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test --tests '*AppointmentMessagingKafkaIntegrationTest'` | `BUILD SUCCESSFUL`, `1 passing`; broker ACK·payload round-trip·type header 부재 |
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test --tests '*AppointmentKafkaConsumerIntegrationTest'` | `BUILD SUCCESSFUL`, `2 passing`; Base58 topic/group suffix |
| `./gradlew --no-daemon --no-configuration-cache :appointment-messaging:test` | `BUILD SUCCESSFUL`, `130 passing` |
| `git diff --check` | `PASS` |
| `rg 'UUID\.randomUUID|assertThrows'` 대상 publisher/Kafka tests | 결과 없음 |
| `colima status`, `docker context show`, `docker info` | Colima 실행 중, `default`, Docker `28.4.0` |

## Finding disposition

- P0/P1: 없음.
- P2: 별도 latency benchmark는 이 child 범위에 없으므로 수치를 주장하지 않는다.
- P3: 없음.
- 기존 outbox relay의 retry/backpressure 테스트는 production algorithm을 변경하지 않았으므로
  전체 module test에 포함된 기존 증거로 확인하고 새 adapter가 재정의하지 않는다.

## PR 전 결론

`PASS` — 현재 child의 implementation/review gate를 통과했다. branch는 선행 #393
head에서 한 commit만 앞서며(`0 1`), 아직 PR 생성·CI·merge는 수행하지 않았다.

## 문서 작성 점검

- [x] SPW-01: 범위, source tip, 선행 base, 제외 항목과 증거를 고정했다.
- [x] SPW-02: review scope, 7-Tier 판정, Kotlin checklist, findings, gaps와 결론을 포함했다.
- [x] SPW-03: 한국어 기술 문체와 정확한 API·명령·식별자를 유지했다.
- [x] SPW-04: source·test·README·실행 결과를 대조했다.
- [x] SPW-05: 최종 Markdown read-back과 P0/P1 count를 확인했다.

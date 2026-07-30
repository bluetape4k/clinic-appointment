# appointment-event

[English](README.md) | [한국어](README.ko.md)

Spring `ApplicationEvent` 기반 도메인 이벤트 발행/구독 + 이벤트 로그 DB 저장.

## 책임

- **하는 것**: 도메인 이벤트 타입 정의, 이벤트 발행, 이벤트 로그 저장, 신뢰된 구매 이벤트를 예약 플랜으로 수렴
- **하지 않는 것**: 알림 직접 발송과 appointment-plan outbox 직접 발행

## 구매 이벤트 수렴

`PurchaseCompletedIngress`는 환자 참조를 보호하기 전에 producer, signature, issuer,
audience, payload hash, replay window, payload 제한을 검증합니다. `WRITE` 모드의
`PurchaseCompletedHandler`는 동일 transaction에서 inbox를 선점하고, 불변 플랜을
생성하고, 대기 상태의 `AppointmentPlanCreated` outbox 한 건을 기록합니다.

중복 event ID와 동일 구매는 하나로 수렴합니다. aggregate version gap은 제한된
백오프로 재시도하고 5회째 격리됩니다. `SHADOW`는 쓰기 없이 평가합니다. outbox
발행·ack·retry/DLQ·alert 책임을 가진 외부 전송 배포가 완성되기 전에는 운영
`WRITE`를 허용하지 않습니다. 방문 확정 약속 런북은 이 금지를 해제하기 전에 필요한
사전 운영 훈련과 증거를 정의할 뿐, 그 문서만으로 `WRITE`를 승인하지 않습니다.

## 외부 예약 사실

예약서비스는 상품·구매·시술 이행의 소유권을 가져오지 않고 다음 사실만 수신합니다.

- `VisitPlanningEventIngress`와 `VisitPlanningEventHandler`는 불변 패키지 실행 BOM을
  검증하고 아직 진행하지 않은 미래 Plan 작업만 생성·개정합니다.
- `ProductVersionMigrationHandler`는 신뢰된 상품 version mapping과 고객 동의를
  확인하고 완료 시술 provenance를 보존합니다.
- `ProductVersionMigrationDeclinedHandler`는 현재 version을 유지한 채 고객 거부 운영
  예외를 기록합니다.
- `TreatmentFulfillmentHandler`는 정확한 item의 완료·부분 이행을 반영하고
  `BLOCKING` 미래 의존 항목만 dirty-set으로 만듭니다.
- `ExternalFactEventConsumer`는 닫힌 event type 허용목록만 routing하고 제한된
  redacted 실패를 격리합니다. 상품·구매·동의·환불·이행의 상태 변경 책임은 원천 서비스에
  그대로 남습니다.

## 이벤트 타입

```kotlin
sealed class AppointmentDomainEvent : ApplicationEvent {
    data class Created(val appointmentId: Long, val clinicId: Long)
    data class StatusChanged(val appointmentId: Long, val clinicId: Long,
                             val fromState: String, val toState: String, val reason: String?)
    data class Cancelled(val appointmentId: Long, val clinicId: Long, val reason: String)
    data class Rescheduled(val originalId: Long, val newId: Long, val clinicId: Long)
}
```

## 발행 패턴

```kotlin
// 발행 (appointment-api, appointment-core에서 사용)
eventPublisher.publishEvent(AppointmentDomainEvent.Created(id, clinicId))

// 구독
@EventListener
fun on(event: AppointmentDomainEvent.Created) { ... }
```

## 핵심 클래스

| 클래스 | 역할 |
|--------|------|
| `AppointmentDomainEvent` | 이벤트 sealed class — Created, StatusChanged, Cancelled, Rescheduled |
| `AppointmentEventLogger` | `@EventListener` — 모든 이벤트를 `AppointmentEventLogs` 테이블에 저장 |
| `AppointmentEventLogRecord` | 이벤트 로그 DTO |
| `AppointmentEventLogs` | Exposed 테이블 — event_type, appointment_id, payload_json, occurred_at |
| `PurchaseCompletedIngress` | 신뢰, 입력 제한, version proof, 환자 참조 보호 경계 |
| `PurchaseCompletedHandler` | 중복·gap 판정을 포함한 inbox/plan/outbox 원자 수렴 |
| `PurchaseEventRedriveService` | 전체 identity 확인, 행위자/사유, release 승인 참조, append-only audit를 강제하는 exact-quarantine dry-run·승인 redrive |
| `VisitPlanningEventIngress` | 엄격한 패키지 실행 payload decoding, 상한, 신뢰 검증 |
| `VisitPlanningEventHandler` | 불변 실행 Plan 생성과 미래 작업만 대상으로 하는 revision |
| `ProductVersionMigrationHandler` | 신뢰된 원천과 동의에 결합된 상품 version 전환 |
| `ProductVersionMigrationDeclinedHandler` | 활성 version을 바꾸지 않는 전환 거부 수렴 |
| `TreatmentFulfillmentHandler` | 정확한 이행 사실, 부분 완료, `BLOCKING` dirty-set 전파 |
| `ExternalFactEventConsumer` | 전환·거부·이행 사실의 닫힌 routing 경계 |

## 이벤트 발행/구독 흐름

![예약 이벤트 아키텍처 다이어그램](../docs/images/readme-diagrams/appointment-event-architecture-01.png)

## 의존성

- **내부**: `appointment-core`
- **외부**: Spring Context

## 테스트 실행

```bash
./gradlew :appointment-event:test
```

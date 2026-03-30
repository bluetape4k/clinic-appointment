# appointment-event

Spring `ApplicationEvent` 기반 도메인 이벤트 발행/구독 + 이벤트 로그 DB 저장.

## 책임

- **하는 것**: 도메인 이벤트 타입 정의, 이벤트 발행, 이벤트 로그 Exposed 테이블 저장
- **하지 않는 것**: 알림 발송 없음 (알림은 `appointment-notification`이 이벤트 구독)

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

## 의존성

- **내부**: `appointment-core`
- **외부**: Spring Context

## 테스트 실행

```bash
./gradlew :appointment-event:test
```

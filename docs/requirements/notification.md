# 알림 모듈 설계

**모듈**: `appointment-notification`
**의존**: `appointment-core`, `appointment-event`

## 개요

예약 이벤트(생성/확정/취소/재배정) 발생 시 알림 발송 + 예약 전날/당일 리마인더.
고가용성(HA) 구성으로 다중 인스턴스에서 단일 노드만 발송 보장.

## 알림 채널 인터페이스

```kotlin
interface NotificationChannel {
    val channelType: String   // "DUMMY", "EMAIL", "SMS", "PUSH"

    fun sendCreated(appointment: AppointmentRecord)
    fun sendConfirmed(appointment: AppointmentRecord)
    fun sendCancelled(appointment: AppointmentRecord, reason: String?)
    fun sendRescheduled(original: AppointmentRecord, newAppointment: AppointmentRecord)
    fun sendReminder(appointment: AppointmentRecord, reminderType: ReminderType)
}

enum class ReminderType { DAY_BEFORE, SAME_DAY }
```

### 현재 구현체

| 구현체 | channelType | 동작 |
|--------|------------|------|
| `DummyNotificationChannel` | `DUMMY` | 로그 출력 + `NotificationHistory` DB 저장, 항상 SUCCESS |
| `ResilientNotificationChannel` | (위임) | Resilience4j CircuitBreaker/Retry/Bulkhead 래핑 |


## 알림 이력 테이블 (`scheduling_notification_history`)

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `id` | Long PK | — |
| `appointment_id` | Long FK | 예약 ID |
| `channel_type` | String | DUMMY, EMAIL, SMS, PUSH |
| `event_type` | String | CREATED, CONFIRMED, CANCELLED, RESCHEDULED, REMINDER_DAY_BEFORE, REMINDER_SAME_DAY |
| `recipient` | String? | 환자 연락처 |
| `payload_json` | Text | 발송 페이로드 |
| `status` | String | SUCCESS, FAILED |
| `error_message` | String? | 실패 시 오류 메시지 |
| `created_at` | Timestamp | — |

## HA 구성 — Redis Leader Election

다중 인스턴스 배포 시 한 노드만 스케줄러 실행:

```kotlin
// AppointmentReminderScheduler
@Scheduled(fixedRate = 3_600_000)  // 1시간 간격
fun sendReminders() {
    if (!leaderElection.isLeader()) return   // leader가 아니면 skip
    // 내일/오늘 CONFIRMED 예약 조회 → 중복 방지(NotificationHistory) → 발송
}
```

- `bluetape4k-leader` 라이브러리 — Redis SETNX 기반 분산 락
- Leader 변경 시 다음 스케줄 주기에 자동 인계

## Resilience4j 설정

`NotificationResilienceProperties`로 설정:

```yaml
scheduling:
  notification:
    resilience:
      circuit-breaker:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
      retry:
        max-attempts: 3
        wait-duration: 1s
      bulkhead:
        max-concurrent-calls: 10
```

## 알림 활성화 설정

```yaml
scheduling:
  notification:
    enabled: true
    events:
      created: true
      confirmed: true
      cancelled: true
      rescheduled: true
    reminder:
      enabled: true
      day-before: true
      same-day: true
      same-day-hours-before: 2
```

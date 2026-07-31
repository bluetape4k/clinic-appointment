# appointment-notification

[English](README.md) | [한국어](README.ko.md)

커밋된 예약 알림 outbox를 안정적으로 발송하는 실행 모듈입니다. 데이터베이스
lease로 작업을 선점하고, 발송 직전에 회원의 최신 알림 프로필을 조회한 다음,
버전이 지정된 템플릿을 렌더링합니다. 외부 provider 호출에는 실행 시간이 제한된
Resilience4j 정책을 적용합니다.

## 책임

- **하는 일**: 공정한 데이터베이스 선점, 만료 lease 복구와 fencing, 발송 시점
  회원 프로필 조회, 타입이 지정된 템플릿 렌더링, provider 장애 격리, 종료
  데이터 최소화, 보존 기간에 따른 제한된 단위의 삭제
- **하지 않는 일**: Spring 예약 이벤트에서 직접 발송, 예약 CRUD, outbox에
  이름·연락처·렌더링 본문·provider payload·원본 예외 메시지 저장

## 핵심 클래스

| 클래스 | 역할 |
|---|---|
| `NotificationOutboxDispatcher` | 발송 대상을 공정하게 선점하고 전체 및 병원별 동시성을 제한합니다. |
| `NotificationOutboxWorker` | fencing된 완료·재시도·소진 처리와 만료 lease 복구를 수행합니다. |
| `NotificationOutboxWorkStore` | outbox 작업의 트랜잭션 기반 데이터베이스 경계를 정의합니다. |
| `MemberNotificationProfileResolver` | 실행 시간이 제한된 정책 안에서 최신 연락처·언어·동의를 조회합니다. |
| `NotificationTemplateCatalog` | 허용된 템플릿 키·버전·채널별 정의를 관리합니다. |
| `NotificationTemplateRenderer` | 타입이 지정된 매개변수와 실행 시점 프로필을 검증 실패 시 차단하는 방식으로 렌더링합니다. |
| `NotificationChannel` | provider가 처리할 수 있는 요청을 발송하고 개인정보가 없는 결과를 반환합니다. |
| `ResilientNotificationChannel` | 코루틴 취소를 재시도하지 않으면서 CircuitBreaker·Retry·Bulkhead를 적용합니다. |
| `NotificationRetentionRunner` | 상태별 보존 기간에 따라 종료 레코드를 제한된 단위로 삭제합니다. |
| `NotificationSchemaReadiness` | 필수 스키마·인덱스·암호화 참조가 없으면 readiness를 실패시킵니다. |

## 발송 흐름

1. 예약 트랜잭션이 회원·예약 식별자와 타입이 지정된 템플릿 매개변수만 담은
   최소 outbox 레코드를 커밋합니다.
2. dispatcher가 발송 대상을 공정하게 찾고 데이터베이스 lease와 fencing
   token으로 레코드를 선점합니다.
3. 발송 adapter가 회원의 최신 연락처·언어·동의를 조회합니다. 연락처가 없거나
   동의를 철회한 경우에는 발송하지 않고 억제 처리합니다.
4. renderer가 승인된 템플릿 버전을 선택해 provider용 본문을 메모리에서
   생성합니다.
5. channel이 결정적인 provider 멱등성 키와 함께 발송합니다.
6. worker는 fencing된 종료 결과 또는 제한된 재시도 결정만 저장합니다.
   종료 레코드는 이후 retention runner가 삭제합니다.

데이터베이스 lease와 fencing token이 발송 정합성의 기준입니다. Redis 리더
선출은 향후 리마인더 복구 trigger에만 사용하며, outbox의 안전한 병렬 발송에는
필요하지 않습니다.

## 개인정보 및 신뢰성 경계

- 연락처와 동의 정보는 회원 서비스가 소유하며 발송 직전에 조회합니다.
- 템플릿 매개변수는 임의의 map이나 저장된 렌더링 문자열이 아니라 제한된 도메인
  타입입니다.
- 실행 객체의 문자열 표현은 민감 정보를 가리고, 저장하는 실패 정보는 provider
  메시지나 stack trace 대신 안정적인 코드만 사용합니다.
- 재시도 횟수·총 경과 시간·lease당 provider 시도 횟수·lease 시간·동시성을
  하나의 제한된 설정으로 함께 검증합니다.
- 코루틴 취소는 provider를 한 번만 호출한 뒤 그대로 전파하며 provider 실패로
  변환하지 않습니다.

## 설정 예시

```yaml
clinic:
  notification:
    enabled: true
    worker:
      enabled: true
      max-attempts: 6
      max-elapsed: 24h
      provider-attempts-per-lease: 1
      catch-up-window: 30m
      lease-duration: 60s
      provider-timeout: 30s
      batch-size: 100
      global-concurrency: 4
      per-clinic-concurrency: 1
      db-claim-max-concurrency: 4
      member-resolver-max-concurrency: 4
      member-resolver-timeout: 5s
      member-resolver-rate-limit-per-second: 100
      member-resolver-circuit-breaker-failure-rate-threshold: 50
      channels:
        dummy:
          provider-max-concurrency: 4
          bulkhead-max-concurrent-calls: 4
          provider-timeout: 30s
          rate-limit-per-second: 100
          circuit-breaker-failure-rate-threshold: 50
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

lease가 제한된 provider 호출 시간을 감당하지 못하거나 worker 동시성이
데이터베이스·회원 조회·provider 용량을 초과하면 시작 단계에서 설정을
거부합니다.

## 의존성

- **내부**: `appointment-core`, `appointment-event`
- **외부**: Exposed JDBC, Resilience4j, Lettuce, `bluetape4k-leader`

## 테스트 실행

```bash
./gradlew :appointment-notification:test
```

## 설계 문서

- [내구성 알림 outbox 설계](../docs/superpowers/specs/2026-07-31-issue-172-notification-outbox-design.md)
- [구현 계획](../docs/superpowers/plans/2026-07-31-issue-172-notification-outbox-plan.md)

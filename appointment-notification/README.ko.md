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
- **하지 않는 일**: 예약 CRUD, outbox에 이름·연락처·렌더링 본문·provider
  payload·원본 예외 메시지 저장. 전환 기간에는 Spring 예약 이벤트 listener가
  정확히 같은 outbox 행을 선점해 동일한 개인정보 보호 발송 절차를 실행할 수 있지만,
  별도 원본 발송 경로나 이력 저장소를 만들지는 않습니다.

## 핵심 클래스

| 클래스 | 역할 |
|---|---|
| `NotificationOutboxDispatcher` | 발송 대상을 공정하게 선점하고 전체 및 병원별 동시성을 제한합니다. |
| `NotificationOutboxSchedulingRunner` | 애플리케이션 준비 직후와 worker 주기마다 dispatcher를 실행합니다. |
| `NotificationObservationSchedulingRunner` | worker와 분리된 낮은 빈도로 상한 있는 관측 snapshot을 갱신합니다. |
| `NotificationOutboxWorker` | fencing된 완료·재시도·소진 처리와 만료 lease 복구를 수행합니다. |
| `NotificationOutboxWorkStore` | outbox 작업의 트랜잭션 기반 데이터베이스 경계를 정의합니다. |
| `NotificationDeliveryRouteGate` | `SHADOW`, `CANARY`, `ACTIVE`, `PAUSED`를 병원별 단일 provider 경로로 변환합니다. |
| `NotificationDirectOutboxDelivery` | 전환기 이벤트 경로가 정확한 outbox 행을 조건부 선점하게 합니다. |
| `MemberNotificationProfileResolver` | 실행 시간이 제한된 정책 안에서 최신 연락처·언어·동의를 조회합니다. |
| `NotificationTemplateCatalog` | 허용된 템플릿 키·버전·채널별 정의를 관리합니다. |
| `NotificationTemplateRenderer` | 타입이 지정된 매개변수와 실행 시점 프로필을 검증 실패 시 차단하는 방식으로 렌더링합니다. |
| `NotificationChannel` | provider가 처리할 수 있는 요청을 발송하고 개인정보가 없는 결과를 반환합니다. |
| `ResilientNotificationChannel` | 코루틴 취소를 재시도하지 않으면서 CircuitBreaker·Retry·Bulkhead를 적용합니다. |
| `NotificationRetentionRunner` | 상태별 보존 기간에 따라 종료 레코드를 제한된 단위로 삭제합니다. |
| `NotificationSchemaReadiness` | 필수 스키마·인덱스·암호화 참조가 없으면 readiness를 실패시킵니다. |

## 발송 흐름

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../docs/requirements/assets/data-flow-05-notification-events-ko-dark.png">
  <img src="../docs/requirements/assets/data-flow-05-notification-events-ko.png" alt="내구성 알림 outbox 발송 경로와 개인정보 경계">
</picture>

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

### 단계별 발송 경로

| 모드 | 전환기 이벤트 경로 | 백그라운드 worker 경로 |
|---|---|---|
| `SHADOW` (기본값) | 모든 병원 | 사용 안 함 |
| `CANARY` | 허용 목록 밖의 병원 | 허용 목록 병원만 사용 |
| `ACTIVE` | 사용 안 함 | 모든 병원 |
| `PAUSED` | 사용 안 함 | 사용 안 함 |

어느 경로든 provider를 호출하기 전에 같은 데이터베이스 행을 조건부 선점해야 합니다.
`PAUSED`도 provider 호출만 멈추며 enqueue·복구·보존 처리는 유지합니다. 운영
카나리 활성화는 코드 전환 PR과 분리되어 있으므로 모드를 바꾸기 전에 운영 런북의
통과 기준을 확인합니다.

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
    crypto:
      active:
        key-id: notification-2026-q3
        secret-reference: env:CLINIC_NOTIFICATION_HMAC_KEY
        activated-at: 2026-07-01T00:00:00Z
        expires-at: 2030-01-01T00:00:00Z
    rollout:
      mode: SHADOW
      canary-clinic-ids: []
    worker:
      enabled: true
      max-attempts: 6
      max-elapsed: 24h
      provider-attempts-per-lease: 1
      catch-up-window: 30m
      lease-duration: 60s
      provider-timeout: 30s
      poll-interval: 1s
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
    observation:
      poll-interval: 10s
      limit: 10001
    retention:
      poll-interval: 1h
      sent: 7d
      suppressed: 7d
      exhausted: 30d
      page-size: 100
      max-pages-per-status: 10
      backpressure: 100ms
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
거부합니다. `channels.<채널 유형 소문자>.provider-timeout`이 있으면 해당 채널에
우선 적용하고, 없으면 `worker.provider-timeout`을 사용합니다. `provider-timeout`은
실제 provider 호출 future의 상한이며, 초과한
작업은 취소하고 안정적인 `PROVIDER_UNAVAILABLE` 실패로 처리합니다. provider
adapter 자체의 connect/read/request timeout도 이 값 이하로 설정해야 하며, interrupt를
무시하는 SDK는 adapter의 자체 timeout으로 종료해야 합니다. active crypto
key reference가 없거나 유효하지 않으면 알림 readiness가 DOWN이 되고 worker 처리를
차단합니다. `secret-reference`에는 key material이 아니라 외부 secret 위치만 둡니다.

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
- [운영 런북](../docs/runbooks/notification-outbox-operations.md)
- [알림 데이터 흐름](../docs/requirements/data-flow.md#5-알림-outbox-발송-흐름)

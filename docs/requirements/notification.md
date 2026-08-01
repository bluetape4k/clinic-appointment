# 알림 outbox 설계

**모듈**: `appointment-notification`
**의존 모듈**: `appointment-core`, `appointment-event`

## 목표와 경계

예약 생성·확정·취소·재배정과 리마인더 알림을 데이터베이스 장애, 애플리케이션
재시작, 여러 인스턴스의 동시 실행에도 복구할 수 있어야 합니다. 예약 명령은 예약
변경과 같은 트랜잭션에서 최소 알림 outbox를 저장합니다. 실제 연락처·언어·동의는
발송 직전에 회원 시스템에서 조회합니다.

알림 서비스가 저장하지 않는 정보는 다음과 같습니다.

- 회원 이름, 전화번호, 이메일
- 렌더링된 제목·본문과 provider payload
- provider 원본 오류 메시지와 stack trace
- 회원의 객관적 특징, 점수, 분류, 설명

고객관리시스템은 회원정보의 기준 시스템입니다. 예약·알림 서비스는 회원 ID와
알림 의도만 전달받고, 발송에 필요한 최신 정보는 메모리에서만 사용합니다.

## 저장 모델

| 테이블 | 역할 | 개인정보 최소화 |
|---|---|---|
| `scheduling_notification_outbox` | 논리 알림, 재시도 시각, lease, fencing, 종료 상태 | 처리 중에만 회원 ID·예약 ID·타입이 지정된 template parameter를 보관하고 종료 시 제거 |
| `scheduling_notification_delivery_attempts` | provider 시도 결과와 진단용 안정 코드 | 연락처·본문·원본 오류 없이 outcome, failure code, 안전한 fingerprint만 보관 |

`NotificationHistory`를 발송 결과의 기준 저장소로 사용하지 않습니다. 중복 방지는
논리 알림의 결정적 멱등성 키, outbox 행의 조건부 선점, fencing token으로 처리합니다.

## 발송 흐름

1. 예약 명령이 예약 변경과 최소 outbox 행을 하나의 트랜잭션으로 커밋합니다.
2. 현재 rollout 모드가 병원별 provider 경로를 하나만 선택합니다.
3. 선택된 경로가 정확한 outbox 행을 데이터베이스 lease와 fencing token으로
   조건부 선점합니다.
4. `MemberNotificationProfileResolver`가 회원 시스템에서 현재 연락처·언어·동의를
   조회합니다. 연락처가 없거나 동의를 철회했다면 발송하지 않고 안정적인 사유
   코드로 억제합니다.
5. `NotificationTemplateRenderer`가 허용된 template key·version과 타입이 지정된
   parameter로 provider 본문을 메모리에서 만듭니다.
6. `NotificationChannel`이 결정적인 provider 멱등성 키로 한 번 호출됩니다.
7. worker가 fencing된 성공·억제·재시도·소진 상태만 저장합니다. 종료 행은
   개인정보 필드를 제거하고 상태별 보존 기간 뒤 제한된 page 단위로 삭제합니다.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/data-flow-05-notification-events-ko-dark.png">
  <img src="assets/data-flow-05-notification-events-ko.png" alt="예약 트랜잭션의 최소 알림 outbox부터 병원별 단일 발송 경로, 발송 시점 회원 조회, 개인정보 제거와 보존까지의 흐름">
</picture>

[한국어 light SVG](assets/data-flow-05-notification-events-ko.svg) ·
[한국어 dark SVG](assets/data-flow-05-notification-events-ko-dark.svg) ·
[English light SVG](assets/data-flow-05-notification-events-en.svg) ·
[English dark SVG](assets/data-flow-05-notification-events-en-dark.svg) ·
[Mermaid 의미 스케치](assets/data-flow-05-notification-events.mmd) ·
[운영 런북](../runbooks/notification-outbox-operations.md)

## 단계별 발송 경로

| 모드 | 전환기 Spring event 경로 | 백그라운드 worker | 용도 |
|---|---|---|---|
| `SHADOW` | 모든 병원 | 발송 안 함 | 코드 배포 기본값. 기존 호출 흐름을 유지하면서 같은 outbox 생명주기를 검증 |
| `CANARY` | 허용 목록 밖의 병원 | 허용 목록 병원만 | 병원 단위 운영 검증 |
| `ACTIVE` | 사용 안 함 | 모든 병원 | 전체 전환 완료 상태 |
| `PAUSED` | 사용 안 함 | 사용 안 함 | provider 장애나 중복 위험 대응. enqueue·복구·보존은 유지 |

전환기 event listener와 worker는 별도 발송 저장소를 사용하지 않습니다. 둘 다 같은
outbox 행을 조건부 선점하므로 rolling deployment에서 설정이 다른 인스턴스가 겹쳐도
동일 논리 알림을 동시에 발송할 수 없습니다.

```yaml
clinic:
  notification:
    rollout:
      mode: SHADOW
      canary-clinic-ids: []
```

`canary-clinic-ids`는 `CANARY`에서만 허용되며 양의 병원 ID가 하나 이상 필요합니다.
실제 `CANARY`와 `ACTIVE` 전환은 코드 배포와 분리된 운영 작업입니다.

## 리마인더 복구

확정 예약의 전일·당일 리마인더는 예약 version과 reminder slot을 포함한 멱등성
키로 미리 outbox에 기록합니다. 보정 scanner는 중단 시간에 누락된 materialization만
같은 키로 복구합니다. `catch-up-window`가 지난 리마인더는 늦게 발송하지 않고
`SUPPRESSED(REMINDER_WINDOW_MISSED)`로 끝냅니다. 아직 발송 시각이 오지 않은 slot도
미래 `availableAt`을 가진 outbox로 미리 기록해 긴 backlog 순회 중 due 시각을 놓치지
않습니다.

애플리케이션 준비 직후와 `reminder-recovery-interval`마다 확정 예약을 ID keyset
page로 제한해 조회합니다. DB 조회 한 번은 `batch-size`, 실행 한 번은
`reminder-recovery-max-candidates-per-run`을 넘지 않으며, 다음 page는 이전 cursor 뒤에서
이어집니다. 현재 순회의 `run_id`와 마지막 완료 예약 ID는
`clinic_notification_reminder_checkpoint`에 기록하므로 프로세스 재시작이나 leader 교체
뒤에도 이어집니다. outbox unique key는 같은 알림의 중복 생성을 막습니다. 별도 환자
목록이나 연락처 snapshot은 만들지 않습니다. `worker.enabled=false`이면 보정 경로도
구성하지 않습니다.

Redis 기반 `LeaderGroupElector`는 향후 많은 병원이 사용하는 SaaS 환경에서 보정
scanner trigger를 줄이는 최적화로 추가할 수 있습니다. 현재 발송 정합성은 Redis가
아니라 DB lease·fencing·멱등성 키가 보장합니다.

## 기본 실행 제한

| 설정 | 기본값 | 의미 |
|---|---:|---|
| `worker.max-attempts` | `6` | outbox 생명주기 최대 시도 횟수 |
| `worker.max-elapsed` | `24h` | 최초 시도부터 허용하는 최대 경과 시간 |
| `worker.provider-attempts-per-lease` | `1` | 한 lease에서 수행할 provider 호출 횟수 |
| `worker.catch-up-window` | `30m` | 누락 리마인더 보정 시간창 |
| `worker.reminder-recovery-interval` | `1h` | 시작 시 보정 이후 반복 실행 간격 |
| `worker.reminder-recovery-max-candidates-per-run` | `1,000` | 한 번의 보정 실행에서 처리할 후보 상한 |
| `worker.lease-duration` | `60s` | 선점 유효 시간 |
| `worker.provider-timeout` | `30s` | 실제 provider 호출을 기다리는 최대 시간 |
| `worker.poll-interval` | `1s` | dispatcher 실행 주기 |
| `worker.batch-size` | `100` | 한 번에 조회할 최대 후보 수 |
| `worker.global-concurrency` | `4` | 전체 동시 처리 상한 |
| `worker.per-clinic-concurrency` | `1` | 병원별 동시 처리 상한 |
| `worker.member-resolver-timeout` | `5s` | 회원 조회 제한 시간 |
| `observation.poll-interval` | `10s` | worker와 분리된 관측 snapshot 갱신 주기 |
| `observation.limit` | `10,001` | 10,000건 경보 임계값을 식별하는 최대 조회 건수 |

설정은 시작 시 함께 검증합니다. lease가 provider 호출 상한보다 짧거나, 전체
동시성이 DB claim·회원 조회·provider 용량보다 크면 애플리케이션이 시작되지
않습니다. pending과 oldest 지표는 미래 예약을 제외하고 현재 DB 시각에 발송 가능한
행만 상한 있는 index 조회로 관측합니다.

## 회원 ID 전환

신규 legacy 예약은 `appointment.notification.member-id-enforcement=ENFORCE`가
기본값입니다. 기존 병원의 이행 기간에는 정확한 `(tenantGroupId, clinicId)` 범위에
담당자와 만료 시각을 둔 `OBSERVE` 예외만 허용합니다. v2 요청은 본문에 회원 ID를
추가하지 않고 인증 주체와 구매 Plan의 보호된 참조를 회원 시스템이 해석합니다.

회원 조회 오류는 `MEMBER_ID_REQUIRED`, `MEMBER_NOT_FOUND`,
`MEMBER_SCOPE_MISMATCH`, `MEMBER_REFERENCE_AMBIGUOUS`,
`MEMBER_DIRECTORY_UNAVAILABLE`처럼 안정적인 코드로 반환합니다. 이름이나 연락처를
오류 응답에 반사하지 않습니다.

## 운영과 보안

- 공개 metric label에는 `channel`, `event_type`, `outcome`, `reason_code`처럼
  닫힌 낮은 cardinality 값만 사용합니다. tenant·clinic·member·appointment·outbox
  ID는 metric label에 넣지 않습니다.
- 병원별 상세 조회는 역할·tenant·정확한 clinic 범위를 확인하는 제한된 운영
  화면에서만 제공합니다.
- 수동 재알림은 최대 100개 예약을 현재 데이터로 다시 평가합니다. 종료 행을
  되살리거나 삭제된 개인정보를 복구하지 않습니다.
- idempotency·감사 HMAC key는 설정 파일에 넣지 않고 지원되는 외부 secret
  reference로만 지정합니다.
- `SENT`는 7일, `SUPPRESSED`는 7일, `EXHAUSTED`는 30일 뒤 제한된 page 단위로
  삭제하는 것이 기본 보존 정책입니다.

배포·경보·재알림·키 교체·DB 마이그레이션 절차는
[알림 outbox 운영 런북](../runbooks/notification-outbox-operations.md)을 따릅니다.

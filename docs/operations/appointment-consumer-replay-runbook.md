# Appointment Consumer Replay 운영 Runbook

이 문서는 `appointment-notification-v1` 및 `appointment-statistics-v1` consumer의
quarantine event를 승인된 별도 replay group으로 재처리하는 절차를 정의한다.

## 안전 경계

- replay 요청은 `approver`, `tenantGroupId`, `clinicId`, `fromOffset`, `toOffset`,
  `dryRun`을 모두 포함해야 한다.
- Kafka operations group의 offset을 rewind하지 않는다. 실행 source는
  `appointment-<consumer>-replay-v1` group만 사용한다.
- inbox key는 원래 consumer의 `(logicalConsumerId, logicalStreamId, eventId)`를
  유지하므로 side effect는 기존 dedup 경계를 따른다.
- replay audit에는 request id, scope, offset, 승인자, 상태와 시간만 저장한다.
  원문 Kafka value, 환자 식별자, recipient/provider payload는 저장하거나 출력하지 않는다.

## 실행 순서

1. quarantine metadata에서 대상 tenant/clinic과 offset 범위를 확인한다.
2. `AppointmentReplayRequest`를 만들고 bounded offset 범위(최대 100,000)를 지정한다.
3. 같은 request id로 `dryRun=true`를 먼저 실행한다. 이 단계는 audit를 `DRY_RUN`으로
   기록하고 replay source/handler를 호출하지 않는다.
4. dry-run 결과와 승인자/범위를 운영 기록으로 확인한 뒤, 동일 범위에 대해 승인된
   `dryRun=false` 요청을 실행한다.
5. 결과가 `EXECUTED`인지 확인하고 `replayedRecords`를 기록한다. 실패하면 audit가
   `REJECTED`가 되며 예외 메시지에는 raw payload가 포함되지 않는다.

## 중단·복구

- schema readiness가 `DOWN`이거나 DB inbox migration이 없으면 replay를 실행하지 않는다.
- source가 오류를 반환하면 operations group을 건드리지 않은 채 `REJECTED`로 종료하고,
  원인을 로그의 bounded failure class로만 확인한다.
- 같은 request id의 재호출은 기존 audit 상태를 반환하는 idempotent 조회로 처리한다.
- 범위가 잘못되었거나 다른 tenant/clinic이면 새 request id를 만들지 말고 요청을 폐기한
  뒤 접근 권한과 quarantine provenance를 재확인한다.

## 관찰 항목

- `scheduling_appointment_consumer_inbox`: consumer별 처리 상태와 attempt 수
- `scheduling_appointment_consumer_quarantine`: failure code와 provenance/hash
- `scheduling_appointment_consumer_replay_audit`: 승인·dry-run·실행 상태

세 테이블 모두 raw Kafka value를 포함하지 않는 것이 정상이다. payload가 보이는
로그/감사 출력은 보안 결함으로 간주하고 즉시 해당 sink를 격리한다.

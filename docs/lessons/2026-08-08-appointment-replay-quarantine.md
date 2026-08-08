# Appointment quarantine replay 의미 보존

## 문제

replay source가 원래 logical inbox identity를 유지하는 것은 올바른 dedup 경계다. 그러나 runtime의 일반 `begin` 경로는 이미 `QUARANTINED`인 terminal row를 handler 없이 다시 `QUARANTINED`로 반환했다. 따라서 runbook이 약속한 quarantine 재처리가 실제 handler 실행으로 이어지지 않았다. 동시에 Kafka adapter는 runtime outcome을 확인하지 않고 모든 record를 `replayedRecords`에 더해 실패나 중복을 성공처럼 보이게 했다.

## 규칙

- 정상 broker delivery는 `QUARANTINED` row를 재점유하지 않는다.
- replay scope가 전달되고 provenance가 동일한 경우에만 기존 inbox identity의 quarantine row를 새 bounded processing attempt로 원자적으로 재개한다.
- replay 결과 count는 handler가 실행되어 `PROCESSED`된 record만 포함한다. `DUPLICATE`는 side effect가 없으므로 제외한다.
- `QUARANTINED` 또는 retryable outcome은 source에서 실패로 전파해 replay audit를 `REJECTED`로 만든다.
- dry-run은 계속 source와 handler를 호출하지 않는다.

## 검증

- quarantine row replay가 동일 identity로 handler를 다시 실행하고 `PROCESSED`로 전환되는 runtime 회귀 테스트
- quarantine outcome이 count되지 않고 replay exception으로 종료되는 Kafka source 테스트
- duplicate outcome이 `replayedRecords=0`이 되는 source 테스트
- `:appointment-messaging:test` 영향 범위 26건 통과

## 범위 경계

이 수정은 local runtime/source semantics와 테스트·runbook만 다룬다. production broker crash/rebalance, 인증된 replay adapter, 실제 Schema Registry와 deployment SLO는 별도 운영 증거가 필요하다.

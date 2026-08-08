# Issue #204 readiness와 외부 rollout 경계

## 맥락

notification outbox의 route, schema, timeout, health, alert 계약은 로컬 테스트와
migration inventory로 검증할 수 있지만, staging DDL 측정과 실제 provider CANARY는
저장소 테스트만으로 재현할 수 없습니다.

## 결정

로컬 readiness를 `PASS`로 기록하되 production rollout은 `HOLD`로 분리합니다.
`SHADOW` route를 유지하고, 24시간·1,000건 CANARY와 owner approval이 없는 상태에서
Issue #204를 닫거나 `ACTIVE`로 전환하지 않습니다.

## 결과와 검증

`NotificationDeliveryRouteGateTest` 등 notification 계약 테스트 46개와 H2/PostgreSQL/
MySQL V14–V23 migration inventory를 확인했습니다. 이 증거는 route/schema 계약을
지지하지만 production DDL lock, provider 처리량, 실제 canary 결과를 증명하지 않습니다.

## 다음 작업자가 지킬 점

운영 전환을 진행할 때는 staging snapshot, DDL/query-plan, provider timeout/throughput,
canary 종료 기준, owner 승인, stabilization window를 실제 관측 자료로 Issue #204에
첨부한 뒤 별도 rollout 변경을 검토합니다.

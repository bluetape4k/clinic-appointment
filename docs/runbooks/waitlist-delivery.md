# 대기 목록 전달 런북

## 사전 조건

1. 대상 dialect의 Flyway 버전과 additive V19 테이블을 확인합니다.
2. 활성 clinic policy, adapter/schema readiness, oldest vacancy age, failed job,
   unknown delivery, Redis leader state를 확인합니다.
3. migration과 복구 검사가 통과할 때까지
   `appointment.waitlist.delivery.enabled=false`를 유지합니다.

```bash
./gradlew :appointment-api:test --tests "*FlywayMigrationTest" --no-build-cache
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" \
  http://localhost:8080/actuator/health/waitlistDelivery
curl -fsS -H "Authorization: Bearer ${MANAGEMENT_TOKEN}" \
  http://localhost:8080/actuator/metrics/appointment_waitlist_oldest_vacancy_seconds
```

management token은 배포 secret store에서 제공해야 합니다. shell history, log,
issue, metric label에 넣지 않습니다.

## 단계적 전환과 롤백

1. V19을 배포하고 table/constraint/index 개수를 확인합니다.
2. shadow preview를 실행하고 mutation이 없는 decision/audit sample을 확인합니다.
3. clinic 하나를 `clinic-allowlist`에 추가하고 `UP`, 2분 미만 oldest vacancy,
   failed job count 0을 확인합니다.
4. `enabled=true`로 설정하고 제한된 한 구간 동안 dispatch, offer, notification,
   suppression metric을 관찰합니다.

rollback 순서는 `allowlist removal -> dispatch/new delivery zero -> in-flight DB
lease expiry -> expiry/suppression/hold-reconcile drain`입니다. V19 row를 삭제하거나
schema를 낮추지 않습니다. failed/unknown row에 대한 운영자 판단이 끝나고 health가
`UP`으로 돌아온 뒤에만 다시 활성화합니다.

## 운영자 작업

- lease가 만료된 `PROCESSING`은 다음 leader가 reclaim하며, Redis lease를 잃은 작업은
  database fence를 통과할 수 없습니다.
- failed job은 version precondition과 typed reason이 있을 때만 requeue합니다.
- 알 수 없는 provider result는 수동 검토 대상으로 표시하거나 provider evidence를
  확인한 뒤 suppress합니다. acceptance로 처리하지 않습니다.
- 만료되었거나 terminal인 offer는 pending notification을 suppress하고 hold를
  해제합니다.
- Retention은 terminal이면서 미해결 항목이 없는 row만 bounded batch로 purge하며,
  active·legal-hold·audit-hold row는 건너뜁니다.

## Health와 alert

`UP`은 adapter/schema/policy readiness, 2분 미만 oldest vacancy, failed job 없음이
필요합니다. `DEGRADED`는 provider failure ratio 5%, 2–5분 oldest vacancy, unknown
delivery 중 하나에서 시작합니다. dependency/policy 누락, 5분 초과 oldest vacancy,
failed job 발생, 100건 초과 expired backlog이면 `OUT_OF_SERVICE`여야 합니다. alert
규칙은 [`docs/alerts/waitlist-delivery.yml`](../alerts/waitlist-delivery.yml)에
있습니다.

incident ticket에 dialect, Flyway version, metric snapshot, command correlation ID,
operator actor reference, action reason, post-action health를 기록합니다. raw member ID,
전화번호, JWT, provider payload는 기록하지 않습니다.

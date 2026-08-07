# Tenant query 격리 V21 운영 런북

이 런북은 Issue #39의 tenant/clinic query 격리와 V21 additive migration을 운영할 때
사용합니다. `TenantClinicScope`는 인증 객체가 아니라 검증된 DB 권위이며, 모든
스케줄링·solver·reschedule·direct notification 경계가 같은 양수 쌍을 전달해야 합니다.

## 1. 배포 전 hold

다음 결과를 dialect별 배포 기록에 저장합니다. orphan가 하나라도 있거나 maintenance
window와 lock 예산이 없으면 migration을 dispatch하지 않습니다.

```sql
SELECT COUNT(*) AS event_log_rows,
       SUM(CASE WHEN clinic.id IS NULL THEN 1 ELSE 0 END) AS orphan_rows,
       SUM(CASE WHEN event_log.tenant_group_id IS NULL THEN 1 ELSE 0 END) AS null_tenant_rows,
       SUM(CASE WHEN clinic.id IS NOT NULL
                     AND clinic.tenant_group_id <> event_log.tenant_group_id
                THEN 1 ELSE 0 END) AS tenant_clinic_mismatch_rows
FROM scheduling_appointment_event_logs event_log
LEFT JOIN scheduling_clinics clinic ON clinic.id = event_log.clinic_id;
```

PostgreSQL에서는 backfill의 `UPDATE ... FROM`을 `EXPLAIN`으로 확인합니다.
H2에서는 동일 join의 `EXPLAIN`을 확인하고 테스트 DB와 제약 metadata를 저장합니다.
MySQL에서는 `EXPLAIN UPDATE` 또는 equivalent join plan과 InnoDB lock 대기를 기록합니다.
모든 dialect에서 다음을 확인합니다.

- V1~V20 checksum이 변하지 않았고 target이 V21이다.
- `scheduling_clinics`의 모든 `clinic_id`가 정확히 하나의 tenant owner로 해소된다.
- event-log null row 수와 예상 backfill row 수가 일치한다.
- event-log null row와 tenant-clinic mismatch row가 모두 0이거나, 아직 backfill 대상인
  null row 수가 사전 계산한 값과 일치한다.
- 기존 `idx_notification_outbox_direct_lookup`를 보존하고 새 tenant-leading index를 추가한다.
- 실행 중 notification route를 `PAUSED`로 전환할 수 있고 rollback 시 schema-down을 하지 않는다.

## 2. V21 실행 순서

H2, PostgreSQL, MySQL migration은 의미가 같으며 다음 순서를 지킵니다.

1. `scheduling_appointment_event_logs.tenant_group_id BIGINT NULL`을 추가합니다.
2. 기존 clinic join으로 null 값을 backfill합니다. event payload JSON을 파싱하거나 임의
   default tenant를 쓰지 않습니다.
3. `(tenant_group_id, clinic_id, created_at, id)` event-log index와
   `ON DELETE RESTRICT` tenant FK를 추가합니다.
4. outbox에
   `(tenant_group_id, clinic_id, appointment_id, event_type, row_kind, status,
   available_at, next_retry_at, id)` 순서의
   `idx_notification_outbox_tenant_direct_lookup`를 추가합니다.
5. Flyway history와 실제 column/index/FK metadata를 다시 읽고 readiness를 확인합니다.

V21 column은 rolling deployment 동안 nullable입니다. 새 writer는 양수 scope를 반드시
기록하지만 구버전 node insert를 DB constraint로 깨뜨리지 않습니다. 구버전 node를 모두
drain한 뒤 null row를 다시 backfill해 0임을 증명하고, `NOT NULL` hardening은 별도 release와
승인을 거친 뒤에만 검토합니다.

## 3. Readiness와 전환

`NotificationSchemaReadiness`는 Flyway V21, event-log tenant column, clinic owner join,
tenant direct index, 기존 outbox lifecycle index와 active key를 모두 요구합니다. null tenant,
clinic orphan, tenant-clinic mismatch가 하나라도 남으면 worker와 background dispatcher는
traffic을 받지 않습니다.

```yaml
clinic:
  notification:
    rollout:
      mode: CANARY
      canary-scopes:
        - tenant-group-id: 1
          clinic-id: 23
      # Deprecated bridge; 사용 시 clinic 집합이 위 scope와 같아야 한다.
      canary-clinic-ids: [23]
```

신규 route/claim/permit은 `canary-scopes`와 `(tenantGroupId, clinicId)`를 사용합니다.
`canary-clinic-ids`는 구버전 node를 위한 deprecated bridge이며 clinic 집합이 다르면
startup을 거부합니다. scope가 없거나 0이면 claim·permit·provider 호출을 만들지 않습니다.
canonical cache key는 `${tenantGroupId}:${clinicId}`이고 `1:23`과 `12:3`은 충돌하지 않습니다.

## 4. Pause와 rollback

- preflight orphan, null mismatch, index/constraint mismatch, partial DDL, lock timeout이면
  Flyway dispatch와 notification route를 즉시 `PAUSED`로 hold합니다.
- application rollback 전 readiness와 outbox row를 삭제하지 않고, schema-down migration을
  실행하지 않습니다.
- MySQL partial DDL은 `flyway_schema_history`, `INFORMATION_SCHEMA.COLUMNS`,
  `INFORMATION_SCHEMA.STATISTICS`, `INFORMATION_SCHEMA.KEY_COLUMN_USAGE`를 대조한 뒤
  누락된 additive 단계만 명시적으로 복구합니다. history를 임의 수정하지 않습니다.
- old-node drain 전에는 nullable column을 유지하고, drain 후 zero-null 증거와 별도
  hardening release가 없으면 `NOT NULL` DDL을 실행하지 않습니다.

## 5. 관측과 개인정보

low-cardinality 관측만 허용합니다.

- `clinic.notification.event.log.write.failures{reason_code}` counter
- `clinic.notification.direct.event.scope.rejections{reason_code}` counter
- `clinic.notification.event.log.null.tenant.rows` gauge (null/orphan/mismatch preflight count)
- schema/index/config mismatch readiness reason

허용 reason code는 `EVENT_LOG_WRITE_FAILED`, `DIRECT_EVENT_SCOPE_REJECTED`,
`DIRECT_EVENT_CLAIM_SCOPE_MISMATCH` 세 가지로 제한합니다.

tenant, clinic, appointment ID와 raw event payload를 metric tag나 error log에 넣지 않습니다.
감사 event-log 저장 실패는 이미 commit된 API 결과를 바꾸지 않으며, durable notification
retry는 tenant-aware outbox가 담당합니다.

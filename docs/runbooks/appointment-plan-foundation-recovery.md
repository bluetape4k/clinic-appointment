# 예약 플랜 기반 복구 런북

## 소유권

- 1차 대응: scheduling on-call
- 구매 정정과 원천 aggregate version 확인: commerce service
- 상품 BOM 정정: product catalog service
- 운영 `WRITE` 승인 조건: outbox transport와 경보 소유자가 별도 승인

## 증상별 확인

| 증상 | 확인 대상 | 안전한 첫 조치 |
|------|----------|---------------|
| `WAITING_GAP` 증가 | source aggregate ID/version, `replayAfter`, attempt count | commerce에서 누락 version 존재 여부 확인 |
| `QUARANTINED` 증가 | allowlist reason code, event ID, producer, schema version | 원천 이벤트를 수정하지 말고 원인 서비스에 정정 요청 |
| terminal trust/scope rejection 증가 | terminal rejection store의 event ID, claimed scope, reason code | security on-call에 인계하고 quarantine release/redrive 대상으로 취급하지 않음 |
| plan은 있으나 outbox가 없음 | inbox, plan, outbox의 동일 event ID | 자동 재생 전 원자성 손상 여부 조사 |
| 동일 구매 충돌 | `sourcePurchaseAuthority + sourcePurchaseId`, tenant/clinic, patient fingerprint | commerce의 구매 소유권을 기준으로 판정 |
| 카탈로그 버전 없음 | tenant/clinic + `catalogSourceAuthority + productId + catalogVersion` | product catalog 동기화 후 지정 redrive |

로그나 티켓에 환자 참조 token/ciphertext/fingerprint, 원문 event, 전체 BOM을 붙이지
않습니다. `eventId`, `correlationId`, producer, schema/version, tenant/clinic,
result, reason code만 사용합니다.

## 제한된 진단 selector

운영 DB의 read-only console에서 먼저 정확한 scope/event를 고정합니다. 모든 목록
조회는 `LIMIT 100` 이하이며 결과를 티켓에 복사할 때는 허용된 식별자만 남깁니다.

```sql
SELECT event_id, status, failure_code, attempt_count, replay_after, received_at
FROM scheduling_inbox_events
WHERE tenant_group_id = :tenantGroupId
  AND clinic_id = :clinicId
  AND event_id = :eventId
LIMIT 1;

SELECT id, event_id, reason_code, status, legal_hold, payload_expires_at, detected_at
FROM scheduling_quarantine_events
WHERE tenant_group_id = :tenantGroupId
  AND clinic_id = :clinicId
  AND event_id = :eventId
LIMIT 1;

SELECT event_id, claimed_tenant_group_id, claimed_clinic_id, producer,
       reason_code, detected_at
FROM scheduling_untrusted_event_rejections
WHERE event_id = :eventId
  AND claimed_tenant_group_id = :tenantGroupId
  AND claimed_clinic_id = :clinicId
ORDER BY detected_at DESC
LIMIT 100;

SELECT action, actor, reason, approval_references, dry_run_diff_hash, created_at
FROM scheduling_quarantine_audit_events
WHERE quarantine_id = :quarantineId
ORDER BY id
LIMIT 100;

SELECT COUNT(*) AS pending_count, MIN(created_at) AS oldest_created_at
FROM scheduling_outbox_events
WHERE tenant_group_id = :tenantGroupId
  AND clinic_id = :clinicId
  AND status = 'PENDING';
```

`scheduling_untrusted_event_rejections`는 서명·신뢰·scope 검증을 통과하지 못한
이벤트의 FK-free terminal 증거입니다. 해당 행은 일반 quarantine이 아니며
release/redrive할 수 없습니다. reason code와 claimed scope만으로 security
on-call에 인계하고, 원문 payload나 환자 참조를 티켓에 복사하지 않습니다.

## 지정 redrive 절차

1. commerce에서 원본 `eventId`, producer, source authority, 정확한
   `sourceAggregateVersion`을 확인합니다.
2. signature, issuer, audience, producer, replay 정책을 우회하지 않습니다.
3. 대상 quarantine ID와 actor/reason을 지정해
   local/test gate에서는 `PurchaseEventRedriveService.redrive(..., dryRun = true)`로 범위, version,
   catalog, factory 검증과 정제된 diff를 확인하고 audit row를 확인합니다.
4. tenant/clinic, `sourcePurchaseAuthority + sourcePurchaseId`,
   `catalogSourceAuthority + productId + catalogVersion`이 모두 일치할 때만 같은
   event ID이고 quarantine 상태가 `RELEASE_APPROVED`이며 입력한 승인 참조가
   release audit와 일치할 때만 `dryRun = false`로 지정 재처리합니다.
5. 결과 inbox가 terminal 상태인지, 플랜이 하나인지, outbox가 하나인지 확인합니다.

위 service-call shape는 local/test 전용입니다. production에서는 같은 bounded
identity, approval, audit 계약을 강제하는 인증된 admin command/API/job wrapper가
추가되기 전 redrive와 consumer `WRITE`가 모두 BLOCKED입니다.

Gap 재시도는 5초 지수 backoff, 최대 5분, 20% deterministic jitter를 사용합니다.
5번째 시도에서 `SOURCE_VERSION_GAP_EXHAUSTED`로 격리됩니다. attempt count를
수동으로 초기화하거나 새 event ID를 만들어 우회하지 않습니다.

## 기능 플래그 롤백

1. 변경 대상을 정확한 `(tenantGroupId, clinicId)`로 고정하고 현재 effective
   control을 readback합니다.
2. 그 scope의 `purchase-consumer-mode=OFF`
3. 그 scope의 `plan-read-enabled=false`
4. 필요하면 그 scope의 `catalog-sync-enabled=false`
5. 동일 tenant의 다른 clinic과 다른 tenant의 control이 변하지 않았는지
   readback합니다.

로컬 Foundation 증명은 다음 typed override 형태를 사용합니다.

```yaml
appointment:
  plan-foundation:
    scope-overrides:
      - tenant-group-id: 7
        clinic-id: 11
        catalog-sync-enabled: false
        plan-read-enabled: false
        purchase-consumer-mode: OFF
```

production에서는 배포 설정 직접 변경만으로 승인하지 않습니다. feature-control
provider가 actor, reason, 이전/새 값, expiry, correlation ID를 append-only audit에
남기고 effective-value readback을 제공해야 합니다. 그 provider가 없으면 production
`WRITE`는 BLOCKED입니다.

플래그 롤백은 기존 plan, treatment, dependency, inbox, outbox 기록을 삭제하지
않습니다. V8은 additive migration이므로 테이블을 되돌리거나 기존 appointment
행을 수정하지 않습니다.

## 복구 완료 조건

- 대상 event ID가 하나의 terminal inbox 판정으로 수렴
- source purchase당 plan 한 건
- 생성된 plan당 pending outbox 한 건
- 환자 참조나 치료 상세가 로그·metric tag·quarantine metadata에 없음
- 원천 서비스의 version/ownership 확인 기록 존재
- outbox pending count/oldest age가 회복되고 해당 alert가 해제됨
- scope flag 변경 audit와 effective-value readback, owner acknowledgement 존재
- 운영 `WRITE`는 transport follow-up 전 계속 차단

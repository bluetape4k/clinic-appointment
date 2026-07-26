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
| plan은 있으나 outbox가 없음 | inbox, plan, outbox의 동일 event ID | 자동 재생 전 원자성 손상 여부 조사 |
| 동일 구매 충돌 | source authority + purchase ID, tenant/clinic, patient fingerprint | commerce의 구매 소유권을 기준으로 판정 |
| 카탈로그 버전 없음 | tenant/clinic/product/version | product catalog 동기화 후 지정 redrive |

로그나 티켓에 환자 참조 token/ciphertext/fingerprint, 원문 event, 전체 BOM을 붙이지
않습니다. `eventId`, `correlationId`, producer, schema/version, tenant/clinic,
result, reason code만 사용합니다.

## 지정 redrive 절차

1. commerce에서 원본 `eventId`와 정확한 `sourceAggregateVersion`을 확인합니다.
2. signature, issuer, audience, producer, replay 정책을 우회하지 않습니다.
3. `PurchaseEventRedriveService.redrive(..., dryRun = true)`로 범위, version,
   catalog, factory 검증과 정제된 diff를 확인합니다.
4. tenant/clinic, source purchase ownership, catalog version이 모두 일치할 때만
   같은 event ID를 `dryRun = false`로 지정 재처리합니다.
5. 결과 inbox가 terminal 상태인지, 플랜이 하나인지, outbox가 하나인지 확인합니다.

Gap 재시도는 5초 지수 backoff, 최대 5분, 20% deterministic jitter를 사용합니다.
5번째 시도에서 `SOURCE_VERSION_GAP_EXHAUSTED`로 격리됩니다. attempt count를
수동으로 초기화하거나 새 event ID를 만들어 우회하지 않습니다.

## 기능 플래그 롤백

1. `purchase-consumer-mode=OFF`
2. `plan-read-enabled=false`
3. 필요하면 `catalog-sync-enabled=false`

플래그 롤백은 기존 plan, treatment, dependency, inbox, outbox 기록을 삭제하지
않습니다. V8은 additive migration이므로 테이블을 되돌리거나 기존 appointment
행을 수정하지 않습니다.

## 복구 완료 조건

- 대상 event ID가 하나의 terminal inbox 판정으로 수렴
- source purchase당 plan 한 건
- 생성된 plan당 pending outbox 한 건
- 환자 참조나 치료 상세가 로그·metric tag·quarantine metadata에 없음
- 원천 서비스의 version/ownership 확인 기록 존재
- 운영 `WRITE`는 transport follow-up 전 계속 차단

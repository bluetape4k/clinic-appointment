# 예약 Commitment v2 운영 런북

## 소유권과 연락 경로

| 영역 | 소유자 | 경보 수신·조치 |
|---|---|---|
| API, proposal, allocation, inbox, outbox, retention | 예약팀 | scheduling on-call이 1차 대응 |
| 원 product/purchase event와 source version replay | 상품관리·구매팀 | 원천 사실 확인과 replay authority 제공 |
| `OperationalException` 접수·상담 | CRM | `OPEN` 후 15분 안에 `ACKNOWLEDGED` |
| quarantine 단건 redrive 승인 | 예약 운영 관리자 | actor/reason/승인 참조를 append-only audit에 기록 |

예약 운영 관리자가 아닌 source replay authority는 원 event를 제공할 수 있지만 예약
격리 row를 실행할 수 없다. `PurchaseEventRedriveService`는 dry-run과 실제 redrive
모두 `RESERVATION_OPERATIONS_ADMIN`을 요구한다.

## Dashboard와 alert

| 신호 | 초기 임계값 | 운영 조치 |
|---|---:|---|
| outbox oldest lag | 5분 | publisher·broker 확인, 미전달 row 보존 |
| oldest open quarantine | 24시간 | source owner와 gap·schema·trust 원인 확인 |
| quarantine rate | 1% | consumer mode를 `SHADOW` 또는 `OFF`로 낮춤 |
| allocation conflict | 최근 기준선의 3배 | 인기 자원·capacity·overbooking 정책 확인 |
| migration rejection | 1건 이상 | 동의·mapping·provenance 검토, 자동 강제 전환 금지 |
| CRM ACK latency | 15분 | CRM on-call과 예약 on-call 동시 통보 |

Metric은 tenant/clinic과 닫힌 result/reason/type만 tag로 사용한다. patient, product,
appointment, proposal, event ID를 tag로 사용하지 않는다.

## 점진 배포

1. `OFF`에서 API·consumer가 신규 v2 row를 만들지 않는지 확인한다.
2. `SHADOW`에서 legacy와 v2 계산의 proposal 수, 일정 구간, reason code 차이를
   집계한다. 환자·상품·event 식별자는 diff tag에 넣지 않는다.
3. 차이 원인이 설명되고 outbox/quarantine alert가 정상일 때 clinic allowlist를 한
   병원씩 추가한다.
4. `WRITE`는 mode와 allowlist가 모두 일치할 때만 허용한다.
5. 각 확대 후 최소 한 proposal TTL 동안 proposal expiry, allocation conflict,
   outbox lag, quarantine age를 관찰한다.

## Version gap과 poison 복구

1. `(tenantGroupId, clinicId, producer, sourceAuthority, sourceAggregateId)`를
   정확히 고정한다.
2. 상품·구매팀에서 누락 source version 존재와 authoritative replay를 확인한다.
3. 같은 event ID를 유지하고 signature·issuer·audience·schema 검증을 우회하지 않는다.
4. version gap은 bounded backoff로 재시도한다. 같은 message가 5회 실패하면 poison으로
   간주해 자동 처리를 멈추고 quarantine에 보존한다.
5. terminal trust/scope rejection은 일반 quarantine redrive 대상이 아니며 security
   on-call에 인계한다.

## 권한 있는 단건 redrive

1. 예약 운영 관리자가 quarantine ID, 원 event ID, source aggregate version,
   tenant/clinic, 상품·구매 authority와 version을 직접 확인한다.
2. 상품·구매팀의 replay authority와 release approval reference를 확보한다.
3. `dryRun=true`로 trust, identity, mapping, Plan diff를 검증한다.
4. append-only audit의 actor, reason, before/after status, approval reference를
   확인한다. quarantine ID를 `scheduling_quarantine_events`와 join하면 원 event
   ID를 얻고, 성공 후 `scheduling_inbox_events.event_id`가 같은 inbox key인지
   확인한다.
5. 정확히 한 quarantine이 `RELEASE_APPROVED`이고 승인 참조가 일치할 때만
   `dryRun=false`를 실행한다.
6. terminal inbox 한 건, Plan 한 건, outbox 한 건으로 수렴했는지 확인한다.

광범위 status selector, attempt count 초기화, 새 event ID 생성, 원문 payload의
티켓 복사는 금지한다.

## Retention

`VisitCommitmentRetentionService`는 tenant·clinic별 bounded batch로 실행한다.

| Record | 보존 기간 | 삭제·만료 조건 | 반드시 보존 |
|---|---:|---|---|
| processed inbox | 30일 | cutoff보다 엄격히 오래된 `PROCESSED` | `WAITING_GAP`, `QUARANTINED` poison |
| command idempotency | 30일 | cutoff보다 엄격히 오래됨 | 경계 시각 이후 |
| delivered outbox | 7일 | `PUBLISHED`이며 cutoff보다 오래됨 | `PENDING`, `FAILED` |
| resolved quarantine payload | 90일 | 해결 상태, legal hold 없음 | metadata, audit, open, legal hold |

Quarantine은 metadata row를 삭제하지 않고 암호화 payload만 만료하며
`PAYLOAD_EXPIRED` audit을 추가한다.

## Rollback

`WRITE` 중 생성된 `COMMITMENT_V2` row는 legacy row로 변환하거나 legacy API로
변경하지 않는다.

1. 신규 고객 요청과 관리자 직접 생성 ingress를 `OFF`로 차단한다.
2. 기존 v2 조회, 승인, 확정, 수락·거절, 변경 proposal 경로는 유지한다.
3. 필요하면 `SHADOW` consumer를 유지해 source gap을 관찰한다.
4. V10 schema/table을 삭제하지 않는다.
5. rollback 전 PostgreSQL backup을 만들고 별도 환경 restore로 row 수, FK,
   최근 audit/outbox 조회를 확인한다.

## PostgreSQL backup·restore drill

```bash
pg_dump --format=custom --file=visit-commitment.dump "$DATABASE_URL"
createdb clinic_appointment_restore
pg_restore --exit-on-error --clean --if-exists \
  --dbname=clinic_appointment_restore visit-commitment.dump
```

restore 환경에서 V10 테이블, 최근 commitment/proposal/allocation, 미전달 outbox,
legal hold quarantine이 원본과 일치하는지 read-only query로 비교한다. production
DB에 `--clean` restore를 실행하지 않는다.

## 복구 완료 조건

- 신규 유입 gate와 기존 v2 read/mutation 경로가 의도대로 분리됨
- duplicate allocation 0, unrecovered deadlock 0
- outbox lag 5분 미만, oldest open quarantine 24시간 미만
- redrive actor/reason/before-after/original event/inbox key 증거 존재
- CRM 운영 예외 ACK가 15분 이내
- schema 삭제·legacy 변환 없이 backup restore 검증 완료

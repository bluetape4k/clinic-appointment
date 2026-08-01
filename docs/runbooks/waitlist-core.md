# 대기 목록 코어 운영 runbook

이 문서는 Issue #170 대기 목록 코어와 V18 migration을 운영할 때 사용하는
기준 절차다. 이 코어의 운영 권위는 appointment DB와
`ResourceAllocationRepository`에 있으며, active hold를 직접 삭제하거나
DB row를 수동 수정하지 않는다.

## 1. 배포 전 readiness

다음 항목을 모두 확인한 뒤 clinic allowlist를 연다.

1. H2, PostgreSQL, MySQL에서 V1부터 V18까지 migration이 순서대로 적용되고
   네 waitlist table, FK, status/version 제약, active unique/index가 존재한다.
2. `bluetape4k-states` catalog alias가 실제 `appointment-core` compile
   classpath에서 resolve되고, 기존 `AppointmentStateMachine` 동등성 테스트가
   통과한다.
3. `OFFERED`/`ACCEPTED` hold가
   `ResourceAllocationRepository` availability와 overlap 계산에 포함된다.
4. 두 DB connection의 같은-offer claim, 같은-vacancy offer 경쟁에서 승자 하나와
   stable conflict 결과를 확인한다.
5. candidate keyset/index `EXPLAIN`, decision batch round-trip, 100회 bounded
   contention/load proof, PII·correlation 입력 거부 테스트가 통과한다.
6. `appointment.waitlist.core.enabled=false` 상태에서 shadow candidate 결과와
   active/backlog metric 수집이 정상이다.

readiness 증거에는 migration 버전, 테스트 결과, representative dataset의
`EXPLAIN`, JDBC pool 설정, 검증한 clinic 범위를 함께 기록한다.

## 2. 단계적 rollout

1. additive V18 migration을 먼저 배포하고 feature flag는 `false`로 둔다.
2. readiness 증거를 확인한 clinic만 allowlist에 추가한다.
3. allowlist clinic에서 offer/claim을 활성화하고 다음 지표를 관찰한다.
   - `waitlist_offer_active`
   - `waitlist_hold_active`
   - `waitlist_claim_conflict_total`
   - `waitlist_decision_unavailable_total`
   - `waitlist_expiry_backlog`
   - `waitlist_hold_reconcile_age_seconds`
4. backlog age가 clinic에 설정한 reconcile interval을 넘으면 warning으로
   기록하고, 두 interval을 넘거나 active count drift가 보이면 critical로
   분류해 rollout을 중지한다. tenant·clinic은 allowlist가 허용한
   low-cardinality label만 사용한다.

## 3. 안전한 rollback

1. `appointment.waitlist.core.enabled=false`로 신규 offer/claim만 차단한다.
2. 이미 생성된 offer, hold, history row는 삭제하지 않는다.
3. `reconcileWaitlistHolds(limit, now)`로 만료 backlog를 100건 이하 batch로
   처리한다(설정 상한은 500건). 처리 중 connection 오류가 나면 해당
   transaction은 rollback되고 다음 실행에서 재시도한다.
4. 재활성화 전 `waitlist_hold_active`, `waitlist_expiry_backlog`,
   `waitlist_hold_reconcile_age_seconds`와 recovery backlog를 다시 확인한다.

## 4. 장애별 triage

### 만료 backlog 증가

- `waitlist_expiry_backlog`와 oldest reconcile age를 확인한다.
- `reconcileWaitlistHolds`를 bounded limit으로 실행하고 결과의
  `CapacityHoldExpired(count, lastId)`를 기록한다.
- `OfferStateConflict`가 반환되면 row를 직접 고치지 말고 recovery backlog와
  연결된 offer/entry/hold ID를 owner에게 전달한다. 상태·capacity 보존이
  우선이다.

### stale reliability decision

- `DecisionStale` 또는 `DecisionUnavailable`은 claim 성공으로 변환하지 않는다.
- transaction 밖에서 같은 `(tenantGroupId, clinicId, memberId)` scope의
  decision을 재평가한 뒤 새 stamp로 매칭을 재시도한다.
- 원래 offer/hold의 상태를 수동으로 `ACCEPTED`로 바꾸지 않는다.

### slot conflict

- `SlotOccupied`를 stable conflict로 기록하고 실패 transaction이 offer/hold를
  orphan으로 남기지 않았는지 확인한다.
- 같은 vacancy의 active key가 남아 있으면 release/expiry command 경계를 통해
  처리한다. SQL `DELETE`나 상태 직접 수정은 금지한다.
- 새로운 vacancy descriptor를 읽어 다음 bounded matching tick에서 재시도한다.

### stuck hold 또는 scope mismatch

- hold, offer, entry의 tenant·clinic·member scope와 상태/version을 읽기 전용
  진단으로 비교한다.
- `HOLD_SCOPE_MISMATCH`이면 상태·capacity·history를 변경하지 않고 command ID,
  bounded reason, correlation ID만으로 recovery backlog에 남긴다.
- operator release는 검증된 recovery command로만 실행하며, hold가
  `CONSUMED`되기 전에는 confirmed allocation을 만들지 않는다.

## 5. 개인정보·감사 경계

- log, metric label, exception, history에는 이름·전화번호·이메일·JWT·상담
  원문·decision payload를 넣지 않는다.
- `actor_ref`는 `SYSTEM`, 내부 opaque staff actor ID, 또는 server secret으로
  계산한 domain-separated HMAC digest만 허용한다. email·전화번호·JWT subject의
  raw hash는 사용하지 않는다.
- `correlation_id`는 1..128자 `[A-Za-z0-9._:-]` opaque 값만 허용한다. newline,
  log-injection, email/phone/JWT 모양은 command boundary에서 거부하고 로그에는
  sanitized 값만 전달한다.
- HMAC key를 교체할 때 새 write는 새 key domain을 사용하고 기존 history를
  재계산하지 않는다. 검증 기간에는 이전 key를 읽기 전용으로 유지한 뒤 폐기
  시점을 감사 기록에 남긴다.

## 6. 명령 결과와 owner

`appointment-core` 서비스 owner가 `selectAndOffer`, `claim`, `release`,
`reconcileWaitlistHolds`의 transaction과 metric을 소유한다. HTTP status,
notification payload, 회원 이름·전화번호 채움은 후속 adapter owner의 책임이다.
`ACCEPTED`는 appointment 생성 완료가 아니라 replacement command가 소비할 수
있는 durable hold가 있다는 뜻이다.

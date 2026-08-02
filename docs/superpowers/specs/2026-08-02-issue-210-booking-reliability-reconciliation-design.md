# Issue #210 예약 신뢰도 V17 reconciliation 설계

상태: 2-R 검토 대기
작성일: 2026-08-02
대상: `bluetape4k/clinic-appointment`
관련 이슈: #210, #176, #170, #208, #209

## 1. 문제와 현재 기준선

Issue #210은 이미 병합된 Issue #176의 booking-reliability V17 계약과, 별도
stale worktree에 남아 있던 재구현을 분리·정렬하기 위한 blocker다. 현재
`origin/develop`와 이 worktree의 기준선은 `a53675e5d24e08c17117dc5224bc71f6d739aa30`이며,
merged PR #207의 신뢰성 구현은 `79d6ea1cf0dd29fd26c224538ca7a09c8df9339d`에서
유래한다.

read-only 조사에서 확인한 stale 변경은 stash
`7a25f7018585ea2724573f5fe7e16355b334083f`의 untracked/dirty 변경이다.
그 변경은 세 dialect에 `V17__add_booking_reliability_events.sql`을 추가하고,
`patient_reference_fingerprint`, waitlist-offer 모델, 다른 event/decision 계약을
도입한다. 현재 authoritative V17은 이미
`V17__add_booking_reliability.sql`로 사건·결정·override·reevaluation-job과
commitment stamp를 정의한다. 따라서 stale 변경을 rebase하거나 migration
version만 renumber하면 drift를 보존하게 된다.

현재 구현에는 별도의 ingress blocker도 있다. `BookingReliabilityEventIngress.accept`
가 trust verification 전에 `QuarantineEnvelopeProtector.protect`를 호출하고,
protector가 booking-reliability envelope bounds를 먼저 검증한다. envelope metadata가
payload와 불일치하거나 raw payload decoding이 실패하면, quarantine으로 전환하기
전에 `IllegalArgumentException`이 발생할 수 있다.

## 2. 결정 요약

1. merged #176/#207의 V17 schema, opaque `MemberId`, decision/override/job ledger,
   commitment stamp를 유일한 authoritative 계약으로 유지한다.
2. stale `patient_reference_fingerprint`, waitlist-offer table/model, 별도 V17
   migration은 이 worktree에 이식하지 않는다. waitlist offer lifecycle은 #170의
   소비자 범위로 남긴다.
3. `BookingReliabilityEventIngress`는 decode와 trust verification을 먼저 수행한다.
   trust failure가 발생하면 그때 `protectUntrusted` quarantine protection을
   수행하고 rejection/quarantine row를 기록한다.
4. `QuarantineEnvelopeProtector`는 기존 검증된 입력용 `protect`와, pre-verification
   실패를 보존하기 위한 tolerant `protectUntrusted` 경계를 분리한다. 정상
   `protect`는 booking-reliability domain bounds를 계속 검증한다. `protectUntrusted`는
   malformed/mismatched envelope를 허용하되 metadata를 bounded string/length/hash로
   canonicalize하여 oversized header가 quarantine allocation을 증폭시키지 않게 한다.
   이 bounded canonicalization은 기존 raw external-fact 경계와 같은 수치를 사용한다:
   identifier 128자, signature 1,024자, payload hash 64자, over-limit 값은 길이와
   앞 256자의 SHA-256 sample hash만 기록한다.
   transport trust와 payload contract 검증의 authoritative owner는 여전히
   `BookingReliabilityEventIngress.verify`와 `SchedulingEventTrustVerifier`다.
5. accepted event 또는 post-verify repository failure에는 기존 `protect` 경계를
   사용하고 caller-owned transaction 결과를 유지한다. 보호된 quarantine envelope는
   trust/repository failure 경로에서만 생성한다.

## 3. 경계와 비목표

### 포함

- 현재 V17 migration이 dialect별로 하나만 존재하는지 검증
- authoritative Kotlin table/model/repository 계약과 stale alternative의 차이 기록
- ingress의 verify-before-protect 순서와 `protectUntrusted` 분리 수정
- malformed raw payload, envelope/payload mismatch, invalid signature의 quarantine
  regression coverage
- H2, PostgreSQL, MySQL migration 및 affected module validation
- exact-head 2-R/3-R/6-R/7-Tier evidence와 reusable lesson

### 제외

- 새로운 Flyway migration 또는 기존 V17 table 변경
- `patient_reference_fingerprint`와 waitlist-offer persistence 도입
- #170 waitlist matching/offer claim lifecycle 구현
- 회원 profile/name/phone 데이터 복제 또는 식별자 해싱 projection 추가
- unrelated Kotlin-pattern cleanup(#209) 또는 historical gate backfill(#208)

## 4. 계약과 데이터 흐름

정상 경로는 다음 순서를 따른다.

```text
raw envelope + raw JSON
    -> size/depth/metadata guard
    -> strict decode
    -> trust verifier (contract/hash/signature/replay)
    -> accepted event repository
```

실패 경로는 다음 순서를 따른다.

```text
verification or accepted-write failure
    -> tolerant protectUntrusted quarantine envelope protection
    -> rejection ledger + quarantine event (caller transaction)
    -> Quarantined result
```

`protect`/`protectUntrusted` 자체의 AES-GCM/key 오류는 안전한 quarantine evidence를 만들 수 없는
인프라 실패이므로 별도 exception/transaction failure로 남긴다. 이 설계가 허용하는
것은 domain validation failure의 uncaught propagation을 막는 것이며, 암호화 실패를
성공으로 위장하는 것이 아니다.

## 5. 실패 모드와 대응

| 실패 모드 | 기대 동작 | 검증 방법 |
|---|---|---|
| raw JSON이 malformed 또는 strict mapping 실패 | `BOOKING_RELIABILITY_MAPPING_FAILED`로 quarantine, uncaught validation exception 없음 | ingress regression test |
| envelope eventId/occurredAt와 payload가 불일치 | `PAYLOAD_CONTRACT_INVALID`로 quarantine | mismatched envelope test |
| signature/key/producer가 허용되지 않음 | 기존 `SchedulingTrustException` reason code로 quarantine | existing invalid-signature test + rerun |
| accepted write가 source-version conflict를 반환 | 기존 conflict reason으로 quarantine하고 event 중복 삽입 금지 | repository/ingress test |
| AES-GCM/key 설정 오류 | quarantine evidence 생성 불가를 명시적으로 실패시키고 transaction rollback | protector unit test 또는 기존 contract |

## 6. 호환성·migration·rollback

- DB migration은 추가하지 않는다. `find`/migration test와 Git diff로 각 dialect의
  `V17__add_booking_reliability.sql` 단일 authoritative 파일을 증명한다.
- 기존 V17 table/column/index 이름과 #207의 public API/error contract를 변경하지
  않는다. accepted/quarantined 결과 타입과 reason code도 유지한다.
- stale stash는 원본 상태로 보존하며 이 worktree에서 apply하지 않는다. 해당 stash와
  현재 기준선의 name/status 비교를 reconciliation evidence로 남긴다.
- rollback은 ingress/protector 변경과 regression test/doc commit을 revert하는
  방식이다. migration rollback이나 data rewrite는 필요하지 않다.

## 7. 검증 계획

1. `git diff --check`와 V17 file inventory로 baseline drift가 없음을 확인한다.
2. 새 mismatch/malformed regression test를 먼저 RED로 실행한다.
3. ingress/protector 최소 수정 후 regression test GREEN을 확인한다.
4. `:appointment-event:test`, 관련 `:appointment-core:test`, H2 migration test를
   실행하고, PostgreSQL/MySQL migration test를 순차 실행한다.
5. Kotlin checklist에서 exception compatibility, Exposed transaction boundary,
   raw payload test, KDoc language, cancellation/locking 영향을 확인한다.
6. exact implementation head에 대해 6-R/7-Tier와 PR CI를 수행한다.

## 8. 수용 기준과 DoD

- [ ] stale work가 authoritative #176 V17과 명시적으로 reconciled되고 duplicate
  `V17__add_booking_reliability_events.sql`가 branch에 존재하지 않는다.
- [ ] persisted schema/model은 opaque `MemberId` 및 decision/override/job ledger와
  일치하고 fingerprint/waitlist 개념을 도입하지 않는다.
- [ ] ingress는 decode/verify 전에 protection을 수행하지 않으며,
  malformed/mismatched payload를 uncaught validation exception 없이 quarantine한다.
- [ ] H2/PostgreSQL/MySQL migration과 ingress/repository regression tests가 fresh
  output으로 통과한다.
- [ ] exact-head 2-R → 3-R → implementation → 6-R/7-Tier 순서와 P0=0/P1=0이
  durable artifact로 기록된다.
- [ ] Kotlin-pattern review에서 raw payload test, assertion API, Korean KDoc,
  Exposed transaction, cancellation/locking을 확인한다.
- [ ] #208과 #209가 follow-up context로 연결되고 unrelated worktree는 변경하지
  않는다.

## 9. 기각한 대안

### A. stale fingerprint/waitlist schema를 새 V17로 채택

기각한다. 이미 병합된 V17과 동일 version을 충돌시키고, opaque `MemberId` 및
decision-ledger 설계를 깨뜨리며, waitlist offer는 #170 소비자 범위다.

### B. stale migration을 V18 등으로 renumber

기각한다. version 충돌만 숨길 뿐 incompatible columns와 누락된 decision/override/job
contract를 보존한다. 새로운 product scope와 별도 approved spec 없이는 migration을
추가하지 않는다.

### C. 기존 `protect`를 그대로 호출하고 ingress에서 예외만 catch

기각한다. pre-verification 실패에서 기존 `protect`를 호출하면 domain validation이
quarantine 전환을 다시 차단한다. 별도 `protectUntrusted` 경계로만 validation을
완화하고 bounded canonicalization을 적용하는 편이 안전성과 책임 분리를 함께 보존한다.

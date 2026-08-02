# Issue #210 booking reliability reconciliation 작업 lesson

## 맥락

Issue #210은 이미 merge된 booking reliability V17 계약과 이전 작업 디렉터리에
남은 stale 설계를 대조하고, trust ingress가 실패 envelope을 안전하게
quarantine하는지 복구하는 Type-A backend 작업이다. authoritative V17의 opaque
`MemberId`/ledger/commitment 경계는 유지하고, waitlist lifecycle은 #170의
소유권으로 남겼다.

## 결정

1. envelope은 trust 검증 전에 `protectUntrusted`로 보호한다. 이 경로는
   identifier 128자, SHA-256 64자, signature 1024자의 상한을 적용하고 초과
   값은 전체 길이와 앞 256자의 SHA-256 표본만 기록한다.
2. trust 검증이 끝난 뒤 repository conflict에는 기존 normal `protect`를
   사용한다. 따라서 정상 verified envelope의 canonical hash 표현과 AES-GCM
   evidence 계약을 바꾸지 않는다.
3. malformed decoder의 `BOOKING_RELIABILITY_MAPPING_FAILED`는 rejection과
   quarantine 양쪽 reason allowlist/trust-failure 분류에 등록한다. decoder
   실패를 raw exception이나 orphan quarantine으로 남기지 않는다.
4. stale `patient_reference_fingerprint`, waitlist-offer schema, duplicate
   V17 migration은 이 issue에서 재도입하지 않는다. 관련 변경은 #170 또는
   별도 승인된 issue의 경계로 유지한다.

## 예상 밖의 실패와 보정

- 첫 RED mismatch는 ingress가 verify 전에 normal `protect`를 호출해
  `eventId does not match envelope`를 예외로 노출했다. 보호 순서를 verify
  이후로 미루는 대신, pre-verification 입력도 bounded evidence로 보존하도록
  tolerant protector를 추가했다.
- 두 번째 RED malformed decoder는 reason 자체가 quarantine repository
  allowlist에 없어 `reasonCode is not allowlisted`로 실패했다. 계획을 보정해
  `BOOKING_RELIABILITY_MAPPING_FAILED`를 두 allowlist에 모두 추가하고 fresh
  3-R을 다시 통과시켰다.
- broad repository `rg`는 기존 V8/V10/V13/V18/profile/waitlist의 합법적인
  fingerprint/waitlist 참조까지 잡아 stale 재유입 판정에 부적합했다. 최종
  검증은 changed path와 정확한 V17 filename을 검사하는 좁은 정적 assertion으로
  바꿨다.

## 결과

- 구현 HEAD `71207014328f1b9fe712d6352e121597fc39d69e`에서 targeted 12건,
  appointment-event 175건, appointment-core 594건, H2/PostgreSQL/MySQL
  migration이 모두 fresh pass했다.
- appointment-event `build`와 `koverVerify`, `git diff --check`, Kotlin
  unsafe-pattern scan, authoritative/stale migration path scan이 통과했다.
- 최종 6-R/7-Tier fallback 리뷰는 `PASS`, `P0=0`, `P1=0`, `P2=0`, `P3=0`이다.
  native `gpt-5.6-luna` reviewer는 runtime capacity/unknown-model 문제로
  생성되지 않아 그 사실을 리뷰 문서에 명시했다.

## 재사용 가능한 검증 증거

- TDD RED: mismatch trust path와 mapping reason allowlist 누락을 각각
  실제 실패로 재현했다.
- TDD GREEN/module: targeted 12, event 175, core 594 passing.
- DB matrix: H2 1, PostgreSQL 1, MySQL 1 migration test passing.
- static: changed Kotlin 5개에 `!!`, `runBlocking`, console output,
  `assertThrows` 0건; authoritative V17 3개; stale duplicate 0개.
- workflow receipt: spec/plan/implementation checks가 sequence 8/9/10/11에서
  통과했고, 최종 verification/delivery 증거는 PR/CI 단계에서 갱신한다.

## 다음 작업의 guard

1. trust verifier가 아직 성공하지 않은 raw envelope에는 normal canonicalizer를
   호출하지 않는다. 새 ingress도 tolerant evidence 경계를 먼저 설계한다.
2. 새 reason code는 decoder/service에서만 추가하지 말고 rejection,
   quarantine allowed/trust-failure/redrive 분류를 한 번에 갱신한다.
3. stale migration 검사는 repository 전체 symbol grep이 아니라 변경 경로와
   migration filename inventory를 함께 사용한다. 기존 authorized history를
   false positive로 해석하지 않는다.
4. PR 생성 전 final 6-R/lesson/receipt verification을 완료하고, merge 전에는
   정확한 PR head와 CI 상태에 묶인 fresh chat approval을 다시 확인한다.

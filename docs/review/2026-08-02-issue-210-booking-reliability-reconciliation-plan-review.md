# Issue #210 예약 신뢰도 V17 reconciliation 계획 3-R 검토

## 검토 범위와 승인된 기준

- 기준선: `origin/develop` `a53675e5d24e08c17117dc5224bc71f6d739aa30`
- 대상 명세: `docs/superpowers/specs/2026-08-02-issue-210-booking-reliability-reconciliation-design.md`
- 대상 계획 commit: `97f9b7460648250fa13bfe000f4af803c36f360b`
- 2-R 명세 검토: `docs/review/2026-08-02-issue-210-booking-reliability-reconciliation-spec-review.md`, `cfb25dafc7a17eeb063b9c978dff13bf870365b2`
- authoritative merged V17: PR #207 merge `79d6ea1cf0dd29fd26c224538ca7a09c8df9339d`
- 보존된 stale 변경: stash `7a25f7018585ea2724573f5fe7e16355b334083f`

계획은 production code를 수정하기 전에 파일 책임, TDD RED→GREEN 순서,
Kotlin/Exposed checklist, H2/PostgreSQL/MySQL 순차 검증, lesson, exact-head
6-R, PR/CI와 fresh merge approval을 고정한다. migration/model 변경은
범위에서 제외하고 merged V17만 검증하도록 한다.

## 검토 레인 제약

2-R과 동일하게 native `gpt-5.6-luna max`를 현재 spawn capability에서 사용할
수 없었다(`Unknown model gpt-5.6-luna`). 따라서 아래 관점은 독립 native lane이
아닌 main-session fallback six-lens review이다. 이 fallback은 별도 독립 에이전트
증거로 가장하지 않으며, 최종 6-R에도 capability gap을 남긴다.

## 3-R 여섯 관점 검토

| 관점 | 판정 | 근거 |
|---|---|---|
| Performance | PASS | metadata 상한(128/1,024/64)과 256자 sample hash를 Task 4에 고정하고 AAD event id도 bounded 처리한다. raw payload나 새 persistence를 추가하지 않으며, 외부 dialect 테스트는 singleton 충돌을 피하도록 순차 실행한다. P0/P1/P2/P3 없음. |
| Stability | PASS | Task 3에서 현재 eager protection blocker를 RED로 재현하고, Task 5에서 verify 전 tolerant/verify 후 정상 protection을 분기한다. AES-GCM/key failure는 catch하여 성공으로 위장하지 않는다. repository failure는 기존 caller transaction과 reason code를 보존한다. P0/P1/P2/P3 없음. |
| Security | PASS | `protectUntrusted`도 AES-GCM evidence/hash/AAD를 유지하며 metadata만 bounded canonicalization한다. opaque `MemberId`, no PII/fingerprint, no waitlist persistence와 stale stash 비적용이 정적 검증에 포함된다. P0/P1/P2/P3 없음. |
| Operator/Ops | PASS | 새 migration을 만들지 않고 세 dialect V17 단일성 및 no stale migration을 검사한다. H2→PostgreSQL→MySQL 순차 command, rollback/revert, PR body/CI/merge gate와 exact head 증거가 계획에 있다. P0/P1/P2/P3 없음. |
| Developer/API | PASS | `fun interface`의 기존 abstract `protect`를 유지하고 default `protectUntrusted`로 lambda 호출자 source compatibility를 지킨다. AES 구현은 tolerant override를 명시하고, spy 테스트로 실제 호출 경계를 검증한다. Kotlin KDoc, Exposed transaction, no `!!`, checklist KT-01~KT-05가 명시되어 있다. P0/P1/P2/P3 없음. |
| User/Caller | PASS | accepted/replay/conflict/invalid-signature 결과와 reason code를 유지하고 malformed/mismatch만 quarantine 결과로 바꾼다. #170 waitlist, #208 backfill, #209 cleanup은 scope 밖이며 PR metadata/merge는 fresh approval으로 분리한다. P0/P1/P2/P3 없음. |

## 통합·의존성 검토

| 검토 항목 | 판정 | 확인 |
|---|---|---|
| 단계 의존성 | PASS | Task 1/2의 3-R이 Task 3 RED보다 먼저이고, Task 3→5가 implementation, Task 6이 module/dialect verification, Task 7이 final review, Task 8/9가 delivery 순서다. |
| 명세 coverage | PASS | authoritative V17, opaque MemberId, bounded canonicalization, verify-before-protect, no migration, three dialects, exact-head reviews, lesson, issue links가 모두 Task 1-9에 매핑된다. |
| API compatibility | PASS | 기존 `QuarantineEnvelopeProtector` lambda 구현을 깨지 않는 default method와 production AES override가 함께 계획되어 있다. post-verify 보호용 envelope에 trusted payload를 copy하는 구체 코드가 있다. |
| 실패/rollback | PASS | crypto failure는 명시적 실패, code/doc/test revert는 data rewrite 없는 rollback, external DB unavailable 시 exact gap evidence라는 대체 경로가 있다. |
| 테스트 품질 | PASS | mismatch RED는 현재 `BookingReliabilityEventBounds.validate`의 uncaught exception을 직접 재현한다. malformed mapping, bounded protector, protection path spy, 기존 accepted/replay/conflict를 함께 검증한다. |

## Step 3-P risk prediction

| 위험 신호 | 예방/탐지 | 잔여 위험 |
|---|---|---|
| 검증 전 domain bounds가 quarantine을 막음 | Task 3 mismatch RED, Task 5 tolerant branch, Task 5 spy | 없음; AES failure는 의도적으로 별도 실패 |
| oversized metadata가 ciphertext/AAD를 증폭 | Task 4 boundedString, 256자 sample hash, bounded AAD unit test | raw payload object 자체의 domain bound는 기존 verifier 소유로 유지 |
| custom lambda protector의 API break | default `protectUntrusted`, 기존 lambda compile/test | production custom implementation은 없음을 source inventory로 확인 |
| raw/verified payload 차이로 post-verify protect 재실패 | `rawEnvelope.copy(payload = trusted.payload)` 및 conflict spy | 없음; trusted verifier가 payload contract owner |
| H2/PG/MySQL 환경 간 migration drift | V17 inventory + three sequential Flyway tests | 외부 DB가 unavailable일 때 fresh PG/MySQL output은 환경 gap으로 남음 |
| stale waitlist/fingerprint migration 재유입 | stash 비적용, no-file/no-symbol static assertions, lesson | 새 별도 issue/spec 없이는 scope 확장 금지 |

## 심각도 집계

| 등급 | 수량 | 처리 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 없음 |
| P3 | 0 | 없음 |

## Step 3-R verdict: PASS

계획은 승인된 명세를 executable TDD 단계와 정확한 파일/심볼/명령으로
변환했고, protection 경계 선택을 spy 테스트로 직접 증명하도록 보강했다.
P0/P1/P2/P3 blocker가 없으므로 risk gate를 통과하고 구현으로 진행한다.
구현 시작 조건은 이 artifact와 계획 commit `97f9b746...`의 durable 기록이며,
첫 production 수정은 Task 3 RED 실행 이후에만 허용한다.

## Kotlin checklist trigger map

| Checklist | 대상 | 3-R 판정 |
|---|---|---|
| KT-01 | Kotlin source/test, AES-GCM helper, Exposed fixture, KDoc | PASS |
| KT-02 | ingress/protector callers, ExternalFact bounded helper, current tests | PASS |
| KT-03 | exception compatibility, transaction ownership, no logging/PII, API default | PASS |
| KT-04 | targeted RED/GREEN, module tests, diff check, three dialect tests | PASS 계획 |
| KT-05 | final checklist artifact and P0/P1 convergence | final 6-R에서 완료 |


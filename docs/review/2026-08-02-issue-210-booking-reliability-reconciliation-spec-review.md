# Issue #210 예약 신뢰도 V17 reconciliation 명세 2-R 검토

## 검토 범위와 기준선

- 대상 저장소: `bluetape4k/clinic-appointment`
- 기준선: `origin/develop` `a53675e5d24e08c17117dc5224bc71f6d739aa30`
- 검토 대상 명세: `docs/superpowers/specs/2026-08-02-issue-210-booking-reliability-reconciliation-design.md`
- 명세 commit: `7f7538c4cda4a58bef4c9a21e81d4da0609234b3`
- authoritative merged 구현: PR #207 merge `79d6ea1cf0dd29fd26c224538ca7a09c8df9339d`
- reconciliation 대상 stale 보관본: stash `7a25f7018585ea2724573f5fe7e16355b334083f`

검토는 명세의 결정, 범위, failure flow, migration 정책, 검증 계획과 현재
구현/보관된 stale 변경의 관계를 read-only로 대조했다. 현재 코드에서
`BookingReliabilityEventIngress.accept`는 `verify` 전에 `protect`를 호출하며
(`appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/BookingReliabilityEventIngress.kt:45-59`),
`QuarantineEnvelopeProtector.protect`는 booking-reliability bounds를 먼저 검증한다
(`appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineEnvelopeProtector.kt:52-70`).
이 순서가 malformed/mismatched 입력을 quarantine 전에 예외로 전파할 수 있다는
명세의 핵심 문제 진술과 일치한다.

## 검토 레인 제약

워크플로가 요구한 네이티브 `gpt-5.6-luna max` 독립 레인은 현재
`spawn_agent` capability에서 사용할 수 없었고, 해당 모델로의 trial spawn은
`Unknown model gpt-5.6-luna`로 거절되었다. 따라서 아래 여섯 관점은 **네이티브
독립 에이전트 증거가 아닌**, 동일한 2-R 체크리스트를 메인 세션에서 분리해
수행한 fallback 검토다. 이 제약을 숨기지 않고 3-R 및 최종 6-R 산출물에도
동일하게 기록한다.

## 2-R 여섯 관점 검토

| 관점 | 검토 결과 | 근거와 판정 |
|---|---|---|
| Performance | PASS | tolerant protection은 metadata를 128/1,024/64자로 제한하고 초과값은 앞 256자 sample hash로 축약한다. `ExternalFactEventConsumer`의 기존 `boundedString` 경계(동일 상한)를 재사용하도록 명시해 header 크기에 따른 ciphertext/allocation 증폭을 막는다. raw payload를 새 product persistence로 복제하지 않는다. P0/P1 없음. |
| Stability | PASS | decode/verify 전 protection을 분리하고, pre-verification failure만 `protectUntrusted`로 보존한다. AES-GCM/key 실패는 성공으로 위장하지 않는 별도 infrastructure failure로 남긴다. 기존 accepted/rejected 결과와 caller-owned transaction은 유지한다. P0/P1 없음. |
| Security | PASS | opaque `MemberId`와 기존 V17 ledger를 유지하고 PII/fingerprint/waitlist persistence를 도입하지 않는다. tolerant path는 validation을 완화할 뿐 암호화/AAD/hash evidence를 제거하지 않으며, bounded canonicalization으로 untrusted metadata를 제한한다. P0/P1 없음. |
| Operator/Ops | PASS | migration을 추가하지 않고 dialect별 authoritative `V17__add_booking_reliability.sql` 단일성을 검증한다. rollback은 ingress/protector와 회귀 테스트/doc commit revert로 정의되어 data rewrite나 migration rollback을 요구하지 않는다. reason code와 quarantine row의 기존 운영 흐름도 유지한다. P0/P1 없음. |
| Developer/API | PASS | `protect`는 검증된 입력 경계로 보존하고 별도 `protectUntrusted`를 추가해 책임을 명확히 한다. 기존 `QuarantineEnvelopeProtector` 호출자 계약을 깨지 않는 default/override 방식을 계획에 요구하며, Kotlin KDoc·Exposed transaction·raw payload 테스트를 수용 기준에 포함했다. P0/P1 없음. |
| User/Caller | PASS | 정상 accepted 결과와 replay/source-version conflict/invalid signature reason code를 보존한다. malformed raw payload와 envelope/payload mismatch는 uncaught `IllegalArgumentException` 대신 quarantine 결과가 되도록 공개 ingress 동작을 안정화하며, #170 waitlist lifecycle과 #209 cleanup은 범위 밖으로 고정한다. P0/P1 없음. |

## 메인 세션 통합 검토

| 통합 항목 | 판정 | 확인 내용 |
|---|---|---|
| 중복·계약 | PASS | 현재 dialect 세 곳에 `V17__add_booking_reliability.sql`이 각각 하나씩 존재하며, stash에만 `V17__add_booking_reliability_events.sql` 대안이 있다. merged #176/#207의 commitment stamp, decision/override/job ledger, opaque `MemberId`를 authoritative로 고정했다. |
| 이슈 경계 | PASS | #210은 reconciliation과 ingress trust boundary remediation만 담당한다. fingerprint/waitlist-offer와 #170 lifecycle, #208 historical gate backfill, #209 Kotlin cleanup은 이 worktree에 이식하지 않는다. |
| 실패 의미 | PASS | `verify` 이전에는 tolerant protection, verified 후 repository failure에는 기존 `protect`를 사용한다. 보호 자체의 암호화 실패를 quarantine 성공으로 삼지 않는다는 불변식이 명시되어 있다. |
| 검증 가능성 | PASS | RED-first mismatch/malformed regression, GREEN affected tests, H2/PostgreSQL/MySQL migration checks, Kotlin-pattern review, exact-head 6-R/7-Tier를 순서대로 요구한다. |
| 문서·추적성 | PASS | 명세가 #208/#209와 stale stash/merged PR을 연결하고, 이후 3-R·6-R·lesson·PR body에 같은 decision boundary를 전파하도록 한다. |

## 심각도 집계와 결론

| 등급 | 수량 | 처리 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 | 없음 |
| P2 | 0 | 없음 |
| P3 | 0 | 없음 |

### Step 2-R verdict: PASS

승인된 명세는 현재 merged V17과 stale stash의 drift를 정확히 분리하고,
pre-verification trust failure를 위한 bounded `protectUntrusted` 경계를
추가하면서 기존 public result/reason/transaction 계약을 보존한다. P0/P1
blocker가 없으므로 다음 게이트인 3-R 계획 검토로 진행한다. 구현은 3-R
검토 artifact가 PASS가 된 뒤에만 시작한다.

## 수용 기준 매핑

1. duplicate V17/fingerprint/waitlist drift 배제: 명세 1-3절, migration 계획.
2. verify-before-protect와 tolerant quarantine: 명세 2-5절, failure mode 표.
3. bounded metadata: identifier 128, signature 1,024, payload hash 64, sample
   hash 256자 규칙을 명세 2절에 고정.
4. dialect/ingress regression: 명세 7-8절의 H2/PostgreSQL/MySQL 및 RED/GREEN 순서.
5. exact-head review/CI/lesson/issue links: 명세 7-8절과 후속 workflow gate.


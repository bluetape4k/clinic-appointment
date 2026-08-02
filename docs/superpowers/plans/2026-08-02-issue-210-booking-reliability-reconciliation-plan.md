# 예약 신뢰도 V17 reconciliation 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 이미 병합된 booking-reliability V17 계약을 보존하면서 pre-verification trust failure가 bounded quarantine으로 수렴하도록 ingress/protector와 회귀 검증을 보완한다.

**Architecture:** `QuarantineEnvelopeProtector.protect`는 검증된 envelope 경계를 유지하고, `protectUntrusted`는 decode/verify 이전 실패를 위한 tolerant·bounded metadata 경계로 분리한다. ingress는 strict decode와 trust verification을 먼저 수행하며, 검증 전 실패에는 tolerant protection을, 검증 후 repository 실패에는 검증된 payload를 반영한 기존 protection을 사용한다. DB migration/model 계약은 변경하지 않는다.

**Tech Stack:** Kotlin 2.3, Java 25, Spring Boot 4, Exposed ORM, JUnit 5, MockK-free constructor fixtures, Flyway H2/PostgreSQL/MySQL tests, AES-GCM.

---

## 파일 책임과 변경 범위

| 파일 | 책임 | 변경 유형 |
|---|---|---|
| `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineEnvelopeProtector.kt` | 검증된/tolerant protection API, bounded canonical metadata, bounded AAD | 수정 |
| `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/BookingReliabilityEventIngress.kt` | verify-before-protect 순서, pre/post verification failure routing | 수정 |
| `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/reliability/BookingReliabilityEventIngressTest.kt` | mismatch/malformed quarantine regression | 수정 |
| `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineEnvelopeProtectorTest.kt` | tolerant protection 및 metadata bound regression | 수정 |
| `docs/review/2026-08-02-issue-210-booking-reliability-reconciliation-spec-review.md` | 2-R 명세 검토 증거 | 이미 작성 |
| `docs/review/2026-08-02-issue-210-booking-reliability-reconciliation-plan-review.md` | 3-R 계획 검토 증거 | 생성 |
| `docs/lessons/2026-08-02-issue-210-booking-reliability-reconciliation.md` | 재사용 가능한 migration/trust boundary lesson | 생성 |
| `appointment-api/src/main/resources/db/migration/{h2,mysql,postgresql}/V17__add_booking_reliability.sql` | merged V17 authoritative schema | 수정하지 않음 |

stale stash `7a25f7018585ea2724573f5fe7e16355b334083f`는 apply하지 않는다. 새
Flyway migration, `patient_reference_fingerprint`, waitlist-offer persistence,
`MemberId` 변경은 이 계획의 산출물이 아니다.

## Task 1: 2-R 이후 계획 자체 검증 및 3-R 준비

**Files:**
- Read: `docs/superpowers/specs/2026-08-02-issue-210-booking-reliability-reconciliation-design.md`
- Read: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/ExternalFactEventConsumer.kt`
- Read: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/SchedulingEventTrustVerifier.kt`

- [ ] **Step 1: 명세-계획 수용 기준을 대조한다**

  명세의 authoritative V17, opaque `MemberId`, verify-before-protect,
  `protectUntrusted`, metadata 상한, 세 dialect, exact-head review 요구가 아래
  Task 2-8에 각각 연결되는지 확인한다. 연결되지 않는 요구가 있으면 계획과
  3-R artifact를 먼저 고친다.

- [ ] **Step 2: 재사용 helper와 예외 계약을 고정한다**

  `ExternalFactEventConsumer`의 `boundedString` 규칙을 source of truth로 삼고,
  기존 `SchedulingTrustException` reason code와 `QuarantineEnvelopeProtector`
  호출자 호환성을 유지한다. 새 dependency나 raw JDBC를 추가하지 않는다.

- [ ] **Step 3: Kotlin trigger map을 기록한다**

  `bluetape-kotlin-patterns`의 testing/checklist reference를 적용한다. 이번
  변경에는 Kotlin production/test, AES-GCM deterministic evidence, Exposed
  transaction fixture, public/internal KDoc이 포함되므로 testing과 checklist
  row를 3-R 및 최종 6-R artifact에 명시한다.

## Task 2: 3-R 계획 검토 artifact 작성

**Files:**
- Create: `docs/review/2026-08-02-issue-210-booking-reliability-reconciliation-plan-review.md`

- [ ] **Step 1: 여섯 관점과 통합 검토를 수행한다**

  performance, stability, security, operator/Ops, developer/API, user/caller와
  main-session integration을 같은 계획에 대조한다. 각 관점에는 파일/심볼,
  수용 기준, P0/P1/P2/P3 판정을 적고, 네이티브 `gpt-5.6-luna max`를 사용할 수
  없었던 경우 메인 세션 fallback임을 반복해서 숨김없이 기록한다.

- [ ] **Step 2: risk prediction을 기록한다**

  crypto evidence 손실, pre-verification metadata allocation, 기존 fun-interface
  호출자 호환성, H2/외부 dialect 순차 실행, transaction rollback을 위험 신호로
  기록하고 각 완화책을 Task 3-7에 연결한다.

- [ ] **Step 3: P0/P1이 0인지 확인하고 commit한다**

  `git diff --check`와 미완성 표식 scan 후 다음 형식의 Lore commit을 만든다.

  ```text
  Approve the booking reliability implementation plan

  Record the 3-R review, risk controls, and executable TDD sequence.

  Constraint: Implementation is blocked until the approved plan has durable 3-R evidence.
  Rejected: Importing stale V17 or waitlist schema | it conflicts with merged #176/#207 ownership.
  Confidence: high
  Scope-risk: narrow
  Directive: Preserve protect/protectUntrusted separation and run external dialect tests sequentially.
  Tested: plan self-review; unfinished-marker scan; diff check.
  Not-tested: implementation and CI remain pending.
  ```

## Task 3: RED 테스트로 verify-before-protect 회귀를 고정한다

**Files:**
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/reliability/BookingReliabilityEventIngressTest.kt`

- [ ] **Step 1: envelope/payload mismatch 테스트를 먼저 추가한다**

  기존 `envelope(event)` fixture를 사용해 envelope `eventId`를 payload의
  `eventId`와 다르게 만들고, 동일한 valid tenant/clinic과 signature를 유지한다.
  `transaction { ingress(event).accept(mismatchedEnvelope, VALID_RAW_PAYLOAD) }`의
  결과가 `BookingReliabilityIngressResult.Quarantined`이고 reason code가
  `PAYLOAD_CONTRACT_INVALID`이며, `SchedulingQuarantineEvents`와
  `UntrustedSchedulingEventRejections`가 각각 한 row인지 검증한다.

  ```kotlin
  @Test
  fun `envelope payload mismatch is quarantined instead of escaping validation`() {
      val event = signalEvent()
      val mismatched = envelope(event).copy(eventId = "envelope-event-2")

      val result = transaction { ingress(event).accept(mismatched, VALID_RAW_PAYLOAD) }

      result as BookingReliabilityIngressResult.Quarantined
      result.reasonCode shouldBeEqualTo "PAYLOAD_CONTRACT_INVALID"
      transaction {
          SchedulingQuarantineEvents.selectAll().toList().shouldHaveSize(1)
          UntrustedSchedulingEventRejections.selectAll().toList().shouldHaveSize(1)
      }
  }
  ```

- [ ] **Step 2: malformed decoder 테스트를 추가한다**

  `ingress` fixture에 decoder lambda가 `IllegalArgumentException("malformed")`를
  던지는 변형을 만들고 `BOOKING_RELIABILITY_MAPPING_FAILED` quarantine을
  검증한다. 이 테스트는 mismatch 테스트와 달리 strict decoder failure reason
  contract가 유지되는지 증명한다.

- [ ] **Step 3: 새 테스트만 RED로 실행한다**

  ```bash
  ./gradlew :appointment-event:test \
    --tests 'io.bluetape4k.clinic.appointment.event.reliability.BookingReliabilityEventIngressTest'
  ```

  기대 결과는 현재 `QuarantineEnvelopeProtector.protect`의
  `IllegalArgumentException`이 quarantine row 작성 전에 전파되어 테스트가
  실패하는 것이다. RED가 아니면 fixture가 실제 blocker를 재현하지 않는 것이므로
  fixture를 수정한 뒤 다시 실행하고, production code는 아직 변경하지 않는다.

## Task 4: tolerant protector를 최소 구현한다

**Files:**
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineEnvelopeProtector.kt:28-125`
- Test: `appointment-event/src/test/kotlin/io/bluetape4k/clinic/appointment/event/integration/QuarantineEnvelopeProtectorTest.kt`

- [ ] **Step 1: 호환 가능한 API 경계를 추가한다**

  기존 abstract `protect`는 그대로 두고 interface에 다음 default method를
  추가한다. `fun interface`의 유일한 abstract method는 변하지 않으므로 기존
  lambda 호출자는 compile compatibility를 유지한다.

  ```kotlin
  /** trust 검증 전에 관측된 envelope를 bounded evidence로 보호합니다. */
  fun protectUntrusted(
      envelope: UntrustedSchedulingEventEnvelope<*>,
  ): ProtectedQuarantineEnvelope = protect(envelope)
  ```

- [ ] **Step 2: AES-GCM 구현에서 tolerant override를 분리한다**

  `AesGcmQuarantineEnvelopeProtector.protect`의 기존 Purchase/Profile/Booking
  bounds validation은 유지한다. `protectUntrusted`는 해당 domain bounds를
  호출하지 않고 같은 AES-GCM, `scope`, evidence hash를 사용한다. 두 경로 모두
  `keyId`와 AES key size invariant를 유지하며 cipher/key 오류는 그대로
  전파한다.

- [ ] **Step 3: canonical metadata를 bounded로 만든다**

  `canonicalBytes` metadata의 모든 identifier/issuer/audience/key/algorithm/
  correlation 값에는 `boundedString(name, value, 128)`, payload hash에는 64,
  signature에는 1,024 상한을 사용한다. 초과 시 frame에는 전체 길이와 앞 256자
  UTF-8 sample의 SHA-256만 쓴다. valid 기존 envelope는 representation이
  동일하므로 stable hash test가 유지된다.

  AAD event id도 `aadComponent`로 bounded 처리한다. 허용 범위 안의 safe
  identifier는 그대로 사용하고, 초과/unsafe 값은
  `invalid:<length>:<sampleHash>`로 대체해 AAD 자체가 unbounded가 되지 않게
  한다. 새 helper는 `ExternalFactEventConsumer`의 `MessageDigest`/writer
  패턴을 재사용하며 raw metadata 전체 복제나 로그 출력은 하지 않는다.

- [ ] **Step 4: tolerant protector unit test를 추가한다**

  2,000자 signature와 200자 event id를 가진 envelope에서
  `protectUntrusted`가 예외 없이 64-hex hash와 non-null ciphertext를 반환하고,
  ciphertext가 원문 header 전체를 포함하지 않음을 검증한다. 기존 `protect`
  stable hash/randomized ciphertext와 invalid key-size 테스트도 그대로 실행한다.

## Task 5: ingress 순서를 교정한다

**Files:**
- Modify: `appointment-event/src/main/kotlin/io/bluetape4k/clinic/appointment/event/integration/BookingReliabilityEventIngress.kt:45-125`

- [ ] **Step 1: pre-verification failure는 tolerant path로 이동한다**

  `accept` 첫 줄의 eager `protect`를 삭제하고 먼저 `verify(rawEnvelope, rawPayload)`를
  실행한다. `SchedulingTrustException`을 잡는 branch에서만
  `quarantineEnvelopeProtector.protectUntrusted(rawEnvelope)`를 호출한 뒤
  기존 `quarantine`/reason code 흐름을 사용한다. decoder의 broad catch는 현재
  `verify` 내부에서 `BOOKING_RELIABILITY_MAPPING_FAILED`로 변환되므로
  `CancellationException`을 삼키는 새 broad catch를 추가하지 않는다.

- [ ] **Step 2: post-verification repository failure는 정상 path로 유지한다**

  verify 결과에서 trusted payload를 반영한 보호용 envelope를 만든다.

  ```kotlin
  val verifiedEnvelope = rawEnvelope.copy(payload = trusted.payload)
  val eventRecordId = try {
      eventRepository.recordAccepted(trusted)
  } catch (failure: SchedulingTrustException) {
      return quarantine(
          verifiedEnvelope,
          quarantineEnvelopeProtector.protect(verifiedEnvelope),
          failure.reasonCode,
      )
  }
  ```

  이렇게 하면 strict decoder가 raw envelope의 임시 payload와 다른 payload를
  반환해도 post-verify protection이 다시 mismatch validation에 걸리지 않는다.
  accepted 정상 결과와 caller-owned transaction은 변경하지 않는다.

- [ ] **Step 3: GREEN 대상 테스트를 실행한다**

  ```bash
  ./gradlew :appointment-event:test \
    --tests 'io.bluetape4k.clinic.appointment.event.reliability.BookingReliabilityEventIngressTest' \
    --tests 'io.bluetape4k.clinic.appointment.event.integration.QuarantineEnvelopeProtectorTest'
  ```

  기대 결과는 기존 7개와 새 mismatch/malformed/bounded 테스트가 모두 PASS하고,
  accepted replay/source-version conflict/invalid signature reason code가
  변하지 않는 것이다.

- [ ] **Step 4: protection path 선택을 직접 검증한다**

  `QuarantineEnvelopeProtector`를 감싼 test spy를 fixture에 주입해
  `protectUntrusted` 호출 수와 `protect` 호출 수를 별도로 센다. mismatch 또는
  mapping failure에서는 tolerant 호출만 1회, source-version conflict처럼
  verified repository failure에서는 정상 `protect`만 1회여야 한다. spy는
  `ProtectedQuarantineEnvelope(ciphertext = "ciphertext", keyId = "key", envelopeHash = "a".repeat(64))`
  를 반환하고 AES 자체의 안정성은 Task 4 unit test가 담당한다. 이 검증으로
  두 경계가 이름만 분리되고 실제 ingress 흐름에서 뒤섞이는 회귀를 막는다.

## Task 6: Kotlin/Exposed 및 migration 계약을 검증한다

**Files:**
- Read-only inventory: `appointment-api/src/main/resources/db/migration/h2/V17__add_booking_reliability.sql`
- Read-only inventory: `appointment-api/src/main/resources/db/migration/mysql/V17__add_booking_reliability.sql`
- Read-only inventory: `appointment-api/src/main/resources/db/migration/postgresql/V17__add_booking_reliability.sql`
- Test anchors: `appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/migration/BookingReliabilityMigrationTestSupport.kt`

- [ ] **Step 1: V17 단일성을 정적 검증한다**

  ```bash
  test "$(find appointment-api/src/main/resources/db/migration -type f -name 'V17__add_booking_reliability.sql' | wc -l | tr -d ' ')" -eq 3
  test "$(find appointment-api/src/main/resources/db/migration -type f -name 'V17__add_booking_reliability_events.sql' | wc -l | tr -d ' ')" -eq 0
  ! rg -n 'patient_reference_fingerprint|waitlist_offer' appointment-event appointment-core appointment-api/src/main/resources/db/migration
  ```

  이 검증은 stale stash를 checkout하거나 적용하지 않고 현재 branch만 검사한다.

- [ ] **Step 2: affected module tests를 실행한다**

  ```bash
  ./gradlew :appointment-event:test
  ./gradlew :appointment-core:test
  ```

  Exposed는 모든 fixture가 `transaction {}` 안에서 실행되는지, `SchemaUtils.createMissingTablesAndColumns(Table)`와 `Table.deleteAll()` 규칙을 지키는지 확인한다. 이번 production 변경에는 새로운 DB write가 없으므로 transaction boundary를 확장하지 않는다.

- [ ] **Step 3: Flyway dialect를 순차 검증한다**

  무거운 singleton DB/컨테이너 충돌을 피하기 위해 한 번에 하나씩 실행한다.

  ```bash
  ./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayMigrationTest'
  ./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayPostgreSQLMigrationTest'
  ./gradlew :appointment-api:test --tests 'io.bluetape4k.clinic.appointment.api.migration.FlywayMySQLMigrationTest'
  ```

  H2/PG/MySQL 각각에서 V17이 한 번 실행되고, commitment/event/decision/override/
  reevaluation-job table 계약이 유지되는 fresh output을 보관한다. 외부 DB가
  환경상 실행되지 않으면 exact command와 blocker를 기록하고 static inventory와
  H2 결과를 대체 증거로 남긴다.

## Task 7: 전체 검토·lesson 산출물을 만든다

**Files:**
- Create: `docs/lessons/2026-08-02-issue-210-booking-reliability-reconciliation.md`
- Create: `docs/review/2026-08-02-issue-210-booking-reliability-reconciliation-final-review.md`

- [ ] **Step 1: lesson을 기록한다**

  lesson에는 stale migration을 renumber하지 않고 authoritative merged contract를
  먼저 확인한 절차, verify-before-protect invariant, bounded canonicalization 상한,
  receipt/CI 재발 방지 규칙을 Korean prose로 기록한다. 구현 결과와 검증 command를
  실제 output에 맞춰 적고 추측성 개선은 포함하지 않는다.

- [ ] **Step 2: exact implementation HEAD 6-R/7-Tier를 수행한다**

  performance, stability, security, operator/Ops, developer/API, user/caller와
  main integration을 현재 diff와 fresh tests에 대조한다. `bluetape-kotlin-patterns`
  checklist의 KT-01~KT-05를 모두 표시하고 P0/P1/P2/P3 수량, N/A와 tooling gap을
  명시한다. native luna lane 불가 fallback은 2-R/3-R과 동일하게 공개한다.

- [ ] **Step 3: final review artifact에 수용 기준을 매핑한다**

  duplicate V17 없음, opaque MemberId 유지, no waitlist/fingerprint, malformed/
  mismatch quarantine, H2/PG/MySQL evidence, #208/#209 links, diff check,
  rollback path를 exact commit/file/line에 매핑한다. P0=0/P1=0이 아니면 PR 단계로
  진행하지 않고 해당 finding을 먼저 수정한다.

## Task 8: PR/CI 및 merge 전 handoff

**Files:**
- PR: `bluetape4k/clinic-appointment`, base `develop`, head `fix/issue-210-booking-reliability-reconcile`

- [ ] **Step 1: branch diff와 public metadata를 확인한다**

  `git diff --check origin/develop...HEAD`, changed-path inventory, exact HEAD,
  unrelated `.worktrees/issue-209-kotlin-patterns` 보존을 확인한다. PR assignee는
  `debop`, labels는 `bug,maintenance,test`, milestone/issue linkage는 #210과
  일치시킨다. PR body는 English로 작성하고 마지막 section을 반드시 `## DoD Status`
  로 둔다.

- [ ] **Step 2: push/PR/CI를 순서대로 검증한다**

  feature branch를 push하고 PR을 만든 뒤 live PR body, review threads, status
  checks를 exact head로 다시 읽는다. required checks는 `Required checks X/Y;
  N/A N; Blocked 0` 형식으로 기록한다. CI가 실패하면 원인별로 고친 뒤 같은
  validation 순서를 반복한다.

- [ ] **Step 3: merge-ready report에서 멈춘다**

  CI green, exact-head final review, issue/PR metadata parity, worktree status와
  cleanup 대상이 모두 증명된 뒤 merge-ready report를 만든다. merge는 별도
  fresh approval이 exact PR head에 묶여야 하며, 그 전에는 auto-merge를 켜지 않는다.

## Task 9: fresh approval 이후 merge·sync·cleanup

- [ ] **Step 1: exact head와 fresh approval을 재확인한다**

  승인 직전에 PR head SHA, CI, reviews, mergeability, unresolved threads를
  다시 조회하고 승인 메시지가 그 SHA를 명시하는지 확인한다.

- [ ] **Step 2: merge 후 local develop을 동기화한다**

  PR merge SHA를 확인하고 main worktree `develop`을 `origin/develop`으로 fast-forward
  한다. `git status --short --branch`, `git rev-parse HEAD`, `git rev-parse
  origin/develop`가 동일한 clean state여야 한다.

- [ ] **Step 3: proven merged worktree만 정리한다**

  `git worktree list --porcelain`로 merge된 feature worktree와 branch만 식별해
  제거한다. `.worktrees/issue-209-kotlin-patterns`와 그 branch는 보존한다. 마지막
  receipt/DoD report에 merge SHA, local parity, cleanup 결과와 남은 위험을 기록한다.

## 롤백과 실패 처리

- production 변경 rollback은 ingress/protector와 regression test/doc commit을
  revert한다. schema/data rewrite는 하지 않는다.
- AES-GCM key/cipher failure는 tolerant fallback으로 성공 처리하지 않고 원래
  exception/transaction failure를 유지한다.
- H2는 통과하지만 PG/MySQL이 실패하면 dialect별 SQL을 새로 만들지 않고 기존
  authoritative V17과 test harness를 원인별로 고친다. stale migration을
  renumber하는 복구는 허용하지 않는다.
- 구현 전 2-R/3-R artifact, 구현 후 6-R/7-Tier artifact가 없으면 PR 생성과
  merge-ready 판정을 하지 않는다.

## 계획 자체 검증

  - 명세 1-9절의 authoritative contract, trust boundary, bounds, migration,
  validation, DoD 요구를 Task 1-9에 모두 매핑했다.
  - 미완성 표식이나 임의의 미정 단계를 계획 내용으로 사용하지 않았다.
- API 이름은 현재 source vocabulary(`protect`, `protectUntrusted`, `verify`,
  `BookingReliabilityEventBounds`, `CanonicalFrameWriter`)와 일치한다.
- 구현은 Task 3 RED와 Task 4-5 GREEN 이후에만 시작하며, Task 2 3-R PASS 이전에는
  production code를 수정하지 않는다.

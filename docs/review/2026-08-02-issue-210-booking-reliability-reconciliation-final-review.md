# Issue #210 booking reliability reconciliation 최종 사전 PR 리뷰

## 결론

- 판정: `PASS`
- P0: `0`
- P1: `0`
- P2: `0`
- P3: `0`
- 리뷰 기준점: `71207014328f1b9fe712d6352e121597fc39d69e`
- 비교 기준: `origin/develop` (`a53675e5d24e08c17117dc5224bc71f6d739aa30`)
- 작업 경계: `fix/issue-210-booking-reliability-reconcile`

이번 리뷰는 exact HEAD의 구현·테스트·문서·workflow evidence를 기준으로
수행한 main-session 6-lens fallback 리뷰다. native `gpt-5.6-luna` review
lane은 현재 runtime에서 `Unknown model`로 시작할 수 없어 독립 native reviewer
결과로 가장하지 않았다. 대신 성능, 안정성, 보안, 운영, 개발자/API,
사용자/호출자 관점을 동일 diff에 각각 적용했다.

## 변경 의도와 경계

검증 전에 입력된 booking reliability envelope을 먼저 저장 가능한 bounded
evidence로 보호하고, 검증이 끝난 뒤 repository conflict에는 기존 정상
`protect` 경로를 사용하는 순서를 확정했다. 이미 merge된 V17 계약과 opaque
`MemberId`, decision/override/job ledger, commitment stamp는 유지했다.
stale waitlist/fingerprint migration이나 중복 V17 migration은 추가하지
않았다. malformed decoder의 `BOOKING_RELIABILITY_MAPPING_FAILED`는 quarantine
repository의 trust-failure 및 allowed reason contract에 함께 등록했다.

## 6-R / 7-Tier 결과

| Tier | 판정 | exact-head 근거 |
|---|---|---|
| Performance | `PASS` | pre-verification metadata는 128/64/1024 길이 상한과 256-char sample hash만 사용하며, 보호 plaintext가 입력 크기에 따라 무제한 증폭되지 않는다. 새 DB query/index 또는 broad scan은 없다. |
| Stability | `PASS` | verify 실패는 `protectUntrusted`로, verify 후 repository trust conflict는 normal `protect`로 분리했다. replay/duplicate quarantine와 malformed mapping 회귀가 통과했다. |
| Security | `PASS` | AES-GCM ciphertext와 envelope hash를 유지하고 raw payload/PII를 로그나 새 저장 컬럼에 넣지 않는다. invalid metadata는 길이와 표본 hash만 canonical frame/AAD에 남긴다. |
| Operations | `PASS` | 실패 reason이 rejection/quarantine 양쪽에 저장되고, `BOOKING_RELIABILITY_MAPPING_FAILED`가 redrive allowlist에서 누락되지 않는다. caller-owned transaction 경계를 유지한다. |
| Developer/API | `PASS` | 기존 `protect` abstract contract와 lambda source compatibility를 유지하고 default `protectUntrusted`를 추가했다. 새 Korean KDoc와 spy regression이 호출 경계를 설명한다. |
| User/Caller | `PASS` | 신뢰 실패가 예외로 탈출하지 않고 `Quarantined(reasonCode)`로 귀결되며, 검증된 repository 충돌도 기존 bounded envelope로 재처리할 수 있다. |
| Integration | `PASS` | authoritative V17 migration 3개, stale duplicate 0개, H2/PostgreSQL/MySQL migration, event/core module evidence와 issue #210/#208/#209 경계가 일치한다. |

## Kotlin 최종 체크리스트

| 항목 | 판정 | 증거 |
|---|---|---|
| KT-01 / KT-FIN-06 | `PASS` | `bluetape-kotlin-patterns`, `references/testing.md`, `references/checklist.md`를 로드했다. Exposed fixture는 기존 repository/transaction 패턴을 재사용했다. |
| KT-02 / KT-FIN-01 | `PASS` | ingress, protector, quarantine repository, callers/tests와 merged V17/stale stash 경계를 exact diff에서 확인했다. |
| KT-03 / KT-FIN-02~05 | `PASS` | caller trust failure는 `SchedulingTrustException`, lifecycle repository conflict도 기존 계약을 유지한다. 새 `!!`, `runBlocking`, blocking coroutine, monitor, Exposed boundary 변경이 없다. |
| KT-04 / KT-FIN-09~10 | `PASS` | targeted/module/build, 세 dialect migration, static scan, `git diff --check`가 모두 fresh pass했다. LSP/AST transport는 사용하지 않고 Gradle compile/build로 대체했다. |
| KT-05 / KT-FIN-11 | `PASS` | X=Y, Blocked=0, P0/P1/P2/P3=0이며 변경 범위는 9개 파일로 제한됐다. |
| KT-TEST-01 | `PASS` | JUnit 5, bluetape4k assertions, `assertFailsWith`, descriptive test names와 Given/When/Then 흐름을 사용했다. |
| KT-TEST-02 | `N/A` | coroutine cancellation 또는 concurrent mutable state를 새로 노출하지 않는 동기 ingress 변경이다. |
| KT-TEST-03 | `PASS` | 기존 singleton/공유 DB 규칙을 변경하지 않았고 migration container 테스트는 H2→PostgreSQL→MySQL 순차 실행했다. |
| KT-TEST-04 | `N/A` | HTTP/HC5 adapter가 변경되지 않았다. |
| KT-TEST-05 | `PASS` | targeted 12건, event 175건, core 594건과 appointment-event `build`/`koverVerify`가 통과했다. |
| KT-FIN-07 | `PASS` | mismatch, malformed decoder, tolerant-only, normal-protect-only spy가 named behavior 없이는 통과하지 않도록 assertion을 고정했다. |
| KT-FIN-08 | `N/A` | public API/README/diagram surface 변경이 없고, 새 내부 KDoc은 Korean-first다. |

## Fresh 검증

| 검증 | 결과 |
|---|---|
| TDD RED | mismatch는 `IllegalArgumentException: eventId does not match envelope`, malformed는 `BOOKING_RELIABILITY_MAPPING_FAILED` allowlist 누락으로 각각 실패했다. |
| TDD GREEN targeted | `:appointment-event:test` 대상 두 클래스, `12 passing`, `BUILD SUCCESSFUL` |
| appointment-event full module | `175 passing`, `BUILD SUCCESSFUL` |
| appointment-core clean test | `594 passing`, `BUILD SUCCESSFUL` (`cleanTest --no-build-cache`) |
| Flyway H2 | `1 passing`, `BUILD SUCCESSFUL` |
| Flyway PostgreSQL | `1 passing`, `BUILD SUCCESSFUL` |
| Flyway MySQL | `1 passing`, `BUILD SUCCESSFUL` |
| appointment-event build | `BUILD SUCCESSFUL`, `koverVerify` 포함 |
| quality task inventory | `koverVerify`/`check` 존재; detekt/ktlint task는 없음 |
| static safety scan | 변경 Kotlin 5개에서 `!!`, `runBlocking`, `println`, `System.out/err`, `assertThrows` 0건 |
| migration/path scan | authoritative V17 3개, stale duplicate 0개, changed path에 forbidden stale 이름 0건 |
| whitespace | `git diff --check` 통과 |

## 남은 위험과 범위 밖

- `protectUntrusted`의 default 구현은 기존 custom lambda protector를 위해
  `protect`로 fallback한다. production AES protector는 tolerant override를
  제공하며 spy regression으로 ingress 호출 경계를 고정했다.
- LSP/AST MCP 진단은 transport `closed`로 사용할 수 없었다. Gradle compile,
  module build, tests, static scan을 동등한 fresh fallback으로 사용했다.
- remote push, PR/CI, merge, local sync와 worktree cleanup은 이 문서 시점에는
  아직 수행하지 않았다. PR 생성 후 exact head/CI를 재확인하고, merge 직전에
  fresh chat approval을 받아야 한다.

## DoD

설계·계획·RED/GREEN 구현·affected module/DB 검증·Kotlin checklist·최종
6-R/7-Tier가 완료됐다. 현재 사전 PR 상태는 `DONE`, P0=0/P1=0/P2=0/P3=0이며,
delivery(PR/CI/merge/local-sync)만 남아 있다.

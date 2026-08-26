# Issue #311 fenced production 구현 계획 검토

## 검토 범위와 기준

- 대상 계획: `docs/superpowers/plans/2026-08-26-issue-311-waitlist-fenced-production-plan.md`
- 기준 설계: `docs/superpowers/specs/2026-08-26-issue-311-waitlist-fenced-production-design.md`
- Issue: `bluetape4k/clinic-appointment#311`
- 기준 커밋: `1859b5cb3ae68c25e918236b0923d74d845e6726`
- 검토 방식: 성능·안정성·보안·운영·개발자/API·사용자/호출자 여섯 관점과 통합 review

## 여섯 관점 독립 검토

| 관점 | 결과 | 근거 및 조치 |
|---|---|---|
| 성능 | P2 | Task 7은 fixed lease expiry를 검증하지만 실제 tick latency 예산과 timer 수집을 명령 수준으로 고정하지 않았다. Task 9에서 metric registry와 bounded duration을 읽고 p95/p99 증거를 남기도록 보완한다. |
| 안정성 | P1 수정 필요 | Task 4의 `WaitlistLeaseHandle`이 plan의 production-facing signature에는 정의되지 않아 release/close가 native handle을 잃을 수 있다. Task 4에 opaque owner/token/leaseUntil와 internal native handle, 단일 상태 전이를 명시한다. |
| 보안 | P2 | Base58 opaque owner는 고정했지만 namespace suffix를 test에서 사용할 때도 metric/log redaction 검증과 test key cleanup 범위를 명시해야 한다. Task 7의 allowlist 및 owned-key cleanup을 유지하고 raw 값 assertion을 추가한다. |
| 운영 | P2 | V31 readiness 실패와 `enabled=false` rollback은 계획되어 있으나 startup failure의 bounded category와 runbook 신호를 Task 6/8에 연결해야 한다. readiness probe는 typed failure만 반환하고 runbook에 대응 명령을 기록한다. |
| 개발자/API | P1 수정 필요 | 설계 문서는 `LockOwnerId`/`LockRequestId`를 공개 handle에 넣는 예시이고 계획은 DB owner string을 요구한다. 공개 dispatcher에는 redacted `owner: String`, token, expiry만 두고 native owner/request/handle은 `internal`로 숨기는 일관된 계약이 필요하다. |
| 사용자/호출자 | P2 | 기존 Boolean runner와 새 typed runner의 선택 기준은 적혀 있지만 어떤 production bean이 nullable legacy claim을 호출하지 않는지 compile-time 테스트가 부족하다. Task 5에 typed dispatcher가 `claimFenced`만 호출한다는 adapter fake 검증을 추가한다. |

## 통합 review와 보정

### P1-01 — `WaitlistLeaseHandle` shape를 고정

문제: 계획의 Task 5는 `WaitlistLeaseHandle`을 dispatcher 입력으로 사용하지만 Task 4에는
구체적인 타입 정의가 없다. 이 상태로 구현하면 native `FencedLockHandle`을 public API에
노출하거나 release 대상이 사라질 수 있다.

보정:

```kotlin
data class WaitlistLeaseHandle internal constructor(
    val owner: String,
    val token: WaitlistFencingToken,
    val leaseUntil: Instant,
    internal val nativeHandle: FencedLockHandle,
)
```

`owner`는 `Base58.randomString(8)`으로 만든 DB opaque reference이며 `LockOwnerId`의 raw
value가 아니다. `LockOwnerId`, `LockRequestId`, native handle의 노출은 adapter 내부로
제한하고, release/reconcile는 adapter가 보관한 동일 native 값으로만 수행한다. 이 보정은
Task 4/5와 설계 문서의 typed lease 단락에 반영한다.

### P1-02 — typed production dispatcher의 nullable 우회 차단

문제: legacy `VacancyClaim.fencingToken: WaitlistFencingToken?`는 하위 호환에 필요하지만,
production dispatcher가 이를 직접 사용하면 fence 경계가 사라진다.

보정: Task 2의 non-null `claimFenced(..., token: WaitlistFencingToken)`만 typed dispatcher
adapter가 호출하도록 하고, Task 5 테스트에서 legacy `claim` 호출이 0회임을 확인한다.

### P2-01 — 성능/운영 증거를 계획에 연결

Task 7/9의 Redis integration과 metric registry 검증 결과에 다음을 포함한다.

- fixed lease가 configured upper bound 안에서 acquire/reconcile하는지
- scheduler tick duration이 bounded budget 안인지
- metric tag key가 allowlist와 일치하고 raw namespace/identity/token이 없는지
- readiness 실패 category와 `enabled=false` rollback 명령이 runbook과 일치하는지

## 대안 검토

- Boolean adapter만 추가하는 방식은 token이 DB에 전달되지 않으므로 기각한다.
- 전체 waitlist service graph를 기본 조립하는 방식은 candidate 정책과 외부 port 책임을 발명하므로 기각한다.
- `UUID.randomUUID()`는 이미 bluetape4k `Base58` 기반 ID 계약이 있으므로 추가하지 않는다.
- V19를 수정하는 방식은 기존 migration contract를 깨므로 V31 additive 방식만 허용한다.

## 통합 verdict

| Priority | 결과 | 후속 조치 |
|---|---:|---|
| P0 | 0 | 없음 |
| P1 | 0 (보정 후) | handle shape와 typed-only claim을 계획/설계 addendum에 반영 |
| P2 | 3 | 성능·redaction·readiness evidence를 Task 6/7/9와 runbook에 연결 |
| P3 | 0 | 없음 |

구현을 시작할 수 있다. 단, 아래 보정 내용을 문서에 반영하고 Korean terminology audit을
통과한 뒤 Task 1 RED 단계로 이동한다.

## SPW writer DoD

- SPW-01: 계획 경로, 설계, Issue, 기준 커밋과 검토 범위를 read-back했다.
- SPW-02: 여섯 관점의 finding과 P0~P3 통합 verdict를 기록했다.
- SPW-03: `korean-naturalness-checklist.md` KO-01~KO-07 기준으로 한국어 문장을 점검했다.
- SPW-04: P1 finding을 구체적인 타입/호출 경계와 Task 2/4/5 보정으로 추적했다.
- SPW-05: 계획의 파일 지도·signature·명령과 설계의 acceptance traceability를 대조했다.

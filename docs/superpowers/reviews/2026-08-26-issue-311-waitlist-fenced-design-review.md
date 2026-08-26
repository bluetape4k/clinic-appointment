# Issue #311 fenced production 설계 검토

## 검토 범위와 근거

- 대상 문서: `docs/superpowers/specs/2026-08-26-issue-311-waitlist-fenced-production-design.md`
- 대상 코드: `WaitlistDeliveryScheduling.kt`, `WaitlistDeliveryRepository.kt`,
  `WaitlistVacancyJobs.kt`, `CacheConfig.kt`, waitlist core service/repository
- 외부 계약: `bluetape4k-lettuce:1.12.1` `CoordinationLocks.ko.md`
- 검토 기준: Issue #311 현재 본문, base `1859b5cb3ae68c25e918236b0923d74d845e6726`
- 언어: 한국어 기술 문서

## 여섯 관점 독립 검토

각 관점은 설계 문서와 현재 소스만 읽고, 근거가 있는 P0~P3 finding만 기록했다.

| 관점 | 결과 | 근거 |
|---|---|---|
| 성능 | P2 | fixed lease와 bounded batch를 명시했지만 실제 `scheduler_tick_seconds` p95/p99 수집 명령은 plan에서 구체화해야 한다. |
| 안정성 | P1 수정 후 PASS | ambiguous/reconcile와 close는 정의했으나, lease handle의 release 중복 방지와 cancellation 중 `UNKNOWN` 격리를 구현 task의 상태 머신으로 고정해야 한다. |
| 보안 | PASS | owner/request/token/key를 logs/tags에서 제외하고, namespace version과 fail-closed wiring을 명시했다. |
| 운영 | P2 | V31 rollback과 `enabled=false`는 정의했지만 readiness 실패 시 operator 신호와 runbook 경로는 plan에서 파일 단위로 연결해야 한다. |
| 개발자/API | P1 수정 후 PASS | legacy Boolean port와 typed port를 함께 두는 경계가 명시됐지만 production dispatcher가 nullable token을 호출하지 않는 컴파일 가능한 API 계약이 필요하다. |
| 사용자/호출자 | P2 | misconfiguration은 fail-closed로 정의했지만 README/KDoc의 migration 예제가 plan에 필요하다. |

## 통합 검토와 보정

### P1 — typed production path에서 nullable token 우회 방지

- 위치: 설계 문서 `API 계약`, `Production wiring 경계`
- 문제: `VacancyClaim.fencingToken: WaitlistFencingToken?`를 하위 호환으로
  허용하면 새 dispatcher가 실수로 null claim을 만들어 fenced 경계를 우회할 수 있다.
- 보정: plan에서 production dispatcher 전용 함수
  `claimFenced(..., fencingToken: WaitlistFencingToken)`를 사용하고, legacy
  `claim(...)`은 기존 내부/테스트 경계로만 남긴다. production bean 생성 조건은
  `WaitlistFencedVacancyDispatcher`와 이 전용 함수의 구현을 모두 요구한다. typed
  dispatcher test는 null token 경로가 호출되지 않음을 확인한다.
- 상태: plan task와 compile-time signature로 보정한다. 설계의 하위 호환 범위는
  변경하지 않는다.

### P1 — release 중복과 ambiguous handle 수명

- 위치: 설계 문서 `Redis lifecycle`, `실패 모드와 복구`
- 문제: `Ambiguous` reconcile 후 release, `close()`와 cancellation이 겹칠 때
  같은 handle을 두 번 release하거나 local task를 남길 위험이 있다.
- 보정: plan에서 handle state를 `ACQUIRED -> RELEASED | UNKNOWN | LOST`로 단일
  전이시키고, release/reconcile는 동일 owner/request를 사용한다. fake lock와 실제
  Redis fixture에서 duplicate release, ownership loss, close/task 종료를 각각 검증한다.
- 상태: 구현 task의 상태 불변식으로 보정한다.

### P2 — migration/readiness 증거의 구체화

- 위치: 설계 문서 `Production wiring 경계`, `호환성과 rollback`
- 보정: V31 migration contract가 세 dialect의 column/default를 읽고, readiness
  probe가 `fence_epoch`/`fence_sequence` 존재를 확인하도록 plan에 명시한다.

### P2 — 성능과 운영 증거의 구체화

- 보정: API integration test에서 고정된 fake clock과 bounded timer를 사용하고,
  Redis Testcontainers는 실제 acquire/reconcile/expiry를 순차 실행한다. metric
  registry 수집 결과에서 허용된 tag set 외 값과 raw identity가 없음을 검사한다.

## 대안과 중복 검토

- Boolean adapter만 추가하는 대안은 token이 DB에 도달하지 않아 기각한다.
- 전체 waitlist service graph를 기본 조립하는 대안은 정책을 발명하고 기존 port
  책임을 넓히므로 기각한다.
- 기존 DB fence 제거는 business authority를 약화하므로 기각한다.
- 기존 `docs/superpowers/specs/2026-08-23-issue-311-waitlist-fencing-design.md`
  및 PR #378의 docs-only hold와 중복되지 않는다. 새 문서는 보류 조건을 충족하는
  additive implementation 범위를 명시한다.

## 통합 verdict

| Priority | 상태 | 조치 |
|---|---|---|
| P0 | 0 | 없음 |
| P1 | 0 | plan의 전용 fenced dispatcher signature와 handle state machine으로 보정 |
| P2 | 2 | migration/readiness·성능/운영 task에 구체화 |
| P3 | 0 | 없음 |

설계는 구현·계획 단계로 진행할 수 있다. P1은 설계 의미를 바꾸지 않고 다음 plan의
compile-time/API 및 회귀 테스트 항목으로 닫는다.

## SPW writer DoD

- SPW-01: 문서 경로, 현재 소스, 외부 계약, 기준 커밋과 검토 목적을 기록했다.
- SPW-02: 여섯 관점, finding, 보정, 통합 verdict를 포함했다.
- SPW-03: `korean-naturalness-checklist.md` KO-01~KO-07을 적용했다.
- SPW-04: 모든 finding을 현재 설계 문단과 구체적인 plan 보정으로 추적했다.
- SPW-05: 표와 heading을 read-back했고 P0/P1 총계를 재계산했다.

# 예약 신뢰성 정책 기준

상태: 승인됨 · 관련 이슈: #176, #170 · 기준일: 2026-08-01

이 문서는 반복되는 `NO_SHOW`와 고객 책임 `late cancellation`을 새 예약 자격
판정에 반영하기 위한 규범 정책이다. 구현 기록은 [승인된 설계](superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md)와
[구현 계획](superpowers/plans/2026-08-01-issue-176-booking-reliability-plan.md)에 남긴다.

## 결정

- 판단 키는 `tenantGroupId + clinicId + MemberId`다. 이름, 전화번호, 이메일 주소,
  직원 자유입력 label은 입력, 저장 필드, metric tag로 사용하지 않는다.
- 정책은 변경할 수 없는 유효 `PRIORITY_AND_RELIABILITY` snapshot에서 읽는다. tenant
  값은 clinic의 `INHERIT`/`SET` 설정으로 상속하거나 대체하며, threshold 수준의
  `DISABLE`은 해당 threshold만 비활성화한다.
- `NO_SHOW`와 `CANCELLED` event에는 `PATIENT`, `CLINIC`,
  `OPERATIONAL_EXCEPTION`, `DATA_CORRECTION`, `UNKNOWN` 중 하나의 typed
  책임이 담긴다. 고객 책임 event만 집계하고 `UNKNOWN`은
  `UNATTRIBUTED_EVENT_EXCLUDED`로 관측한다.
- 결과는 `ELIGIBLE`, `REQUIRES_STAFF_APPROVAL`, `RESTRICTED`,
  `OVERRIDDEN`, `POLICY_DISABLED`, `STALE`, `UNAVAILABLE` 중 하나다.
- `PROPOSED`, `HELD`, 신규 직접 `CONFIRMED` 경로는 commit 직전에 decision을
  다시 확인한다. 기존 `CONFIRMED` appointment는 이 정책으로 자동 변경, 취소,
  자원 해제하지 않는다.
- 직원 override와 clear는 audit row를 추가하고 decision digest/version CAS를
  사용한다. 원래 decision 자체는 변경하지 않는다.
- 새 proposal/commitment에는 변경할 수 없는 decision stamp(`decisionId`, policy
  version/hash, evaluation digest, expiry)를 저장한다. 기존 `CONFIRMED` row는
  이전 동작과 stamp를 유지한다.

## 기본값과 상한

| 설정 | 기본값 | 안전 규칙 |
|---|---:|---|
| lookback | 180일 | effective policy에 고정 |
| late-cancellation window | 120분 | 예약 시작 전 구간으로 계산 |
| no-show threshold | 3건 | 고객 책임 event만 집계 |
| late-cancellation threshold | 3건 | no-show와 독립적으로 적용 |
| cooling-off | 24시간 | `expiresAt`으로 표현 |
| history read | 100행 | member와 clinic 범위로 제한 |
| trigger IDs in response | 32개 | 초과분은 opaque audit cursor로 반환 |

값은 시스템 설정이며 clinic별로 override할 수 있다. override가 없는 clinic은 tenant
기준을 상속한다. threshold가 없는 legacy schemaVersion 1 payload는
`POLICY_DISABLED`로 디코딩하며 기존 예약 동작을 변경하지 않는다.

## 판정 흐름

1. 신뢰된 appointment outcome을 typed attribution event로 변환한다.
2. `(tenant, clinic, member, eventId, sourceVersion)`를 기준으로 중복을 제거하고
   가장 높은 source version을 유지한다.
3. 상한이 있는 lookback 구간에서 고객 책임 no-show와 late cancellation을 센다.
4. threshold에 도달하면 clinic restriction mode를 적용한다. 자동 당일 offer를
   제외하거나 직원 승인을 요구한다.
5. policy version/hash, count, reason code, trigger ID, expiry, decision digest를
   변경할 수 없는 decision row로 저장한다.
6. 활성 cooling-off는 `expiresAt`까지 유지한다. 새 qualifying event 없이 만료되면
   `COOLING_OFF_EXPIRED`를 반환하고 restriction을 갱신하지 않는다. `OFF`는 기존
   예약 동작을 유지하고, `SHADOW`는 차단 없이 관측하며, `ENFORCE`는 restriction,
   stale snapshot, unavailable decision에서 fail-closed로 동작한다.

## 개인정보와 권한

decision API는 opaque `memberId`와 상한이 있는 appointment ID만 노출한다. 이름과
전화번호는 member-management service의 별도 권한 경계 뒤에 두며 booking reliability
store에 복제하지 않는다. preview에는 `booking-reliability:read`, audit에는
`booking-reliability:audit`, override/clear에는 `booking-reliability:write`와
정확한 clinic membership이 필요하다. actor identity는 요청 본문이 아니라 검증된
principal에서 가져온다.

## 장애와 롤백

- schema/table/index readiness가 불완전하면 worker를 시작하지 않으며 `ENFORCE`
  gate는 `BOOKING_DECISION_UNAVAILABLE`을 반환한다.
- policy snapshot이 일치하지 않으면 `BOOKING_DECISION_STALE`을 반환한다. 호출자는
  다시 읽은 뒤 재시도한다.
- 만료된 DB lease는 owner fencing으로 회수한다. 재시도는 상한이 있는 exponential
  backoff와 최대 시도 횟수를 사용한다. 소진된 job은 `DEAD_LETTER`로 이동하며,
  운영자는 durable member-level job을 `PAUSED`/`resume`할 수 있다. coroutine
  cancellation은 retry로 변환하지 않는다.
- canary는 `OFF → SHADOW → ENFORCE` 순서로 승격한다. 최소 24시간과 1,000건의
  decision 중 더 늦은 조건, p95 ≤250ms/p99 ≤500ms, duplicate/unavailable/raw-PII
  발견 0건, attribution 누락 1% 미만을 모두 확인한다. 실패하면 clinic allowlist를
  비우고 `SHADOW` 또는 `OFF`로 되돌린다.

## 관련 문서와 시각화

- [API 계약](api/booking-reliability.md)
- [운영 런북](runbooks/booking-reliability.md)
- [카나리 증거 템플릿](runbooks/booking-reliability-canary-evidence-template.md)
- [업무 흐름 HTML과 PNG](visual-companions/README.md#booking-reliability-workflow)
- [승인된 설계](superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md)

`#170` waitlist/offer 후보 생성과 응답 소비는 여기서 구현하지 않는다. 이 변경은
후속 작업에서 사용할 read-only port와 commitment stamp 계약만 제공한다.

<!-- booking-reliability-workflow-en-light.html / booking-reliability-workflow-en-dark.html -->
<!-- booking-reliability-workflow-ko-light.html / booking-reliability-workflow-ko-dark.html -->

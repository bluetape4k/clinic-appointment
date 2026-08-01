# 예약 신뢰성 정책 기준 문서

상태: 승인됨 · 관련 이슈: #176, #170 · 기준일: 2026-08-01

이 문서는 반복 `NO_SHOW`와 고객 책임 `late cancellation`을 새 예약의 자격 판단에
반영하는 기준 문서다. 구현 세부는 [승인 설계](superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md)와
[실행 계획](superpowers/plans/2026-08-01-issue-176-booking-reliability-plan.md)을 따른다.

## 결정

- 판단 키는 `tenantGroupId + clinicId + MemberId`다. 이름, 전화번호, 이메일, 직원 자유입력
  label은 입력·저장·metric tag로 사용하지 않는다.
- 정책은 `PRIORITY_AND_RELIABILITY`의 immutable effective snapshot에서 읽는다. tenant 기준을
  clinic override가 `INHERIT` 또는 `SET`으로 대체하며, threshold별 `DISABLE`은 해당 종류의
  제한만 끈다.
- `NO_SHOW`와 `CANCELLED`는 `PATIENT`, `CLINIC`, `OPERATIONAL_EXCEPTION`,
  `DATA_CORRECTION`, `UNKNOWN` 책임으로 구분한다. 고객 책임 사건만 누적하고 `UNKNOWN`은
  `UNATTRIBUTED_EVENT_EXCLUDED`로 관측한다.
- 결과는 `ELIGIBLE`, `REQUIRES_STAFF_APPROVAL`, `RESTRICTED`, `OVERRIDDEN`,
  `POLICY_DISABLED`, `STALE`, `UNAVAILABLE` 중 하나다.
- `PROPOSED`·`HELD`와 신규 직접 `CONFIRMED` 진입은 commit 직전에 decision을 다시 읽는다.
  이미 `CONFIRMED`인 예약은 이 정책으로 자동 변경·취소·자원 회수하지 않는다.
- 직원 override/clear는 원 decision을 덮지 않고 append-only 감사 row와 CAS 조건으로 기록한다.
- 신규 proposal/commitment에는 `decisionId`, policy version/hash, evaluation digest, expiry를
  담은 immutable decision stamp를 저장한다. 기존 `CONFIRMED` row의 동작과 stamp는 유지한다.

## 기본값과 상한

| 항목 | 기본값 | 안전 규칙 |
|---|---:|---|
| lookback | 180일 | effective policy에 고정 |
| late cancellation window | 120분 | 예약 시작 전 구간으로 계산 |
| no-show threshold | 3건 | 고객 책임 사건만 집계 |
| late cancellation threshold | 3건 | no-show와 독립적으로 집계 |
| cooling-off | 24시간 | decision `expiresAt`으로 표현 |
| history read | 100행 | member/clinic 범위로 제한 |
| trigger ID response | 32개 | 초과분은 opaque audit cursor로 이동 |

숫자와 기간은 병원별 시스템 설정에서 override할 수 있다. override가 없는 병원은 tenant
기준을 사용하고, legacy schemaVersion 1 payload에 threshold가 없으면 `POLICY_DISABLED`로
읽어 기존 예약 동작을 바꾸지 않는다.

## 판정 흐름

1. 신뢰된 예약 결과 event를 typed attribution으로 변환한다.
2. `(tenant, clinic, member, eventId, sourceVersion)`로 중복을 제거하고 최신 source version을
   선택한다.
3. bounded lookback에서 고객 책임 no-show와 late cancellation을 각각 센다.
4. threshold를 넘으면 clinic 설정에 따라 자동 당일 제안 제외 또는 직원 승인 요구로 판정한다.
5. policy version/hash, count, reason code, trigger ID, expiry, decision digest를 immutable row로
   저장한다.
6. cooling-off가 `expiresAt`까지 유효하면 제한을 유지한다. 새 책임 사건 없이 만료되면
   `COOLING_OFF_EXPIRED`로 기록하고 제한을 갱신하지 않는다. 예약 command는 `OFF`에서는 기존 흐름을 유지하고,
   `SHADOW`에서는 관측만 하며,
   `ENFORCE`에서는 제한·stale·unavailable을 fail-closed로 처리한다.

## 개인정보와 권한

decision API에는 opaque `memberId`와 bounded 예약 ID만 포함한다. 회원 이름·전화번호는 회원
관리시스템의 별도 권한 경계에서만 조회하며 예약 신뢰성 DB에 복제하지 않는다. preview는
`booking-reliability:read`, audit은 `booking-reliability:audit`, override/clear는
`booking-reliability:write`와 정확한 clinic membership을 요구한다. actor는 request body가
아닌 검증된 principal에서 얻는다.

## 장애·롤백

- schema/table/index readiness가 맞지 않으면 worker를 시작하지 않고 `ENFORCE` gate는
  `BOOKING_DECISION_UNAVAILABLE`으로 닫는다.
- policy snapshot mismatch는 `BOOKING_DECISION_STALE`로 응답하고 재조회 후 재시도한다.
- DB lease가 만료된 job은 owner fencing으로 재선점하며 retry는 bounded exponential backoff와
  최대 시도 횟수를 따른다. 소진된 job은 `DEAD_LETTER`가 되고 운영자는 durable member-level
  job을 `PAUSED`/`resume`할 수 있다. coroutine cancellation은 retry로 삼지 않는다.
- canary는 `OFF → SHADOW → ENFORCE` 순서로 올린다. 최소 24시간과 1,000 decisions 중
  늦은 조건, p95 250ms/p99 500ms 이하, duplicate/unavailable/raw PII 0, attribution 누락
  1% 미만을 확인한다. 하나라도 실패하면 clinic allowlist를 비우고 `SHADOW` 또는 `OFF`로
  되돌린다.

## 연결 문서와 시각화

- [API 계약](api/booking-reliability.md)
- [운영 런북](runbooks/booking-reliability.ko.md)
- [카나리 증거 템플릿](runbooks/booking-reliability-canary-evidence-template.md)
- [업무 흐름 HTML·PNG](visual-companions/README.md#booking-reliability-workflow)
- [승인 설계](superpowers/specs/2026-08-01-issue-176-booking-reliability-design.md)

`#170` waitlist/offer 후보 생성과 고객 응답 소비는 이 변경에 포함하지 않는다. 이 변경은
후속 구현이 사용할 read-only port와 commitment stamp 계약만 제공한다.

<!-- booking-reliability-workflow-en-light.html / booking-reliability-workflow-en-dark.html -->
<!-- booking-reliability-workflow-ko-light.html / booking-reliability-workflow-ko-dark.html -->

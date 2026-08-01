# Issue #176 예약 신뢰성 정책 구현에서 배운 점

## 결과

반복 `NO_SHOW`와 고객 책임 late cancellation을 병원별 versioned policy로 평가하는 기반을
추가했다. 결정은 immutable digest로 저장하고, 이벤트·override·재평가 job은 각각 idempotency와
owner fencing을 가진다. `PROPOSED`/`HELD`와 신규 직접 `CONFIRMED` 진입만 gate가 읽으며,
기존 `CONFIRMED` 예약을 재평가해 변경하지 않는다.

## 재사용할 원칙

1. **책임 분류를 먼저 고정한다.** `CLINIC`, `OPERATIONAL_EXCEPTION`, `DATA_CORRECTION`,
   `UNKNOWN`을 고객 책임과 분리하면 운영 장애가 고객 제한으로 전파되지 않는다.
2. **회원 원문을 복제하지 않는다.** `MemberId`만 계약 경계로 전달하고 이름·전화번호·자유
   텍스트를 DTO, schema, metric tag에 넣지 않는다.
3. **결정은 정책 snapshot과 함께 저장한다.** 현재 정책을 다시 읽어 과거 결정을 바꾸지 않고,
   digest/CAS로 직원 명령의 stale 상태를 확인한다.
4. **bounded read와 durable job을 함께 둔다.** 한 건 평가의 history/trigger 상한은 대규모
   clinic에서도 지연을 제한한다. 현재 member-level 재평가는 lease·backoff·cursor·pause/resume·
   dead-letter로 재시작 가능하게 하고, clinic 전체 event keyset backfill은 별도 이슈로 분리한다.
5. **준비 상태를 기능 mode와 분리한다.** V17 table/index가 없으면 worker는 시작하지 않고,
   `ENFORCE`는 unavailable로 닫는다. 운영자는 `OFF → SHADOW → ENFORCE` 증거를 남긴다.
6. **정적 구조와 업무 흐름의 표현을 분리한다.** ERD/sequence/class는 SVG+PNG, 업무 의사결정
   흐름은 locale/theme별 HTML+PNG로 관리한다. Markdown이 항상 의미의 기준이다.

## 검증 증거

- core evaluator/repository/job 테스트와 event ingress 테스트 통과
- H2, MySQL, PostgreSQL Flyway V17 검증 통과
- member lookback query-plan 계약 통과
- API gate, metrics, health, retention, canary readiness 테스트 통과
- decision/response/migration에서 PII denylist 확인

## 다음 개선

`#170`의 waitlist/offer lifecycle과 clinic 전체 event keyset backfill은 이 변경에 포함하지
않는다. clinic 수가 크게 늘면 process-local worker 대신 분산 `LeaderGroupElector`를 도입하되,
DB lease/fencing과 idempotency 계약은 유지한다. process-lifetime semaphore map eviction은
후속 이슈로 남긴다.

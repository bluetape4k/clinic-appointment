# 예약 정책 운영·다이얼렉트 검증 — Task 10 검토 기록

## 결과

Task 10은 예약 정책 foundation의 구현 범위를 늘리는 단계가 아니라, 이미 만든
tenant baseline·clinic override 정책이 운영 DB와 관리 API에서 안전하게 작동한다는
증거를 닫는 단계다. H2 구조 피드백, PostgreSQL 의미 권위, MySQL 지원 동등성,
10,000행 hot-table fixture, 최대 5,000행 preview page, 관리 API 관측성, 운영 문서,
README 영문·국문 discoverability를 함께 검증했다.

최종 6-R 독립 검토와 본 세션 통합 검토를 합친 7-Tier gate는 `P0=0`, `P1=0`으로
통과했다. 최초 검토의 차단 의견은 메트릭 backend 장애가 worker의 durable 상태 전이를
뒤집을 수 있던 문제와 실제 wire schema와 다른 문서 예시였다. 코드·테스트·문서를
수정한 뒤 해당 운영자·개발자·사용자 관점을 독립적으로 다시 검토했다.

## 7-Tier 수렴

| Tier | 관점 | 최종 | 주요 증거 또는 남은 비차단 의견 |
|---:|---|---|---|
| 1 | 성능 | P0 0, P1 0, P2 1, P3 0 | 10,000행 fixture, 최대 5,000행 page, PostgreSQL JSON plan의 호환 index와 no `Seq Scan`; registry 장애 경고량은 운영에서 관찰 |
| 2 | 안정성 | P0 0, P1 0, P2 0, P3 0 | owner fencing, retry/missed terminal state, throwable stack 보존, Redis 종료 순서와 통합 테스트 공통 lock |
| 3 | 보안 | P0 0, P1 0, P2 0, P3 0 | 낮은 cardinality tag, body 기반 권한 상승 거절, 안전 오류 envelope, Gateway actor 경계 |
| 4 | 운영자 | P0 0, P1 0, P2 0, P3 1 | 메트릭 장애 격리 회귀 테스트와 stable error code 로그; flag가 모두 꺼져도 scheduler wake-up하는 소량 overhead는 수용 |
| 5 | 개발자/API | P0 0, P1 0, P2 0, P3 0 | 사람 `policy:write`와 내부 worker scope 분리, 상세 KDoc, rollout chain 실값 검증, README anchor 보정 |
| 6 | 사용자/호출자 | P0 0, P1 0, P2 0, P3 1 | 문서 booking 예제를 production strict codec으로 decode; 나머지 7개 baseline kind의 copy-ready 예제는 후속 개선 |
| 7 | 본 세션 통합 | P0 0, P1 0, P2 0, P3 0 | 전체 API 278개, 3개 DB 방언, 문서 parity, diff audit를 하나의 delivery 증거로 통합 |

비차단 P2/P3은 현재 정책 정확성, tenant 격리, durable worker 결과를 바꾸지 않는다.
다음 기능 변경에서 경보량과 나머지 7개 baseline fixture 문서를 보강할 수 있지만,
이번 Task의 완료 조건을 미루는 근거로 사용하지 않는다.

## 검토에서 수정한 차단 문제

### 메트릭 backend 장애 격리

`SchedulingPolicyMetrics`의 모든 공개 기록 메서드는 registry의 `Exception`을 내부에서
격리한다. 음수 activation lateness 같은 호출 계약 위반은 기록 전에 계속 거절하지만,
meter 등록·기록 실패는 activation 성공을 retry로 바꾸거나 `MISSED`/preview terminal
상태를 막지 않는다. worker 회귀 테스트는 lateness, activation completion, preview
completion 세 경로를 각각 고장 난 registry로 검증한다.

### 문서 wire 계약

booking payload 예시는 `provisionalRequestTtlSeconds`,
`resourceHoldTtlSeconds`, `maximumAgeSeconds`와 초 단위 숫자를 사용한다.
문서 marker 안의 세 JSON을 `SchedulingPolicyPayloadCodec`으로 직접 decode하는 테스트를
추가해 prose와 strict schema의 drift를 빌드에서 차단한다. validate, preview, approve,
schedule, activate, retire, replay 요청 body와 idempotency header 조건도 문서화했다.

### 통합 테스트 lifecycle

`@DirtiesContext(AFTER_CLASS)`만 추가하면 Spring client가 singleton Redis container보다
먼저 닫혀 timeout은 사라지지만, JUnit class 병렬 실행에서는 한 subclass가 다른
subclass의 공유 context를 닫을 수 있다. 실제 전체 실행에서 403 네 건으로 재현했다.
공통 `ResourceLock(READ_WRITE)`와 class 내부 `SAME_THREAD`를 함께 적용해 Spring 통합
테스트만 배타화하고 독립 unit test의 병렬성은 유지했다.

## 검증 증거

- 정책 metric/worker/관리 facade/문서/properties 집중 테스트: 19개, 실패 0
- 전체 `appointment-api` H2 실행: 278개, 실패 0, 환경 의존 2개 skip, 43초
- 같은 전체 실행의 Redis 종료 오류 검색:
  `RedisCommandTimeoutException=0`, `ConnectionWatchdog=0`,
  `Cannot connect=0`, `CLIENT TRACKING OFF=0`
- 다이얼렉트·성능 집중 테스트:
  - H2: 5개, 실패 0
  - PostgreSQL+Flyway: 5개, 실패 0
  - MySQL 8+Flyway: 5개, 실패 0
- `appointment-core`, `appointment-event`, `appointment-api` module build: 통과
- `git diff --check`, README 영문·국문 구조 점검, 문서 링크 점검: 통과

H2는 빠른 구조 피드백이다. PostgreSQL+Flyway 결과가 운영 의미의 권위 증거이며,
MySQL 8 결과는 지원 다이얼렉트 동등성을 확인한다.

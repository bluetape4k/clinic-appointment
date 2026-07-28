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
| 7 | 본 세션 통합 | P0 0, P1 0, P2 0, P3 0 | 전체 API H2 281개·PostgreSQL/MySQL 각 283개, 문서 parity, diff audit를 하나의 delivery 증거로 통합 |

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
먼저 닫혀 timeout은 사라지지만, JUnit class 병렬 실행에서는 한 검사가 다른 검사의
공유 context나 schema/data를 바꿀 수 있다. 실제 전체 실행에서 403이 4건, 재실행에서는
30건으로 달라지며 재현됐다. 공통 `ResourceLock(READ_WRITE)`와 class 내부
`SAME_THREAD`를 함께 적용해 Spring 통합 테스트만 배타화하고 독립 unit test의 병렬성은
유지했다.

기반 class 상속자만 잠그는 것으로는 부족했다. 별도 `@SpringBootTest`와 Flyway
clean/migrate 검사도 `appointment-test` DB와 singleton PostgreSQL·MySQL container를
공유하므로 모두 같은 `API_INTEGRATION_RESOURCE` write lock에 참여시켰다. 이 규칙은
Spring context cache뿐 아니라 Exposed 기본 DB와 migration fixture의 lifecycle까지 하나의
공유 자원으로 취급한다.

### Security filter 단일 소유권

`JwtAuthenticationFilter`, `TenantContextFilter`, `CorrelationIdFilter`는 Spring bean인
동시에 `SecurityFilterChain`에 명시적으로 추가된다. Spring Boot의 servlet filter 자동
등록까지 허용하면 같은 instance가 Security context 경계 밖에서 먼저 실행되고,
`OncePerRequestFilter` 표식 때문에 chain 내부 실행이 생략될 수 있다. 실제 전체
PostgreSQL 실행에서 tenant 요청 세 건이 간헐적으로 403을 반환했다.

세 filter의 `FilterRegistrationBean`을 disabled 상태로 등록해 embedded servlet
container의 독립 실행을 막고, Security chain의 순서만 권위로 유지했다. 회귀 테스트는
servlet context에 세 filter registration이 없음을 확인하고, 서로 다른 tenant token의
연속 요청이 올바른 clinic 경계를 유지하는지도 함께 검증한다.

결합 module build에서 무인증 actuator 요청 하나가 간헐적으로 403을 반환해 남은
fail-closed 공백도 확인했다. `JwtAuthenticationFilter`가 bearer token이 없는 요청에서
thread-local의 이전 authentication을 명시적으로 제거하지 않았기 때문이다. stateless JWT가
유일한 request authentication 권위라는 계약에 따라 매 요청 시작 시 context를 비우고,
검증된 token이 있을 때만 새 principal을 설정한다. stale principal을 선행 주입한 단위
테스트는 no-token 요청 뒤 authentication이 `null`인지 검증한다.

## 검증 증거

- 정책 metric/worker/관리 facade/문서/properties 집중 테스트: 19개, 실패 0
- 전체 `appointment-api` H2 격리 실행: 281개, 실패 0, 환경 의존 2개 skip, 42초
- 전체 `appointment-api` PostgreSQL 격리 실행: 283개, 실패 0, 3분 19초
- 전체 `appointment-api` MySQL 격리 실행: 283개, 실패 0, 1분 33초
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

## PR 후속 검토

PR 생성 뒤 실제 diff를 다시 검토하면서 계획 진료 partition에 두 가지 증거 공백을
발견했다.

- 계획 진료 조회가 상위 예약 계획의 상태를 제한하지 않아, 이미 취소된 계획의 미완료
  진료도 정책 영향 미리보기에 포함될 수 있었다. 조회 조건에 `ACTIVE`,
  `PARTIALLY_FULFILLED` 계획만 허용하는 규칙을 추가하고 H2·PostgreSQL·MySQL 저장소
  테스트로 취소 계획 제외를 고정했다.
- 기존 10,000행 성능 fixture는 appointment 중심이어서 계획 진료와 상위 계획의 join
  경로를 증명하지 못했다. 두 table에도 각각 10,000행을 주입하고, 최대 5,000행 page와
  실제 선택 index, full table scan 부재를 세 지원 DB에서 확인했다. 새 index는 추가하지
  않고 기존 계획·진료 index가 실제 query shape에서 선택됨을 증명했다.

보안 검토에서는 예상하지 못한 정책 예외가 일반 handler로 흘러 원인 메시지를 로그에
남길 수 있는 비차단 P2를 발견했다. 정책 전용 `POLICY_INTERNAL_ERROR` 응답과 correlation
ID를 제공하고, 원인 메시지 대신 예외 종류만 기록하도록 변경했다. 회귀 테스트는 공개
응답과 로그 모두에 내부 marker가 나타나지 않는지 확인한다.

관리 facade 내부에서 tenant code를 다시 검사하자는 P3는 이번 범위에서는 보류했다.
현재 HTTP 경계의 `TenantContextFilter`·`ActorContextResolver`와 command 경계의
`PolicyTenantBoundaryVerifier`가 신뢰 tenant를 검증하며, facade의 숫자 tenant ID를
tenant code로 독립 변환할 권위 mapping은 없다. 미래의 내부 호출 경로가 facade를 직접
노출할 때 같은 verifier 계약을 함께 전달해야 한다.

두 번째 독립 검토는 sparse tenant와 Spring 예외 선택 규칙에서 추가 P1을 발견했다.

- tenant 전체 미리보기가 eligible aggregate가 없는 병원을 row page 크기만으로 순회하면,
  한 트랜잭션이 병원 수만큼 SQL을 실행할 수 있었다. 한 scan이 최대 100개 병원만
  확인하도록 별도 상한을 두고, aggregate가 0건이어도 병원 소진 cursor를 저장해 다음
  트랜잭션에서 재개한다. 101개 빈 병원 회귀 테스트는 첫 scan이 정확히 100번째 병원에서
  멈추고 두 번째 scan이 나머지를 완료하는지 H2·PostgreSQL·MySQL에서 검증한다.
- Spring MVC는 일반 `Exception` handler보다 `IllegalStateException` handler를 먼저
  선택한다. 정책 내부 불변식 오류가 일반 409 응답으로 빠지지 않도록 두 handler가 같은
  정책 내부 오류 변환기를 사용하게 했다. 실제 MVC exception resolver 테스트는 500,
  `POLICY_INTERNAL_ERROR`, correlation ID 보존, 로그 비노출을 함께 검증한다.

첫 PR SHA의 PostgreSQL CI 실패도 같은 검토에서 재현했다.
`MultitenancyMigrationTest`가 남긴 holiday가 있는 상태에서 정책 보안 테스트가 모든
tenant를 삭제해 FK 오류를 일으켰고, 다른 병렬 API 테스트의 tenant까지 지워 연쇄 404를
만들었다. 보안 테스트 정리를 고정 tenant ID 범위로 축소해 migration+security 조합
5개가 PostgreSQL에서 통과하도록 했다.

이후 전체 H2·PostgreSQL 실행에서 공유 lifecycle과 filter 이중 등록을 추가로 교정했다.
최종 격리 검증은 H2 281개 통과·2개 skip, PostgreSQL과 MySQL 각 283개 통과로 수렴했다.

# 예약 정책의 다이얼렉트·운영 검증에서 배운 점

## 맥락

예약 정책 기반의 마지막 단계는 새 기능을 더하는 작업이 아니라, H2에서 빠르게 만든
업무 규칙이 PostgreSQL과 MySQL의 실제 Flyway schema에서도 같은 의미를 갖는지 증명하고
운영자가 안전하게 활성화할 수 있는 문서와 지표를 닫는 작업이었다. 병렬 활성화,
불변 snapshot, preview lease, overlap lookup, outbox 호환성, 대량 조회, Redis 종료가
한 검증 경계에 함께 들어왔다.

## 빈 결과의 index plan은 업무 성능을 증명하지 않는다

초기 10,000건 fixture는 appointment 상태를 전체 row index의 나머지로 계산했다.
clinic partition도 같은 index의 나머지로 나누었기 때문에 특정 clinic에는
`REQUESTED`가 하나도 없었다. H2 `EXPLAIN`은 의도한 index를 사용했지만 실제 반환은
0건이라 hot path를 증명하지 못했다.

수정한 테스트는 선택도가 높은 index 증명용 clinic과 실제 5,000건 최대 page용 clinic을
분리하고, 모든 query가 1건 이상을 반환하면서 최대 반환 상한도 지키도록 한다. 성능 회귀
테스트는 index 이름만 보지 말고 fixture가 실제 선택도를 만들었는지와 materialized row
수를 함께 검증해야 한다.

## H2 구조 피드백과 운영 schema 의미는 분리한다

H2의 `SchemaUtils` 경로는 빠른 RED→GREEN에 유용하지만 PostgreSQL Flyway check constraint와
완전히 같지 않았다. activation fixture에서 `next_attempt_at >= effective_from`을 어긴 row는
H2를 통과했지만 PostgreSQL에서 즉시 거절됐다. fixture를 고친 뒤 PostgreSQL과 MySQL을
순차 실행하여 동일한 동시성·조회 계약을 다시 확인했다.

따라서 H2 성공은 구조 피드백이고, PostgreSQL+Flyway가 운영 의미의 권위 증거다. MySQL
결과는 지원 다이얼렉트 동등성을 보완한다. 세 결과를 한 문장으로 뭉쳐 보고하지 않는다.

## 고정 microbenchmark 대신 구조적 상한을 검증한다

컨테이너 시작, CI 자원, 로컬 장비에 따라 fixture 생성 시간은 달라진다. 그래서 실행 시간은
관측값으로 남기되 machine-specific 임계값으로 성공·실패를 결정하지 않았다. 대신
10,000건 고정 cardinality에서 호환 named index 선택, optimizer 예상 탐색 행 수, 실제 반환
상한, preview page 최대 5,000건, tick별 activation 25건·preview 10건을 검증했다.
PostgreSQL은 `EXPLAIN (FORMAT JSON)`의 최상위 `Limit` 추정치가 아니라 실제 선택된 index
node의 `Plan Rows`를 읽고, 하위 plan 전체에 `Seq Scan`이 없는지도 함께 확인했다. 통계와
index 폭에 따라 preview 전용 index 대신 기존 clinic/date/status index가 더 싸게 선택될
수 있으므로 두 호환 index를 허용하되 full scan은 허용하지 않았다.

2초 동기 preview와 5분 deadline은 wall clock benchmark가 아니라 monotonic/fake clock
테스트로 고정했다. 성능 요구가 업무 deadline이면 실행 환경의 우연보다 bounded work와
결정적 시계가 더 강한 증거다.

## 반복 freshness 검사는 집합 digest가 아니라 단조 epoch로 고정한다

tenant preview page 자체를 100개 병원으로 제한해도, page 전후 freshness 검사가 tenant의
모든 병원과 clinic scope head를 다시 읽으면 전체 작업은 여전히 병원 수에 비례한다.
bounded page와 bounded validation은 별개의 계약이다.

clinic override generation 변경 transaction에서 tenant head의 단조
`clinic_generation_epoch`도 증가시키고, preview에는 tenant ID와 epoch의 hash만 저장했다.
이렇게 하면 정책 변경 감지는 unique head 한 행 조회로 고정된다. 병원 목록과 appointment
inventory는 정책 세대와 분리한다. 병원 생성만으로 정책이 바뀐 것처럼 preview를
무효화하지 않고, 실제 clinic override generation이 증가할 때만 stale 처리한다.

이 패턴의 핵심은 집계 counter를 별도 비동기 consumer가 갱신하는 것이 아니라 원본 clinic
generation과 같은 transaction에서 갱신하는 것이다. 잠금은 항상 tenant→clinic 순서이며,
둘 중 하나라도 실패하면 전체 transaction을 rollback해야 한다.

## 공유 container보다 Spring context를 먼저 닫는다

공유 Redis Testcontainers launcher가 JVM shutdown hook에서 먼저 종료되면, 캐시 bean의
`CLIENT TRACKING OFF` 정리가 이미 닫힌 Redis에 연결되면서 기본 1분 timeout을 기다린다.
테스트 본문은 통과해도 종료 단계가 느리고 reconnect 경고가 남았다.

통합 테스트 기반 클래스에 class 단위 context 종료를 명시해 Spring client와 near-cache를
Redis container가 살아 있을 때 먼저 닫았다. container singleton 재사용은 유지하면서
종료 시간이 약 67초에서 7초로 줄고 timeout/reconnect 로그가 사라졌다. 테스트 인프라도
생산 코드처럼 소유권과 종료 순서를 명시해야 한다.

그러나 `AFTER_CLASS`만으로는 충분하지 않았다. JUnit class 병렬 실행에서 한 통합 테스트가
공유 context를 닫거나 schema/data를 초기화하는 동안 다른 테스트가 같은 datasource와
security chain을 사용해 `403`을 반환하는 경합이 전체 API 실행에서 재현됐다.
`SAME_THREAD`는 한 class 안의 method 순서만 고정한다. 따라서 기반 class 상속자뿐 아니라
별도 `@SpringBootTest`, Flyway clean/migrate 검사처럼 같은 `appointment-test` DB 또는
singleton container를 쓰는 모든 class가 동일한 `ResourceLock(READ_WRITE)`에 참여해야
한다. unit test 병렬성은 그대로 두고 공유 context·DB 소유자만 직렬화한다.

## Security filter bean의 실행 소유자는 하나여야 한다

Spring Boot는 `Filter` bean을 embedded servlet container에 자동 등록한다. 같은 filter를
`SecurityFilterChain`에도 직접 추가하면 filter instance가 container와 Security chain
양쪽에서 실행될 수 있다. 특히 `OncePerRequestFilter`는 먼저 실행된 표식을 남기므로,
Security context 생성·정리 경계 밖의 실행 때문에 chain 내부 인증 또는 tenant context
수립이 생략될 수 있다.

custom security filter의 bean lifecycle은 유지하되 `FilterRegistrationBean`을 disabled로
등록해 servlet container의 독립 실행을 막았다. 요청 보안 filter는
`SecurityFilterChain`만 소유하고, 테스트는 servlet registration 부재와 tenant가 다른
연속 요청의 격리를 함께 확인해야 한다.

이중 등록을 끈 뒤에도 stateless JWT filter가 bearer token이 없는 요청에서 기존
`SecurityContext`를 그대로 두면, 재사용 thread에 남은 principal을 새 요청의 권한처럼
해석할 수 있다. 실제 결합 build에서 무인증 actuator 요청이 401 대신 간헐적으로 403을
반환했다. JWT가 유일한 request authentication 권위인 서비스는 filter 시작 시 context를
비우고, 검증된 token이 있을 때만 principal을 새로 설정해야 한다. stale principal을 먼저
주입하는 단위 테스트가 이 fail-closed 계약을 직접 증명한다.

## 관리 API metric은 닫힌 분류만 사용한다

worker 지표만으로는 관리자가 어느 lifecycle 작업을 시도했고 어디서 거절됐는지 알 수
없었다. 관리 facade 경계에서 작업, 성공/거절, scope 종류만 meter tag로 기록하고 actor,
tenant ID, clinic ID, 예외 상세는 넣지 않았다.

낮은 cardinality 지표는 운영 진단을 가능하게 하면서 개인정보·tenant 식별자 누출과
시계열 폭증을 피한다. meter registry 장애는 best-effort 경계에서 격리하고, 원래 반환값과
checked/application 예외 instance를 그대로 보존해 관측 코드가 업무 계약을 침범하지 않도록
했다.

## 다음 작업을 위한 guard

- 다이얼렉트 성능 fixture는 query마다 양수 결과와 최대 반환 상한을 함께 검증한다.
- parent-child join 성능은 child row만 늘리지 말고 parent와 child를 모두 운영 cardinality로
  채운 뒤, 양쪽 access path와 full table scan 부재를 함께 증명한다.
- tenant-wide scan은 row page뿐 아니라 빈 partition 수에도 별도 상한을 둔다. 결과가 0건인
  page도 durable boundary cursor를 반환해야 다음 트랜잭션이 같은 빈 범위를 반복하지 않는다.
- 매 page freshness 검사는 page 상한과 별도로 SQL 수와 조회 cardinality를 고정한다.
  하위 scope 변경 집계가 필요하면 원본 변경 transaction의 단조 tenant epoch와 exact
  head lookup을 사용하고, 모든 child row를 반복 materialize하지 않는다.
- H2 성공 뒤 PostgreSQL Flyway constraint와 MySQL 지원 의미를 반드시 순차 확인한다.
- 시간 기반 SLO는 가능한 한 monotonic/fake clock으로 검증하고 실제 시간은 관측값으로 남긴다.
- singleton container를 사용하는 Spring 통합 테스트는 client/context 종료가 container보다
  먼저 일어나는지 확인한다.
- class 단위 context 종료와 JUnit 병렬 실행을 함께 쓸 때는 공통 resource lock으로
  context·DB·singleton container 공유자를 모두 배타화한다. 기반 class 상속 여부로
  공유 자원 사용자를 추정하지 않는다.
- custom security `Filter` bean을 `SecurityFilterChain`에 직접 추가했다면 servlet
  auto-registration을 끄고, container registration 부재를 회귀 테스트로 고정한다.
- stateless JWT filter는 no-token/invalid-token 요청에서 이전 `SecurityContext`를
  재사용하지 않는지 stale principal 회귀 테스트로 증명한다.
- metric tag에는 닫힌 enum만 사용하고 tenant·actor·payload·예외 상세를 넣지 않는다.
- 공유 DB 통합 테스트의 정리는 전역 `deleteAll()`보다 테스트가 소유한 tenant 범위로
  제한한다. migration fixture 잔여 데이터와 병렬 class의 tenant를 삭제하지 않아야 한다.
- V10 축소 전에는 aggregate null, legacy/new parity, 모든 writer의 dual-write window를
  운영 query와 runbook으로 먼저 증명한다.

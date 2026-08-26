# Issue #409 알림 event·persistence contract 분리 lesson

## 상황

알림 event 모듈이 write draft와 함께 JDBC table, repository, claim lifecycle까지
직접 노출하고 있었다. API도 concrete repository를 생성자와 configuration에서
참조해 event contract와 persistence contract의 방향이 흐려졌다. 목표는 새
module/dependency/schema migration 없이 event는 재사용 가능한 순수 port와
envelope만 제공하고, notification이 내구성 persistence를 소유하도록 경계를
재정렬하는 것이었다.

## 결정

- event에 NotificationOutboxWriter와 양수 id만 담는
  NotificationOutboxWriteReceipt를 두고, send/suppress/idempotency/recovery
  capability를 한 방향 port로 고정했다.
- table, JDBC repository, claim/work/observation store, waitlist persistence와
  persistence enum/helper를 appointment-notification/persistence로 이동했다.
- JdbcNotificationOutboxRepository는 event port를 구현하되, API는
  NotificationOutboxWriter만 주입받도록 생성자·ServiceConfig를 바꿨다.
- waitlist payload/codec/key/exception 같은 순수 contract는 event에 남기고,
  durable row와 adapter는 notification이 소유한다.
- 기존 bluetape4k codec/hasher, io.bluetape4k.assertions, Base58.randomString(8),
  Exposed transaction, leader policy, Resilience4j, Redis/Lettuce lifecycle과
  singleton/concurrency fixture를 재사용했다. 새 wrapper·dependency·polling
  loop는 만들지 않았다.
- 루트 Gradle fixture와 event jar/source forbidden guard로 persistence가
  event에 되돌아오는 경로를 회귀 차단했다.

## 결과

event jar에는 순수 notification writer/draft/envelope/codec/hasher만 남고,
notification persistence package가 table·repository·claim lifecycle의 실제
소유자가 되었다. API production source에는 concrete notification repository와
codec import가 없다. README 변형, ADR-15, consumer fixture가 같은 소유권과
migration 방향을 설명한다.

## 검증

- Task 0 baseline: event/notification test 성공, V14/V19/V21/V22 H2·MySQL·PostgreSQL
  migration checksum 12개를 고정했다.
- event RED → port 구현 → persistence 물리 이동 → API port 조립 → Base58 test
  isolation → source/jar fixture 순서로 단계별 compile/test를 통과했다.
- fixture와 jar/source boundary command가 BUILD SUCCESSFUL이었다.
- Flyway H2/MySQL/PostgreSQL matrix는 26건 성공, 1건 skip이었다. 생성된
  migration SQL은 실제 schema 변경이 없어 삭제했고 기존 checksum은 불변이었다.
- notification targeted regression 36건, API notification targeted regression
  34건, NotificationReminderRecoveryWiringTest 1건이 성공했다.
- git diff --check가 통과했고 Korean terminology audit에는 기존 API README의
  기준 데이터 용어 5건만 context 예외로 남았다.

## 예상 밖의 문제와 교정

1. Exposed migration scanner를 notification package 전체에 적용하자
   coroutine worker method에서 static-method 오류가 발생했다. table owner만
   스캔하도록 tablesPackage를 notification.persistence로 좁혀 다시 실행했고
   생성 결과와 checksum을 확인했다.
2. API wiring test에 NotificationOutboxWriter와 Database를
   @ConditionalOnBean으로 추가하자 Spring user configuration의 bean 등록 순서
   때문에 notificationReminderSchedulingRunner#poll이 unmatched 되었다. 기존
   API 조건은 NotificationOutboxHasher 기준이었으므로 그 경계를 복원하는
   482c4da1을 추가했고 wiring test가 다시 성공했다. 이 경험은 일반
   user configuration에서 후속 auto-configuration bean을 조건으로 직접
   묶지 말아야 한다는 근거가 되었다.
3. Base58 전환 대상은 테스트/H2 식별자뿐이다. durable recovery checkpoint,
   lease/security token, Redis namespace와 timing의 System.nanoTime()은
   의미가 달라 그대로 유지했다.

## 후속 guard

- event jar/source guard와 API source fixture를 모든 contract 경계 변경의
  최소 회귀 검사로 유지한다.
- auto-configuration 조건을 넓힐 때는 ApplicationContextRunner의 positive/
  negative case를 함께 실행하고, same-class bean ordering에 의존하지 않는다.
- notification worker 일부 public 생성자가 아직 concrete persistence 타입을
  노출하므로, 내부 capability port로 닫는 후속 Issue [#425](https://github.com/bluetape4k/clinic-appointment/issues/425)를 등록했다.
- 새 notification 테스트는 bluetape4k assertions와 Base58 격리 helper를
  먼저 검색하고, Testcontainers 대신 기존 singleton launcher를 사용한다.

## 문서 작성 점검

- [x] SPW-01: lesson의 독자·목적·현재 source와 검증 증거를 고정했다.
- [x] SPW-02: 상황·결정·결과·검증·miss/surprise·future guard를 포함했다.
- [x] SPW-03: 한국어 기술 문체와 code/command/identifier를 보존했다.
- [x] SPW-04: 구현 review·ADR·README·실제 test/checksum 결과를 대조했다.
- [x] SPW-05: 최종 Markdown read-back과 terminology audit를 수행했다.

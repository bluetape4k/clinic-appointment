# Issue #307 DDD 이벤트와 트랜잭션 경계 설계

## 목적

현재 `AppointmentService`는 Exposed의 직접 `transaction {}` 안에서 예약과 두
outbox 의도를 기록한 뒤 트랜잭션이 끝난 후 Spring 이벤트를 best-effort로
전달한다. 이 구조에서는 Spring의 트랜잭션 동기화와
`ExposedAggregateEventPublisher`가 같은 경계를 사용하는지 확인할 수 없고,
이벤트 전달 실패와 데이터 커밋의 관계도 명시적으로 검증되지 않는다.

이 설계의 목적은 Spring `@Transactional`이 Exposed Spring 7 transaction
manager와 실제로 같은 경계를 형성하는지 증명하고, 그 증거가 있을 때만
bounded command 경로에 DDD 이벤트 publisher를 적용할 수 있는 안전한 경계를
정하는 것이다.

## 현재 근거와 제약

- `appointment-api`는 `exposed-spring-boot4-starter`를 사용한다. 이 starter는
  `SpringTransactionManager`와 `@EnableTransactionManagement`를 자동 설정한다.
- `AppointmentService`의 쓰기 메서드는 직접 Exposed `transaction {}`를 열고,
  트랜잭션 이후 `ApplicationEventPublisher`를 `runCatching`으로 호출한다.
- `ExposedAggregateEventPublisher`는
  `TransactionSynchronizationManager.isSynchronizationActive()`와
  `isActualTransactionActive()`가 모두 참일 때만 event-bearing aggregate를
  등록하며, 커밋 시 event buffer를 비운다. 롤백 시 buffer를 보존한다.
- 동기 Spring listener는 publisher 호출 시점에 실행되므로 롤백으로 이미
  실행된 메모리 부작용을 되돌릴 수 없다. `@TransactionalEventListener`는
  커밋 후에만 실행할 수 있으므로 두 의미를 분리해 검증해야 한다.
- Issue 범위는 outbox 스키마·relay·retry·lease 변경, 전체 aggregate 모델 전환,
  Spring 이벤트의 durable 보장을 포함하지 않는다.
- 저장소의 H2 테스트가 성공해야 하며, `@Testcontainers`는 사용하지 않는다.
- publisher 검증 후보는
  `io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc`이며,
  현재 가져오는 `bluetape4k-dependencies` BOM이 관리하는 버전을 우선
  사용한다. 별도 version catalog 전면 변경은 하지 않는다.

## 선택지

### A. `AppointmentService` 전체를 즉시 `@Transactional`로 전환

모든 쓰기 메서드에 annotation을 붙이고 기존 직접 `transaction {}`와 legacy
이벤트 전달을 한 번에 재구성한다. 호출부는 단순하지만 Kotlin `suspend` 프록시,
중첩 Exposed transaction, listener 실패 정책이 동시에 바뀌어 실패 원인을
격리하기 어렵다. 선택하지 않는다.

### B. `@Transactional` bounded command pilot (선택)

Spring 프록시를 통해 호출되는 non-suspend command fixture를 만들고, 여기에
세 가지 저장 행(예약, 알림 outbox 의도, messaging outbox 의도)과 작은
`AggregateRoot`를 함께 기록한다. publisher 의존성은 먼저 `appointment-api`
검증 범위에만 추가한다.

다음 순서로 증명한다.

1. 직접 Exposed `transaction {}`만 실행하면 Spring 동기화가 활성화되지 않는다.
2. 프록시를 통과한 `@Transactional` command에서는 동기화와 실제 트랜잭션이
   모두 활성화되고 Exposed 쓰기가 같은 Spring 경계에 참여한다.
3. 정상 종료에서는 세 저장 행이 커밋되고 publisher가 전달한 event buffer가
   비워진다.
4. command가 예외를 전파하면 세 저장 행이 롤백되고 event buffer가 보존된다.
   동기 listener는 호출된 사실이 남을 수 있음을 명시하고, 별도의
   `@TransactionalEventListener`는 커밋에서만 호출되는지 검증한다.
5. listener 예외가 command 경계 밖으로 전파되면 커밋이 실패하고 저장 행이
   남지 않는지 검증한다.

이 pilot의 synchronous listener는 메모리 관찰·검증만 수행하며 외부 I/O나
재시도 대상 부작용을 수행하지 않는다. synchronous listener 실패는 publisher의
계약대로 command와 저장을 실패시킬 수 있으므로, 이 경로를 durable outbox
재처리 보장으로 표현하지 않는다. 커밋 후 durable 상태를 읽는 빠른 신호가
필요한 경우에는 `@TransactionalEventListener`와 opaque ID를 사용한다.

동기 listener 예외와 AFTER_COMMIT listener 예외의 결과는 분리한다. 전자는
command 예외·DB rollback·event buffer 보존을 확인하고, 후자는 이미 커밋된
행을 되돌리거나 command를 재시도하지 않으며 관찰 가능한 오류만 남기는지
확인한다.

테스트 context는 H2 `DataSource`, Exposed Boot 4 auto-configuration, publisher
auto-configuration을 실제로 로드한다. `@Transactional`이 붙은 open Spring
bean을 context에서 꺼내 호출해 프록시 경계를 통과시키며, baseline은 별도
직접 `transaction {}` 호출로 둔다. 따라서 수동으로
`TransactionSynchronizationManager.initSynchronization()`만 호출한 테스트는
성공 증거로 인정하지 않는다.

증명이 통과할 때만 실제 서비스에 common `DomainEvent`/adapter를 연결하는
후속 구현을 고려한다. 증명이 실패하면 현재 post-transaction signal과 worker
polling을 유지하고 실패 원인을 문서화한다.

### C. `TransactionTemplate` 직접 래핑

`SpringTransactionManager`를 주입한 `TransactionTemplate`으로 command를
감싼다. 경계가 명시적이라는 장점이 있지만 annotation proxy의 실제 동작을
검증하지 못하고, 호출 코드가 사용자가 제안한 `@Transactional`보다 무거워진다.
선택하지 않는다.

## 권장 구조

```text
Spring proxy
    |
    | @Transactional command()
    v
Exposed Spring 7 transaction manager
    |
    +-- appointment row
    +-- notification outbox intent
    +-- messaging outbox intent
    +-- ExposedAggregateEventPublisher.publishAfterSave(aggregate)
    |       +-- synchronous listener: 호출 즉시
    |       +-- @TransactionalEventListener: 커밋 후
    v
commit: 세 행 유지 + aggregate event buffer clear
rollback: 세 행 제거 + aggregate event buffer retain
```

pilot은 실제 `AppointmentService`의 기존 호출 계약을 바꾸지 않는다.
테스트가 먼저 Spring proxy와 Exposed manager의 결합을 증명한 후, 실제 적용
범위와 `AppointmentDomainEvent`의 common contract 채택 여부를 별도 결정한다.
이는 현재 post-transaction legacy signal을 검증되지 않은 상태에서 제거하지
않도록 한다.

## 실패 모드와 대응

| 실패 모드 | 검증 기준 | 대응 |
|---|---|---|
| 직접 Exposed transaction에서 publisher 호출 | 동기화 또는 실제 트랜잭션 조건 위반 예외 | publisher를 Spring 경계 밖에서 호출하지 않도록 유지 |
| `@Transactional` command 중 저장 실패 | 예약·두 outbox 의도 모두 미존재 | 예외를 삼키지 않고 command 호출자에게 전파 |
| 동기 listener 일부 전달 후 실패 | listener 예외가 command로 전파되고 DB 커밋 실패 | listener는 외부 I/O를 하지 않으며 durable 전달로 해석하지 않음 |
| 롤백 후 `@TransactionalEventListener` 실행 | listener 호출 수 0 | AFTER_COMMIT 의미를 유지하고 일반 listener와 혼용하지 않음 |
| Kotlin `suspend` annotation 경계가 기대와 다름 | proxy 호출에서 동기화 상태 또는 롤백 증명 실패 | 해당 suspend 경로에는 annotation을 적용하지 않고 bounded non-suspend 경계를 유지 |
| publisher 의존성 또는 auto-configuration 불일치 | H2 Spring context가 기동하지 않음 | 실제 서비스 wiring을 승격하지 않고 원인·버전을 기록 |

## 호환성과 전환

- 첫 변경은 `appointment-api` 테스트가 publisher를 직접 검증할 수 있는 최소
  의존성과 fixture로 제한한다.
- 기존 `AppointmentDomainEvent` 계층, post-transaction best-effort 전달,
  outbox 테이블과 relay 정책은 pilot 단계에서 변경하지 않는다.
- proof가 통과한 뒤에만 실제 command adapter를 별도 변경으로 승격한다. 그
  변경에서도 `@Transactional`이 적용되는 외부 Spring bean 경계를 유지하고
  self-invocation을 허용하지 않는다.
- `@Transactional`은 쓰기 경계에만 사용하고 조회용 직접 transaction과
  worker polling의 기존 동작은 건드리지 않는다.

## 문서와 호출자 계약

- 예약 행과 두 outbox 의도는 같은 command transaction의 원자적 결과이며,
  outbox가 재시도·복구의 권위(authority)라는 점을 KDoc 또는 테스트 fixture
  설명에 남긴다.
- Spring signal은 빠른 반응을 위한 보조 경로이고 durable 전달이 아니다.
  호출자는 signal 실패만으로 이미 커밋된 outbox 의도를 되돌렸다고 해석하지
  않는다.
- signal listener에는 예약 전체 payload를 싣지 않고 opaque identifier만
  전달한다. listener는 durable outbox 상태를 다시 조회해 필요한 정보를 얻는다.
- pilot fixture는 인증·tenant·clinic 권한을 새로 정의하는 public command가
  아니다. 고정된 테스트 식별자만 사용하고, 실제 요청 경로·권한 검사는 이
  이슈의 변경 대상이 아니다. 따라서 새 event origin이나 caller를 추가하지
  않으며, 실제 서비스 adapter를 만들 때 기존 command 권한 검사를 재사용한다.
- `@Transactional` command는 Spring bean 외부 호출을 통해서만 경계를
  형성한다. self-invocation과 검증되지 않은 `suspend` annotation 경계는
  지원 계약에서 제외한다.
- auto-configuration과 publisher는 pilot 검증 범위에서만 명시적으로 켜며,
  실제 서비스 wiring 승격 전에는 기본 경로가 바뀌지 않는다. publisher가
  동기화 없는 호출을 받으면 fail-closed 예외를 유지한다.
- commit·rollback·listener 실패의 모든 종료 경로에서 Spring synchronization,
  Exposed current transaction, DataSource resource, publisher registration이
  정리되는지 확인한다. rollback buffer는 command 소유의 비영속 상태로
  보존되며, 같은 aggregate 재사용은 명시적인 재시도 호출에서만 허용한다.

## 검증 계획과 수용 기준

### 테스트

- 직접 Exposed transaction과 Spring proxy `@Transactional` command의
  `TransactionSynchronizationManager` 상태를 대조한다.
- command 안에서 `TransactionManager.currentOrNull()?.connection`과
  Spring `ConnectionHolder`가 가리키는 JDBC connection identity를 대조해
  두 저장 계층이 같은 물리 connection/transaction에 참여하는지 확인한다.
- H2에서 세 저장 행의 commit/rollback 원자성을 확인한다.
- publisher commit 시 event buffer clear, rollback 시 buffer retain을 확인한다.
- 동기 listener 실패 전파와 AFTER_COMMIT listener의 commit-only 실행을
  각각 확인한다.
- Issue의 “rollback listener 전달 부재” 조건은
  `@TransactionalEventListener`에 적용한다. 일반 synchronous listener는
  rollback 전에 이미 호출될 수 있으므로, 호출 사실과 DB rollback을 별도
  assertion으로 남긴다.
- 의존성 또는 auto-configuration이 실제로 필요한 context에서 기동되는지
  module-scoped Gradle 테스트로 확인한다.
- connection identity가 어긋나거나 fault injection에서 부분 커밋이 보이면
  publisher adapter를 승격하지 않고 직접 transaction 경계를 유지한다.
- context에는 단일 `springTransactionManager`가 선택되었는지 확인하고,
  다른 `PlatformTransactionManager`가 추가되면 publisher auto-configuration이
  임의 manager를 고르지 않고 비활성화되는지 확인한다.
- 새 hot path benchmark는 범위에 포함하지 않는다. pilot은 실제 서비스
  경로의 성능 결론을 내리는 것이 아니라 트랜잭션 수와 세 저장 행의 원자성을
  검증하는 fixture이므로, 추가 round trip이 없는지 테스트 로그에서만
  확인한다.

### 수용 기준

1. 직접 Exposed 경계와 Spring `@Transactional` 경계의 차이가 테스트 결과와
   로그로 추적된다.
2. 정상 command에서 예약·알림 outbox·messaging outbox 의도가 함께 커밋된다.
3. 예외 command에서 세 저장 행이 함께 롤백되고 publisher buffer 보존 의미가
   확인된다.
4. synchronous listener와 AFTER_COMMIT listener의 호출 시점이 혼동되지 않는다.
5. synchronous listener 실패와 AFTER_COMMIT listener 실패의 DB·buffer·재시도
   결과가 서로 분리되어 고정된다.
6. 모든 종료 경로에서 Spring/Exposed/resource/publisher registration 정리가
   확인된다.
7. proof가 통과하지 않으면 기존 signal/polling을 유지한다는 결정이 코드와
   문서에 남는다.
8. H2 대상 `appointment-api` 테스트와 정적 검사가 통과한다.
9. outbox 권위와 signal의 보조적 의미, 외부 bean/self-invocation 호출 계약이
   KDoc 또는 fixture 문서에 명시된다.
10. synchronous listener에는 외부 I/O가 없고, event payload·로그에는 opaque ID
   외의 예약·환자 정보가 노출되지 않는다.

## 제외 범위

- outbox 테이블 스키마, relay, retry, lease, PostgreSQL 운영 설정 변경
- 전체 aggregate 모델 또는 모든 service 메서드의 annotation 전환
- Spring application event를 durable 이벤트로 간주하는 보장
- 인증·tenant·clinic 권한 모델의 신규 도입 또는 변경
- 검증되지 않은 dependency catalog 전면 개편

## 설계 결정

`@Transactional`을 사용하되, 먼저 bounded non-suspend command pilot에서
Spring-managed Exposed transaction과 publisher의 실제 결합을 검증한다. 이
증거가 실제 서비스 wiring의 전제이며, pilot 검증과 실제 서비스 적용을 한
커밋에서 혼합하지 않는다.

# Issue #307 @Transactional transaction boundary 구현 계획

> **에이전트용:** 이 계획을 작업 단위별로 실행하고 각 단계에서 회귀 검증과 리뷰 증거를 남긴다.

**목표:** appointment-api의 H2 Spring context에서 direct Exposed transaction과 외부 Spring bean을 통한 @Transactional command의 경계를 비교하고, 세 durable 행과 DDD publisher의 commit·rollback·listener 실패 계약을 회귀 테스트로 고정한다.

**구조:** 실제 AppointmentService 전체를 바꾸지 않고, ApplicationContextRunner가 만든 H2 context 안에 작은 AggregateRoot와 세 개의 fixture table을 둔다. @Transactional은 반드시 외부 Spring proxy를 통해 호출하며, Exposed Boot 4 transaction manager와 publisher auto-configuration을 실제로 로드한다. proof가 통과하면 publisher를 bounded adapter 후보로 기록하되, 기존 post-transaction signal과 outbox authority는 유지한다.

**기술 스택:** Kotlin 2.3, Spring Boot 4, Exposed v1 JDBC, @Transactional, ApplicationContextRunner, H2, bluetape4k-exposed-spring-boot-jdbc:1.12.1 BOM 관리 의존성, JUnit 5와 bluetape assertions

---

## 파일 구조와 책임

- 수정: appointment-api/build.gradle.kts — publisher Spring Boot JDBC 모듈을 test classpath에만 추가한다.
- 생성: appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentDddEventTransactionBoundaryTest.kt — H2 context, aggregate fixture, bounded command, commit·rollback·listener·resource 테스트를 한 파일에서 소유한다.
- 수정: appointment-api/src/main/kotlin/io/bluetape4k/clinic/appointment/api/service/AppointmentService.kt — 현재 예약·두 outbox 행이 durable authority이고 legacy signal은 보조 경로임을 KDoc에 명시한다. 실행 경계와 public API는 바꾸지 않는다.
- 수정: docs/superpowers/INDEX.md — 설계·계획·리뷰 링크와 진행 상태를 기록한다.
- 생성: docs/review/2026-08-19-issue-307-implementation-review.md — 구현 테스트와 acceptance criteria의 fresh 결과를 통합한다.

### 공통 fixture 계약

테스트 파일은 다음 이름과 규칙을 사용한다.

~~~kotlin
private object PilotAppointmentTable : Table("issue307_pilot_appointment") {
    val id = long("id")
}

private object PilotNotificationOutboxTable : Table("issue307_pilot_notification_outbox") {
    val appointmentId = long("appointment_id")
}

private object PilotMessagingOutboxTable : Table("issue307_pilot_messaging_outbox") {
    val appointmentId = long("appointment_id")
}

private data class PilotEvent(
    override val aggregateId: Long,
    val opaqueId: String,
    val attempt: Int,
) : DomainEvent<Long>, Serializable

private class PilotAggregate(
    override val id: Long,
) : AbstractAggregateRoot<Long>() {
    fun record(attempt: Int) = PilotEvent(id, "appointment-$id-$attempt", attempt).also(::recordDomainEvent)
}
~~~

@BeforeEach의 schema 생성과 삭제는 반드시 transaction(database) { SchemaUtils.createMissingTablesAndColumns(...); Table.deleteAll() }로 수행한다. @Testcontainers와 수동 TransactionSynchronizationManager.initSynchronization()은 사용하지 않는다.

### Task 1: publisher 의존성의 최소 추가

**파일:** appointment-api/build.gradle.kts

- [ ] dependencies의 test 의존성 영역에 다음 한 줄을 추가한다.

~~~kotlin
testImplementation("io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc")
~~~

- [ ] bluetape4k-dependencies:1.4.0이 bluetape4k-exposed-bom:1.12.1을 import하고 해당 모듈 버전을 관리하는지 확인한다.

실행 명령:

~~~bash
./gradlew :appointment-api:dependencyInsight \
  --dependency bluetape4k-exposed-spring-boot-jdbc \
  --configuration testCompileClasspath --no-daemon --console=plain
~~~

예상 결과: io.github.bluetape4k.exposed:bluetape4k-exposed-spring-boot-jdbc:1.12.1이 BOM에서 선택되고, 별도 version catalog 수정은 없다.

### Task 2: RED — Spring proxy와 direct Exposed 경계 대조

**파일:** appointment-api/src/test/kotlin/io/bluetape4k/clinic/appointment/api/config/AppointmentDddEventTransactionBoundaryTest.kt

- [ ] ApplicationContextRunner에 다음 auto-configuration만 명시한다.

~~~kotlin
AutoConfigurations.of(
    ExposedAutoConfiguration::class.java,
    ExposedAggregateEventPublisherAutoConfiguration::class.java,
)
~~~

- [ ] @TestConfiguration(proxyBeanMethods = false)에 EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build() DataSource를 destroyMethod = "shutdown"으로 등록하고, Database.connect(dataSource) handle을 bean으로 등록한다. fixture command bean은 open class여야 한다.
- [ ] command bean의 public 메서드를 다음 형태로 선언한다.

~~~kotlin
@Transactional
open fun commit(appointmentId: Long) {
    transaction {
        insertThreeRows(appointmentId)
        aggregate.record()
        publisher.publishAfterSave(aggregate)
    }
}
~~~

메서드는 테스트 외부에서 context.getBean(PilotTransactionalCommand::class.java)로 호출한다. 같은 command bean 내부의 self-invocation은 테스트하지 않는다.

- [ ] direct baseline은 별도 transaction(database) {} 호출로 두고, TransactionSynchronizationManager.isSynchronizationActive()와 isActualTransactionActive()가 false임을 확인한다. publisher에 event가 등록된 aggregate를 baseline에서 넘기면 fail-closed 예외가 발생하고 세 행이 생기지 않아야 한다.
- [ ] proxy command에서는 두 synchronization flag가 true이고, TransactionManager.currentOrNull()?.connection의 underlying JDBC connection identity와 DataSourceUtils.getConnection(dataSource) identity가 동일함을 확인한다.
- [ ] publisher auto-configuration을 명시하지 않은 baseline context에는 publisher bean이 없고, 명시적으로 추가한 context에는 단일 publisher bean이 생기는지 확인한다. auto-configuration의 class condition과 Exposed transaction manager 이후 ordering도 assertion한다.

RED 실행:

~~~bash
./gradlew :appointment-api:test --tests \
  "io.bluetape4k.clinic.appointment.api.config.AppointmentDddEventTransactionBoundaryTest" \
  --no-daemon --console=plain
~~~

예상 결과: 아직 fixture와 dependency가 없으므로 컴파일 실패한다. 실패 원인은 테스트 파일 또는 publisher symbol 누락이어야 하며 unrelated module failure가 아니어야 한다.

### Task 3: GREEN — commit·rollback 원자성과 buffer 수명

**파일:** 동일 테스트 파일

- [ ] commit 성공 테스트에서 appointment·notification outbox·messaging outbox 각 1행, synchronous listener가 opaque ID 1개를 관찰하고, command 반환 후 aggregate buffer가 비어 있음을 assertion한다.
- [ ] 예외를 전파하는 rollback command를 추가한다. 세 행을 기록하고 publisher를 호출한 뒤 IllegalStateException("pilot rollback")을 던진다. 호출 후 세 행이 모두 0이고 synchronous listener가 rollback 전에 호출될 수 있음을 별도로 기록하며, aggregate buffer가 1개 유지됨을 assertion한다.
- [ ] rollback aggregate는 다음 성공 command에서만 명시적으로 새 aggregate instance로 재시도한다. 이전 aggregate의 buffer를 자동으로 재사용하지 않으며, 성공 후 새 instance buffer가 비어 있음을 확인한다.
- [ ] rollback event 기준 데이터의 attempt/version과 성공 재시도의 opaque ID를 대조해 같은 event가 두 번 발행되지 않음을 확인한다. retry는 새 aggregate instance와 증가한 attempt를 사용하고 성공 후 이전 buffer를 참조하지 않는다.
- [ ] 모든 Exposed 쓰기는 transaction {} 안에서 수행하고, 조회 assertion도 같은 Database handle을 사용한다.

### Task 4: GREEN — listener 실패 의미와 auto-configuration fail-closed

**파일:** 동일 테스트 파일

- [ ] synchronous ApplicationEventPublisher listener가 예외를 던지는 context를 만들고, command 예외 전파·세 행 rollback·publisher buffer 보존을 확인한다. listener는 외부 I/O를 하지 않고 메모리 observation만 수행한다.
- [ ] @TransactionalEventListener bean은 commit에서만 opaque ID를 받고 rollback에서는 호출 횟수 0이어야 한다. AFTER_COMMIT listener가 예외를 던져도 이미 커밋한 세 행을 되돌리거나 command 재시도하지 않음을 확인한다.
- [ ] 단일 springTransactionManager context에서 publisher bean이 1개 선택되는지 확인한다.
- [ ] 두 개의 비-primary PlatformTransactionManager를 등록한 context에서는 publisher auto-configuration이 publisher를 만들지 않고 context가 fail-closed로 유지되는지 확인한다. primary 하나를 둔 조합은 명시적 선택이 가능한 별도 assertion으로 남긴다.
- [ ] commit·rollback·synchronous listener failure·AFTER_COMMIT failure 후 각각 TransactionSynchronizationManager.isSynchronizationActive()가 false이고, TransactionManager.currentOrNull()가 null이며, DataSourceUtils.isConnectionTransactional(...) resource가 남지 않고, publisher registration이 다음 command를 방해하지 않는지 확인한다.

### Task 5: 코드 문서와 acceptance criteria 정합화

**파일:** AppointmentService.kt, docs/superpowers/INDEX.md, docs/review/2026-08-19-issue-307-implementation-review.md

- [ ] AppointmentService class KDoc에 예약 row와 notification/messaging outbox intent가 durable authority이며 publishLegacyEventSafely는 빠른 보조 signal이라는 사실을 한국어로 추가한다. runCatching 동작, 메서드 signature, transaction 위치는 바꾸지 않는다.
- [ ] 기존 legacy signal의 warn 로그와 opaque correlation ID 관찰을 보존한다. 이번 pilot에서는 예외 분류·metric 정책을 확장하지 않고, 그 보강은 실제 adapter 승격 전 후속 이슈로 명시한다.
- [ ] fixture KDoc 또는 test class KDoc에 외부 Spring bean 호출, opaque ID, synchronous listener의 외부 I/O 금지, @TransactionalEventListener commit-only 계약을 적는다.
- [ ] 구현 리뷰 문서에 Issue #307 완료 조건 6개와 설계 수용 기준 10개를 각각 테스트 method/file/output으로 매핑한다. P0/P1=0을 확인하고, proof가 통과해도 현재 AppointmentService 전체 wiring은 이번 bounded pilot에서 승격하지 않았음을 결정으로 기록한다.
- [ ] 새 문서마다 audit-korean-terms.mjs와 git diff --check를 실행한다.

### Task 6: 모듈 검증과 보류 조건

- [ ] targeted test를 먼저 실행한다.

~~~bash
./gradlew :appointment-api:test --tests \
  "io.bluetape4k.clinic.appointment.api.config.AppointmentDddEventTransactionBoundaryTest" \
  --rerun-tasks --no-daemon --console=plain
~~~

- [ ] Kotlin 컴파일과 전체 appointment-api 테스트를 순서대로 실행한다.

~~~bash
./gradlew :appointment-api:compileKotlin :appointment-api:compileTestKotlin --no-daemon --console=plain
./gradlew :appointment-api:test --no-daemon --console=plain
~~~

- [ ] dependencyInsight, targeted test, compile, module test, git diff --check, Korean terminology audit의 출력과 commit SHA를 구현 리뷰에 남긴다.
- [ ] 새 Kotlin 코드가 org.jetbrains.exposed.v1 import와 transaction receiver를 사용하고 deprecated Exposed import 또는 receiver shadowing을 만들지 않는지 정적 diff로 확인한다. public API/README 변경은 없으므로 README 검증은 N/A 근거를 리뷰에 기록한다.
- [ ] bounded non-suspend fixture이므로 coroutine cancellation·dispatcher와 concurrency stress는 범위 N/A로 기록하되, @Transactional suspend 전환을 하지 않았다는 source evidence를 남긴다.
- [ ] 다음 중 하나라도 발생하면 실제 서비스 adapter나 AppointmentDomainEvent 공통 계약 전환을 하지 않고, 현재 direct transaction·legacy signal·worker polling을 유지한다: connection identity 불일치, 부분 커밋, rollback listener 호출, resource leak, 다중 manager 임의 선택, H2 context startup failure.
- [ ] 모든 테스트가 통과하고 P0/P1이 없으면 Issue #307용 커밋을 Lore protocol로 작성한다. 계획·테스트·문서가 같은 커밋에 포함되는지 확인하고, unrelated worktree 변경은 포함하지 않는다.

## 수용 기준 매핑

| 설계 수용 기준 | 계획 항목 | 증거 |
|---|---|---|
| direct와 Spring @Transactional 차이 | Task 2 | synchronization flags, proxy call log |
| 세 durable 행 commit | Task 3 | H2 row count 3 |
| 예외 시 세 행 rollback과 buffer 보존 | Task 3 | row count 0, aggregate buffer 1 |
| synchronous/AFTER_COMMIT 시점 분리 | Task 4 | listener counters |
| 두 listener 실패 결과 분리 | Task 4 | propagated exception vs committed rows |
| 종료 resource 정리 | Task 4 | Spring/Exposed/DataSource/publisher assertions |
| proof 실패 시 기존 경로 유지 | Task 5·6 | KDoc와 보류 조건 |
| H2 모듈 테스트·정적 검사 | Task 1·6 | Gradle/audit output |
| outbox authority·signal·호출 계약 문서화 | Task 5 | KDoc/review mapping |
| opaque ID와 외부 I/O 금지 | Task 3·4·5 | event payload/listener assertion |

## 실행 전 자기 점검

- spec의 선택지 B와 bounded non-suspend 범위를 그대로 유지하고, 전체 service annotation 전환을 추가하지 않는다.
- publisher 의존성은 BOM 관리 versionless 선언만 사용하고 catalog를 전면 변경하지 않는다.
- Exposed 쓰기와 schema setup은 repository 규칙인 transaction {}와 SchemaUtils.createMissingTablesAndColumns/Table.deleteAll()을 따른다.
- @Testcontainers를 추가하지 않으며, H2가 실패하거나 context가 기동하지 않으면 원인을 해결하거나 proof 실패 결정으로 되돌린다.
- plan에는 모호한 placeholder 단계가 없고 모든 검증 명령·파일·보류 조건이 적혀 있다.

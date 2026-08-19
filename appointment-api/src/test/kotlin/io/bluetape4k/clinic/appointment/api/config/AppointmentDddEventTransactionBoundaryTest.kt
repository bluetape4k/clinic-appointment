package io.bluetape4k.clinic.appointment.api.config

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.exposed.core.ddd.AbstractAggregateRoot
import io.bluetape4k.exposed.core.ddd.DomainEvent
import io.bluetape4k.spring.data.exposed.jdbc.config.ExposedAggregateEventPublisherAutoConfiguration
import io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfiguration
import io.bluetape4k.spring.data.exposed.jdbc.ddd.ExposedAggregateEventPublisher
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.spring.boot4.autoconfigure.ExposedAutoConfiguration
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.Serializable
import java.sql.Connection
import java.time.Instant
import java.util.function.Supplier
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private object PilotAppointmentTable : Table("issue307_pilot_appointment") {
    val id = long("id")
}

private object PilotNotificationOutboxTable : Table("issue307_pilot_notification_outbox") {
    val id = long("appointment_id")
}

private object PilotMessagingOutboxTable : Table("issue307_pilot_messaging_outbox") {
    val id = long("appointment_id")
}

private fun insertPilotRows(appointmentId: Long) {
    PilotAppointmentTable.insert { it[id] = appointmentId }
    PilotNotificationOutboxTable.insert { it[id] = appointmentId }
    PilotMessagingOutboxTable.insert { it[id] = appointmentId }
}

/**
 * Issue #307의 bounded non-suspend transaction 경계를 H2와 실제 Spring proxy로 검증합니다.
 *
 * 외부 Spring bean의 [TransactionalPilotCommand.commit]만 `@Transactional`로 감싸며,
 * direct Exposed `transaction {}` 호출은 의도적으로 publisher가 거부하는 baseline으로 남깁니다.
 * listener는 opaque identifier만 메모리에 기록하고 외부 I/O를 수행하지 않습니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppointmentDddEventTransactionBoundaryTest {

    private lateinit var dataSource: EmbeddedDatabase
    private lateinit var fixtureDatabase: Database
    private val registeredContextDatabases = mutableListOf<Database>()

    @BeforeAll
    fun startFixtureDatabase() {
        dataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()
        fixtureDatabase = Database.connect(dataSource)
    }

    @BeforeEach
    fun prepareFixtureTables() {
        transaction(fixtureDatabase) {
            SchemaUtils.createMissingTablesAndColumns(
                PilotAppointmentTable,
                PilotNotificationOutboxTable,
                PilotMessagingOutboxTable,
            )
            PilotMessagingOutboxTable.deleteAll()
            PilotNotificationOutboxTable.deleteAll()
            PilotAppointmentTable.deleteAll()
        }
    }

    @AfterEach
    fun releaseSpringContextDatabaseAndCheckThreadState() {
        registeredContextDatabases.forEach(TransactionManager::closeAndUnregister)
        registeredContextDatabases.clear()

        TransactionSynchronizationManager.isSynchronizationActive().shouldBeFalse()
        TransactionSynchronizationManager.isActualTransactionActive().shouldBeFalse()
        TransactionSynchronizationManager.hasResource(dataSource).shouldBeFalse()
        TransactionManager.currentOrNull().shouldBeNull()
    }

    @AfterAll
    fun stopFixtureDatabase() {
        TransactionManager.closeAndUnregister(fixtureDatabase)
        dataSource.shutdown()
    }

    @Test
    fun `direct Exposed transaction is fail closed while external transactional proxy shares its connection`() {
        runnerWithPublisher().run { context ->
            captureContextDatabase()
            val publisher = context.getBean(ExposedAggregateEventPublisher::class.java)
            val listener = context.getBean(PilotEventListener::class.java)
            val directAggregate = PilotAggregate(101L)
            var directSynchronizationActive = true
            var directTransactionActive = true

            assertFailsWith<IllegalStateException> {
                transaction(fixtureDatabase) {
                    directSynchronizationActive = TransactionSynchronizationManager.isSynchronizationActive()
                    directTransactionActive = TransactionSynchronizationManager.isActualTransactionActive()
                    insertPilotRows(directAggregate.id)
                    directAggregate.record(attempt = 1)
                    publisher.publishAfterSave(directAggregate)
                }
            }

            directSynchronizationActive.shouldBeFalse()
            directTransactionActive.shouldBeFalse()
            rowCounts() shouldBeEqualTo RowCounts(0L, 0L, 0L)
            listener.synchronousIds.shouldBeEmpty()
            directAggregate.domainEvents() shouldHaveSize 1

            val command = context.getBean(TransactionalPilotCommand::class.java)
            val proxyAggregate = PilotAggregate(102L)
            val observation = command.commit(proxyAggregate, attempt = 1)

            observation.synchronizationActive.shouldBeTrue()
            observation.actualTransactionActive.shouldBeTrue()
            observation.sameJdbcConnection.shouldBeTrue()
            rowCounts() shouldBeEqualTo RowCounts(1L, 1L, 1L)
            listener.synchronousIds shouldBeEqualTo listOf("appointment-102-1")
            listener.afterCommitIds shouldBeEqualTo listOf("appointment-102-1")
            proxyAggregate.domainEvents().shouldBeEmpty()
            assertNoThreadBoundTransaction()
        }
    }

    @Test
    fun `commit keeps three durable rows and clears the aggregate buffer after completion`() {
        runnerWithPublisher().run { context ->
            captureContextDatabase()
            val command = context.getBean(TransactionalPilotCommand::class.java)
            val listener = context.getBean(PilotEventListener::class.java)
            val aggregate = PilotAggregate(201L)

            command.commit(aggregate, attempt = 1)

            rowCounts() shouldBeEqualTo RowCounts(1L, 1L, 1L)
            listener.synchronousIds shouldBeEqualTo listOf("appointment-201-1")
            listener.afterCommitIds shouldBeEqualTo listOf("appointment-201-1")
            aggregate.domainEvents().shouldBeEmpty()
            assertNoThreadBoundTransaction()
        }
    }

    @Test
    fun `rollback removes all durable rows and retains the event buffer for explicit retry`() {
        runnerWithPublisher().run { context ->
            captureContextDatabase()
            val command = context.getBean(TransactionalPilotCommand::class.java)
            val listener = context.getBean(PilotEventListener::class.java)
            val failedAggregate = PilotAggregate(301L)

            assertFailsWith<IllegalStateException> {
                command.rollback(failedAggregate, attempt = 1)
            }

            rowCounts() shouldBeEqualTo RowCounts(0L, 0L, 0L)
            listener.synchronousIds shouldBeEqualTo listOf("appointment-301-1")
            listener.afterCommitIds.shouldBeEmpty()
            failedAggregate.domainEvents() shouldHaveSize 1
            assertNoThreadBoundTransaction()

            val retryAggregate = PilotAggregate(302L)
            command.commit(retryAggregate, attempt = 2)

            rowCounts() shouldBeEqualTo RowCounts(1L, 1L, 1L)
            listener.synchronousIds shouldBeEqualTo listOf("appointment-301-1", "appointment-302-2")
            listener.afterCommitIds shouldBeEqualTo listOf("appointment-302-2")
            failedAggregate.domainEvents() shouldHaveSize 1
            retryAggregate.domainEvents().shouldBeEmpty()
            listener.synchronousEvents.map { it.opaqueId }.distinct() shouldBeEqualTo
                    listOf("appointment-301-1", "appointment-302-2")
        }
    }

    @Test
    fun `synchronous listener failure propagates and rolls back while after commit failure leaves committed rows`() {
        runnerWithPublisher().run { context ->
            captureContextDatabase()
            val command = context.getBean(TransactionalPilotCommand::class.java)
            val listener = context.getBean(PilotEventListener::class.java)

            listener.synchronousFailure = IllegalStateException("synchronous listener failed")
            val synchronousAggregate = PilotAggregate(401L)
            assertFailsWith<IllegalStateException> {
                command.commit(synchronousAggregate, attempt = 1)
            }
            rowCounts() shouldBeEqualTo RowCounts(0L, 0L, 0L)
            synchronousAggregate.domainEvents() shouldHaveSize 1
            listener.afterCommitIds.shouldBeEmpty()
            assertNoThreadBoundTransaction()

            listener.synchronousFailure = null
            listener.afterCommitFailure = IllegalStateException("after commit listener failed")
            val afterCommitAggregate = PilotAggregate(402L)
            val afterCommitResult = runCatching { command.commit(afterCommitAggregate, attempt = 1) }
            afterCommitResult.exceptionOrNull().shouldBeNull()

            command.invocationCount() shouldBeEqualTo 2
            rowCounts() shouldBeEqualTo RowCounts(1L, 1L, 1L)
            listener.afterCommitIds shouldBeEqualTo listOf("appointment-402-1")
            afterCommitAggregate.domainEvents().shouldBeEmpty()
            assertNoThreadBoundTransaction()
        }
    }

    @Test
    fun `publisher auto configuration is absent without opt in and fail closed for ambiguous managers`() {
        runnerWithoutPublisher().run { context ->
            captureContextDatabase()
            context.getBeansOfType(ExposedAggregateEventPublisher::class.java).isEmpty().shouldBeTrue()
        }

        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExposedAggregateEventPublisherAutoConfiguration::class.java))
            .withBean("dataSource", DataSource::class.java, Supplier { dataSource })
            .withUserConfiguration(AmbiguousTransactionManagerConfiguration::class.java)
            .run { context ->
                context.startupFailure.shouldBeNull()
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).isEmpty().shouldBeTrue()
            }
    }

    @Test
    fun `publisher auto configuration honors a primary manager among two candidates`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ExposedAggregateEventPublisherAutoConfiguration::class.java))
            .withBean("dataSource", DataSource::class.java, Supplier { dataSource })
            .withUserConfiguration(PrimaryTransactionManagerConfiguration::class.java)
            .run { context ->
                context.getBeansOfType(ExposedAggregateEventPublisher::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `publisher auto configuration activates with a single manager candidate and declares its class condition`() {
        runnerWithPublisherAutoConfiguration().run { context ->
            captureContextDatabase()
            context.getBeansOfType(ExposedAggregateEventPublisher::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(PlatformTransactionManager::class.java).size shouldBeEqualTo 1
        }

        val condition = AnnotatedElementUtils.findMergedAnnotation(
            ExposedAggregateEventPublisherAutoConfiguration::class.java,
            ConditionalOnClass::class.java,
        ).shouldNotBeNull()
        condition.value.map { it.qualifiedName }
            .contains(ApplicationEventPublisher::class.java.name).shouldBeTrue()
        AnnotatedElementUtils.findMergedAnnotation(
            ExposedAggregateEventPublisherAutoConfiguration::class.java,
            ConditionalOnSingleCandidate::class.java,
        ).shouldNotBeNull()
        val autoConfiguration = ExposedAggregateEventPublisherAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)
            .shouldNotBeNull()
        autoConfiguration.after.map { it.qualifiedName } shouldBeEqualTo
                listOf("io.bluetape4k.spring.data.exposed.jdbc.config.ExposedSpringDataAutoConfiguration")
    }

    private fun runnerWithPublisher(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ExposedAutoConfiguration::class.java,
            )
        )
        .withBean("dataSource", DataSource::class.java, Supplier { dataSource })
        .withUserConfiguration(
            PublisherFixtureConfiguration::class.java,
            PilotEventConfiguration::class.java,
        )

    private fun runnerWithPublisherAutoConfiguration(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ExposedSpringDataAutoConfiguration::class.java,
                ExposedAggregateEventPublisherAutoConfiguration::class.java,
            )
        )
        .withBean("dataSource", DataSource::class.java, Supplier { dataSource })

    private fun runnerWithoutPublisher(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                ExposedAutoConfiguration::class.java,
            )
        )
        .withBean("dataSource", DataSource::class.java, Supplier { dataSource })

    private fun captureContextDatabase() {
        requireNotNull(TransactionManager.primaryDatabase)
            .takeUnless { it === fixtureDatabase }
            ?.let(registeredContextDatabases::add)
    }

    private fun rowCounts(): RowCounts = transaction(fixtureDatabase) {
        RowCounts(
            appointments = PilotAppointmentTable.selectAll().count(),
            notificationOutbox = PilotNotificationOutboxTable.selectAll().count(),
            messagingOutbox = PilotMessagingOutboxTable.selectAll().count(),
        )
    }

    private fun assertNoThreadBoundTransaction() {
        TransactionSynchronizationManager.isSynchronizationActive().shouldBeFalse()
        TransactionSynchronizationManager.isActualTransactionActive().shouldBeFalse()
        TransactionSynchronizationManager.hasResource(dataSource).shouldBeFalse()
        TransactionManager.currentOrNull().shouldBeNull()
    }

    private data class RowCounts(
        val appointments: Long,
        val notificationOutbox: Long,
        val messagingOutbox: Long,
    )

    private data class CommandObservation(
        val synchronizationActive: Boolean,
        val actualTransactionActive: Boolean,
        val sameJdbcConnection: Boolean,
    )

    private data class PilotEvent(
        override val aggregateId: Long,
        val opaqueId: String,
        val attempt: Int,
        override val occurredAt: Instant = Instant.parse("2026-08-19T00:00:00Z"),
    ) : DomainEvent<Long>, Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private class PilotAggregate(
        override val id: Long,
    ) : AbstractAggregateRoot<Long>() {
        fun record(attempt: Int): PilotEvent =
            PilotEvent(id, "appointment-$id-$attempt", attempt).also(::recordDomainEvent)
    }

    private class PilotEventListener {
        val synchronousEvents = mutableListOf<PilotEvent>()
        val afterCommitEvents = mutableListOf<PilotEvent>()
        var synchronousFailure: RuntimeException? = null
        var afterCommitFailure: RuntimeException? = null

        val synchronousIds: List<String>
            get() = synchronousEvents.map(PilotEvent::opaqueId)

        val afterCommitIds: List<String>
            get() = afterCommitEvents.map(PilotEvent::opaqueId)

        @EventListener
        fun onSynchronous(event: PilotEvent) {
            synchronousEvents += event
            synchronousFailure?.let { throw it }
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        fun onAfterCommit(event: PilotEvent) {
            afterCommitEvents += event
            afterCommitFailure?.let { throw it }
        }
    }

    private open class TransactionalPilotCommand(
        private val publisher: ExposedAggregateEventPublisher,
        private val dataSource: DataSource,
    ) {
        private val invocations = AtomicInteger()

        open fun invocationCount(): Int = invocations.get()

        @Transactional
        open fun commit(aggregate: PilotAggregate, attempt: Int): CommandObservation {
            invocations.incrementAndGet()
            return transaction {
                insertPilotRows(aggregate.id)
                aggregate.record(attempt)
                publisher.publishAfterSave(aggregate)
                val exposedConnection = TransactionManager.currentOrNull()
                    .shouldNotBeNull()
                    .connection
                    .connection as Connection
                val springConnection = DataSourceUtils.getConnection(dataSource)
                CommandObservation(
                    synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive(),
                    actualTransactionActive = TransactionSynchronizationManager.isActualTransactionActive(),
                    sameJdbcConnection = exposedConnection === springConnection,
                )
            }
        }

        @Transactional
        open fun rollback(aggregate: PilotAggregate, attempt: Int) {
            invocations.incrementAndGet()
            transaction {
                insertPilotRows(aggregate.id)
                aggregate.record(attempt)
                publisher.publishAfterSave(aggregate)
                error("pilot rollback")
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    private class PublisherFixtureConfiguration {
        @Bean
        fun exposedAggregateEventPublisher(
            applicationEventPublisher: ApplicationEventPublisher,
        ): ExposedAggregateEventPublisher = ExposedAggregateEventPublisher(applicationEventPublisher)
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    private class PilotEventConfiguration {
        @Bean
        fun pilotEventListener(): PilotEventListener = PilotEventListener()

        @Bean
        fun transactionalPilotCommand(
            publisher: ExposedAggregateEventPublisher,
            dataSource: DataSource,
        ): TransactionalPilotCommand = TransactionalPilotCommand(publisher, dataSource)
    }

    @TestConfiguration(proxyBeanMethods = false)
    private class AmbiguousTransactionManagerConfiguration {
        @Bean
        fun firstTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        fun secondTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }

    @TestConfiguration(proxyBeanMethods = false)
    private class PrimaryTransactionManagerConfiguration {
        @Bean
        @org.springframework.context.annotation.Primary
        fun primaryTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        fun secondaryTransactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }
}

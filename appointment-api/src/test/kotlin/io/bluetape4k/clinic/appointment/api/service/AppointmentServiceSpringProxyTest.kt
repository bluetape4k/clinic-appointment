package io.bluetape4k.clinic.appointment.api.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.clinic.appointment.api.dto.CreateAppointmentRequest
import io.bluetape4k.clinic.appointment.api.notification.CommitmentAppointmentNotification
import io.bluetape4k.clinic.appointment.api.notification.AppointmentNotificationWriter
import io.bluetape4k.clinic.appointment.api.notification.MemberResolution
import io.bluetape4k.clinic.appointment.messaging.AppointmentMessagingContext
import io.bluetape4k.clinic.appointment.messaging.AppointmentOutboxWriter
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.tables.AppointmentIdempotencies
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentIdempotencyRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.statemachine.AppointmentStateMachine
import io.bluetape4k.clinic.appointment.event.notification.CancellationReasonCode
import io.bluetape4k.clinic.appointment.model.dto.AppointmentRecord
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.service.AppointmentCommandContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.spring7.transaction.SpringTransactionManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

private object ProxyOutboxRows : LongIdTable("proxy_test_outbox") {
    val appointmentId = long("appointment_id")
}

/** 실제 Spring transaction proxy가 Exposed 현재 transaction과 create의 commit 순서를 공유하는지 검증합니다. */
class AppointmentServiceSpringProxyTest {

    private lateinit var dataSource: EmbeddedDatabase
    private lateinit var database: Database
    private lateinit var service: AppointmentService
    private lateinit var writer: AppointmentNotificationWriter
    private var tenantGroupId = 0L
    private var clinicId = 0L
    private var doctorId = 0L
    private var treatmentTypeId = 0L
    private var failWriter = false
    private var notificationCommitted = false
    private var springTransactionActive = false
    private var publishedEvents = emptyList<Any>()
    private var eventObservedAfterCommit = false

    @BeforeEach
    fun setUp() {
        failWriter = false
        notificationCommitted = false
        springTransactionActive = false
        publishedEvents = emptyList()
        eventObservedAfterCommit = false
        dataSource = EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build()
        database = Database.connect(dataSource)
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(
                TenantGroups,
                Clinics,
                Doctors,
                TreatmentTypes,
                Equipments,
                ConsultationTopics,
                Appointments,
                AppointmentIdempotencies,
                ProxyOutboxRows,
            )
            ProxyOutboxRows.deleteAll()
            AppointmentIdempotencies.deleteAll()
            Appointments.deleteAll()
            TreatmentTypes.deleteAll()
            Doctors.deleteAll()
            Clinics.deleteAll()
            TenantGroups.deleteAll()

            tenantGroupId = TenantGroups.insertAndGetId {
                it[tenantCode] = "proxy-test"
                it[displayName] = "Proxy Test"
            }.value
            clinicId = Clinics.insertAndGetId {
                it[Clinics.tenantGroupId] = this@AppointmentServiceSpringProxyTest.tenantGroupId
                it[name] = "Proxy Clinic"
                it[timezone] = "Asia/Seoul"
            }.value
            doctorId = Doctors.insertAndGetId {
                it[Doctors.clinicId] = this@AppointmentServiceSpringProxyTest.clinicId
                it[name] = "Proxy Doctor"
            }.value
            treatmentTypeId = TreatmentTypes.insertAndGetId {
                it[TreatmentTypes.clinicId] = this@AppointmentServiceSpringProxyTest.clinicId
                it[name] = "Proxy Treatment"
                it[defaultDurationMinutes] = 30
            }.value
        }

        writer = object : AppointmentNotificationWriter {
            override fun appointmentCreated(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                resolution: MemberResolution,
            ) {
                if (failWriter) {
                    error("proxy writer failed")
                }
                springTransactionActive = TransactionSynchronizationManager.isActualTransactionActive()
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCommit() {
                            notificationCommitted = true
                        }
                    }
                )
            }

            override fun statusChanged(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                from: AppointmentState,
                to: AppointmentState,
            ) = Unit

            override fun cancelled(
                tenantGroupId: Long,
                record: AppointmentRecord,
                version: Long,
                reasonCode: CancellationReasonCode?,
            ) = Unit

            override fun rescheduled(
                tenantGroupId: Long,
                original: AppointmentRecord,
                replacement: AppointmentRecord,
                version: Long,
            ) = Unit

            override fun commitmentRequested(notification: CommitmentAppointmentNotification) = Unit

            override fun commitmentConfirmed(notification: CommitmentAppointmentNotification) = Unit

            override fun commitmentCancelled(
                notification: CommitmentAppointmentNotification,
                reasonCode: CancellationReasonCode?,
            ) = Unit

            override fun commitmentRescheduled(
                previous: CommitmentAppointmentNotification,
                replacement: CommitmentAppointmentNotification,
            ) = Unit
        }

        val publisher = object : ApplicationEventPublisher {
            override fun publishEvent(event: Any) {
                eventObservedAfterCommit = notificationCommitted
                publishedEvents = publishedEvents + event
            }
        }
        val target = AppointmentService(
            appointmentRepository = AppointmentRepository(),
            stateMachine = AppointmentStateMachine(),
            eventPublisher = publisher,
            stateHistoryRepository = AppointmentStateHistoryRepository(),
            idempotencyRepository = AppointmentIdempotencyRepository(),
            idempotencyProperties = AppointmentIdempotencyProperties(),
            idempotencyClock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC),
            clinicRepository = ClinicRepository(),
            notificationWriter = writer,
            appointmentOutboxWriter = object : AppointmentOutboxWriter {
                override fun created(scope: TenantClinicScope, appointment: AppointmentRecord, context: AppointmentMessagingContext) {
                    ProxyOutboxRows.insert {
                        it[appointmentId] = checkNotNull(appointment.id)
                    }
                }
                override fun statusChanged(scope: TenantClinicScope, appointment: AppointmentRecord, fromState: AppointmentState, context: AppointmentMessagingContext, reasonCode: CancellationReasonCode?) = Unit
                override fun cancelled(scope: TenantClinicScope, appointment: AppointmentRecord, context: AppointmentMessagingContext, reasonCode: CancellationReasonCode?) = Unit
                override fun rescheduled(scope: TenantClinicScope, original: AppointmentRecord, replacement: AppointmentRecord, context: AppointmentMessagingContext) = Unit
            },
        )
        service = transactionalProxy(target, SpringTransactionManager(dataSource, DatabaseConfig {}, false))
    }

    @AfterEach
    fun tearDown() {
        TransactionManager.closeAndUnregister(database)
        dataSource.shutdown()
    }

    @Test
    fun `Spring proxy create는 Exposed row와 outbox intent를 commit하고 signal은 after commit에 전달한다`() {
        AopUtils.isAopProxy(service).shouldBeTrue()
        val result = service.create(
            tenantGroupId = tenantGroupId,
            request = request(),
            idempotencyKey = "proxy-create",
            resolution = MemberResolution.Resolved(MemberId("member-1")),
            commandContext = AppointmentCommandContext.root("proxy-test"),
        )

        result.replayed shouldBeEqualTo false
        springTransactionActive.shouldBeTrue()
        publishedEvents.shouldHaveSize(1)
        eventObservedAfterCommit.shouldBeTrue()
        transaction(database) {
            Appointments.selectAll().count() shouldBeEqualTo 1L
            AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 1L
            ProxyOutboxRows.selectAll().count() shouldBeEqualTo 1L
        }
    }

    @Test
    fun `Spring proxy create writer failure는 appointment와 idempotency를 rollback한다`() {
        failWriter = true

        assertFailsWith<IllegalStateException> {
            service.create(
                tenantGroupId = tenantGroupId,
                request = request(),
                idempotencyKey = "proxy-rollback",
                resolution = MemberResolution.Resolved(MemberId("member-1")),
                commandContext = AppointmentCommandContext.root("proxy-test"),
            )
        }

        publishedEvents.shouldHaveSize(0)
        transaction(database) {
            Appointments.selectAll().count() shouldBeEqualTo 0L
            AppointmentIdempotencies.selectAll().count() shouldBeEqualTo 0L
            ProxyOutboxRows.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun transactionalProxy(
        target: AppointmentService,
        transactionManager: PlatformTransactionManager,
    ): AppointmentService {
        val interceptor = TransactionInterceptor().apply {
            setTransactionManager(transactionManager)
            setTransactionAttributeSource(AnnotationTransactionAttributeSource())
        }
        return ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(interceptor)
        }.proxy as AppointmentService
    }

    private fun request() = CreateAppointmentRequest(
        clinicId = clinicId,
        doctorId = doctorId,
        treatmentTypeId = treatmentTypeId,
        memberId = "member-1",
        patientName = "환자",
        patientPhone = "010-0000-0000",
        appointmentDate = LocalDate.of(2026, 8, 20),
        startTime = LocalTime.of(10, 0),
        endTime = LocalTime.of(10, 30),
    )
}

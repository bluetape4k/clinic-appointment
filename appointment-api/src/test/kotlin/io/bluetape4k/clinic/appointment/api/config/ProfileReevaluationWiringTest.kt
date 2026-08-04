package io.bluetape4k.clinic.appointment.api.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.api.test.API_INTEGRATION_RESOURCE
import io.bluetape4k.clinic.appointment.api.profile.ProfileAssessmentClient
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAdminService
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationAppointmentProcessor
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationDispatcher
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationEndpoint
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationHealthIndicator
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationRuntimeGate
import io.bluetape4k.clinic.appointment.api.profile.ProfileReevaluationWorker
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventObservationResult
import io.bluetape4k.clinic.appointment.event.profile.ProfileReevaluationEventObserver
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentCommitmentStatus
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentModelVersion
import io.bluetape4k.clinic.appointment.model.commitment.AppointmentOrigin
import io.bluetape4k.clinic.appointment.model.dto.ClaimProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationPriorityClass
import io.bluetape4k.clinic.appointment.model.dto.ProfileReevaluationScope
import io.bluetape4k.clinic.appointment.model.dto.UpsertProfileChange
import io.bluetape4k.clinic.appointment.model.tables.AppointmentCommitments
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationHeads
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationJobs
import io.bluetape4k.clinic.appointment.model.tables.ProfileReevaluationOutcomes
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.ProfileReevaluationRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import javax.sql.DataSource
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

@ResourceLock(value = API_INTEGRATION_RESOURCE, mode = ResourceAccessMode.READ_WRITE)
class ProfileReevaluationWiringTest {
    private var lastDataSource: HikariDataSource? = null
    private var lastDatabase: Database? = null

    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(ProfileReevaluationConfiguration::class.java)
            .withBean(MeterRegistry::class.java, Supplier { SimpleMeterRegistry() })
            .withBean(DataSource::class.java, Supplier {
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = "jdbc:h2:mem:profile_wiring_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                        driverClassName = "org.h2.Driver"
                        username = "sa"
                    },
                ).also { dataSource ->
                    seedMarker(dataSource)
                    lastDataSource = dataSource
                }
            })
            .withBean(AppointmentRepository::class.java, Supplier { AppointmentRepository() })

    @AfterEach
    fun dataSourceIsClosedBySpringContext() {
        lastDataSource?.isClosed?.shouldBeEqualTo(true)
        lastDatabase?.let { database ->
            val unregistered = try {
                TransactionManager.managerFor(database)
                false
            } catch (_: IllegalStateException) {
                true
            }
            unregistered.shouldBeTrue()
        }
    }

    @Test
    fun `기본 비활성 구성도 운영 조회와 health를 제공하지만 worker는 만들지 않는다`() {
        runner.run { context ->
            context.startupFailure shouldBeEqualTo null
            context.getBeansOfType(ProfileReevaluationRuntimeGate::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationAdminService::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationEndpoint::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationHealthIndicator::class.java).size shouldBeEqualTo 1
            context.getBeansOfType(ProfileReevaluationWorker::class.java).size shouldBeEqualTo 0
            context.getBeansOfType(ProfileReevaluationDispatcher::class.java).size shouldBeEqualTo 0
            context.getBeansOfType(ProfileReevaluationSchedulingRunner::class.java).size shouldBeEqualTo 0
            context.getBeansOfType(ProfileReevaluationEventObserver::class.java).size shouldBeEqualTo 1
        }
    }

    @Test
    fun `프로필 이벤트 observer는 API metric으로 rejected quarantine 결과를 기록한다`() {
        runner.run { context ->
            context.startupFailure shouldBeEqualTo null
            val observer = context.getBean(ProfileReevaluationEventObserver::class.java)
            val registry = context.getBean(MeterRegistry::class.java)

            observer.record(ProfileReevaluationEventObservationResult.REJECTED)

            registry.get("clinic.profile.reevaluation.events")
                .tag("result", "rejected")
                .counter()
                .count() shouldBeEqualTo 1.0
            registry.get("clinic.profile.reevaluation.events")
                .counter()
                .id.tags.map { it.key } shouldBeEqualTo listOf("result")
        }
    }

    @Test
    fun `활성 구성은 assessment와 processor가 준비된 경우에만 worker graph를 만든다`() {
        runner
            .withBean(ProfileAssessmentClient::class.java, Supplier {
                ProfileAssessmentClient { error("not called by wiring test") }
            })
            .withBean(ProfileReevaluationAppointmentProcessor::class.java, Supplier {
                ProfileReevaluationAppointmentProcessor { _, _, _, _ -> null }
            })
            .withPropertyValues(
                "appointment.profile-reevaluation.enabled=true",
                "appointment.profile-reevaluation.mutation-mode=APPLY_PROPOSED",
                "appointment.profile-reevaluation.clinic-allowlist[0]=11",
                "appointment.profile-reevaluation.assessment.base-url=https://crm.example.test",
                "appointment.profile-reevaluation.assessment.allowed-hosts[0]=crm.example.test",
            )
            .run { context ->
                context.startupFailure shouldBeEqualTo null
                context.getBeansOfType(ProfileReevaluationWorker::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(ProfileReevaluationDispatcher::class.java).size shouldBeEqualTo 1
                context.getBeansOfType(ProfileReevaluationSchedulingRunner::class.java).size shouldBeEqualTo 1
            }
    }

    @Test
    fun `활성 구성에 예약 재평가 processor가 없으면 시작을 거부한다`() {
        runner
            .withBean(ProfileAssessmentClient::class.java, Supplier {
                ProfileAssessmentClient { error("not called by wiring test") }
            })
            .withPropertyValues(
                "appointment.profile-reevaluation.enabled=true",
                "appointment.profile-reevaluation.mutation-mode=APPLY_PROPOSED",
                "appointment.profile-reevaluation.clinic-allowlist[0]=11",
                "appointment.profile-reevaluation.assessment.base-url=https://crm.example.test",
                "appointment.profile-reevaluation.assessment.allowed-hosts[0]=crm.example.test",
            )
            .run { context ->
                context.startupFailure.shouldNotBeNull()
            }
    }

    @Test
    fun `운영 repository는 실제 예약 상태로 PROPOSED 전용 작업을 분류한다`() {
        runner.run { context ->
            context.startupFailure shouldBeEqualTo null
            val database = context.getBean(Database::class.java)
            lastDatabase = database
            context.getBeansOfType(ExposedDatabaseLifecycle::class.java).size shouldBeEqualTo 1
            transaction(database) {
                exec("SELECT marker_value FROM datasource_marker") { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            } shouldBeEqualTo 223
            val repository = context.getBean(ProfileReevaluationRepository::class.java)
            val fingerprint = "a".repeat(64)
            val scope = ProfileReevaluationScope(1L, 11L, fingerprint)

            val claimed =
                transaction(database) {
                    SchemaUtils.createMissingTablesAndColumns(
                        TenantGroups,
                        Clinics,
                        Doctors,
                        TreatmentTypes,
                        Equipments,
                        ConsultationTopics,
                        Appointments,
                        AppointmentCommitments,
                        ProfileReevaluationHeads,
                        ProfileReevaluationJobs,
                        ProfileReevaluationOutcomes,
                    )
                    TenantGroups.insert {
                        it[id] = EntityID(1L, TenantGroups)
                        it[tenantCode] = "tenant-a"
                        it[displayName] = "Tenant A"
                    }
                    Clinics.insert {
                        it[id] = EntityID(11L, Clinics)
                        it[tenantGroupId] = EntityID(1L, TenantGroups)
                        it[name] = "Clinic 11"
                    }
                    Appointments.insert {
                        it[id] = EntityID(101L, Appointments)
                        it[clinicId] = EntityID(11L, Clinics)
                        it[modelVersion] = AppointmentModelVersion.COMMITMENT_V2
                        it[patientName] = "not persisted by reevaluation"
                        it[patientReferenceFingerprint] = fingerprint
                    }
                    AppointmentCommitments.insert {
                        it[id] = EntityID(1_101L, AppointmentCommitments)
                        it[appointmentId] = EntityID(101L, Appointments)
                        it[status] = AppointmentCommitmentStatus.PROPOSED
                        it[origin] = AppointmentOrigin.SYSTEM
                        it[effectivePolicySnapshotId] = 1L
                        it[version] = 1L
                    }
                    repository.upsertEvent(
                        UpsertProfileChange(
                            scope = scope,
                            revision = 1L,
                            eventId = "profile-event-1",
                            assessmentRef = "assessment/1",
                            assessmentHash = "1".repeat(64),
                            occurredAt = Instant.now().minusSeconds(1),
                            heldTarget = Duration.ofMinutes(5),
                            proposedTarget = Duration.ofMinutes(30),
                            targetPolicyRef = "policy/profile-reevaluation",
                            targetPolicyGeneration = 1L,
                        ),
                    )
                    repository.claimFairJobs(
                        ClaimProfileReevaluationJobs(
                            leaseOwner = "wiring-test",
                            limit = 1,
                            perClinicLimit = 1,
                        ),
                    ).single()
                }

            claimed.priorityClass shouldBeEqualTo ProfileReevaluationPriorityClass.PROPOSED_ONLY
        }
    }

    private fun seedMarker(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE datasource_marker (marker_value INT NOT NULL)")
                statement.execute("INSERT INTO datasource_marker(marker_value) VALUES (223)")
            }
        }
    }
}

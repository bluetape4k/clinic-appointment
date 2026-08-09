package io.bluetape4k.clinic.appointment.service

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.service.AvailableSlot
import io.bluetape4k.clinic.appointment.model.service.SlotQuery
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.model.tables.AppointmentNotes
import io.bluetape4k.clinic.appointment.model.tables.AppointmentStateHistory
import io.bluetape4k.clinic.appointment.model.tables.Appointments
import io.bluetape4k.clinic.appointment.model.tables.BreakTimes
import io.bluetape4k.clinic.appointment.model.tables.ClinicClosures
import io.bluetape4k.clinic.appointment.model.tables.ClinicDefaultBreakTimes
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.ConsultationTopics
import io.bluetape4k.clinic.appointment.model.tables.DoctorAbsences
import io.bluetape4k.clinic.appointment.model.tables.DoctorSchedules
import io.bluetape4k.clinic.appointment.model.tables.Doctors
import io.bluetape4k.clinic.appointment.model.tables.Equipments
import io.bluetape4k.clinic.appointment.model.tables.Holidays
import io.bluetape4k.clinic.appointment.model.tables.OperatingHoursTable
import io.bluetape4k.clinic.appointment.model.tables.RescheduleCandidates
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.TreatmentEquipments
import io.bluetape4k.clinic.appointment.model.tables.TreatmentTypes
import io.bluetape4k.clinic.appointment.repository.AppointmentRepository
import io.bluetape4k.clinic.appointment.repository.AppointmentStateHistoryRepository
import io.bluetape4k.clinic.appointment.repository.ClinicRepository
import io.bluetape4k.clinic.appointment.repository.DoctorRepository
import io.bluetape4k.clinic.appointment.repository.RescheduleCandidateRepository
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import io.bluetape4k.clinic.appointment.test.AbstractExposedTest
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** 동기 closure의 계산·쓰기 경계와 상한을 측정하는 H2 smoke harness입니다. */
class ClosureRescheduleServicePerformanceTest : AbstractExposedTest() {

    @Test
    fun `100건 30일 closure는 cache와 SQL budget 및 p95를 지킨다`() {
        withTables(TestDB.H2, *ALL_TABLES) {
            val fixture = insertFixture(APPOINTMENT_COUNT)
            val capture = SqlStatementCapture()
            registerInterceptor(capture)
            val samplesMillis = mutableListOf<Long>()
            val statementCounts = mutableListOf<Int>()

            repeat(WARMUP_RUNS + MEASURED_RUNS) { runIndex ->
                resetFixture(fixture.appointmentIds)
                capture.statements.clear()
                val slotQueries = mutableListOf<SlotQuery>()
                val service = closureService { query ->
                    slotQueries += query
                    emptyList()
                }
                val startedAt = System.nanoTime()
                val result = service.processClosureReschedule(
                    scope = fixture.scope,
                    closureDate = CLOSURE_DATE,
                    searchDays = SEARCH_DAYS,
                )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                result.size shouldBeEqualTo APPOINTMENT_COUNT
                slotQueries.distinctBy { it.scope to Triple(it.doctorId, it.treatmentTypeId, it.date) }
                    .size shouldBeEqualTo SEARCH_DAYS
                slotQueries.size shouldBeEqualTo SEARCH_DAYS
                capture.statements.size shouldBeLessOrEqualTo MAX_WRITE_SQL_STATEMENTS
                statementCounts += capture.statements.size
                if (runIndex >= WARMUP_RUNS) {
                    samplesMillis += elapsedMillis
                }
            }

            val p95Millis = percentile(samplesMillis, 95)
            p95Millis shouldBeLessOrEqualTo P95_MILLIS
            AppointmentStateHistory.selectAll().count() shouldBeEqualTo APPOINTMENT_COUNT.toLong()
            Appointments.selectAll()
                .count { it[Appointments.status] == AppointmentState.PENDING_RESCHEDULE }
                .toLong()
                .shouldBeEqualTo(APPOINTMENT_COUNT.toLong())
            println(
                "CLOSURE_RESCHEDULE_PERF samplesMs=$samplesMillis p95Ms=$p95Millis " +
                    "slotCallsPerKey=$SEARCH_DAYS maxWriteSql=${statementCounts.maxOrNull()}"
            )
        }
    }

    @Test
    fun `후보 2001건 경로는 세 번 모두 mutation row를 남기지 않는다`() {
        withTables(TestDB.H2, *ALL_TABLES) {
            val fixture = insertFixture(APPOINTMENT_COUNT)
            repeat(CANDIDATE_LIMIT_REPETITIONS) {
                resetFixture(fixture.appointmentIds)
                val slot = AvailableSlot(
                    date = CLOSURE_DATE.plusDays(1),
                    startTime = LocalTime.of(9, 0),
                    endTime = LocalTime.of(9, 30),
                    doctorId = fixture.doctorId,
                    remainingCapacity = 1,
                )
                assertFailsWith<IllegalArgumentException> {
                    closureService { query ->
                        listOf(slot.copy(date = query.date))
                    }.processClosureReschedule(
                        scope = fixture.scope,
                        closureDate = CLOSURE_DATE,
                        searchDays = SEARCH_DAYS,
                    )
                }
                AppointmentStateHistory.selectAll().count() shouldBeEqualTo 0L
                RescheduleCandidates.selectAll().count() shouldBeEqualTo 0L
                Appointments.selectAll()
                    .count { it[Appointments.status] != AppointmentState.CONFIRMED }
                    .toLong()
                    .shouldBeEqualTo(0L)
            }
        }
    }

    @Test
    fun `precompute 중 경쟁 writer는 mutation lock 없이 완료된다`() {
        withTables(TestDB.H2, *ALL_TABLES) {
            val fixture = insertFixture(2)
            commit()

            val precomputeStarted = CountDownLatch(1)
            val releasePrecompute = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val serviceFuture = executor.submit<Throwable?> {
                try {
                    closureService { _ ->
                        precomputeStarted.countDown()
                        releasePrecompute.await(5, TimeUnit.SECONDS).shouldBeTrue()
                        emptyList()
                    }.processClosureReschedule(
                        scope = fixture.scope,
                        closureDate = CLOSURE_DATE,
                        searchDays = 1,
                    )
                    null
                } catch (failure: Throwable) {
                    failure
                }
            }

            try {
                precomputeStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val writerStartedAt = System.nanoTime()
                val writerFuture = executor.submit<Boolean> {
                    transaction(TestDB.H2.db ?: error("H2 database is not connected")) {
                        AppointmentRepository().updateLegacyStatus(
                            scope = fixture.scope,
                            appointmentId = fixture.appointmentIds.last(),
                            expectedVersion = 0L,
                            newStatus = AppointmentState.REQUESTED,
                        )
                    }
                }
                writerFuture.get(2, TimeUnit.SECONDS).shouldBeTrue()
                val writerMillis = (System.nanoTime() - writerStartedAt) / 1_000_000
                writerMillis shouldBeLessOrEqualTo LOCK_DURATION_MILLIS
            } finally {
                releasePrecompute.countDown()
                val serviceFailure = serviceFuture.get(10, TimeUnit.SECONDS)
                (serviceFailure is IllegalStateException).shouldBeTrue()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    private fun closureService(
        findAvailableSlots: (SlotQuery) -> List<AvailableSlot>,
    ): ClosureRescheduleService =
        ClosureRescheduleService(
            slotCalculationService = SlotCalculationService(),
            appointmentRepository = AppointmentRepository(),
            rescheduleCandidateRepository = RescheduleCandidateRepository(),
            stateHistoryRepository = AppointmentStateHistoryRepository(),
            doctorRepository = DoctorRepository(),
            notificationWriter = AppointmentRescheduleNotificationWriter { _, _, _, _ -> },
            statusEventWriter = AppointmentStatusEventWriter { _, _, _, _, _ -> },
            clinicRepository = ClinicRepository(),
            findAvailableSlots = findAvailableSlots,
        )

    private fun JdbcTransaction.insertFixture(count: Int): Fixture {
        val clinicId = Clinics.insertAndGetId {
            it[name] = "Closure Performance Clinic"
            it[slotDurationMinutes] = 30
            it[maxConcurrentPatients] = 1
        }.value
        val doctorId = Doctors.insertAndGetId {
            it[Doctors.clinicId] = clinicId
            it[name] = "Performance Doctor"
        }.value
        val treatmentTypeId = TreatmentTypes.insertAndGetId {
            it[TreatmentTypes.clinicId] = clinicId
            it[name] = "Performance Treatment"
            it[defaultDurationMinutes] = 30
        }.value
        val appointmentIds = (0 until count).map { index ->
            Appointments.insertAndGetId {
                it[Appointments.clinicId] = clinicId
                it[Appointments.doctorId] = doctorId
                it[Appointments.treatmentTypeId] = treatmentTypeId
                it[patientName] = "환자-$index"
                it[patientPhone] = "010-0000-${index.toString().padStart(4, '0')}"
                it[appointmentDate] = CLOSURE_DATE
                it[startTime] = LocalTime.of(9, 0)
                it[endTime] = LocalTime.of(9, 30)
                it[status] = AppointmentState.CONFIRMED
            }.value
        }
        return Fixture(
            scope = TenantClinicScope(TenantGroups.DEFAULT_TENANT_GROUP_ID, clinicId),
            doctorId = doctorId,
            appointmentIds = appointmentIds,
        )
    }

    private fun JdbcTransaction.resetFixture(appointmentIds: List<Long>) {
        RescheduleCandidates.deleteAll()
        AppointmentStateHistory.deleteAll()
        Appointments.update({ Appointments.id inList appointmentIds }) {
            it[status] = AppointmentState.CONFIRMED
            it[version] = 0L
        }
    }

    private data class Fixture(
        val scope: TenantClinicScope,
        val doctorId: Long,
        val appointmentIds: List<Long>,
    )

    private class SqlStatementCapture : StatementInterceptor {
        val statements = mutableListOf<String>()

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            contexts.firstOrNull()?.let { context ->
                statements += context.sql(transaction).lowercase()
            }
        }
    }

    private fun percentile(values: List<Long>, percentile: Int): Long {
        val sorted = values.sorted()
        val index = (ceil(sorted.size * percentile / 100.0).toInt() - 1).coerceAtLeast(0)
        return sorted[index]
    }

    private companion object {
        private const val APPOINTMENT_COUNT = 100
        private const val SEARCH_DAYS = 30
        private const val WARMUP_RUNS = 2
        private const val MEASURED_RUNS = 10
        private const val MAX_WRITE_SQL_STATEMENTS = 2_700
        private const val P95_MILLIS = 10_000L
        private const val LOCK_DURATION_MILLIS = 2_000L
        private const val CANDIDATE_LIMIT_REPETITIONS = 3
        private val CLOSURE_DATE = LocalDate.of(2026, 8, 3)
        private val ALL_TABLES = arrayOf(
            Holidays,
            Clinics,
            ClinicDefaultBreakTimes,
            OperatingHoursTable,
            BreakTimes,
            ClinicClosures,
            Doctors,
            DoctorSchedules,
            DoctorAbsences,
            Equipments,
            TreatmentTypes,
            TreatmentEquipments,
            ConsultationTopics,
            Appointments,
            AppointmentNotes,
            AppointmentStateHistory,
            RescheduleCandidates,
        )
    }
}

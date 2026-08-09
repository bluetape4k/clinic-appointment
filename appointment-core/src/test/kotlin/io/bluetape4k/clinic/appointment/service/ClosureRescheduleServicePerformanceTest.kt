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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/** 동기 closure의 계산·쓰기 경계와 상한을 측정하는 H2 smoke harness입니다. */
class ClosureRescheduleServicePerformanceTest : AbstractExposedTest() {

    @Test
    fun `100건 30일 2000후보 closure는 cache와 합성 SQL budget 및 p95를 지킨다`() {
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
                    if (query.date < CLOSURE_DATE.plusDays(MAX_CANDIDATES_PER_APPOINTMENT + 1L)) {
                        listOf(availableSlot(query.date, fixture.doctorId))
                    } else {
                        emptyList()
                    }
                }
                val startedAt = System.nanoTime()
                val result = service.processClosureReschedule(
                    scope = fixture.scope,
                    closureDate = CLOSURE_DATE,
                    searchDays = SEARCH_DAYS,
                )
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

                result.size shouldBeEqualTo APPOINTMENT_COUNT
                result.values.sumOf { it.size } shouldBeEqualTo MAX_TOTAL_CANDIDATES
                slotQueries.distinctBy { it.scope to Triple(it.doctorId, it.treatmentTypeId, it.date) }
                    .size shouldBeEqualTo SEARCH_DAYS
                slotQueries.size shouldBeEqualTo SEARCH_DAYS
                capture.statements.size shouldBeLessOrEqualTo MAX_CORE_WRITE_SQL_STATEMENTS
                (capture.statements.size + APPOINTMENT_COUNT * MAX_STATUS_WRITER_STATEMENTS)
                    .shouldBeLessOrEqualTo(MAX_COMPOSITE_WRITE_SQL_STATEMENTS)
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
    fun `PostgreSQL write transaction의 row lock은 release 후 제한 시간 안에 수렴한다`() {
        withTables(TestDB.POSTGRESQL, *ALL_TABLES) {
            val fixture = insertFixture(2)
            commit()

            val mutationStarted = CountDownLatch(1)
            val releaseMutation = CountDownLatch(1)
            val competingUpdateStarted = CountDownLatch(1)
            val lockedAppointmentId = AtomicLong()
            val executor = Executors.newFixedThreadPool(2)
            // MultithreadingTester는 예외 수집에는 적합하지만 phase latch와 Future timeout을
            // 노출하지 않으므로, row-lock 진입/해제 순서를 증명하는 이 테스트는 bounded executor를 사용한다.
            val serviceFuture = executor.submit<Throwable?> {
                try {
                    closureService(
                        findAvailableSlots = { emptyList() },
                        statusEventWriter = AppointmentStatusEventWriter { _, appointment, _, _, _ ->
                            if (lockedAppointmentId.compareAndSet(0L, appointment.id ?: error("appointment.id"))) {
                                mutationStarted.countDown()
                                releaseMutation.await(5, TimeUnit.SECONDS).shouldBeTrue()
                            }
                        },
                    ).processClosureReschedule(
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
                mutationStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                val writerFuture = executor.submit<Boolean> {
                    transaction(TestDB.POSTGRESQL.db ?: error("PostgreSQL database is not connected")) {
                        registerInterceptor(object : StatementInterceptor {
                            override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                                if (context.sql(transaction).trimStart().startsWith("UPDATE", ignoreCase = true)) {
                                    competingUpdateStarted.countDown()
                                }
                            }
                        })
                        AppointmentRepository().updateLegacyStatus(
                            scope = fixture.scope,
                            appointmentId = lockedAppointmentId.get(),
                            expectedVersion = 0L,
                            newStatus = AppointmentState.REQUESTED,
                        )
                    }
                }
                competingUpdateStarted.await(5, TimeUnit.SECONDS).shouldBeTrue()
                assertFailsWith<TimeoutException> {
                    writerFuture.get(LOCK_OBSERVATION_MILLIS, TimeUnit.MILLISECONDS)
                }
                val releasedAt = System.nanoTime()
                releaseMutation.countDown()
                val serviceFailure = serviceFuture.get(LOCK_RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                (serviceFailure == null).shouldBeTrue()
                writerFuture.get(LOCK_RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS).shouldBeEqualTo(false)
                val releaseMillis = (System.nanoTime() - releasedAt) / 1_000_000
                releaseMillis shouldBeLessOrEqualTo LOCK_RELEASE_TIMEOUT_MILLIS
            } finally {
                releaseMutation.countDown()
                serviceFuture.cancel(true)
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS).shouldBeTrue()
            }
        }
    }

    private fun closureService(
        statusEventWriter: AppointmentStatusEventWriter = AppointmentStatusEventWriter { _, _, _, _, _ -> },
        findAvailableSlots: (SlotQuery) -> List<AvailableSlot>,
    ): ClosureRescheduleService =
        ClosureRescheduleService(
            slotCalculationService = SlotCalculationService(),
            appointmentRepository = AppointmentRepository(),
            rescheduleCandidateRepository = RescheduleCandidateRepository(),
            stateHistoryRepository = AppointmentStateHistoryRepository(),
            doctorRepository = DoctorRepository(),
            notificationWriter = AppointmentRescheduleNotificationWriter { _, _, _, _ -> },
            statusEventWriter = statusEventWriter,
            clinicRepository = ClinicRepository(),
            findAvailableSlots = findAvailableSlots,
        )

    private fun availableSlot(date: LocalDate, doctorId: Long): AvailableSlot =
        AvailableSlot(
            date = date,
            startTime = LocalTime.of(9, 0),
            endTime = LocalTime.of(9, 30),
            doctorId = doctorId,
            remainingCapacity = 1,
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
        private const val MAX_CANDIDATES_PER_APPOINTMENT = 20
        private const val MAX_TOTAL_CANDIDATES = APPOINTMENT_COUNT * MAX_CANDIDATES_PER_APPOINTMENT
        private const val MAX_CORE_WRITE_SQL_STATEMENTS = 2_400
        private const val MAX_STATUS_WRITER_STATEMENTS = 3
        private const val MAX_COMPOSITE_WRITE_SQL_STATEMENTS = 2_700
        private const val P95_MILLIS = 10_000L
        private const val LOCK_OBSERVATION_MILLIS = 250L
        private const val LOCK_RELEASE_TIMEOUT_MILLIS = 2_000L
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

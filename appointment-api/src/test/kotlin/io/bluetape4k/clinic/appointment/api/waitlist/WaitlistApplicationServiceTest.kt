package io.bluetape4k.clinic.appointment.api.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.api.security.ActorContext
import io.bluetape4k.clinic.appointment.api.security.ActorType
import io.bluetape4k.clinic.appointment.api.security.AuthenticationAssurance
import io.bluetape4k.clinic.appointment.model.identity.MemberId
import io.bluetape4k.clinic.appointment.model.waitlist.ActorRef
import io.bluetape4k.clinic.appointment.model.waitlist.CorrelationId
import io.bluetape4k.clinic.appointment.model.waitlist.OfferClaimed
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistReasonCode
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

class WaitlistApplicationServiceTest {
    private lateinit var database: Database
    private lateinit var reservationPort: FakeReservationPort
    private val claimCalls = AtomicInteger(0)
    private val replacementCalls = AtomicInteger(0)
    private val holdConsumeCalls = AtomicInteger(0)
    private var replacementFailure: RuntimeException? = null
    private var vacancyFailure: RuntimeException? = null
    private var completeSuccessFailure: RuntimeException? = null

    @BeforeEach
    fun setUp() {
        database = Database.connect(
            url = "jdbc:h2:mem:waitlist_app_${System.nanoTime()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        transaction(database) {
            SchemaUtils.create(RollbackProbe)
        }
        reservationPort = FakeReservationPort()
        claimCalls.set(0)
        replacementCalls.set(0)
        holdConsumeCalls.set(0)
        replacementFailure = null
        vacancyFailure = null
        completeSuccessFailure = null
    }

    @Test
    fun `confirm offer reserves durably and replays without duplicate replacement`() {
        val service = applicationService()
        val command = confirmCommand()

        val confirmed = service.confirmOffer(command)
        val replayed = service.confirmOffer(command)

        confirmed.appointmentId shouldBeEqualTo APPOINTMENT_ID
        confirmed.idempotentReplay shouldBeEqualTo false
        replayed.appointmentId shouldBeEqualTo APPOINTMENT_ID
        replayed.idempotentReplay shouldBeEqualTo true
        claimCalls.get() shouldBeEqualTo 1
        replacementCalls.get() shouldBeEqualTo 1
        holdConsumeCalls.get() shouldBeEqualTo 1
        reservationPort.completedSuccesses shouldBeEqualTo 1
    }

    @Test
    fun `replacement failure records stable failed replay after durable reservation`() {
        replacementFailure = IllegalStateException("replacement failed")
        val service = applicationService()
        val command = confirmCommand()

        assertFailsWith<IllegalStateException> {
            service.confirmOffer(command)
        }
        val replay =
            assertFailsWith<WaitlistApplicationConflict> {
                service.confirmOffer(command)
            }

        replay.code shouldBeEqualTo WaitlistApplicationError.IDEMPOTENT_FAILURE_REPLAY
        claimCalls.get() shouldBeEqualTo 1
        replacementCalls.get() shouldBeEqualTo 0
        holdConsumeCalls.get() shouldBeEqualTo 0
        reservationPort.completedFailures shouldBeEqualTo 1
    }

    @Test
    fun `processing replay reconciles appointment created before success record`() {
        completeSuccessFailure = IllegalStateException("success record failed")
        val service = applicationService()
        val command = confirmCommand()

        assertFailsWith<IllegalStateException> {
            service.confirmOffer(command)
        }
        val replayed = service.confirmOffer(command)

        replayed.appointmentId shouldBeEqualTo APPOINTMENT_ID
        replayed.idempotentReplay shouldBeEqualTo true
        claimCalls.get() shouldBeEqualTo 1
        replacementCalls.get() shouldBeEqualTo 1
        holdConsumeCalls.get() shouldBeEqualTo 1
        reservationPort.reconciliations shouldBeEqualTo 1
    }

    @Test
    fun `confirm command factory derives bounded command from tenant scope`() {
        val scope: TenantScope = WaitlistTenantScope(
            tenantGroupId = TENANT_ID,
            tenantCode = "clinic-a",
            clinicId = CLINIC_ID,
            actor = ActorContext(
                actorId = "gateway-subject-123",
                actorType = ActorType.STAFF,
                roles = emptySet(),
                scopes = emptySet(),
                allowedTenantCodes = setOf("clinic-a"),
                allowedClinicIds = setOf(CLINIC_ID),
                patientSubjectId = null,
                assurance = AuthenticationAssurance.MFA,
                issuer = "issuer",
                tokenId = "token-1",
                authenticatedAt = NOW,
                correlationId = "waitlist-confirm-1",
                selectedClinicId = CLINIC_ID,
            ),
            correlationId = "waitlist-confirm-1",
        )
        val command = WaitlistOfferConfirmationCommand.from(
            scope = scope,
            memberId = MemberId(MEMBER_ID),
            offerId = OFFER_ID,
            idempotencyKeyDigest = "hmac-sha256:${"9".repeat(64)}",
            command = ConfirmWaitlistOfferCommand(
                expectedVersion = 3L,
                confirmationSource = "FRONT_DESK",
                requestDigest = DIGEST_A,
            ),
            responseDigest = DIGEST_B,
        )

        command.tenantGroupId shouldBeEqualTo TENANT_ID
        command.clinicId shouldBeEqualTo CLINIC_ID
        command.expectedVersion shouldBeEqualTo 3L
        command.confirmationSource shouldBeEqualTo "FRONT_DESK"
        command.correlationId shouldBeEqualTo CorrelationId("waitlist-confirm-1")
        command.actorRef.value.startsWith("staff:") shouldBeEqualTo true
    }

    @Test
    fun `vacancy opening failure rolls back cancellation side effect`() {
        vacancyFailure = IllegalStateException("vacancy failed")

        assertFailsWith<IllegalStateException> {
            applicationService().cancelAndOpenVacancy(cancelCommand())
        }

        transaction(database) {
            RollbackProbe.selectAll().count() shouldBeEqualTo 0L
        }
    }

    private fun applicationService(): WaitlistApplicationService =
        WaitlistApplicationService(
            database = database,
            commandReservationPort = reservationPort,
            claimPort = WaitlistOfferClaimPort {
                claimCalls.incrementAndGet()
                OfferClaimed(
                    offerId = it.offerId,
                    holdId = HOLD_ID,
                    memberId = it.scope.memberId,
                    holdExpiresAt = EXPIRES_AT,
                )
            },
            replacementPlanner = WaitlistReplacementCommandPlanner { _, claim ->
                WaitlistReplacementPlan(
                    offerId = claim.offerId,
                    holdId = claim.holdId,
                    memberReference = claim.memberId.value,
                )
            },
            replacementPort = WaitlistReplacementAppointmentPort {
                replacementFailure?.let { throw it }
                replacementCalls.incrementAndGet()
                WaitlistReplacementAppointment(APPOINTMENT_ID, PROPOSAL_ID)
            },
            holdConsumptionPort = WaitlistCapacityHoldConsumptionPort { _, holdId, _ ->
                holdId shouldBeEqualTo HOLD_ID
                holdConsumeCalls.incrementAndGet()
            },
            cancellationPort = WaitlistCancellationPort { command ->
                RollbackProbe.insert {
                    it[id] = command.appointmentId
                    it[marker] = "cancelled"
                }
                WaitlistCancelledAppointment(
                    appointmentId = command.appointmentId,
                    tenantGroupId = command.tenantGroupId,
                    clinicId = command.clinicId,
                    occurredAt = NOW,
                )
            },
            vacancyOpeningPort = WaitlistVacancyOpeningPort {
                vacancyFailure?.let { throw it }
                WaitlistOpenedVacancy(vacancyJobId = 800L)
            },
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
        )

    private fun confirmCommand(
        requestDigest: String = DIGEST_A,
    ): WaitlistOfferConfirmationCommand =
        WaitlistOfferConfirmationCommand(
            tenantGroupId = TENANT_ID,
            clinicId = CLINIC_ID,
            memberId = MemberId(MEMBER_ID),
            offerId = OFFER_ID,
            expectedOfferVersion = 3L,
            idempotencyKeyDigest = "hmac-sha256:${"9".repeat(64)}",
            requestDigest = requestDigest,
            responseDigest = DIGEST_B,
            correlationId = CorrelationId("waitlist-confirm-1"),
            actorRef = ActorRef("staff:waitlist-operator"),
        )

    private fun cancelCommand(): WaitlistCancellationVacancyCommand =
        WaitlistCancellationVacancyCommand(
            tenantGroupId = TENANT_ID,
            clinicId = CLINIC_ID,
            appointmentId = APPOINTMENT_ID,
            expectedVersion = 7L,
            reason = WaitlistReasonCode("STAFF_CANCELLED"),
            correlationId = CorrelationId("waitlist-cancel-1"),
            actorRef = ActorRef("staff:waitlist-operator"),
        )

    private inner class FakeReservationPort : WaitlistCommandReservationPort {
        private var state: WaitlistApplicationReservation? = null
        var completedSuccesses: Int = 0
        var completedFailures: Int = 0
        var reconciliations: Int = 0
        var reconciledAppointmentId: Long? = null

        override fun reserve(
            command: WaitlistOfferConfirmationCommand,
            now: Instant,
        ): WaitlistApplicationReservation {
            val current = state ?: WaitlistApplicationReservation.Acquired(recordId = 1L)
            state = current
            return current
        }

        override fun reconcileInProgress(
            command: WaitlistOfferConfirmationCommand,
            now: Instant,
        ): WaitlistApplicationReservation {
            reconciliations += 1
            return reconciledAppointmentId
                ?.let(WaitlistApplicationReservation::ReplaySucceeded)
                ?: WaitlistApplicationReservation.InProgress
        }

        override fun completeSucceeded(
            recordId: Long,
            command: WaitlistOfferConfirmationCommand,
            appointmentId: Long,
            now: Instant,
        ) {
            completeSuccessFailure?.let {
                reconciledAppointmentId = appointmentId
                state = WaitlistApplicationReservation.InProgress
                completeSuccessFailure = null
                throw it
            }
            completedSuccesses += 1
            state = WaitlistApplicationReservation.ReplaySucceeded(appointmentId)
        }

        override fun completeFailed(
            recordId: Long,
            command: WaitlistOfferConfirmationCommand,
            failureCode: String,
            now: Instant,
        ) {
            completedFailures += 1
            state = WaitlistApplicationReservation.ReplayFailed(failureCode)
        }
    }

    private object RollbackProbe : Table("waitlist_application_rollback_probe") {
        val id = long("id")
        val marker = varchar("marker", 32)
    }

    private companion object {
        private const val TENANT_ID = 1L
        private const val CLINIC_ID = 10L
        private const val MEMBER_ID = "member-waitlist-app"
        private const val OFFER_ID = 100L
        private const val HOLD_ID = 200L
        private const val APPOINTMENT_ID = 300L
        private const val PROPOSAL_ID = 400L
        private val NOW: Instant = Instant.parse("2026-08-01T08:10:00Z")
        private val EXPIRES_AT: Instant = Instant.parse("2026-08-01T08:45:00Z")
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

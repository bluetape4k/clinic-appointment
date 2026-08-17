package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldStartWith
import io.bluetape4k.clinic.appointment.model.tables.Clinics
import io.bluetape4k.clinic.appointment.model.tables.TenantGroups
import io.bluetape4k.clinic.appointment.model.tables.WaitlistCommandRecords
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.IdempotencyRequestMismatch
import io.bluetape4k.clinic.appointment.repository.waitlist.CommandReservation
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistCommandDuplicateClassifier
import io.bluetape4k.clinic.appointment.repository.waitlist.WaitlistDeliveryRepository
import io.bluetape4k.clinic.appointment.service.waitlist.WaitlistCommandIdempotencyKeyHasher
import io.bluetape4k.clinic.appointment.test.TestDB
import io.bluetape4k.clinic.appointment.test.withTables
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Instant

class WaitlistCommandReservationTest {
    private val repository = WaitlistDeliveryRepository()
    private val hasher = WaitlistCommandIdempotencyKeyHasher(SECRET)

    @Test
    fun `same key and different request digest is rejected`() {
        withCommandTables {
            val key = commandKey()
            repository.reserve(key, requestDigest = DIGEST_A, now = NOW)

            assertFailsWith<IdempotencyRequestMismatch> {
                repository.reserve(key, requestDigest = DIGEST_B, now = NOW)
            }
        }
    }

    @Test
    fun `PostgreSQL command duplicate classification supports replay and mismatch authority`() {
        val postgresDuplicate = SQLException(
            "duplicate key value violates unique constraint \"uq_waitlist_command_idempotency\"",
            "23505",
        )
        val wrappedDuplicate = SQLException("outer").also { it.initCause(postgresDuplicate) }
        val genericIntegrity = SQLException(
            "duplicate key value violates unique constraint \"other_unique_key\"",
            "23505",
        )

        WaitlistCommandDuplicateClassifier.isCommandReservationDuplicate(wrappedDuplicate).shouldBeEqualTo(true)
        WaitlistCommandDuplicateClassifier.isCommandReservationDuplicate(genericIntegrity).shouldBeEqualTo(false)

        withCommandTables {
            val key = commandKey()
            val recordId = (repository.reserve(key, requestDigest = DIGEST_A, now = NOW) as CommandReservation.Acquired).recordId
            repository.completeCommandSucceeded(
                recordId = recordId,
                requestDigest = DIGEST_A,
                resultType = "OFFER",
                resultId = 70L,
                responseDigest = DIGEST_B,
                now = NOW.plusSeconds(2),
            )

            repository.reserve(key, requestDigest = DIGEST_A, now = NOW.plusSeconds(3)) shouldBeEqualTo
                CommandReservation.ReplaySucceeded(status = 200, resultBody = """{"type":"OFFER","id":70}""")
            assertFailsWith<IdempotencyRequestMismatch> {
                repository.reserve(key, requestDigest = DIGEST_B, now = NOW.plusSeconds(4))
            }
        }
    }

    @Test
    fun `reservation stores only hmac digest and keeps twenty four hour retention`() {
        withCommandTables {
            val rawKey = "retention-test-key-01"
            val key = commandKey(rawKey = rawKey)

            repository.reserve(key, requestDigest = DIGEST_A, now = NOW)

            val row = WaitlistCommandRecords.selectAll().single()
            row[WaitlistCommandRecords.keyDigest] shouldBeEqualTo key.keyDigest
            (row[WaitlistCommandRecords.keyDigest] == rawKey) shouldBeEqualTo false
            row[WaitlistCommandRecords.expiresAt] shouldBeEqualTo NOW.plusSeconds(86_400)

            repository.purgeExpiredCommands(
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                clinicId = CLINIC_ID,
                now = NOW.plusSeconds(86_399),
            ) shouldBeEqualTo 0
            repository.purgeExpiredCommands(
                tenantGroupId = TenantGroups.DEFAULT_TENANT_GROUP_ID,
                clinicId = CLINIC_ID,
                now = NOW.plusSeconds(86_400),
            ) shouldBeEqualTo 1
        }
    }

    @Test
    fun `processing succeeded and failed reservations replay deterministically`() {
        withCommandTables {
            val key = commandKey()

            val acquired = repository.reserve(key, requestDigest = DIGEST_A, now = NOW)
            val recordId = (acquired as CommandReservation.Acquired).recordId

            repository.reserve(key, requestDigest = DIGEST_A, now = NOW.plusSeconds(1)) shouldBeEqualTo
                CommandReservation.InProgress(retryAfterSeconds = 1L)

            repository.completeCommandSucceeded(
                recordId = recordId,
                requestDigest = DIGEST_A,
                resultType = "OFFER",
                resultId = 70L,
                responseDigest = DIGEST_B,
                now = NOW.plusSeconds(2),
            )

            repository.reserve(key, requestDigest = DIGEST_A, now = NOW.plusSeconds(3)) shouldBeEqualTo
                CommandReservation.ReplaySucceeded(status = 200, resultBody = """{"type":"OFFER","id":70}""")

            val failedKey = commandKey(rawKey = "failed-test-key-01")
            val failedId = (repository.reserve(failedKey, requestDigest = DIGEST_A, now = NOW) as CommandReservation.Acquired).recordId
            repository.completeCommandFailed(
                recordId = failedId,
                requestDigest = DIGEST_A,
                failureCode = "OFFER_EXPIRED",
                now = NOW.plusSeconds(2),
            )

            repository.reserve(failedKey, requestDigest = DIGEST_A, now = NOW.plusSeconds(3)) shouldBeEqualTo
                CommandReservation.ReplayFailed(status = 409, errorBody = """{"code":"OFFER_EXPIRED"}""")
        }
    }

    @Test
    fun `process loss after reservation becomes stable failed replay`() {
        withCommandTables {
            val key = commandKey()
            val acquired = repository.reserve(key, requestDigest = DIGEST_A, now = NOW)
            val recordId = (acquired as CommandReservation.Acquired).recordId

            repository.markCommandFailedIfProcessing(
                recordId = recordId,
                requestDigest = DIGEST_A,
                failureCode = "PROCESS_LOST",
                now = NOW.plusSeconds(30),
            )

            repository.reserve(key, requestDigest = DIGEST_A, now = NOW.plusSeconds(31)) shouldBeEqualTo
                CommandReservation.ReplayFailed(status = 409, errorBody = """{"code":"PROCESS_LOST"}""")
        }
    }

    @Test
    fun `offer result can be reconciled after result storage loss`() {
        withCommandTables {
            val key = commandKey()
            val recordId = (repository.reserve(key, requestDigest = DIGEST_A, now = NOW) as CommandReservation.Acquired).recordId

            repository.reconcileCommandSucceeded(
                key = key,
                requestDigest = DIGEST_A,
                resultType = "OFFER",
                resultId = 80L,
                responseDigest = DIGEST_B,
                now = NOW.plusSeconds(5),
            ).shouldBeEqualTo(true)

            repository.reserve(key, requestDigest = DIGEST_A, now = NOW.plusSeconds(6)) shouldBeEqualTo
                CommandReservation.ReplaySucceeded(status = 200, resultBody = """{"type":"OFFER","id":80}""")

            repository.completeCommandFailed(
                recordId = recordId,
                requestDigest = DIGEST_A,
                failureCode = "LATE_FAILURE",
                now = NOW.plusSeconds(7),
            ).shouldBeEqualTo(false)
        }
    }

    @Test
    fun `idempotency key hashing validates ascii length secret and separates scope and command`() {
        assertFailsWith<IllegalArgumentException> {
            WaitlistCommandIdempotencyKeyHasher("too-short-secret".toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            hasher.hash(scope(), commandType = "CONFIRM_OFFER", rawKey = "short-key")
        }
        assertFailsWith<IllegalArgumentException> {
            hasher.hash(scope(), commandType = "CONFIRM_OFFER", rawKey = "한글-key-1234567890123")
        }

        val lowerBound = "0".repeat(16)
        val upperBound = "1".repeat(128)
        hasher.hash(scope(), commandType = "CONFIRM_OFFER", rawKey = lowerBound).keyDigest shouldStartWith "hmac-sha256:"
        hasher.hash(scope(), commandType = "CONFIRM_OFFER", rawKey = upperBound).keyDigest shouldStartWith "hmac-sha256:"

        val base = hasher.hash(scope(), commandType = "CONFIRM_OFFER", rawKey = lowerBound)
        val otherClinic = hasher.hash(scope(clinicId = 11L), commandType = "CONFIRM_OFFER", rawKey = lowerBound)
        val otherCommand = hasher.hash(scope(), commandType = "DECLINE_OFFER", rawKey = lowerBound)

        base.keyDigest.length shouldBeEqualTo 76
        base.keyDigest shouldBeEqualTo base.keyDigest.lowercase()
        (base.keyDigest == lowerBound) shouldBeEqualTo false
        (base.keyDigest == otherClinic.keyDigest) shouldBeEqualTo false
        (base.keyDigest == otherCommand.keyDigest) shouldBeEqualTo false
        WaitlistCommandIdempotencyKeyHasher.PROPERTY_NAME shouldBeEqualTo
            "appointment.waitlist.idempotency-hmac-secret"
    }

    private fun withCommandTables(block: org.jetbrains.exposed.v1.jdbc.JdbcTransaction.() -> Unit) {
        withTables(
            TestDB.POSTGRESQL,
            Clinics,
            WaitlistCommandRecords,
        ) {
            Clinics.insert {
                it[id] = EntityID(CLINIC_ID, Clinics)
                it[tenantGroupId] = EntityID(TenantGroups.DEFAULT_TENANT_GROUP_ID, TenantGroups)
                it[name] = "Waitlist Command Clinic"
                it[slotDurationMinutes] = 30
                it[maxConcurrentPatients] = 1
            }
            block()
        }
    }

    private fun commandKey(rawKey: String = "0123456789abcdef"): io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandKey =
        hasher.hash(scope(), commandType = "CONFIRM_OFFER", rawKey = rawKey)

    private fun scope(
        tenantGroupId: Long = TenantGroups.DEFAULT_TENANT_GROUP_ID,
        clinicId: Long = CLINIC_ID,
    ): ClinicWaitlistScope =
        ClinicWaitlistScope(tenantGroupId, clinicId)

    private companion object {
        private const val CLINIC_ID = 10L
        private val NOW: Instant = Instant.parse("2026-08-01T08:00:00Z")
        private val SECRET: ByteArray = "0123456789abcdef0123456789abcdef".toByteArray()
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

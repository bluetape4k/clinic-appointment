package io.bluetape4k.clinic.appointment.waitlist

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.clinic.appointment.model.waitlist.ClinicWaitlistScope
import io.bluetape4k.clinic.appointment.model.waitlist.IdempotencyRequestMismatch
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyGenerationConflict
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyJobState
import io.bluetape4k.clinic.appointment.model.waitlist.VacancyLease
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandKey
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistCommandState
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistContention
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistDeliveryException
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyConflict
import io.bluetape4k.clinic.appointment.model.waitlist.WaitlistPolicyState
import java.time.Instant
import org.junit.jupiter.api.Test

class WaitlistDeliveryModelTest {

    @Test
    fun `delivery states expose the V19 closed lifecycle vocabularies`() {
        VacancyJobState.entries shouldBeEqualTo listOf(
            VacancyJobState.READY,
            VacancyJobState.PROCESSING,
            VacancyJobState.OFFERED,
            VacancyJobState.NO_CANDIDATE,
            VacancyJobState.EXPIRED,
            VacancyJobState.FAILED,
        )
        WaitlistCommandState.entries shouldBeEqualTo listOf(
            WaitlistCommandState.PROCESSING,
            WaitlistCommandState.SUCCEEDED,
            WaitlistCommandState.FAILED,
        )
        WaitlistPolicyState.entries shouldBeEqualTo listOf(
            WaitlistPolicyState.DRAFT,
            WaitlistPolicyState.ACTIVE,
            WaitlistPolicyState.RETIRED,
        )
    }

    @Test
    fun `vacancy lease validates owner version and strict expiry fence`() {
        val lease = VacancyLease(
            owner = "worker-a",
            version = 3L,
            expiresAt = Instant.parse("2026-08-03T10:00:30Z"),
        )

        lease.isValid("worker-a", 3L, Instant.parse("2026-08-03T10:00:29Z")).shouldBeTrue()
        lease.isValid("worker-a", 3L, Instant.parse("2026-08-03T10:00:30Z")).shouldBeFalse()
        lease.isValid("worker-b", 3L, Instant.parse("2026-08-03T10:00:29Z")).shouldBeFalse()
        lease.isValid("worker-a", 4L, Instant.parse("2026-08-03T10:00:29Z")).shouldBeFalse()

        assertFailsWith<IllegalArgumentException> {
            lease.copy(owner = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            lease.copy(version = -1L)
        }
    }

    @Test
    fun `command key requires positive scope command type and waitlist hmac digest`() {
        val key = WaitlistCommandKey(
            tenantGroupId = 1L,
            clinicId = 2L,
            commandType = "CREATE_WAITLIST_ENTRY",
            keyDigest = "hmac-sha256:" + "a".repeat(64),
        )

        key.commandType shouldBeEqualTo "CREATE_WAITLIST_ENTRY"

        assertFailsWith<IllegalArgumentException> {
            key.copy(tenantGroupId = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            key.copy(clinicId = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            key.copy(commandType = "")
        }
        assertFailsWith<IllegalArgumentException> {
            key.copy(keyDigest = "sha256:" + "a".repeat(64))
        }
    }

    @Test
    fun `clinic waitlist scope rejects non positive identifiers`() {
        ClinicWaitlistScope(tenantGroupId = 1L, clinicId = 2L).clinicId shouldBeEqualTo 2L

        assertFailsWith<IllegalArgumentException> {
            ClinicWaitlistScope(tenantGroupId = 0L, clinicId = 2L)
        }
        assertFailsWith<IllegalArgumentException> {
            ClinicWaitlistScope(tenantGroupId = 1L, clinicId = 0L)
        }
    }

    @Test
    fun `delivery exceptions keep typed stable failure classes`() {
        val failures: List<WaitlistDeliveryException> = listOf(
            VacancyGenerationConflict(),
            WaitlistPolicyConflict(),
            IdempotencyRequestMismatch(),
            WaitlistContention(),
        )

        failures.map { it.message } shouldBeEqualTo listOf(
            "VACANCY_GENERATION_CONFLICT",
            "POLICY_CONFLICT",
            "IDEMPOTENCY_REQUEST_MISMATCH",
            "WAITLIST_CONTENTION",
        )
    }
}

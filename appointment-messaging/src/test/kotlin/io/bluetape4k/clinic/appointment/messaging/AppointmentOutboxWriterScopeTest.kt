package io.bluetape4k.clinic.appointment.messaging

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.clinic.appointment.event.integration.SchedulingOutboxEvents
import io.bluetape4k.clinic.appointment.model.service.TenantClinicScope
import io.bluetape4k.clinic.appointment.statemachine.AppointmentState
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** appointment outbox 행을 쓰기 전에 scope/identity 위조가 실패해야 한다. */
class AppointmentOutboxWriterScopeTest {
    private val support = AppointmentOutboxWriterScopeTestSupport

    @BeforeEach
    fun setup() {
        support.connectAndCreateFixture()
    }

    @Test
    fun `forged tenant clinic and record identities are rejected with no side effects`() {
        val original = support.appointment(
            AppointmentOutboxWriterScopeTestSupport.ORIGINAL_APPOINTMENT,
            support.validScope,
        )
        val forgedRecord = original.copy(clinicId = AppointmentOutboxWriterScopeTestSupport.CLINIC_TWO)
        val writer = support.writer()
        val cases = listOf<NamedOperation>(
            NamedOperation("forged tenant for a valid clinic") {
                writer.created(
                    TenantClinicScope(
                        AppointmentOutboxWriterScopeTestSupport.TENANT_TWO,
                        AppointmentOutboxWriterScopeTestSupport.CLINIC_ONE,
                    ),
                    original,
                    support.context("forged-tenant"),
                )
            },
            NamedOperation("forged clinic in the valid tenant") {
                writer.created(
                    TenantClinicScope(
                        AppointmentOutboxWriterScopeTestSupport.TENANT_ONE,
                        AppointmentOutboxWriterScopeTestSupport.CLINIC_TWO,
                    ),
                    original,
                    support.context("forged-clinic"),
                )
            },
            NamedOperation("forged clinic field on created record") {
                writer.created(support.validScope, forgedRecord, support.context("forged-record-created"))
            },
            NamedOperation("forged clinic field on status transition") {
                writer.statusChanged(
                    support.validScope,
                    forgedRecord,
                    AppointmentState.REQUESTED,
                    support.context("forged-record-status"),
                )
            },
            NamedOperation("forged clinic field on cancellation") {
                writer.cancelled(support.validScope, forgedRecord, support.context("forged-record-cancelled"))
            },
        )

        cases.forEach { operation ->
            assertFailsWith<IllegalArgumentException> {
                transaction { operation.invoke() }
            }
            support.outboxCount() shouldBeEqualTo 0L
        }
    }

    @Test
    fun `reschedule rejects cross clinic and forged replacement identities atomically`() {
        val original = support.appointment(
            AppointmentOutboxWriterScopeTestSupport.ORIGINAL_APPOINTMENT,
            support.validScope,
        )
        val replacement = support.appointment(
            AppointmentOutboxWriterScopeTestSupport.REPLACEMENT_APPOINTMENT,
            TenantClinicScope(
                AppointmentOutboxWriterScopeTestSupport.TENANT_ONE,
                AppointmentOutboxWriterScopeTestSupport.CLINIC_TWO,
            ),
        )
        val forgedReplacement = original.copy(clinicId = AppointmentOutboxWriterScopeTestSupport.CLINIC_TWO)

        val cases = listOf<NamedOperation>(
            NamedOperation("replacement belongs to another clinic") {
                support.writer("reschedule-cross-clinic").rescheduled(
                    support.validScope,
                    original,
                    replacement,
                    support.context("replacement-cross-clinic"),
                )
            },
            NamedOperation("replacement record forges clinic while retaining original id") {
                support.writer("reschedule-forged-replacement").rescheduled(
                    support.validScope,
                    original,
                    forgedReplacement,
                    support.context("replacement-forged-clinic"),
                )
            },
            NamedOperation("original scope is forged to another tenant") {
                support.writer("reschedule-forged-tenant").rescheduled(
                    TenantClinicScope(
                        AppointmentOutboxWriterScopeTestSupport.TENANT_TWO,
                        AppointmentOutboxWriterScopeTestSupport.CLINIC_OTHER_TENANT,
                    ),
                    original,
                    forgedReplacement,
                    support.context("reschedule-forged-tenant"),
                )
            },
        )

        cases.forEach { operation ->
            assertFailsWith<IllegalArgumentException> {
                transaction { operation.invoke() }
            }
            support.outboxCount() shouldBeEqualTo 0L
        }
    }

    private data class NamedOperation(
        val name: String,
        val invoke: () -> Unit,
    )

}
